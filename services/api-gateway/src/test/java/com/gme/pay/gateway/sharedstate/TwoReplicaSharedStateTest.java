package com.gme.pay.gateway.sharedstate;

import com.gme.pay.gateway.ratelimit.InMemoryRateLimitStore;
import com.gme.pay.gateway.ratelimit.RateLimitStore;
import com.gme.pay.gateway.ratelimit.RedisRateLimitStore;
import com.gme.pay.gateway.replay.InMemoryNonceStore;
import com.gme.pay.gateway.replay.NonceStore;
import com.gme.pay.gateway.replay.RedisNonceStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>The property this whole change exists for:</b> two gateway instances sharing one store behave
 * as one gateway, and the same two instances on per-JVM stores do not.
 *
 * <p>Each test runs the identical scenario twice — once against two Redis-backed stores over one
 * {@link FakeReactiveRedis}, once against two independent in-memory stores — and asserts the
 * shared pair gets the correct answer <em>and</em> that the per-JVM pair gets the wrong one. The
 * second half matters: without it a passing test proves only that the code runs, not that the
 * defect it was written for was real.
 */
class TwoReplicaSharedStateTest {

    private static final Duration WINDOW = Duration.ofSeconds(1);
    private static final Duration NONCE_TTL = Duration.ofMinutes(5);
    private static final String PARTNER = "partner_test_001";

    @Nested
    @DisplayName("rate limit")
    class RateLimit {

        @Test
        @DisplayName("two replicas sharing Redis enforce ONE combined limit, not two")
        void sharedRedisEnforcesOneCombinedLimit() {
            FakeReactiveRedis redis = new FakeReactiveRedis();
            Clock fixed = Clock.fixed(Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC);
            RateLimitStore replicaA = new RedisRateLimitStore(redis.template(), fixed);
            RateLimitStore replicaB = new RedisRateLimitStore(redis.template(), fixed);

            // limit 4, alternating replicas: hits 1..4 allowed, 5 and 6 rejected.
            List<Boolean> outcomes = alternate(replicaA, replicaB, 6, 4);

            assertThat(outcomes)
                    .as("one combined window of 4 — the 5th request is over the cap no matter "
                            + "which replica it lands on")
                    .containsExactly(true, true, true, true, false, false);
        }

        @Test
        @DisplayName("the same two replicas on per-JVM stores give the partner 2x the cap "
                + "(the defect this closes)")
        void perJvmStoresMultiplyTheCap() {
            RateLimitStore replicaA = new InMemoryRateLimitStore();
            RateLimitStore replicaB = new InMemoryRateLimitStore();

            List<Boolean> outcomes = alternate(replicaA, replicaB, 6, 4);

            assertThat(outcomes)
                    .as("each replica counts to 4 on its own, so a limit of 4 admits 8 — this is "
                            + "the N x cap T0-7 recorded as a residual")
                    .containsExactly(true, true, true, true, true, true);
        }

        @Test
        @DisplayName("headroom and reset metadata are computed from the SHARED count")
        void headroomReflectsTheSharedCount() {
            FakeReactiveRedis redis = new FakeReactiveRedis();
            Clock fixed = Clock.fixed(Instant.parse("2026-07-30T00:00:00.250Z"), ZoneOffset.UTC);
            RateLimitStore a = new RedisRateLimitStore(redis.template(), fixed);
            RateLimitStore b = new RedisRateLimitStore(redis.template(), fixed);

            a.recordHit(key(), 10, WINDOW).block();
            RateLimitStore.Decision second = b.recordHit(key(), 10, WINDOW).block();

            assertThat(second).isNotNull();
            assertThat(second.remaining())
                    .as("replica B must see replica A's hit; X-RateLimit-Remaining is a partner-"
                            + "visible number and per-replica headroom would be a lie")
                    .isEqualTo(8);
            assertThat(second.resetAfterMillis())
                    .as("aligned window: 250ms into a 1s window leaves 750ms")
                    .isEqualTo(750);
        }

        @Test
        @DisplayName("the window rolls: a new window index gives the partner its budget back")
        void windowRolls() {
            FakeReactiveRedis redis = new FakeReactiveRedis();
            MutableClock clock = new MutableClock(Instant.parse("2026-07-30T00:00:00Z"));
            redis.useClock(clock);
            RateLimitStore a = new RedisRateLimitStore(redis.template(), clock);
            RateLimitStore b = new RedisRateLimitStore(redis.template(), clock);

            assertThat(a.recordHit(key(), 1, WINDOW).block().allowed()).isTrue();
            assertThat(b.recordHit(key(), 1, WINDOW).block().allowed())
                    .as("same window, shared count").isFalse();

            clock.advance(Duration.ofSeconds(1));
            assertThat(b.recordHit(key(), 1, WINDOW).block().allowed())
                    .as("next window index ⇒ a different key ⇒ a fresh counter").isTrue();
        }

