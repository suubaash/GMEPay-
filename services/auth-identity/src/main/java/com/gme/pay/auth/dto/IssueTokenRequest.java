package com.gme.pay.auth.dto;

import java.util.Map;

/**
 * Request body for {@code POST /internal/auth/token/issue}.
 *
 * <p>Mints a short-lived HS256 service-to-service capability token (NOT a human
 * operator session token — those are owned by Keycloak per ADR-011). Consumed
 * by internal callers (api-gateway claim resolver, ops BFF, config-registry)
 * that need a signed, time-boxed assertion of identity + claims for downstream
 * internal calls.
 *
 * @param subject    principal identifier the token asserts (required, non-blank)
 *                   — e.g. {@code svc:config-registry} or {@code partner:42}.
 * @param claims     extra claims embedded in the token payload (role_code,
 *                   permissions, partner_id, …). May be {@code null} / empty.
 * @param ttlSeconds optional override of the configured default access-token TTL;
 *                   {@code null} or non-positive falls back to the service default.
 *                   Capped at the configured maximum to prevent over-long tokens.
 */
public record IssueTokenRequest(
        String subject,
        Map<String, Object> claims,
        Long ttlSeconds) {
}
