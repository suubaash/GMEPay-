package com.gme.pay.scheme.ninepay.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.scheme.ninepay.sign.NinepaySigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link MockRestServiceServer} unit tests for {@link NinepayApiClient} — fakes the 9Pay
 * API. No Spring context. Verifies the wire shape (signed pipe-strings, hl=en header),
 * envelope unwrapping, and the definitive-vs-ambiguous failure taxonomy.
 */
class NinepayApiClientTest {

    private static final String BASE = "http://localhost:9107";
    private static final String REQ = "GMEPAY9P202607270000000001";

    private MockRestServiceServer server;
    private NinepayApiClient client;
    private NinepaySigner signer;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        signer = new NinepaySigner(pair.getPrivate(), pair.getPublic(), "SHA256");

        ObjectMapper mapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new NinepayApiClient(builder.build(), signer, mapper, "GMEPAY", false);
    }

    private static NinepayApiClient.TransferCommand command() {
        return new NinepayApiClient.TransferCommand(REQ, "970418", "1023020330000", 0,
                "NGUYEN VAN A", 50_000L, "GME PAYOUT", null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("transfer: signed pipe-string, hl=en header, envelope data unwrapped")
    void transfer_happyPath() {
        String expectedSignature = signer.sign(NinepaySigner.canonical(
                REQ, "GMEPAY", "970418", "1023020330000", 0, "NGUYEN VAN A", 50_000L, "GME PAYOUT"));
        server.expect(requestTo(BASE + "/service/transfer"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("hl", "en"))
                .andExpect(jsonPath("$.request_id").value(REQ))
                .andExpect(jsonPath("$.partner_id").value("GMEPAY"))
                .andExpect(jsonPath("$.amount").value(50000))
                .andExpect(jsonPath("$.signature").value(expectedSignature))
                .andRespond(withSuccess(
                        "{\"success\":true,\"data\":{\"request_id\":\"" + REQ + "\","
                                + "\"partner_id\":\"GMEPAY\",\"transaction_id\":\"9P123456\","
                                + "\"bank_no\":\"970418\",\"account_no\":\"1023020330000\","
                                + "\"account_type\":0,\"account_name\":\"NGUYEN VAN A\","
                                + "\"request_amount\":50000,\"transfer_amount\":50500,"
                                + "\"content\":\"GME PAYOUT\",\"status\":\"PENDING\","
                                + "\"created_at\":\"2026-07-27 10:00:00\",\"message\":null,"
                                + "\"fee\":500,\"signature\":\"anything\"}}",
                        MediaType.APPLICATION_JSON));

        NinepayApiClient.TransferResult result = client.transfer(command());

        assertEquals("9P123456", result.transactionId());
        assertEquals("PENDING", result.status());
        assertEquals(500L, result.fee());
        assertEquals(50_500L, result.transferAmount());
        server.verify();
    }

    @Test
    @DisplayName("transfer: success=false envelope with error 1062 surfaces as a duplicate NinepayErrorException")
    void transfer_duplicate1062() {
        server.expect(requestTo(BASE + "/service/transfer"))
                .andRespond(withSuccess(
                        "{\"success\":false,\"error\":{\"code\":\"1062\","
                                + "\"message\":\"request_id already taken\"}}",
                        MediaType.APPLICATION_JSON));

        NinepayErrorException ex = assertThrows(NinepayErrorException.class, () -> client.transfer(command()));

        assertEquals("1062", ex.code());
        assertTrue(ex.isDuplicateRequestId());
    }

    @Test
    @DisplayName("transfer: I/O timeout maps to the AMBIGUOUS NinepayTransportException (poll before retry)")
    void transfer_timeoutIsAmbiguous() {
        server.expect(requestTo(BASE + "/service/transfer"))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThrows(NinepayTransportException.class, () -> client.transfer(command()));
    }

    @Test
    @DisplayName("transfer: 5xx maps to the AMBIGUOUS NinepayTransportException, never a definitive failure")
    void transfer_5xxIsAmbiguous() {
        server.expect(requestTo(BASE + "/service/transfer"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThrows(NinepayTransportException.class, () -> client.transfer(command()));
    }

    @Test
    @DisplayName("transfer: HTTP 400 whose body carries error.code is a definitive business error")
    void transfer_http400WithErrorBody() {
        server.expect(requestTo(BASE + "/service/transfer"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"error\":{\"code\":\"1024\","
                                + "\"message\":\"Insufficient balance\"}}"));

        NinepayErrorException ex = assertThrows(NinepayErrorException.class, () -> client.transfer(command()));
        assertEquals("1024", ex.code());
    }

    @Test
    @DisplayName("transfer-info by request_id: content_type=TRANSACTION_REQUEST_ID carries the original id")
    void transferInfo_byRequestId() {
        server.expect(requestTo(BASE + "/service/transfer/info"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.content_type").value("TRANSACTION_REQUEST_ID"))
                .andExpect(jsonPath("$.transaction_id").value(REQ))
                .andRespond(withSuccess(
                        "{\"success\":true,\"data\":{\"request_id\":\"LOOKUP1\","
                                + "\"partner_id\":\"GMEPAY\",\"transaction_id\":\"9P123456\","
                                + "\"request_amount\":50000,\"transfer_amount\":50500,"
                                + "\"status\":\"SUCCESS\",\"created_at\":\"2026-07-27 10:00:00\","
                                + "\"signature\":\"x\"}}",
                        MediaType.APPLICATION_JSON));

        NinepayApiClient.TransferInfo info = client.transferInfoByRequestId("LOOKUP1", REQ);

        assertEquals("SUCCESS", info.status());
        assertEquals("9P123456", info.transactionId());
        server.verify();
    }

    @Test
    @DisplayName("balance: signs request_id|partner_id|request_time and parses balance_available")
    void balance_happyPath() {
        server.expect(requestTo(BASE + "/service/account/balance"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.request_time").exists())
                .andRespond(withSuccess(
                        "{\"success\":true,\"data\":{\"request_id\":\"LOOKUP2\","
                                + "\"partner_id\":\"GMEPAY\",\"response_time\":\"2026-07-27 10:00:00\","
                                + "\"balance_info\":[],"
                                + "\"balance_available\":[{\"unit\":\"VND\",\"value\":123456789}],"
                                + "\"signature\":\"x\"}}",
                        MediaType.APPLICATION_JSON));

        NinepayApiClient.BalanceResult result = client.balance("LOOKUP2");

        assertEquals(1, result.available().size());
        assertEquals("VND", result.available().get(0).unit());
        assertEquals(123_456_789L, result.available().get(0).value());
    }

    @Test
    @DisplayName("decode-qr: signs ONLY request_id|partner_id (not str_qr) per spec")
    void decodeQr_signsTwoFieldsOnly() {
        String expectedSignature = signer.sign(NinepaySigner.canonical("LOOKUP3", "GMEPAY"));
        server.expect(requestTo(BASE + "/service/v2/decode-qr"))
                .andExpect(jsonPath("$.str_qr").value("000201010212..."))
                .andExpect(jsonPath("$.signature").value(expectedSignature))
                .andRespond(withSuccess(
                        "{\"success\":true,\"data\":{\"type\":\"VIETQR\",\"bank_no\":\"970418\","
                                + "\"account_number\":\"1023020330000\",\"amount\":50000,"
                                + "\"account_name\":\"NGUYEN VAN A\",\"city\":null,"
                                + "\"description\":\"pay me\",\"crc\":\"ABCD\",\"service\":\"QRIBFTTA\"}}",
                        MediaType.APPLICATION_JSON));

        NinepayApiClient.DecodedQr decoded = client.decodeQr("LOOKUP3", "000201010212...");

        assertEquals("VIETQR", decoded.type());
        assertEquals("1023020330000", decoded.accountNumber());
        assertEquals(50_000L, decoded.amountVnd());
        assertNull(decoded.city());
        server.verify();
    }

    @Test
    @DisplayName("bank-list: plain GET, unwraps data.banks")
    void bankList_happyPath() {
        server.expect(requestTo(BASE + "/transfer-bank/bank-list"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"data\":{\"banks\":[{\"bank_no\":\"970418\",\"name\":\"BIDV\"}]}}",
                        MediaType.APPLICATION_JSON));

        assertEquals(1, client.bankList().size());
    }

    @Test
    @DisplayName("verify-responses=true: a response signed by the REAL counter-key passes; a forged one is ambiguous")
    void responseSignature_verification() throws Exception {
        // 9Pay-side keypair: client verifies with 9Pay's public key.
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair ours = generator.generateKeyPair();
        KeyPair ninepays = generator.generateKeyPair();
        NinepaySigner ourSigner = new NinepaySigner(ours.getPrivate(), ninepays.getPublic(), "SHA256");
        NinepaySigner ninepaySide = new NinepaySigner(ninepays.getPrivate(), ours.getPublic(), "SHA256");

        ObjectMapper mapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer strictServer = MockRestServiceServer.bindTo(builder).build();
        NinepayApiClient strictClient = new NinepayApiClient(builder.build(), ourSigner, mapper, "GMEPAY", true);

        String canonical = NinepaySigner.canonical("LOOKUP4", "GMEPAY", "9P1", "SUCCESS", "2026-07-27 10:00:00");
        String goodSignature = ninepaySide.sign(canonical);
        String body = "{\"success\":true,\"data\":{\"request_id\":\"LOOKUP4\",\"partner_id\":\"GMEPAY\","
                + "\"transaction_id\":\"9P1\",\"request_amount\":50000,\"transfer_amount\":50500,"
                + "\"status\":\"SUCCESS\",\"created_at\":\"2026-07-27 10:00:00\","
                + "\"signature\":\"%s\"}}";

        // Both expectations declared up front (MockRestServiceServer forbids adding after use).
        strictServer.expect(requestTo(BASE + "/service/transfer/info"))
                .andRespond(withSuccess(String.format(body, goodSignature), MediaType.APPLICATION_JSON));
        strictServer.expect(requestTo(BASE + "/service/transfer/info"))
                .andRespond(withSuccess(String.format(body, ourSigner.sign(canonical)), // forged
                        MediaType.APPLICATION_JSON));

        assertEquals("SUCCESS", strictClient.transferInfoByRequestId("LOOKUP4", REQ).status());
        assertThrows(NinepayTransportException.class,
                () -> strictClient.transferInfoByRequestId("LOOKUP4", REQ));
    }

    @Test
    @DisplayName("T5-4 fail closed: with verification ON but NO 9Pay public key, the response is NOT trusted")
    void responseSignature_failsClosedWithoutAPublicKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair ours = generator.generateKeyPair();
        // The production misconfiguration: our own key is present, 9Pay's public key is NOT
        // (blank ninepay-public-key-pem — exactly the shipped placeholder).
        NinepaySigner noTrustAnchor = new NinepaySigner("SHA256", pem(ours), "");
        assertTrue(!noTrustAnchor.canVerify(), "precondition: nothing to verify against");

        ObjectMapper mapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer keylessServer = MockRestServiceServer.bindTo(builder).build();
        NinepayApiClient keylessClient =
                new NinepayApiClient(builder.build(), noTrustAnchor, mapper, "GMEPAY", true);

        // A perfectly well-formed, "successful" 9Pay response — with any signature at all.
        String body = "{\"success\":true,\"data\":{\"request_id\":\"LOOKUP9\",\"partner_id\":\"GMEPAY\","
                + "\"transaction_id\":\"9P9\",\"request_amount\":50000,\"transfer_amount\":50500,"
                + "\"status\":\"SUCCESS\",\"created_at\":\"2026-07-27 10:00:00\","
                + "\"signature\":\"YW55LXNpZ25hdHVyZQ==\"}}";
        keylessServer.expect(requestTo(BASE + "/service/transfer/info"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        // Before T5-4 the default (verify-responses=false) accepted this as SUCCESS. Now it is
        // rejected as unverifiable-and-therefore-AMBIGUOUS: the caller polls / stays UNKNOWN,
        // and no payout is marked successful on the strength of an unverified response.
        NinepayTransportException ex = assertThrows(NinepayTransportException.class,
                () -> keylessClient.transferInfoByRequestId("LOOKUP9", REQ));
        assertTrue(ex.getMessage().contains("cannot be verified"), ex.getMessage());
        assertTrue(ex.getMessage().contains("ninepay-public-key-pem"), ex.getMessage());
    }

    /** PKCS#8 PEM for a generated private key, as the config property would carry it. */
    private static String pem(KeyPair keyPair) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + java.util.Base64.getMimeEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }
}
