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

    @BeforeEach
    void setUp() {
        TransactionMgmtClient transactions = new StubTransactionMgmtClient();
        PrefundingClient prefunding = new StubPrefundingClient();
        SettlementClient settlement = new StubSettlementClient();
        ConfigRegistryClient configRegistry = new StubConfigRegistryClient();

        PartnerPortalController controller = new PartnerPortalController(
                transactions, prefunding, settlement, configRegistry,
                new StubApiKeyClient(), new StubStatementClient());

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
    @DisplayName("GET /v1/portal/{p}/statement returns text/csv with attachment header and 5 data rows")
    void statement_fullRangeReturnsAllFiveRows() throws Exception {
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
        // 1 header + 5 rows
        org.junit.jupiter.api.Assertions.assertEquals(6, lines.length,
                "expected 1 header + 5 rows, got: " + body);
        org.junit.jupiter.api.Assertions.assertEquals(
                "txnId,partnerId,status,amount,currency,createdAt", lines[0]);
        org.junit.jupiter.api.Assertions.assertTrue(lines[1].startsWith("TXN-1001,partner_test_001,"));
    }

    @Test
    @DisplayName("GET /v1/portal/{p}/statement filters by date range")
    void statement_narrowRangeFilters() throws Exception {
        MvcResult result = mvc.perform(get("/v1/portal/{p}/statement", "partner_test_001")
                        .param("from", "2026-06-03")
                        .param("to", "2026-06-05"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        String[] lines = body.split("\n");
        // header + 2 rows (TXN-1002 on 2026-06-03 and TXN-1003 on 2026-06-05)
        org.junit.jupiter.api.Assertions.assertEquals(3, lines.length,
                "expected 1 header + 2 rows, got: " + body);
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
