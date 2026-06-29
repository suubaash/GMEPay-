package com.gme.pay.bff.client.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.gme.pay.bff.client.ApprovalQueueClient;
import com.gme.pay.bff.web.dto.ApprovalSummary;
import com.gme.pay.rbac.RbacHeaders;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Production {@link ApprovalQueueClient}. Relays the Admin-UI approval queue to auth-identity's
 * {@code /v1/approvals}. Active when {@code gmepay.auth-identity.client=rest}; otherwise
 * {@link com.gme.pay.bff.client.stub.StubApprovalQueueClient} is wired.
 *
 * <p>approve/reject forward the acting operator's {@code X-Gme-Principal-Id} +
 * {@code X-Gme-Permissions} so auth-identity applies its per-step permission gate + maker-checker
 * against the real approver — the BFF never asserts authority on the operator's behalf.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.auth-identity.client", havingValue = "rest")
public class RestApprovalQueueClient implements ApprovalQueueClient {

    private static final Logger log = LoggerFactory.getLogger(RestApprovalQueueClient.class);

    private final RestClient restClient;

    @Autowired
    public RestApprovalQueueClient(
            RestClient.Builder builder,
            @Value("${gmepay.auth-identity.base-url:http://auth-identity:8080}") String baseUrl,
            @Value("${gmepay.auth-identity.internal-secret:}") String internalSecret) {
        this(buildClient(builder, baseUrl, internalSecret));
    }

    /**
     * auth-identity's {@code /v1/approvals/**} is an internal-only surface; when it enforces the
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

    RestApprovalQueueClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public List<ApprovalSummary> listPending() {
        try {
            JsonNode arr = restClient.get().uri("/v1/approvals").retrieve().body(JsonNode.class);
            List<ApprovalSummary> out = new ArrayList<>();
            if (arr != null && arr.isArray()) {
                arr.forEach(n -> out.add(toSummary(n)));
            }
            return out;
        } catch (ResourceAccessException network) {
            log.warn("auth-identity unreachable on /v1/approvals: {}", network.getMessage());
            return List.of();
        } catch (RuntimeException e) {
            log.warn("auth-identity error on /v1/approvals: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public ApprovalSummary get(Long id) {
        JsonNode n = restClient.get().uri("/v1/approvals/{id}", id).retrieve().body(JsonNode.class);
        return n == null ? null : toSummary(n);
    }

    @Override
    public ApprovalSummary approve(Long id, String approverId, Set<String> approverPermissions, String reason) {
        return decide(id, "approve", approverId, approverPermissions, reason);
    }

    @Override
    public ApprovalSummary reject(Long id, String approverId, Set<String> approverPermissions, String reason) {
        return decide(id, "reject", approverId, approverPermissions, reason);
    }

    private ApprovalSummary decide(Long id, String action, String approverId,
                                   Set<String> approverPermissions, String reason) {
        JsonNode n = restClient.post()
                .uri("/v1/approvals/{id}/{action}", id, action)
                .header(RbacHeaders.PRINCIPAL_ID, approverId == null ? "" : approverId)
                .header(RbacHeaders.PERMISSIONS, String.join(",", approverPermissions == null ? Set.of() : approverPermissions))
                .body(Map.of("reason", reason == null ? "" : reason))
                .retrieve()
                .body(JsonNode.class);
        return n == null ? null : toSummary(n);
    }

    // ---- mapping ----

    private static ApprovalSummary toSummary(JsonNode n) {
        List<ApprovalSummary.Decision> decisions = new ArrayList<>();
        JsonNode ds = n.path("decisions");
        if (ds.isArray()) {
            for (JsonNode d : ds) {
                decisions.add(new ApprovalSummary.Decision(
                        d.path("stepIndex").asInt(), text(d, "requiredPermission"), text(d, "approverId"),
                        text(d, "decision"), d.path("cfoOverride").asBoolean(), text(d, "reason"),
                        text(d, "decidedAt")));
            }
        }
        return new ApprovalSummary(
                n.path("id").isNumber() ? n.path("id").asLong() : null,
                text(n, "requestType"), text(n, "subjectRef"), decimalOf(n, "amount"),
                text(n, "currency"), text(n, "tierLabel"), text(n, "status"),
                n.path("requiredSteps").asInt(), n.path("currentStep").asInt(),
                text(n, "requestedBy"), text(n, "requestedAt"), text(n, "decidedAt"),
                text(n, "rejectReason"), decisions);
    }

    private static BigDecimal decimalOf(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? null : new BigDecimal(v.asText());
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
