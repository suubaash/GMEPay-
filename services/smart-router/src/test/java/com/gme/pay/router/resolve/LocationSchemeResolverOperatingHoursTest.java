package com.gme.pay.router.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gme.pay.contracts.PartnerSchemeView;
import com.gme.pay.contracts.SchemeOperatingHoursView;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T3-6 — the operating-window branch {@link LocationSchemeResolver} used to lack. V024's header says the
 * router needs this table to answer "can this transaction route NOW?"; before this the resolver rejected
 * only on direction / presentment mode / no-scheme.
 *
 * <p>Every case runs on a FIXED clock, so "closed" is a property of the fixture rather than of when the
 * suite happens to run.
 */
class LocationSchemeResolverOperatingHoursTest {

    /** 2026-07-28 is a TUESDAY → V024 weekday 1. 03:00Z = 12:00 KST / 10:00 ICT. */
    private static final Instant TUE_NOON_KST = Instant.parse("2026-07-28T03:00:00Z");

    private static PartnerSchemeRegistry registryOf(PartnerSchemeRecord... rows) {
        return countryCode -> {
            String key = countryCode == null ? null : countryCode.trim().toUpperCase();
            return List.of(rows).stream()
                    .filter(r -> r.countryCode().equals(key))
                    .sorted(java.util.Comparator.comparingInt(PartnerSchemeRecord::priority))
                    .toList();
        };
    }

    private static SchemeOperatingHoursView row(String scheme, int weekday, String open, String close,
                                                String zone) {
        return new SchemeOperatingHoursView(scheme, weekday, LocalTime.parse(open),
                LocalTime.parse(close), null, zone);
    }

    /** A source with a per-scheme schedule; any scheme absent from the map has NO rows (UNVERIFIED). */
    private static SchemeOperatingHoursSource sourceOf(Map<String, List<SchemeOperatingHoursView>> byScheme) {
        return schemeId -> byScheme.getOrDefault(schemeId, List.of());
    }

    private static LocationSchemeResolver resolver(PartnerSchemeRegistry registry,
                                                   SchemeOperatingHoursSource hours) {
        return new LocationSchemeResolver(registry, hours, Clock.fixed(TUE_NOON_KST, ZoneOffset.UTC));
    }

    // ---------------------------------------------------------------- CLOSED

    @Test
    @DisplayName("the only candidate is closed → SCHEME_CLOSED (409, non-retryable)")
    void allCandidatesClosed_isSchemeClosed() {
        PartnerSchemeRegistry registry = registryOf(
                new PartnerSchemeRecord("ZEROPAY", "KR", "BOTH", true, true, 0));
        SchemeOperatingHoursSource hours = sourceOf(Map.of(
                "ZEROPAY", List.of(row("ZEROPAY", 1, "18:00", "22:00", "Asia/Seoul"))));

        ApiException ex = assertThrows(ApiException.class, () -> resolver(registry, hours)
                .resolve(new LocationSchemeQuery("kr", PaymentMode.MPM, "domestic")));

        assertEquals(ErrorCode.SCHEME_CLOSED, ex.errorCode());
        assertEquals(409, ex.errorCode().httpStatus());
        assertTrue(ex.getMessage().contains("Asia/Seoul"), ex.getMessage());
    }

    @Test
    @DisplayName("a closed candidate is DROPPED while an open sibling still resolves")
    void closedCandidateIsSkipped_openSiblingWins() {
        // KH: KHQR (priority 0) is closed, BAKONG (priority 1) is open → BAKONG must win rather than the
        // whole corridor failing.
        PartnerSchemeRegistry registry = registryOf(
                new PartnerSchemeRecord("KHQR", "KH", "INBOUND", false, true, 0),
                new PartnerSchemeRecord("BAKONG", "KH", "BOTH", true, true, 1));
        SchemeOperatingHoursSource hours = sourceOf(Map.of(
                "KHQR", List.of(row("KHQR", 1, "20:00", "23:00", "Asia/Phnom_Penh")),
                "BAKONG", List.of(row("BAKONG", 1, "00:00:00", "23:59:59", "Asia/Phnom_Penh"))));

        SchemeResolution r = resolver(registry, hours)
                .resolve(new LocationSchemeQuery("KH", PaymentMode.MPM, "INBOUND"));

        assertEquals("BAKONG", r.scheme());
        assertEquals(List.of("BAKONG"), r.candidates());
    }

