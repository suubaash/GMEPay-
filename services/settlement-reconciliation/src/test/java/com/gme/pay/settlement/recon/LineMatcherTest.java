package com.gme.pay.settlement.recon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 unit tests for {@link LineMatcher}.
 * No Spring context, no Docker, no Testcontainers.
 *
 * Covers the unmatched-record detection required by the wave scope.
 */
class LineMatcherTest {

    private LineMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new LineMatcher();
    }

    @Test
    @DisplayName("All merchants match -> no unmatched lines")
    void allMatch_noUnmatched() {
        Map<String, BigDecimal> gme = Map.of(
                "M1", new BigDecimal("34720"),
                "M2", new BigDecimal("100000")
        );
        Map<String, BigDecimal> scheme = Map.of(
                "M1", new BigDecimal("34720"),
                "M2", new BigDecimal("100000")
        );

        List<ReconLine> unmatched = matcher.unmatchedLines(gme, scheme);

        assertTrue(unmatched.isEmpty(), "Expected zero unmatched lines when all amounts agree");
    }

    @Test
    @DisplayName("Amount discrepancy -> DISCREPANCY line with correct delta")
    void amountDiscrepancy_flagged() {
        Map<String, BigDecimal> gme = Map.of("M1", new BigDecimal("34720"));
        Map<String, BigDecimal> scheme = Map.of("M1", new BigDecimal("34000"));

        List<ReconLine> all = matcher.match(gme, scheme);
        assertEquals(1, all.size());

        ReconLine line = all.get(0);
        assertEquals(MatchStatus.DISCREPANCY, line.matchStatus());
        assertEquals(new BigDecimal("720"), line.discrepancyAmount());
    }

    @Test
    @DisplayName("Merchant in GME but absent from scheme -> MISSING_SCHEME")
    void missingFromScheme_flagged() {
        Map<String, BigDecimal> gme = Map.of("M1", new BigDecimal("34720"));
        Map<String, BigDecimal> scheme = Map.of(); // empty — ZeroPay did not credit M1

        List<ReconLine> unmatched = matcher.unmatchedLines(gme, scheme);

        assertEquals(1, unmatched.size());
        assertEquals(MatchStatus.MISSING_SCHEME, unmatched.get(0).matchStatus());
        assertEquals("M1", unmatched.get(0).merchantId());
    }

    @Test
    @DisplayName("Merchant in scheme but absent from GME -> MISSING_INTERNAL")
    void missingFromInternal_flagged() {
        Map<String, BigDecimal> gme = Map.of();
        Map<String, BigDecimal> scheme = Map.of("M99", new BigDecimal("5000"));

        List<ReconLine> unmatched = matcher.unmatchedLines(gme, scheme);

        assertEquals(1, unmatched.size());
        assertEquals(MatchStatus.MISSING_INTERNAL, unmatched.get(0).matchStatus());
    }

    @Test
    @DisplayName("Mixed result: one match, one discrepancy, one missing")
    void mixedResult_correctCounts() {
        Map<String, BigDecimal> gme = Map.of(
                "M1", new BigDecimal("34720"),  // will match
                "M2", new BigDecimal("100000"), // will discrepancy
                "M3", new BigDecimal("50000")   // will be MISSING_SCHEME
        );
        Map<String, BigDecimal> scheme = Map.of(
                "M1", new BigDecimal("34720"),  // match
                "M2", new BigDecimal("99000")   // discrepancy: delta=1000
        );

        List<ReconLine> all = matcher.match(gme, scheme);
        assertEquals(3, all.size());

        long matched = all.stream().filter(l -> l.matchStatus() == MatchStatus.MATCHED).count();
        long discrepancy = all.stream().filter(l -> l.matchStatus() == MatchStatus.DISCREPANCY).count();
        long missingScheme = all.stream().filter(l -> l.matchStatus() == MatchStatus.MISSING_SCHEME).count();

        assertEquals(1, matched);
        assertEquals(1, discrepancy);
        assertEquals(1, missingScheme);
    }

    @Test
    @DisplayName("Null inputs do not throw and produce empty result")
    void nullInputs_returnEmpty() {
        List<ReconLine> result = matcher.match(null, null);
        assertTrue(result.isEmpty());
    }
}
