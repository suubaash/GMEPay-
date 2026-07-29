package com.gme.pay.bff.client.rest;

import com.gme.pay.bff.client.WebhookOpsClient;
import com.gme.pay.internalauth.InternalAuthHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Gap <b>T5-8</b>: {@link RestWebhookOpsClient}'s webhook-endpoint signing-health read and
 * secret rotation — the two calls that gave the previously caller-less
 * {@code POST /v1/webhooks/endpoints/{id}/rotate-secret} an operator path.
 *
 * <p>Also pins the internal-auth header. That upstream surface mints and reveals {@code whsec_}
 * plaintext and is machine-to-machine only, so it now declares the platform internal-auth gate;
 * the token must go on the wire the same way this client's gated siblings
 * ({@link RestSandboxKeyClient}, {@link RestPrefundingClient}) send it, or arming that gate
 * would 401 every rotation.
 */
class RestWebhookOpsClientEndpointSecretTest {

    /** The client is built with a base URL, so MockRestServiceServer sees absolute URIs. */
    private static final String BASE = "http://notification-webhook:8080";

    /** Test fixture, not a credential — never read outside the test source set. */
    private static final String INTERNAL_TOKEN = "fixture-token-not-a-deployment-secret";

    private static final String HEALTH_JSON = """
            [{"endpointId":"17","partnerId":42,"environment":"LIVE",
              "webhookUrl":"https://legacy.example.com/hooks","secretGeneration":1,
              "status":"SECRET_NOT_DERIVABLE","deliverable":false,"fixableByRotation":true,
              "detail":"cannot be re-derived","rotationOverlapExpiresAt":null,
              "createdAt":"2026-01-05T00:00:00Z","updatedAt":"2026-01-05T00:00:00Z"},
             {"endpointId":"18","partnerId":43,"environment":"LIVE",
              "webhookUrl":"https://new.example.com/hooks","secretGeneration":1,
              "status":"SIGNABLE","deliverable":true,"fixableByRotation":false,
              "detail":"signable","rotationOverlapExpiresAt":null,
              "createdAt":"2026-07-01T00:00:00Z","updatedAt":"2026-07-01T00:00:00Z"}]
            """;

    private static final String ROTATED_JSON = """
            {"endpointId":"17","signingSecretPlaintext":"whsec_fixture_rotated_value",
             "secretGeneration":2,"previousSecretExpiresAt":"2026-07-30T05:00:00Z"}
            """;

    private MockRestServiceServer server;

    private RestWebhookOpsClient clientWithToken(String internalSecret) {
        RestClient.Builder builder = RestWebhookOpsClient.builderFor(
                "http://notification-webhook:8080", internalSecret);
        this.server = MockRestServiceServer.bindTo(builder).build();
        return new RestWebhookOpsClient(builder.build());
    }

    // ------------------------------------------------------- signing health

