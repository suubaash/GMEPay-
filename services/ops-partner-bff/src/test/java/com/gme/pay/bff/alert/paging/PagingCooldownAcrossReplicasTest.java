package com.gme.pay.bff.alert.paging;

import com.gme.pay.bff.alert.InMemoryOpsAlertStore;

import com.gme.pay.bff.alert.OpsAlertStore;
import com.gme.pay.bff.alert.OpsAlertView;
import com.gme.pay.contracts.events.OpsAlertPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>Two BFF replicas must page a human once, not twice.</b>
 *
 * <p>The escalation sweep runs on every replica (deliberately — see
 * {@link OpsPagingEscalationScheduler}), so the shared cooldown is the only thing between "N
 * replicas noticed the same un-acked CRITICAL alert" and "N pages". Each scenario below is run
 * against a shared cooldown and against two per-JVM ones, and the per-JVM pair must get it wrong.
 *
 * <p>Redis is stood in for by two {@link StringRedisTemplate} handles over one map, with faithful
 * {@code SET NX EX} / {@code DEL} semantics. There is no Docker on this machine and the property
 * under test is not the wire protocol — it is that one atomic claim decides.
 */
class PagingCooldownAcrossReplicasTest {

    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-30T12:00:00Z"), ZoneOffset.UTC);

    // ------------------------------------------------------------------ the headline property

