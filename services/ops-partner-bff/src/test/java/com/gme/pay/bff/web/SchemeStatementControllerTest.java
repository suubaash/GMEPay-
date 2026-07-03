package com.gme.pay.bff.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.TransactionMgmtClient;
import com.gme.pay.bff.client.TransactionMgmtClient.Filter;
import com.gme.pay.bff.client.TransactionMgmtClient.TransactionSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * MockMvc tests for {@link SchemeStatementController}. The transaction-mgmt client + its stats path
 * are mocked. Asserts the statement shape — items scoped to the scheme, per-currency totals correct
 * over the whole window, and paging fields present.
 */
class SchemeStatementControllerTest {

    private MockMvc mvc;
    private TransactionMgmtClient transactions;

    /** ZEROPAY window: 3 USD + 1 KRW. Newest-first as transaction-mgmt returns them. */
    private static final List<TransactionSummary> ZEROPAY_ROWS = List.of(
            summary("TXN-4", "COMMITTED", "50000", "KRW", "M-2", "P_B", "2026-06-09T13:00:00Z"),
            summary("TXN-3", "COMMITTED", "75.00", "USD", "M-1", "P_A", "2026-06-09T12:00:00Z"),
            summary("TXN-2", "FAILED",    "20.00", "USD", "M-1", "P_A", "2026-06-09T11:00:00Z"),
            summary("TXN-1", "COMMITTED", "125.50", "USD", "M-1", "P_A", "2026-06-09T10:00:00Z"));

    private static TransactionSummary summary(String id, String state, String amt, String ccy,
                                              String merchantId, String partnerId, String at) {
        return new TransactionSummary(id, partnerId, state, new BigDecimal(amt), ccy,
                Instant.parse(at),
                "ZEROPAY", null, null, null, null, null, null,
                null, null, merchantId, null,
                null, null, null, null);
    }

    @BeforeEach
    void setUp() {
        transactions = mock(TransactionMgmtClient.class);
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new SchemeStatementController(transactions, new OpsRbacGuard(false)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    @Test
    void statement_scopesItemsToScheme_andComputesPerCurrencyTotals() throws Exception {
        // list() is called both for the item page and for the full-window totals fetch. Both are
        // scoped to ZEROPAY; return the full 4-row set (a short page → the totals loop stops).
        when(transactions.list(any(Filter.class)))
                .thenReturn(new TransactionMgmtClient.Page<>(ZEROPAY_ROWS, 0, 50, 4));

        mvc.perform(get("/v1/admin/schemes/ZEROPAY/statement").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemeId").value("ZEROPAY"))
                // paging fields present
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.window.from").exists())
                .andExpect(jsonPath("$.window.to").exists())
                // items: this page, newest-first, mapped shape
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].txnRef").value("TXN-4"))
                .andExpect(jsonPath("$.items[0].occurredAt").exists())
                .andExpect(jsonPath("$.items[0].merchantId").value("M-2"))
                .andExpect(jsonPath("$.items[0].partnerId").value("P_B"))
                .andExpect(jsonPath("$.items[0].currency").value("KRW"))
                .andExpect(jsonPath("$.items[0].status").value("COMMITTED"))
                // totals: per-currency count + gross over the whole window
                // USD: 3 txns, 75.00 + 20.00 + 125.50 = 220.50 ; KRW: 1 txn, 50000
                .andExpect(jsonPath("$.totals[?(@.currency=='USD')].count").value(3))
                .andExpect(jsonPath("$.totals[?(@.currency=='USD')].gross").value("220.50"))
                .andExpect(jsonPath("$.totals[?(@.currency=='KRW')].count").value(1))
                .andExpect(jsonPath("$.totals[?(@.currency=='KRW')].gross").value("50000"));

        // Every list() call carried the schemeId corridor filter (scoping is enforced).
        ArgumentCaptor<Filter> cap = ArgumentCaptor.forClass(Filter.class);
        verify(transactions, atLeastOnce()).list(cap.capture());
        assertThat(cap.getAllValues()).allSatisfy(f -> assertThat(f.schemeId()).isEqualTo("ZEROPAY"));
    }

    @Test
    void statement_capsPageSizeAt200() throws Exception {
        when(transactions.list(any(Filter.class)))
                .thenReturn(new TransactionMgmtClient.Page<>(List.of(), 0, 200, 0));

        mvc.perform(get("/v1/admin/schemes/ZEROPAY/statement").param("size", "9999"))
                .andExpect(status().isOk());

        ArgumentCaptor<Filter> cap = ArgumentCaptor.forClass(Filter.class);
        verify(transactions, atLeastOnce()).list(cap.capture());
        // The item-page fetch must never request more than the 200 cap.
        assertThat(cap.getAllValues()).allSatisfy(f -> assertThat(f.size()).isLessThanOrEqualTo(200));
    }

    @Test
    void statement_emptyScheme_returnsEmptyTotalsAndItems() throws Exception {
        when(transactions.list(any(Filter.class)))
                .thenReturn(new TransactionMgmtClient.Page<>(List.of(), 0, 50, 0));

        mvc.perform(get("/v1/admin/schemes/NEPAL/statement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemeId").value("NEPAL"))
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totals.length()").value(0))
                .andExpect(jsonPath("$.total").value(0));
    }
}
