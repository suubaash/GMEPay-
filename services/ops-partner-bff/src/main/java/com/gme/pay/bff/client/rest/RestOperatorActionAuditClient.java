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
 * Remote {@link OperatorActionAuditClient}. POSTs each operator action to
 * {@code POST /v1/audit/operator-actions} over Spring 6 {@link RestClient}. Active only when
 * {@code gmepay.operator-action-audit.client=rest} is set explicitly.
 *
 * <h2>WARNING: the endpoint this calls does not exist yet</h2>
 * As of this commit <b>no service in this repository exposes
 * {@code POST /v1/audit/operator-actions}</b> — not auth-identity, which this client's base URL points
 * at, and not config-registry, whose {@code /v1/audit} surfaces
 * ({@code AuditLogController}, {@code AuditIntegrityController}) are read-only. Selecting {@code rest}
 * today therefore means: {@link #record} logs a warning per action and returns a local echo, and
 * {@link #recordDurable} <b>fails closed and blocks every audited operator action</b> (500).
 *
 * <p>This is exactly why the default was NOT flipped to this client when the stub's
 * {@code matchIfMissing = true} defect was fixed. T1-1's first step — verify the real client's
 * endpoint is real before inverting a default — is what surfaced it. The default is instead
 * {@code db} ({@link com.gme.pay.bff.client.db.DbOperatorActionAuditClient}, the durable
 * {@code operator_action_audit} table); this class is kept, and kept selectable, for the day a
 * hash-chained write endpoint ships on config-registry (the recorded follow-up), at which point
 * {@code gmepay.auth-identity.base-url} below must be re-pointed at that service.
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
            return post(action, target, actor, reason);
        } catch (Exception e) {
            // Best-effort: a failed write is logged and the operator action still proceeds.
            log.warn("operator-action audit write failed (action={}, target={}): {}",
                    action, target, e.getMessage());
            // Best-effort local echo so callers always get a non-null record.
            return new OperatorActionRecord(null, action, target, actor, reason, Instant.now());
        }
    }

    @Override
    public OperatorActionRecord recordDurable(String action, String target, String actor, String reason) {
        try {
            OperatorActionRecord rec = post(action, target, actor, reason);
            if (rec == null) {
                throw new AuditWriteException(
                        "operator-action audit endpoint returned no record (action=" + action + ")", null);
            }
            return rec;
        } catch (AuditWriteException e) {
            throw e;
        } catch (Exception e) {
            // Fail closed: a money-affecting action must not proceed without a durable audit record.
            log.error("durable operator-action audit write failed (action={}, target={}): {}",
                    action, target, e.getMessage());
            throw new AuditWriteException(
                    "operator-action audit write failed for action=" + action, e);
        }
    }

    private OperatorActionRecord post(String action, String target, String actor, String reason) {
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
        return resp == null ? null : resp.toRecord(action, target, actor, reason);
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
