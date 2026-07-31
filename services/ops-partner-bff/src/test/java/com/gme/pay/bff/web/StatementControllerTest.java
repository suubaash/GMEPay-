package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

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
import com.gme.pay.bff.security.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Standalone MockMvc test for the Phase-C4 statement export endpoint on
 * {@link PartnerPortalController}. Uses the real {@link StubStatementClient}.
 */
class StatementControllerTest {

    private MockMvc mvc;

    /**
     * Portal endpoints are tenant-scoped against the verified token (T0-4). These tests exercise
     * portal FUNCTIONALITY, so they authenticate as a platform operator holding the explicit
     * cross-partner read permission; the scope rules themselves are covered by
     * {@link PartnerPortalScopeTest}.
     */
    @BeforeEach
    void authenticateAsCrossReadingOperator() {
        TestTokens.hubOperator("partner.view");
    }

    @AfterEach
    void clearAuthentication() {
        TestTokens.clear();
    }

    @BeforeEach
    void setUp() {
        TransactionMgmtClient transactions = new StubTransactionMgmtClient();
        PrefundingClient prefunding = new StubPrefundingClient();
        SettlementClient settlement = new StubSettlementClient();
        ConfigRegistryClient configRegistry = new StubConfigRegistryClient();

        PartnerPortalController controller = new PartnerPortalController(
                transactions, prefunding, settlement, configRegistry,
                new StubApiKeyClient(),
                new com.gme.pay.bff.client.stub.StubSandboxKeyClient(),
                // Gap T1-3: real statement, built from the transaction rows transaction-mgmt owns.
                new com.gme.pay.bff.client.rest.RestStatementClient(transactions),
                new com.gme.pay.bff.client.stub.StubPortalWebhookClient(),
                new OpsRbacGuard(true));

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(om);

        // The statement endpoint returns byte[] with Content-Type: text/csv — needs the
        // ByteArrayHttpMessageConverter alongside Jackson (the default standaloneSetup ships none).
        mvc = standaloneSetup(controller)
                .setMessageConverters(converter, new ByteArrayHttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("GET /v1/portal/{p}/statement returns text/csv built from the partner's real transactions")
    void statement_fullRangeReturnsRealTransactionRows() throws Exception {
        MvcResult result = mvc.perform(get("/v1/portal/{p}/statement", "partner_test_001")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-09"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("text/csv")))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"statement-2026-06-01-2026-06-09.csv\""))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String[] lines = body.split("\n");
        // 1 header + partner_test_001's two real transactions (TXN-1001, TXN-1002).
        org.junit.jupiter.api.Assertions.assertEquals(3, lines.length,
                "expected 1 header + 2 real transaction rows, got: " + body);
        // UC-10-02 header — unchanged contract, no revenue fields
        org.junit.jupiter.api.Assertions.assertEquals(
                com.gme.pay.bff.client.rest.RestStatementClient.UC10_HEADER, lines[0]);
        // Rows carry the transactions' REAL commit instants, oldest first.
        org.junit.jupiter.api.Assertions.assertTrue(
                lines[1].startsWith("2026-06-09T10:15:30Z,"),
                "unexpected first data row: " + lines[1]);
        org.junit.jupiter.api.Assertions.assertTrue(
                lines[2].startsWith("2026-06-09T11:02:11Z,"),
                "unexpected second data row: " + lines[2]);
        // Nothing from the removed fixture set may appear.
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("zeropay_kr"),
                "statement must not carry the removed hardcoded qrSchemeId fixture");
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("1325.00000000"),
                "statement must not carry the removed hardcoded FX rate fixture");
    }

    @Test
    @DisplayName("GET /v1/portal/{p}/statement returns header only when no transactions fall in the range")
    void statement_emptyRangeReturnsHeaderOnly() throws Exception {
        MvcResult result = mvc.perform(get("/v1/portal/{p}/statement", "partner_test_001")
                        .param("from", "2026-06-03")
                        .param("to", "2026-06-05"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        String[] lines = body.split("\n");
        org.junit.jupiter.api.Assertions.assertEquals(1, lines.length,
                "expected the header only, got: " + body);
        org.junit.jupiter.api.Assertions.assertEquals(
                com.gme.pay.bff.client.rest.RestStatementClient.UC10_HEADER, lines[0]);
    }

    @Test
    @DisplayName("Stub mode degrades to a header-only CSV, never to fabricated statement rows")
    void statement_stubModeEmitsHeaderOnly() {
        // Gap T1-3: StubStatementClient used to emit five hardcoded transactions
        // (TXN-1001..1005, all zeropay_kr, all at rate 1325.00000000). A statement is a finance
        // document, so an unconfigured BFF must produce an EMPTY one, not a plausible one.
        byte[] csv = new StubStatementClient()
                .exportCsv("partner_test_001", java.time.LocalDate.of(2026, 6, 1),
                        java.time.LocalDate.of(2026, 6, 30));
        String body = new String(csv, java.nio.charset.StandardCharsets.UTF_8);

        org.junit.jupiter.api.Assertions.assertEquals(
                StubStatementClient.UC10_HEADER + "\n", body,
                "stub statement must be the header alone");
        // Header stays byte-identical to the real client's so the download contract is unchanged.
        org.junit.jupiter.api.Assertions.assertEquals(
                com.gme.pay.bff.client.rest.RestStatementClient.UC10_HEADER,
                StubStatementClient.UC10_HEADER);
        for (String fixture : new String[] {"TXN-1001", "TXN-1005", "zeropay_kr", "1325.00000000"}) {
            org.junit.jupiter.api.Assertions.assertFalse(body.contains(fixture),
                    "removed fixture leaked back into the stub statement: " + fixture);
        }
    }

    @Test
    @DisplayName("GET /v1/portal/{p}/statement returns 400 when to is before from")
    void statement_invertedRangeReturns400() throws Exception {
        mvc.perform(get("/v1/portal/{p}/statement", "partner_test_001")
                        .param("from", "2026-06-09")
                        .param("to", "2026-06-01"))
                .andExpect(status().isBadRequest());
    }
}
