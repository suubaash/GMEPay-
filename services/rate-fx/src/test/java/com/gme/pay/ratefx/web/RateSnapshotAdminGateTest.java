package com.gme.pay.ratefx.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.internalauth.InternalAuthHeaders;
import com.gme.pay.ratefx.testsupport.TestInternalAuth;
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
import org.springframework.test.context.ActiveProfiles;

/**
 * T0-2 — proves the treasury-rate override surface is gated and the public partner surface is not.
 *
 * <p>{@code POST /v1/rates/snapshots} appends an effective-dated snapshot and the latest one wins at
 * resolution time, so an anonymous write re-prices every subsequent quote and payment in that
 * currency. It was unauthenticated, and the api-gateway does not front it either (the gateway route
 * is the exact path {@code /v1/rates}).
 *
 * <p>The second half of this test is as important as the first: gating must NOT catch
 * {@code POST /v1/rates} or {@code /v1/quotes/**}, which are the legitimate, gateway-authenticated
 * partner surface. An over-broad pattern there would decline partner traffic.
 *
 * <p>Real port + {@link TestRestTemplate}, i.e. the real servlet filter chain.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RateSnapshotAdminGateTest {

    private static final String SNAPSHOT_BODY =
            "{\"currencyCode\":\"NPR\",\"usdRate\":\"133.50\",\"source\":\"MANUAL\"}";

    @Autowired private TestRestTemplate rest;

    @BeforeEach
    void useJdkClient() {
        // SimpleClientHttpRequestFactory throws HttpRetryException instead of surfacing a 401 whose
        // request carried a body — exactly the case under test.
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @Test
    @DisplayName("anonymous rate override → 401, and nothing is persisted")
    void anonymousOverrideIsRefused() {
        ResponseEntity<String> res =
                exchange(HttpMethod.POST, "/v1/rates/snapshots", SNAPSHOT_BODY, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).contains("UNAUTHORIZED").doesNotContain("snapshotId");
    }

    @Test
    @DisplayName("wrong / blank credential → 401")
    void wrongCredentialIsRefused() {
        assertThat(exchange(HttpMethod.POST, "/v1/rates/snapshots", SNAPSHOT_BODY,
                TestInternalAuth.SECRET + "-tampered").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange(HttpMethod.POST, "/v1/rates/snapshots", SNAPSHOT_BODY, "  ")
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("trusted operator caller → 201 CREATED with the persisted snapshot")
    void trustedCallerCanRecordASnapshot() {
        ResponseEntity<String> res = exchange(HttpMethod.POST, "/v1/rates/snapshots", SNAPSHOT_BODY,
                TestInternalAuth.SECRET);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).contains("snapshotId").contains("NPR");
    }

    @Test
    @DisplayName("the PUBLIC partner surface is NOT swept into the gate (no 401 without a token)")
    void publicSurfaceStaysOpen() {
        // POST /v1/rates — the stateless calculator, authenticated at the api-gateway.
        assertThat(exchange(HttpMethod.POST, "/v1/rates",
                "{\"fromCurrency\":\"USD\",\"toCurrency\":\"NPR\",\"amount\":\"100\"}", null)
                .getStatusCode())
                .as("POST /v1/rates must not be caught by the internal-auth gate")
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);

        // GET /v1/quotes/{id} — an unknown id may 404, but must not 401.
        assertThat(exchange(HttpMethod.GET, "/v1/quotes/does-not-exist", null, null).getStatusCode())
                .as("/v1/quotes/** must not be caught by the internal-auth gate")
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);

        // Container probes stay anonymous. (Asserted as "not 401" rather than "200": the aggregate
        // health status in this test context can legitimately be 503 — e.g. a component reporting
        // DOWN without Redis — and what matters here is that the probe is not behind the gate.)
        assertThat(exchange(HttpMethod.GET, "/actuator/health", null, null).getStatusCode())
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("introspection surfaces are gated (/actuator/metrics, /v3/api-docs)")
    void introspectionIsGated() {
        assertThat(exchange(HttpMethod.GET, "/actuator/metrics", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange(HttpMethod.GET, "/v3/api-docs", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange(HttpMethod.GET, "/v3/api-docs", null, TestInternalAuth.SECRET)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set(InternalAuthHeaders.INTERNAL_TOKEN, token);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }
}
