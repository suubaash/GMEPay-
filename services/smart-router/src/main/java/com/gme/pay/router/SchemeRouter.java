package com.gme.pay.router;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import java.util.List;
import java.util.Map;

/**
 * Resolves an ISO-3166 country code to its QR scheme(s). Phase 1 ships ZeroPay (KR); other schemes
 * are added as configuration, not code. Throws NO_SCHEME_FOR_LOCATION when nothing is registered.
 */
public class SchemeRouter {

    // In F1+ this is loaded from config-registry; seeded here for Phase 1.
    private final Map<String, List<String>> schemesByCountry = Map.of(
            "KR", List.of("ZEROPAY")
    );

    public String resolve(String countryCode) {
        List<String> schemes = lookup(countryCode);
        return schemes.get(0);
    }

    public List<String> list(String countryCode) {
        return lookup(countryCode);
    }

    private List<String> lookup(String countryCode) {
        if (countryCode == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "country_code required");
        }
        List<String> schemes = schemesByCountry.get(countryCode.toUpperCase());
        if (schemes == null || schemes.isEmpty()) {
            throw new ApiException(ErrorCode.NO_SCHEME_FOR_LOCATION,
                    "no QR scheme registered for country " + countryCode);
        }
        return schemes;
    }
}
