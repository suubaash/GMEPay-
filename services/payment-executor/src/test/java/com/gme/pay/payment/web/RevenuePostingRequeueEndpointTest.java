package com.gme.pay.payment.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.internalauth.InternalAuthHeaders;
import com.gme.pay.payment.persistence.RevenuePostingFailureEntity;
import com.gme.pay.payment.persistence.RevenuePostingFailureRepository;
import com.gme.pay.payment.persistence.RevenuePostingFailureStore;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * {@code POST /internal/ops/revenue-posting-failures/requeue} over real HTTP — the perimeter and the contract
 * (gap <b>T2-5</b> follow-up, opened by <b>T3-12</b>).
 *
 * <p>Why an HTTP-level test and not only the service test: the two things that can be wrong here are wrong
 * <em>above</em> the service. The endpoint moves POISON rows carrying real transaction references and money
 * amounts back into an automated re-send queue, so an ungated route would be a way to re-drive money postings
 * anonymously — and the gate is a servlet filter that a slice test never runs. And the JSON binding of the
 * selectors is what decides whether a caller who meant "these two types" gets them.
 *
 * <p>The internal-auth secret is set, which is what arms the {@code /internal/**} gate in
 * {@code SandboxSurfaceInternalAuthConfig}. (With no secret configured every caller is refused 401 anyway —
 * a blank configured secret can never equal a presented one — so this is the permissive case, not the strict
 * one.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gmepay.internal-auth.secret=" + RevenuePostingRequeueEndpointTest.SECRET)
class RevenuePostingRequeueEndpointTest {

    static final String SECRET = "test-fixture-internal-token-not-a-deployment-secret";

    private static final String PATH = "/internal/ops/revenue-posting-failures/requeue";
    private static final String PAYLOAD =
            "{\"reference\":\"TXN-406\",\"residual\":\"0.4000\",\"currency\":\"KRW\"}";

    @Autowired private TestRestTemplate rest;
    @Autowired private RevenuePostingFailureRepository failures;

    @BeforeEach
    void seedOnePoisonedRow() {
        // JdkClientHttpRequestFactory: the default HttpURLConnection factory cannot send a body on some
        // verbs and mangles 4xx bodies, which would make the 400 assertions below unreadable.
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        failures.deleteAll();
        RevenuePostingFailureEntity row = new RevenuePostingFailureEntity("TXN-406",
                RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL, PAYLOAD, "connect refused",
                Instant.parse("2026-07-28T09:00:00Z"));
        // Exactly the state the T3-12 defect left every residual in: POISON on the first sweep, HTTP 406.
        row.poison("unreplayable: HTTP 406 (no body)", Instant.parse("2026-07-28T09:00:00Z"), true);
        failures.save(row);
    }

    private ResponseEntity<String> post(String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Operator-Id", "ops.kim");
        if (token != null) {
            headers.set(InternalAuthHeaders.INTERNAL_TOKEN, token);
        }
        return rest.exchange(PATH, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    @DisplayName("no internal token → 401, and the poisoned row is untouched")
    void requeueIsRefusedWithoutTheInternalToken() {
        ResponseEntity<String> res = post(
                "{\"postingTypes\":[\"ROUNDING_RESIDUAL\"],\"reason\":\"406 fixed\"}", null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(currentStatus())
                .as("an unauthenticated caller must not be able to re-arm a money posting")
                .isEqualTo(RevenuePostingFailureEntity.STATUS_POISON);
    }

    @Test
    @DisplayName("wrong internal token → 401")
    void requeueIsRefusedWithAWrongToken() {
        assertThat(post("{\"postingTypes\":[\"ROUNDING_RESIDUAL\"]}", SECRET + "-tampered").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(currentStatus()).isEqualTo(RevenuePostingFailureEntity.STATUS_POISON);
    }

    @Test
    @DisplayName("the immediate T3-12 case: the two journal types requeue to PENDING with attempts=0")
    void requeueByPostingTypeMovesPoisonToPending() {
        ResponseEntity<String> res = post(
                "{\"postingTypes\":[\"ROUNDING_RESIDUAL\",\"REVERSAL_JOURNAL\"],"
                        + "\"reason\":\"revenue-ledger 406 fixed (T3-12)\"}", SECRET);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"requeued\":1");

        RevenuePostingFailureEntity after = failures.findByReferenceAndPostingType("TXN-406",
                RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RevenuePostingFailureEntity.STATUS_PENDING);
        assertThat(after.getAttempts()).isZero();
        assertThat(after.getNextAttemptAt()).isNotNull();
    }

    @Test
    @DisplayName("calling it twice is safe: the second call requeues 0 and still answers 200")
    void requeueOverHttpIsIdempotent() {
        String body = "{\"postingTypes\":[\"ROUNDING_RESIDUAL\"],\"reason\":\"406 fixed\"}";

        assertThat(post(body, SECRET).getBody()).contains("\"requeued\":1");

        ResponseEntity<String> second = post(body, SECRET);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody())
                .as("a repeat must be a no-op, not an error and not a second reset")
                .contains("\"requeued\":0");
    }

    @Test
    @DisplayName("an empty body is 400, not 'requeue everything'")
    void anUnfilteredRequeueIs400() {
        ResponseEntity<String> res = post("{}", SECRET);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("REQUEUE_NO_SELECTOR");
        assertThat(currentStatus()).isEqualTo(RevenuePostingFailureEntity.STATUS_POISON);
    }

    @Test
    @DisplayName("a mistyped posting type is 400 — never a silent 'requeued=0'")
    void unknownPostingTypeIs400() {
        ResponseEntity<String> res = post("{\"postingTypes\":[\"RESIDUAL\"]}", SECRET);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("REQUEUE_UNKNOWN_POSTING_TYPE");
    }

    @Test
    @DisplayName("the id selector works over the wire too")
    void requeueByIdWorks() {
        Long id = failures.findByReferenceAndPostingType("TXN-406",
                RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL).orElseThrow().getId();

        assertThat(post("{\"ids\":[" + id + "],\"reason\":\"one straggler\"}", SECRET).getBody())
                .contains("\"requeued\":1");
        assertThat(currentStatus()).isEqualTo(RevenuePostingFailureEntity.STATUS_PENDING);
    }

    private String currentStatus() {
        return failures.findByReferenceAndPostingType("TXN-406",
                RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL).orElseThrow().getStatus();
    }
}
