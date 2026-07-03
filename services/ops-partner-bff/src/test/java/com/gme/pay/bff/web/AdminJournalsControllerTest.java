package com.gme.pay.bff.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.RevenueLedgerClient;
import com.gme.pay.bff.web.dto.JournalPage;
import com.gme.pay.bff.web.dto.JournalView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Standalone MockMvc test for {@link AdminJournalsController}: mocks {@link RevenueLedgerClient}
 * and asserts {@code GET /v1/admin/journals} proxies the journals page shape unchanged
 * (items[].lines[].{account,side,amount,currency}, page/size/total). Broker-free.
 */
class AdminJournalsControllerTest {

    private RevenueLedgerClient client;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        client = mock(RevenueLedgerClient.class);
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new AdminJournalsController(client))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    @Test
    void list_proxiesJournalsShape() throws Exception {
        JournalView jv = new JournalView("jrnl-1", "TXN-1", Instant.parse("2026-07-01T00:00:00Z"), List.of(
                new JournalView.Line("RECEIVABLE_PARTNER", "DEBIT", new BigDecimal("12.34000000"), "USD"),
                new JournalView.Line("REVENUE_FX_MARGIN", "CREDIT", new BigDecimal("12.34000000"), "USD")));
        when(client.listJournals(any(), any(), any(), any(), any()))
                .thenReturn(new JournalPage(List.of(jv), 0, 50, 1L));

        mvc.perform(get("/v1/admin/journals")
                        .param("from", "2026-06-01T00:00:00Z")
                        .param("to", "2026-07-03T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].journalId").value("jrnl-1"))
                .andExpect(jsonPath("$.items[0].reference").value("TXN-1"))
                .andExpect(jsonPath("$.items[0].lines.length()").value(2))
                .andExpect(jsonPath("$.items[0].lines[0].account").value("RECEIVABLE_PARTNER"))
                .andExpect(jsonPath("$.items[0].lines[0].side").value("DEBIT"))
                // amount rides as a decimal STRING (money convention).
                .andExpect(jsonPath("$.items[0].lines[0].amount").value("12.34000000"))
                .andExpect(jsonPath("$.items[0].lines[0].currency").value("USD"))
                .andExpect(jsonPath("$.items[0].lines[1].side").value("CREDIT"));
    }

    @Test
    void list_passesReferenceFilterThrough() throws Exception {
        when(client.listJournals(any(), any(), eq("TXN-9"), any(), any()))
                .thenReturn(new JournalPage(List.of(), 0, 50, 0L));

        mvc.perform(get("/v1/admin/journals").param("reference", "TXN-9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }
}
