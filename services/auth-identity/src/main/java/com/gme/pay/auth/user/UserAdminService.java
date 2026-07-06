package com.gme.pay.auth.user;

import com.gme.pay.auth.persistence.PrincipalEntity;
import com.gme.pay.auth.persistence.PrincipalRepository;
import com.gme.pay.auth.persistence.RoleEntity;
import com.gme.pay.auth.persistence.RoleRepository;
import com.gme.pay.auth.rbac.RbacResolutionService;
import com.gme.pay.auth.user.UserDtos.InviteUserRequest;
import com.gme.pay.auth.user.UserDtos.UpdateUserRequest;
import com.gme.pay.auth.user.UserDtos.UserView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The operator user-management surface: list operator principals, invite new ones, edit their role
 * set, and deactivate / reactivate them. Backs the admin dashboard's Users page. The user store is
 * the {@code principals} table (V002) filtered to {@link PrincipalEntity.Type#OPERATOR} — no new
 * table; a "user" is an OPERATOR principal with its {@code principal_roles} grants.
 *
 * <p>Every mutation that changes a principal's effective roles evicts the
 * {@link RbacResolutionService} cache for that principal so the next token mint sees it immediately
 * (mirrors {@link com.gme.pay.auth.rbac.RbacAdminService#assignRole}).
 */
@Service
public class UserAdminService {

    private final PrincipalRepository principals;
    private final RoleRepository roles;
    private final RbacResolutionService resolution;

    public UserAdminService(PrincipalRepository principals, RoleRepository roles,
                            RbacResolutionService resolution) {
        this.principals = principals;
        this.roles = roles;
        this.resolution = resolution;
    }

    @Transactional(readOnly = true)
    public List<UserView> listOperators() {
        return principals.findByType(PrincipalEntity.Type.OPERATOR).stream()
                .map(UserAdminService::toView)
                .toList();
    }

    @Transactional
    public UserView invite(InviteUserRequest req) {
        String email = require(req.email(), "email");
        principals.findByUsername(email).ifPresent(p -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "user already exists: " + email);
        });
        PrincipalEntity p = new PrincipalEntity(
                PrincipalEntity.Type.OPERATOR, email, email, null, Instant.now());
        p.setEmail(email);
        applyRoles(p, req.roles());
        principals.save(p);
        // lastLoginAt stays null → status derives to INVITED.
        return toView(p);
    }

    @Transactional
    public UserView updateRoles(Long id, UpdateUserRequest req) {
        PrincipalEntity p = require(id);
        p.getRoles().clear();
        applyRoles(p, req.roles());
        principals.save(p);
        resolution.evict(id);
        return toView(p);
    }

    @Transactional
    public UserView deactivate(Long id) {
        PrincipalEntity p = require(id);
        p.setStatus(PrincipalEntity.Status.DISABLED);
        principals.save(p);
        resolution.evict(id);
        return toView(p);
    }

    @Transactional
    public UserView reactivate(Long id) {
        PrincipalEntity p = require(id);
        p.setStatus(PrincipalEntity.Status.ACTIVE);
        principals.save(p);
        resolution.evict(id);
        return toView(p);
    }

    // -------------------------------------------------------------------- internals

    /** Add roles by code, failing the request if a code is unknown. Clears nothing (caller does). */
    private void applyRoles(PrincipalEntity p, List<String> roleCodes) {
        if (roleCodes == null) {
            return;
        }
        for (String code : roleCodes) {
            if (code == null || code.isBlank()) {
                continue;
            }
            RoleEntity role = roles.findByCode(code.trim()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "role not found: " + code));
            p.addRole(role);
        }
    }

    private PrincipalEntity require(Long id) {
        return principals.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found: " + id));
    }

    private static String require(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return v.trim();
    }

    private static UserView toView(PrincipalEntity p) {
        TreeSet<String> roleCodes = new TreeSet<>();
        for (RoleEntity r : p.getRoles()) {
            roleCodes.add(r.getCode());
        }
        return new UserView(p.getId(), p.getDisplayName(), p.getEmail(),
                new ArrayList<>(roleCodes), status(p), p.getLastLoginAt());
    }

    /**
     * Derive the UI-facing status from the principal's stored status + login history:
     * ACTIVE with no login yet → an outstanding {@code INVITED}; LOCKED collapses to
     * {@code DISABLED} (the UI has no lock state); otherwise the stored status name.
     */
    private static String status(PrincipalEntity p) {
        PrincipalEntity.Status s = p.getStatus();
        if (s == PrincipalEntity.Status.ACTIVE && p.getLastLoginAt() == null) {
            return "INVITED";
        }
        if (s == PrincipalEntity.Status.LOCKED) {
            return "DISABLED";
        }
        return s.name();
    }
}
