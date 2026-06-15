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
 * <p>JSON fixtures use the canonical camelCase field names from transaction-mgmt's
 * {@code TransactionQueryPageResponse} / {@code TransactionResponse} contract:
 * <ul>
 *   <li>Page wrapper: {@code content}, {@code page}, {@code size}, {@code totalElements}</li>
 *   <li>Item fields: {@code txnRef}, {@code partnerRef}, {@code sendAmount}, {@code sendCcy},
 *       {@code targetPayout}, {@code targetCcy}, {@code status}, {@code createdAt},
 *       {@code updatedAt}, {@code appliedFxRate}, {@code prefundingDeductedUsd}, etc.</li>
 * </ul>
 *
 * <p>Tests verify:
 * <ul>
 *   <li>The client calls the correct URI (status=APPROVED, camelCase params).</li>
 *   <li>JSON is correctly deserialized into {@link CommittedTransaction} domain objects.</li>
 *   <li>Direction is derived correctly from currency pair (KRW target → INBOUND, etc.).</li>
 *   <li>Pagination: the client pages through all results when there are multiple pages.</li>
 *   <li>Single-page empty response returns an empty list (never null).</li>
 * </ul>
 */
class RestTransactionClientTest {

    private MockRestServiceServer mockServer;
    private RestTransactionClient client;

    @BeforeEach
    void setUp() {
        // Use RestTemplate adapter so MockRestServiceServer can intercept calls.
        RestTemplate restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);

        RestClient restClient = RestClient.builder()
                .requestFactory(restTemplate.getRequestFactory())
                .baseUrl("http://transaction-mgmt:8080")
                .build();

