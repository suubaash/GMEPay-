package com.gme.pay.kyb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The fail-open / fail-closed posture of the payment path (gap T5-3).
 *
 * <p>The properties pinned here are the ones an auditor would ask about, so each test is named for the
 * claim it defends rather than for the method it calls.
 */
class TransactionScreeningPolicyTest {

    private static final Instant AT = Instant.parse("2026-07-28T04:05:06Z");

    private static ScreeningResult noProvider() {
        return new ScreeningResult(
                ScreeningResult.Status.NOT_SCREENED_NO_PROVIDER, List.of(), AT, null,
                ScreeningProvenance.noProvider(NoProviderPaymentScreeningPort.CAVEAT));
    }

    private static ScreeningResult vendorClear() {
        return new ScreeningResult(
                ScreeningResult.Status.CLEAR, List.of(), AT, "octa-1",
                ScreeningProvenance.vendor("octa"));
    }

    private static ScreeningResult vendorHit() {
        return new ScreeningResult(
                ScreeningResult.Status.HIT,
                List.of(new ScreeningResult.Hit("OFAC_SDN", "ACME", 0.97)),
                AT, "octa-2", ScreeningProvenance.vendor("octa"));
    }

    // ------------------------------------------------------------------
    // The shipped default must change nothing
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("screening off (the shipped default)")
    class Off {

        @Test
        @DisplayName("requires nothing, so refuses nothing — even with no provider wired")
        void defaultIsInertAndRefusesNothing() {
            TransactionScreeningPolicy policy = TransactionScreeningPolicy.off();

            assertTrue(policy.inert(), "the shipped policy must not be able to refuse anything");
            assertTrue(policy.requirement().isEmpty());

            for (PaymentParty party : PaymentParty.values()) {
                TransactionScreeningPolicy.Decision d = policy.decide(party, noProvider());
                assertFalse(d.refuses(), party + " must not be refused by the default policy");
                assertEquals(TransactionScreeningPolicy.Posture.PROCEED_NOT_REQUIRED, d.posture());
                assertFalse(d.mustAudit(), "an untouched payment path must not generate audit noise");
            }
        }

        @Test
        @DisplayName("a HIT on a party nobody required is still not refused here")
        void notRequiredMeansNotThisPolicysDecision() {
            // Recorded deliberately: this policy answers "was a REQUIRED check done", not "is this
            // party sanctioned". With no requirement configured it has no opinion, and pretending
            // otherwise would mean the seam silently started blocking payments on merge.
            TransactionScreeningPolicy.Decision d =
                    TransactionScreeningPolicy.off().decide(PaymentParty.BENEFICIARY, vendorHit());

            assertFalse(d.refuses());
            assertEquals(TransactionScreeningPolicy.Posture.PROCEED_NOT_REQUIRED, d.posture());
        }
    }

    // ------------------------------------------------------------------
    // Required but unavailable -> refuse, by default
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("screening required")
    class Required {

        private final TransactionScreeningPolicy policy = new TransactionScreeningPolicy(
                ScreeningRequirement.of(PaymentParty.BENEFICIARY), false, "prod");

        @Test
        @DisplayName("required but unavailable REFUSES by default")
        void requiredButUnavailableRefuses() {
            TransactionScreeningPolicy.Decision d =
                    policy.decide(PaymentParty.BENEFICIARY, noProvider());

            assertTrue(d.refuses());
            assertEquals(TransactionScreeningPolicy.Posture.REFUSE_SCREENING_UNAVAILABLE, d.posture());
            assertTrue(d.mustAudit());
            assertTrue(d.reason().contains("could not be performed"), d.reason());
        }

        @Test
        @DisplayName("a null result is an unavailable provider, never a pass")
        void absentAnswerIsNotAPass() {
            TransactionScreeningPolicy.Decision d = policy.decide(PaymentParty.BENEFICIARY, null);

            assertTrue(d.refuses());
            assertEquals(TransactionScreeningPolicy.Posture.REFUSE_SCREENING_UNAVAILABLE, d.posture());
        }

        @Test
        @DisplayName("a party outside the requirement is untouched")
        void otherPartiesAreUnaffected() {
            assertFalse(policy.decide(PaymentParty.PAYER, noProvider()).refuses());
            assertFalse(policy.decide(null, noProvider()).refuses());
        }

        @Test
        @DisplayName("an authoritative CLEAR proceeds")
        void authoritativeClearProceeds() {
            TransactionScreeningPolicy.Decision d =
                    policy.decide(PaymentParty.BENEFICIARY, vendorClear());

            assertFalse(d.refuses());
            assertEquals(TransactionScreeningPolicy.Posture.PROCEED_SCREENED_CLEAR, d.posture());
            assertFalse(d.mustAudit(), "a clean authoritative screening is the normal case");
        }

        @Test
        @DisplayName("a HIT and a NEEDS_REVIEW refuse regardless of the override")
        void adverseDispositionsAlwaysRefuse() {
            TransactionScreeningPolicy permissive = new TransactionScreeningPolicy(
                    ScreeningRequirement.of(PaymentParty.BENEFICIARY), true, "sandbox");

            assertTrue(permissive.decide(PaymentParty.BENEFICIARY, vendorHit()).refuses(),
                    "the override governs what happens when we know NOTHING, not when we know"
                            + " something bad");
            assertEquals(TransactionScreeningPolicy.Posture.REFUSE_SCREENING_HIT,
                    permissive.decide(PaymentParty.BENEFICIARY, vendorHit()).posture());

            ScreeningResult review = new ScreeningResult(
                    ScreeningResult.Status.NEEDS_REVIEW, List.of(), AT, "octa-3",
                    ScreeningProvenance.vendor("octa"));
            assertEquals(TransactionScreeningPolicy.Posture.REFUSE_SCREENING_NEEDS_REVIEW,
                    permissive.decide(PaymentParty.BENEFICIARY, review).posture());
        }
    }

