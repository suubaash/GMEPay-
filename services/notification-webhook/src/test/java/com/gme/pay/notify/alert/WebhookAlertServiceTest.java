package com.gme.pay.notify.alert;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link WebhookAlertService} (WBS 8.6-T24) — fixed clock,
 * mocked repository, no DB or network.
 */
class WebhookAlertServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-30T00:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AlertEventRepository repo = mock(AlertEventRepository.class);
    private final WebhookAlertService service = new WebhookAlertService(repo, clock);

    private void echoSave() {
        when(repo.save(any(AlertEventEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void fireDlqAlert_alwaysInsertsP2() {
        echoSave();

        Optional<AlertEventEntity> result = service.fireDlqAlert(7L, 42L, "payment.approved");

        assertThat(result).isPresent();
        ArgumentCaptor<AlertEventEntity> captor = ArgumentCaptor.forClass(AlertEventEntity.class);
        verify(repo).save(captor.capture());
        AlertEventEntity saved = captor.getValue();
        assertThat(saved.getAlertType()).isEqualTo(WebhookAlertService.TYPE_DLQ);
        assertThat(saved.getSeverity()).isEqualTo(WebhookAlertService.SEVERITY_P2);
        assertThat(saved.getPartnerId()).isEqualTo(7L);
        assertThat(saved.getFiredAt()).isEqualTo(NOW);
        assertThat(saved.getContext()).contains("\"eventType\":\"payment.approved\"");
    }

    @Test
    void fireDlqAlertForPayload_extractsPartnerIdFromFlatPayload() {
        echoSave();

        service.fireDlqAlertForPayload("{\"partnerId\":99}", 1L, "payment.approved");

        ArgumentCaptor<AlertEventEntity> captor = ArgumentCaptor.forClass(AlertEventEntity.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getPartnerId()).isEqualTo(99L);
    }

    @Test
    void fireDlqAlertForPayload_extractsPartnerIdFromNestedEnvelope() {
        echoSave();

        service.fireDlqAlertForPayload("{\"eventType\":\"x\",\"payload\":{\"partnerId\":\"123\"}}", 1L, "x");

        ArgumentCaptor<AlertEventEntity> captor = ArgumentCaptor.forClass(AlertEventEntity.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getPartnerId()).isEqualTo(123L);
    }

    @Test
    void fireQueueDepthAlert_belowThreshold_doesNotInsert() {
        Optional<AlertEventEntity> result = service.fireQueueDepthAlert(7L, 499L);

        assertThat(result).isEmpty();
        verify(repo, never()).save(any());
    }

    @Test
    void fireQueueDepthAlert_atThreshold_doesNotInsert() {
        // Threshold is strictly greater-than, so exactly 500 must NOT alert.
        Optional<AlertEventEntity> result = service.fireQueueDepthAlert(7L, 500L);

        assertThat(result).isEmpty();
        verify(repo, never()).save(any());
    }

    @Test
    void fireQueueDepthAlert_aboveThreshold_insertsP2() {
        when(repo.existsByPartnerIdAndAlertTypeAndAcknowledgedAtIsNullAndFiredAtAfter(
                eq(7L), eq(WebhookAlertService.TYPE_QUEUE_DEPTH), any())).thenReturn(false);
        echoSave();

        Optional<AlertEventEntity> result = service.fireQueueDepthAlert(7L, 501L);

        assertThat(result).isPresent();
        ArgumentCaptor<AlertEventEntity> captor = ArgumentCaptor.forClass(AlertEventEntity.class);
        verify(repo).save(captor.capture());
        AlertEventEntity saved = captor.getValue();
        assertThat(saved.getAlertType()).isEqualTo(WebhookAlertService.TYPE_QUEUE_DEPTH);
        assertThat(saved.getSeverity()).isEqualTo(WebhookAlertService.SEVERITY_P2);
        assertThat(saved.getContext()).contains("\"pendingCount\":501");
    }

    @Test
    void fireQueueDepthAlert_recentUnacknowledged_isSuppressed() {
        when(repo.existsByPartnerIdAndAlertTypeAndAcknowledgedAtIsNullAndFiredAtAfter(
                eq(7L), eq(WebhookAlertService.TYPE_QUEUE_DEPTH), any())).thenReturn(true);

        Optional<AlertEventEntity> result = service.fireQueueDepthAlert(7L, 1000L);

        assertThat(result).isEmpty();
        verify(repo, never()).save(any());
    }

    @Test
    void fireQueueDepthAlert_nullPartner_usesGlobalSentinel() {
        when(repo.existsByPartnerIdAndAlertTypeAndAcknowledgedAtIsNullAndFiredAtAfter(
                eq(0L), eq(WebhookAlertService.TYPE_QUEUE_DEPTH), any())).thenReturn(false);
        echoSave();

        service.fireQueueDepthAlert(null, 600L);

        ArgumentCaptor<AlertEventEntity> captor = ArgumentCaptor.forClass(AlertEventEntity.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getPartnerId()).isEqualTo(0L);
    }
}
