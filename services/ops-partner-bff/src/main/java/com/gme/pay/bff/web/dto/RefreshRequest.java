package com.gme.pay.bff.web.dto;

/**
 * Wire shape for {@code POST /v1/auth/refresh}. Phase-1 stub regenerates a
 * token from any non-empty input.
 *
 * <p>REPLACE WITH auth-identity integration in Phase 4 hardening — that ticket
 * adds refresh-token rotation and revocation.
 */
public record RefreshRequest(String token) {}
