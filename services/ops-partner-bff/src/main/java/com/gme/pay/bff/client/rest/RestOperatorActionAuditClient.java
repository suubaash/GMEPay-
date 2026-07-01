package com.gme.pay.bff.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.bff.client.OperatorActionAuditClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

/**
 * Production {@link OperatorActionAuditClient}. POSTs each operator action to
 * auth-identity's operator-action audit endpoint ({@code POST /v1/audit/operator-actions})
 * over Spring 6 {@link RestClient}. Active when
 * {@code gmepay.operator-action-audit.client=rest}; otherwise the in-memory
 * {@link com.gme.pay.bff.client.stub.StubOperatorActionAuditClient} wins.
 *
 * <p>The audit log is owned by auth-identity, so the base URL reuses
 * {@code gmepay.auth-identity.base-url} and the internal-auth shared secret rides in the
 * {@code X-Gme-Internal} header (blank in local dev = gate off), matching the other
 * auth-identity-backed adapters.
 *
 * <p><b>Best-effort.</b> {@link #record} NEVER throws — a failed write is logged and a
 * local echo record is returned so the operator action still proceeds.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.operator-action-audit.client", havingValue = "rest")
public class RestOperatorActionAuditClient implements OperatorActionAuditClient {

    private static final Logger log = LoggerFactory.getLogger(RestOperatorActionAuditClient.class);

    private final RestClient restClient;
    private final String internalSecret;

    @Autowired
    public RestOperatorActionAuditClient(
            @Value("${gmepay.auth-identity.base-url:http://auth-identity:8080}") String baseUrl,
            @Value("${gmepay.auth-identity.internal-secret:}") String internalSecret) {
        this(RestClient.builder().baseUrl(baseUrl).build(), internalSecret);
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestOperatorActionAuditClient(RestClient restClient, String internalSecret) {
        this.restClient = restClient;
        this.internalSecret = internalSecret;
    }

    @Override
    public OperatorActionRecord record(String action, String target, String actor, String reason) {
        try {
            WireRecord resp = restClient.post()
                    .uri("/v1/audit/operator-actions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        if (internalSecret != null && !internalSecret.isBlank()) {
                            h.set("X-Gme-Internal", internalSecret);
                        }
                    })
                    .body(Map.of(
                            "action", nz(action),
                            "target", nz(target),
                            "actor", nz(actor),
                            "reason", reason == null ? "" : reason))
                    .retrieve()
                    .body(WireRecord.class);
            if (resp != null) {
                return resp.toRecord(action, target, actor, reason);
            }
        } catch (Exception e) {
            // Audit is a durable side-effect, not a gate — never fail the operator action.
            log.warn("operator-action audit write failed (action={}, target={}): {}",
                    action, target, e.getMessage());
        }
        // Best-effort local echo so callers always get a non-null record.
        return new OperatorActionRecord(null, action, target, actor, reason, Instant.now());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** auth-identity's persisted audit-row wire shape (subset). Unknown fields ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WireRecord(String id, String action, String target, String actor,
                              String reason, Instant at) {
        OperatorActionRecord toRecord(String reqAction, String reqTarget, String reqActor, String reqReason) {
            return new OperatorActionRecord(
                    id,
                    action != null ? action : reqAction,
                    target != null ? target : reqTarget,
                    actor != null ? actor : reqActor,
                    reason != null ? reason : reqReason,
                    at != null ? at : Instant.now());
        }
    }
}
