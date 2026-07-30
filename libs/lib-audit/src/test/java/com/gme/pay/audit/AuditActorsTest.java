package com.gme.pay.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The actor-vocabulary rules of gap T5-1. These tests exist to make the two failure modes the
 * CISO audit found <b>unrepresentable</b>, not merely discouraged:
 *
 * <ol>
 *   <li>a name that arrived on the wire with no verified credential must not be recordable as
 *       if it were a real principal;</li>
 *   <li>the bare literal {@code "system"} — which was simultaneously the silent default for an
 *       absent {@code X-Actor} header and the 4-eyes carve-out — must not be mintable at all.</li>
 * </ol>
 */
class AuditActorsTest {

    private static final Instant T0 = Instant.parse("2026-07-28T00:00:00Z");

    @Test
    @DisplayName("the bare \"system\" literal cannot be written, in any casing")
    void bareSystemLiteralIsRefused() {
        for (String spelling : new String[] {"system", "SYSTEM", "System", "  system  "}) {
            assertThatThrownBy(() -> AuditActors.requireAttributable(spelling))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not writable");
        }
    }

    @Test
    @DisplayName("no writer can seal an event with the bare \"system\" actor — the choke point is newEvent")
    void newEventRefusesBareSystem() {
        assertThatThrownBy(() -> AuditEvent.newEvent(
                "partner", "P001", "system", null, "PARTNER_SAVED",
                null, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                HashChain.GENESIS, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a blank or absent actor cannot be sealed either — it was the same bug wearing a different hat")
    void newEventRefusesBlankActor() {
        for (String blank : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> AuditEvent.newEvent(
                    "partner", "P001", blank, null, "PARTNER_SAVED",
                    null, null, HashChain.GENESIS, T0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("a genuine system action IS writable, but only when it names its component")
    void namedSystemPrincipalIsWritable() {
        String actor = AuditActors.system("auto-suspend");
        assertThat(actor).isEqualTo("system:auto-suspend");
        assertThat(AuditActors.isSystem(actor)).isTrue();
        assertThat(AuditActors.isAttributable(actor))
                .as("a named system principal is attributable — we know exactly what acted")
                .isTrue();

        AuditEvent sealed = AuditEvent.newEvent(
                "partner", "P001", actor, null, "PARTNER_SUSPENDED",
                null, null, HashChain.GENESIS, T0);
        assertThat(sealed.actorId()).isEqualTo("system:auto-suspend");
    }

    @Test
    @DisplayName("an unnamed system principal is refused — that is the lost-identity case in disguise")
    void unnamedSystemPrincipalIsRefused() {
        assertThatThrownBy(() -> AuditActors.system(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must name its component");
        assertThatThrownBy(() -> AuditActors.system("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an unverified claim is recorded, but never as an attested principal")
    void unverifiedClaimIsStructurallyDistinguishable() {
        String actor = AuditActors.unverified("alice@gme.com");
        assertThat(actor).isEqualTo("unverified:alice@gme.com");
        assertThat(AuditActors.isAttributable(actor)).isFalse();
        // The forensic value of the claim is preserved...
        assertThat(actor).contains("alice@gme.com");
        // ...but it can never be joined against, or read as, a real operator id.
        assertThat(actor).isNotEqualTo("alice@gme.com");
    }

    @Test
    @DisplayName("a claim cannot smuggle itself into a reserved namespace")
    void unverifiedClaimCannotForgeASystemPrincipal() {
        assertThat(AuditActors.unverified("system:auto-suspend"))
                .isEqualTo("unverified:system:auto-suspend");
        assertThat(AuditActors.isSystem(AuditActors.unverified("system:auto-suspend"))).isFalse();
        // ...and an ATTESTED subject that looks like a reserved principal is refused outright
        // rather than accepted: an attested channel is not a licence to mint a system actor.
        assertThatThrownBy(() -> AuditActors.attested("system:auto-suspend"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
        assertThatThrownBy(() -> AuditActors.attested("svc:ops-partner-bff"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("nothing claimed and nothing proven is spelled out, not defaulted to \"system\"")
    void nothingClaimedBecomesUnattributed() {
        assertThat(AuditActors.unverified(null)).isEqualTo(AuditActors.UNATTRIBUTED);
        assertThat(AuditActors.unverified("   ")).isEqualTo(AuditActors.UNATTRIBUTED);
        assertThat(AuditActors.isAttributable(AuditActors.UNATTRIBUTED)).isFalse();
        // It IS writable — the honest record of a lost identity must be recordable.
        assertThat(AuditActors.requireAttributable(AuditActors.UNATTRIBUTED))
                .isEqualTo(AuditActors.UNATTRIBUTED);
    }

    @Test
    @DisplayName("historical bare-\"system\" rows still read as unattributable, so reports count them")
    void historicalSystemRowsAreNotAttributable() {
        assertThat(AuditActors.isAttributable("system")).isFalse();
        assertThat(AuditActors.isAttributable("SYSTEM")).isFalse();
        // ...while a named system principal is. The two must not be conflated in a report.
        assertThat(AuditActors.isAttributable("system:migration-v042")).isTrue();
    }

    @Test
    @DisplayName("truncation keeps the provenance prefix, never the tail")
    void clampPreservesTheProvenance() {
        String longName = "a".repeat(120);
        String actor = AuditActors.unverified(longName);
        assertThat(actor).hasSize(AuditActors.MAX_LEN);
        assertThat(actor)
                .as("an over-long value must not silently lose the fact that it was unverified")
                .startsWith(AuditActors.UNVERIFIED_PREFIX);
    }
}
