package com.gme.pay.bff.web;

import com.gme.pay.bff.client.UserAdminClient;
import com.gme.pay.bff.web.dto.UserSummary;
import com.gme.pay.rbac.RequiresPermission;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin operator user-management BFF endpoints (admin-ui {@code /users} page → auth-identity
 * {@code /v1/users/*}).
 *
 * <ul>
 *   <li>{@code GET   /v1/admin/users} — all operator users ({@link UserSummary}[])</li>
 *   <li>{@code POST  /v1/admin/users/invite} — invite an operator (body: {email, roles[]})</li>
 *   <li>{@code PATCH /v1/admin/users/{id}} — replace a user's role set (body: {roles[]})</li>
 *   <li>{@code POST  /v1/admin/users/{id}/deactivate} — disable a user</li>
 *   <li>{@code POST  /v1/admin/users/{id}/reactivate} — re-enable a user</li>
 * </ul>
 *
 * <p>Mutations require {@code rbac.manage} (enforced at the API level when {@code gmepay.rbac.enabled}).
 * Reads are open to any authenticated operator so the page renders. Backed by {@link UserAdminClient}
 * (stub by default; live auth-identity when {@code gmepay.auth-identity.client=rest}). Mirrors
 * {@link RbacAdminController}.
 */
@RestController
@RequestMapping("/v1/admin/users")
public class UserAdminController {

    private final UserAdminClient users;

    public UserAdminController(UserAdminClient users) {
        this.users = users;
    }

    @GetMapping("")
    public List<UserSummary> list() {
        return users.listUsers();
    }

    @PostMapping("/invite")
    @RequiresPermission("rbac.manage")
    public UserSummary invite(@RequestBody InviteUserRequest body) {
        return users.invite(body == null ? null : body.email(),
                body == null ? List.of() : body.roles());
    }

    @PatchMapping("/{id}")
    @RequiresPermission("rbac.manage")
    public UserSummary updateRoles(@PathVariable String id, @RequestBody UpdateUserRequest body) {
        return users.updateRoles(id, body == null ? List.of() : body.roles());
    }

    @PostMapping("/{id}/deactivate")
    @RequiresPermission("rbac.manage")
    public UserSummary deactivate(@PathVariable String id) {
        return users.deactivate(id);
    }

    @PostMapping("/{id}/reactivate")
    @RequiresPermission("rbac.manage")
    public UserSummary reactivate(@PathVariable String id) {
        return users.reactivate(id);
    }

    /** POST invite body: new operator email + role codes. */
    public record InviteUserRequest(String email, List<String> roles) {}

    /** PATCH body: the full role code list to assign to the user. */
    public record UpdateUserRequest(List<String> roles) {}
}
