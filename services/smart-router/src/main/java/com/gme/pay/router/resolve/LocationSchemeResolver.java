package com.gme.pay.router.resolve;

import com.gme.pay.contracts.PartnerSchemeView;
import com.gme.pay.contracts.SchemeAvailability;
import com.gme.pay.domain.Direction;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
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
 *   <li>{@link ErrorCode#SCHEME_CLOSED} — rows match country + direction + mode,
 *       but EVERY matching scheme is outside its published operating window right
 *       now ({@code scheme_operating_hours}, V024). Gap <b>T3-6</b>.</li>
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
 *
 * <h2>T3-6 — the time-window branch this resolver used to lack</h2>
 * V024's migration header states that "the router … needs this to decide 'can this transaction route
 * NOW?'", yet the resolver rejected only on direction, presentment mode and no-scheme: there was no time
 * branch and no {@code SCHEME_CLOSED}. The window is now the LAST narrowing step, deliberately after the
 * three structural ones, so the caller still learns the most specific structural reason first — a
 * corridor that is inbound-only reports DIRECTION_NOT_ENABLED, not "closed".
 *
 * <p><b>Three verdicts, never two.</b> A scheme is dropped only when a seeded row for the current
 * scheme-LOCAL weekday affirmatively excludes the current scheme-local time. A scheme with no usable row
 * is {@code UNVERIFIED} and is KEPT (logged, not assumed open) — four of the nine rostered schemes,
 * including two of the three live adapters, have no seeded rows, so refusing on UNVERIFIED would take
 * live corridors down over missing reference data. {@code SCHEME_CLOSED} is raised only when every
 * surviving candidate is affirmatively closed.
 */
@Service
public class LocationSchemeResolver {

    private static final Logger log = LoggerFactory.getLogger(LocationSchemeResolver.class);

    private final PartnerSchemeRegistry registry;
    /** T3-6 window source. Null in legacy unit slices — resolution then behaves exactly as before. */
    @Nullable private final SchemeOperatingHoursSource operatingHours;
    private final Clock clock;

    @Autowired
    public LocationSchemeResolver(PartnerSchemeRegistry registry,
                                 @Nullable SchemeOperatingHoursSource operatingHours) {
        this(registry, operatingHours, Clock.systemUTC());
    }

    /** Test constructor — explicit clock so a closed window is reproducible. */
    public LocationSchemeResolver(PartnerSchemeRegistry registry,
                                  @Nullable SchemeOperatingHoursSource operatingHours,
                                  Clock clock) {
        this.registry = registry;
        this.operatingHours = operatingHours;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Registry-only resolver (no operating-window narrowing) — pre-T3-6 shape, kept for callers/tests. */
    public LocationSchemeResolver(PartnerSchemeRegistry registry) {
        this(registry, null, Clock.systemUTC());
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

        // Branch 5 (T3-6): mode matches, but every candidate is outside its operating window NOW.
        List<String> routable = routableNow(candidates);
        if (routable.isEmpty()) {
            throw new ApiException(ErrorCode.SCHEME_CLOSED,
                    "every scheme in " + country + " for " + query.mode() + "/" + query.direction()
                            + " is outside its operating window: " + closedDetail(candidates));
        }

        // Success: priority-ordered winner + the ambiguity surface.
        return SchemeResolution.of(routable);
    }

    /**
     * ADR-016 QR-classified failover resolution: given the QR's own classified
     * network GUID plus the same country/mode/direction filter context, return the
     * ORDERED candidate list (ascending {@code priority}, ACTIVE only) of every
     * {@code partner_scheme} row whose {@code networkIdentifier} CSV CONTAINS the
     * requested network AND matches country + direction + presentment mode. This
     * ordered list IS the failover order: the caller dispatches to element 0, and
     * on a technical failure walks to the next.
     *
     * <p>Reuses the country/direction/mode axes of {@link #resolve(LocationSchemeQuery)};
     * the only added axis is CSV network membership ({@link PartnerSchemeRecord#servesNetwork}).
     * When several partners serve the same network in the same corridor, ALL are
     * returned in priority order — the multi-candidate failover case.
     *
     * @param network the QR-classified network GUID (e.g. {@code fonepay.com},
     *                {@code com.zeropay}); required.
     * @param query   the country/mode/direction filter context.
     * @return ordered candidate views (never empty on success).
     * @throws ApiException {@link ErrorCode#VALIDATION_ERROR} for a blank network or
     *         an invalid base query; {@link ErrorCode#NO_SCHEME_FOR_LOCATION} when no
     *         ACTIVE row in the corridor serves the network for this mode+direction.
     */
    public List<PartnerSchemeView> resolveCandidates(String network, LocationSchemeQuery query) {
        String country = validate(query);
        if (network == null || network.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "network required");
        }
        String requestedDirection = query.direction().trim().toUpperCase(Locale.ROOT);

        // Registry returns ACTIVE/enabled rows in priority order; narrow by the
        // three QR axes (network CSV membership, direction, mode) and preserve
        // that priority order as the failover order.
        List<PartnerSchemeView> candidates = registry.schemesForCountry(country).stream()
                .filter(r -> r.servesNetwork(network))
                .filter(r -> r.enabledFor(requestedDirection))
                .filter(r -> r.supports(query.mode()))
                .map(PartnerSchemeRecord::toView)
                .toList();

        if (candidates.isEmpty()) {
            throw new ApiException(ErrorCode.NO_SCHEME_FOR_LOCATION,
                    "no ACTIVE scheme in " + country + " serves network " + network
                            + " for " + query.mode() + "/" + query.direction());
        }

        // T3-6: a CLOSED rail is not a failover target — drop it from the ordered list (preserving
        // priority order) rather than letting the caller submit to it and take a certain failure. When
        // every candidate is closed the whole resolution is SCHEME_CLOSED.
        List<PartnerSchemeView> routable = new ArrayList<>(candidates.size());
        for (PartnerSchemeView candidate : candidates) {
            if (availability(candidate.schemeId()).closed()) {
                continue;
            }
            routable.add(candidate);
        }
        if (routable.isEmpty()) {
            throw new ApiException(ErrorCode.SCHEME_CLOSED,
                    "every candidate serving network " + network + " in " + country
                            + " is outside its operating window: "
                            + closedDetail(candidates.stream().map(PartnerSchemeView::schemeId).toList()));
        }
        return List.copyOf(routable);
    }

    // ------------------------- T3-6 operating window --------------------------

    /**
     * The subset of {@code schemeIds} whose seeded operating window does not affirmatively exclude now.
     * OPEN and UNVERIFIED both survive; only CLOSED is dropped. Order is preserved (it is the priority
     * / failover order).
     */
    private List<String> routableNow(List<String> schemeIds) {
        if (operatingHours == null) {
            return schemeIds;
        }
        List<String> routable = new ArrayList<>(schemeIds.size());
        for (String schemeId : schemeIds) {
            if (availability(schemeId).closed()) {
                continue;
            }
            routable.add(schemeId);
        }
        return routable;
    }

    /**
     * Evaluate one scheme's window. UNVERIFIED is logged at WARN — the router must never quietly treat
     * an unseeded schedule as "open"; it treats it as "unknown, and here is the record".
     */
    private SchemeAvailability availability(String schemeId) {
        if (operatingHours == null) {
            return SchemeAvailability.evaluate(schemeId, List.of(), Instant.now(clock));
        }
        List<com.gme.pay.contracts.SchemeOperatingHoursView> rows;
        try {
            rows = operatingHours.weeklySchedule(schemeId);
        } catch (RuntimeException ex) {
            // The port contracts implementations not to throw; degrade to UNVERIFIED regardless.
            log.warn("operating-hours lookup for {} threw ({}) — window UNVERIFIED", schemeId,
                    ex.getMessage());
            rows = List.of();
        }
        SchemeAvailability availability =
                SchemeAvailability.evaluate(schemeId, rows, Instant.now(clock));
        if (availability.unverified()) {
            log.warn("scheme {} operating window UNVERIFIED — keeping it as a routing candidate and"
                    + " recording the fact (NOT assuming open): {}", schemeId, availability.reason());
        } else if (availability.closed()) {
            log.warn("scheme {} dropped from routing — {}", schemeId, availability.reason());
        }
        return availability;
    }

    /** Human-readable "why every candidate was dropped", for the SCHEME_CLOSED message. */
    private String closedDetail(List<String> schemeIds) {
        StringBuilder sb = new StringBuilder();
        for (String schemeId : schemeIds) {
            SchemeAvailability availability = availability(schemeId);
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(availability.reason());
        }
        return sb.toString();
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
