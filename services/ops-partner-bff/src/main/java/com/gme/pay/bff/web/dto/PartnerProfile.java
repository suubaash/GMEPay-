package com.gme.pay.bff.web.dto;

import com.gme.pay.contracts.PartnerView;

import java.math.RoundingMode;
import java.time.Instant;

/**
 * Partner Portal "Profile" page payload. Combines the partner identity fields
 * (config-registry) with an onboarding timestamp. Phase-1 stubs the onboarding
 * timestamp; in production it is sourced from config-registry's partner record.
 *
 * @deprecated Slice 1 DTO collapse — new consumers should bind directly to
 * {@link PartnerView} from {@code lib-api-contracts}. This record stays as an
 * Expand-phase alias so the Partner Portal UI's existing JSON contract (the
 * fields it reads) is unchanged. New fields land on {@link PartnerView}, not
 * here. The Contract migration drops this record.
 */
@Deprecated(forRemoval = true, since = "Slice 1 — see docs/PARTNER_SETUP_PLAN.md")
public record PartnerProfile(
        String partnerId,
        String type,
        String settlementCurrency,
        RoundingMode settlementRoundingMode,
        Instant onboardedAt
) {

    /**
     * Adapt a canonical {@link PartnerView} plus an onboarding timestamp into
     * the legacy profile shape. {@code view} may be {@code null}, in which case
     * a {@code null} profile is returned.
     */
    public static PartnerProfile fromView(PartnerView view, Instant onboardedAt) {
        if (view == null) {
            return null;
        }
        return new PartnerProfile(
                view.partnerCode(),
                view.type() == null ? null : view.type().name(),
                view.settlementCurrency(),
                view.settlementRoundingMode(),
                onboardedAt);
    }
}