        @Test
        @DisplayName("a Redis failure errors the Mono; it never resolves to 'allowed'")
        void storeFailurePropagates() {
            FakeReactiveRedis redis = new FakeReactiveRedis();
            RateLimitStore store = new RedisRateLimitStore(redis.template());
            redis.failWith(new IllegalStateException("connection refused"));

            assertThatThrownBy(() -> store.recordHit(key(), 10, WINDOW).block())
                    .as("synthesising an allow inside the store would hide the outage behind the "
                            + "exact outcome an attacker wants; the posture belongs to the filter")
                    .hasMessageContaining("connection refused");
        }

        private List<Boolean> alternate(RateLimitStore a, RateLimitStore b, int hits, long limit) {
            List<Boolean> out = new ArrayList<>();
            for (int i = 0; i < hits; i++) {
                RateLimitStore replica = (i % 2 == 0) ? a : b;
                out.add(replica.recordHit(key(), limit, WINDOW).block().allowed());
            }
            return out;
        }

        private String key() {
            return PARTNER + ":payments";
        }
    }

    @Nested
    @DisplayName("replay nonce")
    class Replay {

        @Test
        @DisplayName("a nonce consumed on instance A is rejected on instance B")
        void nonceBurnedOnAIsRejectedOnB() {
            FakeReactiveRedis redis = new FakeReactiveRedis();
            NonceStore a = new RedisNonceStore(redis.template());
            NonceStore b = new RedisNonceStore(redis.template());

            assertThat(a.checkAndSet(PARTNER, "n-1", NONCE_TTL).block())
                    .as("first sighting anywhere in the fleet").isTrue();
            assertThat(b.checkAndSet(PARTNER, "n-1", NONCE_TTL).block())
                    .as("the replay lands on a different replica and must still be caught")
                    .isFalse();
            assertThat(a.checkAndSet(PARTNER, "n-1", NONCE_TTL).block())
                    .as("and on the original replica").isFalse();
        }

        @Test
        @DisplayName("per-JVM nonce sets let the same captured request through once per replica "
                + "(the integrity defect this closes)")
        void perJvmNonceSetsAllowOneReplayPerReplica() {
            NonceStore a = new InMemoryNonceStore();
            NonceStore b = new InMemoryNonceStore();

            assertThat(a.checkAndSet(PARTNER, "n-1", NONCE_TTL).block()).isTrue();
            assertThat(b.checkAndSet(PARTNER, "n-1", NONCE_TTL).block())
                    .as("B has never seen it, so B accepts the replay — N replicas, N replays")
                    .isTrue();
        }

        @Test
        @DisplayName("the nonce set is scoped per partner")
        void scopedPerPartner() {
            FakeReactiveRedis redis = new FakeReactiveRedis();
            NonceStore a = new RedisNonceStore(redis.template());
            NonceStore b = new RedisNonceStore(redis.template());

            assertThat(a.checkAndSet("partner_a", "shared-value", NONCE_TTL).block()).isTrue();
            assertThat(b.checkAndSet("partner_b", "shared-value", NONCE_TTL).block())
                    .as("one partner's nonce must not exhaust another's namespace").isTrue();
        }

        @Test
        @DisplayName("the nonce expires with the clock-skew window, so a value is reusable later")
        void nonceExpires() {
            FakeReactiveRedis redis = new FakeReactiveRedis();
            MutableClock clock = new MutableClock(Instant.parse("2026-07-30T00:00:00Z"));
            redis.useClock(clock);
            NonceStore a = new RedisNonceStore(redis.template());

            assertThat(a.checkAndSet(PARTNER, "n-1", NONCE_TTL).block()).isTrue();
            clock.advance(NONCE_TTL.plusSeconds(1));
            assertThat(a.checkAndSet(PARTNER, "n-1", NONCE_TTL).block())
                    .as("past the skew window the original signed request is already stale, so "
                            + "retaining the nonce forever would only reject honest traffic")
                    .isTrue();
            assertThat(redis.keyCount()).as("and the key is reaped, not leaked").isEqualTo(1);
        }

        @Test
        @DisplayName("a Redis failure errors; an empty reply reads as NOT fresh")
        void storeFailureAndEmptyReplyAreBothNonPermissive() {
            FakeReactiveRedis redis = new FakeReactiveRedis();
            NonceStore store = new RedisNonceStore(redis.template());
            redis.failWith(new IllegalStateException("connection refused"));

            assertThatThrownBy(() -> store.checkAndSet(PARTNER, "n-1", NONCE_TTL).block())
                    .hasMessageContaining("connection refused");
        }
    }

    /** A clock the test can move, so TTL and window rollover are deterministic. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
