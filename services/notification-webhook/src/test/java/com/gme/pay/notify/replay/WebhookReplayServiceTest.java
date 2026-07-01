package com.gme.pay.notify.replay;

import com.gme.pay.notify.persistence.WebhookDeliveryEntity;
import com.gme.pay.notify.persistence.WebhookDeliveryRepository;
import com.gme.pay.notify.persistence.WebhookPersistenceService;
import com.gme.pay.notify.replay.WebhookReplayService.Outcome;
import com.gme.pay.notify.replay.WebhookReplayService.ReplayResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Broker-free unit tests for {@link WebhookReplayService}: a DLQ/FAILED delivery is
 * re-enqueued to PENDING (backoff reset) and audited; a live (PENDING/DELIVERED) row
 * is an idempotent no-op; a missing row is NOT_FOUND. All with mocked repositories.
 */
class WebhookReplayServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final WebhookDeliveryRepository deliveryRepo = mock(WebhookDeliveryRepository.class);
    private final WebhookReplayAuditRepository auditRepo = mock(WebhookReplayAuditRepository.class);
    private final WebhookReplayService service = new WebhookReplayService(deliveryRepo, auditRepo, clock);

    private static WebhookDeliveryEntity row(long id, String status) {
        WebhookDeliveryEntity r = new WebhookDeliveryEntity();
        r.setId(id);
        r.setWebhookId("evt_" + id);
        r.setEventType("payment.approved");
        r.setPayload("{\"partnerId\":7}");
        r.setStatus(status);
        r.setAttempt(5);
        r.setLastAttemptedAt(NOW);
        r.setLastError("boom");
        r.setCreatedAt(NOW);
        return r;
    }

    @Test
    void replayById_dlqRow_reenqueuesToPendingAndAudits() {
        WebhookDeliveryEntity dlq = row(1L, WebhookPersistenceService.STATUS_DLQ);
        when(deliveryRepo.findById(1L)).thenReturn(Optional.of(dlq));

        ReplayResult result = service.replayById(1L, "ops.jane", "customer reported missing webhook");

        assertThat(result.outcome()).isEqualTo(Outcome.REENQUEUED);
        assertThat(result.reenqueued()).isTrue();

        ArgumentCaptor<WebhookDeliveryEntity> saved = ArgumentCaptor.forClass(WebhookDeliveryEntity.class);
        verify(deliveryRepo).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(WebhookPersistenceService.STATUS_PENDING);
        assertThat(saved.getValue().getAttempt()).isZero();
        assertThat(saved.getValue().getLastAttemptedAt()).isNull();
        assertThat(saved.getValue().getLastError()).isNull();

        ArgumentCaptor<WebhookReplayAuditEntity> audit = ArgumentCaptor.forClass(WebhookReplayAuditEntity.class);
        verify(auditRepo).save(audit.capture());
        assertThat(audit.getValue().getOutcome()).isEqualTo(Outcome.REENQUEUED.name());
        assertThat(audit.getValue().getRequestedBy()).isEqualTo("ops.jane");
        assertThat(audit.getValue().getReason()).isEqualTo("customer reported missing webhook");
        assertThat(audit.getValue().getDeliveryId()).isEqualTo(1L);
    }

    @Test
    void replayById_pendingRow_isIdempotentNoOp() {
        WebhookDeliveryEntity pending = row(2L, WebhookPersistenceService.STATUS_PENDING);
        when(deliveryRepo.findById(2L)).thenReturn(Optional.of(pending));

        ReplayResult result = service.replayById(2L, "ops.jane", null);

        assertThat(result.outcome()).isEqualTo(Outcome.NOOP_ALREADY_PENDING);
        verify(deliveryRepo, never()).save(any());
        verify(auditRepo).save(any()); // no-op is still audited
    }

    @Test
    void replayById_deliveredRow_isIdempotentNoOp() {
        WebhookDeliveryEntity delivered = row(3L, WebhookPersistenceService.STATUS_DELIVERED);
        when(deliveryRepo.findById(3L)).thenReturn(Optional.of(delivered));

        ReplayResult result = service.replayById(3L, "ops.jane", null);

        assertThat(result.outcome()).isEqualTo(Outcome.NOOP_ALREADY_DELIVERED);
        verify(deliveryRepo, never()).save(any());
    }

    @Test
    void replayById_missing_isNotFound() {
        when(deliveryRepo.findById(99L)).thenReturn(Optional.empty());

        ReplayResult result = service.replayById(99L, "ops.jane", null);

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_FOUND);
        verify(deliveryRepo, never()).save(any());
        verify(auditRepo).save(any());
    }

    @Test
    void replayByReference_prefersParkedRowOverLiveOne() {
        WebhookDeliveryEntity delivered = row(4L, WebhookPersistenceService.STATUS_DELIVERED);
        WebhookDeliveryEntity failed = row(5L, WebhookPersistenceService.STATUS_FAILED);
        when(deliveryRepo.findByWebhookId("evt_x")).thenReturn(List.of(delivered, failed));

        ReplayResult result = service.replayByReference("evt_x", "ops.jane", "retry");

        assertThat(result.outcome()).isEqualTo(Outcome.REENQUEUED);
        assertThat(result.deliveryId()).isEqualTo(5L); // the FAILED row, not the DELIVERED one
    }
}
