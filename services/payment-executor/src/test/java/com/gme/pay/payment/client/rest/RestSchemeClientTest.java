package com.gme.pay.payment.client.rest;

import com.gme.pay.payment.domain.SchemeDeclinedException;
import com.gme.pay.payment.domain.SchemeTimeoutException;
import com.gme.pay.payment.domain.client.SchemeClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** {@link MockRestServiceServer} unit tests for {@link RestSchemeClient}. */
class RestSchemeClientTest {

    private static final String BASE = "http://scheme-adapter-zeropay:8080";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private RestSchemeClient client;

    @BeforeEach
    void setUp() {
        builder = RestClientSupport.withJavaTime(RestClient.builder().baseUrl(BASE));
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestSchemeClient(builder.build());
    }

    @Test
    @DisplayName("submitMpm: parses approval response")
    void submitMpm_happyPath() {
        server.expect(requestTo(BASE + "/internal/scheme/zeropay/submit"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"schemeApprovalCode\":\"ZP_OK_001\","
                                + "\"schemeTxnRef\":\"ZP20260608093115001234\","
                                + "\"approvedAt\":\"2026-06-08T09:31:15Z\"}",
                        MediaType.APPLICATION_JSON));

        SchemeClient.MpmSubmitResponse resp = client.submitMpm(
                new SchemeClient.MpmSubmitRequest(
                        "txn_001", "M001",
                        new BigDecimal("50000"), "KRW", "zeropay"));

        assertEquals("ZP_OK_001", resp.schemeApprovalCode());
        assertEquals("ZP20260608093115001234", resp.schemeTxnRef());
        server.verify();
    }

    @Test
    @DisplayName("submitMpm: 422 maps to SchemeDeclinedException with parsed code/message")
    void submitMpm_unprocessableMapsToDeclined() {
        server.expect(requestTo(BASE + "/internal/scheme/zeropay/submit"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"ZP_ERR_001\",\"message\":\"Declined by issuer\"}"));

        SchemeDeclinedException ex = assertThrows(SchemeDeclinedException.class,
                () -> client.submitMpm(new SchemeClient.MpmSubmitRequest(
                        "txn_001", "M001",
                        new BigDecimal("50000"), "KRW", "zeropay")));

        assertEquals("ZP_ERR_001", ex.schemeErrorCode());
        server.verify();
    }

    @Test
    @DisplayName("submitMpm: 503 maps to SchemeTimeoutException")
    void submitMpm_serviceUnavailableMapsToTimeout() {
        server.expect(requestTo(BASE + "/internal/scheme/zeropay/submit"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body(""));

        assertThrows(SchemeTimeoutException.class,
                () -> client.submitMpm(new SchemeClient.MpmSubmitRequest(
                        "txn_001", "M001",
                        new BigDecimal("50000"), "KRW", "zeropay")));
        server.verify();
    }

    @Test
    @DisplayName("cancelPayment: POSTs to /cancel endpoint")
    void cancelPayment_postsToCancelEndpoint() {
        server.expect(requestTo(BASE + "/internal/scheme/zeropay/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        client.cancelPayment("ZP20260608093115001234", "PARTNER_INITIATED");
        server.verify();
    }
}
