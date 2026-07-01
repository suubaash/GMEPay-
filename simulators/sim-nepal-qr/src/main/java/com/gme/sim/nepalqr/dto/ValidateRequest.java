package com.gme.sim.nepalqr.dto;

/** Body of POST /api/qr/validate/ : {"qr":"<string>"}. */
public record ValidateRequest(String qr) {}
