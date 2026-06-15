package com.gme.pay.payment.client.rest;

import com.gme.pay.payment.domain.InsufficientPrefundingException;
import com.gme.pay.payment.domain.PaymentException;
import com.gme.pay.payment.domain.client.PrefundingClient;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** {@link MockRestServiceServer} unit tests for {@link RestPrefundingClient}. */
class RestPrefundingClientTest {

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private RestPrefundingClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl("http://prefunding:8080");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestPrefundingClient(builder.build());
    }

    @Test
    @DisplayName("deduct: happy path returns deducted amount and balance after")
    void deduct_parsesResponse() {
        server.expect(requestTo("http://prefunding:8080/v1/prefunding/42/deduct"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"deductedUsd\":37.015197,\"balanceAfter\":962.985}",
                        MediaType.APPLICATION_JSON));

        PrefundingClient.DeductionResult result =
                client.deduct(42L, "txn_001", new BigDecimal("37.015197"));

        assertEquals(new BigDecimal("37.015197"), result.deductedUsd());
        assertEquals(new BigDecimal("962.985"), result.balanceAfter());
        server.verify();
    }

    @Test
    @DisplayName("deduct: 402 Payment Required maps to InsufficientPrefundingException")
    void deduct_paymentRequiredMapsToInsufficient() {
        server.expect(requestTo("http://prefunding:8080/v1/prefunding/42/deduct"))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"INSUFFICIENT_PREFUNDING\",\"available\":5.00,\"required\":37.015197}"));

        InsufficientPrefundingException ex = assertThrows(InsufficientPrefundingException.class,
                () -> client.deduct(42L, "txn_001", new BigDecimal("37.015197")));

        // available was parsed out of the body; required came from the request amount
        assertEquals(new BigDecimal("5.00"), ex.available());
        assertEquals(new BigDecimal("37.015197"), ex.required());
        server.verify();
    }

    @Test
    @DisplayName("deduct: other non-2xx wraps as PaymentException")
    void deduct_otherErrorWrapsAsPaymentException() {
        server.expect(requestTo("http://prefunding:8080/v1/prefunding/42/deduct"))
                .andRespond(withServerError().body("upstream boom"));

        assertThrows(PaymentException.class,
                () -> client.deduct(42L, "txn_002", new BigDecimal("10.00")));
        server.verify();
    }

    @Test
    @DisplayName("reverse: POSTs to /reverse and returns the actual reversed amount")
    void reverse_postsToReverseEndpoint() {
        server.expect(requestTo("http://prefunding:8080/v1/prefunding/42/reverse"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"partnerId\":\"42\",\"reversedUsd\":125.50,\"balance\":1088.485}",
                        MediaType.APPLICATION_JSON));

        PrefundingClient.ReverseResult r = client.reverse(42L, "txn_001");

        assertEquals(0, r.reversedUsd().compareTo(new BigDecimal("125.50")),
                "client must surface the reversed USD from the response");
        server.verify();
    }
}
