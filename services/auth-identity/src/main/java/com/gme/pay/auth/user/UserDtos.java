package com.gme.pay.auth.user;

import java.time.Instant;
import java.util.List;

/**
 * DTOs for the operator user-management API ({@link UserAdminController}). Grouped in one file as
 * small request/response records, mirroring {@link com.gme.pay.auth.rbac.RbacAdminDtos}.
 *
 * <p>The user store is the {@code principals} table (V002) filtered to {@link
 * com.gme.pay.auth.persistence.PrincipalEntity.Type#OPERATOR}. A {@link UserView} projects a
 * principal plus its granted role codes; {@code status} is derived (see
 * {@link UserAdminService}) into the UI's {@code ACTIVE|INVITED|DISABLED} vocabulary.
 */
public final class UserDtos {

    private UserDtos() {}

    /**
     * An operator user as the admin dashboard consumes it. {@code roles} are role codes;
     * {@code status} is the derived lifecycle state; {@code lastLoginAt}=null means the user has
     * never signed in (an outstanding invite).
     */
    public record UserView(Long id, String name, String email, List<String> roles,
                           String status, Instant lastLoginAt) {}

    /** Invite a new operator: {@code email} becomes the username; {@code roles} are role codes. */
    public record InviteUserRequest(String email, List<String> roles) {}

    /** Replace an operator's full role set (by role code). */
    public record UpdateUserRequest(List<String> roles) {}
}
