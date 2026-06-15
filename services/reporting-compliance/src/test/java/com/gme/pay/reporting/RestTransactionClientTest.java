package com.gme.pay.reporting;

import com.gme.pay.reporting.domain.CommittedTransaction;
import com.gme.pay.reporting.domain.TransactionDirection;
import com.gme.pay.reporting.infrastructure.RestTransactionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Unit tests for {@link RestTransactionClient} using {@link MockRestServiceServer}.
 *
 * <p>No real transaction-mgmt service is needed. Tests verify:
 * <ul>
 *   <li>The client calls the correct URI with correct query parameters.</li>
 *   <li>JSON is correctly deserialized into {@link CommittedTransaction} domain objects.</li>
 *   <li>Pagination: the client pages through all results when total_pages > 1.</li>
 *   <li>Null/missing direction records are skipped gracefully.</li>
 *   <li>Single-page empty response returns an empty list (never null).</li>
 * </ul>
 */
class RestTransactionClientTest {

    private MockRestServiceServer mockServer;
    private RestTransactionClient client;

    @BeforeEach
    void setUp() {
        // Use RestTemplate adapter so MockRestServiceServer can intercept calls.
        // RestClient.builder().requestFactory() accepts a RestTemplate's ClientHttpRequestFactory.
        RestTemplate restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);

        RestClient restClient = RestClient.builder()
                .requestFactory(restTemplate.getRequestFactory())
                .baseUrl("http://transaction-mgmt:8080")
                .build();

