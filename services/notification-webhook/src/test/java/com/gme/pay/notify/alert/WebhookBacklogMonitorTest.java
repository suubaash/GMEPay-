package com.gme.pay.notify.alert;

import com.gme.pay.contracts.events.OpsAlertPayload;
import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.notify.persistence.WebhookDeliveryRepository;
import com.gme.pay.notify.persistence.WebhookPersistenceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Broker-free unit tests for {@link WebhookBacklogMonitor}: over threshold emits a
 * WEBHOOK_BACKLOG ops alert on the (mocked) EventPublisher seam; at/under threshold
 * emits nothing. Severity scales with backlog size.
 */
class WebhookBacklogMonitorTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final WebhookDeliveryRepository repo = mock(WebhookDeliveryRepository.class);
    private final EventPublisher publisher = mock(EventPublisher.class);

    private WebhookBacklogMonitor monitor(long threshold) {
        return new WebhookBacklogMonitor(repo, publisher, clock, threshold, 300L);
    }

    @Test
    void overThreshold_emitsOpsAlert() {
        when(repo.countByStatusAndCreatedAtBefore(eq(WebhookPersistenceService.STATUS_PENDING), any()))
                .thenReturn(80L);
        when(repo.countByStatus(WebhookPersistenceService.STATUS_DLQ)).thenReturn(40L); // 120 > 100

        monitor(100L).evaluate();

        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue()).isInstanceOf(OpsAlertEvent.class);
        OpsAlertPayload payload = ((OpsAlertEvent) event.getValue()).getPayload();
        assertThat(payload.eventType()).isEqualTo(OpsAlertPayload.EVENT_TYPE);
        assertThat(payload.alertType()).isEqualTo(WebhookBacklogMonitor.ALERT_TYPE);
        assertThat(payload.subjectRef()).isEqualTo("global");
        assertThat(payload.detail()).contains("backlog=120").contains("dlq=40");
        // 120 is between 1x and 2x threshold -> INFO
        assertThat(payload.severity()).isEqualTo(WebhookBacklogMonitor.SEV_INFO);
        // Routes to gmepay.ops.alert
        assertThat(event.getValue().eventType()).isEqualTo("ops.alert");
    }

    @Test
    void underThreshold_emitsNothing() {
        when(repo.countByStatusAndCreatedAtBefore(eq(WebhookPersistenceService.STATUS_PENDING), any()))
                .thenReturn(50L);
        when(repo.countByStatus(WebhookPersistenceService.STATUS_DLQ)).thenReturn(30L); // 80 <= 100

        OpsAlertPayload result = monitor(100L).evaluate();

        assertThat(result).isNull();
        verify(publisher, never()).publish(any());
    }

    @Test
    void atThreshold_emitsNothing() {
        when(repo.countByStatusAndCreatedAtBefore(eq(WebhookPersistenceService.STATUS_PENDING), any()))
                .thenReturn(100L);
        when(repo.countByStatus(WebhookPersistenceService.STATUS_DLQ)).thenReturn(0L); // exactly 100

        assertThat(monitor(100L).evaluate()).isNull();
        verify(publisher, never()).publish(any());
    }

    @Test
    void severityScalesWithBacklogSize() {
        when(repo.countByStatusAndCreatedAtBefore(anyString(), any())).thenReturn(0L);
        when(repo.countByStatus(WebhookPersistenceService.STATUS_DLQ)).thenReturn(60L); // 6x of 10 -> CRITICAL

        OpsAlertPayload result = monitor(10L).evaluate();

        assertThat(result).isNotNull();
        assertThat(result.severity()).isEqualTo(WebhookBacklogMonitor.SEV_CRITICAL);
    }
}
