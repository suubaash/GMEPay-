package com.gme.pay.payment.client.rest;

import com.gme.pay.contracts.SchemeOperatingHoursView;
import com.gme.pay.payment.domain.SchemeId;
import com.gme.pay.payment.domain.client.SchemeOperatingHoursClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST {@link SchemeOperatingHoursClient} reading config-registry's V024 reference table:
 * <pre>GET {config-registry}/v1/schemes/{schemeId}/operating-hours → List&lt;SchemeOperatingHoursView&gt;</pre>
 *
 * <p>Gap <b>T3-6</b>: this adapter is the missing consumer. V024 was seeded and readable but nothing on
 * the payment path had ever called it.
 *
 * <h2>Cache: long, because this is migration-seeded reference data</h2>
 * The rows change only when a new Flyway migration ships, so the per-scheme cache TTL defaults to
 * {@code gmepay.scheme-hours.cache-ttl-millis} = 10 minutes — vastly longer than the operational-status
 * kill switch's 3s, because a schedule is not a kill switch and a payment must not pay for a
 * config-registry round trip on every scan. A deploy of new hours takes effect within the TTL.
 *
 * <h2>Hard timeouts + fail-to-UNVERIFIED (never fail-closed, never a wrong "open")</h2>
 * Connect/read timeouts default to 500ms each (the same discipline as
 * {@link RestOperationalStatusClient}) so a hung config-registry cannot stall authorization. On any
 * failure the last-known-good rows are served if we have them; otherwise an EMPTY list is returned,
 * which {@code SchemeAvailability.evaluate} answers as UNVERIFIED — the gate then PERMITS the payment
 * and raises an ops alert.
 *
 * <p>That fail direction is the opposite of the operational-status client's, deliberately: a suspension
 * list is a security kill switch (fail CLOSED, deny), whereas an operating window is availability
 * reference data. Blocking a live corridor because a reference table could not be read would convert a
 * config-registry blip into a corridor outage; the honest degradation is "we could not verify the
 * window, and we said so loudly".
 *
 * <p>A 404 (scheme outside the V022 roster) is cached as an empty list too, so a stray/unknown scheme
 * code does not re-hit config-registry on every payment.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "gmepay.config-registry", name = "base-url")
public class RestSchemeOperatingHoursClient implements SchemeOperatingHoursClient {

    private static final Logger log = LoggerFactory.getLogger(RestSchemeOperatingHoursClient.class);

    private static final ParameterizedTypeReference<List<SchemeOperatingHoursView>> ROWS =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final long cacheTtlMillis;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public RestSchemeOperatingHoursClient(
            RestClient.Builder builder,
            @Value("${gmepay.config-registry.base-url}") String baseUrl,
            @Value("${gmepay.scheme-hours.cache-ttl-millis:600000}") long cacheTtlMillis,
            @Value("${gmepay.scheme-hours.connect-timeout-millis:500}") long connectTimeoutMillis,
            @Value("${gmepay.scheme-hours.read-timeout-millis:500}") long readTimeoutMillis) {
        ClientHttpRequestFactorySettings timeouts = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .withReadTimeout(Duration.ofMillis(readTimeoutMillis));
        this.restClient = RestClientSupport.withJavaTime(builder.baseUrl(baseUrl))
                .requestFactory(ClientHttpRequestFactories.get(timeouts))
                .build();
        this.cacheTtlMillis = cacheTtlMillis;
    }

    /** Test constructor — pre-built RestClient + explicit TTL. */
    RestSchemeOperatingHoursClient(RestClient restClient, long cacheTtlMillis) {
        this.restClient = restClient;
        this.cacheTtlMillis = cacheTtlMillis;
    }

    @Override
    public List<SchemeOperatingHoursView> weeklySchedule(String schemeId) {
        String roster = SchemeId.canonicalCode(schemeId);
        if (roster == null) {
            // Not a platform scheme code at all — nothing to read. UNVERIFIED, not an error.
            return List.of();
        }
        long now = System.currentTimeMillis();
        Cached hit = cache.get(roster);
        if (hit != null && now - hit.fetchedAtMillis < cacheTtlMillis) {
            return hit.rows;
        }
        try {
            List<SchemeOperatingHoursView> fresh = restClient.get()
                    .uri("/v1/schemes/{schemeId}/operating-hours", roster)
                    .retrieve()
                    .body(ROWS);
            List<SchemeOperatingHoursView> rows = fresh == null ? List.of() : List.copyOf(fresh);
            cache.put(roster, new Cached(rows, now));
            return rows;
        } catch (org.springframework.web.client.RestClientResponseException http) {
            if (http.getStatusCode().value() == 404) {
                // Scheme not in the V022 roster. A definitive "no schedule" — cache it so an unknown
                // code does not re-hit config-registry on every payment.
                log.warn("config-registry has no scheme '{}' — operating window UNVERIFIED", roster);
                cache.put(roster, new Cached(List.of(), now));
                return List.of();
            }
            return degraded(roster, hit, http.getStatusCode() + " " + http.getMessage());
        } catch (RuntimeException ex) {
            return degraded(roster, hit, ex.getMessage());
        }
    }

    /** Last-known-good if we have any, else empty ⇒ UNVERIFIED (permitted + alerted, never a wrong OPEN). */
    private List<SchemeOperatingHoursView> degraded(String roster, Cached hit, String cause) {
        if (hit != null) {
            log.warn("scheme operating hours unreachable for {} ({}) — serving last-known-good",
                    roster, cause);
            return hit.rows;
        }
        log.warn("scheme operating hours unreadable for {} ({}) — window UNVERIFIED", roster, cause);
        return List.of();
    }

    private record Cached(List<SchemeOperatingHoursView> rows, long fetchedAtMillis) {
    }
}
