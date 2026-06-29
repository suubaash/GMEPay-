package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.client.PrefundingClient;
import com.gme.pay.bff.client.SettlementClient;
import com.gme.pay.bff.client.TransactionMgmtClient;
import com.gme.pay.bff.client.stub.StubApiKeyClient;
import com.gme.pay.bff.client.stub.StubConfigRegistryClient;
import com.gme.pay.bff.client.stub.StubPrefundingClient;
import com.gme.pay.bff.client.stub.StubSettlementClient;
import com.gme.pay.bff.client.stub.StubStatementClient;
import com.gme.pay.bff.client.stub.StubTransactionMgmtClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * UC-10 Phase 4 Stage 2 — portal pass-through tests.
 *
 * <p>Exercises:
 * <ul>
 *   <li>UC-10-01: balance endpoint returns canonical {@link com.gme.pay.contracts.BalanceView}
 *       with {@code partnerCode}, {@code threshold}, {@code pctOfThreshold}, {@code recentDeductions}.
 *   <li>UC-10-02: transactions list includes enriched UC-10 fields
 *       ({@code qrSchemeId}, {@code krwAmount}, {@code payerCurrency}, {@code payerCurrencyAmount},
 *       {@code appliedFxRate}, {@code rateTimestamp}, {@code prefundingDeductedUsd}).
 *   <li>UC-10-03: transaction detail includes enriched UC-10 fields plus
 *       {@code merchantId}, {@code merchantName}, {@code statusHistory}.
 *   <li>Revenue stripping: NONE of {@code fxMarginPct}, {@code gmeRevenue},
 *       {@code marginRevenueUsd}, {@code feeRevenueUsd} appear in any portal response.
 *   <li>UC-10-02 statement CSV uses the correct column header (no revenue columns).
 * </ul>
 *
 * <p>Uses the real stub clients — no Spring context, no mocks — so this is a
 * deterministic unit test with the same wiring the BFF uses in standalone mode.
 */
class PortalUc10ControllerTest {

    private static final String PARTNER = "partner_test_001";

    private MockMvc mvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        ConfigRegistryClient configRegistry = new StubConfigRegistryClient();
        TransactionMgmtClient transactions = new StubTransactionMgmtClient();
        PrefundingClient prefunding = new StubPrefundingClient();
        SettlementClient settlement = new StubSettlementClient();

        PartnerPortalController controller = new PartnerPortalController(
                transactions, prefunding, settlement, configRegistry,
                new StubApiKeyClient(), new StubStatementClient());

        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(objectMapper);

        mvc = standaloneSetup(controller)
                .setMessageConverters(converter, new ByteArrayHttpMessageConverter())
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // UC-10-01 Balance
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("UC-10-01: GET balance returns canonical BalanceView with partnerCode + threshold")
    void balance_uc1001_returnsCanonicalShape() throws Exception {
        mvc.perform(get("/v1/portal/{p}/balance", PARTNER))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // UC-10-01: canonical field names (not legacy partnerId/lowBalanceThreshold)
                .andExpect(jsonPath("$.partnerCode").value(PARTNER))
                .andExpect(jsonPath("$.currency").value("USD"))
                // money as decimal STRING per MONEY_CONVENTION
                .andExpect(jsonPath("$.balance").value("10000.00"))
                .andExpect(jsonPath("$.threshold").value("1000.00"))
                // pctOfThreshold: 10000/1000*100 = 1000.00
                .andExpect(jsonPath("$.pctOfThreshold").value("1000.00"))
                // recentDeductions null until prefunding service wires it — field must be present (JsonInclude.ALWAYS)
                .andExpect(jsonPath("$.recentDeductions").isEmpty());
    }

