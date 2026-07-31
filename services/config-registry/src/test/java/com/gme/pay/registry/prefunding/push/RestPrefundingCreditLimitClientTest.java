package com.gme.pay.registry.prefunding.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gme.pay.internalauth.InternalAuthHeaders;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link MockRestServiceServer} unit tests for
 * {@link RestPrefundingCreditLimitClient} — the HTTP transport behind the
 * Wave-3 credit-limit push ({@code PUT
 * /internal/v1/prefunding/{partnerId}/credit-limit}, IR-pf-2). Same harness as
 * {@code RestNotificationWebhookClientTest}.
 */
class RestPrefundingCreditLimitClientTest {

    /** Test fixture, not a credential — never read outside the test source set. */
    private static final String INTERNAL_TOKEN = "fixture-token-not-a-deployment-secret";

    private MockRestServiceServer server;
    private RestPrefundingCreditLimitClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://prefunding:8080");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestPrefundingCreditLimitClient(builder.build());
    }

    @Test
    @DisplayName("pushCreditLimit: PUTs the caps JSON to the partner-keyed path")
    void pushCreditLimit_happyPath() {
        server.expect(requestTo(
                        "http://prefunding:8080/internal/v1/prefunding/GMEREMIT/credit-limit"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.creditLimitUsd").value(100000.0))
                .andExpect(jsonPath("$.dailyCapUsd").value(5000.0))
                .andExpect(jsonPath("$.dailyTxnCountLimit").value(50))
                .andRespond(withSuccess());

        client.pushCreditLimit("GMEREMIT", new CreditLimitPushCommand(
                new BigDecimal("100000.0000"),
                new BigDecimal("5000.0000"),
                new BigDecimal("50000.0000"),
                new BigDecimal("500000.0000"),
                50));

        server.verify();
    }

    @Test
    @DisplayName("pushCreditLimit: null caps are omitted from the body (NON_NULL)")
    void pushCreditLimit_nullCapsOmitted() {
        server.expect(requestTo(
                        "http://prefunding:8080/internal/v1/prefunding/GMEREMIT/credit-limit"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.creditLimitUsd").value(250000.0))
                .andExpect(jsonPath("$.dailyCapUsd").doesNotExist())
                .andExpect(jsonPath("$.dailyTxnCountLimit").doesNotExist())
                .andRespond(withSuccess());

        client.pushCreditLimit("GMEREMIT", new CreditLimitPushCommand(
                new BigDecimal("250000.0000"), null, null, null, null));

        server.verify();
    }

    @Test
    @DisplayName("pushCreditLimit: upstream 400 re-thrown with status + body preserved")
    void pushCreditLimit_badRequestSurfacesVerbatim() {
        server.expect(requestTo(
                        "http://prefunding:8080/internal/v1/prefunding/GMEREMIT/credit-limit"))
                .andRespond(withBadRequest().body("unknown partner GMEREMIT"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> client.pushCreditLimit("GMEREMIT", new CreditLimitPushCommand(
                        new BigDecimal("1"), null, null, null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("unknown partner GMEREMIT"));
        server.verify();
    }

    @Test
    @DisplayName("pushCreditLimit: upstream 5xx mapped to 502 Bad Gateway")
    void pushCreditLimit_serverErrorMapsTo502() {
        server.expect(requestTo(
                        "http://prefunding:8080/internal/v1/prefunding/GMEREMIT/credit-limit"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("boom"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> client.pushCreditLimit("GMEREMIT", new CreditLimitPushCommand(
                        new BigDecimal("1"), null, null, null, null)));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
        server.verify();
    }

    // ---- T0-5 / T0-2: prefunding's whole balance API is behind the internal-auth gate ----

    @Test
    @DisplayName("the configured internal token is presented on the wire (else prefunding 401s)")
    void internalTokenIsSentWhenConfigured() {
        // Bound to the SAME builder the production constructor uses, so this asserts the real
        // default-header wiring rather than a hand-built client.
        RestClient.Builder builder = RestPrefundingCreditLimitClient.builderFor(
                "http://prefunding:8080", INTERNAL_TOKEN);
        MockRestServiceServer gated = MockRestServiceServer.bindTo(builder).build();
        RestPrefundingCreditLimitClient gatedClient =
                new RestPrefundingCreditLimitClient(builder.build());

        gated.expect(requestTo(
                        "http://prefunding:8080/internal/v1/prefunding/GMEREMIT/credit-limit"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header(InternalAuthHeaders.INTERNAL_TOKEN, INTERNAL_TOKEN))
                .andRespond(withSuccess());

        gatedClient.pushCreditLimit("GMEREMIT", new CreditLimitPushCommand(
                new BigDecimal("1"), null, null, null, null));

        gated.verify();
    }

    @Test
    @DisplayName("a blank secret sends NO token — fail-closed (prefunding refuses), never a bypass")
    void blankSecretSendsNoToken() {
        RestClient.Builder builder =
                RestPrefundingCreditLimitClient.builderFor("http://prefunding:8080", "  ");
        MockRestServiceServer ungated = MockRestServiceServer.bindTo(builder).build();
        RestPrefundingCreditLimitClient blankClient =
                new RestPrefundingCreditLimitClient(builder.build());

        ungated.expect(requestTo(
                        "http://prefunding:8080/internal/v1/prefunding/GMEREMIT/credit-limit"))
                .andExpect(headerDoesNotExist(InternalAuthHeaders.INTERNAL_TOKEN))
                .andRespond(withSuccess());

        blankClient.pushCreditLimit("GMEREMIT", new CreditLimitPushCommand(
                new BigDecimal("1"), null, null, null, null));

        ungated.verify();
    }
}
