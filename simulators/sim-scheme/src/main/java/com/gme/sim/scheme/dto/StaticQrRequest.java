package com.gme.sim.scheme.dto;

import jakarta.validation.constraints.NotBlank;

public record StaticQrRequest(@NotBlank String merchantId) {}
