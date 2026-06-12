package com.gme.pay.prefunding.client;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link MockRestServiceServer} wire tests for prefunding's
 * {@link RestConfigRegistryClient}: the breach hook must POST
 * {@code /v1/change-requests} with {@code aggregateType=partner},
 * {@code payloadJsonb} carrying {@code status=SUSPENDED} and
 * {@code proposedBy='system'} (ADR-008 carve-out) — and must swallow upstream
 * failures (a config-registry outage may not roll back the balance mutation).
 */
class RestConfigRegistryClientTest {

    private MockRestServiceServer server;
    private RestConfigRegistryClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://config-registry:8080");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestConfigRegistryClient(builder.build());
    }

    @Test
    @DisplayName("proposePartnerSuspension POSTs the system change_request wire shape")
    void proposeSuspension_postsSystemChangeRequest() {
        server.expect(requestTo("http://config-registry:8080/v1/change-requests"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.aggregateType").value("partner"))
                .andExpect(jsonPath("$.aggregateId").value("BREACH_P1"))
                .andExpect(jsonPath("$.proposedBy").value("system"))
                .andExpect(jsonPath("$.payloadJsonb").value("{\"status\":\"SUSPENDED\"}"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":1,\"state\":\"PROPOSED\"}"));

        client.proposePartnerSuspension("BREACH_P1", "balance breached: -5.0000 USD");

        server.verify();
    }

    @Test
    @DisplayName("an upstream 5xx is swallowed (logged), never propagated into the balance tx")
    void upstreamFailure_isSwallowed() {
        server.expect(requestTo("http://config-registry:8080/v1/change-requests"))
                .andRespond(withServerError());

        // must not throw
        client.proposePartnerSuspension("BREACH_P2", "balance breached");

        server.verify();
    }
}
