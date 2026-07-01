package com.gme.sim.nepalqr.dto;

/**
 * Issuance-extension signed body: {"data":"<base64(json)>","signature":"<base64>"}.
 * The mock base64-decodes {@code data}; {@code signature} is soft-logged only
 * (no real RSA verification — this is a sim).
 */
public record SignedEnvelope(String data, String signature) {}
