package com.gme.pay.notify.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.notify.config.ClockConfig;
import com.gme.pay.notify.domain.RetryPolicy;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

/**
 * The <b>composition</b> half of per-endpoint fairness, against a real database.
 *
 * <p>{@code WebhookEndpointFairnessTest} proves the scheduling: a stalled endpoint cannot occupy the
 * shared workers. It cannot prove the other half, because it stubs the repository — and the other half
 * is where the original defect actually lived. With one global
 * {@code WHERE status='PENDING' ORDER BY created_at LIMIT 200}, a partner with a deep backlog filled
 * every page, so a healthy partner's rows were not delivered late, they were <b>never selected</b>. No
 * amount of concurrency reaches a row that is not in the batch.
 *
 * <p>So these tests run the actual queries (V009's column and index applied by the full Flyway set)
 * against real rows: the DISTINCT endpoint scan, the per-partner paged read, and the {@code IS NULL}
 * variant that keeps pre-V009 rows selectable.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({WebhookPersistenceService.class, ClockConfig.class, RetryPolicy.class})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:notifyfairselect;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
                + "DATABASE_TO_LOWER=TRUE"
})
class WebhookFairSelectionQueryTest {

    private static final String PENDING = WebhookPersistenceService.STATUS_PENDING;

    @Autowired
    private WebhookDeliveryRepository repository;

    @Autowired
    private WebhookPersistenceService persistence;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private void insert(Long partnerId, int count, Instant createdAt) {
        for (int i = 0; i < count; i++) {
            WebhookDeliveryEntity row = new WebhookDeliveryEntity();
            row.setWebhookId("evt_" + partnerId + "_" + i + "_" + System.nanoTime());
            row.setEventType("payment.approved");
            row.setPayload("{\"partnerId\":" + partnerId + "}");
            row.setPartnerId(partnerId);
            row.setStatus(PENDING);
            row.setAttempt(0);
            row.setCreatedAt(createdAt.plusMillis(i));
            repository.save(row);
        }
    }

    @Test
    @DisplayName("the enqueue path stamps partner_id from the payload (both envelope shapes)")
    void enqueueStampsThePartner() {
        WebhookDeliveryEntity flat = persistence
                .enqueuePendingIfAbsent("evt_flat", "payment.approved", "{\"partnerId\":42}")
                .orElseThrow();
        WebhookDeliveryEntity nested = persistence
                .enqueuePendingIfAbsent("evt_nested", "payment.approved",
                        "{\"eventType\":\"payment.approved\",\"payload\":{\"partnerId\":43}}")
                .orElseThrow();
        WebhookDeliveryEntity none = persistence
                .enqueuePendingIfAbsent("evt_none", "payment.approved", "{\"amount\":\"1000\"}")
                .orElseThrow();

        // Read once at enqueue, not per drain: the drain must not parse every candidate row's JSON
        // just to decide whose fair share it belongs to.
        assertThat(flat.getPartnerId()).isEqualTo(42L);
        assertThat(nested.getPartnerId())
                .as("the canonical outbox envelope nests the event's own fields under `payload`")
                .isEqualTo(43L);
        assertThat(none.getPartnerId())
                .as("a payload with no partner is a legitimate row, not an error — it becomes part of "
                        + "the unattributed group")
                .isNull();
    }

    @Test
    @DisplayName("the DISTINCT scan finds every endpoint with work, including the unattributed group")
    void distinctEndpointsIncludesTheNullGroup() {
        Instant base = Instant.parse("2026-07-28T00:00:00Z");
        insert(11L, 3, base);
        insert(22L, 1, base);
        insert(null, 2, base);

        assertThat(repository.findDistinctPartnerIdsByStatus(PENDING))
                .as("bounded by the number of partners, not by the backlog")
                .containsExactlyInAnyOrder(11L, 22L, null);
    }

    @Test
    @DisplayName("a deep backlog for one partner no longer hides another partner's rows")
    void aDeepBacklogDoesNotHideAnotherPartnersRows() {
        Instant old = Instant.parse("2026-07-01T00:00:00Z");
        Instant recent = Instant.parse("2026-07-28T00:00:00Z");
        insert(11L, 300, old);       // the stalled partner, oldest rows in the table
        insert(22L, 2, recent);      // the healthy partner, newest rows in the table

        // The old behaviour, kept reachable as an escape hatch and shown here as the defect it was:
        // 200 rows of partner 11 and NOT ONE row of partner 22.
        List<WebhookDeliveryEntity> globalFifo = repository.findByStatusOrderByCreatedAtAsc(
                PENDING, PageRequest.of(0, 200));
        assertThat(globalFifo).hasSize(200);
        assertThat(globalFifo).extracting(WebhookDeliveryEntity::getPartnerId)
                .as("this is the defect: the healthy partner is not in the batch at all")
                .doesNotContain(22L);

        // Per-endpoint selection: each endpoint's own share, oldest-first within the share.
        List<WebhookDeliveryEntity> healthyShare = repository
                .findByStatusAndPartnerIdOrderByCreatedAtAsc(PENDING, 22L, PageRequest.of(0, 100));
        assertThat(healthyShare).hasSize(2);

        List<WebhookDeliveryEntity> stalledShare = repository
                .findByStatusAndPartnerIdOrderByCreatedAtAsc(PENDING, 11L, PageRequest.of(0, 100));
        assertThat(stalledShare).hasSize(100);
        assertThat(stalledShare.get(0).getCreatedAt())
                .as("oldest-first inside a share too, so nothing is starved within a partner")
                .isBeforeOrEqualTo(stalledShare.get(99).getCreatedAt());
    }

    @Test
    @DisplayName("unattributed (pre-V009) rows stay selectable through the explicit IS NULL read")
    void unattributedRowsAreStillSelectable() {
        Instant base = Instant.parse("2026-07-28T00:00:00Z");
        insert(null, 4, base);

        assertThat(repository.findByStatusAndPartnerIdIsNullOrderByCreatedAtAsc(
                PENDING, PageRequest.of(0, 10)))
                .as("a row the platform believes it has queued and then never selects again would be a "
                        + "far worse failure than an unfair one, so the null group is a first-class "
                        + "selection key rather than an edge case")
                .hasSize(4);

        // Spring Data happens to translate a null derived-query parameter into `IS NULL` (plain SQL
        // `= NULL` never matches), so the equality method finds them too. The drain still calls the
        // explicit method: relying on that translation would be relying on framework behaviour for a
        // property — "every PENDING row is reachable by exactly one selection key" — that has to hold.
        assertThat(repository.findByStatusAndPartnerIdOrderByCreatedAtAsc(
                PENDING, null, PageRequest.of(0, 10))).hasSize(4);
    }

    @Test
    @DisplayName("selection is scoped to PENDING: delivered and DLQ'd rows are never re-selected")
    void onlyPendingRowsAreSelected() {
        Instant base = Instant.parse("2026-07-28T00:00:00Z");
        insert(11L, 2, base);
        WebhookDeliveryEntity delivered = repository.findAll().get(0);
        persistence.markDelivered(delivered, 1);

        assertThat(repository.findByStatusAndPartnerIdOrderByCreatedAtAsc(
                PENDING, 11L, PageRequest.of(0, 10))).hasSize(1);
        assertThat(repository.findDistinctPartnerIdsByStatus(
                WebhookPersistenceService.STATUS_DELIVERED)).containsExactly(11L);
    }
}
