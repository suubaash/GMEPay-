package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.stub.StubApiKeyClient;
import com.gme.pay.bff.client.stub.StubConfigRegistryClient;
import com.gme.pay.bff.client.stub.StubPortalWebhookClient;
import com.gme.pay.bff.client.stub.StubPrefundingClient;
import com.gme.pay.bff.client.stub.StubSandboxKeyClient;
import com.gme.pay.bff.client.stub.StubSettlementClient;
import com.gme.pay.bff.client.stub.StubStatementClient;
import com.gme.pay.bff.client.stub.StubTransactionMgmtClient;
import com.gme.pay.bff.security.TestTokens;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * GAP T4-5: the <b>partner-facing settlement statement</b> on
 * {@code GET /v1/portal/{partnerId}/settlements}.
 *
 * <p>The register's finding was that a partner had no settlement statement at all. This asserts the
 * statement exists, is scoped to the calling partner, and — the part that matters most — never lets a
 * partner believe money has been instructed to the scheme when it has not.
 */
class PortalSettlementStatementControllerTest {

    private static final String PARTNER = "partner_test_001";

    private MockMvc mvc;

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
        StubTransactionMgmtClient transactions = new StubTransactionMgmtClient();
        PartnerPortalController controller = new PartnerPortalController(
                transactions,
                new StubPrefundingClient(),
                new StubSettlementClient(),
                new StubConfigRegistryClient(),
                new StubApiKeyClient(),
                new StubSandboxKeyClient(),
                new StubStatementClient(),
                new StubPortalWebhookClient(),
                new OpsRbacGuard(true));

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    @Test
    @DisplayName("the statement exists, is partner-scoped, and sums the settled lines")
    void statementIsPartnerScopedAndSummed() throws Exception {
        mvc.perform(get("/v1/portal/{partnerId}/settlements", PARTNER)
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerId").value(PARTNER))
                .andExpect(jsonPath("$.from").value("2026-06-01"))
                .andExpect(jsonPath("$.to").value("2026-06-30"))
                // Two of the three seeded batches belong to partner_test_001.
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[*].batch.partnerId",
                        Matchers.everyItem(Matchers.is(PARTNER))))
                // 125.50 + 75.00 + 9.92 + 210.00 + 310.00 = 730.42
                .andExpect(jsonPath("$.netSettlementAmount").value(Matchers.comparesEqualTo(730.42)))
                .andExpect(jsonPath("$.lineCount").value(5))
                .andExpect(jsonPath("$.openLineCount").value(1));
    }

    @Test
    @DisplayName("T4-5: no entry presents as transmitted, and the channel board says why")
    void statementIsHonestAboutTransmission() throws Exception {
        mvc.perform(get("/v1/portal/{partnerId}/settlements", PARTNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transmittedEntryCount").value(0))
                .andExpect(jsonPath("$.transmissionChannel.live").value(false))
                .andExpect(jsonPath("$.transmissionChannel.reason")
                        .value(Matchers.containsString("never sent")))
                .andExpect(jsonPath("$.entries[*].batch.transmissionState",
                        Matchers.everyItem(Matchers.is("NOT_TRANSMITTED_CHANNEL_UNAVAILABLE"))))
                .andExpect(jsonPath("$.entries[*].batch.transmittedAt",
                        Matchers.everyItem(Matchers.nullValue())))
                // And no entry may carry the retired invented status either.
                .andExpect(jsonPath("$.entries[*].batch.status",
                        Matchers.everyItem(Matchers.not(Matchers.is("COMPLETED")))));
    }

    @Test
    @DisplayName("includeLines=false yields a summary-only statement with the same totals")
    void summaryOnly() throws Exception {
        mvc.perform(get("/v1/portal/{partnerId}/settlements", PARTNER)
                        .param("includeLines", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineCount").value(5))
                .andExpect(jsonPath("$.entries[0].lines.length()").value(0));
    }

    @Test
    @DisplayName("an inverted window is a 400, not a silently swapped one")
    void invertedWindowIsRejected() throws Exception {
        mvc.perform(get("/v1/portal/{partnerId}/settlements", PARTNER)
                        .param("from", "2026-06-30")
                        .param("to", "2026-06-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a partner with no settled batches gets an empty statement, not an error")
    void emptyStatement() throws Exception {
        mvc.perform(get("/v1/portal/{partnerId}/settlements", "partner_with_nothing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0))
                .andExpect(jsonPath("$.transmittedEntryCount").value(0));
    }
}
