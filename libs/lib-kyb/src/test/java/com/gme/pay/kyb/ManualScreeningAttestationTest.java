package com.gme.pay.kyb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The manual-KYB-SOP screening authority (gap T1-4, owner decision 2026-07-28).
 *
 * <p>Two properties are under test and they pull in opposite directions, which is the whole point:
 * an <b>attested</b> manual screening must be constructible and must be authoritative, while an
 * <b>unattested</b> one must be unconstructable — and neither may weaken the T1-4 guarantee that a
 * non-authoritative producer cannot report a clean screening.
 */
class ManualScreeningAttestationTest {

    private static final Instant WHEN = Instant.parse("2026-07-28T09:15:00Z");

    private static ManualScreeningAttestation attestation() {
        return new ManualScreeningAttestation(
                "compliance.officer@gme.com",
                WHEN,
                "GME-COMP-SOP-014 Manual sanctions screening",
                "v3",
                "UN consolidated list and the destination-jurisdiction list named in §4 of the SOP,"
                        + " searched by romanized and local legal name plus every declared UBO");
    }

    // ------------------------------------------------------------------ the record itself

    @Test
    @DisplayName("a complete attestation records who, when, which SOP version and what was checked")
    void completeAttestation() {
        ManualScreeningAttestation a = attestation();
        assertEquals("compliance.officer@gme.com", a.attesterActorId());
        assertEquals(WHEN, a.attestedAt());
        assertEquals("GME-COMP-SOP-014 Manual sanctions screening", a.sopDocumentRef());
        assertEquals("v3", a.sopVersion());
        assertTrue(a.sourcesConsulted().contains("UN consolidated list"));
    }

