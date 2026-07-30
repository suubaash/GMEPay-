package com.gme.pay.bff.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.TransactionMgmtClient;
import com.gme.pay.bff.client.stub.StubApiKeyClient;
import com.gme.pay.bff.client.stub.StubConfigRegistryClient;
import com.gme.pay.bff.client.stub.StubPortalWebhookClient;
import com.gme.pay.bff.client.stub.StubPrefundingClient;
import com.gme.pay.bff.client.stub.StubRevenueLedgerClient;
import com.gme.pay.bff.client.stub.StubSandboxKeyClient;
import com.gme.pay.bff.client.stub.StubSettlementClient;
import com.gme.pay.bff.client.rest.RestStatementClient;
import com.gme.pay.bff.security.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * T4-4 — BOTH transaction-detail builders surface the real merchant name.
 *
 * <p>{@code AdminDashboardController.buildDetail} and {@code PartnerPortalController.buildDetail} each
 * passed a hardcoded {@code null} into {@code TransactionDetail.merchantName}, so the drawer rendered
 * an em dash no matter what transaction-mgmt returned. They are separate methods that must stay in
 * step (the Portal explicitly "mirrors" the Admin one), so both are asserted here — fixing one and
 * leaving the other is exactly how this half-regresses.
 *
 * <p>The null case is asserted just as hard: when the upstream row has no name the field must be
 * ABSENT (the DTO is {@code @JsonInclude(NON_NULL)}) rather than filled in from {@code merchantId}.
 */
class MerchantNameDetailTest {

    private static final String PARTNER = "partner_test_001";

    private ObjectMapper objectMapper;

    /**
     * A platform operator holding both the Admin transaction-read permission ({@code txn.view}) and
     * the cross-partner portal read ({@code partner.view}) — this test exercises the two detail
     * BUILDERS, not the scope rules (those live in {@code PartnerPortalScopeTest} / RBAC tests).
     */
    @BeforeEach
    void authenticateAsCrossReadingOperator() {
        TestTokens.hubOperator("txn.view", "partner.view");
    }

    @AfterEach
    void clearAuthentication() {
        TestTokens.clear();
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** A transaction-mgmt row with (or without) the V012 merchant name. */
    private static TransactionMgmtClient.TransactionSummary row(String merchantName) {
        return new TransactionMgmtClient.TransactionSummary(
                "TXN-T44", PARTNER, "COMMITTED", new BigDecimal("125.50"), "USD",
                Instant.parse("2026-06-09T10:15:30Z"),
                "zeropay_kr", null, null, null, null, null, new BigDecimal("0.0935"),
                "ZP-TXN-T44", "AUTH-T44", "M0000000001", merchantName,
                Instant.parse("2026-06-09T10:15:31Z"),
                null, "Approved", null,
                List.of(TransactionMgmtClient.StatusEntry.of(
                        "CREATED", Instant.parse("2026-06-09T10:15:30Z"))));
    }

    private static TransactionMgmtClient clientReturning(String merchantName) {
        TransactionMgmtClient txns = mock(TransactionMgmtClient.class);
        when(txns.getTransaction(anyString())).thenReturn(row(merchantName));
        return txns;
    }

    private MockMvc adminMvc(TransactionMgmtClient txns) {
        AdminDashboardController controller = new AdminDashboardController(
                new StubConfigRegistryClient(), txns, new StubPrefundingClient(),
                new StubRevenueLedgerClient(), new StubSettlementClient(), new OpsRbacGuard(false));
        return build(controller);
    }

    private MockMvc portalMvc(TransactionMgmtClient txns) {
        PartnerPortalController controller = new PartnerPortalController(
                txns, new StubPrefundingClient(), new StubSettlementClient(),
                new StubConfigRegistryClient(), new StubApiKeyClient(), new StubSandboxKeyClient(),
                new RestStatementClient(txns), new StubPortalWebhookClient(),
                new OpsRbacGuard(true));
        return build(controller);
    }

    private MockMvc build(Object controller) {
        return standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper),
                        new ByteArrayHttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("Admin detail returns the REAL merchant name (was a hardcoded null)")
    void adminDetailCarriesTheName() throws Exception {
        adminMvc(clientReturning("Gangnam Coffee House"))
                .perform(get("/v1/admin/transactions/{id}", "TXN-T44"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantName").value("Gangnam Coffee House"))
                .andExpect(jsonPath("$.merchantId").value("M0000000001"));
    }

    @Test
    @DisplayName("Portal detail returns the REAL merchant name (mirrors Admin)")
    void portalDetailCarriesTheName() throws Exception {
        portalMvc(clientReturning("Gangnam Coffee House"))
                .perform(get("/v1/portal/{p}/transactions/{id}", PARTNER, "TXN-T44"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantName").value("Gangnam Coffee House"))
                .andExpect(jsonPath("$.merchantId").value("M0000000001"));
    }

    @Test
    @DisplayName("Admin detail omits merchantName when upstream has none — no id substitution")
    void adminDetailOmitsUnknownName() throws Exception {
        adminMvc(clientReturning(null))
                .perform(get("/v1/admin/transactions/{id}", "TXN-T44"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantName").doesNotExist())
                .andExpect(jsonPath("$.merchantId").value("M0000000001"));
    }

    @Test
    @DisplayName("Portal detail omits merchantName when upstream has none — no id substitution")
    void portalDetailOmitsUnknownName() throws Exception {
        portalMvc(clientReturning(null))
                .perform(get("/v1/portal/{p}/transactions/{id}", PARTNER, "TXN-T44"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantName").doesNotExist())
                .andExpect(jsonPath("$.merchantId").value("M0000000001"));
    }
}