    @Test
    @DisplayName("structural branches still win: an inbound-only corridor reports DIRECTION_NOT_ENABLED")
    void windowNarrowingIsTheLASTBranch() {
        // The window check must not mask the more specific structural reason.
        PartnerSchemeRegistry registry = registryOf(
                new PartnerSchemeRecord("KHQR", "KH", "INBOUND", false, true, 0));
        SchemeOperatingHoursSource hours = sourceOf(Map.of(
                "KHQR", List.of(row("KHQR", 1, "20:00", "23:00", "Asia/Phnom_Penh"))));

        ApiException ex = assertThrows(ApiException.class, () -> resolver(registry, hours)
                .resolve(new LocationSchemeQuery("KH", PaymentMode.MPM, "OUTBOUND")));

        assertEquals(ErrorCode.DIRECTION_NOT_ENABLED, ex.errorCode());
    }

    // ------------------------------------------------------------- TIMEZONE

    @Test
    @DisplayName("the scheme's own timezone decides, across a day boundary")
    void theSchemesTimezoneDecides_notTheServers() {
        // 2026-07-27T23:30Z: MONDAY 19:30 in New York, already TUESDAY 08:30 in Seoul.
        Instant at = Instant.parse("2026-07-27T23:30:00Z");
        PartnerSchemeRegistry registry = registryOf(
                new PartnerSchemeRecord("ZEROPAY", "KR", "BOTH", true, true, 0));

        // A row seeded for Seoul-TUESDAY 08:00-09:00 is IN window at this instant.
        LocationSchemeResolver seoul = new LocationSchemeResolver(registry,
                sourceOf(Map.of("ZEROPAY", List.of(row("ZEROPAY", 1, "08:00", "09:00", "Asia/Seoul")))),
                Clock.fixed(at, ZoneOffset.UTC));
        assertEquals("ZEROPAY",
                seoul.resolve(new LocationSchemeQuery("KR", PaymentMode.MPM, "DOMESTIC")).scheme());

        // The SAME instant with the SAME wall-clock window, evaluated in New York (still MONDAY 19:30),
        // is outside 08:00-09:00 → closed. A server-local weekday/time would have got this wrong.
        LocationSchemeResolver newYork = new LocationSchemeResolver(registry,
                sourceOf(Map.of("ZEROPAY",
                        List.of(row("ZEROPAY", 0, "08:00", "09:00", "America/New_York")))),
                Clock.fixed(at, ZoneOffset.UTC));
        ApiException ex = assertThrows(ApiException.class,
                () -> newYork.resolve(new LocationSchemeQuery("KR", PaymentMode.MPM, "DOMESTIC")));
        assertEquals(ErrorCode.SCHEME_CLOSED, ex.errorCode());
    }

    // ----------------------------------------------------------- UNVERIFIED

    @Test
    @DisplayName("UNVERIFIED (no seeded rows) keeps the candidate — never assumed open, never blocked")
    void unverifiedWindowStillResolves() {
        // NEPAL is a LIVE adapter with no V024 rows: blocking it would take the corridor offline over
        // missing reference data, which is why UNVERIFIED is permissive (and logged).
        PartnerSchemeRegistry registry = registryOf(
                new PartnerSchemeRecord("NEPAL", "NP", "BOTH", true, true, 0));

        SchemeResolution r = resolver(registry, sourceOf(Map.of()))
                .resolve(new LocationSchemeQuery("NP", PaymentMode.MPM, "DOMESTIC"));

        assertEquals("NEPAL", r.scheme());
    }