        client = new RestTransactionClient(restClient);
    }

    // =========================================================================
    // TEST 1: Single page of two transactions — both deserialized correctly
    // =========================================================================

    @Test
    @DisplayName("fetchCommitted: single page response with INBOUND+OUTBOUND transactions")
    void fetchCommitted_singlePage_twoTransactions() {
        mockServer.expect(requestTo(
                "http://transaction-mgmt:8080/v1/transactions"
                + "?from=2026-01-15&to=2026-01-15&size=500&page=0&status=COMMITTED"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(singlePageTwoRecordsJson(), MediaType.APPLICATION_JSON));

        List<CommittedTransaction> result =
                client.fetchCommitted(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 15), null);

        mockServer.verify();

        assertEquals(2, result.size(), "Should return exactly 2 transactions");

        // First record: INBOUND
        CommittedTransaction inbound = result.get(0);
        assertEquals(1001L, inbound.getTxnId());
        assertEquals("TXN-2026-001", inbound.getTxnRef());
        assertEquals(TransactionDirection.INBOUND, inbound.getDirection());
        assertFalse(inbound.isSameCcyShortcircuit());
        assertEquals(0, new BigDecimal("1.01010103").compareTo(inbound.getOfferRateColl()),
                "offerRateColl (BOK FX1015 field #14) must be deserialized correctly");
        assertEquals(0, new BigDecimal("1316.25000000").compareTo(inbound.getCrossRate()));
        assertEquals("USD", inbound.getCollectionCcy());
        assertEquals(0, new BigDecimal("38.9867").compareTo(inbound.getCollectionAmount()));
        assertEquals("KRW", inbound.getPayoutCcy());
        assertEquals(42L, inbound.getPartnerId());

        // Second record: OUTBOUND
        CommittedTransaction outbound = result.get(1);
        assertEquals(1002L, outbound.getTxnId());
        assertEquals(TransactionDirection.OUTBOUND, outbound.getDirection());
    }

    // =========================================================================
    // TEST 2: Pagination — two pages, client fetches both
    // =========================================================================

    @Test
    @DisplayName("fetchCommitted: paginates through two pages of results")
    void fetchCommitted_twoPages_fetchesBoth() {
        // Page 0 response
        mockServer.expect(requestTo(
                "http://transaction-mgmt:8080/v1/transactions"
                + "?from=2026-01-01&to=2026-01-31&size=500&page=0&status=COMMITTED"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(pageJson(0, 2, 1001L, "INBOUND"), MediaType.APPLICATION_JSON));

        // Page 1 response
        mockServer.expect(requestTo(
                "http://transaction-mgmt:8080/v1/transactions"
                + "?from=2026-01-01&to=2026-01-31&size=500&page=1&status=COMMITTED"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(pageJson(1, 2, 1002L, "OUTBOUND"), MediaType.APPLICATION_JSON));

        List<CommittedTransaction> result =
                client.fetchCommitted(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null);

        mockServer.verify();
        assertEquals(2, result.size(), "Should collect 1 record from each of 2 pages");
        assertEquals(1001L, result.get(0).getTxnId());
        assertEquals(1002L, result.get(1).getTxnId());
    }

    // =========================================================================
    // TEST 3: Partner filter appended to URI
    // =========================================================================

    @Test
    @DisplayName("fetchCommitted: partnerId is appended to query string when provided")
    void fetchCommitted_withPartnerId_appendsToUri() {
        mockServer.expect(requestTo(
                "http://transaction-mgmt:8080/v1/transactions"
                + "?from=2026-03-01&to=2026-03-31&size=500&page=0&status=COMMITTED&partnerId=99"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(emptyPageJson(), MediaType.APPLICATION_JSON));

        List<CommittedTransaction> result =
                client.fetchCommitted(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), 99L);

        mockServer.verify();
        assertTrue(result.isEmpty(), "Empty page response should return empty list");
    }

    // =========================================================================
    // TEST 4: Empty response returns empty list — never null
    // =========================================================================

    @Test
    @DisplayName("fetchCommitted: empty page returns empty list, not null")
    void fetchCommitted_emptyPage_returnsEmptyList() {
        mockServer.expect(requestTo(
                "http://transaction-mgmt:8080/v1/transactions"
                + "?from=2026-06-01&to=2026-06-01&size=500&page=0&status=COMMITTED"))
                .andRespond(withSuccess(emptyPageJson(), MediaType.APPLICATION_JSON));

        List<CommittedTransaction> result =
                client.fetchCommitted(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1), null);

        assertNotNull(result, "Result must never be null");
        assertTrue(result.isEmpty());
    }

    // =========================================================================
    // TEST 5: Unknown direction is skipped gracefully
    // =========================================================================

    @Test
    @DisplayName("fetchCommitted: record with unknown direction is skipped without throwing")
    void fetchCommitted_unknownDirection_isSkipped() {
        mockServer.expect(requestTo(
                "http://transaction-mgmt:8080/v1/transactions"
                + "?from=2026-04-01&to=2026-04-01&size=500&page=0&status=COMMITTED"))
                .andRespond(withSuccess(unknownDirectionJson(), MediaType.APPLICATION_JSON));

        List<CommittedTransaction> result =
                client.fetchCommitted(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 1), null);

        // The bad record is skipped; list is empty (not exception)
        assertNotNull(result);
        assertEquals(0, result.size(), "Record with unknown direction must be skipped");
    }

    // =========================================================================
    // JSON fixtures
    // =========================================================================

    private static String singlePageTwoRecordsJson() {
        return """
                {
                  "content": [
                    {
                      "txn_id": 1001,
                      "txn_ref": "TXN-2026-001",
                      "direction": "INBOUND",
                      "same_ccy_shortcircuit": false,
                      "offer_rate_coll": "1.01010103",
                      "cross_rate": "1316.25000000",
                      "collection_amount": "38.9867",
                      "collection_ccy": "USD",
                      "payout_amount": "50000",
                      "payout_ccy": "KRW",
                      "usd_amount": "37.0370",
                      "committed_at": "2026-01-15T10:00:00Z",
                      "partner_id": 42
                    },
                    {
                      "txn_id": 1002,
                      "txn_ref": "TXN-2026-002",
                      "direction": "OUTBOUND",
                      "same_ccy_shortcircuit": false,
                      "offer_rate_coll": "1.00500000",
                      "cross_rate": "0.99502488",
                      "collection_amount": "105.00",
                      "collection_ccy": "USD",
                      "payout_amount": "100.00",
                      "payout_ccy": "USD",
                      "usd_amount": "104.47",
                      "committed_at": "2026-01-15T11:00:00Z",
                      "partner_id": 43
                    }
                  ],
                  "total_elements": 2,
                  "total_pages": 1,
                  "page": 0
                }
                """;
    }

    private static String pageJson(int page, int totalPages, long txnId, String direction) {
        return """
                {
                  "content": [
                    {
                      "txn_id": %d,
                      "txn_ref": "TXN-%d",
                      "direction": "%s",
                      "same_ccy_shortcircuit": false,
                      "offer_rate_coll": "1.00000000",
                      "cross_rate": "1.00000000",
                      "collection_amount": "100.00",
                      "collection_ccy": "USD",
                      "payout_amount": "100.00",
                      "payout_ccy": "USD",
                      "usd_amount": "100.00",
                      "committed_at": "2026-01-10T09:00:00Z",
                      "partner_id": 1
                    }
                  ],
                  "total_elements": %d,
                  "total_pages": %d,
                  "page": %d
                }
                """.formatted(txnId, txnId, direction, totalPages, totalPages, page);
    }

    private static String emptyPageJson() {
        return """
                {
                  "content": [],
                  "total_elements": 0,
                  "total_pages": 1,
                  "page": 0
                }
                """;
    }

    private static String unknownDirectionJson() {
        return """
                {
                  "content": [
                    {
                      "txn_id": 9999,
                      "txn_ref": "TXN-BAD",
                      "direction": "UNKNOWN_FUTURE_DIR",
                      "same_ccy_shortcircuit": false,
                      "offer_rate_coll": "1.00000000",
                      "cross_rate": "1.00000000",
                      "collection_amount": "50.00",
                      "collection_ccy": "USD",
                      "payout_amount": "50.00",
                      "payout_ccy": "USD",
                      "usd_amount": "50.00",
                      "committed_at": "2026-04-01T09:00:00Z",
                      "partner_id": 1
                    }
                  ],
                  "total_elements": 1,
                  "total_pages": 1,
                  "page": 0
                }
                """;
    }
}