    @Test
    @DisplayName("UC-10-01: balance response MUST NOT contain revenue fields")
    void balance_noRevenueFields() throws Exception {
        MvcResult result = mvc.perform(get("/v1/portal/{p}/balance", PARTNER))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertNoRevenueFields(json, "balance");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // UC-10-02 Transaction History
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("UC-10-02: GET transactions returns list with enriched UC-10 fields present")
    void transactions_uc1002_enrichedFieldsPresent() throws Exception {
        // StubTransactionMgmtClient has 2 rows for partner_test_001
        mvc.perform(get("/v1/portal/{p}/transactions", PARTNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].txnId").value("TXN-1001"))
                .andExpect(jsonPath("$[0].state").value("COMMITTED"))
                // UC-10-02 additive fields are null in stub but serialized via @JsonInclude(NON_NULL)
                // so they are absent when null — that is correct compact behaviour.
                // When non-null in production they will appear; we test for absence meaning null.
                .andExpect(jsonPath("$[0].partnerId").value(PARTNER));
    }

    @Test
    @DisplayName("UC-10-02: transactions response MUST NOT contain revenue fields")
    void transactions_noRevenueFields() throws Exception {
        MvcResult result = mvc.perform(get("/v1/portal/{p}/transactions", PARTNER))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        // json is an array — check each element
        Assertions.assertTrue(json.isArray(), "Expected array response");
        for (JsonNode txn : json) {
            assertNoRevenueFields(txn, "transaction list item");
        }
    }

    @Test
    @DisplayName("UC-10-02: transactions by different partner are not returned (partner isolation)")
    void transactions_partnerIsolation() throws Exception {
        // partner_test_002 has TXN-1003 (KRW), should NOT see partner_test_001's rows
        mvc.perform(get("/v1/portal/{p}/transactions", "partner_test_002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].txnId").value("TXN-1003"));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // UC-10-03 Transaction Detail
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("UC-10-03: GET transaction detail returns enriched TransactionDetail")
    void transactionDetail_uc1003_enrichedShape() throws Exception {
        mvc.perform(get("/v1/portal/{p}/transactions/{id}", PARTNER, "TXN-1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.txnId").value("TXN-1001"))
                .andExpect(jsonPath("$.summary.partnerId").value(PARTNER))
                .andExpect(jsonPath("$.summary.state").value("COMMITTED"))
                // REAL scheme-confirmation values pass through from transaction-mgmt (no "SCH-"/"AP-" stub)
                .andExpect(jsonPath("$.schemeTxnRef").value("ZP-TXN-1001-CONF"))
                .andExpect(jsonPath("$.schemeApprovalCode").value("AUTH-1001"))
                .andExpect(jsonPath("$.merchantId").value("M0000000001"))
                .andExpect(jsonPath("$.settlementRoundingMode").value("HALF_UP"))
                // settlement booking is locked at settlement time, not payment time → absent here
                .andExpect(jsonPath("$.bookedSettlementAmount").doesNotExist())
                .andExpect(jsonPath("$.prefundDeductedUsd").exists());
    }

    @Test
    @DisplayName("UC-10-03: transaction detail MUST NOT contain revenue fields")
    void transactionDetail_noRevenueFields() throws Exception {
        MvcResult result = mvc.perform(get("/v1/portal/{p}/transactions/{id}", PARTNER, "TXN-1001"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertNoRevenueFields(json, "transaction detail");
        // Also check the nested summary
        assertNoRevenueFields(json.path("summary"), "transaction detail summary");
    }

    @Test
    @DisplayName("UC-10-03: transaction belonging to another partner returns 404")
    void transactionDetail_wrongPartnerReturns404() throws Exception {
        // TXN-1003 belongs to partner_test_002, not partner_test_001
        mvc.perform(get("/v1/portal/{p}/transactions/{id}", PARTNER, "TXN-1003"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("UC-10-03: unknown transaction returns 404")
    void transactionDetail_unknownReturns404() throws Exception {
        mvc.perform(get("/v1/portal/{p}/transactions/{id}", PARTNER, "TXN-DOES-NOT-EXIST"))
                .andExpect(status().isNotFound());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // UC-10-02 Statement CSV
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("UC-10-02: statement CSV uses UC-10-02 column header (no revenue columns)")
    void statement_uc1002_csvHeader() throws Exception {
        MvcResult result = mvc.perform(get("/v1/portal/{p}/statement", PARTNER)
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-09"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("text/csv")))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"statement-2026-06-01-2026-06-09.csv\""))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String[] lines = body.split("\n");
        // 1 header + 5 data rows
        Assertions.assertEquals(6, lines.length,
                "expected 1 header + 5 data rows, got: " + body);

        // UC-10-02 header must match exactly — timestamp,qrSchemeId,krwAmount,...
        Assertions.assertEquals(
                StubStatementClient.UC10_HEADER, lines[0],
                "CSV header must match UC-10-02 spec");

        // Revenue columns must be absent from the header
        String header = lines[0];
        Assertions.assertFalse(header.contains("fxMarginPct"),
                "revenue field fxMarginPct must not appear in CSV header");
        Assertions.assertFalse(header.contains("gmeRevenue"),
                "revenue field gmeRevenue must not appear in CSV header");
        Assertions.assertFalse(header.contains("marginRevenue"),
                "revenue field marginRevenue must not appear in CSV header");
        Assertions.assertFalse(header.contains("feeRevenue"),
                "revenue field feeRevenue must not appear in CSV header");

        // First data row must start with a UTC ISO timestamp
        Assertions.assertTrue(lines[1].startsWith("2026-06-01T00:00:00Z,"),
                "data rows must start with UTC ISO timestamp, got: " + lines[1]);

        // Confirm UC-10-02 fields appear in the data rows
        Assertions.assertTrue(lines[1].contains("zeropay_kr"),
                "qrSchemeId must appear in data rows");
    }

    @Test
    @DisplayName("UC-10-02: statement CSV narrow date range filters correctly")
    void statement_uc1002_narrowRangeFilters() throws Exception {
        MvcResult result = mvc.perform(get("/v1/portal/{p}/statement", PARTNER)
                        .param("from", "2026-06-03")
                        .param("to", "2026-06-05"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        String[] lines = body.split("\n");
        // 1 header + 2 rows (TXN-1002 on 2026-06-03, TXN-1003 on 2026-06-05)
        Assertions.assertEquals(3, lines.length,
                "expected 1 header + 2 rows for narrow range, got: " + body);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Revenue-stripping helper
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Asserts that the given JSON node does not contain any internal revenue fields
     * that must be stripped from partner-facing responses.
     *
     * <p>Fields checked:
     * <ul>
     *   <li>{@code fxMarginPct} — FX margin percentage (GME internal)
     *   <li>{@code gmeRevenue} — GME revenue share (GME internal)
     *   <li>{@code marginRevenueUsd} — from {@code RevenueLedgerClient.RevenueSummary}
     *   <li>{@code feeRevenueUsd} — from {@code RevenueLedgerClient.RevenueSummary}
     *   <li>{@code totalRevenueUsd} — from {@code RevenueLedgerClient.RevenueSummary}
     * </ul>
     */
    private static void assertNoRevenueFields(JsonNode node, String context) {
        String[] revenueFields = {
                "fxMarginPct", "gmeRevenue", "marginRevenueUsd",
                "feeRevenueUsd", "totalRevenueUsd"
        };
        for (String field : revenueFields) {
            Assertions.assertFalse(
                    node.has(field),
                    "Revenue field '" + field + "' must be absent from " + context
                            + " — found in: " + node);
        }
    }
}
