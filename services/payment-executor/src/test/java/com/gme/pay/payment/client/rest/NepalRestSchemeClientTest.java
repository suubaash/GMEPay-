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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** {@link MockRestServiceServer} tests for {@link NepalRestSchemeClient} (single-phase submit). */
class NepalRestSchemeClientTest {

    private static final String BASE = "http://localhost:18091";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private NepalRestSchemeClient client;

    @BeforeEach
    void setUp() {
        builder = RestClientSupport.withJavaTime(RestClient.builder().baseUrl(BASE));
        server = MockRestServiceServer.bindTo(builder).build();
        client = new NepalRestSchemeClient(builder.build());
    }

    @Test
    @DisplayName("submitMpm: hits the Nepal submit endpoint and maps schemeTxnRef/status")
    void submitMpm_mapsResponse() {
        server.expect(requestTo(BASE + "/internal/scheme/nepal/submit"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.qs").value("QR_NP_1"))
                .andExpect(jsonPath("$.amountPaisa").value(500000))   // 5000.00 NPR -> paisa
                .andExpect(jsonPath("$.reference").value("txn_np_1"))
                .andRespond(withSuccess(
                        "{\"schemeTxnRef\":\"NP-ABC-123\",\"status\":\"SUCCESS\",\"amountPaisa\":500000}",
                        MediaType.APPLICATION_JSON));

        SchemeClient.MpmSubmitResponse resp = client.submitMpm(
                new SchemeClient.MpmSubmitRequest(
                        "txn_np_1", "M_NP", new BigDecimal("5000"), "NPR", "NEPAL", "QR_NP_1"));

        assertEquals("NP-ABC-123", resp.schemeTxnRef());
        assertEquals("SUCCESS", resp.schemeApprovalCode());
        server.verify();
    }

    @Test
    @DisplayName("submitCpm: routes to the same single-phase submit endpoint")
    void submitCpm_usesSubmitEndpoint() {
        server.expect(requestTo(BASE + "/internal/scheme/nepal/submit"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"schemeTxnRef\":\"NP-CPM-9\",\"status\":\"SUCCESS\",\"amountPaisa\":100000}",
                        MediaType.APPLICATION_JSON));

        SchemeClient.CpmSubmitResponse resp = client.submitCpm(
                new SchemeClient.CpmSubmitRequest(
                        "txn_np_2", "CPM_TOKEN", new BigDecimal("1000"), "NPR", "NEPAL"));

        assertEquals("NP-CPM-9", resp.schemeTxnRef());
        server.verify();
    }

    @Test
    @DisplayName("submit: 422 maps to SchemeDeclinedException")
    void submit_declined() {
        server.expect(requestTo(BASE + "/internal/scheme/nepal/submit"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"NP_DECLINE\",\"message\":\"insufficient funds\"}"));

        SchemeDeclinedException ex = assertThrows(SchemeDeclinedException.class,
                () -> client.submitMpm(new SchemeClient.MpmSubmitRequest(
                        "t", "M", new BigDecimal("10"), "NPR", "NEPAL", "QR")));
        assertEquals("NP_DECLINE", ex.schemeErrorCode());
        server.verify();
    }

    @Test
    @DisplayName("submit: 503 maps to SchemeTimeoutException")
    void submit_timeout() {
        server.expect(requestTo(BASE + "/internal/scheme/nepal/submit"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body(""));

        assertThrows(SchemeTimeoutException.class,
                () -> client.submitMpm(new SchemeClient.MpmSubmitRequest(
                        "t", "M", new BigDecimal("10"), "NPR", "NEPAL", "QR")));
        server.verify();
    }

    @Test
    @DisplayName("lookupStatus: SUCCESS → APPROVED")
    void lookupStatus_approved() {
        server.expect(requestTo(BASE + "/internal/scheme/nepal/status?reference=ref-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"schemeTxnRef\":\"NP-1\",\"status\":\"SUCCESS\",\"reference\":\"ref-1\"}",
                        MediaType.APPLICATION_JSON));

        assertEquals(SchemeClient.LookupStatus.APPROVED, client.lookupStatus("NEPAL", "ref-1"));
        server.verify();
    }

    @Test
    @DisplayName("lookupStatus: 404 → NOT_FOUND (safe to fail over)")
    void lookupStatus_notFound() {
        server.expect(requestTo(BASE + "/internal/scheme/nepal/status?reference=ref-x"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body(""));

        assertEquals(SchemeClient.LookupStatus.NOT_FOUND, client.lookupStatus("NEPAL", "ref-x"));
        server.verify();
    }
}
