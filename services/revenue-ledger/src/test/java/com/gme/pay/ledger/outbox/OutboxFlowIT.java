package com.gme.pay.ledger.outbox;

import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.events.RecordingEventPublisher;
import com.gme.pay.ledger.domain.ledger.LedgerPostingService;
import com.gme.pay.ledger.domain.model.Journal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the full transactional Outbox flow inside revenue-ledger.
 *
 * <p>Boots the whole Spring context against the H2 PostgreSQL-mode datasource (no Docker)
 * and verifies the end-to-end pattern:
 * <ol>
 *   <li>{@link LedgerPostingService#postRevenueCapture} saves a journal — and, in the same
 *       transaction, {@code JpaJournalStore} enqueues an {@code outbox} row of
 *       eventType {@code "journal.posted"}.</li>
 *   <li>The row starts unpublished ({@code published_at IS NULL}).</li>
 *   <li>{@link OutboxPublisher#publishPending()} hands the row to the {@link EventPublisher}
 *       bean (overridden here with a {@link RecordingEventPublisher}) and stamps
 *       {@code published_at}.</li>
 * </ol>
 *
 * <p>The configured poll interval is pushed out to an hour so the {@code @Scheduled} tick
 * cannot race with the test's explicit {@code publishPending()} call. The initial tick on
 * context startup is harmless because the outbox is empty until the test posts its journal.
 */
// Poll interval is set very high (1 hour) so the scheduled tick does not race with the
// test's explicit publishPending() call. The initial @Scheduled run on context startup
// is harmless: the outbox is empty until the test posts its journal.
@SpringBootTest(properties = "gmepay.outbox.poll-ms=3600000")
class OutboxFlowIT {

    @TestConfiguration
    static class RecordingPublisherConfig {
        /**
         * Override the default {@link com.gme.pay.events.LogEventPublisher} bean with a
         * {@link RecordingEventPublisher} so the test can assert what was published.
         */
        @Bean
        @Primary
        RecordingEventPublisher recordingEventPublisher() {
            return new RecordingEventPublisher();
        }
    }

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private RecordingEventPublisher recordingEventPublisher;

    @Test
    void journalSave_writesOutboxRow_thenPublisherDrainsItAndStampsPublishedAt() {
        recordingEventPublisher.clear();

        // 1. Snapshot the unpublished outbox so we can isolate just-this-test's row.
        List<Long> preIds = outboxRepository.findUnpublished(org.springframework.data.domain.PageRequest.of(0, 1000))
                .stream().map(OutboxEntity::getId).toList();

        // 2. Post a journal — JpaJournalStore.save(...) enqueues the outbox row in the SAME txn.
        String ref = "TXN-OUTBOX-IT-" + UUID.randomUUID();
        Journal journal = ledgerPostingService.postRevenueCapture(
                ref, new BigDecimal("12.3400"), new BigDecimal("500"), "KRW");

        // 3. There should now be a fresh unpublished outbox row for this journal.
        List<OutboxEntity> unpublished = outboxRepository.findUnpublished(
                org.springframework.data.domain.PageRequest.of(0, 1000));
        OutboxEntity ours = unpublished.stream()
                .filter(o -> !preIds.contains(o.getId()))
                .filter(o -> journal.journalId().equals(o.getAggregateId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected an unpublished outbox row for journal " + journal.journalId()));

        assertEquals("journal.posted", ours.getEventType(), "eventType");
        assertEquals(journal.journalId(), ours.getAggregateId(), "aggregateId");
        assertNotNull(ours.getCreatedAt(), "createdAt set on enqueue");
        assertNull(ours.getPublishedAt(), "publishedAt must start null");
        assertTrue(ours.getPayload().contains(journal.journalId()),
                "payload must reference the journalId; was: " + ours.getPayload());
        assertTrue(ours.getPayload().contains(ref),
                "payload must reference the transaction reference; was: " + ours.getPayload());

        // 4. Manually drain the outbox.
        outboxPublisher.publishPending();

        // 5. RecordingEventPublisher captured our event.
        List<DomainEvent> captured = recordingEventPublisher.published();
        assertTrue(captured.stream().anyMatch(e ->
                        "journal.posted".equals(e.eventType())
                                && journal.journalId().equals(e.aggregateId())
                                && e.occurredAt() != null),
                "RecordingEventPublisher should have captured the journal.posted event; got: " + captured);

        // 6. The row is now stamped published.
        OutboxEntity reread = outboxRepository.findById(ours.getId()).orElseThrow();
        assertNotNull(reread.getPublishedAt(), "publishedAt must be stamped after publishPending()");
    }
}