    // ------------------------------------------------------------------
    // The override is non-prod only, and audited
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the fail-open override")
    class Override {

        @ParameterizedTest(name = "refuses to construct in environment \"{0}\"")
        @ValueSource(strings = {"prod", "production", "PROD", "live", "eu-west-1", "?", " "})
        @DisplayName("cannot be armed in production — or in anything unrecognised")
        void rejectedInProduction(String environment) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new TransactionScreeningPolicy(
                            ScreeningRequirement.of(PaymentParty.BENEFICIARY), true, environment));
            assertTrue(e.getMessage().contains("treated as production"), e.getMessage());
        }

        @Test
        @DisplayName("cannot be armed when the environment is unset — unlabelled counts as production")
        void unsetEnvironmentIsProduction() {
            assertTrue(TransactionScreeningPolicy.isProduction(null));
            assertTrue(TransactionScreeningPolicy.isProduction(""));
            assertThrows(IllegalStateException.class, () -> new TransactionScreeningPolicy(
                    ScreeningRequirement.of(PaymentParty.PAYER), true, null));
        }

        @ParameterizedTest(name = "permitted in environment \"{0}\"")
        @ValueSource(strings = {"local", "dev", "test", "ci", "sandbox", "uat", "staging", "STAGE"})
        @DisplayName("is permitted in a recognised non-production environment")
        void permittedOutsideProduction(String environment) {
            TransactionScreeningPolicy policy = new TransactionScreeningPolicy(
                    ScreeningRequirement.of(PaymentParty.BENEFICIARY), true, environment);

            assertTrue(policy.overrideArmed());
            assertFalse(TransactionScreeningPolicy.isProduction(environment));
        }

        @Test
        @DisplayName("proceeding under it is never a clean pass — it always audits")
        void overriddenProceedIsAlwaysAudited() {
            TransactionScreeningPolicy policy = new TransactionScreeningPolicy(
                    ScreeningRequirement.of(PaymentParty.BENEFICIARY), true, "sandbox");

            TransactionScreeningPolicy.Decision d =
                    policy.decide(PaymentParty.BENEFICIARY, noProvider());

            assertFalse(d.refuses(), "the override exists precisely so the payment proceeds");
            assertEquals(TransactionScreeningPolicy.Posture.PROCEED_UNAVAILABLE_OVERRIDDEN, d.posture());
            assertTrue(d.mustAudit(), "money moved without a required check — that is an audit row");
            assertTrue(d.reason().contains("PROCEEDING WITHOUT A REQUIRED SCREENING"), d.reason());
        }

        @Test
        @DisplayName("is irrelevant while nothing is required (the shipped state)")
        void inertPolicyIgnoresTheOverride() {
            // No requirement means no unavailable-branch to override, so arming it in a non-prod
            // environment changes literally nothing.
            TransactionScreeningPolicy policy =
                    new TransactionScreeningPolicy(ScreeningRequirement.none(), true, "dev");

            assertTrue(policy.inert());
            assertEquals(TransactionScreeningPolicy.Posture.PROCEED_NOT_REQUIRED,
                    policy.decide(PaymentParty.BENEFICIARY, noProvider()).posture());
        }
    }

    // ------------------------------------------------------------------
    // Requirement binding
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("ScreeningRequirement")
    class Requirement {

        @Test
        @DisplayName("is empty by default and empty for null/blank configuration")
        void emptyByDefault() {
            assertTrue(ScreeningRequirement.none().isEmpty());
            assertTrue(ScreeningRequirement.parse(null).isEmpty());
            assertTrue(ScreeningRequirement.parse(List.of()).isEmpty());
            assertTrue(ScreeningRequirement.parse(List.of("  ")).isEmpty());
            assertTrue(ScreeningRequirement.of().isEmpty());
        }

        @Test
        @DisplayName("parses role names case-insensitively")
        void parsesRoles() {
            ScreeningRequirement r = ScreeningRequirement.parse(List.of("beneficiary", " MERCHANT "));

            assertTrue(r.requires(PaymentParty.BENEFICIARY));
            assertTrue(r.requires(PaymentParty.MERCHANT));
            assertFalse(r.requires(PaymentParty.PAYER));
            assertFalse(r.requires(null));
        }

        @Test
        @DisplayName("REJECTS an unknown role rather than silently requiring nobody")
        void rejectsTypos() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ScreeningRequirement.parse(List.of("BENEFICIARY", "SENDER")));
            assertTrue(e.getMessage().contains("SENDER"), e.getMessage());
            assertTrue(e.getMessage().contains("screen nobody"), e.getMessage());
        }

        @Test
        @DisplayName("describes the empty state in words an operator can act on")
        void describesItself() {
            assertTrue(ScreeningRequirement.none().describe().contains("no party requires screening"));
            assertTrue(ScreeningRequirement.of(PaymentParty.PAYER).describe().contains("PAYER"));
        }
    }
}
