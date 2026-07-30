package com.gme.pay.payment.dayclose;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.payment.opsrun.LedgerOpsRunTrigger;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

/**
 * <b>T2-5 / CFO#10 persistence proof</b> for the day-close artifact ({@code day_close_reports}, Flyway V009,
 * whose "Done =" asked for a close that is "persisted and exportable").
 *
 * <p>Two properties matter here and neither is obvious:
 *
 * <ol>
 *   <li>the report <b>round-trips</b>. It is stored as JSON, so a reader months later gets back the document
 *       that was reviewed — including its variances, their {@code UNRESOLVED_DECISION} treatment and the decimal
 *       amounts. A report that serialised lossily would quietly become a different document;</li>
 *   <li>a re-run <b>overwrites</b> the same business date rather than adding a second close of the same day. The
 *       report is a derived view, so a re-run is a correction.</li>
 * </ol>
 *
 * <p>Runs against the real Flyway schema so the {@code business_date} unique constraint and the broken-out count
 * columns are exercised rather than assumed.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DayCloseReportStoreTest {

    private static final LocalDate D = LocalDate.of(2026, 7, 26);
    private static final Instant NOW = Instant.parse("2026-07-27T02:00:00Z");

    @Autowired
    private DayCloseReportRepository repository;

    private DayCloseReportStore store;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        // Mirrors the Boot-configured mapper's relevant behaviour (JSR-310 support); the production bean is
        // autowired in the real service.
        store = new DayCloseReportStore(repository, new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    private static DayCloseReport report(boolean clean, List<DayCloseReport.Variance> variances,
                                        List<DayCloseReport.UnresolvedDecision> decisions) {
        return new DayCloseReport(D, NOW, "Asia/Seoul", clean,
                List.of(DayCloseReport.Leg.ok(DayCloseReport.LEG_TRANSACTIONS, "transaction-mgmt"),
                        DayCloseReport.Leg.unavailable(DayCloseReport.LEG_PREFUNDING, "prefunding",
                                "no partner codes configured")),
                List.of(new DayCloseReport.CorridorSummary("sendmn KRW->MNT", "sendmn", "KRW", "MNT", 2,
                        new BigDecimal("2025000"), new BigDecimal("4500000"), BigDecimal.ZERO,
                        new BigDecimal("1500"), 0)),
                List.of(new DayCloseReport.CurrencySummary("KRW", 2, new BigDecimal("2025000"), 0,
                        BigDecimal.ZERO, new BigDecimal("1000"), new BigDecimal("1000"), BigDecimal.ZERO,
                        true, 4)),
                new DayCloseReport.LedgerSummary(true, false, 0, 0, List.of()),
                List.of(new DayCloseReport.PrefundingSummary("SENDMN", 2, new BigDecimal("-1495"),
                        new BigDecimal("1495"), new BigDecimal("1500"), new BigDecimal("5"))),
                new DayCloseReport.PostingBacklog(1, 1, 0, NOW.minusSeconds(3600)),
                FxExposureReport.unavailable(D, D, NOW, "not measured in this fixture"),
                variances, decisions);
    }

    private static DayCloseReport.Variance unresolvedVariance() {
        return new DayCloseReport.Variance("UNMAPPED_COMPONENT", "component=PARTNER_COMMISSION_SHARE", "KRW",
                "recorded", new BigDecimal("378.0000"), "on the books", BigDecimal.ZERO,
                new BigDecimal("378.0000"),
                DayCloseReport.Variance.Treatment.UNRESOLVED_DECISION, "not a defect and not netted");
    }

    @Test
    @DisplayName("the stored report round-trips, keeping its variances, treatments and decimal amounts")
    void theStoredReportRoundTrips() {
        DayCloseReport original = report(false, List.of(unresolvedVariance()),
                List.of(new DayCloseReport.UnresolvedDecision("T2-10", "PARTNER_COMMISSION_SHARE", "KRW",
                        new BigDecimal("378.0000"), "which account does the carve hit?",
                        "retained commission is overstated by exactly this")));

        store.upsert(original, LedgerOpsRunTrigger.SCHEDULER);
        DayCloseReport reloaded = store.find(D).orElseThrow();

        assertThat(reloaded.businessDate()).isEqualTo(D);
        assertThat(reloaded.generatedAt()).isEqualTo(NOW);
        assertThat(reloaded.closeZone()).isEqualTo("Asia/Seoul");
        assertThat(reloaded.clean()).isFalse();
        assertThat(reloaded.corridors()).hasSize(1);
        assertThat(reloaded.corridors().get(0).collectedAmount()).isEqualByComparingTo("2025000");
        assertThat(reloaded.prefunding().get(0).delta()).isEqualByComparingTo("5");
        assertThat(reloaded.postingBacklog().outstanding()).isEqualTo(1);
        assertThat(reloaded.legs())
                .as("an unavailable leg keeps its reason, so a later reader knows it was not zero")
                .anySatisfy(l -> {
                    assertThat(l.leg()).isEqualTo(DayCloseReport.LEG_PREFUNDING);
                    assertThat(l.available()).isFalse();
                    assertThat(l.reason()).isEqualTo("no partner codes configured");
                });
        assertThat(reloaded.variances()).hasSize(1);
        assertThat(reloaded.variances().get(0).treatment())
                .as("the treatment must survive storage or the decision looks like a defect")
                .isEqualTo(DayCloseReport.Variance.Treatment.UNRESOLVED_DECISION);
        assertThat(reloaded.variances().get(0).delta()).isEqualByComparingTo("378.0000");
        assertThat(reloaded.unresolvedDecisions()).hasSize(1);
        assertThat(reloaded.unresolvedDecisions().get(0).registerItem()).isEqualTo("T2-10");
        assertThat(reloaded.fxExposure().available()).isFalse();
    }

    @Test
    @DisplayName("the headline columns are broken out so a monitor need not parse the JSON")
    void headlineColumnsAreBrokenOut() {
        store.upsert(report(false, List.of(unresolvedVariance()),
                List.of(new DayCloseReport.UnresolvedDecision("T2-10", "X", "KRW", BigDecimal.ONE, "q", "e"))),
                LedgerOpsRunTrigger.OPERATOR);

        DayCloseReportEntity row = repository.findByBusinessDate(D).orElseThrow();
        assertThat(row.isClean()).isFalse();
        assertThat(row.getVarianceCount()).isEqualTo(1);
        assertThat(row.getUnresolvedDecisionCount())
                .as("a day carrying an undecided treatment must be findable in SQL")
                .isEqualTo(1);
        assertThat(row.getUnavailableLegCount()).isEqualTo(1);
        assertThat(row.getTriggerSource()).isEqualTo(LedgerOpsRunTrigger.OPERATOR.name());
    }

    @Test
    @DisplayName("a re-run CORRECTS the same business date instead of adding a second close of that day")
    void aReRunOverwritesTheSameBusinessDate() {
        store.upsert(report(false, List.of(unresolvedVariance()), List.of()), LedgerOpsRunTrigger.SCHEDULER);
        Long firstId = repository.findByBusinessDate(D).orElseThrow().getId();

        // Same day, re-run after the variance was fixed.
        store.upsert(report(true, List.of(), List.of()), LedgerOpsRunTrigger.OPERATOR);

        assertThat(repository.findAll()).hasSize(1);
        DayCloseReportEntity row = repository.findByBusinessDate(D).orElseThrow();
        assertThat(row.getId()).as("the same row is corrected, not duplicated").isEqualTo(firstId);
        assertThat(row.isClean()).isTrue();
        assertThat(row.getVarianceCount()).isZero();
        assertThat(store.find(D).orElseThrow().variances()).isEmpty();
    }

    @Test
    @DisplayName("a date that was never closed is absent, not an empty report")
    void anUnclosedDateIsAbsent() {
        assertThat(store.find(D.minusDays(30))).isEmpty();
    }
}
