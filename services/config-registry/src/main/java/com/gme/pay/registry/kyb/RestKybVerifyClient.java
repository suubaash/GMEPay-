package com.gme.pay.registry.kyb;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Production {@link KybVerifyClient}: calls kyb-adapter's
 * {@code POST /v1/kyb/verify} via Spring 6 {@link RestClient}. Active when
 * {@code gmepay.kyb-adapter.client=rest}; base URL from
 * {@code gmepay.kyb-adapter.base-url} (default the compose-internal
 * {@code http://kyb-adapter:8080}) — the same conditional/@Primary wiring as
 * {@link RestKybClient} (the screen seam), so a single property flips BOTH the
 * screen and verify transports together.
 *
 * <p>Two-constructor {@code @Autowired} trap (the {@code RestKybClient} lesson):
 * the {@code @Value} constructor MUST carry {@code @Autowired}.
 *
 * <h2>Failure mapping</h2>
 *
 * <ul>
 *   <li>upstream 4xx → re-thrown with status + body preserved (a bad subject is
 *       a caller bug worth surfacing verbatim);</li>
 *   <li>network failure / 5xx → 502 Bad Gateway: a verify run is an explicit
 *       operator action, so there is no graceful null — the operator must know
 *       the run did not happen.</li>
 * </ul>
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.kyb-adapter.client", havingValue = "rest")
public class RestKybVerifyClient implements KybVerifyClient {

    private final RestClient restClient;

    @Autowired
    public RestKybVerifyClient(
            @Value("${gmepay.kyb-adapter.base-url:http://kyb-adapter:8080}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).build());
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestKybVerifyClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public KybVerificationResult verify(KybVerificationRequest request) {
        try {
            return restClient.post()
                    .uri("/v1/kyb/verify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(KybVerificationResult.class);
        } catch (RestClientResponseException e) {
            throw new ResponseStatusException(e.getStatusCode(),
                    "kyb-adapter rejected the verification request: " + e.getResponseBodyAsString());
        } catch (ResourceAccessException network) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "kyb-adapter unreachable: " + network.getMessage());
        }
    }
}
