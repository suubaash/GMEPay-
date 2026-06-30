package com.gme.pay.reporting.persistence;

import com.gme.pay.reporting.domain.CommittedTransaction;
import com.gme.pay.reporting.domain.TransactionDirection;
import com.gme.pay.reporting.service.BokRecordPersistenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence tests for {@link BokRecordPersistenceService}. Verifies that:
 * <ul>
 *   <li>BOK FX1015 field #14 ({@code offerRateColl}) is persisted from the
 *       rate-locked committed-transaction source (NOT null);</li>
 *   <li>cross-border records are split into FX1014 / FX1015 filings;</li>
 *   <li>domestic/same-currency transactions are exempt (no row);</li>
 *   <li>a re-run for the same date is idempotent (no duplicate rows).</li>
 * </ul>
 */
@DataJpaTest
@Import({BokRecordPersistenceService.class, ReportFilingService.class})
class BokRecordPersistenceServiceTest {

    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 5, 20);

    @Autowired
    BokRecordPersistenceService service;

    @Autowired
    BokReportRecordRepository recordRepository;

    @Autowired
    ReportFilingRepository filingRepository;

    private static CommittedTransaction inbound() {
        return new CommittedTransaction(
                3001L, "SCH-IN-001", TransactionDirection.INBOUND, false,
                new BigDecimal("1.01010103"),   // offerRateColl — FX1015 field #14
                new BigDecimal("1316.25000000"),
                new BigDecimal("38.99"), "USD",
                new BigDecimal("50000"), "KRW",
                new BigDecimal("37.04"),
                Instant.parse("2026-05-20T03:00:00Z"), 42L);
    }

    private static CommittedTransaction outbound() {
        return new CommittedTransaction(
                3002L, "SCH-OUT-001", TransactionDirection.OUTBOUND, false,
                new BigDecimal("1.00500000"),
                new BigDecimal("0.99502488"),
                new BigDecimal("105.00"), "USD",
                new BigDecimal("100.00"), "USD",
                new BigDecimal("104.47"),
                Instant.parse("2026-05-20T04:00:00Z"), 43L);
    }

    private static CommittedTransaction domestic() {
        return new CommittedTransaction(
                3003L, "SCH-DOM-001", TransactionDirection.DOMESTIC, true,
                null, null,
                new BigDecimal("15000"), "KRW",
                new BigDecimal("15000"), "KRW",
                null,
                Instant.parse("2026-05-20T05:00:00Z"), 42L);
    }

    @Test
    @DisplayName("FX1015 record persists offerRateColl (BOK field #14) from the txn source")
    void persist_populatesOfferRateColl() {
        int inserted = service.persistForDate(
                List.of(inbound(), outbound(), domestic()), REPORT_DATE);

        // Two cross-border txns persisted; domestic is exempt.
        assertEquals(2, inserted);
        assertEquals(2, recordRepository.count());

        BokReportRecordEntity fx1015 = recordRepository.findByReportDateOrderByTxnIdAsc(REPORT_DATE)
                .stream().filter(r -> r.getTxnId() == 3001L).findFirst().orElseThrow();

        assertEquals("FX1015", fx1015.getReportType());
        assertNotNull(fx1015.getOfferRateColl(),
                "FX1015 field #14 (offer_rate_coll) must be populated, not null");
        assertEquals(0, new BigDecimal("1.01010103").compareTo(fx1015.getOfferRateColl()),
                "offer_rate_coll must be carried verbatim from the committed-transaction source");
        assertEquals(42L, fx1015.getPartnerId());
    }

    @Test
    @DisplayName("cross-border txns open FX1014 + FX1015 filings with correct counts")
    void persist_opensTypedFilings() {
        service.persistForDate(List.of(inbound(), outbound()), REPORT_DATE);

        ReportFiling fx1014 = filingRepository
                .findByLaneAndReportTypeAndReportDate("BOK", "FX1014", REPORT_DATE).orElseThrow();
        ReportFiling fx1015 = filingRepository
                .findByLaneAndReportTypeAndReportDate("BOK", "FX1015", REPORT_DATE).orElseThrow();

        assertEquals(1, fx1014.getRecordCount());
        assertEquals(1, fx1015.getRecordCount());
        assertEquals(ReportFiling.Status.GENERATED.name(), fx1015.getSubmissionStatus());
    }

    @Test
    @DisplayName("re-running for the same date is idempotent (no duplicate records)")
    void persist_idempotentOnRerun() {
        service.persistForDate(List.of(inbound(), outbound()), REPORT_DATE);
        int secondRunInserted = service.persistForDate(List.of(inbound(), outbound()), REPORT_DATE);

        assertEquals(0, secondRunInserted, "Second run must insert nothing");
        assertEquals(2, recordRepository.count(), "No duplicate bok_report_record rows");
        // Still exactly one filing per type.
        assertEquals(2, filingRepository.count());
    }

    @Test
    @DisplayName("offerRateColl persists with full DECIMAL(20,8) precision")
    void persist_preservesScale() {
        service.persistForDate(List.of(inbound()), REPORT_DATE);
        BokReportRecordEntity rec = recordRepository.findByReportDateOrderByTxnIdAsc(REPORT_DATE).get(0);
        assertTrue(rec.getOfferRateColl().scale() >= 8 || rec.getOfferRateColl()
                        .compareTo(new BigDecimal("1.01010103")) == 0,
                "8-dp offer_rate_coll must round-trip without loss");
    }
}
