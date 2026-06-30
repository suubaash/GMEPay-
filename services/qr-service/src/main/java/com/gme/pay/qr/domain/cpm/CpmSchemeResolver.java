package com.gme.pay.qr.domain.cpm;

import com.gme.pay.qr.exception.QRErrorCode;
import com.gme.pay.qr.exception.QRParseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Resolves the active CPM-capable scheme for a country (WBS 5.3-T04, NO_SCHEME handling).
 *
 * <p>Authoritative scheme selection (smart-router over config-registry's partner_scheme tables)
 * lives in OTHER services and is FROZEN — captured as INTEGRATION REQUEST #2. Until that lands,
 * this resolver applies a config-driven country allow-list ({@code qr.cpm.zeropay-countries},
 * default {@code KR}) so {@code NO_SCHEME_FOR_LOCATION} is genuinely exercised rather than always
 * succeeding. A provided {@code schemeId} hint is validated against the resolved scheme.
 */
@Component
public class CpmSchemeResolver {

    private static final String ZEROPAY = "ZEROPAY";

    private final Set<String> zeropayCountries;

    public CpmSchemeResolver(
            @Value("${qr.cpm.zeropay-countries:KR}") String zeropayCountries) {
        this.zeropayCountries = new LinkedHashSet<>(
                Arrays.stream(zeropayCountries.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(s -> s.toUpperCase())
                        .toList());
    }

    /**
     * Resolve the scheme id for the given country, honouring an optional partner-supplied hint.
     *
     * @param countryCode ISO 3166-1 alpha-2 country code
     * @param schemeIdHint optional scheme id from the request (may be blank/null)
     * @return the resolved scheme id (e.g. "ZEROPAY")
     * @throws QRParseException {@code NO_SCHEME_FOR_LOCATION} when no scheme serves the country;
     *                          {@code QR_UNKNOWN_SCHEME} when the hint names an unsupported scheme
     */
    public String resolve(String countryCode, String schemeIdHint) {
        String country = countryCode == null ? "" : countryCode.toUpperCase();
        if (!zeropayCountries.contains(country)) {
            throw new QRParseException(QRErrorCode.NO_SCHEME_FOR_LOCATION,
                    "No active CPM scheme for country: " + country);
        }
        if (schemeIdHint != null && !schemeIdHint.isBlank()
                && !ZEROPAY.equalsIgnoreCase(schemeIdHint)) {
            throw new QRParseException(QRErrorCode.QR_UNKNOWN_SCHEME,
                    "Unsupported scheme_id for country " + country + ": " + schemeIdHint);
        }
        return ZEROPAY;
    }
}
