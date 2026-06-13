package com.gme.pay.registry.kyb;

import com.gme.pay.kyb.KybSubject;
import com.gme.pay.kyb.ScreeningResult;
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
 * Production {@link KybScreeningClient}: calls the kyb-adapter service's
 * {@code POST /v1/kyb/screen} via Spring 6 {@link RestClient}. Active when
 * {@code gmepay.kyb-adapter.client=rest}; base URL from
 * {@code gmepay.kyb-adapter.base-url} (default the compose-internal
 * {@code http://kyb-adapter:8080}) — the same conditional/@Primary wiring
 * pattern as the BFF's {@code RestConfigRegistryClient}.
 *
 * <h2>Failure mapping</h2>
 *
 * <ul>
 *   <li>upstream 4xx → re-thrown with status + message preserved (a 400 from
 *       the adapter is a caller bug worth surfacing verbatim);</li>
 *   <li>network failure / 5xx → 502 Bad Gateway: a screening run is an
 *       explicit operator action, so unlike read paths there is no graceful
 *       null — the operator must know the run did not happen.</li>
 * </ul>
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.kyb-adapter.client", havingValue = "rest")
public class RestKybClient implements KybScreeningClient {

    private final RestClient restClient;

    @Autowired
    public RestKybClient(
            @Value("${gmepay.kyb-adapter.base-url:http://kyb-adapter:8080}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).build());
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestKybClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public ScreeningResult screen(KybSubject subject) {
        try {
            return restClient.post()
                    .uri("/v1/kyb/screen")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(subject)
                    .retrieve()
                    .body(ScreeningResult.class);
        } catch (RestClientResponseException e) {
            throw new ResponseStatusException(e.getStatusCode(),
                    "kyb-adapter rejected the screening request: " + e.getResponseBodyAsString());
        } catch (ResourceAccessException network) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "kyb-adapter unreachable: " + network.getMessage());
        }
    }
}
