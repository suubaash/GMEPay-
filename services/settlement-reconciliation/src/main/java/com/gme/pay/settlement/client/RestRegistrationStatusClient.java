package com.gme.pay.settlement.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.internalauth.InternalAuthHeaders;
import com.gme.pay.settlement.port.RegistrationStatusPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Production {@link RegistrationStatusPort}: consults scheme-adapter-zeropay's
 * {@code GET /internal/scheme/zeropay/registration-status?businessDate=} (the projection over its
 * {@code zp_batch_files} lifecycle rows). Active when
 * {@code gmepay.clients.scheme-adapter-zeropay.enabled=true}; otherwise the permissive
 * in-process adapter applies (dev/test keep generating without a live adapter).
 *
 * <p><b>Namespace note:</b> this class used to read {@code settlement.clients.scheme-adapter-zeropay.*}
 * — a namespace no config file in the repo declares and every sibling client here spells
 * {@code gmepay.clients.*}. The effect was that {@code SCHEME_ADAPTER_ZEROPAY_ENABLED=true} (set in
 * {@code docker-compose.yml} and Helm precisely to arm this gate) resolved a property nothing read,
 * so {@link PermissiveRegistrationStatusAdapter} won in every environment and the §8.2 prerequisite
 * was inert wherever it mattered. Aligned to {@code gmepay.clients.*} so the shipped env vars work.
 *
 * <p><b>Fail-CLOSED:</b> if the adapter is unreachable or errors, registration status is UNKNOWN —
 * this returns {@code (false, false)} so the prerequisite gate blocks the settlement request
 * rather than asking the scheme to settle payments whose registration was never confirmed.
 *
 * <p><b>Internal auth (T0-2):</b> the adapter's whole {@code /internal/**} surface — this
 * projection included — now sits behind the service-to-service internal-auth gate
 * ({@code com.gme.pay.internalauth}), so settlement-reconciliation presents the shared secret from
 * {@code gmepay.internal-auth.secret} in the {@code X-Gme-Internal} header. Without it every call
 * 401s; combined with the fail-CLOSED policy above the visible symptom is <b>settlement generation
 * permanently blocked</b>, not a wrong answer. A blank secret sends no header and logs a WARN —
 * fail-closed, never a fabricated credential.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.clients.scheme-adapter-zeropay.enabled", havingValue = "true")
public class RestRegistrationStatusClient implements RegistrationStatusPort {

    private static final Logger log = LoggerFactory.getLogger(RestRegistrationStatusClient.class);

    private final RestClient restClient;

    @Autowired
    public RestRegistrationStatusClient(
            RestClient.Builder builder,
            @Value("${gmepay.clients.scheme-adapter-zeropay.base-url:http://scheme-adapter-zeropay:8080}")
            String baseUrl,
            @Value("${gmepay.internal-auth.secret:}") String internalSecret) {
        this(builderFor(builder, baseUrl, internalSecret).build());
    }

    /**
     * Builds the {@link RestClient.Builder} the production constructor uses: base URL plus, when a
     * secret is configured, the {@code X-Gme-Internal} default header. Package-private so a test can
     * bind a {@code MockRestServiceServer} to the very same builder and assert the header really
     * goes on the wire instead of trusting a hand-built client.
     *
     * <p><b>T3-11:</b> the builder is a parameter, and in production it is the <b>injected
     * {@code RestClient.Builder} bean</b>. It used to be the static {@code RestClient.builder()}
     * factory called right here, which returns a fresh builder that no {@code RestClientCustomizer}
     * has touched — so {@code HttpClientTimeoutAutoConfiguration}'s connect/read floor never reached
     * this client and it had no read timeout at all. This probe is <b>fail-CLOSED</b>: an unbounded
     * read did not block settlement generation with a visible 401-shaped symptom, it simply never
     * returned, holding the generation window's scheduler thread. Deliberately no
     * {@code .requestFactory(..)} call here — a test binds {@code MockRestServiceServer} to this very
     * builder, and that binding works by installing a request factory, so overwriting the factory
     * afterwards would silently detach the test from its mock and open real sockets.
     */
    static RestClient.Builder builderFor(
            RestClient.Builder builder, String baseUrl, String internalSecret) {
        RestClient.Builder b = builder.baseUrl(baseUrl);
        if (internalSecret != null && !internalSecret.isBlank()) {
            b.defaultHeader(InternalAuthHeaders.INTERNAL_TOKEN, internalSecret);
        } else {
            log.warn("gmepay.internal-auth.secret is blank — the registration-status probe will carry "
                    + "no {} header and a gated scheme-adapter-zeropay will refuse it (401), which "
                    + "fails CLOSED and blocks settlement generation. Set GMEPAY_INTERNAL_AUTH_SECRET.",
                    InternalAuthHeaders.INTERNAL_TOKEN);
        }
        return b;
    }

    /** Package-private for tests to inject a pre-built RestClient. */
    RestRegistrationStatusClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public RegistrationStatus statusFor(LocalDate businessDate) {
        try {
            WireStatus wire = restClient.get()
                    .uri("/internal/scheme/zeropay/registration-status?businessDate={d}",
                            businessDate.format(DateTimeFormatter.ISO_LOCAL_DATE))
                    .retrieve()
                    .body(WireStatus.class);
            if (wire == null) {
                log.warn("registration-status returned empty body for {} — failing CLOSED", businessDate);
                return new RegistrationStatus(false, false);
            }
            return new RegistrationStatus(wire.zp0011Succeeded(), wire.zp0012Received());
        } catch (RestClientException e) {
            log.warn("registration-status unreachable for {} — failing CLOSED (settlement blocked): {}",
                    businessDate, e.getMessage());
            return new RegistrationStatus(false, false);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WireStatus(boolean zp0011Succeeded, boolean zp0012Received) {}
}
