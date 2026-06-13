package com.gme.sim.scheme.dto;

import jakarta.validation.constraints.NotBlank;

public record CpmTokenRequest(
        @NotBlank String customerId,
        @NotBlank String fundingRef
) {}
