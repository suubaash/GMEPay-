package com.gme.pay.bff.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Wire shape for {@code POST /v1/admin/partners}. Mirrors the Admin UI
 * partner-form fields. The {@code settlementRoundingMode} is the textual
 * {@code java.math.RoundingMode} name (e.g. {@code "HALF_UP"}, {@code "DOWN"});
 * the BFF passes it through to config-registry, which is the source of truth
 * for the partner record.
 */
public record PartnerCreateRequest(
        @NotBlank String partnerId,
        @NotBlank String type,
        @NotBlank String settlementCurrency,
        @NotBlank String settlementRoundingMode
) {}
