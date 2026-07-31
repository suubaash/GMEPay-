package com.gme.pay.kyb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the deterministic decision rules of {@link StubKybAdapter} (ADR-009 /
 * ADR-014 dev default): trigger words, HIT precedence, providerRef stability,
 * and the MICROS truncation discipline on {@code screenedAt}.
 *
 * <p>T1-4 additions: the clean branch reports
 * {@link ScreeningResult.Status#NOT_SCREENED_NO_PROVIDER} (never CLEAR), every
 * result carries non-authoritative {@link ScreeningProvenance}, and the full-KYB
 * registry flags are false because no register was consulted.
 */
class StubKybAdapterTest {

    private final StubKybAdapter adapter = new StubKybAdapter();

    private static KybSubject subject(String legalLocal, String legalRoman, List<KybSubject.Ubo> ubos) {
        return new KybSubject("P_TEST", legalLocal, legalRoman, "KR", "123-45-67890", ubos);
    }

    private static KybSubject.Ubo ubo(String name) {
        return new KybSubject.Ubo(name, new BigDecimal("25.5"), false, "KR");
    }

    @Test
    void cleanSubject_isNotScreened_notClear_withNoHits() {
        ScreeningResult result = adapter.screen(
                subject("지엠이송금", "GME Remit Co Ltd", List.of(ubo("Hong Gil Dong"))));

        // The whole point of T1-4: no trigger word does NOT mean "clear".
        assertEquals(ScreeningResult.Status.NOT_SCREENED_NO_PROVIDER, result.status());
        assertNotEquals(ScreeningResult.Status.CLEAR, result.status());
        assertTrue(result.hits().isEmpty());
        assertNotNull(result.screenedAt());
        assertTrue(result.providerRef().startsWith("stub-"));
    }

    @Test
    void everyStubResult_carriesNonAuthoritativeProvenance_withACaveat() {
        ScreeningResult clean = adapter.screen(subject("A", "Alpha Corp", List.of()));
        ScreeningResult hit = adapter.screen(subject("B", "Sanctioned Corp", List.of()));
        ScreeningResult review = adapter.screen(subject("C", "Review Corp", List.of()));

        for (ScreeningResult r : List.of(clean, hit, review)) {
            assertEquals(ScreeningProvenance.STUB_PROVIDER_ID, r.provenance().providerId());
            assertFalse(r.authoritative(), "a stub run is never authoritative");
            assertFalse(r.screeningPerformed(), "the stub screens nothing");
            assertEquals(ScreeningProvenance.STUB_CAVEAT, r.caveat());
        }
    }

    @Test
    void stubProvenance_cannotClaimAuthority() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScreeningProvenance(ScreeningProvenance.STUB_PROVIDER_ID, true, null));
        assertThrows(IllegalArgumentException.class,
                () -> ScreeningProvenance.vendor(ScreeningProvenance.STUB_PROVIDER_ID));
    }

    @Test
    void nonAuthoritativeClear_isCoercedToNotScreened_evenIfHandConstructed() {
        // A hand-built (or deserialised) CLEAR from a non-authoritative producer
        // must be impossible to hold — this is the structural guarantee the
        // activation gate and every persisted row rely on.
        ScreeningResult stubClear = new ScreeningResult(ScreeningResult.Status.CLEAR,
                List.of(), java.time.Instant.now(), "stub-abc", ScreeningProvenance.stub());
        assertEquals(ScreeningResult.Status.NOT_SCREENED_NO_PROVIDER, stubClear.status());

        // Absent provenance (an older producer's JSON) is not authority either.
        ScreeningResult provenanceless = new ScreeningResult(ScreeningResult.Status.CLEAR,
                List.of(), java.time.Instant.now(), "who-knows");
        assertEquals(ScreeningResult.Status.NOT_SCREENED_NO_PROVIDER, provenanceless.status());
        assertEquals(ScreeningProvenance.UNKNOWN_PROVIDER_ID,
                provenanceless.provenance().providerId());

        // A real provider CAN report CLEAR — the coercion is about provenance,
        // not about forbidding clean results.
        ScreeningResult vendorClear = new ScreeningResult(ScreeningResult.Status.CLEAR,
                List.of(), java.time.Instant.now(), "octa-1", ScreeningProvenance.vendor("octa"));
        assertEquals(ScreeningResult.Status.CLEAR, vendorClear.status());
        assertTrue(vendorClear.screeningPerformed());
        assertNull(vendorClear.caveat());
    }

    @Test
    void sanctionedEntityName_isHit_caseInsensitive() {
        ScreeningResult result = adapter.screen(
                subject("멀쩡한이름", "Sanctioned Holdings PLC", List.of()));

        assertEquals(ScreeningResult.Status.HIT, result.status());
        assertEquals(1, result.hits().size());
        assertEquals("STUB_WATCHLIST", result.hits().get(0).listName());
        assertEquals("Sanctioned Holdings PLC", result.hits().get(0).matchedName());
        assertEquals(0.99, result.hits().get(0).score());
    }

    @Test
    void sanctionedUboName_isHit_evenWhenEntityIsClean() {
        ScreeningResult result = adapter.screen(
                subject("클린법인", "Clean Corp", List.of(ubo("MR SANCTIONED PERSON"))));

        assertEquals(ScreeningResult.Status.HIT, result.status());
        assertEquals("MR SANCTIONED PERSON", result.hits().get(0).matchedName());
    }

    @Test
    void reviewName_isNeedsReview_withFuzzyHit() {
        ScreeningResult result = adapter.screen(
                subject("리뷰테스트", "Review Trading LLC", List.of()));

        assertEquals(ScreeningResult.Status.NEEDS_REVIEW, result.status());
        assertEquals(1, result.hits().size());
        assertEquals("STUB_FUZZY", result.hits().get(0).listName());
        assertEquals(0.65, result.hits().get(0).score());
    }

    @Test
    void hitOutranksNeedsReview_whenBothTokensPresent() {
        ScreeningResult result = adapter.screen(
                subject("X", "Sanctioned Review Partners", List.of(ubo("Review Person"))));

        assertEquals(ScreeningResult.Status.HIT, result.status());
        // Only the watchlist hit is reported — the fuzzy pass never runs once HIT fires.
        assertTrue(result.hits().stream().allMatch(h -> "STUB_WATCHLIST".equals(h.listName())));
    }

    @Test
    void providerRef_isStableForSameSubject_andDiffersAcrossSubjects() {
        KybSubject a = subject("A", "Alpha Corp", List.of(ubo("Kim")));
        KybSubject b = subject("A", "Alpha Corp", List.of(ubo("Lee")));

        assertEquals(adapter.screen(a).providerRef(), adapter.screen(a).providerRef());
        assertNotEquals(adapter.screen(a).providerRef(), adapter.screen(b).providerRef());
        assertTrue(adapter.screen(a).providerRef().matches("stub-[0-9a-f]{12}"));
    }

    @Test
    void screenedAt_isMicrosTruncated() {
        ScreeningResult result = adapter.screen(subject("A", "Alpha Corp", List.of()));
        assertEquals(0, result.screenedAt().getNano() % 1000,
                "screenedAt must carry no sub-microsecond component (TIMESTAMP discipline)");
    }

    @Test
    void runFullKyb_embedsScreening_andVerifiesNothing() {
        KybRunResult clean = adapter.runFullKyb(subject("클린", "Clean Corp", List.of()));
        assertEquals(ScreeningResult.Status.NOT_SCREENED_NO_PROVIDER, clean.screening().status());
        // No register was contacted, so nothing is verified (T1-4): these used to
        // read true off a CLEAR the stub can no longer produce.
        assertFalse(clean.licenseVerified());
        assertFalse(clean.uboVerified());
        assertFalse(clean.registryVerified());
        assertTrue(clean.providerRef().endsWith("-full"));

        KybRunResult hit = adapter.runFullKyb(subject("X", "Sanctioned Corp", List.of()));
        assertEquals(ScreeningResult.Status.HIT, hit.screening().status());
        assertFalse(hit.licenseVerified());
        assertFalse(hit.uboVerified());
        assertFalse(hit.registryVerified());
    }

    @Test
    void nullSubject_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> adapter.screen(null));
    }

    @Test
    void nullUboList_isTreatedAsEmpty() {
        ScreeningResult result = adapter.screen(subject("A", "Alpha Corp", null));
        assertEquals(ScreeningResult.Status.NOT_SCREENED_NO_PROVIDER, result.status());
    }
}
