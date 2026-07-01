package com.gme.pay.router.resolve;

import com.gme.pay.contracts.PartnerSchemeView;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.PartnerView;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
 * Production {@link PartnerSchemeRegistry}: the data-driven backing for
 * {@link LocationSchemeResolver}, reading config-registry's {@code partner_scheme}
 * registry (V022) over HTTP — the same RestClient pattern, base-url property and
 * two-constructor {@code @Autowired} trap as {@link com.gme.pay.router.client.RestPartnerSchemeResolver}.
 *
 * <p>Gated by {@code gmepay.config-registry.enabled=true} (mirrors the existing
 * rest-client gating): when unset/false the default {@link InMemoryPartnerSchemeRegistry}
 * stays in place, so tests and local runs never reach the network. With both
 * present Spring would see two {@code PartnerSchemeRegistry} beans, so the
 * in-memory fixture is {@code @Profile("!config-registry")} and the real adapter
 * is selected by activating that profile (or simply flipping the property + profile
 * in the deploy env); the resolver depends only on the port.
 *
 * <p>Resolution is data-driven, not a country table in code. We read the partner
 * directory ({@code GET /v1/partners}), keep routable partners operating in the
 * requested country, fan out to each one's enablement rows
 * ({@code GET /v1/admin/partners/{partnerCode}/schemes} → {@link PartnerSchemeView})
 * and project the ENABLED, country-matching rows to {@link PartnerSchemeRecord}.
 * Mode support is taken from the view's {@code supportsCpm}/{@code supportsMpm}
 * flags, falling back to {@code approvalMethodCpm}/{@code Mpm} presence when the
 * flags are still null (config-registry populates them incrementally).
 *
 * <p>Failure mapping (never a silent empty fallback when the registry is down):
 * a transport/upstream failure surfaces as {@link ErrorCode#SCHEME_UNAVAILABLE}
 * (503, retryable); a country that is simply unwired returns an EMPTY list and
 * the resolver turns that into {@link ErrorCode#NO_SCHEME_FOR_LOCATION}.
 */
@Component
@ConditionalOnProperty(name = "gmepay.config-registry.enabled", havingValue = "true")
public class RestPartnerSchemeRegistry implements PartnerSchemeRegistry {

    private static final Logger log = LoggerFactory.getLogger(RestPartnerSchemeRegistry.class);

    private final RestClient restClient;

    // Spring 6 trap: with 2 constructors the @Value one MUST carry @Autowired,
    // or the container picks neither (mirrors RestPartnerSchemeResolver).
    @Autowired
    public RestPartnerSchemeRegistry(
            @Value("${gmepay.config-registry.base-url:http://config-registry:8080}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).build());
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestPartnerSchemeRegistry(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public List<PartnerSchemeRecord> schemesForCountry(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return List.of();
        }
        String country = countryCode.trim().toUpperCase(Locale.ROOT);

        List<PartnerSchemeRecord> rows = new ArrayList<>();
        for (PartnerView partner : listPartners()) {
            if (!routable(partner) || !operatesIn(partner, country)) {
                continue;
            }
            for (PartnerSchemeView view : fetchSchemes(partner.partnerCode())) {
                PartnerSchemeRecord record = toRecord(view, country);
                if (record != null) {
                    rows.add(record);
                }
            }
        }
        rows.sort(Comparator.comparingInt(PartnerSchemeRecord::priority));
        return List.copyOf(rows);
    }

    // -------------------------- wire calls -----------------------------------

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

    /**
     * {@code GET /v1/admin/partners/{partnerCode}/schemes} → the partner's
     * enablement rows. A 404 (partner vanished between the directory read and
     * the scheme read — a registry write race) contributes nothing rather than
     * failing the whole scan; any other upstream failure is SCHEME_UNAVAILABLE.
     */
    private List<PartnerSchemeView> fetchSchemes(String partnerCode) {
        try {
            List<PartnerSchemeView> body = restClient.get()
                    .uri("/v1/admin/partners/{partnerCode}/schemes", partnerCode)
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (req, resp) -> {
                        throw new ApiException(ErrorCode.NO_SCHEME_FOR_LOCATION,
                                "partner " + partnerCode + " has no scheme wiring");
                    })
                    .body(new ParameterizedTypeReference<List<PartnerSchemeView>>() {});
            return body == null ? List.of() : body;
        } catch (ApiException notFound) {
            log.debug("partner {} disappeared during country scan: {}",
                    partnerCode, notFound.getMessage());
            return List.of();
        } catch (RestClientException upstream) {
            log.warn("config-registry scheme lookup failed for partner {}: {}",
                    partnerCode, upstream.getMessage());
            throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE,
                    "scheme registry unavailable for partner " + partnerCode);
        }
    }

    // -------------------------- mapping --------------------------------------

    /**
     * Project one wire row to the resolver's read model, or {@code null} when it
     * must not participate: a disabled kill switch, a non-active lifecycle
     * status, a blank scheme id, or a country that does not match the query
     * (the view's {@code countryCode} wins; a null country falls through to the
     * partner-location match already established by the caller).
     */
    private static PartnerSchemeRecord toRecord(PartnerSchemeView view, String country) {
        if (view == null || view.schemeId() == null || view.schemeId().isBlank()) {
            return null;
        }
        if (view.enabled() != null && !view.enabled()) {
            return null;
        }
        if (view.status() != null && !"ACTIVE".equalsIgnoreCase(view.status().trim())) {
            return null;
        }
        if (view.countryCode() != null && !view.countryCode().isBlank()
                && !country.equalsIgnoreCase(view.countryCode().trim())) {
            return null;
        }
        return new PartnerSchemeRecord(
                view.schemeId(),
                country,
                view.direction(),
                supportsCpm(view),
                supportsMpm(view),
                view.priority() == null ? Integer.MAX_VALUE : view.priority());
    }

    /** Explicit flag wins; else the presence of the CPM approval-method wiring. */
    private static boolean supportsCpm(PartnerSchemeView view) {
        if (view.supportsCpm() != null) {
            return view.supportsCpm();
        }
        return notBlank(view.approvalMethodCpm());
    }

    private static boolean supportsMpm(PartnerSchemeView view) {
        if (view.supportsMpm() != null) {
            return view.supportsMpm();
        }
        return notBlank(view.approvalMethodMpm());
    }

    // -------------------------- helpers --------------------------------------

    /** A partner with no usable code, or halted/closed, routes nothing. */
    private static boolean routable(PartnerView partner) {
        if (partner == null || partner.partnerCode() == null || partner.partnerCode().isBlank()) {
            return false;
        }
        return partner.status() != PartnerStatus.SUSPENDED
                && partner.status() != PartnerStatus.TERMINATED;
    }

    /** Operating address wins; registered address, then incorporation, fall back. */
    private static boolean operatesIn(PartnerView partner, String country) {
        String partnerCountry = null;
        if (partner.operatingAddress() != null && notBlank(partner.operatingAddress().country())) {
            partnerCountry = partner.operatingAddress().country();
        } else if (partner.registeredAddress() != null
                && notBlank(partner.registeredAddress().country())) {
            partnerCountry = partner.registeredAddress().country();
        } else if (notBlank(partner.countryOfIncorporation())) {
            partnerCountry = partner.countryOfIncorporation();
        }
        return partnerCountry != null && partnerCountry.equalsIgnoreCase(country);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