    @Test
    @DisplayName("signing-health maps the rows and separates un-signable from signable")
    void signingHealth_mapsRows() {
        RestWebhookOpsClient client = clientWithToken(INTERNAL_TOKEN);
        server.expect(requestTo(containsString("/v1/webhooks/endpoints/signing-health")))
                .andExpect(method(GET))
                .andExpect(header(InternalAuthHeaders.INTERNAL_TOKEN, INTERNAL_TOKEN))
                .andRespond(withSuccess(HEALTH_JSON, MediaType.APPLICATION_JSON));

        List<WebhookOpsClient.EndpointSigningHealth> rows = client.endpointSigningHealth(null);
        server.verify();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).deliverable()).isFalse();
        assertThat(rows.get(0).fixableByRotation()).isTrue();
        assertThat(rows.get(0).status()).isEqualTo("SECRET_NOT_DERIVABLE");
        assertThat(rows.get(1).deliverable()).isTrue();
    }

    @Test
    @DisplayName("a partnerId is forwarded as a query param (scoped read)")
    void signingHealth_forwardsPartnerId() {
        RestWebhookOpsClient client = clientWithToken(INTERNAL_TOKEN);
        server.expect(requestTo(BASE + "/v1/webhooks/endpoints/signing-health?partnerId=42"))
                .andExpect(method(GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.endpointSigningHealth(42L)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("signing-health degrades to an empty list on an upstream fault — the panel "
            + "shows no data rather than 500ing")
    void signingHealth_degradesOnError() {
        RestWebhookOpsClient client = clientWithToken(INTERNAL_TOKEN);
        server.expect(requestTo(containsString("/signing-health")))
                .andExpect(method(GET))
                .andRespond(withServerError());

        assertThat(client.endpointSigningHealth(null)).isEmpty();
        server.verify();
    }

    // ------------------------------------------------------------- rotation

    @Test
    @DisplayName("rotate posts to the endpoint's rotate-secret path with the internal token and "
            + "returns the one-time secret")
    void rotate_postsAndReturnsSecret() {
        RestWebhookOpsClient client = clientWithToken(INTERNAL_TOKEN);
        server.expect(requestTo(BASE + "/v1/webhooks/endpoints/17/rotate-secret?overlapMinutes=0"))
                .andExpect(method(POST))
                .andExpect(header(InternalAuthHeaders.INTERNAL_TOKEN, INTERNAL_TOKEN))
                .andRespond(withSuccess(ROTATED_JSON, MediaType.APPLICATION_JSON));

        WebhookOpsClient.RotatedWebhookSecret rotated = client.rotateEndpointSecret("17", 0L);
        server.verify();

        assertThat(rotated.endpointId()).isEqualTo("17");
        assertThat(rotated.signingSecretPlaintext()).isEqualTo("whsec_fixture_rotated_value");
        assertThat(rotated.secretGeneration()).isEqualTo(2);
        assertThat(rotated.previousSecretExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("a null overlap sends no overlapMinutes param, deferring to the upstream default")
    void rotate_omitsOverlapWhenNull() {
        RestWebhookOpsClient client = clientWithToken(INTERNAL_TOKEN);
        server.expect(requestTo(BASE + "/v1/webhooks/endpoints/17/rotate-secret"))
                .andExpect(method(POST))
                .andRespond(withSuccess(ROTATED_JSON, MediaType.APPLICATION_JSON));

        client.rotateEndpointSecret("17", null);
        server.verify();
    }

    @Test
    @DisplayName("an upstream 4xx propagates rather than being swallowed — a failed rotation must "
            + "never look like a success with no secret")
    void rotate_propagatesUpstreamRejection() {
        RestWebhookOpsClient client = clientWithToken(INTERNAL_TOKEN);
        server.expect(requestTo(containsString("/v1/webhooks/endpoints/999/rotate-secret")))
                .andExpect(method(POST))
                .andRespond(withBadRequest().body("no webhook endpoint with id 999"));

        assertThatThrownBy(() -> client.rotateEndpointSecret("999", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        server.verify();
    }

    @Test
    @DisplayName("a 200 with no secret in it is a 502, not a success — the operator would "
            + "otherwise believe they had a value to hand the partner")
    void rotate_emptyBodyIsNotSuccess() {
        RestWebhookOpsClient client = clientWithToken(INTERNAL_TOKEN);
        server.expect(requestTo(containsString("/rotate-secret")))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"endpointId\":\"17\",\"secretGeneration\":2}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.rotateEndpointSecret("17", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no signing secret");
        server.verify();
    }

    @Test
    @DisplayName("a blank internal secret sends NO token — fail-closed once the gate is armed, "
            + "never a bypass attempt")
    void blankSecretSendsNoToken() {
        RestWebhookOpsClient client = clientWithToken("");
        server.expect(requestTo(containsString("/signing-health")))
                .andExpect(method(GET))
                .andExpect(headerDoesNotExist(InternalAuthHeaders.INTERNAL_TOKEN))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        client.endpointSigningHealth(null);
        server.verify();
    }
}