        client = new RestTransactionClient(restClient);
    }

    // =========================================================================
    // TEST 1: Single page — two transactions deserialized correctly
    // =========================================================================

    @Test
    @DisplayName("fetchCommitted: single page with USD→KRW (INBOUND) and USD→USD (OUTBOUND) transactions")
    void fetchCommitted_singlePage_twoTransactions() {
        mockServer.expect(requestTo(
                "http://transaction-mgmt:8080/v1/transactions"
                + "?from=2026-01-15&to=2026-01-15&size=500&page=0&status=APPROVED"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(singlePageTwoRecordsJson(), MediaType.APPLICATION_JSON));

        List<CommittedTransaction> result =
                client.fetchCommitted(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 15), null);

        mockServer.verify();

        assertEquals(2, result.size(), "Should return exactly 2 transactions");

        // First record: USD → KRW => INBOUND
        CommittedTransaction inbound = result.get(0);
        assertEquals("TXN-2026-001", inbound.getTxnRef());
        assertEquals(TransactionDirection.INBOUND, inbound.getDirection(),
                "USD→KRW must be derived as INBOUND");
        assertFalse(inbound.isSameCcyShortcircuit());
        assertEquals("USD", inbound.getCollectionCcy());
        assertEquals(0, new BigDecimal("38.9867").compareTo(inbound.getCollectionAmount()),
                "sendAmount must map to collectionAmount");
        assertEquals("KRW", inbound.getPayoutCcy());
        assertEquals(0, new BigDecimal("50000").compareTo(inbound.getPayoutAmount()),
                "targetPayout must map to payoutAmount");
        // crossRate from appliedFxRate
        assertEquals(0, new BigDecimal("1282.05128205").compareTo(inbound.getCrossRate()),
                "appliedFxRate must map to crossRate");
        // usdAmount from prefundingDeductedUsd
        assertEquals(0, new BigDecimal("37.04").compareTo(inbound.getUsdAmount()),
                "prefundingDeductedUsd must map to usdAmount");
        // offerRateColl is null — not available in GET response
        assertNull(inbound.getOfferRateColl(),
                "offerRateColl must be null (not available in canonical GET response)");

        // Second record: USD → USD => OUTBOUND (cross-border)
        CommittedTransaction outbound = result.get(1);
        assertEquals("TXN-2026-002", outbound.getTxnRef());
        assertEquals(TransactionDirection.OUTBOUND, outbound.getDirection(),
                "USD→USD (non-KRW) must be derived as OUTBOUND");
        assertFalse(outbound.isSameCcyShortcircuit());
    }

    // =========================================================================
    // TEST 2: Direction derivation — KRW → USD = OUTBOUND
    // =========================================================================

    @Test
    @DisplayName("fetchCommitted: KRW→USD transaction is derived as OUTBOUND")
    void fetchCommitted_krwSenderUsdTarget_derivedAsOutbound() {
        mockServer.expect(requestTo(
                "http://transaction-mgmt:8080/v1/transactions"
                + "?from=2026-02-01&to=2026-02-28&size=500&page=0&status=APPROVED"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(krwToUsdJson(), MediaType.APPLICATION_JSON));

        List<CommittedTransaction> result =
                client.fetchCommitted(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), null);

        mockServer.verify();
        assertEquals(1, result.size());
        assertEquals(TransactionDirection.OUTBOUND, result.get(0).getDirection(),
                "KRW→USD must be OUTBOUND");
    }

    // =========================================================================
    // TEST 3: Direction derivation — KRW → KRW = DOMESTIC (same-currency)
    // =========================================================================

    @Test
    @DisplayName("fetchCommitted: KRW→KRW transaction is derived as DOMESTIC (same-currency)")
    void fetchCommitted_krwToKrw_derivedAsDomestic() {
        mockServer.expect(requestTo(
                "http://transaction-mgmt:8080/v1/transactions"
                + "?from=2026-02-01&to=2026-02-28&size=500&page=0&status=APPROVED"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(krwToKrwJson(), MediaType.APPLICATION_JSON));

        List<CommittedTransaction> result =
                client.fetchCommitted(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), null);

        mockServer.verify();
        assertEquals(1, result.size());
        CommittedTransaction domestic = result.get(0);
        assertEquals(TransactionDirection.DOMESTIC, domestic.getDirection(),
                "KRW→KRW must be DOMESTIC");
        assertTrue(domestic.isSameCcyShortcircuit(),
                "DOMESTIC must have sameCcyShortcircuit=true");
    }

    // =========================================================================
    // TEST 4: Pagination — two pages fetched
    // =========================================================================

    @Test
    @DisplayName("fetchCommitted: paginates through two pages of results")
    void fetchCommitted_twoPages_fetchesBoth() {
        // Page 0: returns 500 records (full page) so client fetches page 1
        mockServer.expect(requestTo(
                "http://transaction-mgmt:8080/v1/transactions"
                + "?from=2026-01-01&to=2026-01-31&size=500&page=0&status=APPROVED"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        pageJsonWithSize(0, 1000L, "TXN-1001", "USD", "KRW", 500),
                        MediaType.APPLICATION_JSON));

        // Page 1: returns fewer than 500 records (end of results)
        mockServer.expect(requestTo(
                "http://transaction-mgmt:8080/v1/transactions"
                + "?from=2026-01-01&to=2026-01-31&size=500&page=1&status=APPROVED"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        pageJsonWithSize(1, 1000L, "TXN-1002", "USD", "KRW", 1),
                        MediaType.APPLICATION_JSON));

        List<CommittedTransaction> result =
                client.fetchCommitted(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null);

        mockServer.verify();
        assertEquals(501, result.size(), "Should collect 500 from page 0 + 1 from page 1");
    }

    // =========================================================================
    // TEST 5: Partner filter appended to URI
    // =========================================================================

    @Test
    @DisplayName("fetchCommitted: partnerId is appended to query string when provided")
    void fetchCommitted_withPartnerId_appendsToUri() {
        mockServer.expect(requestTo(
                "http://transaction-mgmt:8080/v1/transactions"
                + "?from=2026-03-01&to=2026-03-31&size=500&page=0&status=APPROVED&partnerId=99"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(emptyPageJson(), MediaType.APPLICATION_JSON));

        List<CommittedTransaction> result =
                client.fetchCommitted(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), 99L);

        mockServer.verify();
        assertTrue(result.isEmpty(), "Empty page response should return empty list");
    }

    // =========================================================================
    // TEST 6: Empty response returns empty list — never null
    // =========================================================================

    @Test
    @DisplayName("fetchCommitted: empty page returns empty list, not null")
    void fetchCommitted_emptyPage_returnsEmptyList() {
        mockServer.expect(requestTo(
                "http://transaction-mgmt:8080/v1/transactions"
                + "?from=2026-06-01&to=2026-06-01&size=500&page=0&status=APPROVED"))
                .andRespond(withSuccess(emptyPageJson(), MediaType.APPLICATION_JSON));

        List<CommittedTransaction> result =
                client.fetchCommitted(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1), null);

        assertNotNull(result, "Result must never be null");
        assertTrue(result.isEmpty());
    }

    // =========================================================================
    // JSON fixtures — camelCase, matching canonical TransactionQueryPageResponse
    // =========================================================================

    /**
     * Single page: one USD→KRW (INBOUND) and one USD→USD (OUTBOUND) transaction.
     * Uses canonical camelCase field names.
     */
    private static String singlePageTwoRecordsJson() {
        return """
                {
                  "content": [
                    {
                      "txnRef":                "TXN-2026-001",
                      "partnerRef":            "PARTNER-REF-001",
                      "sendAmount":            "38.9867",
                      "sendCcy":               "USD",
                      "targetPayout":          "50000",
                      "targetCcy":             "KRW",
                      "status":                "APPROVED",
                      "createdAt":             "2026-01-15T10:00:00Z",
                      "updatedAt":             "2026-01-15T10:05:00Z",
                      "appliedFxRate":         "1282.05128205",
                      "prefundingDeductedUsd": "37.04",
                      "merchantId":            "MERCHANT-001"
                    },
                    {
                      "txnRef":                "TXN-2026-002",
                      "partnerRef":            "PARTNER-REF-002",
                      "sendAmount":            "105.00",
                      "sendCcy":               "USD",
                      "targetPayout":          "100.00",
                      "targetCcy":             "USD",
                      "status":                "APPROVED",
                      "createdAt":             "2026-01-15T11:00:00Z",
                      "updatedAt":             "2026-01-15T11:05:00Z",
                      "appliedFxRate":         null,
                      "prefundingDeductedUsd": "104.47"
                    }
                  ],
                  "page":          0,
                  "size":          500,
                  "totalElements": 2
                }
                """;
    }

    /** KRW→USD outbound transaction. */
    private static String krwToUsdJson() {
        return """
                {
                  "content": [
                    {
                      "txnRef":       "TXN-KRW-OUT-001",
                      "partnerRef":   "PARTNER-KRW-001",
                      "sendAmount":   "130000",
                      "sendCcy":      "KRW",
                      "targetPayout": "100.00",
                      "targetCcy":    "USD",
                      "status":       "APPROVED",
                      "createdAt":    "2026-02-15T09:00:00Z",
                      "updatedAt":    "2026-02-15T09:05:00Z",
                      "appliedFxRate": "0.00076923"
                    }
                  ],
                  "page":          0,
                  "size":          500,
                  "totalElements": 1
                }
                """;
    }

    /** KRW→KRW domestic/same-currency transaction. */
    private static String krwToKrwJson() {
        return """
                {
                  "content": [
                    {
                      "txnRef":       "TXN-KRW-DOM-001",
                      "partnerRef":   "PARTNER-DOM-001",
                      "sendAmount":   "15500",
                      "sendCcy":      "KRW",
                      "targetPayout": "15500",
                      "targetCcy":    "KRW",
                      "status":       "APPROVED",
                      "createdAt":    "2026-02-15T10:00:00Z",
                      "updatedAt":    "2026-02-15T10:01:00Z"
                    }
                  ],
                  "page":          0,
                  "size":          500,
                  "totalElements": 1
                }
                """;
    }

    /**
     * Builds a page JSON with a configurable number of records (all with the same direction).
     * Used for pagination tests. When count < 500, the pagination loop stops.
     */
    private static String pageJsonWithSize(int page, long totalElements,
                                            String txnRefPrefix, String sendCcy, String targetCcy,
                                            int count) {
        StringBuilder records = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) records.append(",\n");
            records.append(String.format("""
                    {
                      "txnRef":       "%s-%d",
                      "partnerRef":   "PARTNER-%d",
                      "sendAmount":   "100.00",
                      "sendCcy":      "%s",
                      "targetPayout": "130000",
                      "targetCcy":    "%s",
                      "status":       "APPROVED",
                      "createdAt":    "2026-01-10T09:00:00Z",
                      "updatedAt":    "2026-01-10T09:05:00Z",
                      "appliedFxRate": "1300.00000000"
                    }""", txnRefPrefix, (page * 500 + i), (page * 500 + i), sendCcy, targetCcy));
        }
        return """
                {
                  "content": [ %s ],
                  "page":          %d,
                  "size":          500,
                  "totalElements": %d
                }
                """.formatted(records, page, totalElements);
    }

    private static String emptyPageJson() {
        return """
                {
                  "content":       [],
                  "page":          0,
                  "size":          500,
                  "totalElements": 0
                }
                """;
    }
}
