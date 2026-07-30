package com.gme.pay.kyb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A non-authoritative result must not be storable OR readable as a completed screening (gap T5-3,
 * inheriting T1-4).
 *
 * <p>The read direction is the point of most of these cases: {@link TransactionScreeningEvidence} is
 * how a persisted row comes back, and a row can hold combinations the write path would never produce.
 */
class TransactionScreeningEvidenceTest {

    private static final Instant AT = Instant.parse("2026-07-28T04:05:06Z");

    private static TransactionScreeningEvidence row(String providerId,
                                                    boolean authoritative,
                                                    ScreeningResult.Status status,
                                                    String caveat) {
        return new TransactionScreeningEvidence(
                "TXN-1", "SENDMN", PaymentParty.BENEFICIARY, "cust-9", "BENEFICIARY[name=present]",
                providerId, authoritative, status, AT, caveat,
                TransactionScreeningPolicy.Posture.PROCEED_NOT_REQUIRED, null);
    }

    // ------------------------------------------------------------------
    // Reading a row back
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a stored CLEAR from a non-authoritative provider reads back as NOT_SCREENED")
    void nonAuthoritativeClearIsCoercedOnRead() {
        // The exact row a backfill, a migration or a hand-edit could leave behind.
        TransactionScreeningEvidence e = row("octa", false, ScreeningResult.Status.CLEAR, "cached path");

        assertEquals(ScreeningResult.Status.NOT_SCREENED_NO_PROVIDER, e.status());
        assertFalse(e.completedScreening());
        assertEquals(UnscreenedReason.PROVIDER_NOT_AUTHORITATIVE, e.unscreenedReason());
    }

    @Test
    @DisplayName("the reserved provider ids can never read back as authoritative")
    void reservedProviderIdsCannotClaimAuthority() {
        for (String reserved : List.of(ScreeningProvenance.STUB_PROVIDER_ID,
                ScreeningProvenance.NO_PROVIDER_ID, ScreeningProvenance.UNKNOWN_PROVIDER_ID)) {
            TransactionScreeningEvidence e =
                    row(reserved, true, ScreeningResult.Status.CLEAR, null);

            assertFalse(e.providerAuthoritative(), reserved + " must never be authoritative");
            assertEquals(ScreeningResult.Status.NOT_SCREENED_NO_PROVIDER, e.status(), reserved);
            assertFalse(e.completedScreening(), reserved);
            assertNotNull(e.caveat(), reserved + " must carry a caveat");
        }
    }

    @Test
    @DisplayName("a row with no provider id is not evidence of a check")
    void absentProviderIsNotAuthority() {
        TransactionScreeningEvidence e = row("  ", true, ScreeningResult.Status.CLEAR, null);

        assertEquals(ScreeningProvenance.UNKNOWN_PROVIDER_ID, e.providerId());
        assertFalse(e.providerAuthoritative());
        assertFalse(e.completedScreening());
    }

    @Test
    @DisplayName("a non-authoritative row without a caveat gets one — silence is not reassurance")
    void missingCaveatIsSupplied() {
        TransactionScreeningEvidence e =
                row("octa", false, ScreeningResult.Status.NOT_SCREENED_NO_PROVIDER, null);

        assertEquals(TransactionScreeningEvidence.MISSING_CAVEAT, e.caveat());
    }

    @Test
    @DisplayName("a stored unscreened-reason cannot survive on a genuinely completed screening")
    void completedScreeningHasNoUnscreenedReason() {
        TransactionScreeningEvidence e = new TransactionScreeningEvidence(
                "TXN-1", "SENDMN", PaymentParty.BENEFICIARY, "cust-9", "BENEFICIARY[name=present]",
                "octa", true, ScreeningResult.Status.CLEAR, AT, null,
                TransactionScreeningPolicy.Posture.PROCEED_SCREENED_CLEAR,
                UnscreenedReason.NO_PROVIDER);

        assertTrue(e.completedScreening());
        assertNull(e.unscreenedReason());
        assertNull(e.caveat(), "an authoritative screening has nothing to caveat");
    }

    @Test
    @DisplayName("an authoritative HIT is a completed screening that refuses")
    void authoritativeHitIsCompleted() {
        TransactionScreeningEvidence e = new TransactionScreeningEvidence(
                "TXN-1", "SENDMN", PaymentParty.BENEFICIARY, "cust-9", "BENEFICIARY[name=present]",
                "octa", true, ScreeningResult.Status.HIT, AT, null,
                TransactionScreeningPolicy.Posture.REFUSE_SCREENING_HIT, null);

        assertTrue(e.completedScreening(), "a HIT is a check that happened");
        assertTrue(e.refused());
    }

