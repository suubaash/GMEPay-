package com.gme.pay.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gme.pay.domain.routing.PartnerSchemeResolver;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SchemeRouter} over a programmable stub {@link PartnerSchemeResolver}
 * — the router's policy (first-scheme preference, case normalization,
 * NO_SCHEME_FOR_LOCATION on empty) tested in isolation from the REST adapter
 * (which has its own MockRestServiceServer wire tests in
 * {@code RestPartnerSchemeResolverTest}).
 */
class SchemeRouterTest {

    /** In-memory stand-in for the config-registry-backed resolver. */
    private static final class StubResolver implements PartnerSchemeResolver {

        final Map<String, List<String>> byPartner = new HashMap<>();
        final Map<String, List<String>> byCountry = new HashMap<>();
        String lastCountryQueried;
        String lastPartnerQueried;

        @Override
        public List<String> resolveForPartner(String partnerCode) {
            lastPartnerQueried = partnerCode;
            return byPartner.getOrDefault(partnerCode, List.of());
        }

        @Override
        public List<String> resolveForCountry(String countryCode) {
            lastCountryQueried = countryCode;
            return byCountry.getOrDefault(countryCode, List.of());
        }
    }

    private final StubResolver resolver = new StubResolver();
    private final SchemeRouter router = new SchemeRouter(resolver);

    @Test
    void koreaResolvesToZeroPay() {
        resolver.byCountry.put("KR", List.of("ZEROPAY"));

        assertEquals("ZEROPAY", router.resolve("KR"));
        assertEquals("ZEROPAY", router.resolve("kr"));
    }

    @Test
    void unknownCountryThrowsNoSchemeForLocation() {
        ApiException ex = assertThrows(ApiException.class, () -> router.resolve("ZZ"));
        assertEquals(ErrorCode.NO_SCHEME_FOR_LOCATION, ex.errorCode());
    }

    @Test
    void nullCountryIsRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> router.resolve(null));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
    }

    // ----------------- Slice 7: data-driven paths ----------------------------

    @Test
    @DisplayName("per-partner override returns the partner's own scheme set, untouched")
    void partnerOverrideResolvesThroughResolver() {
        resolver.byPartner.put("GMEREMIT", List.of("BAKONG", "KHQR"));

        assertEquals(List.of("BAKONG", "KHQR"), router.resolveForPartner("GMEREMIT"));
        assertEquals("GMEREMIT", resolver.lastPartnerQueried);
    }

    @Test
    @DisplayName("multi-scheme country: resolve() prefers the first, list() keeps the fallbacks")
    void multiSchemeCountryFallsBackInOrder() {
        resolver.byCountry.put("KH", List.of("KHQR", "BAKONG"));

        assertEquals("KHQR", router.resolve("KH"));
        assertEquals(List.of("KHQR", "BAKONG"), router.list("KH"));
    }

    @Test
    @DisplayName("empty resolver result -> NO_SCHEME_FOR_LOCATION (country and partner paths)")
    void emptyResolverResultThrowsNoSchemeForLocation() {
        resolver.byCountry.put("SG", List.of());
        resolver.byPartner.put("NEWPARTNER", List.of());

        ApiException byCountry = assertThrows(ApiException.class, () -> router.resolve("SG"));
        assertEquals(ErrorCode.NO_SCHEME_FOR_LOCATION, byCountry.errorCode());

        ApiException byPartner =
                assertThrows(ApiException.class, () -> router.resolveForPartner("NEWPARTNER"));
        assertEquals(ErrorCode.NO_SCHEME_FOR_LOCATION, byPartner.errorCode());
    }

    @Test
    @DisplayName("country lookup normalizes case/whitespace before hitting the resolver")
    void countryLevelLookupNormalizesBeforeResolver() {
        resolver.byCountry.put("VN", List.of("NAPAS_247"));

        assertEquals(List.of("NAPAS_247"), router.list(" vn "));
        assertEquals("VN", resolver.lastCountryQueried);
    }

    @Test
    void blankPartnerIsRejected() {
        ApiException ex =
                assertThrows(ApiException.class, () -> router.resolveForPartner("  "));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
    }
}
