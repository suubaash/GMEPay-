package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.OperatorActionAuditClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory stub of {@link OperatorActionAuditClient}. Captures every recorded operator action in a
 * thread-safe list so controller tests can assert the audit record was written before the upstream
 * delegation ran.
 *
 * <h2>OPT-IN ONLY — this is NOT an audit trail</h2>
 * This bean used to carry {@code matchIfMissing = true} while
 * {@code GMEPAY_OPERATOR_ACTION_AUDIT_CLIENT} was set in <b>no</b> values file, no compose service and
 * no properties file — so it was the live "audit trail" in every environment. What that meant:
 *
 * <ul>
 *   <li>ids came from a per-JVM {@code AtomicLong}, so every replica minted {@code OA-1}, {@code OA-2},
 *       … independently and <b>operator-action audit ids collided</b> at N&gt;1;</li>
 *   <li>the record of who paused the platform / suspended which partner was split across replicas and
 *       <b>lost on every restart</b>;</li>
 *   <li>{@link #recordDurable} — the fail-closed write whose whole purpose is to <em>block</em> a
 *       money/state-affecting operator action that has no audit trail — could never fail, so the
 *       guarantee was decorative.</li>
 * </ul>
 *
 * <p>It is now reachable only by explicitly setting
 * {@code gmepay.operator-action-audit.client=stub}, and doing so logs a {@code WARN} at construction
 * naming exactly that. The default is {@link com.gme.pay.bff.client.db.DbOperatorActionAuditClient}
 * (the durable {@code operator_action_audit} table); an unrecognised selector value leaves <b>no</b>
 * bean at all, so the service refuses to start rather than silently degrading — the same three-way
 * shape T1-1 established for the credential clients.
 */
@Component
@ConditionalOnProperty(
        name = "gmepay.operator-action-audit.client",
        havingValue = "stub")
public class StubOperatorActionAuditClient implements OperatorActionAuditClient {

    private static final Logger log =
            LoggerFactory.getLogger(StubOperatorActionAuditClient.class);

    private final AtomicLong seq = new AtomicLong(1);
    private final List<OperatorActionRecord> captured = new CopyOnWriteArrayList<>();

    public StubOperatorActionAuditClient() {
        log.warn("operator-action audit: IN-MEMORY STUB selected via "
                + "gmepay.operator-action-audit.client=stub. THERE IS NO AUDIT TRAIL: records live in "
                + "this JVM's heap, ids ('OA-n') restart at 1 on every replica and every restart so "
                + "they COLLIDE at >1 replica, everything is lost on restart, and recordDurable() "
                + "CANNOT FAIL — so 'no money-affecting operator action without a durable audit "
                + "record' is not being enforced. Intended for unit slices only; remove the override "
                + "to use the durable operator_action_audit table.");
    }

    @Override
    public OperatorActionRecord record(String action, String target, String actor, String reason) {
        OperatorActionRecord rec = new OperatorActionRecord(
                "OA-" + seq.getAndIncrement(), action, target, actor, reason, Instant.now());
        captured.add(rec);
        return rec;
    }

    /**
     * <b>Not durable, despite the name.</b> An in-memory list cannot fail, so this behaves exactly
     * like {@link #record} and never throws — which is why the fail-closed contract is unenforceable
     * under this implementation and why the class-level {@code WARN} says so. The real fail-closed
     * path lives in {@code DbOperatorActionAuditClient} (a failed commit throws) and is exercised
     * there and against a failing test double.
     */
    @Override
    public OperatorActionRecord recordDurable(String action, String target, String actor, String reason) {
        return record(action, target, actor, reason);
    }

    /** Test/observability hook — the operator actions recorded so far, oldest first. */
    public List<OperatorActionRecord> captured() {
        return List.copyOf(captured);
    }
}
