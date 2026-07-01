package com.gme.pay.payment.client.rest;

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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Dispatch tests for {@link SchemeClientRouter}: scheme=NEPAL reaches the Nepal
 * adapter base-url; scheme=ZEROPAY (and unknown) reaches the ZeroPay adapter.
 */
class SchemeClientRouterTest {

    private static final String ZEROPAY_BASE = "http://scheme-adapter-zeropay:8080";
    private static final String NEPAL_BASE = "http://localhost:18091";

    private MockRestServiceServer zeropayServer;
    private MockRestServiceServer nepalServer;
    private SchemeClientRouter router;

    @BeforeEach
    void setUp() {
        RestClient.Builder zpBuilder =
                RestClientSupport.withJavaTime(RestClient.builder().baseUrl(ZEROPAY_BASE));
        zeropayServer = MockRestServiceServer.bindTo(zpBuilder).build();

        RestClient.Builder npBuilder =
                RestClientSupport.withJavaTime(RestClient.builder().baseUrl(NEPAL_BASE));
        nepalServer = MockRestServiceServer.bindTo(npBuilder).build();

        router = new SchemeClientRouter(
                new RestSchemeClient(zpBuilder.build()),
                new NepalRestSchemeClient(npBuilder.build()));
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
