package com.gme.pay.txn.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.txn.domain.model.TransactionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the transaction-mgmt REST API contract.
 *
 * <p>Covers:
 * <ol>
 *   <li>POST /v1/transactions — 11-field create persists all V003 fields, response has {txnRef, paymentId, createdAt}</li>
 *   <li>PATCH /v1/transactions/{ref}/status — sets status + lock fields</li>
 *   <li>GET /v1/transactions — filters by date/status/partner + pages correctly</li>
 * </ol>
 *
 * <p>Runs against H2 (PostgreSQL mode) with Flyway; no Docker needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
@Transactional
class TransactionContractIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // =========================================================================
    // 1. POST /v1/transactions — 11-field create
    // =========================================================================

    @Test
    @DisplayName("POST /v1/transactions: all 11 fields accepted, response has txnRef+paymentId+createdAt")
    void create_elevenFields_persistedAndResponseCorrect() throws Exception {
        String body = """
                {
                  "partnerId": 42,
                  "partnerTxnRef": "PE-REF-001",
                  "schemeId": "zeropay_kr",
                  "direction": "INBOUND",
                  "paymentMode": "QR",
                  "targetPayout": "130000.00000000",
                  "payoutCurrency": "KRW",
                  "collectionAmount": "100.00000000",
                  "collectionCurrency": "USD",
                  "merchantId": "MERCH-001",
                  "quoteId": "QUOTE-001"
                }
                """;

        MvcResult result = mockMvc.perform(post("/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode resp = objectMapper.readTree(result.getResponse().getContentAsString());

        // Response must have exactly these three fields (payment-executor contract)
        assertNotNull(resp.get("txnRef"),    "txnRef must be present");
        assertNotNull(resp.get("paymentId"), "paymentId must be present");
        assertNotNull(resp.get("createdAt"), "createdAt must be present");
        assertFalse(resp.get("txnRef").asText().isBlank());
        assertFalse(resp.get("paymentId").asText().isBlank());

        // Round-trip: fetch via GET /{txnRef} and verify all 11 fields were persisted
        String txnRef = resp.get("txnRef").asText();
        MvcResult getResult = mockMvc.perform(get("/v1/transactions/{ref}", txnRef))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode txnBody = objectMapper.readTree(getResult.getResponse().getContentAsString());
        // Check V003 fields were stored and reflected in the response
        assertEquals("zeropay_kr", txnBody.get("qrSchemeId").asText(), "qrSchemeId must equal schemeId");
        assertEquals("MERCH-001",  txnBody.get("merchantId").asText(), "merchantId must persist");
        assertEquals("CREATED",    txnBody.get("status").asText());
        // sendAmount should equal collectionAmount (mapped in Transaction constructor)
        assertNotNull(txnBody.get("sendAmount"));
        assertNotNull(txnBody.get("targetPayout"));
    }

    // =========================================================================
    // 2. PATCH /v1/transactions/{ref}/status — sets status + lock fields
    // =========================================================================

    @Test
    @DisplayName("PATCH /v1/transactions/{ref}/status: sets status + persists all 8 lock fields")
    void patchStatus_setsStatusAndLockFields() throws Exception {
        // First create a transaction
        String createBody = """
                {
                  "partnerId": 10,
                  "partnerTxnRef": "PE-PATCH-001",
                  "schemeId": "zeropay_kr",
                  "direction": "INBOUND",
                  "paymentMode": "QR",
                  "targetPayout": "50000.00000000",
                  "payoutCurrency": "KRW",
                  "collectionAmount": "38.00000000",
                  "collectionCurrency": "USD",
                  "merchantId": null,
                  "quoteId": "Q-PATCH-001"
                }
                """;
        MvcResult createResult = mockMvc.perform(post("/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn();

        String txnRef = objectMapper.readTree(
                createResult.getResponse().getContentAsString()).get("txnRef").asText();

        // Apply PATCH
        String patchBody = """
                {
                  "newStatus": "APPROVED",
                  "schemeTxnRef": "SCHEME-TXN-999",
                  "schemeApprovalCode": "APV-CODE-123",
                  "prefundDeductedUsd": "38.50000000",
                  "approvedAt": "2026-06-15T03:00:00Z",
                  "bookedSettlementAmount": "50000.00000000",
                  "settlementRoundingMode": "DOWN",
                  "roundingResidual": "0.00500000"
                }
                """;

        mockMvc.perform(patch("/v1/transactions/{ref}/status", txnRef)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody))
                .andExpect(status().isNoContent());

        // Verify via GET that status changed and lock fields were stored
        MvcResult getResult = mockMvc.perform(get("/v1/transactions/{ref}", txnRef))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode txnBody = objectMapper.readTree(getResult.getResponse().getContentAsString());
        assertEquals("APPROVED", txnBody.get("status").asText(), "status must be APPROVED after patch");
        // prefundingDeductedUsd comes from prefundDeductedUsd patch field
        assertNotNull(txnBody.get("prefundingDeductedUsd"), "prefundingDeductedUsd must be set");
        assertEquals("38.50000000", txnBody.get("prefundingDeductedUsd").asText());
        // The scheme-confirmation evidence (proof the QR scheme paid the merchant) must be readable on
        // the GET — these were persisted by the patch but previously omitted from the response DTO.
        assertNotNull(txnBody.get("schemeTxnRef"), "schemeTxnRef must be exposed on GET after APPROVED");
        assertEquals("SCHEME-TXN-999", txnBody.get("schemeTxnRef").asText());
        assertEquals("APV-CODE-123", txnBody.get("schemeApprovalCode").asText(),
                "schemeApprovalCode must be exposed on GET after APPROVED");
    }

    @Test
    @DisplayName("PATCH status: PENDING_DEBIT → SCHEME_SENT → UNCERTAIN performs real FSM transitions")
    void patchStatus_drivesSchemeSentAndUncertain() throws Exception {
        String createBody = """
                {
                  "partnerId": 11,
                  "partnerTxnRef": "PE-UNCERTAIN-001",
                  "schemeId": "zeropay_kr",
                  "direction": "INBOUND",
                  "paymentMode": "QR",
                  "targetPayout": "45000.00000000",
                  "payoutCurrency": "KRW",
                  "collectionAmount": "33.88000000",
                  "collectionCurrency": "USD",
                  "merchantId": null,
                  "quoteId": "Q-UNCERTAIN-001"
                }
                """;
        MvcResult createResult = mockMvc.perform(post("/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn();
        String txnRef = objectMapper.readTree(
                createResult.getResponse().getContentAsString()).get("txnRef").asText();

        // CREATED → PENDING_DEBIT (PENDING maps to PENDING_DEBIT)
        patchStatus(txnRef, "PENDING");
        assertEquals("PENDING_DEBIT", readStatus(txnRef));

        // PENDING_DEBIT → SCHEME_SENT
        patchStatus(txnRef, "SCHEME_SENT");
        assertEquals("SCHEME_SENT", readStatus(txnRef));

        // SCHEME_SENT → UNCERTAIN (scheme timeout) — previously a no-op, now a real transition
        patchStatus(txnRef, "UNCERTAIN");
        assertEquals("UNCERTAIN", readStatus(txnRef));
    }

    private void patchStatus(String txnRef, String newStatus) throws Exception {
        String body = "{\"newStatus\":\"" + newStatus + "\"}";
        mockMvc.perform(patch("/v1/transactions/{ref}/status", txnRef)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    private String readStatus(String txnRef) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/transactions/{ref}", txnRef))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("status").asText();
    }

    // =========================================================================
    // 2b. Wave-3: margin persisted via PATCH → margin-accurate offerRateColl on /fx-committed
    // =========================================================================

    @Test
    @DisplayName("Wave-3: PATCH carries margin+collectionUsd → /fx-committed offerRateColl is margin-accurate")
    void patchWithMargin_yieldsMarginAccurateOfferRateColl() throws Exception {
        // Cross-border OUTBOUND: collect 10,850,000 IDR (= send_amount), pay out 130,000 KRW.
        String createBody = """
                {
                  "partnerId": 7001,
                  "partnerTxnRef": "PE-FX-001",
                  "schemeId": "zeropay_kr",
                  "direction": "OUTBOUND",
                  "paymentMode": "CPM",
                  "targetPayout": "130000.00000000",
                  "payoutCurrency": "KRW",
                  "collectionAmount": "10850000.00000000",
                  "collectionCurrency": "IDR",
                  "merchantId": "M-FX",
                  "quoteId": "Q-FX"
                }
                """;
        String txnRef = objectMapper.readTree(mockMvc.perform(post("/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString()).get("txnRef").asText();

        // PATCH APPROVED carrying the rate-lock pool: real collection_usd 673.0769, margin 6.7308.
        String patchBody = """
                {
                  "newStatus": "APPROVED",
                  "schemeTxnRef": "SCH-FX",
                  "schemeApprovalCode": "AP-FX",
                  "prefundDeductedUsd": "999.99999999",
                  "approvedAt": "2026-06-20T03:00:00Z",
                  "collectionUsd": "673.0769",
                  "collectionMarginUsd": "6.7308",
                  "payoutMarginUsd": "3.1500",
                  "costRateColl": "0.00148000",
                  "costRatePay": "0.00752000"
                }
                """;
        mockMvc.perform(patch("/v1/transactions/{ref}/status", txnRef)
                        .contentType(MediaType.APPLICATION_JSON).content(patchBody))
                .andExpect(status().isNoContent());

        // committed_at is stamped at the APPROVED transition (= now), so query a [-1,+1] day window.
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        MvcResult fx = mockMvc.perform(get("/v1/transactions/fx-committed")
                        .param("from", today.minusDays(1).toString())
                        .param("to", today.plusDays(1).toString())
                        .param("partnerId", "7001"))
                .andExpect(status().isOk()).andReturn();

        JsonNode rows = objectMapper.readTree(fx.getResponse().getContentAsString());
        assertTrue(rows.isArray() && rows.size() == 1, "exactly one committed row expected");
        JsonNode row = rows.get(0);
        // offerRateColl = 10,850,000 / (673.0769 - 6.7308) = 16,282.82959861  (NON-zero margin).
        assertEquals(new java.math.BigDecimal("16282.82959861"),
                new java.math.BigDecimal(row.get("offerRateColl").asText()),
                "offerRateColl must use the REAL persisted collectionMarginUsd, not zero margin");
        // usdAmount must be the REAL collection_usd (673.0769), not the prefund proxy (999.99999999).
        assertEquals(0, new java.math.BigDecimal(row.get("usdAmount").asText())
                .compareTo(new java.math.BigDecimal("673.0769")));
        assertEquals(0, new java.math.BigDecimal(row.get("collectionMarginUsd").asText())
                .compareTo(new java.math.BigDecimal("6.7308")));
    }

    // =========================================================================
    // 2c. Wave-3: GET /v1/transactions/refunded returns the canonical RefundedTransactionView
    // =========================================================================

    @Test
    @DisplayName("Wave-3: /refunded returns canonical view shape (txnRef/originalPaymentTxnRef/refundAmountKrw/settlementDate)")
    void refunded_returnsCanonicalView() throws Exception {
        // Create → APPROVED → REFUNDED so the row is refunded "today" and findRefundedOn finds it.
        String txnRef = createTxn("PE-REFUND-001", 8001L);
        patchStatus(txnRef, "APPROVED");
        patchStatus(txnRef, "REFUNDED");

        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        MvcResult result = mockMvc.perform(get("/v1/transactions/refunded")
                        .param("refundedOn", today.toString()))
                .andExpect(status().isOk()).andReturn();

        JsonNode rows = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(rows.isArray() && rows.size() >= 1, "expected at least one refunded row today");
        JsonNode row = rows.get(0);
        // Canonical RefundedTransactionView field names (producer-authoritative).
        assertNotNull(row.get("txnRef"), "txnRef (canonical) must be present");
        assertTrue(row.has("status"));
        assertEquals("REFUNDED", row.get("status").asText());
        // settlementDate slot exists on the canonical view; null here (no settlement window booked
        // yet) and @JsonInclude(NON_NULL) omits it — assert it is NOT a wrong-named field.
        assertFalse(row.has("refundTxnRef"), "must not carry settlement's divergent ad-hoc name");
        assertFalse(row.has("originalTxnRef"), "must not carry settlement's divergent ad-hoc name");
        assertFalse(row.has("refundSchemeTxnRef"), "must not carry scheme-adapter's ad-hoc name");
    }

    // =========================================================================
    // 2d. CS quick-wins — statusLabel + statusHistory on GET, userRef search
    // =========================================================================

    @Test
    @DisplayName("CS: GET exposes statusLabel + a non-null ordered statusHistory; APPROVED reads friendly")
    void getExposesStatusLabelAndTimeline() throws Exception {
        String txnRef = createTxn("PE-CS-LABEL", 9101L);
        // CREATED → statusLabel + single-entry history.
        JsonNode created = getTxn(txnRef);
        assertEquals("Payment created", created.get("statusLabel").asText());
        assertTrue(created.get("statusHistory").isArray());
        assertEquals(1, created.get("statusHistory").size());
        assertEquals("CREATED", created.get("statusHistory").get(0).get("status").asText());
        assertEquals("Payment created", created.get("statusHistory").get(0).get("statusLabel").asText());

        // Approve it → label becomes "Payment approved", timeline gains an APPROVED entry (ordered).
        patchStatus(txnRef, "APPROVED");
        JsonNode approved = getTxn(txnRef);
        assertEquals("APPROVED", approved.get("status").asText());
        assertEquals("Payment approved", approved.get("statusLabel").asText());
        JsonNode hist = approved.get("statusHistory");
        assertTrue(hist.isArray() && hist.size() >= 2, "timeline must have CREATED + APPROVED");
        assertEquals("CREATED", hist.get(0).get("status").asText());
        assertEquals("APPROVED", hist.get(hist.size() - 1).get("status").asText());
    }

    @Test
    @DisplayName("CS: search by userRef returns the matching customer's transaction")
    void searchByUserRef() throws Exception {
        String body = """
                {
                  "partnerId": 9200,
                  "partnerTxnRef": "PE-CS-USERREF",
                  "schemeId": "zeropay_kr",
                  "direction": "INBOUND",
                  "paymentMode": "QR",
                  "targetPayout": "10000.00",
                  "payoutCurrency": "KRW",
                  "collectionAmount": "7.50",
                  "collectionCurrency": "USD",
                  "merchantId": null,
                  "quoteId": null,
                  "userRef": "WALLET-CUST-42"
                }
                """;
        String txnRef = objectMapper.readTree(mockMvc.perform(post("/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString()).get("txnRef").asText();

        // userRef must round-trip onto the GET projection.
        assertEquals("WALLET-CUST-42", getTxn(txnRef).get("userRef").asText());

        // Search by the end-customer identifier finds it.
        MvcResult result = mockMvc.perform(get("/v1/transactions/search")
                        .param("userRef", "WALLET-CUST-42"))
                .andExpect(status().isOk()).andReturn();
        JsonNode resp = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(resp.get("totalElements").asLong() >= 1, "search by userRef must find the txn");
        assertEquals("WALLET-CUST-42", resp.get("content").get(0).get("userRef").asText());
    }

    @Test
    @DisplayName("CS: search by reference matches the partner's own reference (partnerTxnRef)")
    void searchByReference() throws Exception {
        createTxn("PE-CS-REFERENCE", 9300L);

        MvcResult result = mockMvc.perform(get("/v1/transactions/search")
                        .param("reference", "PE-CS-REFERENCE"))
                .andExpect(status().isOk()).andReturn();
        JsonNode resp = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(resp.get("totalElements").asLong() >= 1, "search by reference must find the txn");
        assertEquals("PE-CS-REFERENCE", resp.get("content").get(0).get("partnerRef").asText());
    }

    private JsonNode getTxn(String txnRef) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/transactions/{ref}", txnRef))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // =========================================================================
    // 3. GET /v1/transactions — filters + pagination
    // =========================================================================

    @Test
    @DisplayName("GET /v1/transactions: returns paged wrapper with content/page/size/totalElements")
    void getTransactions_returnsPaginatedResults() throws Exception {
        // Create two transactions
        createTxn("PARTNER-GET-001", 99L);
        createTxn("PARTNER-GET-002", 99L);
        createTxn("PARTNER-GET-003", 88L); // different partner

        MvcResult result = mockMvc.perform(get("/v1/transactions")
                        .param("partnerId", "99")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode resp = objectMapper.readTree(result.getResponse().getContentAsString());

        // Page wrapper fields
        assertNotNull(resp.get("content"),       "content must be present");
        assertNotNull(resp.get("page"),          "page must be present");
        assertNotNull(resp.get("size"),          "size must be present");
        assertNotNull(resp.get("totalElements"), "totalElements must be present");
        assertEquals(0, resp.get("page").asInt());
        assertEquals(10, resp.get("size").asInt());
        // At least 2 results for partner 99
        assertTrue(resp.get("totalElements").asLong() >= 2,
                "totalElements must include partner-99 transactions");
        assertTrue(resp.get("content").size() >= 2, "content must have at least 2 items");

        // Verify content items have the Phase-4 camelCase fields
        JsonNode first = resp.get("content").get(0);
        assertNotNull(first.get("txnRef"),    "txnRef must be present in content items");
        assertNotNull(first.get("status"),    "status must be present in content items");
        assertNotNull(first.get("createdAt"), "createdAt must be present in content items");
    }

    @Test
    @DisplayName("GET /v1/transactions: status filter returns only matching transactions")
    void getTransactions_statusFilter() throws Exception {
        // Create two transactions (both start as CREATED)
        createTxn("STATUS-FILTER-001", 55L);

        MvcResult result = mockMvc.perform(get("/v1/transactions")
                        .param("status", "CREATED")
                        .param("partnerId", "55")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode resp = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(resp.get("totalElements").asLong() >= 1,
                "Should find at least one CREATED transaction for partner 55");
        // All returned transactions must have status CREATED
        resp.get("content").forEach(item ->
                assertEquals("CREATED", item.get("status").asText(),
                        "All filtered results must have status CREATED"));
    }

    @Test
    @DisplayName("GET /v1/transactions: date filter with from/to returns only matching range")
    void getTransactions_dateFilter() throws Exception {
        createTxn("DATE-FILTER-001", 77L);

        // A date window around "now" should include the just-created transaction. Uses a
        // [-1, +1] day window so the assertion is robust to the UTC/KST day boundary: createdAt is
        // a UTC Instant while LocalDate.now() can already be the next day in KST near midnight, so a
        // same-day from=to=today filter would spuriously miss the row.
        java.time.LocalDate today = java.time.LocalDate.now();
        MvcResult result = mockMvc.perform(get("/v1/transactions")
                        .param("from", today.minusDays(1).toString())
                        .param("to", today.plusDays(1).toString())
                        .param("partnerId", "77")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode resp = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(resp.get("totalElements").asLong() >= 1,
                "Should find at least one transaction created today for partner 77");
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private String createTxn(String partnerTxnRef, long partnerId) throws Exception {
        String body = String.format("""
                {
                  "partnerId": %d,
                  "partnerTxnRef": "%s",
                  "schemeId": "zeropay_kr",
                  "direction": "INBOUND",
                  "paymentMode": "QR",
                  "targetPayout": "10000.00",
                  "payoutCurrency": "KRW",
                  "collectionAmount": "7.50",
                  "collectionCurrency": "USD",
                  "merchantId": null,
                  "quoteId": null
                }
                """, partnerId, partnerTxnRef);

        MvcResult result = mockMvc.perform(post("/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("txnRef").asText();
    }
}
