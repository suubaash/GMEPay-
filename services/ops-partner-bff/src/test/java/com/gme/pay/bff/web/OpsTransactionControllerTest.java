package com.gme.pay.bff.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.TransactionMgmtClient;
import com.gme.pay.bff.client.TransactionMgmtClient.SearchQuery;
import com.gme.pay.bff.client.TransactionMgmtClient.TransactionSummary;
import com.gme.pay.bff.client.stub.StubOperatorActionAuditClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * MockMvc tests for {@link OpsTransactionController}: 360° search proxies the mapped
 * result page; resolve delegates AND records the operator-action audit.
 */
class OpsTransactionControllerTest {

    private MockMvc mvc;
    private TransactionMgmtClient transactions;
    private StubOperatorActionAuditClient audit;

    @BeforeEach
    void setUp() {
        transactions = mock(TransactionMgmtClient.class);
        audit = new StubOperatorActionAuditClient();
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // enforce=false (dev gate-off): absent permissions header allowed; a present-but-wrong
        // header is still denied. Fail-closed enforcement is covered in OpsRbacGuardTest.
        mvc = standaloneSetup(new OpsTransactionController(transactions, audit, new OpsRbacGuard(false)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    @Test
    void search_proxiesMappedResults() throws Exception {
        TransactionSummary row = TransactionSummary.of("TXN-1001", "P_A", "UNCERTAIN",
                new BigDecimal("125.50"), "USD", Instant.parse("2026-06-09T10:15:30Z"));
        when(transactions.search(any(SearchQuery.class)))
                .thenReturn(new TransactionMgmtClient.Page<>(List.of(row), 0, 20, 1));

        mvc.perform(get("/v1/admin/transactions/search")
                        .param("q", "TXN-1001").param("status", "UNCERTAIN")
                        .param("userRef", "wallet-123").param("reference", "PARTNER-REF-9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.content[0].txnId").value("TXN-1001"))
                .andExpect(jsonPath("$.content[0].state").value("UNCERTAIN"));

        ArgumentCaptor<SearchQuery> cap = ArgumentCaptor.forClass(SearchQuery.class);
        verify(transactions).search(cap.capture());
        assertThat(cap.getValue().q()).isEqualTo("TXN-1001");
        assertThat(cap.getValue().status()).isEqualTo("UNCERTAIN");
        assertThat(cap.getValue().userRef()).isEqualTo("wallet-123");
        assertThat(cap.getValue().reference()).isEqualTo("PARTNER-REF-9");
    }

    @Test
    void txnViewCanSearch_butCannotResolve() throws Exception {
        // A support agent presents txn.view but NOT ops:operate.
        when(transactions.search(any(SearchQuery.class)))
                .thenReturn(new TransactionMgmtClient.Page<>(List.of(), 0, 20, 0));

        // CAN search with txn.view (fail-closed guard passes on txn.view).
        mvc.perform(get("/v1/admin/transactions/search")
                        .param("q", "TXN-1001")
                        .header("X-Gme-Permissions", "txn.view"))
                .andExpect(status().isOk());

        // CANNOT force-resolve — that still requires ops:operate → 403.
        mvc.perform(post("/v1/admin/transactions/TXN-1001/resolve")
                        .header("X-Gme-Permissions", "txn.view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"FORCE_APPROVE\"}"))
                .andExpect(status().isForbidden());
        assertThat(audit.captured()).isEmpty();
    }

    @Test
    void resolve_delegatesAndAudits() throws Exception {
        TransactionSummary resolved = TransactionSummary.of("TXN-1001", "P_A", "COMMITTED",
                new BigDecimal("125.50"), "USD", Instant.parse("2026-06-09T10:15:30Z"));
        when(transactions.resolve(eq("TXN-1001"), eq("FORCE_APPROVE"), any(), any()))
                .thenReturn(resolved);

        mvc.perform(post("/v1/admin/transactions/TXN-1001/resolve")
                        .header("X-Gme-Principal-Id", "ops.admin@gmepay.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"FORCE_APPROVE\",\"reason\":\"scheme confirmed offline\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMMITTED"));

        verify(transactions).resolve("TXN-1001", "FORCE_APPROVE", "ops.admin@gmepay.com",
                "scheme confirmed offline");
        assertThat(audit.captured()).singleElement()
                .satisfies(r -> {
                    assertThat(r.action()).isEqualTo("transaction.resolve");
                    assertThat(r.target()).isEqualTo("TXN-1001");
                    assertThat(r.actor()).isEqualTo("ops.admin@gmepay.com");
                });
    }

    @Test
    void resolve_missingResolution_is400_andNoAudit() throws Exception {
        mvc.perform(post("/v1/admin/transactions/TXN-1001/resolve")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(audit.captured()).isEmpty();
    }
}
