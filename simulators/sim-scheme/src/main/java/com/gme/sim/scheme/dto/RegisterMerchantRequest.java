package com.gme.sim.scheme.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterMerchantRequest(
        @NotBlank String merchantId,
        @NotBlank String name,
        @NotBlank String city,
        @NotBlank String mcc
) {}
