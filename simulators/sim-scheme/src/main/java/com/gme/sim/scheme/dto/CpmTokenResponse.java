package com.gme.sim.scheme.dto;

public record CpmTokenResponse(
        String mode,
        String cpmToken,
        String expiresAt     // KST ISO-8601
) {}
