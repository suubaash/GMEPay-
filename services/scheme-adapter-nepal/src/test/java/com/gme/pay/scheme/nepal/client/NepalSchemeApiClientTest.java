package com.gme.pay.scheme.nepal.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.scheme.nepal.sign.StubNepalSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link MockRestServiceServer} unit tests for {@link NepalSchemeApiClient} — fakes sim-nepal-qr.
 * No Spring context.
 */
class NepalSchemeApiClientTest {

    private static final String BASE = "http://localhost:9103";

    private MockRestServiceServer server;
    private NepalSchemeApiClient client;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new NepalSchemeApiClient(builder.build(), new StubNepalSigner(mapper), mapper,
                "sim-token", "sim-key");
    }

    @Test
    @DisplayName("parse: returns merchant fields for a fonepay QR")
    void parse_happyPath() {
        server.expect(requestTo(BASE + "/qrscan-thirdparty/parse/"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"format\":\"EMVCo\",\"initMethod\":\"static\","
                        + "\"merchantInfoExtra\":\"fonepay.com\",\"merchantCategoryCode\":\"5411\","
                        + "\"trxCurrency\":\"NPR\",\"trxAmount\":null,"
                        + "\"merchantCountry\":\"NP\",\"merchantName\":\"SudanMerchant\","
                        + "\"merchantCity\":\"AathraiTriveni\"}",
                        MediaType.APPLICATION_JSON));

        NepalSchemeApiClient.ParseResponse resp = client.parse("00020101021126350011fonepay.com...");

        assertEquals("SudanMerchant", resp.merchantName());
        assertEquals("AathraiTriveni", resp.merchantCity());
        assertEquals("NPR", resp.trxCurrency());
        server.verify();
    }

    @Test
    @DisplayName("validate: Token header + returns network/merchant fields")
    void validate_happyPath() {
        server.expect(requestTo(BASE + "/api/qr/validate/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Token sim-token"))
                .andRespond(withSuccess(
                        "{\"network\":\"fonepay\",\"name\":\"SudanMerchant\","
                        + "\"merchant_id\":\"M123\",\"amount\":null,\"currency\":\"NPR\","
                        + "\"purpose\":\"Remittance\",\"extra\":{\"merchant_city\":\"Kathmandu\"}}",
                        MediaType.APPLICATION_JSON));

        NepalSchemeApiClient.ValidateResponse resp = client.validate("00020101...");

        assertEquals("fonepay", resp.network());
        assertEquals("M123", resp.merchant_id());
        server.verify();
    }

    @Test
    @DisplayName("pay: success returns idx + Key header + nonce header")
    void pay_success() {
        server.expect(requestTo(BASE + "/qrscan-thirdparty/pay/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Key sim-key"))
                .andRespond(withSuccess(
                        "{\"idx\":\"KHTXNABC123456789\",\"amount\":\"100000\","
                        + "\"type\":\"ScanandPay\",\"detail\":\"Transaction has been approved\"}",
                        MediaType.APPLICATION_JSON));

        NepalSchemeApiClient.PayResponse resp = client.pay(
                "00020101...", 100000L, "REF-1", "9800000000", "ServicePayment", "test");

        assertEquals("KHTXNABC123456789", resp.idx());
        assertEquals("100000", resp.amount());
        server.verify();
    }

    @Test
    @DisplayName("pay: duplicate reference (400) maps to IDEMPOTENCY_CONFLICT")
    void pay_duplicateReference_mapsToIdempotencyConflict() {
        server.expect(requestTo(BASE + "/qrscan-thirdparty/pay/"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"reference\":\"Duplicate reference.REF-1\","
                              + "\"error_key\":\"validation_error\"}"));

        ApiException ex = assertThrows(ApiException.class,
                () -> client.pay("qs", 100000L, "REF-1", null, "p", "r"));

        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, ex.errorCode());
        server.verify();
    }

    @Test
    @DisplayName("pay: nonce expired (400) maps to VALIDATION_ERROR")
    void pay_nonceExpired_mapsToValidationError() {
        server.expect(requestTo(BASE + "/qrscan-thirdparty/pay/"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"Nonce already expired.\",\"error_key\":\"validation_error\"}"));

        ApiException ex = assertThrows(ApiException.class,
                () -> client.pay("qs", 100000L, "REF-2", null, "p", "r"));

        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
        server.verify();
    }

    @Test
    @DisplayName("pay: khalti_error / Invalid QR (400) maps to VALIDATION_ERROR")
    void pay_khaltiError_mapsToValidationError() {
        server.expect(requestTo(BASE + "/qrscan-thirdparty/pay/"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"Payment failed.\",\"error_key\":\"khalti_error\"}"));

        ApiException ex = assertThrows(ApiException.class,
                () -> client.pay("qs", 100000L, "REF-3", null, "p", "r"));

        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
        server.verify();
    }

    @Test
    @DisplayName("pay: invalid key (401) maps to SCHEME_UNAVAILABLE")
    void pay_invalidKey_mapsToSchemeUnavailable() {
        server.expect(requestTo(BASE + "/qrscan-thirdparty/pay/"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"Invalid token.\"}"));

        ApiException ex = assertThrows(ApiException.class,
                () -> client.pay("qs", 100000L, "REF-4", null, "p", "r"));

        assertEquals(ErrorCode.SCHEME_UNAVAILABLE, ex.errorCode());
        server.verify();
    }

    @Test
    @DisplayName("status: APPROVED state parsed")
    void status_approved() {
        server.expect(requestTo(BASE + "/qrscan-thirdparty/status/"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"detail\":\"Transaction has been approved\",\"state\":\"APPROVED\"}",
                        MediaType.APPLICATION_JSON));

        NepalSchemeApiClient.StatusResponse resp = client.status("REF-1", null);

        assertEquals("APPROVED", resp.state());
        server.verify();
    }

    @Test
    @DisplayName("status: reference-not-found returns Error state (HTTP 200)")
    void status_notFound_errorState() {
        server.expect(requestTo(BASE + "/qrscan-thirdparty/status/"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"detail\":\"Transaction does not exist\",\"state\":\"Error\"}",
                        MediaType.APPLICATION_JSON));

        NepalSchemeApiClient.StatusResponse resp = client.status("REF-UNKNOWN", null);

        assertEquals("Error", resp.state());
        server.verify();
    }
}
