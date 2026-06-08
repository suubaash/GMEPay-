package com.gme.pay.qr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * Request body for POST /v1/qr/cpm/generate (WBS 5.3-T03).
 *
 * <p>Amounts are carried as {@link BigDecimal} and serialised to JSON as strings to avoid
 * floating-point precision issues ({@code @JsonSerialize} is applied at the mapper level).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CpmGenerateRequest(

        @NotBlank(message = "scheme_id is required")
        String schemeId,

        @NotBlank(message = "direction is required")
        @Pattern(regexp = "domestic|inbound|outbound|hub",
                 message = "direction must be one of: domestic, inbound, outbound, hub")
        String direction,

        @NotBlank(message = "customer_ref is required")
        String customerRef,

        @NotBlank(message = "partner_txn_ref is required")
        String partnerTxnRef,

        @NotBlank(message = "country_code is required")
        @Pattern(regexp = "[A-Z]{2}", message = "country_code must be ISO 3166-1 alpha-2 (e.g. KR)")
        String countryCode,

        /**
         * Optional prefunding reservation amount in USD. Must be positive if provided.
         * If absent, the resolved scheme's max_txn_amount_usd is used.
         */
        @DecimalMin(value = "0", inclusive = false,
                    message = "prefund_reserve_usd must be greater than 0")
        BigDecimal prefundReserveUsd
) {}
