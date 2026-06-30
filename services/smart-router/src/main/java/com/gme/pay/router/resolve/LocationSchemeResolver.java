package com.gme.pay.router.resolve;

import com.gme.pay.domain.Direction;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Authoritative data-driven scheme-for-location resolution (cross-service
 * request from qr-service). Given a {@link LocationSchemeQuery}
 * (country/location + presentment mode + direction), returns the enabled
 * scheme(s) wired in the {@code partner_scheme} registry, disambiguated by
 * priority — replacing qr-service's config-driven country allow-list.
 *
 * <h2>Branch order (each a distinct, named outcome)</h2>
 * <ol>
 *   <li>{@link ErrorCode#VALIDATION_ERROR} — null/blank country, missing mode,
 *       or a direction outside {@link Direction}.</li>
 *   <li>{@link ErrorCode#NO_SCHEME_FOR_LOCATION} — no enabled row exists for the
 *       country at all.</li>
 *   <li>{@link ErrorCode#DIRECTION_NOT_ENABLED} — rows exist for the country,
 *       but none participates in the requested direction.</li>
 *   <li>{@link ErrorCode#PAYMENT_MODE_NOT_SUPPORTED} — rows match the direction,
 *       but none is wired for the requested presentment mode.</li>
 * </ol>
 *
 * <p>The branches narrow progressively so the caller learns the MOST specific
 * reason: a corridor that exists but is inbound-only, scanned for an outbound
 * payment, returns DIRECTION_NOT_ENABLED — not a blanket NO_SCHEME — and the
 * wallet can react accordingly.
 *
 * <p>Phase 2: migrated off the former router-local {@code ResolutionError} enum
 * onto the canonical {@link ErrorCode} (lib-errors), throwing {@link ApiException}
 * so every branch surfaces as the unified API-05 error envelope with its
 * canonical status (409 for mode/direction, 404 for no-scheme, 400 for validation).
 */
@Service
public class LocationSchemeResolver {

    private final PartnerSchemeRegistry registry;

    public LocationSchemeResolver(PartnerSchemeRegistry registry) {
        this.registry = registry;
    }

    /**
     * Resolve the scheme(s) for one location query.
     *
     * @throws ApiException carrying the most specific {@link ErrorCode} branch
     *         that applies.
     */
    public SchemeResolution resolve(LocationSchemeQuery query) {
        String country = validate(query);

        // Branch 2: nothing wired for the country at all.
        List<PartnerSchemeRecord> rows = registry.schemesForCountry(country);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NO_SCHEME_FOR_LOCATION,
                    "no enabled scheme wired for country " + country);
        }

        // Branch 3: rows exist, but none enabled for this direction.
        List<PartnerSchemeRecord> directionMatches = rows.stream()
                .filter(r -> r.enabledFor(query.direction().trim().toUpperCase(Locale.ROOT)))
                .toList();
        if (directionMatches.isEmpty()) {
            throw new ApiException(ErrorCode.DIRECTION_NOT_ENABLED,
                    "no scheme in " + country + " enabled for direction " + query.direction());
        }

        // Branch 4: direction matches, but none wired for this presentment mode.
        List<String> candidates = directionMatches.stream()
                .filter(r -> r.supports(query.mode()))
                .map(PartnerSchemeRecord::schemeId)
                .distinct()
                .toList();
        if (candidates.isEmpty()) {
            throw new ApiException(ErrorCode.PAYMENT_MODE_NOT_SUPPORTED,
                    "no scheme in " + country + " supports " + query.mode()
                            + " for direction " + query.direction());
        }

        // Success: priority-ordered winner + the ambiguity surface.
        return SchemeResolution.of(candidates);
    }

    /** Returns the normalized country code, or throws VALIDATION_ERROR. */
    private static String validate(LocationSchemeQuery query) {
        if (query == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "query required");
        }
        if (query.countryCode() == null || query.countryCode().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "country_code required");
        }
        if (query.mode() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "payment mode (CPM/MPM) required");
        }
        if (query.direction() == null || query.direction().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "direction required");
        }
        try {
            Direction.valueOf(query.direction().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "unknown direction: " + query.direction());
        }
        return query.countryCode().trim().toUpperCase(Locale.ROOT);
    }
}
