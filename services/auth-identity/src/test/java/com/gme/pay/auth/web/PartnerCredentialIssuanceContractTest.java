package com.gme.pay.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.auth.persistence.ApiKeyEntity;
import com.gme.pay.auth.persistence.ApiKeyRepository;
import com.gme.pay.auth.service.ApiKeyIssuanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Gap <b>T1-1</b> proof, auth-identity side: <b>a credential issued through the
 * exact wire request config-registry's activation flow sends is one this service
 * can subsequently verify.</b>
 *
 * <h2>What was broken</h2>
 *
 * <p>Nothing here — this endpoint always worked. The defect was that
 * config-registry never reached it: its {@code StubAuthIdentityClient} was the
 * unconditional default because {@code GMEPAY_AUTH_IDENTITY_CLIENT=rest} was set
 * for ops-partner-bff but never for config-registry, so activation returned
 * locally-invented strings for which no {@code api_keys} row existed. This test
 * is the "verifiable" half of the claim; the "actually calls it" half is
 * {@code config-registry}'s {@code CredentialClientSelectionTest} (bean
 * selection) + {@code RestAuthIdentityClientTest} (wire shape).
 *
 * <h2>Harness</h2>
 *
 * <p>The REAL {@link ApiKeyAdminController} over the REAL
 * {@link ApiKeyIssuanceService} over a REAL (H2/PostgreSQL-mode) database, driven
 * over HTTP by {@code standaloneSetup} — the repo's controller-test idiom, but
 * with the service unmocked so the persistence side effect is genuine. The
 * request bodies below are hand-written JSON, deliberately NOT built from
 * {@code com.gme.pay.auth.dto.IssueKeyRequest}: they must reproduce what
 * config-registry's independently-mirrored
 * {@code RestAuthIdentityClient.IssueKeyRequest} record puts on the wire, so a
 * field rename on either side has to break one of these two tests.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ApiKeyIssuanceService.class)
@DisplayName("T1-1: activation-issued credentials are verifiable by auth-identity")
class PartnerCredentialIssuanceContractTest {

    /** Exactly the field set + names RestAuthIdentityClient.IssueKeyRequest serialises. */
    private static final String API_KEY_REQUEST = """
            {"partnerId":42,
             "partnerCode":"GMEREMIT",
             "environment":"PRODUCTION",
             "purpose":"API",
             "keyPrefix":"pk_live_",
             "secretPrefix":"sk_live_",
             "expiresAt":"2027-07-28T00:00:00Z"}""";

