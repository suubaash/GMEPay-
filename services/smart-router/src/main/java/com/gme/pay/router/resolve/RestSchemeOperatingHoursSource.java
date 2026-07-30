package com.gme.pay.router.resolve;

import com.gme.pay.contracts.SchemeOperatingHoursView;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Production {@link SchemeOperatingHoursSource}: reads config-registry's V024 reference table over HTTP
 * — {@code GET /v1/schemes/{schemeId}/operating-hours}. Gap <b>T3-6</b>.
 *
 * <p>Gated by {@code gmepay.config-registry.enabled=true} and configured by
 * {@code gmepay.config-registry.base-url}, the same two properties and the same two-constructor
 * {@code @Autowired} trap as {@link RestPartnerSchemeRegistry}; when unset,
 * {@link UnverifiedSchemeOperatingHoursSource} stays in place and resolution never reaches the network.
 *
 * <h2>Cached, and NEVER a failure</h2>
 * The rows are migration-seeded reference data, so they are cached per scheme for
 * {@code gmepay.scheme-hours.cache-ttl-millis} (default 10 minutes) — a resolution on the pay path must
 * not pay for a round trip per scheme per request.
 *
 * <p>Unlike {@link RestPartnerSchemeRegistry}, an upstream failure here is deliberately NOT mapped to
 * {@code SCHEME_UNAVAILABLE}: the partner registry IS the resolution (no rows, no answer), whereas the
 * schedule only NARROWS an answer we already have. Failing resolution because a reference table could
 * not be read would convert a config-registry blip into a corridor outage, so a failure degrades to an
 * empty list ⇒ {@code UNVERIFIED} ⇒ the candidate is kept and the fact is logged. The one thing that
 * never happens is a silent "assume open".
 */
@Component
@ConditionalOnProperty(name = "gmepay.config-registry.enabled", havingValue = "true")
public class RestSchemeOperatingHoursSource implements SchemeOperatingHoursSource {

    private static final Logger log = LoggerFactory.getLogger(RestSchemeOperatingHoursSource.class);

    private static final ParameterizedTypeReference<List<SchemeOperatingHoursView>> ROWS =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final long cacheTtlMillis;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    // Spring 6 trap: with 2 constructors the @Value one MUST carry @Autowired.
    @Autowired
    public RestSchemeOperatingHoursSource(
            @Value("${gmepay.config-registry.base-url:http://config-registry:8080}") String baseUrl,
            @Value("${gmepay.scheme-hours.cache-ttl-millis:600000}") long cacheTtlMillis) {
        this(RestClient.builder().baseUrl(baseUrl).build(), cacheTtlMillis);
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestSchemeOperatingHoursSource(RestClient restClient, long cacheTtlMillis) {
        this.restClient = restClient;
        this.cacheTtlMillis = cacheTtlMillis;
    }

    @Override
    public List<SchemeOperatingHoursView> weeklySchedule(String schemeId) {
        if (schemeId == null || schemeId.isBlank()) {
            return List.of();
        }
        String scheme = schemeId.trim();
        long now = System.currentTimeMillis();
        Cached hit = cache.get(scheme);
        if (hit != null && now - hit.fetchedAtMillis < cacheTtlMillis) {
            return hit.rows;
        }
        try {
            List<SchemeOperatingHoursView> fresh = restClient.get()
                    .uri("/v1/schemes/{schemeId}/operating-hours", scheme)
                    .retrieve()
                    .body(ROWS);
            List<SchemeOperatingHoursView> rows = fresh == null ? List.of() : List.copyOf(fresh);
            cache.put(scheme, new Cached(rows, now));
            return rows;
        } catch (RestClientException upstream) {
            if (hit != null) {
                log.warn("operating hours for {} unreachable ({}) — last-known-good",
                        scheme, upstream.getMessage());
                return hit.rows;
            }
            log.warn("operating hours for {} unreadable ({}) — window UNVERIFIED, candidate kept",
                    scheme, upstream.getMessage());
            return List.of();
        }
    }

    private record Cached(List<SchemeOperatingHoursView> rows, long fetchedAtMillis) {
    }
}
