package com.gme.pay.router;

import com.gme.pay.domain.routing.PartnerSchemeResolver;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import java.util.List;
import java.util.Locale;

/**
 * Resolves an ISO-3166 country code (or a partner code) to its QR scheme(s).
 * Data-driven since Slice 7: scheme wiring lives in config-registry's
 * {@code partner_scheme} table (V022) behind the {@link PartnerSchemeResolver}
 * port — adding a scheme is configuration, not code. Throws
 * {@code NO_SCHEME_FOR_LOCATION} when nothing is wired.
 */
public class SchemeRouter {

    private final PartnerSchemeResolver resolver;

    public SchemeRouter(PartnerSchemeResolver resolver) {
        this.resolver = resolver;
    }

    /** The preferred (first) scheme for a merchant country. */
    public String resolve(String countryCode) {
        return lookupForCountry(countryCode).get(0);
    }

    /** All schemes available in a merchant country, priority order. */
    public List<String> list(String countryCode) {
        return lookupForCountry(countryCode);
    }

    /** The schemes wired to one partner (per-partner override path). */
    public List<String> resolveForPartner(String partnerCode) {
        if (partnerCode == null || partnerCode.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "partner_code required");
        }
        List<String> schemes = resolver.resolveForPartner(partnerCode.trim());
        if (schemes == null || schemes.isEmpty()) {
            throw new ApiException(ErrorCode.NO_SCHEME_FOR_LOCATION,
                    "no QR scheme enabled for partner " + partnerCode);
        }
        return schemes;
    }

    private List<String> lookupForCountry(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "country_code required");
        }
        List<String> schemes =
                resolver.resolveForCountry(countryCode.trim().toUpperCase(Locale.ROOT));
        if (schemes == null || schemes.isEmpty()) {
            throw new ApiException(ErrorCode.NO_SCHEME_FOR_LOCATION,
                    "no QR scheme registered for country " + countryCode);
        }
        return schemes;
    }
}
