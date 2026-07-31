package com.gme.pay.bff.alert;

import com.gme.pay.contracts.events.OpsAlertPayload;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Store of the {@code ops.alert} events consumed from {@code gmepay.ops.alert}, together with the
 * paging delivery record and the operator acknowledgement ({@link OpsAlertView.Paging} /
 * {@link OpsAlertView.Ack}). Backs the control tower and {@code GET /v1/admin/ops/alerts}.
 *
 * <h2>This was the last single-replica ceiling in the platform</h2>
 * Until this became a port with a durable implementation, it was a 200-entry per-JVM
 * {@code ArrayDeque}, and it was the one thing keeping ops-partner-bff at one replica:
 *
 * <ul>
 *   <li>each replica held only the alerts <em>its own</em> Kafka consumer received, so
 *       {@code GET /v1/admin/ops/alerts} answered differently depending on which replica was hit
 *       and the control tower's counts were a fraction of the fleet's;</li>
 *   <li>{@code seq} came from a per-JVM {@code AtomicLong} starting at 1 on every replica and every
 *       restart, so {@code POST /v1/admin/ops/alerts/{id}/ack} could acknowledge a <em>different</em>
 *       alert than the operator clicked;</li>
 *   <li>an ack recorded on replica A was invisible on B, so B's escalation sweep kept escalating an
 *       alert a human had already acknowledged;</li>
 *   <li>a restart lost the window entirely.</li>
 * </ul>
 *
 * <h2>Implementations</h2>
 * {@link JpaOpsAlertStore} — the {@code ops_alerts} table (Flyway V001): durable, shared across
 * replicas, and the default. {@link InMemoryOpsAlertStore} — per-JVM, for unit slices and for a
 * laptop with no database; selecting it logs a {@code WARN} naming the N=1 ceiling it re-imposes.
 * Selection lives in {@link OpsAlertStoreConfig} ({@code gmepay.ops.alerts.store}).
 *
 * <p><b>Why a table and not Redis</b> is written out in full in the migration. The short version:
 * {@link #update} is a read-modify-write over a record with two independently-written fields (the
 * paging stamp from the Kafka thread and the escalation sweep, the ack from a request thread), which
 * on a Redis hash needs {@code WATCH}/Lua or an ack silently overwrites a concurrent paging stamp.
 * Add {@code seq} allocation, eviction and filtered newest-first queries and what is being described
 * is a table. The JPA implementation does that read-modify-write under {@code SELECT ... FOR UPDATE}.
 *
 * <p><b>Bounded by contract.</b> Every query is capped at {@link #MAX_LIMIT}; there is no
 * "unlimited" value. The deque bounded reads by silently discarding everything past 200 entries, and
 * a durable store must not replace that with an unbounded fetch into memory.
 *
 * <p>Thread-safe in every implementation: the Kafka listener thread, request threads and the
 * escalation scheduler must never see a torn view.
 */
public interface OpsAlertStore {

    /** Hard ceiling on any single query, whatever the caller asks for. Mirrors payment-executor. */
    int MAX_LIMIT = 500;

    /** Applied when a caller passes a non-positive limit. */
    int DEFAULT_LIMIT = 100;

    /** Store one consumed alert (newest-first) and return the stored view with its assigned seq. */
    OpsAlertView add(OpsAlertPayload payload);

    /**
     * Recent alerts, newest-first, optionally filtered by severity and/or alertType
     * (case-insensitive exact match; null/blank filter = no constraint). {@code limit} is clamped to
     * {@code [1, }{@value #MAX_LIMIT}{@code ]}; a non-positive value means {@value #DEFAULT_LIMIT}.
     */
    List<OpsAlertView> recent(String severity, String alertType, int limit);

    /** Look up a stored alert by its {@code seq}. */
    Optional<OpsAlertView> find(long seq);

    /**
     * Apply an update to the alert with the given {@code seq} (used to stamp the paging record and
     * the acknowledgement). Returns the updated view, or empty if the alert is not retained.
     *
     * <p>The mutator runs while the row is held exclusively, so a concurrent ack and paging stamp —
     * on the same replica or on different ones — cannot lose one another's field.
     */
    Optional<OpsAlertView> update(long seq, UnaryOperator<OpsAlertView> mutator);

    /** Number of retained alerts. */
    int size();

    /** Clamp a caller-supplied limit into {@code [1, MAX_LIMIT]}. */
    static int clampLimit(int limit) {
        return limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }
}
