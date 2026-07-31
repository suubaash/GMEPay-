package com.gme.pay.settlement.persistence;

import com.gme.pay.settlement.recon.MatchStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * H2 (PostgreSQL-mode) slice proving the GAP T2-2 schema and its JPA mappings agree: Flyway V010's
 * {@code scheme} / {@code txn_ref} columns on {@code recon_exceptions} and V011's
 * {@code corridor_recon_summary} table, including its one-row-per-(date, scheme) uniqueness — the
 * constraint the reconciler's idempotent re-run relies on.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class CorridorReconPersistenceIT {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 28);

    @Autowired
    private ReconExceptionRepository reconExceptionRepository;

    @Autowired
    private CorridorReconSummaryRepository summaryRepository;

    @Test
    void reconException_carriesSchemeAndTxnRef_roundTrip() {
        ReconExceptionEntity e = ReconExceptionEntity.fromCorridorLine(
                "SENDMN-3WAY-20260728", "SENDMN", "SENDMN-abc",
                new com.gme.pay.settlement.recon.ReconLine(
                        "merchant-1",
                        new BigDecimal("74.44444444"),
                        new BigDecimal("77.74390000"),
                        new BigDecimal("3.29945556"),
                        MatchStatus.RATE_BASIS_VARIANCE),
                Instant.parse("2026-07-29T02:30:00Z"));
        e.setResolutionNote("USD collected is short of the USD owed SENDMN at its registered rate");
        reconExceptionRepository.saveAndFlush(e);

        ReconExceptionEntity loaded = reconExceptionRepository
                .findByBatchId("SENDMN-3WAY-20260728").get(0);
        assertThat(loaded.getScheme()).isEqualTo("SENDMN");
        assertThat(loaded.getTxnRef()).isEqualTo("SENDMN-abc");
        assertThat(loaded.getMatchStatus()).isEqualTo(MatchStatus.RATE_BASIS_VARIANCE);
        assertThat(loaded.getDiscrepancyAmount()).isEqualByComparingTo("3.29945556");
        // The ops lifecycle fields still apply unchanged to a cross-border break.
        assertThat(loaded.getExceptionStatus())
                .isEqualTo(com.gme.pay.settlement.exception.ExceptionStatus.OPEN);
    }

    @Test
    void corridorSummary_roundTripsSignedVarianceAndIsUniquePerDateAndScheme() {
        summaryRepository.saveAndFlush(summary(DATE, new BigDecimal("-3.29945556")));

        CorridorReconSummaryEntity loaded = summaryRepository
                .findBySettlementDateAndScheme(DATE, "SENDMN").orElseThrow();
        // NUMERIC(20,8) must keep the SIGN — a stored absolute value would hide the loss.
        assertThat(loaded.getRateBasisVarianceUsd()).isEqualByComparingTo("-3.29945556");
        assertThat(loaded.getCumulativeVarianceUsd()).isEqualByComparingTo("-3.29945556");
        assertThat(loaded.getLocalCurrency()).isEqualTo("MNT");
        assertThat(loaded.isSchemeFeedAvailable()).isFalse();

        assertThatThrownBy(() -> summaryRepository.saveAndFlush(summary(DATE, BigDecimal.ONE)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void corridorSummary_seriesReadsOldestFirst() {
        summaryRepository.saveAndFlush(summary(DATE.plusDays(1), new BigDecimal("1.00")));
        summaryRepository.saveAndFlush(summary(DATE, new BigDecimal("2.00")));

        assertThat(summaryRepository.findBySchemeOrderBySettlementDateAsc("SENDMN"))
                .extracting(CorridorReconSummaryEntity::getSettlementDate)
                .containsExactly(DATE, DATE.plusDays(1));
    }

    private static CorridorReconSummaryEntity summary(LocalDate date, BigDecimal variance) {
        CorridorReconSummaryEntity e = new CorridorReconSummaryEntity();
        e.setSettlementDate(date);
        e.setScheme("SENDMN");
        e.setCorridor("KRW->MNT");
        e.setBatchId("SENDMN-3WAY-" + date.toString().replace("-", ""));
        e.setTxnCount(1);
        e.setChargedKrw(new BigDecimal("100500"));
        e.setLocalPaid(new BigDecimal("255000"));
        e.setLocalCurrency("MNT");
        e.setUsdDeducted(new BigDecimal("74.44444444"));
        e.setUsdOwedScheme(new BigDecimal("77.74390000"));
        e.setRateBasisVarianceUsd(variance);
        e.setCumulativeVarianceUsd(variance);
        e.setFallbackRateBasisCount(1);
        e.setBreakCount(1);
        e.setBreakValueUsd(new BigDecimal("3.29945556"));
        e.setSchemeFeedAvailable(false);
        e.setGeneratedAt(Instant.parse("2026-07-29T02:30:00Z"));
        return e;
    }
}
