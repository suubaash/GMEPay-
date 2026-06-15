package com.gme.pay.payment.client.rest;

import com.gme.pay.payment.domain.PaymentException;
import com.gme.pay.payment.domain.client.RateClient;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
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
    @DisplayName("loadQuote GETs /v1/quotes/{quoteId} and maps the StoredQuote into the view")
    void loadQuote_parsesResponse() {
        // StoredQuote-shaped body: money/rate fields are decimal strings (MONEY_CONVENTION.md).
        String responseJson = "{"
                + "\"quoteId\":\"RQ-qte_1\","
                + "\"collectionCurrency\":\"USD\","
                + "\"settleACurrency\":\"USD\","
                + "\"settleBCurrency\":\"KRW\","
                + "\"payoutCurrency\":\"KRW\","
                + "\"targetPayout\":\"50000.00000000\","
                + "\"payoutUsdCost\":\"35.56258900\","
                + "\"collectionUsd\":\"37.01519700\","
                + "\"collectionMarginUsd\":\"0.92538000\","
                + "\"payoutMarginUsd\":\"0.37015200\","
                + "\"sendAmount\":\"37.01519700\","
                + "\"collectionAmount\":\"37.36519700\","
                + "\"offerRateColl\":\"1.02561000\","
                + "\"crossRate\":\"1351.00000000\","
                + "\"shortCircuit\":false,"
                + "\"createdAt\":\"2026-06-09T09:45:00Z\","
                + "\"expiresAt\":\"2026-06-09T10:00:00Z\""
                + "}";

        server.expect(requestTo("http://rate-fx:8080/v1/quotes/RQ-qte_1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        RateClient.RateQuoteView view = client.loadQuote("RQ-qte_1", 42L);

        assertEquals("RQ-qte_1", view.quoteId());
        // partnerId is carried from the loadQuote arg (no server-side ownership check on GET).
        assertEquals(42L, view.partnerId());
        // schemeId / direction are not part of StoredQuote and not supplied by the signature.
        assertNull(view.schemeId());
        assertNull(view.direction());
        assertEquals(new BigDecimal("50000.00000000"), view.targetPayout());
        assertEquals("KRW", view.payoutCurrency());
        assertEquals("USD", view.collectionCurrency());
        assertEquals(new BigDecimal("37.01519700"), view.collectionUsd());
        assertEquals(new BigDecimal("35.56258900"), view.payoutUsdCost());
        assertEquals(new BigDecimal("0.92538000"), view.collectionMarginUsd());
        assertEquals(new BigDecimal("0.37015200"), view.payoutMarginUsd());
        assertEquals(new BigDecimal("37.01519700"), view.sendAmount());
        assertEquals(new BigDecimal("37.36519700"), view.collectionAmount());
        assertEquals(new BigDecimal("1.02561000"), view.offerRateColl());
        assertEquals(new BigDecimal("1351.00000000"), view.crossRate());
        // serviceCharge is derived: collectionAmount - sendAmount.
        assertEquals(new BigDecimal("0.35000000"), view.serviceCharge());
        // validUntil <- expiresAt; isSameCcyShortCircuit <- shortCircuit.
        assertEquals(java.time.Instant.parse("2026-06-09T10:00:00Z"), view.validUntil());
        assertEquals(false, view.isSameCcyShortCircuit());
        server.verify();
    }

    @Test
    @DisplayName("missing sendAmount/collectionAmount yields a ZERO serviceCharge")
    void loadQuote_derivesZeroServiceChargeWhenLegsMissing() {
        String responseJson = "{"
                + "\"quoteId\":\"RQ-qte_2\","
                + "\"collectionCurrency\":\"USD\","
                + "\"payoutCurrency\":\"KRW\","
                + "\"targetPayout\":\"50000.00000000\","
                + "\"shortCircuit\":true,"
                + "\"expiresAt\":\"2026-06-09T10:00:00Z\""
                + "}";

        server.expect(requestTo("http://rate-fx:8080/v1/quotes/RQ-qte_2"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        RateClient.RateQuoteView view = client.loadQuote("RQ-qte_2", 7L);

        assertEquals(BigDecimal.ZERO, view.serviceCharge());
        assertEquals(true, view.isSameCcyShortCircuit());
        server.verify();
    }

    @Test
    @DisplayName("409 RATE_QUOTE_EXPIRED is wrapped in PaymentException")
    void loadQuote_expiredQuoteThrowsPaymentException() {
        server.expect(requestTo("http://rate-fx:8080/v1/quotes/RQ-qte_x"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .body("{\"code\":\"RATE_QUOTE_EXPIRED\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThrows(PaymentException.class, () -> client.loadQuote("RQ-qte_x", 42L));
        server.verify();
    }
}
