package com.gme.pay.payment.client.rest;

import com.gme.pay.payment.domain.PaymentException;
import com.gme.pay.payment.domain.client.RateClient;
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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** {@link MockRestServiceServer} unit tests for {@link RestRateClient}. */
class RestRateClientTest {

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private RestRateClient client;

    @BeforeEach
    void setUp() {
        builder = RestClientSupport.withJavaTime(RestClient.builder().baseUrl("http://rate-fx:8080"));
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestRateClient(builder.build());
    }

    @Test
    @DisplayName("loadQuote POSTs to /v1/rates and parses the rate view")
    void loadQuote_parsesResponse() {
        String responseJson = "{"
                + "\"quoteId\":\"qte_1\",\"partnerId\":42,\"schemeId\":\"zeropay\","
                + "\"direction\":\"inbound\",\"targetPayout\":50000,\"payoutCurrency\":\"KRW\","
                + "\"collectionUsd\":37.015197,\"payoutUsdCost\":35.562589,"
                + "\"collectionMarginUsd\":0.925380,\"payoutMarginUsd\":0.370152,"
                + "\"sendAmount\":37.015197,\"serviceCharge\":0.35,"
                + "\"collectionAmount\":37.365197,\"collectionCurrency\":\"USD\","
                + "\"offerRateColl\":1.025610,\"crossRate\":1351.00,"
                + "\"validUntil\":\"2026-06-09T10:00:00Z\",\"isSameCcyShortCircuit\":false"
                + "}";

        server.expect(requestTo("http://rate-fx:8080/v1/rates"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        RateClient.RateQuoteView view = client.loadQuote("qte_1", 42L);

        assertEquals("qte_1", view.quoteId());
        assertEquals(42L, view.partnerId());
        assertEquals("zeropay", view.schemeId());
        assertEquals(new BigDecimal("50000"), view.targetPayout());
        assertEquals("KRW", view.payoutCurrency());
        assertEquals(new BigDecimal("37.015197"), view.collectionUsd());
        server.verify();
    }

    @Test
    @DisplayName("non-2xx response is wrapped in PaymentException")
    void loadQuote_nonSuccessThrowsPaymentException() {
        server.expect(requestTo("http://rate-fx:8080/v1/rates"))
                .andRespond(withBadRequest().body("{\"code\":\"RATE_QUOTE_EXPIRED\"}"));

        assertThrows(PaymentException.class, () -> client.loadQuote("qte_x", 42L));
        server.verify();
    }
}
