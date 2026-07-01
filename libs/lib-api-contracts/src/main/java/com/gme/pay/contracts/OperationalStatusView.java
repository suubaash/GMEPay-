package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Canonical read DTO for the platform's operational / kill-switch state — the
 * Operations-features wave (ops-console) read model. It answers "what is the
 * platform allowed to do right now": is everything globally paused, are we in
 * maintenance, and which partners / schemes / routes are individually
 * suspended.
 *
 * <p><b>Where this lives.</b> lib-api-contracts already carries the
 * jackson-annotations dependency and the money/wire conventions, so the
 * canonical, serialization-annotated read DTOs for the ops wave live here (same
 * home as {@link PartnerSchemeView} / {@link RuleView}).
 *
 * <ul>
 *   <li>{@code systemPaused} — the global master kill switch; when {@code true}
 *       the platform accepts nothing new regardless of the per-entity lists.</li>
 *   <li>{@code maintenanceMode} — a softer flag: the platform is under
 *       maintenance (degraded / read-mostly) but not fully halted.</li>
 *   <li>{@code suspendedPartners} — partner references (business codes / ids)
 *       currently suspended; never {@code null} (empty when none).</li>
 *   <li>{@code suspendedSchemes} — scheme ids currently suspended; never
 *       {@code null}.</li>
 *   <li>{@code suspendedRoutes} — route identifiers currently suspended; never
 *       {@code null}.</li>
 *   <li>{@code reason} — free-text operator reason for the current state;
 *       nullable when all-clear.</li>
 *   <li>{@code since} — ISO-8601 instant STRING the current state took effect;
 *       nullable when all-clear.</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} so {@code null} fields stay on the wire —
 * same contract as {@link PartnerView} / {@link PartnerSchemeView}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record OperationalStatusView(
        boolean systemPaused,
        boolean maintenanceMode,
        List<String> suspendedPartners,
        List<String> suspendedSchemes,
        List<String> suspendedRoutes,
        String reason,
        String since) {

    /**
     * The "all clear" default: nothing paused, nothing suspended, no reason and
     * no effective-since stamp. Empty (never {@code null}) suspension lists so
     * consumers can iterate safely.
     */
    public static final OperationalStatusView ALL_CLEAR =
            new OperationalStatusView(false, false, List.of(), List.of(), List.of(), null, null);

    /** Convenience factory returning the {@link #ALL_CLEAR} default. */
    public static OperationalStatusView allClear() {
        return ALL_CLEAR;
    }
}
