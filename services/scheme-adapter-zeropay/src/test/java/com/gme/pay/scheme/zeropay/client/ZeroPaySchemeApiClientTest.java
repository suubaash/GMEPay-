package com.gme.pay.scheme.zeropay.client;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link MockRestServiceServer} unit tests for {@link ZeroPaySchemeApiClient}.
 * No Spring context — pure JUnit 5.
 */
class ZeroPaySchemeApiClientTest {

    private static final String BASE = "http://localhost:9102/v1/scheme";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private ZeroPaySchemeApiClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl(BASE);
        server  = MockRestServiceServer.bindTo(builder).build();
        client  = new ZeroPaySchemeApiClient(builder.build());
    }

    // -----------------------------------------------------------------------
    // authorize — happy path (MPM_STATIC)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("authorize: MPM_STATIC returns parsed AuthorizeResponse")
    void authorize_mpmStatic_happyPath() {
        server.expect(requestTo(BASE + "/payments/authorize"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"authId\":\"AUTH-AABBCC001122\","
                        + "\"status\":\"APPROVED\","
                        + "\"schemeRef\":\"ZEROPAY-AABB0011\","
                        + "\"merchantId\":\"M001\","
                        + "\"amount\":50000,"
                        + "\"currency\":\"KRW\","
                        + "\"asOf\":\"2026-06-13T10:00:00+09:00\"}",
                        MediaType.APPLICATION_JSON));

        ZeroPaySchemeApiClient.AuthorizeResponse resp = client.authorize(
                "MPM_STATIC", "QR_PAYLOAD_STATIC", null,
                new BigDecimal("50000"), "KRW", "txnRef-001");

        assertEquals("AUTH-AABBCC001122", resp.authId());
        assertEquals("APPROVED", resp.status());
        assertEquals("M001", resp.merchantId());
        server.verify();
    }

    // -----------------------------------------------------------------------
    // authorize + commit — MPM_DYNAMIC decode-then-authorize happy path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("commit: returns parsed CommitResponse with schemeTxnRef")
    void commit_happyPath() {
        server.expect(requestTo(BASE + "/payments/AUTH-AABBCC001122/commit"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"authId\":\"AUTH-AABBCC001122\","
                        + "\"status\":\"CAPTURED\","
                        + "\"schemeTxnRef\":\"TXN-AABBCC112233\","
                        + "\"committedAt\":\"2026-06-13T10:00:05+09:00\"}",
                        MediaType.APPLICATION_JSON));

        ZeroPaySchemeApiClient.CommitResponse resp = client.commit("AUTH-AABBCC001122");

        assertEquals("CAPTURED", resp.status());
        assertEquals("TXN-AABBCC112233", resp.schemeTxnRef());
        server.verify();
    }

    // -----------------------------------------------------------------------
    // decodeQr — dynamic QR returns embedded amount
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("decodeQr: dynamic QR returns mode=dynamic and non-null amount")
    void decodeQr_dynamicQr_returnsAmount() {
        server.expect(requestTo(BASE + "/qr/decode"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"merchantId\":\"M001\","
                        + "\"merchantName\":\"Test Merchant\","
                        + "\"mode\":\"dynamic\","
                        + "\"amount\":\"75000\","
                        + "\"currency\":\"KRW\"}",
                        MediaType.APPLICATION_JSON));

        ZeroPaySchemeApiClient.DecodeQrResponse resp = client.decodeQr("SOME_DYNAMIC_QR_PAYLOAD");

        assertEquals("M001", resp.merchantId());
        assertEquals("dynamic", resp.mode());
        assertEquals("75000", resp.amount());
        server.verify();
    }

    // -----------------------------------------------------------------------
    // decodeQr — static QR returns null amount
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("decodeQr: static QR returns mode=static and null amount")
    void decodeQr_staticQr_nullAmount() {
        server.expect(requestTo(BASE + "/qr/decode"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"merchantId\":\"M002\","
                        + "\"merchantName\":\"Static Merchant\","
                        + "\"mode\":\"static\","
                        + "\"amount\":null,"
                        + "\"currency\":\"KRW\"}",
                        MediaType.APPLICATION_JSON));

        ZeroPaySchemeApiClient.DecodeQrResponse resp = client.decodeQr("SOME_STATIC_QR_PAYLOAD");

        assertEquals("static", resp.mode());
        assertEquals(null, resp.amount());
        server.verify();
    }

    // -----------------------------------------------------------------------
    // authorize — 422 AMOUNT_MISMATCH -> VALIDATION_ERROR
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("authorize: 422 AMOUNT_MISMATCH maps to VALIDATION_ERROR ApiException")
    void authorize_422AmountMismatch_mapsToValidationError() {
        server.expect(requestTo(BASE + "/payments/authorize"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"AMOUNT_MISMATCH\","
                              + "\"message\":\"Request amount 99000 does not match QR amount 75000\"}"));

        ApiException ex = assertThrows(ApiException.class,
                () -> client.authorize("MPM_DYNAMIC", "DYNAMIC_QR", null,
                        new BigDecimal("99000"), "KRW", "txn-002"));

        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
        assertTrue(ex.getMessage().contains("422"));
        server.verify();
    }

    // -----------------------------------------------------------------------
    // authorize — sim-scheme down (503) -> SCHEME_UNAVAILABLE
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("authorize: 503 from sim-scheme maps to SCHEME_UNAVAILABLE ApiException")
    void authorize_503_mapsToSchemeUnavailable() {
        server.expect(requestTo(BASE + "/payments/authorize"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(""));

        ApiException ex = assertThrows(ApiException.class,
                () -> client.authorize("MPM_STATIC", "QR_PAYLOAD", null,
                        new BigDecimal("50000"), "KRW", "txn-003"));

        assertEquals(ErrorCode.SCHEME_UNAVAILABLE, ex.errorCode());
        server.verify();
    }

    // -----------------------------------------------------------------------
    // authorize — 404 MERCHANT_NOT_FOUND -> MERCHANT_NOT_FOUND
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("authorize: 404 maps to MERCHANT_NOT_FOUND ApiException")
    void authorize_404_mapsToMerchantNotFound() {
        server.expect(requestTo(BASE + "/payments/authorize"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"MERCHANT_NOT_FOUND\",\"message\":\"Merchant M999 not found\"}"));

        ApiException ex = assertThrows(ApiException.class,
                () -> client.authorize("MPM_STATIC", "UNKNOWN_QR", null,
                        new BigDecimal("50000"), "KRW", "txn-004"));

        assertEquals(ErrorCode.MERCHANT_NOT_FOUND, ex.errorCode());
        server.verify();
    }

    // -----------------------------------------------------------------------
    // fetchCpmToken — happy path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("fetchCpmToken: returns parsed CpmTokenResponse with cpmToken")
    void fetchCpmToken_happyPath() {
        server.expect(requestTo(BASE + "/cpm/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"mode\":\"CPM\","
                        + "\"cpmToken\":\"CPM-TOKEN-AABB1122\","
                        + "\"expiresAt\":\"2026-06-15T14:05:00+09:00\"}",
                        MediaType.APPLICATION_JSON));

        ZeroPaySchemeApiClient.CpmTokenResponse resp = client.fetchCpmToken("M001", "WALLET");

        assertEquals("CPM", resp.mode());
        assertEquals("CPM-TOKEN-AABB1122", resp.cpmToken());
        assertEquals("2026-06-15T14:05:00+09:00", resp.expiresAt());
        server.verify();
    }

    @Test
    @DisplayName("fetchCpmToken: 503 maps to SCHEME_UNAVAILABLE ApiException")
    void fetchCpmToken_503_mapsToSchemeUnavailable() {
        server.expect(requestTo(BASE + "/cpm/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body(""));

        ApiException ex = assertThrows(ApiException.class,
                () -> client.fetchCpmToken("M001", "WALLET"));

        assertEquals(ErrorCode.SCHEME_UNAVAILABLE, ex.errorCode());
        server.verify();
    }

    // -----------------------------------------------------------------------
    // refund — happy path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("refund: returns parsed RefundResponse with status REFUNDED")
    void refund_happyPath() {
        server.expect(requestTo(BASE + "/payments/AUTH-CPM-001/refund"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"refundId\":\"REFUND-AABB1122\","
                        + "\"status\":\"REFUNDED\"}",
                        MediaType.APPLICATION_JSON));

        ZeroPaySchemeApiClient.RefundResponse resp =
                client.refund("AUTH-CPM-001", new BigDecimal("50000"));

        assertEquals("REFUND-AABB1122", resp.refundId());
        assertEquals("REFUNDED", resp.status());
        server.verify();
    }

    @Test
    @DisplayName("refund: 404 maps to MERCHANT_NOT_FOUND (unknown authId)")
    void refund_404_mapsToMerchantNotFound() {
        server.expect(requestTo(BASE + "/payments/AUTH-UNKNOWN/refund"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"NOT_FOUND\",\"message\":\"Auth AUTH-UNKNOWN not found\"}"));

        ApiException ex = assertThrows(ApiException.class,
                () -> client.refund("AUTH-UNKNOWN", new BigDecimal("50000")));

        assertEquals(ErrorCode.MERCHANT_NOT_FOUND, ex.errorCode());
        server.verify();
    }
}
