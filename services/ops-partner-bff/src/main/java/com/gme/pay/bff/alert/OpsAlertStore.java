package com.gme.pay.bff.alert;

import com.gme.pay.contracts.events.OpsAlertPayload;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory, bounded store of the most-recent {@code ops.alert} events consumed from
 * {@code gmepay.ops.alert}. This closes the alert loop (#5): the Operations wave emits
 * ops alerts but nothing consumed them — now the BFF retains a rolling window so the
 * control tower and {@code GET /v1/admin/ops/alerts} can surface them.
 *
 * <p><b>Scope.</b> A rolling in-memory buffer (capacity {@code gmepay.ops.alerts.capacity},
 * default {@value #DEFAULT_CAPACITY}); it is intentionally not durable across restarts.
 * A durable JPA-backed store and a real pager / on-call push are documented follow-ups.
 *
 * <p>Thread-safe: {@link #add} and the query methods synchronize on the backing deque, so
 * the Kafka listener thread and request threads never see a torn view.
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
