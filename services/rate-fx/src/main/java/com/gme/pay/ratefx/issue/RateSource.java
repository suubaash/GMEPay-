package com.gme.pay.ratefx.issue;

/**
 * How a leg's cost rate is resolved at quote time (RATE-04 §3.2, WBS 4.1-T01). Configured per leg on
 * the pricing rule ({@code rate_coll_source} / {@code rate_pay_source}).
 *
 * <ul>
 *   <li>{@link #IDENTITY} — the leg settles in USD; the rate is hard-coded 1.0 (no lookup).</li>
 *   <li>{@link #LIVE} — default for a non-identity cross-border leg; the latest treasury snapshot
 *       (the XE scheduler upserts these) is read at quote time.</li>
 *   <li>{@link #MANUAL} — an operator-entered override snapshot is used instead of LIVE.</li>
 *   <li>{@link #PARTNER} — a per-transaction quote from Partner B is authoritative; read at both
 *       quote and commit time, with a commit-time deviation guard (WBS 4.6).</li>
 * </ul>
 *
 * <p>IDENTITY is resolved structurally by {@link com.gme.pay.ratefx.RateEngine} (a USD leg forces
 * rate 1.0), so LIVE and MANUAL are both served by the treasury snapshot store; the source name on
 * the snapshot row selects between them.
 */
public enum RateSource {
    IDENTITY,
    LIVE,
    MANUAL,
    PARTNER;

    /** {@code true} only for {@link #IDENTITY} — a USD settlement leg priced at 1.0. */
    public boolean isIdentity() {
        return this == IDENTITY;
    }

    /** {@code true} only for {@link #PARTNER} — the externally-quoted, deviation-guarded source. */
    public boolean isPartner() {
        return this == PARTNER;
    }

    /** Parse a nullable/blank source string to {@link #LIVE} (the default), case-insensitively. */
    public static RateSource fromNullable(String raw) {
        if (raw == null || raw.isBlank()) {
            return LIVE;
        }
        return RateSource.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
