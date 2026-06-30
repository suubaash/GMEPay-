package com.gme.pay.router.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Data-driven scheme-for-location resolution — every {@link ResolutionError}
 * branch and the priority disambiguation, over an in-process
 * {@link PartnerSchemeRegistry} fixture. Mirrors the cross-service contract
 * qr-service binds to: VALIDATION_ERROR / NO_SCHEME_FOR_LOCATION /
 * DIRECTION_NOT_ENABLED / PAYMENT_MODE_NOT_SUPPORTED.
 */
class LocationSchemeResolverTest {

    /** A registry staged per-test, independent of the seeded production fixture. */
    private static PartnerSchemeRegistry registryOf(PartnerSchemeRecord... rows) {
        return countryCode -> {
            String key = countryCode == null ? null : countryCode.trim().toUpperCase();
            return List.of(rows).stream()
                    .filter(r -> r.countryCode().equals(key))
                    .sorted(java.util.Comparator.comparingInt(PartnerSchemeRecord::priority))
                    .toList();
        };
    }

    private static SchemeResolutionException resolveExpectingError(
            PartnerSchemeRegistry registry, LocationSchemeQuery query) {
        return assertThrows(SchemeResolutionException.class,
                () -> new LocationSchemeResolver(registry).resolve(query));
    }

    // --------------------------- happy paths ---------------------------------

    @Test
    @DisplayName("single matching scheme resolves, not ambiguous")
    void singleMatchResolves() {
        PartnerSchemeRegistry registry = registryOf(
                new PartnerSchemeRecord("ZEROPAY", "KR", "BOTH", true, true, 0));

        SchemeResolution r = new LocationSchemeResolver(registry)
                .resolve(new LocationSchemeQuery("kr", PaymentMode.MPM, "domestic"));

        assertEquals("ZEROPAY", r.scheme());
        assertEquals(List.of("ZEROPAY"), r.candidates());
        assertFalse(r.ambiguous());
    }

    @Test
    @DisplayName("multiple matches: priority winner first, flagged ambiguous, fallbacks kept")
    void multipleMatchesDisambiguateByPriority() {
        PartnerSchemeRegistry registry = registryOf(
                new PartnerSchemeRecord("BAKONG", "KH", "BOTH", true, true, 1),
                new PartnerSchemeRecord("KHQR", "KH", "INBOUND", false, true, 0));

        SchemeResolution r = new LocationSchemeResolver(registry)
                .resolve(new LocationSchemeQuery("KH", PaymentMode.MPM, "INBOUND"));

        assertEquals("KHQR", r.scheme());
        assertEquals(List.of("KHQR", "BAKONG"), r.candidates());
        assertTrue(r.ambiguous());
    }

    // --------------------------- branch: VALIDATION_ERROR --------------------

    @Test
    void blankCountryIsValidationError() {
        SchemeResolutionException ex = resolveExpectingError(registryOf(),
                new LocationSchemeQuery("  ", PaymentMode.CPM, "DOMESTIC"));
        assertEquals(ResolutionError.VALIDATION_ERROR, ex.error());
    }

    @Test
    void nullModeIsValidationError() {
        SchemeResolutionException ex = resolveExpectingError(registryOf(),
                new LocationSchemeQuery("KR", null, "DOMESTIC"));
        assertEquals(ResolutionError.VALIDATION_ERROR, ex.error());
    }

    @Test
    void unknownDirectionIsValidationError() {
        SchemeResolutionException ex = resolveExpectingError(
                registryOf(new PartnerSchemeRecord("ZEROPAY", "KR", "BOTH", true, true, 0)),
                new LocationSchemeQuery("KR", PaymentMode.CPM, "SIDEWAYS"));
        assertEquals(ResolutionError.VALIDATION_ERROR, ex.error());
    }

    // --------------------------- branch: NO_SCHEME_FOR_LOCATION ---------------

    @Test
    @DisplayName("country with zero wired rows -> NO_SCHEME_FOR_LOCATION")
    void unwiredCountryIsNoScheme() {
        SchemeResolutionException ex = resolveExpectingError(
                registryOf(new PartnerSchemeRecord("ZEROPAY", "KR", "BOTH", true, true, 0)),
                new LocationSchemeQuery("JP", PaymentMode.MPM, "INBOUND"));
        assertEquals(ResolutionError.NO_SCHEME_FOR_LOCATION, ex.error());
        assertEquals(404, ex.error().httpStatus());
    }

    // --------------------------- branch: DIRECTION_NOT_ENABLED ----------------

    @Test
    @DisplayName("rows exist but only inbound, asked outbound -> DIRECTION_NOT_ENABLED")
    void wrongDirectionIsDirectionNotEnabled() {
        SchemeResolutionException ex = resolveExpectingError(
                registryOf(new PartnerSchemeRecord("NAPAS_247", "VN", "INBOUND", false, true, 0)),
                new LocationSchemeQuery("VN", PaymentMode.MPM, "OUTBOUND"));
        assertEquals(ResolutionError.DIRECTION_NOT_ENABLED, ex.error());
    }

    // --------------------------- branch: PAYMENT_MODE_NOT_SUPPORTED -----------

    @Test
    @DisplayName("direction matches but scheme not wired for the mode -> PAYMENT_MODE_NOT_SUPPORTED")
    void wrongModeIsPaymentModeNotSupported() {
        // KHQR is MPM-only; ask for CPM.
        SchemeResolutionException ex = resolveExpectingError(
                registryOf(new PartnerSchemeRecord("KHQR", "KH", "INBOUND", false, true, 0)),
                new LocationSchemeQuery("KH", PaymentMode.CPM, "INBOUND"));
        assertEquals(ResolutionError.PAYMENT_MODE_NOT_SUPPORTED, ex.error());
        assertEquals(409, ex.error().httpStatus());
    }

    @Test
    @DisplayName("mode mismatch is reported AFTER direction filtering, not as NO_SCHEME")
    void modeBranchTakesPrecedenceOverNoScheme() {
        // Inbound CPM-incapable scheme + an outbound CPM-capable one: asking
        // INBOUND/CPM must surface PAYMENT_MODE_NOT_SUPPORTED (direction matched,
        // mode didn't) rather than collapsing to NO_SCHEME.
        SchemeResolutionException ex = resolveExpectingError(
                registryOf(
                        new PartnerSchemeRecord("KHQR", "KH", "INBOUND", false, true, 0),
                        new PartnerSchemeRecord("FAST_SG", "KH", "OUTBOUND", true, false, 1)),
                new LocationSchemeQuery("KH", PaymentMode.CPM, "INBOUND"));
        assertEquals(ResolutionError.PAYMENT_MODE_NOT_SUPPORTED, ex.error());
    }
}
