package com.gme.pay.ledger.outbox;

import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link OutboxPublisher} that verifies retry behaviour on publisher failure.
 *
 * <ul>
 *   <li>First call: the underlying {@link EventPublisher} throws — the row's
 *       {@code publishedAt} must stay null so the next tick picks it up again
 *       (at-least-once contract).</li>
 *   <li>Second call: a happy {@link EventPublisher} stamps {@code publishedAt} and the row
 *       is no longer returned by {@code findUnpublished}.</li>
 * </ul>
 *
 * <p>{@link OutboxRepository} is a Mockito mock so this test stays in-process with no
 * Spring or database context. The mock's {@code save(...)} mutates the same instance the
 * publisher passed in, mirroring how Spring Data merges/persists JPA entities.
 */
class OutboxPublisherRetryTest {

    @Test
    void firstCallFails_rowStaysUnpublished_secondCallSucceeds_rowGetsStamped() {
        // Arrange: one unpublished row in a mocked repository.
        OutboxRepository repo = mock(OutboxRepository.class);
        OutboxEntity row = new OutboxEntity("agg-1", "journal.posted", "{}", Instant.now());
        row.setId(42L);

        // findUnpublished always returns the row UNTIL its publishedAt has been stamped.
        when(repo.findUnpublished(any(Pageable.class))).thenAnswer(inv ->
                row.getPublishedAt() == null ? List.of(row) : List.of());

        // save(...) is a passthrough: persistence is "done" by virtue of the entity mutation
        // the publisher already performed before calling save.
        when(repo.save(any(OutboxEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // --- First publish: failing publisher --------------------------------
        FailingPublisher failing = new FailingPublisher();
        OutboxPublisher publisher1 = new OutboxPublisher(repo, failing);
        publisher1.publishPending();

        assertEquals(1, failing.calls, "EventPublisher must have been called once");
        assertNull(row.getPublishedAt(),
                "publishedAt must stay null when EventPublisher throws — at-least-once retry");
        // No save should have happened on the failure path (we leave the row untouched).
        verify(repo, times(0)).save(any(OutboxEntity.class));

        // --- Second publish: happy publisher --------------------------------
        RecordingPublisher happy = new RecordingPublisher();
        OutboxPublisher publisher2 = new OutboxPublisher(repo, happy);
        publisher2.publishPending();

        assertEquals(1, happy.events.size(), "happy publisher should receive the event once");
        DomainEvent received = happy.events.get(0);
        assertEquals("journal.posted", received.eventType());
        assertEquals("agg-1", received.aggregateId());
        assertNotNull(received.occurredAt(), "occurredAt must be propagated from the outbox row");

        assertNotNull(row.getPublishedAt(),
                "publishedAt must be stamped after a successful publish");
        verify(repo, times(1)).save(row);

        // findUnpublished now returns empty (publishedAt is non-null).
        assertTrue(repo.findUnpublished(Pageable.unpaged()).isEmpty(),
                "row must no longer appear in findUnpublished after a successful publish");
    }

    // ----------------------------------------------------------------
    // Test doubles
    // ----------------------------------------------------------------

    private static final class FailingPublisher implements EventPublisher {
        int calls = 0;
        @Override public void publish(DomainEvent event) {
            Objects.requireNonNull(event);
            calls++;
            throw new RuntimeException("simulated downstream failure");
        }
    }

    private static final class RecordingPublisher implements EventPublisher {
        final List<DomainEvent> events = new ArrayList<>();
        @Override public void publish(DomainEvent event) {
            Objects.requireNonNull(event);
            events.add(event);
        }
    }
}
