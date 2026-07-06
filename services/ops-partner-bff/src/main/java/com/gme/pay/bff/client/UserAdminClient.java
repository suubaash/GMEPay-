package com.gme.pay.bff.client;

import com.gme.pay.bff.web.dto.UserSummary;
import java.util.List;

/**
 * BFF adapter onto auth-identity's operator user-management API ({@code /v1/users/*}), reshaped for
 * the Admin-UI Users page contract ({@code /v1/admin/users/*}).
 *
 * <p>Two implementations, mutually exclusive via {@code gmepay.auth-identity.client}:
 * {@link com.gme.pay.bff.client.rest.RestUserAdminClient} (live HTTP, {@code =rest}) and
 * {@link com.gme.pay.bff.client.stub.StubUserAdminClient} (in-memory, default) so the BFF and the
 * Users page run standalone for local dev / tests. Mirrors {@link RbacAdminClient}.
 */
public interface UserAdminClient {

    /** All operator users with their role codes, status, and last-login. */
    List<UserSummary> listUsers();

    /** Invite a new operator ({@code email} becomes the username); returns the created user. */
    UserSummary invite(String email, List<String> roles);

    /** Replace a user's full role set; returns the updated user. */
    UserSummary updateRoles(String id, List<String> roles);

    /** Deactivate (DISABLE) a user; returns the updated user. */
    UserSummary deactivate(String id);

    /** Reactivate a previously disabled user; returns the updated user. */
    UserSummary reactivate(String id);
}
