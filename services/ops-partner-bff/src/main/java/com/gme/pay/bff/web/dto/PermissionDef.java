package com.gme.pay.bff.web.dto;

/**
 * A permission definition as the Admin-UI RBAC page consumes it
 * ({@code GET /v1/admin/rbac/permissions}). Maps from auth-identity's {@code PermissionView}:
 * {@code permission} is the {@code resource.action} code.
 */
public record PermissionDef(String permission, String resource, String action, String description) {}
