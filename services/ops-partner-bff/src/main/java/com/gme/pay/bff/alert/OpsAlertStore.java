package com.gme.pay.bff.alert;

import com.gme.pay.contracts.events.OpsAlertPayload;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * In-memory, bounded store of the most-recent {@code ops.alert} events consumed from
 * {@code gmepay.ops.alert}. This closes the alert loop (#5): the Operations wave emits
 * ops alerts but nothing consumed them — now the BFF retains a rolling window so the
 * control tower and {@code GET /v1/admin/ops/alerts} can surface them, along with the
 * paging delivery record and acknowledgement state ({@link OpsAlertView.Paging} /
 * {@link OpsAlertView.Ack}).
 *
 * <p><b>Scope.</b> A rolling in-memory buffer (capacity {@code gmepay.ops.alerts.capacity},
 * default {@value #DEFAULT_CAPACITY}); it is intentionally not durable across restarts.
 * A durable JPA-backed store is a documented follow-up.
 *
 * <h2>This is the ops-partner-bff replica ceiling, stated rather than papered over</h2>
 * The paging side of this service is now N&gt;1-safe (see {@link
 * com.gme.pay.bff.alert.paging.PagingCooldown}), but <b>this buffer is not</b>, and it was not
 * moved to Redis. What that costs, precisely:
 *
 * <ul>
 *   <li>Each replica holds only the alerts <em>its own</em> Kafka consumer received, so
 *       {@code GET /v1/admin/ops/alerts} returns a different list depending on which replica
 *       answers, and the control tower's counts are a fraction of the fleet's.</li>
 *   <li>An ack recorded on replica A is invisible on replica B, so an alert can look open after it
 *       was acknowledged — and B's escalation sweep will keep escalating it (bounded to one page
 *       per dedupe window by the shared cooldown, but it will not stop when a human acks).</li>
 *   <li>A restart loses the window entirely, which is pre-existing and documented above.</li>
 * </ul>
 *
 * <h2>Why Redis was not the answer here</h2>
 * Not effort, and not "it is only a display": Redis is the wrong <em>shape</em> for this store, and
 * a half-right implementation of an operator surface is worse than an accurate limitation.
 * {@link #update} is a read-modify-write over a record with two independently-written fields (the
 * paging stamp from the Kafka thread and the escalation sweep, the ack from a request thread). On a
 * Redis hash that needs {@code WATCH}/Lua optimistic concurrency, or an ack silently overwrites a
 * concurrent paging stamp and the alert shows as never-paged. Add {@code seq} allocation, capacity
 * eviction and filtered newest-first queries and what is being described is a table — which is
 * exactly the durable JPA store already recorded as the follow-up, and which would also fix
 * restart durability and give ack an audit trail. Building the Redis version first would mean
 * building it twice and shipping the weaker one.
 *
 * <p><b>Consequence:</b> run ops-partner-bff at <b>one replica</b> until it has that store, or
 * accept a divergent alerts view knowingly. It is an operator-surface correctness defect, not a
 * money one, and it does not affect any other service's ability to scale.
 *
 * <p>Thread-safe: {@link #add}, the query methods and the {@link #update} mutator all
 * synchronize on the backing deque, so the Kafka listener thread, request threads and the
 * escalation scheduler never see a torn view.
 */
@Component
public class OpsAlertStore {

    /** Default rolling-window size when {@code gmepay.ops.alerts.capacity} is unset. */
    static final int DEFAULT_CAPACITY = 200;

    private final int capacity;
    private final AtomicLong seq = new AtomicLong(1);
    /** Newest at the head (addFirst); evicts from the tail once at capacity. */
    private final Deque<OpsAlertView> alerts = new ArrayDeque<>();

    public OpsAlertStore(@Value("${gmepay.ops.alerts.capacity:200}") int capacity) {
        this.capacity = capacity <= 0 ? DEFAULT_CAPACITY : capacity;
    }

    /** Store one consumed alert (newest-first); evicts the oldest once at capacity. */
    public OpsAlertView add(OpsAlertPayload payload) {
        OpsAlertView view = OpsAlertView.from(seq.getAndIncrement(), payload);
        synchronized (alerts) {
            alerts.addFirst(view);
            while (alerts.size() > capacity) {
                alerts.removeLast();
            }
        }
        return view;
    }

    /**
     * Recent alerts, newest-first, optionally filtered by severity and/or alertType
     * (case-insensitive exact match; null/blank filter = no constraint), capped to
     * {@code limit} (<=0 = no cap).
     */
    public List<OpsAlertView> recent(String severity, String alertType, int limit) {
        List<OpsAlertView> out = new ArrayList<>();
        synchronized (alerts) {
            for (OpsAlertView a : alerts) {
                if (matches(a.severity(), severity) && matches(a.alertType(), alertType)) {
                    out.add(a);
                    if (limit > 0 && out.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** Look up a stored alert by its {@code seq}. */
    public Optional<OpsAlertView> find(long seq) {
        synchronized (alerts) {
            for (OpsAlertView a : alerts) {
                if (a.seq() == seq) {
                    return Optional.of(a);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Apply an in-place update to the alert with the given {@code seq} (used to stamp the
     * paging record and the acknowledgement). Returns the updated view, or empty if the
     * alert is no longer retained (evicted). The mutator runs under the store lock.
     */
    public Optional<OpsAlertView> update(long seq, UnaryOperator<OpsAlertView> mutator) {
        synchronized (alerts) {
            OpsAlertView[] arr = alerts.toArray(new OpsAlertView[0]);
            for (int i = 0; i < arr.length; i++) {
                if (arr[i].seq() == seq) {
                    OpsAlertView updated = mutator.apply(arr[i]);
                    arr[i] = updated;
                    alerts.clear();
                    for (OpsAlertView v : arr) {
                        alerts.addLast(v);
                    }
                    return Optional.of(updated);
                }
            }
        }
        return Optional.empty();
    }

    /** Current number of retained alerts. */
    public int size() {
        synchronized (alerts) {
            return alerts.size();
        }
    }

    private static boolean matches(String actual, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        return actual != null && actual.equalsIgnoreCase(filter.trim());
    }
}
