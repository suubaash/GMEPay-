package com.gme.pay.settlement.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Posts the rounding residual to revenue-ledger {@code POST /v1/journals/rounding-residual} with
 * {@code reference} = settlement batch id; zero residual is a client-side no-op; transport error
 * reports failure (so the caller retries) without throwing.
 */
class RestRoundingResidualClientTest {

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
    private final RestRoundingResidualClient client =
            new RestRoundingResidualClient(restTemplate, "http://revenue-ledger:8084");

    private static final String BATCH_ID = "ZP0061-20260615-MORNING";

    @Test
    @DisplayName("POSTs residual with reference = batch id, money as decimal string")
    void postsResidualWithBatchIdReference() {
        server.expect(requestTo("http://revenue-ledger:8084/v1/journals/rounding-residual"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.reference").value(BATCH_ID))
                .andExpect(jsonPath("$.residual").value("0.37"))
                .andExpect(jsonPath("$.currency").value("KRW"))
                .andRespond(withSuccess());

        boolean ok = client.postResidual(BATCH_ID, new BigDecimal("0.37"), "KRW");

        assertThat(ok).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("zero residual is a client-side no-op (no HTTP call), reported as handled")
    void zeroResidualNoCall() {
        // No server.expect(...) → MockRestServiceServer fails if any request is made.
        boolean ok = client.postResidual(BATCH_ID, BigDecimal.ZERO, "KRW");

        assertThat(ok).isTrue();
        server.verify();   // verifies NO request was issued
    }

    @Test
    @DisplayName("revenue-ledger error → returns false (caller retries), never throws")
    void failsSoftOnError() {
        server.expect(requestTo("http://revenue-ledger:8084/v1/journals/rounding-residual"))
                .andRespond(withServerError());

        boolean ok = client.postResidual(BATCH_ID, new BigDecimal("0.37"), "KRW");

        assertThat(ok).isFalse();
    }
}
