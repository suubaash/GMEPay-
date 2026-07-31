package com.gme.pay.registry.prefunding.push;

import com.gme.pay.internalauth.InternalAuthHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p><b>Internal auth (T0-5 / T0-2):</b> prefunding's entire balance API is behind the
 * service-to-service internal-auth gate ({@code com.gme.pay.internalauth}), so config-registry — a
 * trusted in-cluster caller — presents the shared secret from {@code gmepay.internal-auth.secret} in
 * the {@code X-Gme-Internal} header. Without this the credit-limit push 401s against any correctly
 * deployed prefunding. A blank secret sends no header (local dev against an ungated stub); against a
 * gated prefunding that yields 401, which is the intended fail-closed outcome of a missing
 * {@code GMEPAY_INTERNAL_AUTH_SECRET} rather than a silent bypass.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.prefunding.client", havingValue = "rest")
public class RestPrefundingCreditLimitClient implements PrefundingCreditLimitClient {

    private static final Logger log = LoggerFactory.getLogger(RestPrefundingCreditLimitClient.class);

    private final RestClient restClient;

    @Autowired
    public RestPrefundingCreditLimitClient(
            @Value("${gmepay.prefunding.base-url:http://prefunding:8080}") String baseUrl,
            @Value("${gmepay.internal-auth.secret:}") String internalSecret) {
        this(builderFor(baseUrl, internalSecret).build());
    }

    /**
     * Builds the {@link RestClient.Builder} the production constructor uses: base URL plus, when a
     * secret is configured, the {@code X-Gme-Internal} default header. Package-private so a test can
     * bind a {@code MockRestServiceServer} to the very same builder and assert the header really
     * goes on the wire (rather than trusting a hand-built client).
     */
    static RestClient.Builder builderFor(String baseUrl, String internalSecret) {
        RestClient.Builder b = RestClient.builder().baseUrl(baseUrl);
        if (internalSecret != null && !internalSecret.isBlank()) {
            b.defaultHeader(InternalAuthHeaders.INTERNAL_TOKEN, internalSecret);
        } else {
            log.warn("gmepay.internal-auth.secret is blank — the credit-limit push to prefunding will "
                    + "carry no {} header and a gated prefunding will refuse it (401). Set "
                    + "GMEPAY_INTERNAL_AUTH_SECRET.", InternalAuthHeaders.INTERNAL_TOKEN);
        }
        return b;
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
