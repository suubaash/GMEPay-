package com.gme.pay.auth.user;

import com.gme.pay.auth.user.UserDtos.InviteUserRequest;
import com.gme.pay.auth.user.UserDtos.UpdateUserRequest;
import com.gme.pay.auth.user.UserDtos.UserView;
import com.gme.pay.rbac.RequiresPermission;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator user-management API — the admin dashboard's Users page backend. Read + mutate the
 * OPERATOR principals in the {@code principals} table (V002) and their {@code principal_roles}
 * grants. Mirrors {@link com.gme.pay.auth.rbac.RbacAdminController}.
 *
 * <p><b>Perimeter (#90).</b> auth-identity is NOT fronted by the api-gateway — these routes are
 * reached server-to-server by the ops BFF — so the gate is the service-to-service internal-auth
 * filter ({@code gmepay.internal-auth}, covering {@code /v1/users/**}). The {@code rbac.manage}
 * {@link RequiresPermission} asserts the OPERATOR's authority and is enforced UPSTREAM (the BFF's
 * own {@code @RequiresPermission}); it is dormant in-process here because enabling
 * {@code gmepay.rbac.enabled} on auth-identity would 403 the BFF's unsigned calls.
 */
@RestController
@RequestMapping("/v1/users")
@RequiresPermission("rbac.manage")
public class UserAdminController {

    private final UserAdminService users;

    public UserAdminController(UserAdminService users) {
        this.users = users;
    }

    @GetMapping("")
    public List<UserView> listUsers() {
        return users.listOperators();
    }

    @PostMapping("/invite")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView invite(@RequestBody InviteUserRequest req) {
        return users.invite(req);
    }

    @PatchMapping("/{id}")
    public UserView updateRoles(@PathVariable Long id, @RequestBody UpdateUserRequest req) {
        return users.updateRoles(id, req);
    }

    @PostMapping("/{id}/deactivate")
    public UserView deactivate(@PathVariable Long id) {
        return users.deactivate(id);
    }

    @PostMapping("/{id}/reactivate")
    public UserView reactivate(@PathVariable Long id) {
        return users.reactivate(id);
    }
}
