package com.gme.pay.notify.persistence;

import com.gme.pay.notify.config.ClockConfig;
import com.gme.pay.notify.domain.RetryPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the Phase-1 webhook persistence layer.
 *
 * <p>Uses {@code @DataJpaTest} with {@code @AutoConfigureTestDatabase(replace = NONE)}
 * so the H2 datasource configured in {@code application.properties} is used (no
 * Docker / Testcontainers required). Verifies:
 * <ol>
 *   <li>Recording a delivery attempt persists the row.</li>
 *   <li>Moving to DLQ after max attempts inserts the DLQ row and flips the
 *       delivery-log status to {@code DLQ}.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({WebhookPersistenceService.class, ClockConfig.class})
class WebhookDeliveryPersistenceIT {

    @Autowired
    private WebhookPersistenceService persistenceService;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    @Autowired
    private WebhookDlqRepository dlqRepository;

    // ------------------------------------------------------------------
    // (a) Round-trip: recording an attempt persists a row we can read back.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("recordAttempt persists a delivery row that can be read back")
    void recordAttempt_persistsRow() {
        WebhookDeliveryEntity saved = persistenceService.recordAttempt(
                "evt_001",
                "payment.approved",
                "{\"id\":\"p1\"}",
                1,
                true,
                null
        );

        assertNotNull(saved.getId(), "row must be assigned an id by the DB");

        List<WebhookDeliveryEntity> rows = deliveryRepository.findByWebhookId("evt_001");
        assertEquals(1, rows.size(), "exactly one row should be persisted");

        WebhookDeliveryEntity row = rows.get(0);
        assertEquals("payment.approved", row.getEventType());
        assertEquals("{\"id\":\"p1\"}", row.getPayload());
        assertEquals(WebhookPersistenceService.STATUS_DELIVERED, row.getStatus());
        assertEquals(1, row.getAttempt());
        assertNotNull(row.getDeliveredAt(), "delivered_at should be set on success");
        assertNotNull(row.getCreatedAt());
    }

    @Test
    @DisplayName("recordAttempt with failure persists FAILED status with last_error")
    void recordAttempt_failurePersistsError() {
        WebhookDeliveryEntity saved = persistenceService.recordAttempt(
                "evt_fail_001",
                "payment.failed",
                "{\"id\":\"p2\"}",
                3,
                false,
                "HTTP 500"
        );

        WebhookDeliveryEntity row = deliveryRepository.findById(saved.getId()).orElseThrow();
        assertEquals(WebhookPersistenceService.STATUS_FAILED, row.getStatus());
        assertEquals("HTTP 500", row.getLastError());
        assertEquals(3, row.getAttempt());
    }

    // ------------------------------------------------------------------
    // (b) DLQ promotion after max attempts.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("recordAttemptAndMaybeDlq promotes to DLQ on the MAX_ATTEMPTS-th failure")
    void recordAttemptAndMaybeDlq_promotesToDlqAtMax() {
        String webhookId = "evt_dlq_001";
        String payload = "{\"id\":\"p3\"}";

        // Simulate 9 failed attempts that stay in FAILED, no DLQ yet.
        for (int i = 1; i < RetryPolicy.MAX_ATTEMPTS; i++) {
            persistenceService.recordAttemptAndMaybeDlq(
                    webhookId,
                    "payment.approved",
                    payload,
                    i,
                    false,
                    "HTTP 500 attempt " + i
            );
        }
        assertTrue(dlqRepository.findByWebhookId(webhookId).isEmpty(),
                "DLQ must be empty before MAX_ATTEMPTS is reached");

        // 10th failure -> DLQ promotion.
        WebhookDeliveryEntity tenth = persistenceService.recordAttemptAndMaybeDlq(
                webhookId,
                "payment.approved",
                payload,
                RetryPolicy.MAX_ATTEMPTS,
                false,
                "HTTP 500 attempt 10"
        );

        // Re-read: the 10th row must now be in DLQ status.
        WebhookDeliveryEntity finalRow = deliveryRepository.findById(tenth.getId()).orElseThrow();
        assertEquals(WebhookPersistenceService.STATUS_DLQ, finalRow.getStatus(),
                "10th attempt row must be flipped to DLQ status");

        // And one DLQ row must have been inserted, pointing back at the originating row.
        List<WebhookDlqEntity> dlqRows = dlqRepository.findByWebhookId(webhookId);
        assertEquals(1, dlqRows.size(), "exactly one DLQ row expected");

        WebhookDlqEntity dlq = dlqRows.get(0);
        assertEquals(tenth.getId(), dlq.getOriginalId(),
                "DLQ.original_id must match the promoted delivery row id");
        assertEquals(webhookId, dlq.getWebhookId());
        assertEquals(payload, dlq.getPayload());
        assertNotNull(dlq.getReason());
        assertFalse(dlq.getReason().isBlank(), "DLQ reason must be populated");
        assertNotNull(dlq.getAddedAt());
    }
}
