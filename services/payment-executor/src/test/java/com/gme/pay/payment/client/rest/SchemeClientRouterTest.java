package com.gme.pay.payment.client.rest;

import com.gme.pay.payment.domain.SchemeOperationNotSupportedException;
import com.gme.pay.payment.domain.client.SchemeClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Dispatch tests for {@link SchemeClientRouter}: scheme=NEPAL reaches the Nepal
 * adapter base-url; scheme=SENDMN reaches the SendMN adapter base-url;
 * scheme=ZEROPAY (and unknown) reaches the ZeroPay adapter.
 */
class SchemeClientRouterTest {

    private static final String ZEROPAY_BASE = "http://scheme-adapter-zeropay:8080";
    private static final String NEPAL_BASE = "http://localhost:18091";
    private static final String SENDMN_BASE = "http://localhost:8093";

    private MockRestServiceServer zeropayServer;
    private MockRestServiceServer nepalServer;
    private MockRestServiceServer sendmnServer;
    private SchemeClientRouter router;

    @BeforeEach
    void setUp() {
        RestClient.Builder zpBuilder =
                RestClientSupport.withJavaTime(RestClient.builder().baseUrl(ZEROPAY_BASE));
        zeropayServer = MockRestServiceServer.bindTo(zpBuilder).build();

        RestClient.Builder npBuilder =
                RestClientSupport.withJavaTime(RestClient.builder().baseUrl(NEPAL_BASE));
        nepalServer = MockRestServiceServer.bindTo(npBuilder).build();

        RestClient.Builder smnBuilder =
                RestClientSupport.withJavaTime(RestClient.builder().baseUrl(SENDMN_BASE));
        sendmnServer = MockRestServiceServer.bindTo(smnBuilder).build();

        router = new SchemeClientRouter(
                new RestSchemeClient(zpBuilder.build()),
                new NepalRestSchemeClient(npBuilder.build()),
                new SendmnRestSchemeClient(smnBuilder.build()));
    }

    @Test
    @DisplayName("scheme=NEPAL routes to the Nepal adapter and maps the response")
    void nepalRoutesToNepalAdapter() {
        nepalServer.expect(requestTo(NEPAL_BASE + "/internal/scheme/nepal/submit"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"schemeTxnRef\":\"NP-777\",\"status\":\"SUCCESS\",\"amountPaisa\":200000}",
                        MediaType.APPLICATION_JSON));

        SchemeClient.MpmSubmitResponse resp = router.submitMpm(
                new SchemeClient.MpmSubmitRequest(
                        "txn_np", "M", new BigDecimal("2000"), "NPR", "NEPAL", "QR"));

        assertEquals("NP-777", resp.schemeTxnRef());
        assertEquals("SUCCESS", resp.schemeApprovalCode());
        nepalServer.verify();
    }

    @Test
    @DisplayName("scheme=SENDMN routes to the SendMN adapter (verify-qr then submit-mpm)")
    void sendmnRoutesToSendmnAdapter() {
        sendmnServer.expect(requestTo(SENDMN_BASE + "/internal/scheme/sendmn/verify-qr"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"txTokenNo\":\"SMN20260727ABCDEF\",\"merchantId\":\"GUID-1\","
                                + "\"merchantName\":\"UB Mart\",\"localAmountMnt\":null,\"currency\":\"MNT\"}",
                        MediaType.APPLICATION_JSON));
        sendmnServer.expect(requestTo(SENDMN_BASE + "/internal/scheme/sendmn/submit-mpm"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"txTokenNo\":\"SMN20260727ABCDEF\",\"status\":\"APPROVED\","
                                + "\"paymentNo\":\"PN-42\",\"paymentReceiptNo\":\"RC-42\","
                                + "\"fxUsdBuyRate\":\"3450.00\",\"settlementAmountUsd\":\"9.9420\"}",
                        MediaType.APPLICATION_JSON));

        SchemeClient.MpmSubmitResponse resp = router.submitMpm(
                new SchemeClient.MpmSubmitRequest(
                        "txn_smn", "M", new BigDecimal("34300"), "MNT", "SENDMN", "QR_MN"));

        assertEquals("PN-42", resp.schemeTxnRef());
        assertEquals("APPROVED", resp.schemeApprovalCode());
        sendmnServer.verify();
    }

    @Test
    @DisplayName("scheme=sendmn (lower case) also routes to the SendMN adapter")
    void sendmnLowerCaseRoutes() {
        sendmnServer.expect(requestTo(SENDMN_BASE + "/internal/scheme/sendmn/verify-qr"))
                .andRespond(withSuccess(
                        "{\"txTokenNo\":\"SMN20260727GHIJKL\",\"currency\":\"MNT\"}",
                        MediaType.APPLICATION_JSON));
        sendmnServer.expect(requestTo(SENDMN_BASE + "/internal/scheme/sendmn/submit-mpm"))
                .andRespond(withSuccess(
                        "{\"txTokenNo\":\"SMN20260727GHIJKL\",\"status\":\"APPROVED\"}",
                        MediaType.APPLICATION_JSON));

        SchemeClient.MpmSubmitResponse resp = router.submitMpm(
                new SchemeClient.MpmSubmitRequest(
                        "txn_smn2", "M", new BigDecimal("100.50"), "MNT", "sendmn", "QR_MN"));

        // No paymentNo yet → schemeTxnRef falls back to the txTokenNo.
        assertEquals("SMN20260727GHIJKL", resp.schemeTxnRef());
        sendmnServer.verify();
    }

