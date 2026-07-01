package com.gme.pay.router.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gme.pay.contracts.PartnerSchemeView;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Data-driven scheme-for-location resolution — every canonical {@link ErrorCode}
 * branch and the priority disambiguation, over an in-process
 * {@link PartnerSchemeRegistry} fixture. Phase 2: asserts the canonical
 * {@link ApiException}/{@link ErrorCode} surface (VALIDATION_ERROR /
 * NO_SCHEME_FOR_LOCATION / DIRECTION_NOT_ENABLED / PAYMENT_MODE_NOT_SUPPORTED)
 * with its HTTP status (409 for mode/direction), mirroring the cross-service
 * contract qr-service binds to.
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

    private static ApiException resolveExpectingError(
            PartnerSchemeRegistry registry, LocationSchemeQuery query) {
        return assertThrows(ApiException.class,
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

    @Test
    @DisplayName("NP scan resolves to NEPAL in both presentment modes (single-phase, BOTH)")
    void npResolvesToNepalEitherMode() {
        PartnerSchemeRegistry registry = registryOf(
                new PartnerSchemeRecord("NEPAL", "NP", "BOTH", true, true, 0));

        SchemeResolution cpm = new LocationSchemeResolver(registry)
                .resolve(new LocationSchemeQuery("np", PaymentMode.CPM, "INBOUND"));
        assertEquals("NEPAL", cpm.scheme());
        assertFalse(cpm.ambiguous());

        SchemeResolution mpm = new LocationSchemeResolver(registry)
                .resolve(new LocationSchemeQuery("NP", PaymentMode.MPM, "DOMESTIC"));
        assertEquals("NEPAL", mpm.scheme());
        assertEquals(List.of("NEPAL"), mpm.candidates());
    }

    // --------------------------- branch: VALIDATION_ERROR --------------------

    @Test
    void blankCountryIsValidationError() {
        ApiException ex = resolveExpectingError(registryOf(),
                new LocationSchemeQuery("  ", PaymentMode.CPM, "DOMESTIC"));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
    }

    @Test
    void nullModeIsValidationError() {
        ApiException ex = resolveExpectingError(registryOf(),
                new LocationSchemeQuery("KR", null, "DOMESTIC"));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
    }

    @Test
    void unknownDirectionIsValidationError() {
        ApiException ex = resolveExpectingError(
                registryOf(new PartnerSchemeRecord("ZEROPAY", "KR", "BOTH", true, true, 0)),
                new LocationSchemeQuery("KR", PaymentMode.CPM, "SIDEWAYS"));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
    }

    // --------------------------- branch: NO_SCHEME_FOR_LOCATION ---------------

    @Test
    @DisplayName("country with zero wired rows -> NO_SCHEME_FOR_LOCATION (404)")
    void unwiredCountryIsNoScheme() {
        ApiException ex = resolveExpectingError(
                registryOf(new PartnerSchemeRecord("ZEROPAY", "KR", "BOTH", true, true, 0)),
                new LocationSchemeQuery("JP", PaymentMode.MPM, "INBOUND"));
        assertEquals(ErrorCode.NO_SCHEME_FOR_LOCATION, ex.errorCode());
        assertEquals(404, ex.errorCode().httpStatus());
    }

    // --------------------------- branch: DIRECTION_NOT_ENABLED ----------------

    @Test
    @DisplayName("rows exist but only inbound, asked outbound -> DIRECTION_NOT_ENABLED (409)")
    void wrongDirectionIsDirectionNotEnabled() {
        ApiException ex = resolveExpectingError(
                registryOf(new PartnerSchemeRecord("NAPAS_247", "VN", "INBOUND", false, true, 0)),
                new LocationSchemeQuery("VN", PaymentMode.MPM, "OUTBOUND"));
        assertEquals(ErrorCode.DIRECTION_NOT_ENABLED, ex.errorCode());
        assertEquals(409, ex.errorCode().httpStatus());
    }

    // --------------------------- branch: PAYMENT_MODE_NOT_SUPPORTED -----------

    @Test
    @DisplayName("direction matches but scheme not wired for the mode -> PAYMENT_MODE_NOT_SUPPORTED (409)")
    void wrongModeIsPaymentModeNotSupported() {
        // KHQR is MPM-only; ask for CPM.
        ApiException ex = resolveExpectingError(
                registryOf(new PartnerSchemeRecord("KHQR", "KH", "INBOUND", false, true, 0)),
                new LocationSchemeQuery("KH", PaymentMode.CPM, "INBOUND"));
        assertEquals(ErrorCode.PAYMENT_MODE_NOT_SUPPORTED, ex.errorCode());
        assertEquals(409, ex.errorCode().httpStatus());
    }

    @Test
    @DisplayName("mode mismatch is reported AFTER direction filtering, not as NO_SCHEME")
    void modeBranchTakesPrecedenceOverNoScheme() {
        // Inbound CPM-incapable scheme + an outbound CPM-capable one: asking
        // INBOUND/CPM must surface PAYMENT_MODE_NOT_SUPPORTED (direction matched,
        // mode didn't) rather than collapsing to NO_SCHEME.
        ApiException ex = resolveExpectingError(
                registryOf(
                        new PartnerSchemeRecord("KHQR", "KH", "INBOUND", false, true, 0),
                        new PartnerSchemeRecord("FAST_SG", "KH", "OUTBOUND", true, false, 1)),
                new LocationSchemeQuery("KH", PaymentMode.CPM, "INBOUND"));
        assertEquals(ErrorCode.PAYMENT_MODE_NOT_SUPPORTED, ex.errorCode());
    }

    // --------------------- ADR-016: resolveCandidates (failover) --------------

    @Test
    @DisplayName("network=fonepay.com + NP + MPM -> ordered candidates by priority (failover order)")
    void networkTwoPartnersSameNetworkReturnsBothInPriorityOrder() {
        // Two NP partners both front fonepay.com; NEPAL is priority 0, the direct
        // integration is priority 1 -> both returned, NEPAL first (the failover order).
        PartnerSchemeRegistry registry = registryOf(
                new PartnerSchemeRecord("NEPAL_FONEPAY_DIRECT", "NP", "BOTH", true, true, 1,
                        21L, "fonepay.com"),
                new PartnerSchemeRecord("NEPAL", "NP", "BOTH", true, true, 0,
                        20L, "fonepay.com,nepalpay,com.f1soft"));

        List<PartnerSchemeView> candidates = new LocationSchemeResolver(registry)
                .resolveCandidates("fonepay.com",
                        new LocationSchemeQuery("NP", PaymentMode.MPM, "DOMESTIC"));

        assertEquals(List.of("NEPAL", "NEPAL_FONEPAY_DIRECT"),
                candidates.stream().map(PartnerSchemeView::schemeId).toList());
        assertEquals(20L, candidates.get(0).partnerId());
        assertEquals("ACTIVE", candidates.get(0).status());
    }

    @Test
    @DisplayName("CSV membership: a mid-list Nepal GUID (nepalpay) still matches the NEPAL row")
    void networkCsvMembershipMatchesInteriorToken() {
        PartnerSchemeRegistry registry = registryOf(
                new PartnerSchemeRecord("NEPAL", "NP", "BOTH", true, true, 0,
                        20L, "fonepay.com,nepalpay,com.f1soft"));

        List<PartnerSchemeView> candidates = new LocationSchemeResolver(registry)
                .resolveCandidates("nepalpay",
                        new LocationSchemeQuery("NP", PaymentMode.MPM, "DOMESTIC"));

        assertEquals(List.of("NEPAL"),
                candidates.stream().map(PartnerSchemeView::schemeId).toList());
    }

    @Test
    @DisplayName("network=com.zeropay -> ZEROPAY only")
    void networkZeroPayResolvesToZeropay() {
        PartnerSchemeRegistry registry = registryOf(
                new PartnerSchemeRecord("ZEROPAY", "KR", "BOTH", true, true, 0, 10L, "com.zeropay"),
                new PartnerSchemeRecord("NEPAL", "NP", "BOTH", true, true, 0, 20L, "fonepay.com"));

        List<PartnerSchemeView> candidates = new LocationSchemeResolver(registry)
                .resolveCandidates("com.zeropay",
                        new LocationSchemeQuery("KR", PaymentMode.MPM, "DOMESTIC"));

        assertEquals(List.of("ZEROPAY"),
                candidates.stream().map(PartnerSchemeView::schemeId).toList());
    }

    @Test
    @DisplayName("unknown network -> NO_SCHEME_FOR_LOCATION (nothing serves it)")
    void unknownNetworkIsNoScheme() {
        PartnerSchemeRegistry registry = registryOf(
                new PartnerSchemeRecord("NEPAL", "NP", "BOTH", true, true, 0, 20L, "fonepay.com"));

        ApiException ex = assertThrows(ApiException.class,
                () -> new LocationSchemeResolver(registry).resolveCandidates("com.unknown",
                        new LocationSchemeQuery("NP", PaymentMode.MPM, "DOMESTIC")));
        assertEquals(ErrorCode.NO_SCHEME_FOR_LOCATION, ex.errorCode());
    }

    @Test
    @DisplayName("network member but wrong mode -> filtered out -> NO_SCHEME_FOR_LOCATION")
    void networkMatchesButModeExcludes() {
        // Serves com.zeropay but MPM-only; a CPM request finds no candidate.
        PartnerSchemeRegistry registry = registryOf(
                new PartnerSchemeRecord("ZEROPAY", "KR", "BOTH", false, true, 0, 10L, "com.zeropay"));

        ApiException ex = assertThrows(ApiException.class,
                () -> new LocationSchemeResolver(registry).resolveCandidates("com.zeropay",
                        new LocationSchemeQuery("KR", PaymentMode.CPM, "DOMESTIC")));
        assertEquals(ErrorCode.NO_SCHEME_FOR_LOCATION, ex.errorCode());
    }

    @Test
    @DisplayName("blank network -> VALIDATION_ERROR")
    void blankNetworkIsValidationError() {
        ApiException ex = assertThrows(ApiException.class,
                () -> new LocationSchemeResolver(registryOf()).resolveCandidates("  ",
                        new LocationSchemeQuery("NP", PaymentMode.MPM, "DOMESTIC")));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
    }
}
