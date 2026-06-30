package com.gme.pay.reporting.persistence;

import com.gme.pay.reporting.domain.CommittedTransaction;
import com.gme.pay.reporting.infrastructure.RestCommittedFxTransactionPort;
import com.gme.pay.reporting.service.BokRecordPersistenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * End-to-end wiring test: {@code CommittedFxView} JSON returned by a mock
 * transaction-mgmt {@code GET /v1/transactions/fx-committed} (transaction-mgmt NOT running)
 * flows through {@link RestCommittedFxTransactionPort} into
 * {@link BokRecordPersistenceService}, and BOK FX1015 field #14 ({@code offer_rate_coll})
 * plus {@code cross_rate} are persisted from real projection data (not null, not synthesised).
 */
@DataJpaTest
@Import({BokRecordPersistenceService.class, ReportFilingService.class})
class CommittedFxEndToEndPersistenceTest {

    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 5, 20);

    @Autowired
    BokRecordPersistenceService persistenceService;

    @Autowired
    BokReportRecordRepository recordRepository;

    @Test
    @DisplayName("CommittedFxView JSON → REST adapter → persistence: FX1015 #14 + cross_rate populated end-to-end")
    void committedFxView_flowsThroughAdapterIntoPersistedFx1015Field14() {
        // --- mock transaction-mgmt projection (no live upstream) ---
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer mockServer = MockRestServiceServer.createServer(restTemplate);
        RestClient restClient = RestClient.builder()
                .requestFactory(restTemplate.getRequestFactory())
                .baseUrl("http://transaction-mgmt:8080")
                .build();
        RestCommittedFxTransactionPort port = new RestCommittedFxTransactionPort(restClient);

        mockServer.expect(requestTo(
                "http://transaction-mgmt:8080/v1/transactions/fx-committed"
                        + "?from=2026-05-20&to=2026-05-20"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(committedFxViewJson(), MediaType.APPLICATION_JSON));

        // --- adapter maps CommittedFxView → domain, persistence writes bok_report_record ---
        List<CommittedTransaction> committed = port.fetchCommittedFx(REPORT_DATE, REPORT_DATE, null);
        mockServer.verify();
        assertEquals(1, committed.size());

        int inserted = persistenceService.persistForDate(committed, REPORT_DATE);
        assertEquals(1, inserted);

        BokReportRecordEntity fx1015 = recordRepository
                .findByReportDateOrderByTxnIdAsc(REPORT_DATE).get(0);

        assertEquals("FX1015", fx1015.getReportType());
        assertEquals(42L, fx1015.getPartnerId(), "partnerId sourced from CommittedFxView");
        assertNotNull(fx1015.getOfferRateColl(),
                "FX1015 field #14 (offer_rate_coll) must be sourced from CommittedFxView, not null");
        assertEquals(0, new BigDecimal("1.01010103").compareTo(fx1015.getOfferRateColl()),
                "offer_rate_coll (FX1015 #14) flows from CommittedFxView.offerRateColl verbatim");
        assertEquals(0, new BigDecimal("1316.25000000").compareTo(fx1015.getCrossRate()),
                "cross_rate flows from CommittedFxView.crossRate verbatim");
    }

    private static String committedFxViewJson() {
        return """
                [
                  {
                    "txnId": 3001,
                    "txnRef": "SCH-IN-001",
                    "partnerId": 42,
                    "direction": "INBOUND",
                    "sameCcyShortcircuit": false,
                    "offerRateColl": "1.01010103",
                    "crossRate": "1316.25000000",
                    "collectionAmount": "38.99",
                    "collectionCcy": "USD",
                    "payoutAmount": "50000",
                    "payoutCcy": "KRW",
                    "usdAmount": "37.04",
                    "collectionMarginUsd": "0.50",
                    "payoutMarginUsd": "0.10",
                    "committedAt": "2026-05-20T03:00:00Z"
                  }
                ]
                """;
    }
}
