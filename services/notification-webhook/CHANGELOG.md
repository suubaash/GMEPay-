# Changelog — notification-webhook

All notable changes to the notification-webhook service. Newest first.

## 2026-07-01 — Ops wave: operator webhook replay + backlog alert

### Added
- **Operator replay endpoint** — `POST /v1/webhooks/deliveries/{id}/replay` (and
  `POST /v1/webhooks/deliveries/replay?reference=`) re-enqueues a parked
  (`DLQ`/`FAILED`) delivery back to `PENDING` so the existing `WebhookDispatcher`
  drain re-sends it (reuses the drain — no new dispatcher). Idempotent-safe: a live
  `PENDING`/`DELIVERED` row is a 200 no-op (never a duplicate in-flight send); a
  missing row is 404. Backoff clock is reset (`attempt=0`, `lastAttemptedAt=null`) so
  a re-enqueued row sends on the next cycle instead of waiting on the exhausted-attempt
  window.
  - `replay/WebhookReplayService` + `api/WebhookReplayController`. Operator identity
    (`X-Operator` header or body `operator`) and optional `reason` recorded to a new
    **webhook_replay_audit** ledger (`db/migration/V006__create_webhook_replay_audit.sql`,
    `WebhookReplayAuditEntity`/`Repository`) — one audit row per request incl. no-ops.
- **Backlog alert** — `alert/WebhookBacklogMonitor`, a `@Scheduled` **config-gated
  (default off)** check (`gmepay.webhook.backlog-monitor.enabled=true`). Backlog =
  overdue PENDING (older than `overdue-window-seconds`, default 300) + DLQ. When it
  strictly exceeds `threshold` (default 100) it emits an `OpsAlertPayload`
  (`alertType=WEBHOOK_BACKLOG`, severity INFO/WARN/CRITICAL by multiple of threshold,
  `subjectRef="global"`, `detail=counts`) on the `EventPublisher` seam → topic
  `gmepay.ops.alert` (via `alert/OpsAlertEvent` DomainEvent adapter). Under/at threshold
  emits nothing. Log-fallback `LogEventPublisher` bean (`@ConditionalOnMissingBean`) so
  it works with no broker; a `@Primary KafkaEventPublisher` wins when
  `spring.kafka.bootstrap-servers` is set.
- **Tests** (broker-free / mocked): `WebhookReplayServiceTest` (5) — DLQ re-enqueue +
  audit, PENDING/DELIVERED no-ops, NOT_FOUND, reference prefers parked row;
  `WebhookBacklogMonitorTest` (4) — over-threshold emits, under/at emits nothing,
  severity scaling. `:services:notification-webhook:test` green.


## 2026-06-30 — Phase 2: align payment.approved consumer to canonical contract

### Changed
- **PaymentApprovedEventHandler** now deserializes the canonical
  `com.gme.pay.contracts.events.PaymentApprovedPayload` (lib-api-contracts, camelCase)
  instead of bespoke `JsonNode` field-plucking, so producer (payment-executor) and this
  consumer agree on the wire shape for topic `gmepay.payment.approved`.
  - Transaction reference resolution is now `txnRef` → `aggregateId` → record key
    (was `aggregateId` → record key); still bounded to the 64-char `webhook_id` column.
  - The persisted/partner-delivered payload is a normalized re-serialization of the typed
    event, guaranteeing the approved-payment fields (`txnRef`, `partnerId`, `schemeId`,
    `collectionMarginUsd`, `payoutMarginUsd`, `serviceChargeAmount`, `serviceChargeCcy`,
    `feeSharePct`) are always carried in the canonical shape.
  - `EVENT_TYPE` now references `PaymentApprovedPayload.EVENT_TYPE`; topic/DLT constants
    unchanged (`gmepay.payment.approved`).
  - Defensive: unknown additive producer fields tolerated (`FAIL_ON_UNKNOWN_PROPERTIES`
    disabled); blank/invalid JSON and eventType/txnRef gaps remain poison → DLT.
- **PaymentApprovedEventHandlerTest** reworked onto a canonical payload + a new assertion
  that the delivery payload carries the mapped approved-payment fields, plus an
  unknown-field tolerance case. Broker-free (mocked persistence). `:services:notification-webhook:test` green.

## 2026-06-30 — Ops alerting (WBS 8.6-T24)

### Added
- **WebhookAlertService** (`alert/WebhookAlertService.java`) — Phase-1 P2 operational
  alerting for the webhook pipeline:
  - `fireDlqAlert` / `fireDlqAlertForPayload` — always records a `WEBHOOK_DLQ` P2 alert
    on a DLQ promotion (partner id parsed from the delivery payload, flat or
    nested-envelope shape).
  - `fireQueueDepthAlert` — records a `WEBHOOK_QUEUE_DEPTH` P2 alert when the pending
    backlog **strictly exceeds** 500, suppressing repeats within a 10-minute window per
    partner (alert-storm guard). Below/at threshold = no row.
  - Never throws to callers — alerting failures are logged and swallowed so they cannot
    break webhook delivery.
- **alert_event** table — `db/migration/V005__create_alert_event.sql` (durable Phase-1
  alert ledger; nullable `partner_id`, dedup index). Swappable for PagerDuty/Slack later
  behind the same method surface.
- **AlertEventEntity** / **AlertEventRepository** (incl. dedup probe
  `existsByPartnerIdAndAlertTypeAndAcknowledgedAtIsNullAndFiredAtAfter`).
- **WebhookDeliveryRepository.countByStatus** — cheap PENDING-backlog gauge for the
  queue-depth check.

### Changed
- **WebhookPersistenceService.moveToDlq** — fires the P2 DLQ alert after writing the DLQ
  row. Alert collaborator injected via `ObjectProvider` so `@DataJpaTest` slices that
  don't load the `@Service` stay green (alert simply skipped).
- **WebhookDispatcher.drainPending** — reports the global PENDING backlog to
  `WebhookAlertService.fireQueueDepthAlert` once per drain. Optional collaborator
  (nullable ctor) keeps the unit test self-contained.

### Tests
- `WebhookAlertServiceTest` — DLQ always-fires; queue-depth 499/500 (no insert), 501
  (insert), recent-unacknowledged suppression, null-partner global sentinel; payload
  partner-id extraction (flat + nested).
- `WebhookAlertPersistenceIT` (`@DataJpaTest`, H2) — V005 applies cleanly; DLQ promotion
  writes a persisted P2 alert with payload partner id; dedup query round-trip.
- `WebhookDispatcherTest` — extended for the per-drain queue-depth wiring (empty + backlog).

---

## Prior (built in earlier sessions — committed before this changelog existed)

The signed-webhook delivery spine for **UC-WEBHOOK-DELIVERY** was already implemented and
committed: Flyway V001–V004 (`webhook_delivery_log`, `webhook_dlq`, `webhook_endpoint`),
HMAC-SHA256 `WebhookSigningService` + `WebhookReplayGuard`, `RetryPolicy` (10-attempt
backoff: 0/30/120/600/1800/3600…s → DLQ), `WebhookPersistenceService`,
`WebhookSender`, the concrete `RestWebhookHttpClient` transport, the `@Scheduled`
`WebhookDispatcher` drain loop (bounded FIFO fetch, backoff-gated, unresolved→DLQ,
in-place DELIVERED/PENDING/DLQ), the `DefaultWebhookTargetResolver`, the Kafka
`PaymentApprovedKafkaConsumer` (manual ack + `.DLT`) → `PaymentApprovedEventHandler`
(idempotent enqueue), and the partner webhook-config / endpoint provisioning APIs.
