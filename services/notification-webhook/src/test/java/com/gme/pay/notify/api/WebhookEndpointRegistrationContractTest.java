package com.gme.pay.notify.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.notify.config.ClockConfig;
import com.gme.pay.notify.persistence.WebhookEndpointEntity;
import com.gme.pay.notify.persistence.WebhookEndpointRepository;
import com.gme.pay.notify.provisioning.SigningSecrets;
import com.gme.pay.notify.provisioning.WebhookEndpointProvisioningService;
import com.gme.pay.notify.provisioning.WebhookSecretDeriver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Gap <b>T1-1</b> proof, notification-webhook side: <b>the webhook signing secret
 * returned at partner activation is the one THIS service stores and will use to
 * verify/produce delivery signatures.</b>
 *
 * <h2>What was broken</h2>
 *
 * <p>Not this endpoint. config-registry's
 * {@code StubNotificationWebhookClient} carried
 * {@code @ConditionalOnProperty(..., matchIfMissing = true)} while its REST twin
 * needed an explicit {@code gmepay.notification-webhook.client=rest} that was set
 * in NO environment (not docker-compose.yml, not values.yaml, not run-fleet.ps1).
 * Activation therefore minted a {@code whsec_} secret in config-registry's own
 * process; no {@code webhook_endpoint} row was ever created here, so a partner
 * computing {@code X-Gme-Signature} with that secret could never match what the
 * dispatcher signs with — and the endpoint itself did not exist, so no delivery
 * would have been attempted at all.
 *
 * <h2>Harness</h2>
 *
 * <p>REAL {@link WebhookEndpointController} over the REAL
 * {@link WebhookEndpointProvisioningService} over a real H2 (PostgreSQL-mode)
 * database — {@code standaloneSetup} for the HTTP layer,
 * {@link WebhookEndpointRepository} for the side-effect assertion. The request
 * body is hand-written JSON matching what config-registry's
 * {@code RestNotificationWebhookClient} PUTs on the wire from
 * {@code WebhookEndpointRegistrationCommand}, so a field rename breaks this test.
 *
 * <p>The service-level behaviours (validation rosters, idempotency internals) are
 * covered by {@code WebhookEndpointProvisioningServiceTest}; what is added here is
 * the HTTP contract + the secret-identity assertion that gap T1-1 turns on.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({WebhookEndpointProvisioningService.class, ClockConfig.class,
        WebhookEndpointRegistrationContractTest.DeriverConfig.class})
@DisplayName("T1-1: the activation webhook secret is the one notification-webhook knows")
class WebhookEndpointRegistrationContractTest {

    /**
     * T5-4 added the per-endpoint derivation collaborator; the T1-1 contract asserted here
     * is unchanged (the secret returned at activation still hashes to the stored row) — the
     * secret is now derived for this endpoint instead of being randomness the dispatcher
     * ignored in favour of one global secret.
     */
    @TestConfiguration
    static class DeriverConfig {
        @Bean
        WebhookSecretDeriver webhookSecretDeriver() {
            return WebhookSecretDeriver.withRootKey("t1-1-contract-test-root-key-0123456789");
        }
    }

    /** Exactly the field names WebhookEndpointRegistrationCommand serialises. */
    private static final String REGISTRATION = """
            {"partnerId":42,
             "url":"https://partner.example.com/hooks/gmepay",
             "eventTypes":["payment.approved","payment.failed"],
             "environment":"LIVE"}""";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private WebhookEndpointProvisioningService service;

