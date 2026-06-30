package com.gme.pay.registry.prefunding.push;

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
 * Production {@link PrefundingCreditLimitClient}: pushes a partner's credit
 * line + AML caps to prefunding's {@code PUT
 * /internal/v1/prefunding/{partnerId}/credit-limit} via Spring 6
 * {@link RestClient}. Active when {@code gmepay.prefunding.client=rest}; base
 * URL from {@code gmepay.prefunding.base-url} (default the compose-internal
 * {@code http://prefunding:8080}) — the same conditional/@Primary wiring
 * pattern as {@code RestKybClient} / {@code RestNotificationWebhookClient}.
 *
 * <p>Spring 6 two-constructor trap (the {@code RestNotificationWebhookClient}
 * lesson): the {@code @Value} constructor MUST carry {@code @Autowired} or
 * context startup fails "ambiguous constructor".
 *
 * <h2>Failure mapping</h2>
 *
 * <ul>
 *   <li>upstream 4xx — re-thrown with status + body preserved (a 400 is a
 *       caller bug worth surfacing verbatim);</li>
 *   <li>network failure / 5xx — 502 Bad Gateway. The push is an explicit
 *       config write side-effect, so the operator must know it did not land
 *       (no silent swallow at the transport layer — the caller decides whether
 *       to roll its own write back).</li>
 * </ul>
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.prefunding.client", havingValue = "rest")
public class RestPrefundingCreditLimitClient implements PrefundingCreditLimitClient {

    private final RestClient restClient;

    @Autowired
    public RestPrefundingCreditLimitClient(
            @Value("${gmepay.prefunding.base-url:http://prefunding:8080}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).build());
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestPrefundingCreditLimitClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void pushCreditLimit(String partnerCode, CreditLimitPushCommand command) {
        try {
            restClient.put()
                    .uri("/internal/v1/prefunding/{partnerId}/credit-limit", partnerCode)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(command)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new ResponseStatusException(e.getStatusCode(),
                    "prefunding rejected the credit-limit push: " + e.getResponseBodyAsString());
        } catch (ResourceAccessException network) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "prefunding unreachable: " + network.getMessage());
        }
    }
}
