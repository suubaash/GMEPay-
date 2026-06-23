package com.gme.pay.payment.client.rest;

import com.gme.pay.payment.domain.PaymentException;
import com.gme.pay.payment.domain.PaymentStatus;
import com.gme.pay.payment.domain.client.TransactionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** {@link MockRestServiceServer} unit tests for {@link RestTransactionClient}. */
class RestTransactionClientTest {

    private static final String BASE = "http://transaction-mgmt:8080";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private RestTransactionClient client;

    @BeforeEach
    void setUp() {
        builder = RestClientSupport.withJavaTime(RestClient.builder().baseUrl(BASE));
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestTransactionClient(builder.build());
    }

    @Test
    @DisplayName("createPending: POSTs to /v1/transactions and parses created result")
    void createPending_parsesResponse() {
        server.expect(requestTo(BASE + "/v1/transactions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"txnRef\":\"txn_001\",\"paymentId\":\"pay_001\","
                                + "\"createdAt\":\"2026-06-08T09:31:15Z\"}",
                        MediaType.APPLICATION_JSON));

        TransactionClient.CreateResult result = client.createPending(
                new TransactionClient.CreateRequest(
                        42L, "PTNR_TXN_001", "zeropay", "inbound", "MPM",
                        new BigDecimal("50000"), "KRW",
                        new BigDecimal("37.365197"), "USD",
                        "M001", "qte_1", new BigDecimal("0.0080")));

        assertEquals("txn_001", result.txnRef());
        assertEquals("pay_001", result.paymentId());
        server.verify();
    }

    @Test
    @DisplayName("createPending: non-2xx wraps as PaymentException")
    void createPending_serverErrorWrapsAsPaymentException() {
        server.expect(requestTo(BASE + "/v1/transactions"))
                .andRespond(withServerError().body("db down"));

        assertThrows(PaymentException.class, () -> client.createPending(
                new TransactionClient.CreateRequest(
                        42L, "PTNR_TXN_001", "zeropay", "inbound", "MPM",
                        new BigDecimal("50000"), "KRW",
                        new BigDecimal("37.365197"), "USD",
                        "M001", "qte_1", new BigDecimal("0.0080"))));
        server.verify();
    }

    @Test
    @DisplayName("commitStatus: PATCHes /v1/transactions/{ref}/status with status payload")
    void commitStatus_patchesEndpoint() {
        server.expect(requestTo(BASE + "/v1/transactions/txn_001/status"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withSuccess());

        client.commitStatus("txn_001",
                new TransactionClient.StatusPatch(
                        PaymentStatus.APPROVED, "ZP_TXN_001", "ZP_OK",
                        new BigDecimal("37.015197"), Instant.parse("2026-06-08T09:31:15Z")));

        server.verify();
    }
}