    @Autowired
    private WebhookEndpointRepository repository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new WebhookEndpointController(service)).build();
    }

    private JsonNode register(String body, int expectedStatus) throws Exception {
        String response = mvc.perform(post("/v1/webhooks/endpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response);
    }

    @Test
    @DisplayName("POST /v1/webhooks/endpoints: the returned secret hashes to the row this service stored")
    void returnedSecretIsTheStoredSecret() throws Exception {
        JsonNode view = register(REGISTRATION, 201);

        String endpointId = view.get("endpointId").asText();
        String secret = view.get("signingSecretPlaintext").asText();
        assertThat(view.get("newlyRegistered").asBoolean()).isTrue();
        assertThat(secret).startsWith(SigningSecrets.SECRET_PREFIX);

        WebhookEndpointEntity row =
                repository.findById(Long.valueOf(endpointId)).orElseThrow();

        // THE T1-1 assertion: activation's plaintext belongs to a row that exists
        // HERE. With the stub wired in, there was no row at all and the secret was
        // config-registry-local randomness.
        assertThat(SigningSecrets.matches(secret, row.getSigningSecretHash())).isTrue();
        assertThat(row.getSigningSecretHash()).isNotEqualTo(secret);   // hash-only at rest
        assertThat(row.getPartnerId()).isEqualTo(42L);
        assertThat(row.getEnvironment()).isEqualTo("LIVE");
        assertThat(row.getWebhookUrl()).isEqualTo("https://partner.example.com/hooks/gmepay");
        assertThat(row.getEventTypesCsv()).isEqualTo("payment.approved,payment.failed");
        assertThat(row.isActive()).isTrue();

        // Cross-service digest agreement: config-registry stores its own SHA-256 hex
        // of the same plaintext (WebhookProvisioningService.sha256Hex) on the V030
        // partner_webhook_subscription row. Both sides must derive the SAME 64-char
        // lowercase hex or the two ledgers can never be reconciled.
        assertThat(SigningSecrets.sha256Hex(secret))
                .isEqualTo(row.getSigningSecretHash())
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("SHA-256 hex is the agreed at-rest form on BOTH sides (fixed-vector check)")
    void digestIsTheAgreedAlgorithm() {
        // config-registry's WebhookProvisioningService.sha256Hex is an INDEPENDENT copy
        // of this function (no shared lib links them). Pinning a fixed vector on this
        // side means a change of algorithm or hex-encoding here cannot silently
        // desynchronise the two credential ledgers: unsalted SHA-256 of the secret's
        // UTF-8 bytes, lowercase hex, 64 chars.
        assertThat(SigningSecrets.sha256Hex("whsec_test")).isEqualTo(
                "609b97b03239401be8235dd68f4a53ea4e32183a775fb958b1c745e173586d73");
        assertThat(SigningSecrets.sha256Hex("whsec_test")).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(SigningSecrets.sha256Hex("whsec_tesu"))
                .isNotEqualTo(SigningSecrets.sha256Hex("whsec_test"));
    }

    @Test
    @DisplayName("activation retry replays 200 with the SAME endpoint and NO second secret")
    void retryIsIdempotentAndRevealsNoNewSecret() throws Exception {
        JsonNode first = register(REGISTRATION, 201);
        String endpointId = first.get("endpointId").asText();
        String hashAfterFirst = repository.findById(Long.valueOf(endpointId))
                .orElseThrow().getSigningSecretHash();

        JsonNode replay = register(REGISTRATION, 200);

        assertThat(replay.get("endpointId").asText()).isEqualTo(endpointId);
        assertThat(replay.get("newlyRegistered").asBoolean()).isFalse();
        assertThat(replay.get("signingSecretPlaintext").isNull()).isTrue();
        // The stored secret was NOT rotated by the retry — a partner already
        // integrated against the first secret keeps working.
        assertThat(repository.findById(Long.valueOf(endpointId)).orElseThrow()
                .getSigningSecretHash()).isEqualTo(hashAfterFirst);
        assertThat(repository.findByPartnerIdAndEnvironmentAndActiveTrue(42L, "LIVE")).hasSize(1);
    }

    @Test
    @DisplayName("a malformed registration is a 400, so activation rolls back rather than half-provisioning")
    void malformedRegistrationIsBadRequest() throws Exception {
        // config-registry maps a 4xx here into a ResponseStatusException that
        // propagates out of the activation transaction (RestNotificationWebhookClient),
        // leaving the V030 row DRAFT instead of PROVISIONED-with-no-endpoint.
        mvc.perform(post("/v1/webhooks/endpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partnerId":42,"url":"http://insecure.example.com/hooks",
                                 "eventTypes":["payment.approved"],"environment":"LIVE"}"""))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/v1/webhooks/endpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partnerId":42,"url":"https://p.example.com/hooks",
                                 "eventTypes":["payment.approved"],"environment":"PRODUCTION"}"""))
                .andExpect(status().isBadRequest());

        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("the response carries exactly the three fields config-registry's client reads")
    void responseShapeMatchesTheSharedContract() throws Exception {
        mvc.perform(post("/v1/webhooks/endpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTRATION))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.endpointId").exists())
                .andExpect(jsonPath("$.signingSecretPlaintext").exists())
                .andExpect(jsonPath("$.newlyRegistered").value(true));
    }
}