    @Test
    @DisplayName("scheme=ZEROPAY still routes to the ZeroPay adapter, unchanged")
    void zeropayRoutesToZeropayAdapter() {
        zeropayServer.expect(requestTo(ZEROPAY_BASE + "/internal/scheme/zeropay/submit"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"schemeApprovalCode\":\"ZP_OK\",\"schemeTxnRef\":\"ZP-1\","
                                + "\"approvedAt\":\"2026-06-08T09:31:15Z\"}",
                        MediaType.APPLICATION_JSON));

        SchemeClient.MpmSubmitResponse resp = router.submitMpm(
                SchemeClient.MpmSubmitRequest.of(
                        "txn_zp", "M001", new BigDecimal("50000"), "KRW", "ZEROPAY"));

        assertEquals("ZP-1", resp.schemeTxnRef());
        assertEquals("ZP_OK", resp.schemeApprovalCode());
        zeropayServer.verify();
    }

    // =========================================================================
    // T2-7: cancel/refund routing. cancelPayment used to carry no scheme code, so a
    // Nepal/SendMN cancel unconditionally hit /internal/scheme/zeropay/cancel and came
    // back as a ZeroPay decline. It now routes by scheme code like submitMpm.
    // =========================================================================

    @Test
    @DisplayName("T2-7: SENDMN cancel does NOT reach ZeroPay — it surfaces SCHEME_OPERATION_UNSUPPORTED")
    void sendmnCancelDoesNotHitZeropay() {
        SchemeOperationNotSupportedException ex = assertThrows(
                SchemeOperationNotSupportedException.class,
                () -> router.cancelPayment(new SchemeClient.CancelRequest(
                        "SMN-PAYMENT-1", "CUSTOMER_REQUEST", "SENDMN")));

        assertEquals("SCHEME_OPERATION_UNSUPPORTED", ex.code());
        assertEquals("SENDMN", ex.schemeId());
        assertEquals("cancelPayment", ex.operation());
        // No HTTP call to ANY adapter: the ZeroPay server has no expectations, so a stray
        // /internal/scheme/zeropay/cancel would have failed the request; verify() confirms silence.
        zeropayServer.verify();
        sendmnServer.verify();
    }

    @Test
    @DisplayName("T2-7: NEPAL cancel does NOT reach ZeroPay — it surfaces SCHEME_OPERATION_UNSUPPORTED")
    void nepalCancelDoesNotHitZeropay() {
        SchemeOperationNotSupportedException ex = assertThrows(
                SchemeOperationNotSupportedException.class,
                () -> router.cancelPayment(new SchemeClient.CancelRequest(
                        "NP-777", "CUSTOMER_REQUEST", "nepal")));

        assertEquals("SCHEME_OPERATION_UNSUPPORTED", ex.code());
        assertEquals("NEPAL", ex.schemeId());
        zeropayServer.verify();
        nepalServer.verify();
    }

    @Test
    @DisplayName("T2-7: ZEROPAY cancel still posts to /internal/scheme/zeropay/cancel, unchanged")
    void zeropayCancelStillWorks() {
        zeropayServer.expect(requestTo(ZEROPAY_BASE + "/internal/scheme/zeropay/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        router.cancelPayment(new SchemeClient.CancelRequest("ZP-1", "PARTNER_INITIATED", "ZEROPAY"));

        zeropayServer.verify();
    }

    @Test
    @DisplayName("T2-7: a scheme-less cancel keeps the legacy ZeroPay default routing")
    void schemelessCancelFallsBackToZeropay() {
        zeropayServer.expect(requestTo(ZEROPAY_BASE + "/internal/scheme/zeropay/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        router.cancelPayment(SchemeClient.CancelRequest.of("ZP-2", "PARTNER_INITIATED"));

        zeropayServer.verify();
    }

    @Test
    @DisplayName("unknown/null scheme falls back to the ZeroPay default adapter")
    void unknownSchemeFallsBackToDefault() {
        zeropayServer.expect(requestTo(ZEROPAY_BASE + "/internal/scheme/zeropay/submit"))
                .andRespond(withSuccess(
                        "{\"schemeApprovalCode\":\"ZP_OK\",\"schemeTxnRef\":\"ZP-2\","
                                + "\"approvedAt\":\"2026-06-08T09:31:15Z\"}",
                        MediaType.APPLICATION_JSON));

        SchemeClient.MpmSubmitResponse resp = router.submitMpm(
                SchemeClient.MpmSubmitRequest.of(
                        "txn_x", "M001", new BigDecimal("50000"), "KRW", null));

        assertEquals("ZP-2", resp.schemeTxnRef());
        zeropayServer.verify();
    }
}
