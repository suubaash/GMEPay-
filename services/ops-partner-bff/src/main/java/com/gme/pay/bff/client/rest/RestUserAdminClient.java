package com.gme.pay.bff.client.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.gme.pay.bff.client.UserAdminClient;
import com.gme.pay.bff.web.dto.UserSummary;
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
 * Production {@link UserAdminClient}. Calls auth-identity's user-management API ({@code /v1/users/*})
 * and reshapes it to the Admin-UI Users contract. Active when {@code gmepay.auth-identity.client=rest};
 * otherwise {@link com.gme.pay.bff.client.stub.StubUserAdminClient} is wired.
 *
 * <p>Reads degrade gracefully (empty list) when auth-identity is unreachable, so the page shows a
 * real-but-empty state rather than an error. Writes propagate the failure so the UI surfaces it.
 * Mirrors {@link RestRbacAdminClient}.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.auth-identity.client", havingValue = "rest")
public class RestUserAdminClient implements UserAdminClient {

    private static final Logger log = LoggerFactory.getLogger(RestUserAdminClient.class);

    private final RestClient restClient;

    @Autowired
    public RestUserAdminClient(
            RestClient.Builder builder,
            @Value("${gmepay.auth-identity.base-url:http://auth-identity:8080}") String baseUrl,
            @Value("${gmepay.auth-identity.internal-secret:}") String internalSecret) {
        this(buildClient(builder, baseUrl, internalSecret));
    }

    /**
     * auth-identity's {@code /v1/users/**} is an internal-only surface; when it enforces the
     * service-to-service internal-auth gate (#90), the ops BFF is a trusted caller and must present
     * the shared {@code X-Gme-Internal} token on every call. Blank secret = local dev (gate off).
     */
    private static RestClient buildClient(RestClient.Builder builder, String baseUrl, String internalSecret) {
        builder.baseUrl(baseUrl);
        if (internalSecret != null && !internalSecret.isBlank()) {
            builder.defaultHeader(
                    com.gme.pay.internalauth.InternalAuthHeaders.INTERNAL_TOKEN, internalSecret);
        }
        return builder.build();
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestUserAdminClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public List<UserSummary> listUsers() {
        JsonNode arr = getArray("/v1/users");
        List<UserSummary> out = new ArrayList<>();
        for (JsonNode u : arr) {
            out.add(toUser(u));
        }
        return out;
    }

    @Override
    public UserSummary invite(String email, List<String> roles) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("roles", roles == null ? List.of() : roles);
        JsonNode created = restClient.post()
                .uri("/v1/users/invite")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return created != null ? toUser(created)
                : new UserSummary(null, email, email, roles, "INVITED", null);
    }

    @Override
    public UserSummary updateRoles(String id, List<String> roles) {
        Map<String, Object> body = new HashMap<>();
        body.put("roles", roles == null ? List.of() : roles);
        JsonNode updated = restClient.patch()
                .uri("/v1/users/{id}", id)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return updated != null ? toUser(updated) : null;
    }

    @Override
    public UserSummary deactivate(String id) {
        return post("/v1/users/{id}/deactivate", id);
    }

    @Override
    public UserSummary reactivate(String id) {
        return post("/v1/users/{id}/reactivate", id);
    }

    // ---- helpers ----

    private UserSummary post(String path, String id) {
        JsonNode node = restClient.post()
                .uri(path, id)
                .retrieve()
                .body(JsonNode.class);
        return node != null ? toUser(node) : null;
    }

    /** Map auth-identity's UserView (id as number, lastLoginAt as instant) → UserSummary (strings). */
    private static UserSummary toUser(JsonNode u) {
        JsonNode idNode = u.get("id");
        String id = (idNode == null || idNode.isNull()) ? null : idNode.asText();
        return new UserSummary(id, text(u, "name"), text(u, "email"),
                codes(u.path("roles")), text(u, "status"), text(u, "lastLoginAt"));
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
