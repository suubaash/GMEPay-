package com.gme.pay.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T0-6 (rotation half) — the key <em>set</em>: kid derivation, the previous-keys format, and the
 * retirement arithmetic that tells an operator when an old key can be deleted.
 *
 * <p>Before this there was one un-versioned signing value, so a suspected compromise had exactly
 * one response — replace it, instantly invalidating every live token — and a scheduled rotation was
 * not expressible at all.
 */
class JwtKeySetTest {

    private static final String ACTIVE = "3f9c1e77a04b2d68be51c0a7f4d29b83";
    private static final String OLD    = "77bb0431ea9d5c62f018a3d94e7c265f";
    private static final Instant T0    = Instant.parse("2026-07-28T09:00:00Z");

    // ── kid derivation ────────────────────────────────────────────────────────

    @Test
    @DisplayName("the kid is derived from the secret — stable, prefixed, and carrying no key material")
    void kidIsDerivedAndStable() {
        String kid = JwtKeySet.kidFor(ACTIVE);

        assertThat(kid).isEqualTo(JwtKeySet.kidFor(ACTIVE));      // same input, same id, every process
        assertThat(kid).startsWith(JwtKeySet.KID_PREFIX);
        assertThat(kid).hasSize(JwtKeySet.KID_PREFIX.length() + 16);
        assertThat(kid).doesNotContain(ACTIVE);
        // Not a plain digest of the secret: the domain separator keeps the published key id from
        // being interchangeable with any other SHA-256 of the same material.
        assertThat(kid).isNotEqualTo(JwtKeySet.kidFor("gmepay-jwt-kid-v1:" + ACTIVE));
    }

    @Test
    @DisplayName("different secrets get different kids")
    void differentSecretsDifferentKids() {
        assertThat(JwtKeySet.kidFor(ACTIVE)).isNotEqualTo(JwtKeySet.kidFor(OLD));
    }

