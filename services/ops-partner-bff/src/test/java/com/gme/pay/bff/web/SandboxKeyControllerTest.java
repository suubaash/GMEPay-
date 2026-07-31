package com.gme.pay.bff.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.client.PrefundingClient;
import com.gme.pay.bff.client.SandboxKeyClient;
import com.gme.pay.bff.client.SettlementClient;
import com.gme.pay.bff.client.TransactionMgmtClient;
import com.gme.pay.bff.client.stub.StubApiKeyClient;
import com.gme.pay.bff.client.stub.StubConfigRegistryClient;
import com.gme.pay.bff.client.stub.StubPrefundingClient;
import com.gme.pay.bff.client.stub.StubSandboxKeyClient;
import com.gme.pay.bff.client.stub.StubSettlementClient;
import com.gme.pay.bff.client.stub.StubStatementClient;
import com.gme.pay.bff.client.stub.StubTransactionMgmtClient;
import com.gme.pay.bff.security.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Standalone MockMvc test for the self-serve SANDBOX key issuance endpoints on
 * {@link PartnerPortalController}, backed by the real {@link StubSandboxKeyClient}.
 *
 * <p>Pins the self-serve onboarding contract:
 * <ol>
 *   <li>POST {@code /v1/portal/{p}/sandbox-keys} returns 201 with the ONE-TIME
 *       plaintext {@code apiKey}, a non-secret prefix and {@code scope=SANDBOX};</li>
 *   <li>the store persists only a hash — a subsequent GET never returns the
 *       plaintext, only id/prefix/scope/createdAt;</li>
 *   <li>the issued key is SANDBOX-scoped (test-prefixed) so it is visibly
 *       distinct from a production key.</li>
 * </ol>
 */
class SandboxKeyControllerTest {

    private MockMvc mvc;
    private StubSandboxKeyClient sandbox;

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
        sandbox = new StubSandboxKeyClient();

        PartnerPortalController controller = new PartnerPortalController(
                transactions, prefunding, settlement, configRegistry,
                new StubApiKeyClient(), sandbox, new StubStatementClient(),
                new com.gme.pay.bff.client.stub.StubPortalWebhookClient(),
                new OpsRbacGuard(true));

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(om);

        mvc = standaloneSetup(controller).setMessageConverters(converter).build();
    }

    @Test
    @DisplayName("POST sandbox-keys returns 201 with a one-time plaintext key, prefix and SANDBOX scope")
    void issue_returnsPlaintextOnce_scopedSandbox() throws Exception {
        mvc.perform(post("/v1/portal/{p}/sandbox-keys", "partner_test_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"my first key\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.keyId").value(org.hamcrest.Matchers.startsWith("pk_test_")))
                .andExpect(jsonPath("$.apiKey").value(org.hamcrest.Matchers.startsWith("sk_test_")))
                .andExpect(jsonPath("$.prefix").value(org.hamcrest.Matchers.startsWith("pk_test_")))
                .andExpect(jsonPath("$.scope").value("SANDBOX"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    @DisplayName("issued secret is stored only as a hash — plaintext is never persisted or re-listed")
    void store_keepsHashOnly_plaintextNeverReListed() throws Exception {
        SandboxKeyClient.IssuedSandboxKey issued = sandbox.issue("partner_hash_check", "k1");

        // The list view never carries the plaintext secret — only metadata.
        var listed = sandbox.listForPartner("partner_hash_check");
        assertThat(listed).hasSize(1);
        SandboxKeyClient.SandboxKeyView view = listed.get(0);
        assertThat(view.keyId()).isEqualTo(issued.keyId());
        assertThat(view.scope()).isEqualTo("SANDBOX");
        // No accessor on the view exposes the secret; and the record's field set
        // is (keyId, prefix, scope, createdAt) — the plaintext is unreachable.
        assertThat(view.toString()).doesNotContain(issued.apiKey());

        // The GET endpoint returns the same secret-free view shape.
        mvc.perform(get("/v1/portal/{p}/sandbox-keys", "partner_hash_check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].keyId").value(issued.keyId()))
                .andExpect(jsonPath("$[0].scope").value("SANDBOX"))
                .andExpect(jsonPath("$[0].apiKey").doesNotExist());
    }

    @Test
    @DisplayName("each issuance mints a distinct one-time secret")
    void issue_mintsDistinctSecretsPerCall() {
        SandboxKeyClient.IssuedSandboxKey a = sandbox.issue("partner_uniq", null);
        SandboxKeyClient.IssuedSandboxKey b = sandbox.issue("partner_uniq", null);
        assertThat(a.keyId()).isNotEqualTo(b.keyId());
        assertThat(a.apiKey()).isNotEqualTo(b.apiKey());
        assertThat(sandbox.listForPartner("partner_uniq")).hasSize(2);
    }

    @Test
    @DisplayName("IssuedSandboxKey.toString redacts the plaintext secret (SEC-09 §4)")
    void issued_toStringRedactsSecret() {
        SandboxKeyClient.IssuedSandboxKey issued = sandbox.issue("partner_redact", "k");
        assertThat(issued.toString()).contains("REDACTED").doesNotContain(issued.apiKey());
    }
}
