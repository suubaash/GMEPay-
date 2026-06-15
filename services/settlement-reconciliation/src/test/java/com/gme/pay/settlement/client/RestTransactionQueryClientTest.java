package com.gme.pay.settlement.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.settlement.model.TransactionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Unit tests for {@link RestTransactionQueryClient} using {@link MockRestServiceServer}.
 *
 * <p>Response bodies use the canonical transaction-mgmt field names verbatim
 * (camelCase, page wrapper with {@code content/page/size/totalElements}).
 *
 * <p>Field-name contract verified:
 * <ul>
 *   <li>{@code totalElements} — page wrapper (NOT snake_case total_elements)</li>
 *   <li>{@code txnRef}        — item field</li>
 *   <li>{@code partnerRef}    — item field (→ schemeRef)</li>
 *   <li>{@code targetPayout}  — item field  (→ targetPayoutKrw BigDecimal)</li>
 *   <li>{@code sendCcy}       — item field  (→ settlementType derivation)</li>
 *   <li>{@code status}        — item field</li>
 *   <li>{@code merchantId}    — item field  (ReconDiffEngine key)</li>
 * </ul>
 */
class RestTransactionQueryClientTest {

    private static final String BASE_URL = "http://transaction-mgmt-test:8082";

    private MockRestServiceServer mockServer;
    private RestTransactionQueryClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        client = new RestTransactionQueryClient(restTemplate, BASE_URL);
    }

    // ---------------------------------------------------------------------------------
    // findUnbatchedApproved — single page
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("findUnbatchedApproved: single-page response maps all canonical fields correctly")
    void findUnbatchedApproved_singlePage_mapsFields() throws Exception {
        LocalDate date = LocalDate.of(2026, 6, 15);

        // Canonical page response: camelCase field names, page wrapper
        String body = """
                {
                  "content": [
                    {
                      "txnRef":      "TXN-001",
                      "partnerRef":  "ZP-SCH-001",
                      "sendAmount":  "3000.00",
                      "sendCcy":     "KRW",
                      "targetPayout": "34720",
                      "targetCcy":   "KRW",
                      "status":      "APPROVED",
                      "createdAt":   "2026-06-15T01:05:00Z",
                      "updatedAt":   "2026-06-15T01:06:00Z",
                      "merchantId":  "MRC001",
                      "merchantName":"Seoul Merchant"
                    }
                  ],
                  "page": 0,
                  "size": 500,
                  "totalElements": 1
                }
                """;

        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/transactions")))
                  .andExpect(queryParam("from",   "2026-06-15"))
                  .andExpect(queryParam("to",     "2026-06-15"))
                  .andExpect(queryParam("status", "APPROVED"))
                  .andExpect(queryParam("page",   "0"))
                  .andExpect(queryParam("size",   "500"))
                  .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<TransactionRecord> records = client.findUnbatchedApproved(date);

        mockServer.verify();

        assertThat(records).hasSize(1);

        TransactionRecord r = records.get(0);
        assertThat(r.txnRef()).isEqualTo("TXN-001");
        assertThat(r.schemeRef()).isEqualTo("ZP-SCH-001");     // partnerRef → schemeRef
        assertThat(r.merchantId()).isEqualTo("MRC001");         // key for ReconDiffEngine
        assertThat(r.targetPayoutKrw()).isEqualByComparingTo(new BigDecimal("34720")); // targetPayout → targetPayoutKrw
        assertThat(r.status()).isEqualTo("APPROVED");
        assertThat(r.settlementType()).isEqualTo('N');           // KRW sendCcy → NET
    }

    @Test
    @DisplayName("findUnbatchedApproved: non-KRW sendCcy maps to GROSS settlementType")
    void findUnbatchedApproved_nonKrwSendCcy_grossSettlementType() throws Exception {
        LocalDate date = LocalDate.of(2026, 6, 15);

        String body = """
                {
                  "content": [
                    {
                      "txnRef":      "TXN-GROSS-001",
                      "partnerRef":  "ZP-GROSS-001",
                      "sendAmount":  "1000.00",
                      "sendCcy":     "USD",
                      "targetPayout": "5000000",
                      "targetCcy":   "KRW",
                      "status":      "APPROVED",
                      "createdAt":   "2026-06-15T01:10:00Z",
                      "updatedAt":   "2026-06-15T01:11:00Z",
                      "merchantId":  "MRC_GROSS_01"
                    }
                  ],
                  "page": 0,
                  "size": 500,
                  "totalElements": 1
                }
                """;

        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/transactions")))
                  .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<TransactionRecord> records = client.findUnbatchedApproved(date);

        mockServer.verify();

        assertThat(records).hasSize(1);
        assertThat(records.get(0).settlementType()).isEqualTo('G'); // non-KRW → GROSS
        assertThat(records.get(0).targetPayoutKrw()).isEqualByComparingTo(new BigDecimal("5000000"));
        assertThat(records.get(0).merchantId()).isEqualTo("MRC_GROSS_01");
    }

    @Test
    @DisplayName("findUnbatchedApproved: multi-page fetches all pages until totalElements satisfied")
    void findUnbatchedApproved_multiPage_fetchesAllPages() throws Exception {
        LocalDate date = LocalDate.of(2026, 6, 15);

        // page 0: 1 item, totalElements=2 → client must fetch page 1
        String page0 = """
                {
                  "content": [
                    {
                      "txnRef": "TXN-001", "partnerRef": "P-001",
                      "sendCcy": "KRW", "targetPayout": "10000",
                      "status": "APPROVED", "merchantId": "MRC001"
                    }
                  ],
                  "page": 0,
                  "size": 500,
                  "totalElements": 2
                }
                """;

        String page1 = """
                {
                  "content": [
                    {
                      "txnRef": "TXN-002", "partnerRef": "P-002",
                      "sendCcy": "KRW", "targetPayout": "20000",
                      "status": "APPROVED", "merchantId": "MRC002"
                    }
                  ],
                  "page": 1,
                  "size": 500,
                  "totalElements": 2
                }
                """;

        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("page=0")))
                  .andRespond(withSuccess(page0, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("page=1")))
                  .andRespond(withSuccess(page1, MediaType.APPLICATION_JSON));

        List<TransactionRecord> records = client.findUnbatchedApproved(date);

        mockServer.verify();

        assertThat(records).hasSize(2);
        assertThat(records).extracting(TransactionRecord::txnRef)
                .containsExactlyInAnyOrder("TXN-001", "TXN-002");
        assertThat(records).extracting(TransactionRecord::merchantId)
                .containsExactlyInAnyOrder("MRC001", "MRC002");
        assertThat(records.get(0).targetPayoutKrw()).isEqualByComparingTo("10000");
        assertThat(records.get(1).targetPayoutKrw()).isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("findUnbatchedApproved: empty content returns empty list")
    void findUnbatchedApproved_emptyContent_returnsEmptyList() throws Exception {
        LocalDate date = LocalDate.of(2026, 6, 15);

        String body = """
                {
                  "content": [],
                  "page": 0,
                  "size": 500,
                  "totalElements": 0
                }
                """;

        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/transactions")))
                  .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<TransactionRecord> records = client.findUnbatchedApproved(date);

        mockServer.verify();
        assertThat(records).isEmpty();
    }

    @Test
    @DisplayName("findUnbatchedApproved: HTTP 500 from server returns empty list gracefully")
    void findUnbatchedApproved_serverError_returnsEmptyList() throws Exception {
        LocalDate date = LocalDate.of(2026, 6, 15);

        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/transactions")))
                  .andRespond(withServerError());

        List<TransactionRecord> records = client.findUnbatchedApproved(date);

        mockServer.verify();
        assertThat(records).isEmpty();
    }

    // ---------------------------------------------------------------------------------
    // Field-name contract: snake_case would silently null-out the fields
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("Contract: snake_case field names (txn_ref, merchant_id, target_payout) silently null — camelCase required")
    void fieldNameContract_snakeCaseProducesNullKeyFields() throws Exception {
        LocalDate date = LocalDate.of(2026, 6, 15);

        // Simulate what a broken producer would return — snake_case field names.
        // Jackson camelCase binding ignores snake_case keys → txnRef/merchantId/targetPayout
        // all remain null/zero, making the record useless for the ReconDiffEngine.
        // The canonical producer (transaction-mgmt) MUST send camelCase.
        String snakeCaseBody = """
                {
                  "content": [
                    {
                      "txn_ref":       "TXN-SNAKE-001",
                      "partner_ref":   "ZP-SNAKE-001",
                      "send_ccy":      "KRW",
                      "target_payout": "99000",
                      "status":        "APPROVED",
                      "merchant_id":   "MRC_SNAKE"
                    }
                  ],
                  "page": 0,
                  "size": 500,
                  "totalElements": 1
                }
                """;

        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/transactions")))
                  .andRespond(withSuccess(snakeCaseBody, MediaType.APPLICATION_JSON));

        List<TransactionRecord> records = client.findUnbatchedApproved(date);

        mockServer.verify();

        // Content was parsed (totalElements=1 in camelCase is found, so 1 item is returned),
        // but the item's key fields are all null/zero because the item fields are snake_case.
        // This verifies that ANY snake_case drift on the producer side silently corrupts data.
        assertThat(records).hasSize(1);
        TransactionRecord r = records.get(0);
        // These are null because the producer sent snake_case, not camelCase
        assertThat(r.txnRef()).isNull();       // txn_ref not matched → null
        assertThat(r.merchantId()).isNull();    // merchant_id not matched → null (ReconDiffEngine key = null)
        assertThat(r.targetPayoutKrw()).isEqualByComparingTo(BigDecimal.ZERO); // target_payout not matched → 0
    }

    // ---------------------------------------------------------------------------------
    // findByBatchId: unsupported, returns empty
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("findByBatchId returns empty list (endpoint not in canonical contract)")
    void findByBatchId_returnsEmptyList() {
        // No HTTP interaction expected — the method returns early
        List<TransactionRecord> records = client.findByBatchId(42L);
        assertThat(records).isEmpty();
        mockServer.verify(); // no calls made
    }
}
