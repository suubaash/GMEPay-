package com.gme.pay.bff.web.dto;

import java.time.Instant;

/**
 * Wire shape for {@code POST /v1/auth/login} and {@code POST /v1/auth/refresh}.
 * Phase-1 stub: {@code token} is a mock JWT-shaped string, {@code expiresAt}
 * is one hour from issue, {@code role} defaults to {@code "ADMIN"}.
 */
public record LoginResponse(String token, Instant expiresAt, String role) {}