    /** The WEBHOOK-purpose twin config-registry issues in the same activation. */
    private static final String WEBHOOK_KEY_REQUEST = """
            {"partnerId":42,
             "partnerCode":"GMEREMIT",
             "environment":"PRODUCTION",
             "purpose":"WEBHOOK",
             "keyPrefix":"whk_",
             "secretPrefix":"whsec_live_",
             "expiresAt":"2027-07-28T00:00:00Z"}""";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ApiKeyIssuanceService service;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new ApiKeyAdminController(service)).build();
    }

    private JsonNode issue(String body) throws Exception {
        String response = mvc.perform(post("/internal/auth/keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response);
    }

    private JsonNode resolve(String apiKey) throws Exception {
        String response = mvc.perform(post("/internal/auth/keys/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + apiKey + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response);
    }

    @Test
    @DisplayName("issue -> resolve: the API key activation hands the operator is found + ACTIVE")
    void issuedApiKeyResolvesAsActive() throws Exception {
        JsonNode issued = issue(API_KEY_REQUEST);

        String keyId = issued.get("keyId").asText();
        String secret = issued.get("secretPlaintext").asText();
        assertThat(keyId).startsWith("pk_live_");
        assertThat(secret).startsWith("sk_live_");

        // THE assertion gap T1-1 was about: the credential the activation modal
        // displays is one this service recognises. With the stub wired in,
        // found=false here and every partner request 401s.
        JsonNode lookup = resolve(keyId);
        assertThat(lookup.get("found").asBoolean()).isTrue();
        assertThat(lookup.get("active").asBoolean()).isTrue();
        assertThat(lookup.get("partnerId").asLong()).isEqualTo(42L);

        // ...and the one-time HMAC secret verifies against the stored salted hash,
        // so a request SIGNED with it authenticates (SEC-09 §4: hash only at rest).
        ApiKeyEntity row = apiKeyRepository.findByApiKey(keyId).orElseThrow();
        assertThat(row.secretMatches(secret)).isTrue();
        assertThat(row.secretMatches(secret + "x")).isFalse();
        assertThat(row.getSecretHash()).isNotBlank().isNotEqualTo(secret);
        assertThat(row.getStatus()).isEqualTo(ApiKeyEntity.Status.ACTIVE);
    }

    @Test
    @DisplayName("issue(WEBHOOK) -> resolve: the webhook credential is equally verifiable")
    void issuedWebhookKeyResolvesAsActive() throws Exception {
        JsonNode issued = issue(WEBHOOK_KEY_REQUEST);

        String keyId = issued.get("keyId").asText();
        assertThat(keyId).startsWith("whk_");
        assertThat(issued.get("secretPlaintext").asText()).startsWith("whsec_live_");

        JsonNode lookup = resolve(keyId);
        assertThat(lookup.get("found").asBoolean()).isTrue();
        assertThat(lookup.get("active").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("the pre-fix symptom: a locally-fabricated stub-shaped key resolves found=false")
    void fabricatedKeyDoesNotResolve() throws Exception {
        // Precisely the shape StubAuthIdentityClient minted (prefix + 24 random
        // display-alphabet chars). It authenticates nothing — this is what every
        // deployed environment was handing partners before T1-1 was closed.
        JsonNode lookup = resolve("pk_live_abcdefghjkmnpqrstuvwxyz");
        assertThat(lookup.get("found").asBoolean()).isFalse();
        assertThat(lookup.get("active").asBoolean()).isFalse();
        assertThat(lookup.get("partnerId").isNull()).isTrue();
    }

    @Test
    @DisplayName("revoke is REAL: after POST .../revoke the key resolves found=true, active=false")
    void revokeActuallyDeactivates() throws Exception {
        String keyId = issue(API_KEY_REQUEST).get("keyId").asText();
        assertThat(resolve(keyId).get("active").asBoolean()).isTrue();

        // StubAuthIdentityClient.revokeKey() is an empty method — a suspended or
        // terminated partner's key stayed usable. Here it flips the row.
        mvc.perform(post("/internal/auth/keys/{keyId}/revoke", keyId))
                .andExpect(status().isNoContent());

        JsonNode afterRevoke = resolve(keyId);
        assertThat(afterRevoke.get("found").asBoolean()).isTrue();
        assertThat(afterRevoke.get("active").asBoolean()).isFalse();
        assertThat(apiKeyRepository.findByApiKey(keyId).orElseThrow().getStatus())
                .isEqualTo(ApiKeyEntity.Status.REVOKED);

        // Idempotent: config-registry's rotation/revocation flow may retry.
        mvc.perform(post("/internal/auth/keys/{keyId}/revoke", keyId))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("the response carries every field config-registry's mirrored record reads")
    void responseShapeMatchesTheRegistryMirror() throws Exception {
        mvc.perform(post("/internal/auth/keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(API_KEY_REQUEST))
                .andExpect(status().isOk())
                // RestAuthIdentityClient.IssueKeyResponse reads these three...
                .andExpect(jsonPath("$.keyId").exists())
                .andExpect(jsonPath("$.secretPlaintext").exists())
                .andExpect(jsonPath("$.expiresAt").exists())
                // ...and tolerates these three via @JsonIgnoreProperties(ignoreUnknown).
                .andExpect(jsonPath("$.prefix").exists())
                .andExpect(jsonPath("$.environment").value("PRODUCTION"))
                .andExpect(jsonPath("$.createdAt").exists());
    }
}
