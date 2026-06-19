package com.gme.pay.bff.client.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.gme.pay.bff.client.RbacAdminClient;
import com.gme.pay.bff.web.dto.PermissionDef;
import com.gme.pay.bff.web.dto.RoleSummary;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Production {@link RbacAdminClient}. Calls auth-identity's RBAC management API ({@code /v1/rbac/*})
 * and reshapes it to the Admin-UI RBAC contract. Active when {@code gmepay.auth-identity.client=rest};
 * otherwise {@link com.gme.pay.bff.client.stub.StubRbacAdminClient} is wired.
 *
 * <p>Reads degrade gracefully (empty list) when auth-identity is unreachable, so the page shows a
 * real-but-empty state rather than an error. Writes propagate the failure so the UI surfaces it.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.auth-identity.client", havingValue = "rest")
public class RestRbacAdminClient implements RbacAdminClient {

    private static final Logger log = LoggerFactory.getLogger(RestRbacAdminClient.class);

    private final RestClient restClient;

    @Autowired
    public RestRbacAdminClient(
            RestClient.Builder builder,
            @Value("${gmepay.auth-identity.base-url:http://auth-identity:8080}") String baseUrl) {
        this(builder.baseUrl(baseUrl).build());
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestRbacAdminClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public List<RoleSummary> listRoles() {
        JsonNode arr = getArray("/v1/rbac/roles");
        List<RoleSummary> out = new ArrayList<>();
        for (JsonNode r : arr) {
            out.add(toRole(r));
        }
        return out;
    }

    @Override
    public List<PermissionDef> listPermissions() {
        JsonNode arr = getArray("/v1/rbac/permissions");
        List<PermissionDef> out = new ArrayList<>();
        for (JsonNode p : arr) {
            out.add(new PermissionDef(text(p, "code"), text(p, "resource"),
                    text(p, "action"), text(p, "description")));
        }
        return out;
    }

    @Override
    public RoleSummary putRolePermissions(String roleCode, List<String> grants) {
        // Resolve the role (id + current grants) and the permission code→id map.
        JsonNode role = findRole(roleCode);
        if (role == null) {
            throw new IllegalArgumentException("role not found: " + roleCode);
        }
        long roleId = role.path("id").asLong();
        List<String> current = codes(role.path("permissions"));
        Map<String, Long> permIdByCode = permissionIdByCode();

        List<String> target = grants == null ? List.of() : grants;
        for (String code : target) {
            if (!current.contains(code)) {
                grant(roleId, code); // add
            }
        }
        for (String code : current) {
            if (!target.contains(code)) {
                Long pid = permIdByCode.get(code);
                if (pid != null) {
                    revoke(roleId, pid); // remove
                }
            }
        }
        // Return the freshly-read role so the UI reflects exactly what persisted.
        JsonNode updated = findRole(roleCode);
        return updated != null ? toRole(updated)
                : new RoleSummary(roleCode, text(role, "description"), role.path("userCount").asLong(), target);
    }

    @Override
    public RoleSummary createRole(String name, List<String> basePermissions) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", name);
        body.put("permissionCodes", basePermissions == null ? List.of() : basePermissions);
        JsonNode created = restClient.post()
                .uri("/v1/rbac/roles")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return created != null ? toRole(created) : new RoleSummary(name, null, 0, basePermissions);
    }

    // ---- helpers ----

    private void grant(long roleId, String permissionCode) {
        restClient.post()
                .uri("/v1/rbac/roles/{id}/permissions", roleId)
                .body(Map.of("permissionCode", permissionCode))
                .retrieve()
                .toBodilessEntity();
    }

    private void revoke(long roleId, long permissionId) {
        restClient.delete()
                .uri("/v1/rbac/roles/{rid}/permissions/{pid}", roleId, permissionId)
                .retrieve()
                .toBodilessEntity();
    }

    private JsonNode findRole(String roleCode) {
        for (JsonNode r : getArray("/v1/rbac/roles")) {
            if (roleCode.equals(text(r, "code"))) {
                return r;
            }
        }
        return null;
    }

    private Map<String, Long> permissionIdByCode() {
        Map<String, Long> map = new HashMap<>();
        for (JsonNode p : getArray("/v1/rbac/permissions")) {
            map.put(text(p, "code"), p.path("id").asLong());
        }
        return map;
    }

    private static RoleSummary toRole(JsonNode r) {
        return new RoleSummary(text(r, "code"), text(r, "description"),
                r.path("userCount").asLong(), codes(r.path("permissions")));
    }

    private static List<String> codes(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    private JsonNode getArray(String path) {
        try {
            JsonNode node = restClient.get().uri(path).retrieve().body(JsonNode.class);
            return (node != null && node.isArray()) ? node : com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        } catch (ResourceAccessException network) {
            log.warn("auth-identity unreachable on {}: {}", path, network.getMessage());
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        } catch (RuntimeException e) {
            log.warn("auth-identity error on {}: {}", path, e.getMessage());
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
