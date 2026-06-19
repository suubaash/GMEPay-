package com.gme.pay.bff.web.dto;

import java.util.List;

/**
 * A role as the Admin-UI RBAC page consumes it ({@code GET /v1/admin/rbac/roles}). Maps from
 * auth-identity's {@code RoleView}: {@code role} is the role code, {@code userCount} is the
 * number of principals currently holding it, {@code permissions} are the granted permission codes.
 */
public record RoleSummary(String role, String description, long userCount, List<String> permissions) {}
