package com.gme.pay.payment.client.rest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * Plain JUnit 5 unit test for {@link RestRevenueLedgerClient} using Spring's
 * {@link MockRestServiceServer} (built-in via spring-boot-starter-test — no extra deps,
 * no running revenue-ledger). Covers the rounding-residual posting contract per
 * {@code docs/MONEY_CONVENTION.md}.
 */
class RestRevenueLedgerClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private RestRevenueLedgerClient client;

    @BeforeEach
    void setUp() {
        // MockRestServiceServer intercepts via the RestTemplate's request factory.
        // We build a RestClient on top of that same factory so calls hit the mock instead of the network.
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        RestClient rest = RestClient.builder()
                .baseUrl("http://revenue-ledger:8080")
                .requestFactory(restTemplate.getRequestFactory())
                .build();
        client = new RestRevenueLedgerClient(rest);
    }

    @Test
    void positiveResidual_postsExpectedJson_andReturnsNormally() {
        server.expect(requestTo("http://revenue-ledger:8080/v1/journals/rounding-residual"))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().contentType(MediaType.APPLICATION_JSON))
              .andExpect(jsonPath("$.reference").value("TXN-00001"))
              .andExpect(jsonPath("$.currency").value("USD"))
              .andExpect(jsonPath("$.residual").value(0.007))
              .andRespond(withSuccess("{\"journalId\":\"J1\"}", MediaType.APPLICATION_JSON));

        assertDoesNotThrow(() ->
                client.postRoundingResidual("TXN-00001", new BigDecimal("0.007"), "USD"));

        server.verify();
    }

    @Test
    void zeroResidual_postsNothing() {
        // No expectations queued — if the client posts anything, server.verify() will fail.
        client.postRoundingResidual("TXN-NOOP", BigDecimal.ZERO, "USD");
        server.verify();
    }

    @Test
    void serverError_doesNotPropagateToCaller() {
        // 5xx: per RestRevenueLedgerClient contract, must be swallowed (residual stays locked on txn).
        server.expect(requestTo("http://revenue-ledger:8080/v1/journals/rounding-residual"))
              .andRespond(withServerError());
        assertDoesNotThrow(() ->
                client.postRoundingResidual("TXN-5XX", new BigDecimal("0.10"), "USD"));
        server.verify();
    }

    @Test
    void clientError_doesNotPropagateToCaller() {
        // 4xx: also swallowed — same policy, residual is locked on the txn for offline retry.
        server.expect(requestTo("http://revenue-ledger:8080/v1/journals/rounding-residual"))
              .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error_code\":\"MISSING_CURRENCY\"}"));
        assertDoesNotThrow(() ->
                client.postRoundingResidual("TXN-4XX", new BigDecimal("0.10"), "USD"));
        server.verify();
    }

    @Test
    void noContentResponse_isAccepted() {
        // revenue-ledger returns 204 when residual is zero — but our client filters zero earlier;
        // still, 204 from server must be handled cleanly.
        server.expect(requestTo("http://revenue-ledger:8080/v1/journals/rounding-residual"))
              .andRespond(withNoContent());
        assertDoesNotThrow(() ->
                client.postRoundingResidual("TXN-204", new BigDecimal("0.01"), "USD"));
        server.verify();
    }

    @Test
    void revenueCapture_postsExpectedJson_andReturnsNormally() {
        server.expect(requestTo("http://revenue-ledger:8080/v1/revenue/capture"))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().contentType(MediaType.APPLICATION_JSON))
              .andExpect(jsonPath("$.txnRef").value("TXN-9"))
              .andExpect(jsonPath("$.partnerId").value(42))
              .andExpect(jsonPath("$.revenueDate").value("2026-06-15"))   // sent as ISO string
              .andExpect(jsonPath("$.serviceChargeAmount").value(500))
              .andExpect(jsonPath("$.serviceChargeCcy").value("KRW"))
              .andRespond(withStatus(HttpStatus.CREATED));

        assertDoesNotThrow(() -> client.postRevenueCapture(
                "TXN-9", 42L, 0L, java.time.LocalDate.of(2026, 6, 15),
                new BigDecimal("1.00"), new BigDecimal("0.50"),
                new BigDecimal("500"), "KRW", new BigDecimal("0.70")));

        server.verify();
    }

    @Test
    void revenueCapture_serverError_doesNotPropagateToCaller() {
        // 5xx: capture is best-effort; must never fail the commit path.
        server.expect(requestTo("http://revenue-ledger:8080/v1/revenue/capture"))
              .andRespond(withServerError());
        assertDoesNotThrow(() -> client.postRevenueCapture(
                "TXN-5XX", 42L, 0L, java.time.LocalDate.of(2026, 6, 15),
                new BigDecimal("1.00"), new BigDecimal("0.50"),
                new BigDecimal("500"), "KRW", new BigDecimal("0.70")));
        server.verify();
    }

    @Test
    void commissionSplit_postsExpectedJson_andReturnsNormally() {
        server.expect(requestTo("http://revenue-ledger:8080/v1/revenue/commission-split"))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().contentType(MediaType.APPLICATION_JSON))
              .andExpect(jsonPath("$.txnRef").value("TXN-CS"))
              .andExpect(jsonPath("$.partnerId").value(42))
              .andExpect(jsonPath("$.revenueDate").value("2026-06-15"))   // ISO string
              .andExpect(jsonPath("$.payoutAmountKrw").value(50000))
              .andExpect(jsonPath("$.merchantFeeRate").value(0.0080))
              .andExpect(jsonPath("$.vanFeeRate").value(0.0008))
              .andExpect(jsonPath("$.gmeSharePct").value(0.70))
              .andExpect(jsonPath("$.partnerSharePct").value(0.30))
              .andRespond(withStatus(HttpStatus.CREATED));

        assertDoesNotThrow(() -> client.postCommissionSplit(
                "TXN-CS", 42L, 0L, java.time.LocalDate.of(2026, 6, 15),
                50000L, new BigDecimal("0.0080"), new BigDecimal("0.0008"),
                new BigDecimal("0.70"), new BigDecimal("0.30")));

        server.verify();
    }

    @Test
    void commissionSplit_serverError_doesNotPropagateToCaller() {
        // 5xx: the split is best-effort post-commit; must never fail the (already committed) payment.
        server.expect(requestTo("http://revenue-ledger:8080/v1/revenue/commission-split"))
              .andRespond(withServerError());
        assertDoesNotThrow(() -> client.postCommissionSplit(
                "TXN-CS-5XX", 42L, 0L, java.time.LocalDate.of(2026, 6, 15),
                50000L, new BigDecimal("0.0080"), new BigDecimal("0.0008"),
                new BigDecimal("0.70"), new BigDecimal("0.30")));
        server.verify();
    }
}
