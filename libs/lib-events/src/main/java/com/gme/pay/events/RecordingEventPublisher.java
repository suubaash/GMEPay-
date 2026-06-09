package com.gme.pay.events;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Test helper {@link EventPublisher} that retains every published event in
 * insertion order so tests can make assertions about what was emitted.
 *
 * <p>Lives in {@code main} (not {@code test}) so that consumers across services
 * can reuse it from their own test suites. It is intentionally free of Spring
 * annotations — consumers wire it where needed.
 *
 * <p>Thread-safe for typical single-threaded test use; external synchronization
 * is the caller's responsibility if used concurrently.
 */
public final class RecordingEventPublisher implements EventPublisher {

    private final List<DomainEvent> published = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void publish(DomainEvent event) {
        Objects.requireNonNull(event, "event");
        published.add(event);
    }

    /** @return an unmodifiable snapshot of the events captured so far (in insertion order). */
    public List<DomainEvent> published() {
        synchronized (published) {
            return List.copyOf(published);
        }
    }

    /** Removes all captured events. */
    public void clear() {
        published.clear();
    }
}
