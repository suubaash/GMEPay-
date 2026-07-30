package com.gme.pay.payment.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gme.pay.payment.persistence.RevenuePostingFailureStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * <b>T2-5 wire-classification proof</b> for the replay client.
 *
 * <p>Everything the replay job decides — clear the row, back off, or poison and alert — hangs off how the HTTP
 * answer is classified, and two of those classifications are easy to get silently wrong:
 *
 * <ul>
 *   <li><b>200 vs 201.</b> revenue-ledger's four endpoints answer {@code 201} when they create the posting and
 *       {@code 200}/{@code 204} when their idempotency key already has it. Treating them identically would still
 *       clear the row (both mean the revenue is on the books) but would lose the only signal that says the money
 *       path had already succeeded and this row was stale — see
 *       {@link #twoHundredMeansTheLedgerAlreadyHadIt()};</li>
 *   <li><b>4xx vs 5xx.</b> A body revenue-ledger has judged invalid will be judged invalid again, so retrying it
 *       eight times only delays the alert. A 5xx, 408 or 429 is the opposite.</li>
 * </ul>
 *
 * <p>Also pinned: the STORED payload goes on the wire byte-for-byte. The payload captured at failure time is what
 * the money path actually tried to send; re-serialising it could replay something subtly different from what was
 * lost.
 */
class RestRevenuePostingReplayClientTest {

    private static final String PAYLOAD = "{\"txnRef\":\"TXN-1\",\"payoutMarginUsd\":\"1.2500\"}";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private RestRevenuePostingReplayClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl("http://revenue-ledger:8080");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestRevenuePostingReplayClient(builder.build());
    }

    @Test
    @DisplayName("201 means the posting was CREATED — the revenue reached the ledger on this replay")
    void twoHundredOneMeansPosted() {
        server.expect(requestTo("http://revenue-ledger:8080/v1/revenue/capture"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                // The stored JSON is sent verbatim, not re-serialised.
                .andExpect(content().string(PAYLOAD))
                .andRespond(withStatus(HttpStatus.CREATED));

        RevenuePostingReplayOutcome outcome =
                client.replay(RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE, PAYLOAD);

        assertThat(outcome.kind()).isEqualTo(RevenuePostingReplayOutcome.Kind.POSTED);
        assertThat(outcome.landed()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("200 means revenue-ledger ALREADY had it — idempotent no-op, still 'landed'")
    void twoHundredMeansTheLedgerAlreadyHadIt() {
        server.expect(requestTo("http://revenue-ledger:8080/v1/journals/rounding-residual"))
                .andRespond(withSuccess());

        RevenuePostingReplayOutcome outcome =
                client.replay(RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL, PAYLOAD);

        assertThat(outcome.kind()).isEqualTo(RevenuePostingReplayOutcome.Kind.ALREADY_PRESENT);
        assertThat(outcome.landed())
                .as("the goal is 'the posting is in the ledger', not 'this job put it there'")
                .isTrue();
        assertThat(outcome.detail()).contains("idempotent no-op");
        server.verify();
    }

    @Test
    @DisplayName("204 (a zero-amount no-op) also counts as already present, not as a failure")
    void twoHundredFourAlsoCountsAsPresent() {
        server.expect(requestTo("http://revenue-ledger:8080/v1/journals/reversal"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertThat(client.replay(RevenuePostingFailureStore.TYPE_REVERSAL_JOURNAL, PAYLOAD).landed())
                .isTrue();
    }

    @Test
    @DisplayName("5xx is TRANSIENT — worth backing off and trying again")
    void serverErrorIsTransient() {
        server.expect(requestTo("http://revenue-ledger:8080/v1/revenue/commission-split"))
                .andRespond(withServerError());

        RevenuePostingReplayOutcome outcome =
                client.replay(RevenuePostingFailureStore.TYPE_COMMISSION_SPLIT, PAYLOAD);

        assertThat(outcome.kind()).isEqualTo(RevenuePostingReplayOutcome.Kind.TRANSIENT_FAILURE);
        assertThat(outcome.permanent()).isFalse();
        assertThat(outcome.status()).isEqualTo(500);
    }

    @Test
    @DisplayName("400 is PERMANENT — the same body will be rejected identically every time")
    void badRequestIsPermanent() {
        server.expect(requestTo("http://revenue-ledger:8080/v1/revenue/capture"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error_code\":\"INVALID_REVENUE_DATE\"}"));

        RevenuePostingReplayOutcome outcome =
                client.replay(RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE, PAYLOAD);

        assertThat(outcome.kind()).isEqualTo(RevenuePostingReplayOutcome.Kind.PERMANENT_REJECTION);
        assertThat(outcome.permanent()).isTrue();
        assertThat(outcome.detail())
                .as("the operator needs the ledger's own reason, not just a status code")
                .contains("INVALID_REVENUE_DATE");
    }

    @Test
    @DisplayName("429 and 408 are the two 4xx answers that DO mean 'come back later'")
    void rateLimitAndTimeoutAreTransient() {
        server.expect(requestTo("http://revenue-ledger:8080/v1/revenue/capture"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(client.replay(RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE, PAYLOAD).kind())
                .isEqualTo(RevenuePostingReplayOutcome.Kind.TRANSIENT_FAILURE);

        setUp();
        server.expect(requestTo("http://revenue-ledger:8080/v1/revenue/capture"))
                .andRespond(withStatus(HttpStatus.REQUEST_TIMEOUT));
        assertThat(client.replay(RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE, PAYLOAD).kind())
                .isEqualTo(RevenuePostingReplayOutcome.Kind.TRANSIENT_FAILURE);
    }

    @Test
    @DisplayName("an unknown posting type or a missing payload is UNREPLAYABLE and makes no HTTP call")
    void unreplayableRowsMakeNoCall() {
        // No server.expect() at all: any HTTP call would fail verify().
        assertThat(client.replay("SOME_FUTURE_TYPE", PAYLOAD).kind())
                .isEqualTo(RevenuePostingReplayOutcome.Kind.UNREPLAYABLE);
        assertThat(client.replay(RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE, null).kind())
                .isEqualTo(RevenuePostingReplayOutcome.Kind.UNREPLAYABLE);
        assertThat(client.replay(RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE, "  ").kind())
                .isEqualTo(RevenuePostingReplayOutcome.Kind.UNREPLAYABLE);
        server.verify();
    }

    @Test
    @DisplayName("the client never throws — the replay service needs a decision, not an exception")
    void theClientNeverThrows() {
        server.expect(requestTo("http://revenue-ledger:8080/v1/revenue/capture"))
                .andRespond(request -> {
                    throw new java.net.SocketTimeoutException("read timed out");
                });

        RevenuePostingReplayOutcome outcome =
                client.replay(RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE, PAYLOAD);

        assertThat(outcome.kind()).isEqualTo(RevenuePostingReplayOutcome.Kind.TRANSIENT_FAILURE);
        assertThat(outcome.status()).as("0 = no HTTP status was ever received").isZero();
        assertThat(outcome.detail()).contains("read timed out");
    }
}
