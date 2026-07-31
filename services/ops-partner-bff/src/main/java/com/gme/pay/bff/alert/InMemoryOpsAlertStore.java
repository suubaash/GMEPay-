package com.gme.pay.bff.alert;

import com.gme.pay.contracts.events.OpsAlertPayload;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * Per-JVM, bounded {@link OpsAlertStore} — a rolling window of the most recent alerts (capacity
 * {@code gmepay.ops.alerts.capacity}, default {@value #DEFAULT_CAPACITY}), not durable across
 * restarts.
 *
 * <p><b>This is no longer the production store</b> — {@link JpaOpsAlertStore} is. It is kept for
 * unit slices and for running the BFF on a laptop with no database, and it is reachable only by
 * setting {@code gmepay.ops.alerts.store=memory}, which logs a {@code WARN} naming the consequences:
 * a divergent alerts list per replica, per-replica {@code seq} numbering (so an ack can hit the wrong
 * alert), acks invisible to other replicas, and the whole window lost on restart. In other words
 * <b>N=1</b>.
 *
 * <p>Thread-safe: {@link #add}, the query methods and the {@link #update} mutator all synchronize on
 * the backing deque, so the Kafka listener thread, request threads and the escalation scheduler never
 * see a torn view.
 */
public class InMemoryOpsAlertStore implements OpsAlertStore {

    /** Default rolling-window size when {@code gmepay.ops.alerts.capacity} is unset. */
    static final int DEFAULT_CAPACITY = 200;

    private final int capacity;
    private final AtomicLong seq = new AtomicLong(1);
    /** Newest at the head (addFirst); evicts from the tail once at capacity. */
    private final Deque<OpsAlertView> alerts = new ArrayDeque<>();

    public InMemoryOpsAlertStore(int capacity) {
        this.capacity = capacity <= 0 ? DEFAULT_CAPACITY : capacity;
    }

    @Override
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

    @Override
    public List<OpsAlertView> recent(String severity, String alertType, int limit) {
        int capped = OpsAlertStore.clampLimit(limit);
        List<OpsAlertView> out = new ArrayList<>();
        synchronized (alerts) {
            for (OpsAlertView a : alerts) {
                if (matches(a.severity(), severity) && matches(a.alertType(), alertType)) {
                    out.add(a);
                    if (out.size() >= capped) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    @Override
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

    @Override
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

    @Override
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
