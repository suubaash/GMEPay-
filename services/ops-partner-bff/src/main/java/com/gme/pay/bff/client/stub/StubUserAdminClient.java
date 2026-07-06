package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.UserAdminClient;
import com.gme.pay.bff.web.dto.UserSummary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * In-memory {@link UserAdminClient} so the BFF + Users page boot standalone (local dev / tests)
 * without auth-identity. Wired by default; {@link com.gme.pay.bff.client.rest.RestUserAdminClient}
 * takes over when {@code gmepay.auth-identity.client=rest}.
 *
 * <p>Seeded to mirror the admin-ui {@code FIXTURE_USERS} (usersApi.js) but with REAL role codes
 * (HUB_ADMIN / HUB_OPERATOR / SUPPORT from the RBAC catalogue) so the page renders realistic data
 * offline. Mutations persist in-process for the life of this JVM; state resets on BFF restart —
 * durable persistence needs the REST client + auth-identity DB. Mirrors {@link StubRbacAdminClient}.
 */
@Component
@ConditionalOnProperty(name = "gmepay.auth-identity.client", havingValue = "stub", matchIfMissing = true)
public class StubUserAdminClient implements UserAdminClient {

    /** Mutable user registry seeded to mirror the admin-ui fixture. Guarded by its own monitor. */
    private final List<UserSummary> users = Collections.synchronizedList(new ArrayList<>(List.of(
            new UserSummary("u-001", "Subash Sharma", "subash@gmeremit.com",
                    List.of("HUB_ADMIN", "HUB_OPERATOR"), "ACTIVE", "2026-06-15T09:12:00+09:00"),
            new UserSummary("u-002", "Ji-yeon Park", "jiyeon.park@gmeremit.com",
                    List.of("HUB_OPERATOR", "SUPPORT"), "ACTIVE", "2026-06-14T17:45:00+09:00"),
            new UserSummary("u-003", "Arjun Thapa", "arjun.thapa@gmeremit.com",
                    List.of("HUB_OPERATOR"), "ACTIVE", "2026-06-13T11:00:00+09:00"),
            new UserSummary("u-004", "Mei Lin", "mei.lin@gmeremit.com",
                    List.of("SUPPORT"), "INVITED", null),
            new UserSummary("u-005", "Carlos Reyes", "carlos.reyes@gmeremit.com",
                    List.of("HUB_OPERATOR", "SUPPORT"), "DISABLED", "2026-05-30T08:00:00+09:00"))));

    /** Monotonic id source for invited users (u-006, u-007, ...). */
    private final AtomicInteger seq = new AtomicInteger(5);

    @Override
    public List<UserSummary> listUsers() {
        synchronized (users) {
            return List.copyOf(users);
        }
    }

    @Override
    public UserSummary invite(String email, List<String> roles) {
        String name = deriveName(email);
        List<String> roleList = roles == null ? List.of() : List.copyOf(roles);
        synchronized (users) {
            for (UserSummary u : users) {
                if (u.email() != null && u.email().equalsIgnoreCase(email)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "user already exists: " + email);
                }
            }
            UserSummary created = new UserSummary(
                    String.format("u-%03d", seq.incrementAndGet()), name, email, roleList, "INVITED", null);
            users.add(created);
            return created;
        }
    }

    @Override
    public UserSummary updateRoles(String id, List<String> roles) {
        List<String> roleList = roles == null ? List.of() : List.copyOf(roles);
        return mutate(id, u -> new UserSummary(u.id(), u.name(), u.email(), roleList, u.status(), u.lastLoginAt()));
    }

    @Override
    public UserSummary deactivate(String id) {
        return mutate(id, u -> new UserSummary(u.id(), u.name(), u.email(), u.roles(), "DISABLED", u.lastLoginAt()));
    }

    @Override
    public UserSummary reactivate(String id) {
        return mutate(id, u -> new UserSummary(u.id(), u.name(), u.email(), u.roles(),
                u.lastLoginAt() == null ? "INVITED" : "ACTIVE", u.lastLoginAt()));
    }

    // ---- helpers ----

    private UserSummary mutate(String id, java.util.function.UnaryOperator<UserSummary> op) {
        synchronized (users) {
            for (int i = 0; i < users.size(); i++) {
                if (users.get(i).id().equals(id)) {
                    UserSummary updated = op.apply(users.get(i));
                    users.set(i, updated);
                    return updated;
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found: " + id);
    }

    /** Turn an email local part into a display name ("jane.doe@x" → "Jane Doe"). */
    private static String deriveName(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        String local = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        String[] parts = local.split("[._-]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.length() == 0 ? local : sb.toString();
    }
}
