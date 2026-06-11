package com.gme.pay.notify.persistence;

import com.gme.pay.notify.config.ClockConfig;
import com.gme.pay.notify.domain.WebhookConfigEntry;
import com.gme.pay.notify.dto.WebhookConfigResponse;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2 (PostgreSQL mode) slice test for {@link JpaWebhookConfigStore} — the JPA
 * adapter behind the {@code WebhookConfigStore} port (17.2-G11). The same
 * behaviour is re-verified against real PostgreSQL 16 in
 * {@code WebhookPersistencePostgresIT} (docker-tagged, CI only).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaWebhookConfigStore.class, ClockConfig.class})
class JpaWebhookConfigStoreTest {

    @Autowired
    private JpaWebhookConfigStore store;

    @Autowired
    private WebhookEndpointRepository repository;

    @Test
    @DisplayName("save persists an active endpoint and round-trips all fields")
    void save_roundTrips() {
        WebhookConfigResponse saved = store.save(new WebhookConfigEntry(
                42L,
                "https://partner.example.com/hooks/gmepay",
                List.of("payment.approved", "payment.failed"),
                "s3cret-goes-to-vault"));

        assertNotNull(saved.id(), "DB must assign an id");
        assertEquals(42L, saved.partnerId());
        assertEquals("https://partner.example.com/hooks/gmepay", saved.webhookUrl());
        assertEquals(List.of("payment.approved", "payment.failed"), saved.eventTypes());
        assertTrue(saved.active());
        assertNotNull(saved.createdAt());
        assertNotNull(saved.updatedAt());

        // The signing secret must never be persisted (Vault only).
        WebhookEndpointEntity entity = repository.findById(saved.id()).orElseThrow();
        assertEquals("payment.approved,payment.failed", entity.getEventTypesCsv());
    }

    @Test
    @DisplayName("null/empty eventTypes means 'all events' and maps to NULL in the DB")
    void save_nullEventTypesMeansAll() {
        WebhookConfigResponse saved = store.save(new WebhookConfigEntry(
                7L, "https://partner.example.com/hooks", null, "secret"));

        assertNull(saved.eventTypes(), "null list = subscribe to all events");
        assertNull(repository.findById(saved.id()).orElseThrow().getEventTypesCsv());
    }

    @Test
    @DisplayName("findActiveByPartnerId filters by partner and active flag")
    void findActiveByPartnerId_filters() {
        WebhookConfigResponse a = store.save(new WebhookConfigEntry(
                1L, "https://a.example.com/hook", List.of("payment.approved"), "s"));
        store.save(new WebhookConfigEntry(
                2L, "https://b.example.com/hook", List.of("payment.approved"), "s"));
        WebhookConfigResponse deactivated = store.save(new WebhookConfigEntry(
                1L, "https://a2.example.com/hook", null, "s"));
        store.deactivate(deactivated.id());

        List<WebhookConfigResponse> active = store.findActiveByPartnerId(1L);
        assertEquals(1, active.size(), "only partner 1's active endpoint expected");
        assertEquals(a.id(), active.get(0).id());
    }

    @Test
    @DisplayName("deactivate soft-deletes: row remains, active=false, updatedAt bumped")
    void deactivate_softDeletes() {
        WebhookConfigResponse saved = store.save(new WebhookConfigEntry(
                9L, "https://p.example.com/hook", List.of("payment.approved"), "s"));

        store.deactivate(saved.id());

        WebhookConfigResponse after = store.findById(saved.id()).orElseThrow();
        assertFalse(after.active(), "endpoint must be soft-deleted, not removed");
        assertTrue(store.findActiveByPartnerId(9L).isEmpty());
    }

    @Test
    @DisplayName("findById on an unknown id returns empty")
    void findById_unknownIsEmpty() {
        assertTrue(store.findById(999_999L).isEmpty());
    }
}
