package com.gme.sim.scheme.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record AuthorizeRequest(
        @NotBlank String mode,          // CPM | MPM_STATIC | MPM_DYNAMIC
        String qrPayload,               // required for MPM modes
        String cpmToken,                // required for CPM mode
        @NotNull  BigDecimal amount,
        @NotBlank String currency,
        @NotBlank String payerRef
) {}
