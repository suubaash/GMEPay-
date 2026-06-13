package com.gme.sim.scheme.dto;

public record QrResponse(
        String mode,
        String qrPayload,
        String humanReadable
) {}
