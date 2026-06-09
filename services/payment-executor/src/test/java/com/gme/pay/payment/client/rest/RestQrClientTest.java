package com.gme.pay.payment.client.rest;

import com.gme.pay.payment.domain.PaymentException;
import com.gme.pay.payment.domain.client.QrClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** {@link MockRestServiceServer} unit tests for {@link RestQrClient}. */
class RestQrClientTest {

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private RestQrClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl("http://merchant-qr-data:8080");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestQrClient(builder.build());
    }

    @Test
    @DisplayName("resolve: parses merchant view from GET /v1/merchants/{qr}")
    void resolve_parsesResponse() {
        server.expect(requestTo("http://merchant-qr-data:8080/v1/merchants/ZPQR0001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"merchantId\":\"M001\",\"merchantName\":\"Coffee Shop\","
                                + "\"payoutCurrency\":\"KRW\",\"schemeId\":\"zeropay\"}",
                        MediaType.APPLICATION_JSON));

        QrClient.MerchantView view = client.resolve("ZPQR0001");

        assertEquals("M001", view.merchantId());
        assertEquals("Coffee Shop", view.merchantName());
        assertEquals("KRW", view.payoutCurrency());
        assertEquals("zeropay", view.schemeId());
        server.verify();
    }

    @Test
    @DisplayName("resolve: 404 maps to PaymentException")
    void resolve_notFoundWrapsAsPaymentException() {
        server.expect(requestTo("http://merchant-qr-data:8080/v1/merchants/UNKNOWN"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND)
                        .body("{\"code\":\"MERCHANT_NOT_FOUND\"}"));

        assertThrows(PaymentException.class, () -> client.resolve("UNKNOWN"));
        server.verify();
    }
}