    @Test
    @DisplayName("identity fields are required — evidence with no transaction is not evidence")
    void requiredFields() {
        assertThrows(IllegalArgumentException.class, () -> new TransactionScreeningEvidence(
                "  ", "SENDMN", PaymentParty.BENEFICIARY, null, null, "octa", true,
                ScreeningResult.Status.CLEAR, AT, null, null, null), "blank txnRef");
        assertThrows(IllegalArgumentException.class, () -> new TransactionScreeningEvidence(
                "TXN-1", "SENDMN", null, null, null, "octa", true,
                ScreeningResult.Status.CLEAR, AT, null, null, null), "null party");
        assertThrows(IllegalArgumentException.class, () -> new TransactionScreeningEvidence(
                "TXN-1", "SENDMN", PaymentParty.BENEFICIARY, null, null, "octa", true,
                ScreeningResult.Status.CLEAR, null, null, null, null), "null screenedAt");
        assertThrows(IllegalArgumentException.class,
                () -> TransactionScreeningEvidence.of("TXN-1", null, null, null, null, AT),
                "null subject");
    }

    // ------------------------------------------------------------------
    // Building from what happened
    // ------------------------------------------------------------------

    @Test
    @DisplayName("with no provider wired, the evidence names NO_PROVIDER and no PII")
    void fromNoProviderPort() {
        PaymentScreeningSubject subject =
                PaymentScreeningSubject.named(PaymentParty.BENEFICIARY, "cust-9", "ACME TRADING LLC");
        ScreeningResult result = new NoProviderPaymentScreeningPort().screen(subject);
        TransactionScreeningPolicy policy = new TransactionScreeningPolicy(
                ScreeningRequirement.of(PaymentParty.BENEFICIARY), false, "prod");

        TransactionScreeningEvidence e = TransactionScreeningEvidence.of(
                "TXN-1", "SENDMN", subject, result,
                policy.decide(PaymentParty.BENEFICIARY, result), AT);

        assertEquals(ScreeningProvenance.NO_PROVIDER_ID, e.providerId());
        assertFalse(e.completedScreening());
        assertEquals(UnscreenedReason.NO_PROVIDER, e.unscreenedReason());
        assertEquals(TransactionScreeningPolicy.Posture.REFUSE_SCREENING_UNAVAILABLE, e.posture());
        assertTrue(e.refused());
        assertFalse(e.subjectAttributes().contains("ACME"),
                "the subject's NAME must never reach the evidence row (no column encryption, T5-5)");
        assertTrue(e.subjectAttributes().contains("name=present"));
    }

    @Test
    @DisplayName("a party we hold no name for is NO_SUBJECT_IDENTITY, not a provider problem")
    void referenceOnlySubjectIsAContractGap() {
        // The shape both live payment entry points actually produce today.
        PaymentScreeningSubject subject =
                PaymentScreeningSubject.byReferenceOnly(PaymentParty.PAYER, "user-uuid");
        ScreeningResult result = new NoProviderPaymentScreeningPort().screen(subject);

        TransactionScreeningEvidence e = TransactionScreeningEvidence.of(
                "TXN-1", null, subject, result, null, AT);

        assertFalse(subject.screenable());
        assertEquals(UnscreenedReason.NO_SUBJECT_IDENTITY, e.unscreenedReason(),
                "buying a vendor would not fix this one — the contract carries no name");
        assertFalse(e.completedScreening());
        assertNull(e.partnerId());
    }

    @Test
    @DisplayName("a provider that answered nothing at all is a PROVIDER_ERROR")
    void nullResultIsProviderError() {
        PaymentScreeningSubject subject =
                PaymentScreeningSubject.named(PaymentParty.MERCHANT, "m-1", "ACME");

        TransactionScreeningEvidence e =
                TransactionScreeningEvidence.of("TXN-1", "SENDMN", subject, null, null, AT);

        assertEquals(UnscreenedReason.PROVIDER_ERROR, e.unscreenedReason());
        assertEquals(ScreeningProvenance.UNKNOWN_PROVIDER_ID, e.providerId());
        assertEquals(AT, e.screenedAt());
    }
}
