package com.gme.pay.payment.client.rest;

import com.gme.pay.contracts.PrefundingDeductionHistoryView;
import com.gme.pay.contracts.PrefundingReserveResponse;
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
    @DisplayName("balance: GETs the BalanceView and maps balance/threshold/currency")
    void balance_parsesBalanceView() {
        server.expect(requestTo("http://prefunding:8080/v1/prefunding/PTNR-OS/balance"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"partnerCode\":\"PTNR-OS\",\"currency\":\"USD\","
                                + "\"balance\":\"48234.5600\",\"threshold\":\"10000.00\","
                                + "\"pctOfThreshold\":\"482.35\"}",
                        MediaType.APPLICATION_JSON));

        PrefundingClient.BalanceSnapshot snap = client.balance("PTNR-OS");

        assertEquals(new BigDecimal("48234.5600"), snap.balanceUsd());
        assertEquals(new BigDecimal("10000.00"), snap.lowBalanceThresholdUsd());
        assertEquals("USD", snap.currency());
        server.verify();
    }

    @Test
    @DisplayName("balance: a non-2xx is wrapped as PaymentException")
    void balance_errorWrapped() {
        server.expect(requestTo("http://prefunding:8080/v1/prefunding/PTNR-OS/balance"))
                .andRespond(withServerError());

        assertThrows(PaymentException.class, () -> client.balance("PTNR-OS"));
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

    @Test
    @DisplayName("deductionHistory: GETs /deductions?limit and binds PrefundingDeductionHistoryView")
    void deductionHistory_bindsCanonicalView() {
        server.expect(requestTo("http://prefunding:8080/v1/prefunding/PTNR-OS/deductions?limit=5"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"partnerCode\":\"PTNR-OS\",\"limit\":5,\"entries\":["
                                + "{\"amountUsd\":\"12.50\",\"at\":\"2026-06-29T01:00:00Z\",\"txnRef\":\"txn_9\"}]}",
                        MediaType.APPLICATION_JSON));

        PrefundingDeductionHistoryView view = client.deductionHistory("PTNR-OS", 5);

        assertEquals("PTNR-OS", view.partnerCode());
        assertEquals(1, view.entries().size());
        assertEquals(0, view.entries().get(0).amountUsd().compareTo(new BigDecimal("12.50")));
        assertEquals("txn_9", view.entries().get(0).txnRef());
        server.verify();
    }

    @Test
    @DisplayName("reserveCpm: POSTs the canonical reserve request and binds the reserve response")
    void reserveCpm_bindsResponse() {
        server.expect(requestTo("http://prefunding:8080/internal/v1/prefunding/7/reserve"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"partnerId\":7,\"reservationId\":\"RSV-1\",\"reservedAmountUsd\":\"40.00\","
                                + "\"availableUsd\":\"960.00\",\"reservedUsd\":\"40.00\"}",
                        MediaType.APPLICATION_JSON));

        PrefundingReserveResponse r = client.reserveCpm(7L, new BigDecimal("40.00"), "idem-1", "txn_7");

        assertEquals("RSV-1", r.reservationId());
        assertEquals(0, r.reservedAmountUsd().compareTo(new BigDecimal("40.00")));
        assertEquals(0, r.availableUsd().compareTo(new BigDecimal("960.00")));
        server.verify();
    }

    @Test
    @DisplayName("reserveCpm: 402 Payment Required maps to InsufficientPrefundingException")
    void reserveCpm_paymentRequiredMapsToInsufficient() {
        server.expect(requestTo("http://prefunding:8080/internal/v1/prefunding/7/reserve"))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"INSUFFICIENT_PREFUNDING\",\"available\":5.00}"));

        assertThrows(InsufficientPrefundingException.class,
                () -> client.reserveCpm(7L, new BigDecimal("40.00"), "idem-1", "txn_7"));
        server.verify();
    }

    @Test
    @DisplayName("releaseCpm: POSTs /release with the canonical release request")
    void releaseCpm_deletesReservation() {
        server.expect(requestTo("http://prefunding:8080/internal/v1/prefunding/7/release"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        client.releaseCpm(7L, "RSV-1", "idem-1", "EXPIRED");
        server.verify();
    }
}
