package com.gme.pay.registry.kyb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gme.pay.kyb.KybSubject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link MockRestServiceServer} unit tests for {@link RestKybVerifyClient} —
 * the HTTP transport behind the Wave-3 onboarding→KYB-verify wiring
 * ({@code POST /v1/kyb/verify}). Same harness as
 * {@code RestNotificationWebhookClientTest}.
 */
class RestKybVerifyClientTest {

    private MockRestServiceServer server;
    private RestKybVerifyClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://kyb-adapter:8080");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestKybVerifyClient(builder.build());
    }

    private static KybVerificationRequest request() {
        return new KybVerificationRequest(
                new KybSubject("GMEREMIT", "지엠이", "GME Remit", "KR", "TAX-1", List.of()),
                List.of("BUSINESS_REGISTRATION", "AOA", "UBO_DECLARATION"),
                false);
    }

    @Test
    @DisplayName("verify: POSTs the subject + documents and parses the collapsed decision")
    void verify_happyPath() {
        server.expect(requestTo("http://kyb-adapter:8080/v1/kyb/verify"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.subject.partnerCode").value("GMEREMIT"))
                .andExpect(jsonPath("$.suppliedDocuments[0]").value("BUSINESS_REGISTRATION"))
                .andExpect(jsonPath("$.force").value(false))
                .andRespond(withSuccess(
                        "{\"providerRef\":\"kyb-9f2\",\"partnerCode\":\"GMEREMIT\","
                                + "\"decision\":\"APPROVED\",\"decisionReason\":\"all clear\","
                                + "\"screeningStatus\":\"CLEAR\","
                                + "\"missingDocuments\":[],\"idempotentReplay\":false,"
                                + "\"screenedAt\":\"2026-06-30T01:02:03.000004Z\"}",
                        MediaType.APPLICATION_JSON));

        KybVerificationResult result = client.verify(request());

        assertEquals("kyb-9f2", result.providerRef());
        assertEquals("APPROVED", result.decision());
        assertEquals("CLEAR", result.screeningStatus());
        server.verify();
    }

    @Test
    @DisplayName("verify: unknown adapter fields are ignored (ignoreUnknown)")
    void verify_toleratesRicherPayload() {
        server.expect(requestTo("http://kyb-adapter:8080/v1/kyb/verify"))
                .andRespond(withSuccess(
                        "{\"providerRef\":\"kyb-1\",\"decision\":\"MANUAL_REVIEW\","
                                + "\"hits\":[{\"listName\":\"OFAC\"}],"
                                + "\"bizRegStatus\":\"VERIFIED\"}",
                        MediaType.APPLICATION_JSON));

        KybVerificationResult result = client.verify(request());

        assertEquals("kyb-1", result.providerRef());
        assertEquals("MANUAL_REVIEW", result.decision());
        server.verify();
    }

    @Test
    @DisplayName("verify: upstream 400 re-thrown with status + body preserved")
    void verify_badRequestSurfacesVerbatim() {
        server.expect(requestTo("http://kyb-adapter:8080/v1/kyb/verify"))
                .andRespond(withBadRequest().body("subject is required"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> client.verify(request()));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("subject is required"));
        server.verify();
    }

    @Test
    @DisplayName("verify: upstream 5xx surfaced with status preserved")
    void verify_serverError() {
        server.expect(requestTo("http://kyb-adapter:8080/v1/kyb/verify"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("boom"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> client.verify(request()));

        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
        server.verify();
    }
}