    @Test
    @DisplayName("two replicas escalating the same alert page ONCE between them")
    void twoReplicasPageOnce() {
        FakeRedis redis = new FakeRedis();
        CountingPager pagerA = new CountingPager();
        CountingPager pagerB = new CountingPager();
        OpsPagingDispatcher a = dispatcher(pagerA, sharedCooldown(redis));
        OpsPagingDispatcher b = dispatcher(pagerB, sharedCooldown(redis));

        OpsAlertView alert = critical("UNCERTAIN_AGED", "TXN-1");
        a.escalate(alert);
        b.escalate(alert);

        assertThat(pagerA.count.get() + pagerB.count.get())
                .as("the cooldown is claimed atomically, so the second replica's escalation tick "
                        + "is a no-op instead of a second page to a human at 03:00")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("two replicas with per-JVM cooldowns page TWICE (the defect this closes)")
    void perJvmCooldownsPageTwice() {
        CountingPager pagerA = new CountingPager();
        CountingPager pagerB = new CountingPager();
        OpsPagingDispatcher a = dispatcher(pagerA, new InMemoryPagingCooldown(CLOCK));
        OpsPagingDispatcher b = dispatcher(pagerB, new InMemoryPagingCooldown(CLOCK));

        OpsAlertView alert = critical("UNCERTAIN_AGED", "TXN-1");
        a.escalate(alert);
        b.escalate(alert);

        assertThat(pagerA.count.get() + pagerB.count.get())
                .as("each replica's map is empty, so each one pages: N replicas, N pages per "
                        + "escalation tick")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("an alert consumed on A and re-fired onto B is SUPPRESSED on B")
    void reFiredAlertOnAnotherReplicaIsSuppressed() {
        FakeRedis redis = new FakeRedis();
        CountingPager pagerB = new CountingPager();
        OpsPagingDispatcher a = dispatcher(new CountingPager(), sharedCooldown(redis));
        OpsPagingDispatcher b = dispatcher(pagerB, sharedCooldown(redis));

        a.onStored(critical("STUCK_TXN", "TXN-9"));
        OpsAlertView onB = b.onStored(critical("STUCK_TXN", "TXN-9"));

        assertThat(pagerB.count.get()).isZero();
        assertThat(onB.paging()).isNotNull();
        assertThat(onB.paging().status())
                .as("recorded as SUPPRESSED so the operator can see the page was considered")
                .isEqualTo("SUPPRESSED");
    }

    // ------------------------------------------------------------------ claim / release

    @Test
    @DisplayName("a FAILED delivery releases the claim, so another replica may retry")
    void failedDeliveryReleasesTheClaim() {
        FakeRedis redis = new FakeRedis();
        CountingPager failing = new CountingPager(false);
        CountingPager working = new CountingPager(true);
        OpsPagingDispatcher a = dispatcher(failing, sharedCooldown(redis));
        OpsPagingDispatcher b = dispatcher(working, sharedCooldown(redis));

        OpsAlertView alert = critical("UNCERTAIN_AGED", "TXN-2");
        OpsAlertView afterA = a.escalate(alert);
        assertThat(afterA.paging().status()).isEqualTo("FAILED");

        OpsAlertView afterB = b.escalate(alert);

        assertThat(working.count.get())
                .as("only a DELIVERED page opens the cooldown — a failed attempt must not silence "
                        + "the key for the whole 15-minute window")
                .isEqualTo(1);
        assertThat(afterB.paging().status()).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("a throwing paging port releases the claim too")
    void throwingPortReleasesTheClaim() {
        FakeRedis redis = new FakeRedis();
        PagingCooldown cooldown = sharedCooldown(redis);
        OpsPagingDispatcher a = dispatcher(alert -> {
            throw new IllegalStateException("pager exploded");
        }, cooldown);

        assertThatThrownBy(() -> a.escalate(critical("UNCERTAIN_AGED", "TXN-3")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(cooldown.tryClaim("UNCERTAIN_AGED|TXN-3", WINDOW))
                .as("an exception must not leave the key silenced")
                .isTrue();
    }

    @Test
    @DisplayName("a delivered page holds the cooldown for the window on BOTH replicas")
    void deliveredPageHoldsTheWindow() {
        FakeRedis redis = new FakeRedis();
        PagingCooldown a = sharedCooldown(redis);
        PagingCooldown b = sharedCooldown(redis);

        assertThat(a.tryClaim("k", WINDOW)).isTrue();
        assertThat(a.tryClaim("k", WINDOW)).isFalse();
        assertThat(b.tryClaim("k", WINDOW)).isFalse();
    }

    // ------------------------------------------------------------------ failure posture

    @Test
    @DisplayName("an unavailable Redis DEGRADES dedupe — it never suppresses a page")
    void unavailableRedisNeverSuppresses() {
        FakeRedis redis = new FakeRedis();
        redis.failWith(new IllegalStateException("Unable to connect to Redis"));
        CountingPager pager = new CountingPager();
        OpsPagingDispatcher dispatcher = dispatcher(pager, sharedCooldown(redis));

        dispatcher.escalate(critical("UNCERTAIN_AGED", "TXN-4"));

        assertThat(pager.count.get())
                .as("failing CLOSED here would silence the pager during an incident — the one "
                        + "moment it exists for. This control authorises nothing, so its failure "
                        + "mode is noisier, not quieter (the inverse of the api-gateway's).")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("after failover the per-JVM cooldown still dedupes within the replica")
    void failoverStillDedupesLocally() {
        FakeRedis redis = new FakeRedis();
        redis.failWith(new IllegalStateException("Unable to connect to Redis"));
        CountingPager pager = new CountingPager();
        OpsPagingDispatcher dispatcher = dispatcher(pager, sharedCooldown(redis));

        OpsAlertView alert = critical("UNCERTAIN_AGED", "TXN-5");
        dispatcher.escalate(alert);
        dispatcher.escalate(alert);

        assertThat(pager.count.get())
                .as("degraded to per-replica dedupe, not to no dedupe at all")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an absent Redis reply is read as 'page it', not 'already paged'")
    void absentReplyPages() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);

        assertThat(new RedisPagingCooldown(template).tryClaim("k", WINDOW))
                .as("'I do not know whether this was already paged' must resolve to paging")
                .isTrue();
    }

    // ------------------------------------------------------------------ selection

    @Test
    @DisplayName("cooldown-store decision table, including refuse-to-start")
    void selectionDecisionTable() {
        assertThat(PagingCooldownConfig.useRedis(env("auto", "redis"))).isTrue();
        assertThat(PagingCooldownConfig.useRedis(env(null, "redis"))).isTrue();
        assertThat(PagingCooldownConfig.useRedis(env("auto", null))).isFalse();
        assertThat(PagingCooldownConfig.useRedis(env("memory", "redis"))).isFalse();

        assertThatThrownBy(() -> PagingCooldownConfig.useRedis(env("redis", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ops-partner-bff refuses to start")
                .hasMessageContaining("once per replica");
        assertThatThrownBy(() -> PagingCooldownConfig.useRedis(env("shared", "redis")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ops-partner-bff refuses to start");
    }

    @Test
    @DisplayName("Redis is always wrapped in the failover decorator — never used bare")
    void redisIsAlwaysWrapped() {
        PagingCooldown built = new PagingCooldownConfig()
                .pagingCooldown(env("redis", "redis"), provider(mock(StringRedisTemplate.class)));

        assertThat(built)
                .as("a bare RedisPagingCooldown would let a store error propagate into the Kafka "
                        + "consumer and lose the alert")
                .isInstanceOf(FailoverPagingCooldown.class);
        assertThat(new PagingCooldownConfig().pagingCooldown(env("memory", null), provider(null)))
                .isInstanceOf(InMemoryPagingCooldown.class);
    }

    // ------------------------------------------------------------------ helpers

    private static PagingCooldown sharedCooldown(FakeRedis redis) {
        return new FailoverPagingCooldown(
                new RedisPagingCooldown(redis.template()), new InMemoryPagingCooldown(CLOCK));
    }

    private static OpsPagingDispatcher dispatcher(PagingPort port, PagingCooldown cooldown) {
        return new OpsPagingDispatcher(port, new InMemoryOpsAlertStore(200), cooldown,
                "CRITICAL", WINDOW, "", CLOCK);
    }

    private static OpsAlertView critical(String type, String subjectRef) {
        // OpsAlertView.from is package-private; the store is the public way to mint a view.
        return new InMemoryOpsAlertStore(200).add(new OpsAlertPayload(OpsAlertPayload.EVENT_TYPE,
                type, "CRITICAL", subjectRef, "detail", "2026-07-30T11:00:00Z"));
    }

    private static Environment env(String store, String redisHost) {
        MockEnvironment environment = new MockEnvironment();
        if (store != null) {
            environment.setProperty(PagingCooldownConfig.STORE_PROPERTY, store);
        }
        if (redisHost != null) {
            environment.setProperty(PagingCooldownConfig.REDIS_HOST_PROPERTY, redisHost);
        }
        return environment;
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> provider(
            StringRedisTemplate value) {
        org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> p =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    /** Counts pages; {@code delivered} controls whether the cooldown is kept or released. */
    private static final class CountingPager implements PagingPort {
        private final AtomicInteger count = new AtomicInteger();
        private final boolean delivered;

        private CountingPager() {
            this(true);
        }

        private CountingPager(boolean delivered) {
            this.delivered = delivered;
        }

        @Override
        public PageOutcome page(PageRequest request) {
            count.incrementAndGet();
            return delivered
                    ? new PageOutcome(true, "test", null)
                    : new PageOutcome(false, "test", "delivery refused");
        }
    }

    /** One in-process Redis behind several template handles. */
    private static final class FakeRedis {
        private final Map<String, Instant> keys = new ConcurrentHashMap<>();
        private volatile RuntimeException fault;

        void failWith(RuntimeException e) {
            this.fault = e;
        }

        @SuppressWarnings("unchecked")
        StringRedisTemplate template() {
            StringRedisTemplate template = mock(StringRedisTemplate.class);
            ValueOperations<String, String> ops = mock(ValueOperations.class);
            when(template.opsForValue()).thenReturn(ops);
            when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                    .thenAnswer(inv -> {
                        raise();
                        return setIfAbsent(inv.getArgument(0), inv.getArgument(2));
                    });
            when(template.delete(anyString())).thenAnswer(inv -> {
                raise();
                return keys.remove(inv.<String>getArgument(0)) != null;
            });
            return template;
        }

        private void raise() {
            RuntimeException f = fault;
            if (f != null) {
                throw f;
            }
        }

        private synchronized Boolean setIfAbsent(String key, Duration ttl) {
            Instant now = CLOCK.instant();
            keys.values().removeIf(expiry -> !expiry.isAfter(now));
            if (keys.containsKey(key)) {
                return Boolean.FALSE;
            }
            keys.put(key, now.plus(ttl));
            return Boolean.TRUE;
        }
    }
}
