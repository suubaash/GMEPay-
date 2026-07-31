package com.gme.pay.settlement.netting;

import com.gme.pay.settlement.calculator.MultilateralNettingCalculator;
import com.gme.pay.settlement.persistence.CorridorReconSummaryEntity;
import com.gme.pay.settlement.persistence.CorridorReconSummaryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GAP T4-5: {@link MultilateralNettingCalculator} now has a production caller, and this pins what that
 * caller produces from a known fixture.
 *
 * <p>Fixture — three reconciled corridor-days:
 * <pre>
 *   SENDMN  KRW-&gt;MNT   hub owes  +12,000.00 USD
 *   SENDMN  MNT-&gt;KRW   hub owed   −4,000.00 USD   (a two-sided corridor against the same counterparty)
 *   NINEPAY KRW-&gt;VND   hub owes   +3,000.00 USD
 * </pre>
 * Gross funding = 12,000 + 4,000 + 3,000 = 19,000. After netting WITHIN each counterparty:
 * SENDMN nets to +8,000, NINEPAY stays +3,000 ⇒ net funding 11,000, efficiency
 * (19,000 − 11,000) / 19,000 = 42.11%. NINEPAY is <b>not</b> netted against SENDMN — cross-counterparty
 * netting needs a scheme-level agreement the platform has no basis to assert.
 */
class NettingReportServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 6, 13);
    private static final LocalDate TO = LocalDate.of(2026, 6, 15);

    private final CorridorReconSummaryRepository repo = mock(CorridorReconSummaryRepository.class);
    private final NettingReportService service =
            new NettingReportService(repo, new MultilateralNettingCalculator());

    private static CorridorReconSummaryEntity row(String scheme, String corridor, String usdOwed,
                                                  boolean feedAvailable) {
        CorridorReconSummaryEntity e = new CorridorReconSummaryEntity();
        e.setSettlementDate(LocalDate.of(2026, 6, 14));
        e.setScheme(scheme);
        e.setCorridor(corridor);
        e.setBatchId(scheme + "-3WAY-20260614");
        e.setLocalCurrency("MNT");
        e.setUsdOwedScheme(new BigDecimal(usdOwed));
        e.setSchemeFeedAvailable(feedAvailable);
        e.setGeneratedAt(Instant.parse("2026-06-14T02:30:00Z"));
        return e;
    }

    @Test
    @DisplayName("the report nets WITHIN a counterparty and produces the loop-C headline numbers")
    void netsWithinCounterparty() {
        when(repo.findBySettlementDateBetweenOrderBySettlementDateAscSchemeAsc(any(), any()))
                .thenReturn(List.of(
                        row("SENDMN", "KRW->MNT", "12000.00", false),
                        row("SENDMN", "MNT->KRW", "-4000.00", false),
                        row("NINEPAY", "KRW->VND", "3000.00", false)));

        NettingReportResponse report = service.report(FROM, TO);

        assertThat(report.from()).isEqualTo(FROM);
        assertThat(report.to()).isEqualTo(TO);
        assertThat(report.obligationCount()).isEqualTo(3);
        assertThat(report.grossFundingUsd()).isEqualByComparingTo("19000.00");
        assertThat(report.netFundingUsd()).isEqualByComparingTo("11000.00");
        assertThat(report.nettingEfficiencyPct()).isEqualByComparingTo("42.11");

        assertThat(report.positions()).extracting(NettingReportResponse.Position::counterparty)
                .containsExactly("SENDMN", "NINEPAY");
        assertThat(report.positions().get(0).grossUsd()).isEqualByComparingTo("16000.00");
        assertThat(report.positions().get(0).netUsd()).isEqualByComparingTo("8000.00");
        assertThat(report.positions().get(0).corridors()).containsExactly("KRW->MNT", "MNT->KRW");
        assertThat(report.positions().get(1).netUsd())
                .as("NINEPAY is never netted against SENDMN")
                .isEqualByComparingTo("3000.00");

        assertThat(report.sourceCorridors())
                .containsExactly("SENDMN:KRW->MNT", "SENDMN:MNT->KRW", "NINEPAY:KRW->VND");
    }

    @Test
    @DisplayName("one-directional corridors offset nothing — efficiency is 0.00, and that is the truth")
    void oneDirectionalCorridorsYieldZeroEfficiency() {
        when(repo.findBySettlementDateBetweenOrderBySettlementDateAscSchemeAsc(any(), any()))
                .thenReturn(List.of(
                        row("SENDMN", "KRW->MNT", "12000.00", false),
                        row("NINEPAY", "KRW->VND", "3000.00", false)));

        NettingReportResponse report = service.report(FROM, TO);

        assertThat(report.grossFundingUsd()).isEqualByComparingTo("15000.00");
        assertThat(report.netFundingUsd()).isEqualByComparingTo("15000.00");
        assertThat(report.nettingEfficiencyPct()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("an empty window is empty, with a NULL efficiency — not a 0% achievement")
    void emptyWindow() {
        when(repo.findBySettlementDateBetweenOrderBySettlementDateAscSchemeAsc(any(), any()))
                .thenReturn(List.of());

        NettingReportResponse report = service.report(FROM, TO);

        assertThat(report.obligationCount()).isZero();
        assertThat(report.positions()).isEmpty();
        assertThat(report.grossFundingUsd()).isEqualByComparingTo("0.00");
        assertThat(report.nettingEfficiencyPct())
                .as("no obligations means the ratio is undefined, not zero")
                .isNull();
        assertThat(report.schemeFeedBackedAll()).isFalse();
    }

    @Test
    @DisplayName("EVERY report states that netting is not applied to funding, and why")
    void reportIsAlwaysReportingOnly() {
        when(repo.findBySettlementDateBetweenOrderBySettlementDateAscSchemeAsc(any(), any()))
                .thenReturn(List.of(row("SENDMN", "KRW->MNT", "12000.00", false)));

        NettingReportResponse report = service.report(FROM, TO);

        assertThat(report.applied()).isFalse();
        assertThat(report.appliedNote())
                .isEqualTo(NettingReportResponse.REPORTING_ONLY_NOTE)
                .contains("REPORTING ONLY")
                .contains("decision");
    }

    @Test
    @DisplayName("scheme-feed provenance is reported, not assumed: one unbacked row makes it false")
    void schemeFeedProvenance() {
        when(repo.findBySettlementDateBetweenOrderBySettlementDateAscSchemeAsc(any(), any()))
                .thenReturn(List.of(
                        row("SENDMN", "KRW->MNT", "12000.00", true),
                        row("NINEPAY", "KRW->VND", "3000.00", false)));

        assertThat(service.report(FROM, TO).schemeFeedBackedAll()).isFalse();

        when(repo.findBySettlementDateBetweenOrderBySettlementDateAscSchemeAsc(any(), any()))
                .thenReturn(List.of(row("SENDMN", "KRW->MNT", "12000.00", true)));
        assertThat(service.report(FROM, TO).schemeFeedBackedAll()).isTrue();
    }

    @Test
    @DisplayName("the window is validated: both bounds required, ordered, and bounded")
    void windowValidation() {
        assertThatThrownBy(() -> service.report(null, TO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("required");
        assertThatThrownBy(() -> service.report(TO, FROM))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is after");
        assertThatThrownBy(() -> service.report(TO.minusYears(5), TO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maximum");
    }
}
