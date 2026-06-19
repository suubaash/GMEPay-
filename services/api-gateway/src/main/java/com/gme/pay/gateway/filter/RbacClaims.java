package com.gme.pay.gateway.filter;

import java.util.List;

/**
 * A principal's resolved RBAC claims, fetched from auth-identity {@code /v1/rbac/resolve} and
 * stamped by {@link RbacClaimStampingFilter} into the downstream {@code X-Gme-*} headers.
 */
public record RbacClaims(
        String principalId,
        String tenantId,
        List<String> permissions,
        List<String> roles,
        String constraints) {}
