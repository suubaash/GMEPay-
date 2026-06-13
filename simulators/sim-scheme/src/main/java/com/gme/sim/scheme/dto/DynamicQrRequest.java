package com.gme.sim.scheme.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record DynamicQrRequest(
        @NotBlank String merchantId,
        @NotNull  BigDecimal amount,
        @NotBlank String currency
) {}
