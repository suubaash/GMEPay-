package com.gme.pay.bff.web.dto;

import java.util.List;

/**
 * An operator user as the Admin-UI Users page consumes it ({@code GET /v1/admin/users}). Maps from
 * auth-identity's {@code UserView}: {@code id} is the principal id rendered as a string, {@code roles}
 * are role codes, {@code status} is {@code ACTIVE|INVITED|DISABLED}, and {@code lastLoginAt} is an
 * ISO-8601 instant string (or {@code null} when the user has never signed in).
 */
public record UserSummary(String id, String name, String email, List<String> roles,
                          String status, String lastLoginAt) {}
