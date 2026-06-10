package com.gme.pay.bff.web.dto;

/**
 * Wire shape for {@code POST /v1/auth/login}. Phase-1 stub: any non-empty
 * username with password {@code "demo"} returns a mock JWT.
 *
 * <p>REPLACE WITH auth-identity integration in Phase 4 hardening — that ticket
 * adds HMAC/RBAC and a real JWT issuer; this DTO is expected to grow a
 * second-factor field at that point.
 */
public record LoginRequest(String username, String password) {}