    @Test
    @DisplayName("a throwing / unreachable hours source degrades to UNVERIFIED, not to a failed resolution")
    void unreachableHoursSourceDoesNotBreakResolution() {
        PartnerSchemeRegistry registry = registryOf(
                new PartnerSchemeRecord("ZEROPAY", "KR", "BOTH", true, true, 0));
        SchemeOperatingHoursSource exploding = schemeId -> {
            throw new IllegalStateException("config-registry down");
        };

        SchemeResolution r = resolver(registry, exploding)
                .resolve(new LocationSchemeQuery("KR", PaymentMode.MPM, "DOMESTIC"));

        assertEquals("ZEROPAY", r.scheme());
    }

    @Test
    @DisplayName("the default source answers UNVERIFIED and says so loudly")
    void defaultSourceIsUnverifiedAndLoud() {
        assertTrue(new UnverifiedSchemeOperatingHoursSource().weeklySchedule("ZEROPAY").isEmpty());
        // The banner must name the missing property AND the capability that is therefore absent.
        assertTrue(UnverifiedSchemeOperatingHoursSource.BANNER.contains("gmepay.config-registry.enabled"));
        assertTrue(UnverifiedSchemeOperatingHoursSource.BANNER.contains("SCHEME_CLOSED"));
        assertTrue(UnverifiedSchemeOperatingHoursSource.BANNER.contains("T3-6"));
    }

    @Test
    @DisplayName("no hours source at all (legacy constructor): behaviour is exactly as before")
    void legacyConstructorIsUnchanged() {
        PartnerSchemeRegistry registry = registryOf(
                new PartnerSchemeRecord("ZEROPAY", "KR", "BOTH", true, true, 0));

        SchemeResolution r = new LocationSchemeResolver(registry)
                .resolve(new LocationSchemeQuery("KR", PaymentMode.MPM, "DOMESTIC"));

        assertEquals("ZEROPAY", r.scheme());
    }

    // ------------------------------------ ADR-016 failover candidate list

    @Test
    @DisplayName("resolveCandidates drops a closed rail from the failover order, keeping priority")
    void resolveCandidates_dropsClosedRails() {
        PartnerSchemeRegistry registry = registryOf(
                new PartnerSchemeRecord("NEPAL", "NP", "BOTH", true, true, 0, 20L, "fonepay.com"),
                new PartnerSchemeRecord("NEPAL_FONEPAY_DIRECT", "NP", "BOTH", true, true, 1, 21L,
                        "fonepay.com"));
        // NEPAL closed; the direct integration has no rows (UNVERIFIED) and survives.
        SchemeOperatingHoursSource hours = sourceOf(Map.of(
                "NEPAL", List.of(row("NEPAL", 1, "20:00", "23:00", "Asia/Kathmandu"))));

        List<PartnerSchemeView> candidates = resolver(registry, hours).resolveCandidates(
                "fonepay.com", new LocationSchemeQuery("NP", PaymentMode.MPM, "DOMESTIC"));

        assertEquals(1, candidates.size());
        assertEquals("NEPAL_FONEPAY_DIRECT", candidates.get(0).schemeId());
    }

    @Test
    @DisplayName("resolveCandidates with EVERY rail closed → SCHEME_CLOSED, not an empty list")
    void resolveCandidates_allClosed_isSchemeClosed() {
        PartnerSchemeRegistry registry = registryOf(
                new PartnerSchemeRecord("NEPAL", "NP", "BOTH", true, true, 0, 20L, "fonepay.com"));
        SchemeOperatingHoursSource hours = sourceOf(Map.of(
                "NEPAL", List.of(row("NEPAL", 1, "20:00", "23:00", "Asia/Kathmandu"))));

        ApiException ex = assertThrows(ApiException.class, () -> resolver(registry, hours)
                .resolveCandidates("fonepay.com",
                        new LocationSchemeQuery("NP", PaymentMode.MPM, "DOMESTIC")));

        assertEquals(ErrorCode.SCHEME_CLOSED, ex.errorCode());
    }
}
