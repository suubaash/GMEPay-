package com.gme.pay.notify.alert;

import com.gme.pay.notify.config.ClockConfig;
import com.gme.pay.notify.domain.RetryPolicy;
import com.gme.pay.notify.persistence.WebhookDeliveryEntity;
import com.gme.pay.notify.persistence.WebhookDlqRepository;
import com.gme.pay.notify.persistence.WebhookPersistenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2-backed slice test for the Phase-1 alert ledger (WBS 8.6-T24). Proves:
 * <ol>
 *   <li>The {@code V005__create_alert_event.sql} migration applies cleanly (Flyway
 *       runs as part of the {@code @DataJpaTest} datasource bootstrap).</li>
 *   <li>A DLQ promotion through {@link WebhookPersistenceService#moveToDlq} fires a
 *       persisted P2 {@code WEBHOOK_DLQ} alert with the partner id read from the
 *       delivery payload.</li>
 *   <li>The queue-depth dedup query returns the right rows.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({WebhookPersistenceService.class, WebhookAlertService.class, ClockConfig.class})
class WebhookAlertPersistenceIT {

    @Autowired
    private WebhookPersistenceService persistenceService;

    @Autowired
    private WebhookDlqRepository dlqRepository;

    @Autowired
    private AlertEventRepository alertRepository;

    @Test
    @DisplayName("DLQ promotion writes a P2 WEBHOOK_DLQ alert with partner id from payload")
    void dlqPromotion_writesAlertEvent() {
        // A FAILED row that has reached the retry ceiling, then promote it.
        WebhookDeliveryEntity failed = persistenceService.recordAttempt(
                "evt_alert_001",
                "payment.approved",
                "{\"partnerId\":55,\"id\":\"p9\"}",
                RetryPolicy.MAX_ATTEMPTS,
                false,
                "HTTP 500");

        persistenceService.moveToDlq(failed, "max attempts exhausted");

        // DLQ row written (existing behaviour).
        List<?> dlqRows = dlqRepository.findByWebhookId("evt_alert_001");
        assertEquals(1, dlqRows.size(), "one DLQ row expected");

        // Alert row written with partner id parsed out of the payload.
        List<AlertEventEntity> alerts = alertRepository.findAll();
        assertEquals(1, alerts.size(), "one WEBHOOK_DLQ alert expected");
        AlertEventEntity alert = alerts.get(0);
        assertEquals(WebhookAlertService.TYPE_DLQ, alert.getAlertType());
        assertEquals(WebhookAlertService.SEVERITY_P2, alert.getSeverity());
        assertEquals(55L, alert.getPartnerId());
        assertNotNull(alert.getFiredAt());
        assertNotNull(alert.getMessage());
    }

    @Test
    @DisplayName("queue-depth dedup query finds an unacknowledged recent alert")
    void dedupQuery_findsUnacknowledgedAlert() {
        AlertEventEntity row = new AlertEventEntity();
        row.setAlertType(WebhookAlertService.TYPE_QUEUE_DEPTH);
        row.setSeverity(WebhookAlertService.SEVERITY_P2);
        row.setPartnerId(7L);
        row.setMessage("backlog");
        row.setFiredAt(java.time.Instant.parse("2026-06-30T00:00:00Z"));
        alertRepository.save(row);

        boolean exists = alertRepository.existsByPartnerIdAndAlertTypeAndAcknowledgedAtIsNullAndFiredAtAfter(
                7L, WebhookAlertService.TYPE_QUEUE_DEPTH, java.time.Instant.parse("2026-06-29T23:55:00Z"));
        assertTrue(exists, "an unacknowledged recent queue-depth alert should be found");

        boolean none = alertRepository.existsByPartnerIdAndAlertTypeAndAcknowledgedAtIsNullAndFiredAtAfter(
                7L, WebhookAlertService.TYPE_QUEUE_DEPTH, java.time.Instant.parse("2026-06-30T00:05:00Z"));
        assertTrue(!none, "no alert after a later cutoff");
    }
}
