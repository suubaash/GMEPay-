package com.gme.pay.reporting;

import com.gme.pay.reporting.domain.CommittedTransaction;
import com.gme.pay.reporting.domain.TransactionDirection;
import com.gme.pay.reporting.dto.BokFxRecordDto;
import com.gme.pay.reporting.dto.ReportRequest;
import com.gme.pay.reporting.dto.ReportResponse;
import com.gme.pay.reporting.dto.ReportType;
import com.gme.pay.reporting.service.BokReportService;
import com.gme.pay.reporting.service.TransactionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 unit tests for {@link BokReportService}.
 * Uses a hand-rolled stub for {@link TransactionClient} — no Spring, no Mockito required.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>Domestic transactions are filtered out before mapping.</li>
 *   <li>Report-type filter (FX1014/FX1015/ALL) is applied correctly.</li>
 *   <li>offer_rate_coll (BOK FX1015 field #14) is preserved through the pipeline.</li>
 *   <li>Validation: missing 'from' throws; 'from' after 'to' throws.</li>
 * </ul>
 */
class BokReportServiceTest {

    private BokReportService service;

    // Three test transactions: 1 INBOUND, 1 OUTBOUND, 1 DOMESTIC
    private static final CommittedTransaction INBOUND_TXN = new CommittedTransaction(
            101L, "REF-101",
            TransactionDirection.INBOUND,
            false,
            new BigDecimal("1.01010103"),   // offerRateColl – BOK FX1015 #14
            new BigDecimal("1316.25000000"),
            new BigDecimal("38.99"), "USD",
            new BigDecimal("50000"), "KRW",
            new BigDecimal("37.04"),
            Instant.parse("2026-01-15T10:00:00Z"),
            1L);

    private static final CommittedTransaction OUTBOUND_TXN = new CommittedTransaction(
            102L, "REF-102",
            TransactionDirection.OUTBOUND,
            false,
            new BigDecimal("1.00500000"),
            new BigDecimal("0.99500000"),
            new BigDecimal("105.00"), "USD",
            new BigDecimal("100.00"), "USD",
            new BigDecimal("104.47"),
            Instant.parse("2026-01-15T11:00:00Z"),
            1L);

    private static final CommittedTransaction DOMESTIC_TXN = new CommittedTransaction(
            103L, "REF-103",
            TransactionDirection.DOMESTIC,
            true,
            null, null,
            new BigDecimal("15500"), "KRW",
            new BigDecimal("15000"), "KRW",
            null,
            Instant.parse("2026-01-15T12:00:00Z"),
            1L);

    @BeforeEach
    void setUp() {
        // Stub that always returns the three test transactions
        TransactionClient stub = (from, to, partnerId) ->
                List.of(INBOUND_TXN, OUTBOUND_TXN, DOMESTIC_TXN);
        service = new BokReportService(stub);
    }

    // =========================================================================
    // TEST 1: BOK_FX_ALL returns inbound + outbound but NOT domestic
    // =========================================================================

    @Test
    @DisplayName("BOK_FX_ALL returns cross-border records and excludes domestic transactions")
    void bokFxAll_excludesDomestic() {
        ReportRequest request = request(ReportType.BOK_FX_ALL);

        ReportResponse response = service.buildReport(request);

        assertEquals(2, response.getTotalCount(),
                "Should return 2 records: 1 INBOUND + 1 OUTBOUND; domestic is excluded");
        assertEquals(2, response.getRecords().size());
    }

    // =========================================================================
    // TEST 2: BOK_FX1015 filter returns only INBOUND records
    // =========================================================================

    @Test
    @DisplayName("BOK_FX1015 filter returns only inbound (FX1015) records")
    void bokFx1015Filter_returnsOnlyInbound() {
        ReportRequest request = request(ReportType.BOK_FX1015);

        ReportResponse response = service.buildReport(request);

        assertEquals(1, response.getTotalCount());
        BokFxRecordDto record = response.getRecords().get(0);
        assertEquals("FX1015", record.getReportType());
        assertEquals(101L, record.getTxnId());
    }

    // =========================================================================
    // TEST 3: BOK_FX1014 filter returns only OUTBOUND records
    // =========================================================================

    @Test
    @DisplayName("BOK_FX1014 filter returns only outbound (FX1014) records")
    void bokFx1014Filter_returnsOnlyOutbound() {
        ReportRequest request = request(ReportType.BOK_FX1014);

        ReportResponse response = service.buildReport(request);

        assertEquals(1, response.getTotalCount());
        BokFxRecordDto record = response.getRecords().get(0);
        assertEquals("FX1014", record.getReportType());
        assertEquals(102L, record.getTxnId());
    }

    // =========================================================================
    // TEST 4: offerRateColl (BOK FX1015 field #14) is preserved end-to-end
    // =========================================================================

    @Test
    @DisplayName("offer_rate_coll (BOK FX1015 field #14) is preserved through the service pipeline")
    void offerRateColl_isPreservedEndToEnd() {
        ReportRequest request = request(ReportType.BOK_FX1015);

        ReportResponse response = service.buildReport(request);

        BokFxRecordDto dto = response.getRecords().get(0);
        assertNotNull(dto.getOfferRateColl(),
                "offer_rate_coll (BOK FX1015 field #14) must not be null for INBOUND transaction");
        assertEquals(0, new BigDecimal("1.01010103").compareTo(dto.getOfferRateColl()),
                "offer_rate_coll must equal the original locked value from the committed transaction");
    }

    // =========================================================================
    // TEST 5: Missing 'from' parameter throws
    // =========================================================================

    @Test
    @DisplayName("Missing 'from' date throws NullPointerException")
    void missingFrom_throwsNullPointerException() {
        ReportRequest request = new ReportRequest();
        request.setTo(LocalDate.of(2026, 1, 31));

        assertThrows(NullPointerException.class, () -> service.buildReport(request));
    }

    // =========================================================================
    // TEST 6: 'from' after 'to' throws IllegalArgumentException
    // =========================================================================

    @Test
    @DisplayName("'from' after 'to' throws IllegalArgumentException")
    void fromAfterTo_throwsIllegalArgument() {
        ReportRequest request = new ReportRequest();
        request.setFrom(LocalDate.of(2026, 1, 31));
        request.setTo(LocalDate.of(2026, 1, 1));

        assertThrows(IllegalArgumentException.class, () -> service.buildReport(request));
    }

    // =========================================================================
    // TEST 7: Empty transaction list returns empty response
    // =========================================================================

    @Test
    @DisplayName("Empty transaction list returns report with zero records")
    void emptyTransactions_returnsZeroRecords() {
        TransactionClient emptyStub = (from, to, partnerId) -> List.of();
        BokReportService emptyService = new BokReportService(emptyStub);

        ReportResponse response = emptyService.buildReport(request(ReportType.BOK_FX_ALL));

        assertEquals(0, response.getTotalCount());
        assertNotNull(response.getGeneratedAt(), "generatedAt must be set even for empty results");
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private static ReportRequest request(ReportType type) {
        ReportRequest r = new ReportRequest();
        r.setFrom(LocalDate.of(2026, 1, 1));
        r.setTo(LocalDate.of(2026, 1, 31));
        r.setReportType(type);
        return r;
    }
}