    // ── set construction ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a set with no active key cannot be built (there would be nothing to sign with)")
    void noActiveKeyIsRejected() {
        assertThatThrownBy(() -> JwtKeySet.of("  ", List.of(JwtKeySet.retiredKey(OLD, T0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no active key");
    }

    @Test
    @DisplayName("the same secret listed as both active and previous is rejected, not de-duplicated")
    void duplicateSecretIsRejected() {
        assertThatThrownBy(() -> JwtKeySet.of(ACTIVE, List.of(JwtKeySet.retiredKey(ACTIVE, T0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    @DisplayName("find() is an exact kid lookup with no fallback to the other keys")
    void findIsExact() {
        JwtKeySet set = JwtKeySet.of(ACTIVE, List.of(JwtKeySet.retiredKey(OLD, T0)));

        assertThat(set.find(JwtKeySet.kidFor(ACTIVE))).contains(set.activeKey());
        assertThat(set.find(JwtKeySet.kidFor(OLD))).isPresent();
        assertThat(set.find("gmek_deadbeefdeadbeef")).isEmpty();
        assertThat(set.find(null)).isEmpty();
        assertThat(set.find("")).isEmpty();
        assertThat(set.size()).isEqualTo(2);
    }

    // ── previous-keys parsing ─────────────────────────────────────────────────

    @Test
    @DisplayName("blank / absent previous-keys is the normal steady state, not an error")
    void emptySpecParsesToNothing() {
        assertThat(JwtKeySet.parsePreviousKeys(null)).isEmpty();
        assertThat(JwtKeySet.parsePreviousKeys("")).isEmpty();
        assertThat(JwtKeySet.parsePreviousKeys("   ")).isEmpty();
    }

    @Test
    @DisplayName("multiple entries parse, separated by ';' or newlines, with whitespace tolerated")
    void multipleEntriesParse() {
        List<JwtKeySet.Key> keys = JwtKeySet.parsePreviousKeys(
                OLD + "@2026-07-28T09:00:00Z ;\n " + ACTIVE + "@2026-06-14T09:00:00Z");

        assertThat(keys).hasSize(2);
        assertThat(keys.get(0).kid()).isEqualTo(JwtKeySet.kidFor(OLD));
        assertThat(keys.get(0).demotedAt()).isEqualTo(T0);
        assertThat(keys.get(0).isActive()).isFalse();
        assertThat(keys.get(1).demotedAt()).isEqualTo(Instant.parse("2026-06-14T09:00:00Z"));
    }

    @Test
    @DisplayName("a secret containing '@' still parses — the split is on the LAST '@'")
    void secretContainingAtSignParses() {
        String awkward = "p@ssword-material-that-is-long-enough-32";
        List<JwtKeySet.Key> keys = JwtKeySet.parsePreviousKeys(awkward + "@2026-07-28T09:00:00Z");

        assertThat(keys).singleElement()
                .satisfies(k -> assertThat(k.secret()).isEqualTo(awkward));
    }

    @Test
    @DisplayName("an entry with no demotion timestamp is refused — the retirement date is not optional")
    void missingTimestampIsRejected() {
        assertThatThrownBy(() -> JwtKeySet.parsePreviousKeys(OLD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing its '@<demoted-at>' timestamp");
    }

    @Test
    @DisplayName("an unparseable timestamp is refused rather than silently dropping the key")
    void badTimestampIsRejected() {
        // Silently skipping the entry would drop a key that live tokens still name — the exact
        // outcome (mass rejection) that the overlap window exists to prevent.
        assertThatThrownBy(() -> JwtKeySet.parsePreviousKeys(OLD + "@last-tuesday"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unparseable demoted-at");
    }

    @Test
    @DisplayName("an entry with an empty secret is refused")
    void emptySecretIsRejected() {
        assertThatThrownBy(() -> JwtKeySet.parsePreviousKeys("@2026-07-28T09:00:00Z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty secret");
    }

    @Test
    @DisplayName("a previously active key must declare when it stopped signing")
    void retiredKeyRequiresDemotedAt() {
        assertThatThrownBy(() -> JwtKeySet.retiredKey(OLD, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("demotedAt");
    }

    // ── retirement arithmetic ─────────────────────────────────────────────────

    @Test
    @DisplayName("safe-to-remove is demotedAt + one maximum token TTL")
    void retirementReportArithmetic() {
        Duration maxTtl = Duration.ofHours(1);
        JwtKeySet set = JwtKeySet.of(ACTIVE, List.of(JwtKeySet.retiredKey(OLD, T0)));

        // 30 minutes after demotion: tokens signed by the old key can still be live.
        List<JwtKeySet.KeyStatus> during = set.report(maxTtl, T0.plus(Duration.ofMinutes(30)));
        assertThat(during).hasSize(2);
        assertThat(during.get(0).active()).isTrue();
        assertThat(during.get(0).safeToRemoveAfter()).isNull();
        assertThat(during.get(1).safeToRemoveAfter()).isEqualTo(T0.plus(maxTtl));
        assertThat(during.get(1).safeToRemoveNow()).isFalse();
        assertThat(during.get(1).overdueForRemoval()).isFalse();

        // 90 minutes after: nothing it signed can still be live.
        assertThat(set.report(maxTtl, T0.plus(Duration.ofMinutes(90))).get(1).safeToRemoveNow())
                .isTrue();

        // A whole extra window later: still accepted, and now flagged as an unnecessary extra copy
        // of live signing material.
        JwtKeySet.KeyStatus stale = set.report(maxTtl, T0.plus(Duration.ofHours(5))).get(1);
        assertThat(stale.overdueForRemoval()).isTrue();
        assertThat(stale.safeToRemoveNow()).isTrue();
    }

    @Test
    @DisplayName("the active key is never reported as removable — it is still minting")
    void activeKeyIsNeverRemovable() {
        JwtKeySet set = JwtKeySet.active(ACTIVE);
        assertThatCode(() -> set.report(Duration.ofHours(1), Instant.now())).doesNotThrowAnyException();

        JwtKeySet.KeyStatus active = set.report(Duration.ofHours(1), Instant.now()).get(0);
        assertThat(active.active()).isTrue();
        assertThat(active.safeToRemoveNow()).isFalse();
        assertThat(active.overdueForRemoval()).isFalse();
        assertThat(set.activeKey().safeToRemoveAfter(Duration.ofHours(1))).isNull();
    }
}