    @Test
    @DisplayName("attestedAt is truncated to micros so the stored TIMESTAMP equals the value")
    void attestedAtTruncated() {
        ManualScreeningAttestation a = new ManualScreeningAttestation(
                "alice@gme.com", Instant.parse("2026-07-28T09:15:00.123456789Z"),
                "SOP-1", "v1", "lists");
        assertEquals(Instant.parse("2026-07-28T09:15:00.123456Z"), a.attestedAt());
        assertEquals(a.attestedAt(), a.attestedAt().truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    @DisplayName("every field is mandatory — a missing one is refused, not defaulted")
    void everyFieldMandatory() {
        assertThrows(IllegalArgumentException.class, () -> new ManualScreeningAttestation(
                null, WHEN, "SOP-1", "v1", "lists"));
        assertThrows(IllegalArgumentException.class, () -> new ManualScreeningAttestation(
                "  ", WHEN, "SOP-1", "v1", "lists"));
        assertThrows(IllegalArgumentException.class, () -> new ManualScreeningAttestation(
                "alice@gme.com", null, "SOP-1", "v1", "lists"));
        assertThrows(IllegalArgumentException.class, () -> new ManualScreeningAttestation(
                "alice@gme.com", WHEN, null, "v1", "lists"));
        assertThrows(IllegalArgumentException.class, () -> new ManualScreeningAttestation(
                "alice@gme.com", WHEN, "SOP-1", null, "lists"));
        assertThrows(IllegalArgumentException.class, () -> new ManualScreeningAttestation(
                "alice@gme.com", WHEN, "SOP-1", "v1", null));
    }

    @Test
    @DisplayName("a platform principal or an unproven claim cannot be the attester")
    void attesterMustBeAPerson() {
        for (String notAPerson : List.of(
                "system", "SYSTEM", "system:migration-v045", "svc:internal-caller",
                "unverified:alice@gme.com", "unattributed")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ManualScreeningAttestation(notAPerson, WHEN, "SOP-1", "v1", "lists"),
                    notAPerson + " must not be accepted as the attester");
        }
    }

    @Test
    @DisplayName("over-long text is refused, never truncated — a shortened record is a different claim")
    void overLongTextRefused() {
        String tooLongSources = "x".repeat(ManualScreeningAttestation.MAX_SOURCES_CONSULTED_LEN + 1);
        assertThrows(IllegalArgumentException.class, () -> new ManualScreeningAttestation(
                "alice@gme.com", WHEN, "SOP-1", "v1", tooLongSources));
        String tooLongRef = "x".repeat(ManualScreeningAttestation.MAX_SOP_DOCUMENT_REF_LEN + 1);
        assertThrows(IllegalArgumentException.class, () -> new ManualScreeningAttestation(
                "alice@gme.com", WHEN, tooLongRef, "v1", "lists"));
    }

    // ------------------------------------------------------------------ provenance binding

    @Test
    @DisplayName("manual provenance is authoritative and carries its evidence")
    void manualProvenanceIsAuthoritative() {
        ScreeningProvenance p = ScreeningProvenance.manualAttestation(attestation());
        assertEquals(ScreeningProvenance.MANUAL_ATTESTATION_PROVIDER_ID, p.providerId());
        assertTrue(p.authoritative());
        assertTrue(p.manuallyAttested());
        assertNull(p.caveat());
        assertEquals("compliance.officer@gme.com", p.attestation().attesterActorId());
    }

    @Test
    @DisplayName("the manual provider id cannot claim authority without an attestation")
    void manualProviderIdRequiresAttestation() {
        assertThrows(IllegalArgumentException.class, () -> ScreeningProvenance.manualAttestation(null));
        assertThrows(IllegalArgumentException.class, () -> new ScreeningProvenance(
                ScreeningProvenance.MANUAL_ATTESTATION_PROVIDER_ID, true, null));
        // …and it cannot sneak in through the vendor or degraded-run factories either.
        assertThrows(IllegalArgumentException.class, () -> ScreeningProvenance.vendor(
                ScreeningProvenance.MANUAL_ATTESTATION_PROVIDER_ID));
        assertThrows(IllegalArgumentException.class, () -> ScreeningProvenance.nonAuthoritative(
                ScreeningProvenance.MANUAL_ATTESTATION_PROVIDER_ID, "some caveat"));
    }

    @Test
    @DisplayName("attestation evidence cannot be attached to a machine run")
    void attestationCannotDecorateAMachineRun() {
        assertThrows(IllegalArgumentException.class, () -> new ScreeningProvenance(
                "octa", true, null, attestation()));
        assertThrows(IllegalArgumentException.class, () -> new ScreeningProvenance(
                ScreeningProvenance.STUB_PROVIDER_ID, false, ScreeningProvenance.STUB_CAVEAT,
                attestation()));
    }

    // ------------------------------------------------------------------ status coercions

    @Test
    @DisplayName("a manual CLEAR is coerced UP to CLEAR_MANUAL_ATTESTATION — never a bare CLEAR")
    void manualClearNeverFlattens() {
        ScreeningResult r = new ScreeningResult(
                ScreeningResult.Status.CLEAR, List.of(), WHEN, "manual-SOP-014@v3",
                ScreeningProvenance.manualAttestation(attestation()));
        assertEquals(ScreeningResult.Status.CLEAR_MANUAL_ATTESTATION, r.status());
        assertTrue(r.authoritative());
        assertTrue(r.screeningPerformed());
        assertTrue(r.manuallyAttested());
        assertEquals("compliance.officer@gme.com", r.attestation().attesterActorId());
    }

    @Test
    @DisplayName("CLEAR_MANUAL_ATTESTATION is unusable by any producer without an attestation")
    void manualStatusCannotBeBorrowed() {
        for (ScreeningProvenance p : List.of(
                ScreeningProvenance.stub(),
                ScreeningProvenance.unknown(),
                ScreeningProvenance.noProvider("no provider is wired"),
                ScreeningProvenance.vendor("octa"))) {
            ScreeningResult r = new ScreeningResult(
                    ScreeningResult.Status.CLEAR_MANUAL_ATTESTATION, List.of(), WHEN, "ref", p);
            assertEquals(ScreeningResult.Status.NOT_SCREENED_NO_PROVIDER, r.status(),
                    p.providerId() + " must not be able to report a manual attestation");
            assertFalse(r.manuallyAttested());
        }
    }

    @Test
    @DisplayName("the T1-4 stub coercion is untouched by the manual authority")
    void stubCoercionUnchanged() {
        ScreeningResult r = new ScreeningResult(
                ScreeningResult.Status.CLEAR, List.of(), WHEN, "stub-abc",
                ScreeningProvenance.stub());
        assertEquals(ScreeningResult.Status.NOT_SCREENED_NO_PROVIDER, r.status());
        assertFalse(r.screeningPerformed());
        assertFalse(r.manuallyAttested());
    }

    @Test
    @DisplayName("a vendor CLEAR stays a bare CLEAR — the two authorities remain distinguishable")
    void vendorClearStaysClear() {
        ScreeningResult r = new ScreeningResult(
                ScreeningResult.Status.CLEAR, List.of(), WHEN, "octa-1",
                ScreeningProvenance.vendor("octa"));
        assertEquals(ScreeningResult.Status.CLEAR, r.status());
        assertTrue(r.screeningPerformed());
        assertFalse(r.manuallyAttested());
        assertNull(r.attestation());
    }

    @Test
    @DisplayName("a manual HIT stays a HIT and still fails closed")
    void manualHitFailsClosed() {
        ScreeningResult r = new ScreeningResult(
                ScreeningResult.Status.HIT,
                List.of(new ScreeningResult.Hit("SOP-14 §4 source", "ACME HOLDINGS", 0.9)),
                WHEN, "manual-SOP-014@v3",
                ScreeningProvenance.manualAttestation(attestation()));
        assertEquals(ScreeningResult.Status.HIT, r.status());
        assertTrue(r.manuallyAttested());
        assertTrue(r.screeningPerformed());
    }
}
