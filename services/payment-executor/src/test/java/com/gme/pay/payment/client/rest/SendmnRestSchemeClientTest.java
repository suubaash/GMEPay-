package com.gme.pay.payment.client.rest;

import com.gme.pay.payment.domain.PaymentException;
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

/**
 * {@link MockRestServiceServer} tests for {@link SendmnRestSchemeClient} (two-step
 * verify-qr + submit-mpm folded into one {@code submitMpm}, ADR-016 lookupStatus policy).
 */
class SendmnRestSchemeClientTest {

    private static final String BASE = "http://localhost:8093";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private SendmnRestSchemeClient client;

    @BeforeEach
    void setUp() {
        builder = RestClientSupport.withJavaTime(RestClient.builder().baseUrl(BASE));
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SendmnRestSchemeClient(builder.build());
    }

    private void expectVerify(String txTokenNo) {
        server.expect(requestTo(BASE + "/internal/scheme/sendmn/verify-qr"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.qrPayload").value("QR_MN_1"))
                // Our stable reference rides along so the adapter persists it BEFORE
                // Confirm — the durable leg of the ADR-016 by-reference probe.
                .andExpect(jsonPath("$.reference").isNotEmpty())
                .andRespond(withSuccess(
                        "{\"txTokenNo\":\"" + txTokenNo + "\",\"merchantId\":\"GUID-9\","
                                + "\"merchantName\":\"UB Mart\",\"qrType\":\"11\","
                                + "\"localAmountMnt\":null,\"currency\":\"MNT\"}",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("submitMpm: verify-qr mints the token, submit-mpm carries token + MNT(2dp) amount")
    void submitMpm_twoStep_mapsResponse() {
        expectVerify("SMN-TOKEN-1");
        server.expect(requestTo(BASE + "/internal/scheme/sendmn/submit-mpm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.txTokenNo").value("SMN-TOKEN-1"))
                .andExpect(jsonPath("$.localAmountMnt").value("34300.00")) // MNT Decimal(18,2)
                .andRespond(withSuccess(
                        "{\"txTokenNo\":\"SMN-TOKEN-1\",\"status\":\"APPROVED\","
                                + "\"paymentNo\":\"PN-1\",\"paymentReceiptNo\":\"RC-1\","
                                + "\"fxUsdBuyRate\":\"3450.00\",\"settlementAmountUsd\":\"9.9420\"}",
                        MediaType.APPLICATION_JSON));

        SchemeClient.MpmSubmitResponse resp = client.submitMpm(
                new SchemeClient.MpmSubmitRequest(
                        "ref-1", "M001", new BigDecimal("34300"), "MNT", "SENDMN", "QR_MN_1"));

        assertEquals("APPROVED", resp.schemeApprovalCode());
        assertEquals("PN-1", resp.schemeTxnRef());
        server.verify();
    }

    @Test
    @DisplayName("submitMpm without a qrPayload is rejected before any HTTP call")
    void submitMpm_requiresQrPayload() {
        assertThrows(PaymentException.class, () -> client.submitMpm(
                SchemeClient.MpmSubmitRequest.of(
                        "ref-x", "M", new BigDecimal("10"), "MNT", "SENDMN")));
        server.verify(); // nothing was called
    }

    @Test
    @DisplayName("submit-mpm 400 (adapter VALIDATION_ERROR) maps to SchemeDeclinedException")
    void submit_declined() {
        expectVerify("SMN-TOKEN-2");
        server.expect(requestTo(BASE + "/internal/scheme/sendmn/submit-mpm"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"VALIDATION_ERROR\","
                                + "\"message\":\"sendmn Confirm rejected (RES_CODE=303)\","
                                + "\"retryable\":false}"));

        SchemeDeclinedException ex = assertThrows(SchemeDeclinedException.class,
                () -> client.submitMpm(new SchemeClient.MpmSubmitRequest(
                        "ref-2", "M", new BigDecimal("100"), "MNT", "SENDMN", "QR_MN_1")));
        assertEquals("VALIDATION_ERROR", ex.schemeErrorCode());
        server.verify();
    }

    @Test
    @DisplayName("adapter 503 maps to SchemeTimeoutException")
    void submit_timeout() {
        expectVerify("SMN-TOKEN-3");
        server.expect(requestTo(BASE + "/internal/scheme/sendmn/submit-mpm"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body(""));

        assertThrows(SchemeTimeoutException.class,
                () -> client.submitMpm(new SchemeClient.MpmSubmitRequest(
                        "ref-3", "M", new BigDecimal("100"), "MNT", "SENDMN", "QR_MN_1")));
        server.verify();
    }

    @Test
    @DisplayName("lookupStatus: remembered reference resolves the txTokenNo; APPROVED maps through")
    void lookupStatus_approvedViaRememberedToken() {
        expectVerify("SMN-TOKEN-4");
        server.expect(requestTo(BASE + "/internal/scheme/sendmn/submit-mpm"))
                .andRespond(withSuccess(
                        "{\"txTokenNo\":\"SMN-TOKEN-4\",\"status\":\"UNKNOWN\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/internal/scheme/sendmn/status/SMN-TOKEN-4"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"txTokenNo\":\"SMN-TOKEN-4\",\"status\":\"APPROVED\",\"paymentNo\":\"PN-4\"}",
                        MediaType.APPLICATION_JSON));

        client.submitMpm(new SchemeClient.MpmSubmitRequest(
                "ref-4", "M", new BigDecimal("100"), "MNT", "SENDMN", "QR_MN_1"));

        assertEquals(SchemeClient.LookupStatus.APPROVED, client.lookupStatus("SENDMN", "ref-4"));
        server.verify();
    }

    @Test
    @DisplayName("lookupStatus: UNKNOWN scheme status holds at PENDING (never NOT_FOUND — ADR-016)")
    void lookupStatus_unknownHoldsPending() {
        expectVerify("SMN-TOKEN-5");
        server.expect(requestTo(BASE + "/internal/scheme/sendmn/submit-mpm"))
                .andRespond(withSuccess(
                        "{\"txTokenNo\":\"SMN-TOKEN-5\",\"status\":\"UNKNOWN\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/internal/scheme/sendmn/status/SMN-TOKEN-5"))
                .andRespond(withSuccess(
                        "{\"txTokenNo\":\"SMN-TOKEN-5\",\"status\":\"UNKNOWN\"}",
                        MediaType.APPLICATION_JSON));

        client.submitMpm(new SchemeClient.MpmSubmitRequest(
                "ref-5", "M", new BigDecimal("100"), "MNT", "SENDMN", "QR_MN_1"));

        assertEquals(SchemeClient.LookupStatus.PENDING, client.lookupStatus("SENDMN", "ref-5"));
        server.verify();
    }

    @Test
    @DisplayName("lookupStatus: map miss + adapter 404 (never saw the reference) → NOT_FOUND")
    void lookupStatus_unknownReferenceNotFound() {
        // Map miss no longer concludes NOT_FOUND on its own — the durable by-reference
        // probe is consulted; only the adapter's authoritative 404 allows fail-over.
        server.expect(requestTo(BASE + "/internal/scheme/sendmn/status/by-reference/never-submitted-here"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"PAYMENT_NOT_FOUND\","
                                + "\"message\":\"status: unknown reference never-submitted-here\","
                                + "\"retryable\":false}"));

        assertEquals(SchemeClient.LookupStatus.NOT_FOUND,
                client.lookupStatus("SENDMN", "never-submitted-here"));
        server.verify();
    }

    @Test
    @DisplayName("lookupStatus: map miss (restart) falls back to by-reference and maps the status")
    void lookupStatus_fallsBackToByReferenceAfterRestart() {
        // Fresh client instance = restarted process: the in-memory reference→token map is
        // empty, but the adapter persisted the reference at verify time.
        server.expect(requestTo(BASE + "/internal/scheme/sendmn/status/by-reference/ref-restart"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"txTokenNo\":\"SMN-TOKEN-R\",\"status\":\"APPROVED\",\"paymentNo\":\"PN-R\"}",
                        MediaType.APPLICATION_JSON));

        assertEquals(SchemeClient.LookupStatus.APPROVED,
                client.lookupStatus("SENDMN", "ref-restart"));
        server.verify();
    }

    @Test
    @DisplayName("lookupStatus: by-reference probe transport failure holds PENDING (never NOT_FOUND — ADR-016)")
    void lookupStatus_byReferenceFailureHoldsPending() {
        // A 5xx from the probe is NOT proof of absence: the Confirm may have landed before
        // the restart. Falling over here could double-charge, so hold at PENDING.
        server.expect(requestTo(BASE + "/internal/scheme/sendmn/status/by-reference/ref-flaky"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body(""));

        assertEquals(SchemeClient.LookupStatus.PENDING,
                client.lookupStatus("SENDMN", "ref-flaky"));
        server.verify();
    }

    @Test
    @DisplayName("submitCpm / cancelPayment are unsupported for SENDMN")
    void unsupportedOperations() {
        assertThrows(PaymentException.class, () -> client.submitCpm(
                new SchemeClient.CpmSubmitRequest("t", "TOKEN", new BigDecimal("10"), "MNT", "SENDMN")));
        assertThrows(PaymentException.class, () -> client.cancelPayment("ref", "why"));
    }
}
