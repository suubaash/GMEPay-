package com.gme.pay.registry.client;

import com.gme.pay.contracts.WebhookEndpointRegistrationCommand;
import com.gme.pay.contracts.WebhookEndpointRegistrationView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link MockRestServiceServer} unit tests for
 * {@link RestNotificationWebhookClient} — the HTTP transport behind
 * {@code POST /v1/webhooks/endpoints} (Slice 8 Lane D), same harness as
 * payment-executor's {@code RestPrefundingClientTest}.
 */
class RestNotificationWebhookClientTest {

    private MockRestServiceServer server;
    private RestNotificationWebhookClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder =
                RestClient.builder().baseUrl("http://notification-webhook:8085");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestNotificationWebhookClient(builder.build());
    }

    @Test
    @DisplayName("registerEndpoint: POSTs the registration JSON and parses the one-time secret")
    void registerEndpoint_happyPath() {
        server.expect(requestTo("http://notification-webhook:8085/v1/webhooks/endpoints"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.partnerId").value(42))
                .andExpect(jsonPath("$.url").value("https://p.example.com/hooks"))
                .andExpect(jsonPath("$.eventTypes[0]").value("payment.approved"))
                .andExpect(jsonPath("$.environment").value("SANDBOX"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"endpointId\":\"1001\","
                                + "\"signingSecretPlaintext\":\"whsec_abc123\","
                                + "\"newlyRegistered\":true}"));

        WebhookEndpointRegistrationView view = client.registerEndpoint(
                new WebhookEndpointRegistrationCommand(
                        42L, "https://p.example.com/hooks",
                        List.of("payment.approved"), "SANDBOX"));

        assertEquals("1001", view.endpointId());
        assertEquals("whsec_abc123", view.signingSecretPlaintext());
        assertTrue(view.newlyRegistered());
        server.verify();
    }

    @Test
    @DisplayName("registerEndpoint: idempotent 200 replay parses null secret")
    void registerEndpoint_idempotentReplay() {
        server.expect(requestTo("http://notification-webhook:8085/v1/webhooks/endpoints"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"endpointId\":\"1001\","
                                + "\"signingSecretPlaintext\":null,"
                                + "\"newlyRegistered\":false}",
                        MediaType.APPLICATION_JSON));

        WebhookEndpointRegistrationView view = client.registerEndpoint(
                new WebhookEndpointRegistrationCommand(
                        42L, "https://p.example.com/hooks", null, "SANDBOX"));

        assertEquals("1001", view.endpointId());
        assertNull(view.signingSecretPlaintext());
        assertFalse(view.newlyRegistered());
        server.verify();
    }

    @Test
    @DisplayName("registerEndpoint: upstream 400 re-thrown with status + body preserved")
    void registerEndpoint_badRequestSurfacesVerbatim() {
        server.expect(requestTo("http://notification-webhook:8085/v1/webhooks/endpoints"))
                .andRespond(withBadRequest()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("url must use HTTPS, got: http://x"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> client.registerEndpoint(new WebhookEndpointRegistrationCommand(
                        42L, "http://x", null, "SANDBOX")));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("url must use HTTPS"));
        server.verify();
    }

    @Test
    @DisplayName("registerEndpoint: upstream 5xx re-thrown with status preserved (activation rolls back)")
    void registerEndpoint_serverErrorMapsTo502() {
        server.expect(requestTo("http://notification-webhook:8085/v1/webhooks/endpoints"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("upstream boom"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> client.registerEndpoint(new WebhookEndpointRegistrationCommand(
                        42L, "https://p.example.com/hooks", null, "LIVE")));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
        server.verify();
    }
}
