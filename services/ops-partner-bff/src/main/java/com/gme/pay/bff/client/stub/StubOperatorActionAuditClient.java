package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.OperatorActionAuditClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase-1 in-memory stub of {@link OperatorActionAuditClient}. Captures every
 * recorded operator action in a thread-safe list so (a) the BFF boots standalone
 * and (b) controller tests can assert the audit record was written before the
 * upstream delegation ran.
 *
 * <p>Default bean: wired unless {@code gmepay.operator-action-audit.client=rest}
 * selects the live {@link com.gme.pay.bff.client.rest.RestOperatorActionAuditClient}.
 */
@Component
@ConditionalOnProperty(
        name = "gmepay.operator-action-audit.client",
        havingValue = "stub",
        matchIfMissing = true)
public class StubOperatorActionAuditClient implements OperatorActionAuditClient {

    private final AtomicLong seq = new AtomicLong(1);
    private final List<OperatorActionRecord> captured = new CopyOnWriteArrayList<>();

    @Override
    public OperatorActionRecord record(String action, String target, String actor, String reason) {
        OperatorActionRecord rec = new OperatorActionRecord(
                "OA-" + seq.getAndIncrement(), action, target, actor, reason, Instant.now());
        captured.add(rec);
        return rec;
    }

    /**
     * In-memory capture is always durable, so the durable write behaves like {@link #record}
     * (and never throws) — the fail-closed path is exercised against the live REST client or a
     * failing test double.
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
