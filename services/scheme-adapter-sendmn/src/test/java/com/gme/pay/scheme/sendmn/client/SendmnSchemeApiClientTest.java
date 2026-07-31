package com.gme.pay.scheme.sendmn.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.scheme.sendmn.crypto.PlainJsonEnvelopeCodec;
import com.gme.pay.scheme.sendmn.crypto.SendmnEnvelopeCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link MockRestServiceServer} unit tests for {@link SendmnSchemeApiClient} — fakes the
 * SendMN partner API (envelope wrapping, auth headers, token caching + S104 re-auth,
 * business RES_CODEs returned as data). No Spring context.
 */
class SendmnSchemeApiClientTest {

    private static final String BASE = "http://localhost:9106";
    private static final String TOKEN_1 = "tok-one";
    private static final String TOKEN_2 = "tok-two";

    private final ObjectMapper mapper = new ObjectMapper();
    private final SendmnEnvelopeCodec codec = new PlainJsonEnvelopeCodec();

    private MockRestServiceServer server;
    private SendmnSchemeApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        SendmnAuthClient authClient = new SendmnAuthClient(
                restClient, "gme-user", "GME001", "secret-key", 80, Clock.systemUTC());
        client = new SendmnSchemeApiClient(restClient, authClient, codec, mapper);
    }

    // ------------------------------------------------------------------ helpers

    private String authBody(String token) {
        return "{\"code\":0,\"message\":\"success\",\"detail\":{\"token\":\"" + token
                + "\",\"note\":\"Token will only be Valid for 90 minutes.\",\"processId\":\"guid\"}}";
    }

    /** Wraps a plaintext JSON body in the {"encryptedData": base64(json)} envelope. */
    private String env(String json) {
        return "{\"encryptedData\":\"" + Base64.getEncoder()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8)) + "\"}";
    }

    private void expectAuth(String token) {
        server.expect(requestTo(BASE + "/api/Authentication"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Username", "gme-user"))
                .andExpect(header("AgentCode", "GME001"))
                .andExpect(header("AuthKey", "secret-key"))
                .andRespond(withSuccess(authBody(token), MediaType.APPLICATION_JSON));
    }

    private SendmnSchemeApiClient.ConfirmCommand confirmCommand() {
        return new SendmnSchemeApiClient.ConfirmCommand(
                "SMN20261027041530ABCDEF", "merchant-guid", "MNT",
                new BigDecimal("10000.00"), "USD", new BigDecimal("3373.000000"),
                "TICKER-1", "USD", new BigDecimal("2.9647"), "20261027041530");
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("verifyQr: authenticates first, sends enveloped body + token headers, unwraps response")
    void verifyQr_happyPath() {
        expectAuth(TOKEN_1);
        server.expect(requestTo(BASE + "/api/Partner/VerifyQr"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", TOKEN_1)) // raw token, no Bearer prefix
                .andExpect(header("Username", "gme-user"))
                .andExpect(header("AgentCode", "GME001"))
                .andExpect(request -> {
                    // Body must be the single-field envelope whose value decodes to our payload.
                    JsonNode root = mapper.readTree(
                            ((MockClientHttpRequest) request).getBodyAsString());
                    assertTrue(root.hasNonNull("encryptedData"), "body must be enveloped");
                    JsonNode payload = mapper.readTree(codec.decrypt(root.get("encryptedData").asText()));
                    assertEquals("qr-payload", payload.get("QR_CODE").asText());
                    assertEquals("SMN-TOKEN-1", payload.get("TX_TOKEN_NO").asText());
                })
                .andRespond(withSuccess(env(
                        "{\"RES_CODE\":\"0\",\"RES_MSG\":\"success\",\"QR_TYPE\":\"11\","
                        + "\"MERCHANT_ID\":\"a1b2c3\",\"MERCHANT_NAME\":\"UB Store\","
                        + "\"TX_TOKEN_NO\":\"SMN-TOKEN-1\",\"LOCAL_PAYMENT_AMOUNT\":\"25000.00\"}"),
                        MediaType.APPLICATION_JSON));

        SendmnSchemeApiClient.VerifyQrApiResponse resp = client.verifyQr("qr-payload", "SMN-TOKEN-1");

        assertEquals("0", resp.resCode());
        assertEquals("11", resp.qrType());
        assertEquals("a1b2c3", resp.merchantId());
        assertEquals("UB Store", resp.merchantName());
        assertEquals("25000.00", resp.localPaymentAmount());
        server.verify();
    }

    @Test
    @DisplayName("token is cached: two business calls, one Authentication call")
    void tokenCached() {
        expectAuth(TOKEN_1);
        server.expect(ExpectedCount.twice(), requestTo(BASE + "/api/Partner/PaymentStatus"))
                .andExpect(header("Authorization", TOKEN_1))
                .andRespond(withSuccess(env(
                        "{\"RES_CODE\":\"0\",\"TX_TOKEN_NO\":\"T\",\"PAYMENT_STATUS\":\"Processing\"}"),
                        MediaType.APPLICATION_JSON));

        client.paymentStatus("T");
        client.paymentStatus("T");

        server.verify(); // exactly one auth call expected
    }

    @Test
    @DisplayName("S104 token-expired answer → invalidate, re-authenticate, replay once")
    void tokenExpired_reauthAndReplay() {
        expectAuth(TOKEN_1);
        server.expect(requestTo(BASE + "/api/Partner/PaymentStatus"))
                .andExpect(header("Authorization", TOKEN_1))
                .andRespond(withSuccess(env(
                        "{\"RES_CODE\":\"S104\",\"RES_MSG\":\"Token is expired\"}"),
                        MediaType.APPLICATION_JSON));
        expectAuth(TOKEN_2);
        server.expect(requestTo(BASE + "/api/Partner/PaymentStatus"))
                .andExpect(header("Authorization", TOKEN_2))
                .andRespond(withSuccess(env(
                        "{\"RES_CODE\":\"0\",\"TX_TOKEN_NO\":\"T\",\"PAYMENT_STATUS\":\"Approved\"}"),
                        MediaType.APPLICATION_JSON));

        SendmnSchemeApiClient.PaymentStatusApiResponse resp = client.paymentStatus("T");

        assertEquals("0", resp.resCode());
        assertEquals("Approved", resp.paymentStatus());
        server.verify();
    }

    @Test
    @DisplayName("confirm: duplicate TX_TOKEN_NO (304) is returned as data, not an exception")
    void confirm_duplicate304_returnedAsData() {
        expectAuth(TOKEN_1);
        server.expect(requestTo(BASE + "/api/Partner/Confirm"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(env(
                        "{\"RES_CODE\":\"304\",\"RES_MSG\":\"TX_TOKEN_NO is duplicated!\"}"),
                        MediaType.APPLICATION_JSON));

        SendmnSchemeApiClient.ConfirmApiResponse resp = client.confirm(confirmCommand());

        assertEquals("304", resp.resCode());
        server.verify();
    }

    @Test
    @DisplayName("confirm: sends both FX_CUR_CODE and FX_CUR_CD plus the registered FX_TICKER_NO")
    void confirm_fieldNameDriftDefense() {
        expectAuth(TOKEN_1);
        server.expect(requestTo(BASE + "/api/Partner/Confirm"))
                .andExpect(request -> {
                    JsonNode root = mapper.readTree(
                            ((MockClientHttpRequest) request).getBodyAsString());
                    JsonNode payload = mapper.readTree(codec.decrypt(root.get("encryptedData").asText()));
                    assertEquals("USD", payload.get("FX_CUR_CODE").asText());
                    assertEquals("USD", payload.get("FX_CUR_CD").asText());
                    assertEquals("TICKER-1", payload.get("FX_TICKER_NO").asText());
                    assertEquals("10000.00", payload.get("LOCAL_PAYMENT_AMOUNT").asText());
                    assertEquals("2.9647", payload.get("SETTLEMENT_AMOUNT").asText());
                    assertEquals("3373.000000", payload.get("FX_USD_BUY_RATE").asText());
                    assertEquals("", payload.get("FX_USD_SELL_RATE").asText());
                })
                .andRespond(withSuccess(env(
                        "{\"RES_CODE\":\"0\",\"PAYMENT_NO\":\"PN-1\",\"PAYMENT_RECIPT_NO\":\"GME1453767113\"}"),
                        MediaType.APPLICATION_JSON));

        SendmnSchemeApiClient.ConfirmApiResponse resp = client.confirm(confirmCommand());

        assertEquals("0", resp.resCode());
        assertEquals("PN-1", resp.paymentNo());
        assertEquals("GME1453767113", resp.paymentReceiptNo());
        server.verify();
    }

    @Test
    @DisplayName("transport 5xx maps to SCHEME_UNAVAILABLE (ambiguous — adapter must poll)")
    void serverError_mapsToSchemeUnavailable() {
        expectAuth(TOKEN_1);
        server.expect(requestTo(BASE + "/api/Partner/Confirm"))
                .andRespond(withServerError());

        ApiException ex = assertThrows(ApiException.class, () -> client.confirm(confirmCommand()));

        assertEquals(ErrorCode.SCHEME_UNAVAILABLE, ex.errorCode());
        server.verify();
    }

    @Test
    @DisplayName("authentication rejection (nonzero code) maps to SCHEME_UNAVAILABLE")
    void authRejected() {
        server.expect(requestTo(BASE + "/api/Authentication"))
                .andRespond(withSuccess(
                        "{\"code\":\"S101\",\"message\":\"AuthKey is invalid\"}",
                        MediaType.APPLICATION_JSON));

        ApiException ex = assertThrows(ApiException.class, () -> client.paymentStatus("T"));

        assertEquals(ErrorCode.SCHEME_UNAVAILABLE, ex.errorCode());
        server.verify();
    }

    @Test
    @DisplayName("HTTP 401 (e.g. S216 IP not whitelisted) maps to SCHEME_UNAVAILABLE")
    void unauthorized_mapsToSchemeUnavailable() {
        expectAuth(TOKEN_1);
        server.expect(requestTo(BASE + "/api/Partner/VerifyQr"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"S216\",\"message\":\"Invalid credentials, access denied!\"}"));

        ApiException ex = assertThrows(ApiException.class, () -> client.verifyQr("qr", "T"));

        assertEquals(ErrorCode.SCHEME_UNAVAILABLE, ex.errorCode());
        server.verify();
    }
}
