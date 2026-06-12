package com.gme.pay.router.client;

import com.gme.pay.contracts.PartnerSchemeView;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.PartnerView;
import com.gme.pay.domain.routing.PartnerSchemeResolver;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Production {@link PartnerSchemeResolver}. Reads the {@code partner_scheme}
 * registry (V022) from config-registry over HTTP via Spring 6
 * {@link RestClient} — same client pattern as the BFF's / prefunding's
 * {@code RestConfigRegistryClient}.
 *
 * <p>Endpoint mapping (config-registry):
 * <ul>
 *   <li>{@code GET /v1/admin/partners/{partnerCode}/schemes}
 *       (PartnerSchemeController) -> {@link #resolveForPartner(String)}</li>
 *   <li>{@code GET /v1/partners} (PartnerController) +
 *       per-partner scheme fan-out -> {@link #resolveForCountry(String)}</li>
 * </ul>
 *
 * <p>Failure mapping: a 404 on the per-partner scheme lookup (unknown partner
 * — or the endpoint not yet deployed) surfaces as
 * {@link ErrorCode#NO_SCHEME_FOR_LOCATION}; any other transport/upstream
 * failure is {@link ErrorCode#SCHEME_UNAVAILABLE} (503, retryable) — routing
 * must not silently fall back when the registry is down.
 *
 * <p>Country resolution is data-driven, not a table in code: partners whose
 * operating country matches (operating address, falling back to registered
 * address, then country of incorporation) and that are not
 * {@code SUSPENDED}/{@code TERMINATED} contribute their ENABLED scheme rows,
 * de-duplicated in encounter order.
 */
@Component
public class RestPartnerSchemeResolver implements PartnerSchemeResolver {

    private static final Logger log = LoggerFactory.getLogger(RestPartnerSchemeResolver.class);

    private final RestClient restClient;

    // Spring 6 trap: with 2 constructors the @Value one MUST carry @Autowired,
    // or the container picks neither (the RestConfigRegistryClient incident).
    @Autowired
    public RestPartnerSchemeResolver(
            @Value("${gmepay.config-registry.base-url:http://config-registry:8080}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).build());
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestPartnerSchemeResolver(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public List<String> resolveForPartner(String partnerCode) {
        return enabledSchemeIds(fetchSchemes(partnerCode));
    }

    @Override
    public List<String> resolveForCountry(String countryCode) {
        LinkedHashSet<String> schemes = new LinkedHashSet<>();
        for (PartnerView partner : listPartners()) {
            if (!routable(partner) || !operatesIn(partner, countryCode)) {
                continue;
            }
            try {
                schemes.addAll(enabledSchemeIds(fetchSchemes(partner.partnerCode())));
            } catch (ApiException ex) {
                if (ex.errorCode() != ErrorCode.NO_SCHEME_FOR_LOCATION) {
                    throw ex;
                }
                // Partner vanished between the directory read and the scheme
                // read (registry write race) — it simply contributes nothing.
                log.debug("partner {} disappeared during country scan: {}",
                        partner.partnerCode(), ex.getMessage());
            }
        }
        return List.copyOf(schemes);
    }

    // -------------------------- wire calls -----------------------------------

    /**
     * {@code GET /v1/admin/partners/{partnerCode}/schemes}. 404 (unknown
     * partner, or Lane A's endpoint not deployed yet) maps to
     * {@code NO_SCHEME_FOR_LOCATION}; other failures to {@code SCHEME_UNAVAILABLE}.
     */
    private List<PartnerSchemeView> fetchSchemes(String partnerCode) {
        try {
            List<PartnerSchemeView> body = restClient.get()
                    .uri("/v1/admin/partners/{partnerCode}/schemes", partnerCode)
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (req, resp) -> {
                        throw new ApiException(ErrorCode.NO_SCHEME_FOR_LOCATION,
                                "no scheme wiring registered for partner " + partnerCode);
                    })
                    .body(new ParameterizedTypeReference<List<PartnerSchemeView>>() {});
            return body == null ? List.of() : body;
        } catch (ApiException mapped) {
            throw mapped;
        } catch (RestClientException upstream) {
            log.warn("config-registry scheme lookup failed for partner {}: {}",
                    partnerCode, upstream.getMessage());
            throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE,
                    "scheme registry unavailable for partner " + partnerCode);
        }
    }

    /** {@code GET /v1/partners} — the partner directory for the country scan. */
    private List<PartnerView> listPartners() {
        try {
            List<PartnerView> body = restClient.get()
                    .uri("/v1/partners")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<PartnerView>>() {});
            return body == null ? List.of() : body;
        } catch (RestClientException upstream) {
            log.warn("config-registry partner directory unavailable: {}", upstream.getMessage());
            throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE,
                    "scheme registry unavailable for country lookup");
        }
    }

    // -------------------------- helpers --------------------------------------

    /** Enabled rows only, scheme ids de-duplicated in registry order. */
    private static List<String> enabledSchemeIds(List<PartnerSchemeView> views) {
        return views.stream()
                .filter(v -> v.enabled() == null || v.enabled())
                .map(PartnerSchemeView::schemeId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
    }

    /** A partner with no usable code, or halted/closed, routes nothing. */
    private static boolean routable(PartnerView partner) {
        if (partner == null || partner.partnerCode() == null || partner.partnerCode().isBlank()) {
            return false;
        }
        return partner.status() != PartnerStatus.SUSPENDED
                && partner.status() != PartnerStatus.TERMINATED;
    }

    /** Operating address wins; registered address, then incorporation, fall back. */
    private static boolean operatesIn(PartnerView partner, String countryCode) {
        String country = null;
        if (partner.operatingAddress() != null && notBlank(partner.operatingAddress().country())) {
            country = partner.operatingAddress().country();
        } else if (partner.registeredAddress() != null
                && notBlank(partner.registeredAddress().country())) {
            country = partner.registeredAddress().country();
        } else if (notBlank(partner.countryOfIncorporation())) {
            country = partner.countryOfIncorporation();
        }
        return country != null && country.equalsIgnoreCase(countryCode);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
