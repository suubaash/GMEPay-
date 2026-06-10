package com.gme.pay.bff.web.dto;

import java.math.RoundingMode;
import java.time.Instant;

/**
 * Partner Portal "Profile" page payload. Combines the partner identity fields
 * (config-registry) with an onboarding timestamp. Phase-1 stubs the onboarding
 * timestamp; in production it is sourced from config-registry's partner record.
 */
public record PartnerProfile(
        String partnerId,
        String type,
        String settlementCurrency,
        RoundingMode settlementRoundingMode,
        Instant onboardedAt
) {}
