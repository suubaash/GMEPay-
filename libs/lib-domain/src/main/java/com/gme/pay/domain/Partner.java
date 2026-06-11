package com.gme.pay.domain;

import java.math.RoundingMode;
import java.util.Objects;

/**
 * A partner that connects to GMEPay+. Owned by config-registry. Carries the per-partner
 * {@code settlementRoundingMode} that dictates how the partner's settlement liability is booked
 * at transaction creation (e.g. a partner that books round-DOWN to 2dp vs GMEPay+ default HALF_UP).
 * The residual between the precise amount and the booked amount is posted to the rounding ledger.
 *
 * <h2>Partner ID schism resolution — Slice 1 cross-cutting bug fix</h2>
 *
 * Prior to Slice 1 this record only carried a {@code String partnerId}, while every consuming
 * service ({@code PrincipalEntity}, {@code WebhookEndpointEntity},
 * settlement-reconciliation, {@code PartnerCredentialPort}) modelled the foreign key as
 * {@code Long}. The record now carries <b>both</b> in line with the universal join-key
 * decision in {@code docs/PARTNER_SETUP_PLAN.md} §"Cross-cutting bug fixes":
 *
 * <ul>
 *   <li>{@code partnerId} — {@code BIGINT} surrogate, the universal join key used by every
 *       service that holds a partner foreign key. May be {@code null} during the Expand phase
 *       (ADR-013) for legacy rows whose surrogate has not yet been populated.</li>
 *   <li>{@code partnerCode} — {@code VARCHAR(20) UNIQUE}, the human-facing business code
 *       (e.g. {@code "GMEREMIT"}, {@code "SENDMN"}) that operators type and external systems
 *       see. URL paths under {@code /v1/partners/{id}} are routed by this code.</li>
 * </ul>
 *
 * The legacy factory {@link #of(String, PartnerType, String)} remains supported for callers
 * that only know the business code; it leaves {@code partnerId} {@code null} so the caller
 * can be migrated incrementally.
 */
public record Partner(
        Long partnerId,
        String partnerCode,
        PartnerType type,
        String settlementCurrency,
        RoundingMode settlementRoundingMode) {

    public Partner {
        // partnerId may be null during the Expand phase (rows newly minted by operators
        // before V003 backfill has issued a surrogate) — do not require it.
        Objects.requireNonNull(partnerCode, "partnerCode required");
        Objects.requireNonNull(type, "type required");
        if (settlementRoundingMode == null) {
            settlementRoundingMode = RoundingMode.HALF_UP; // default policy
        }
    }

    /**
     * Create a partner with the default {@code HALF_UP} settlement rounding and no surrogate
     * id yet. Equivalent to the pre-schism factory; the {@code partnerCode} argument is the
     * old {@code partnerId} string (e.g. {@code "GMEREMIT"}). Kept so existing call-sites
     * compile during the Expand phase.
     */
    public static Partner of(String partnerCode, PartnerType type, String settlementCurrency) {
        return new Partner(null, partnerCode, type, settlementCurrency, RoundingMode.HALF_UP);
    }

    /**
     * Create a partner with an explicit rounding mode but no surrogate id yet. Convenience
     * for call-sites that previously used {@code new Partner(String, PartnerType, String,
     * RoundingMode)} — kept to keep the Expand-phase migration small.
     */
    public static Partner of(String partnerCode, PartnerType type, String settlementCurrency,
                             RoundingMode settlementRoundingMode) {
        return new Partner(null, partnerCode, type, settlementCurrency, settlementRoundingMode);
    }

    /**
     * Full-form factory carrying both the surrogate id and the business code. Used by the
     * persistence boundary in config-registry once the row has its {@code BIGSERIAL}
     * assigned.
     */
    public static Partner of(Long partnerId, String partnerCode, PartnerType type,
                             String settlementCurrency, RoundingMode settlementRoundingMode) {
        return new Partner(partnerId, partnerCode, type, settlementCurrency, settlementRoundingMode);
    }
}
