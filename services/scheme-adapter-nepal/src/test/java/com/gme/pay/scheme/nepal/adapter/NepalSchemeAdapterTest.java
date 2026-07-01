package com.gme.pay.scheme.nepal.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.scheme.nepal.client.NepalSchemeApiClient;
import com.gme.pay.scheme.nepal.dto.DecodeResponse;
import com.gme.pay.scheme.nepal.dto.SubmitRequest;
import com.gme.pay.scheme.nepal.dto.SubmitResponse;
import com.gme.pay.scheme.nepal.sign.StubNepalSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Mapping tests for {@link NepalSchemeAdapter} driven through a mocked sim-nepal-qr — covers
 * decode field-shape + rupee→paisa conversion and submit idx→schemeTxnRef + state derivation.
 */
class NepalSchemeAdapterTest {

    private static final String BASE = "http://localhost:9103";

    private MockRestServiceServer server;
    private NepalSchemeAdapter adapter;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        NepalSchemeApiClient client = new NepalSchemeApiClient(
                builder, new StubNepalSigner(mapper), mapper, BASE, "t", "k");
        adapter = new NepalSchemeAdapter(client);
    }

    @Test
    @DisplayName("decode: dynamic QR rupee amount converts to paisa; static → null")
    void decode_convertsRupeesToPaisa() {
        server.expect(requestTo(BASE + "/qrscan-thirdparty/parse/"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"format\":\"EMVCo\",\"merchantInfoExtra\":\"fonepay.com\","
                        + "\"trxCurrency\":\"NPR\",\"trxAmount\":\"123.45\","
                        + "\"merchantName\":\"Shop\",\"merchantCity\":\"Kathmandu\"}",
                        MediaType.APPLICATION_JSON));

        DecodeResponse resp = adapter.decode("00020101...fonepay...");

        assertEquals("fonepay", resp.network());
        assertEquals("Shop", resp.merchantName());
        assertEquals("Kathmandu", resp.merchantCity());
        assertEquals(12345L, resp.amountPaisa());
        assertEquals("NPR", resp.currency());
        server.verify();
    }

    @Test
    @DisplayName("decode: static QR → null amount")
    void decode_staticNullAmount() {
        server.expect(requestTo(BASE + "/qrscan-thirdparty/parse/"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"format\":\"EMVCo\",\"merchantInfoExtra\":\"nepalpay\","
                        + "\"trxCurrency\":\"NPR\",\"trxAmount\":null,\"merchantName\":\"M\"}",
                        MediaType.APPLICATION_JSON));

        DecodeResponse resp = adapter.decode("00020101...nepalpay...");

        assertEquals("nepalpay", resp.network());
        assertNull(resp.amountPaisa());
        server.verify();
    }

    @Test
    @DisplayName("submit: pay success → schemeTxnRef=idx, APPROVED, echoed paisa")
    void submit_success() {
        server.expect(requestTo(BASE + "/qrscan-thirdparty/pay/"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"idx\":\"KHTXNXYZ\",\"amount\":\"100000\","
                        + "\"type\":\"ScanandPay\",\"detail\":\"Transaction has been approved\"}",
                        MediaType.APPLICATION_JSON));

        SubmitResponse resp = adapter.submit(new SubmitRequest(
                "qs", 100000L, "REF-9", null, "ServicePayment", "r"));

        assertEquals("KHTXNXYZ", resp.schemeTxnRef());
        assertEquals("APPROVED", resp.status());
        assertEquals(100000L, resp.amountPaisa());
        server.verify();
    }

    @Test
    @DisplayName("submit: pending detail → PENDING state")
    void submit_pending() {
        server.expect(requestTo(BASE + "/qrscan-thirdparty/pay/"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"idx\":\"KHTXNPEND\",\"amount\":\"5000\","
                        + "\"detail\":\"Transaction is in pending state\"}",
                        MediaType.APPLICATION_JSON));

        SubmitResponse resp = adapter.submit(new SubmitRequest(
                "qs", 5000L, "REF-P", null, null, null));

        assertEquals("PENDING", resp.status());
        server.verify();
    }
}
