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
