package com.gme.pay.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Plain JUnit 5 tests for the two reference {@link EventPublisher} implementations
 * shipped in {@code lib-events}.
 *
 * <p>No Spring context, no Docker — these classes are pure Java.
 */
class EventPublisherTest {

    /** Minimal {@link DomainEvent} used to drive the publishers. */
    private static final class TestEvent implements DomainEvent {
        private final String type;
        private final String aggregateId;
        private final Instant occurredAt;

        TestEvent(String type, String aggregateId) {
            this.type = type;
            this.aggregateId = aggregateId;
            this.occurredAt = Instant.now();
        }

        @Override public String eventType()    { return type; }
        @Override public String aggregateId()  { return aggregateId; }
        @Override public Instant occurredAt()  { return occurredAt; }
    }

    @Test
    @DisplayName("LogEventPublisher.publish does not throw on a well-formed event")
    void logEventPublisherDoesNotThrow() {
        LogEventPublisher publisher = new LogEventPublisher();
        TestEvent event = new TestEvent("Sample", "agg-1");
        assertDoesNotThrow(() -> publisher.publish(event));
    }

    @Test
    @DisplayName("LogEventPublisher.publish rejects null event with NullPointerException")
    void logEventPublisherRejectsNull() {
        LogEventPublisher publisher = new LogEventPublisher();
        assertThrows(NullPointerException.class, () -> publisher.publish(null));
    }

    @Test
    @DisplayName("RecordingEventPublisher captures every published event in insertion order")
    void recordingEventPublisherCapturesOrder() {
        RecordingEventPublisher publisher = new RecordingEventPublisher();

        TestEvent first  = new TestEvent("A", "agg-1");
        TestEvent second = new TestEvent("B", "agg-2");
        TestEvent third  = new TestEvent("C", "agg-3");

        publisher.publish(first);
        publisher.publish(second);
        publisher.publish(third);

        List<DomainEvent> captured = publisher.published();
        assertNotNull(captured);
        assertEquals(3, captured.size(), "all three events should be captured");
        assertEquals("A",     captured.get(0).eventType());
        assertEquals("agg-1", captured.get(0).aggregateId());
        assertEquals("B",     captured.get(1).eventType());
        assertEquals("agg-2", captured.get(1).aggregateId());
        assertEquals("C",     captured.get(2).eventType());
        assertEquals("agg-3", captured.get(2).aggregateId());

        publisher.clear();
        assertEquals(0, publisher.published().size(), "clear() should empty the buffer");
    }
}
