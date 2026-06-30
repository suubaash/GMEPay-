> 작업: notification-webhook backlog 완성 / 출처: agent

# notification-webhook build report — 2026-06-30

## Build status
- `./gradlew :services:notification-webhook:test` — **GREEN**. 81 tests, 0 failures, 0 ignored.
- All changes scoped to `services/notification-webhook/`. Committed on `agent/notification-webhook` (`5879064`).

## Key finding: backlog was already ~95% done on entry
The task brief's stated top priority — "NO concrete WebhookHttpClient impl … and NO
dispatcher/drain loop … implement a WebClient WebhookHttpClient + a @Scheduled drain …
consume payment.approved" — was **already implemented and committed in prior sessions**.
Verified present and tested on entry:
- `RestWebhookHttpClient` (concrete Spring `RestClient` transport, bounded timeouts, never-throws).
- `WebhookDispatcher` — `@Scheduled` drain over PENDING `webhook_delivery_log` rows, bounded FIFO
  fetch, backoff-gated retry, in-place DELIVERED/PENDING/DLQ, unresolved-target→DLQ (#92).
- `PaymentApprovedKafkaConsumer` (manual ack + `.DLT`) → idempotent `PaymentApprovedEventHandler`
  enqueue (17.4-G04). `DefaultWebhookTargetResolver` resolves endpoint from `webhook_endpoint`.
- HMAC-SHA256 `WebhookSigningService` + `WebhookReplayGuard`, `RetryPolicy` (10-attempt backoff
  → DLQ), `WebhookPersistenceService`, Flyway V001–V004, partner webhook-config/provisioning APIs.

Note: the implemented design diverged from the original T-ticket plan — it uses a leaner
`webhook_delivery_log`-based drain rather than the `outbox_event` + `OutboxPollerService` table
names in tickets T01/T04/T08. Functionally equivalent; ticket text is stale vs. the real codebase.

## Tickets completed THIS run
1. **8.6-T24 — Ops alert integration (P2 DLQ + queue-depth)** — DONE.
   - New `WebhookAlertService`: `fireDlqAlert`/`fireDlqAlertForPayload` (always-fire on DLQ
     promotion, partner id parsed from payload); `fireQueueDepthAlert` (fires only when
     pending count strictly > 500, deduped within a 10-min window per partner via
     `existsByPartnerIdAndAlertTypeAndAcknowledgedAtIsNullAndFiredAtAfter`). Severity P2.
     Never throws to delivery callers.
   - New `V005__create_alert_event.sql`, `AlertEventEntity`, `AlertEventRepository`,
     `WebhookDeliveryRepository.countByStatus`.
   - Wired into `WebhookPersistenceService.moveToDlq` (DLQ alert) and
     `WebhookDispatcher.drainPending` (queue-depth, once per drain).
   - Tests: `WebhookAlertServiceTest` (8 Mockito unit tests covering 499/500/501 threshold,
     dedup suppression, global sentinel, payload extraction); `WebhookAlertPersistenceIT`
     (`@DataJpaTest` H2 — V005 applies, DLQ promotion writes persisted P2 alert, dedup query);
     `WebhookDispatcherTest` extended for the per-drain queue-depth wiring.
   - Added `services/notification-webhook/CHANGELOG.md` (required deliverable; did not previously exist).

## Completion estimate
- Service vs. its backlog (26 T-tickets + 2 G-tickets): **~95% functionally complete**.
- The signed-delivery + retry/DLQ + Kafka-consumer spine (the P0 UC-WEBHOOK-DELIVERY path) is
  fully built and tested; T24 ops-alerting added this run. Remaining items are observability,
  the Ops DLQ admin API, and CI-only Postgres/Kafka end-to-end ITs (see below).

## Remaining (top items)
1. **8.6-T17 — Micrometer metrics / OTel** (`webhook_dispatch_attempts_total`,
   `webhook_outbox_pending_count` gauge, `webhook_dlq_total`, `webhook_dispatch_duration_seconds`,
   `/actuator/prometheus`). NOT done — requires adding `spring-boot-starter-actuator` +
   `micrometer-registry-prometheus` to `build.gradle` and a `WebhookMetricsConfig`. Deferred this
   run because it needs a fresh dependency + a boot/actuator-endpoint verification rather than a
   pure unit slice; low risk but unverifiable as a unit test alone.
2. **8.6-T12 — Ops DLQ admin API** (`GET/POST /internal/v1/webhook/dlq[/{id}/retry|/resolve]`,
   `@PreAuthorize ROLE_GME_OPS`, audit rows). NOT done. The replay primitive already exists
   (`enqueuePendingIfAbsent` re-enqueues past a prior DLQ), so this is mostly controller + service +
   `@WebMvcTest`. `webhook_dlq` has no `resolved_at`/`resolved_by` columns yet — a small V006 ALTER
   would be needed for the resolve action.
3. **CI-only end-to-end ITs (17.2-G11 Testcontainers PG16, 17.4-G04 produce→deliver→WireMock).**
   `@Tag("docker")`/Testcontainers classes are intentionally excluded from the local `test` task
   (no Docker on dev boxes) and run in CI's `integrationTest`. Could not be exercised locally.

## INTEGRATION REQUESTS
(none — all work this run stayed inside `services/notification-webhook/`; no frozen lib or
cross-service contract change was required. The Kafka `gmepay.payment.approved` topic contract
from transaction-mgmt's outbox is already consumed by the existing, committed consumer.)
