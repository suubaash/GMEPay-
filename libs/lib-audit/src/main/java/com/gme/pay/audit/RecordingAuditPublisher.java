package com.gme.pay.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Test helper {@link AuditPublisher} that retains every published event in insertion
 * order so tests can make assertions about what was emitted. Modelled on
 * {@code RecordingEventPublisher} in {@code lib-events}.
 *
 * <p>Lives in {@code main} (not {@code test}) so any service can pull it into its
 * own test fixtures via the normal {@code testImplementation} dependency on
 * {@code lib-audit}.
 */
public final class RecordingAuditPublisher implements AuditPublisher {

    private final List<AuditEvent> published = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void publish(AuditEvent event) {
        Objects.requireNonNull(event, "event");
        published.add(event);
    }

    /** Unmodifiable snapshot of every event captured so far, in publish order. */
    public List<AuditEvent> published() {
        synchronized (published) {
            return List.copyOf(published);
        }
    }

    public void clear() {
        published.clear();
    }
}
