# Changelog — notification-webhook

All notable changes to the notification-webhook service. Newest first.

## [feat/exec-gap-closure-2026-07-28] - 2026-07-28 (per-endpoint webhook fairness: T3-11 defect 5, the design half)

Flyway **V009** (`webhook_delivery_log.partner_id` + a `(status, partner_id, created_at)` index —
additive, nullable, no backfill).

### Fixed - one partner's dead endpoint no longer degrades delivery to every other partner
T3-11 raised drain concurrency from 1 to 8 and said plainly that this raised the stall threshold
rather than removing the coupling. Two separate mechanisms produced that coupling, so two separate
fixes were needed:

- **Composition.** Rows were selected with one global
  `WHERE status='PENDING' ORDER BY created_at LIMIT batch-size`. A partner with 300 queued rows
  filled every 200-row page, so a healthy partner's rows were **never selected** — and no amount of
  concurrency reaches a row that is not in the batch. `WebhookDispatcher.selectFairly()` now asks each
  endpoint *with work* for an equal share of `batch-size` (one indexed DISTINCT + one paged read per
  endpoint, bounded by the partner count) and interleaves the shares round-robin, so the order is fair
  too. A single endpoint with work degenerates to exactly the old query.
- **Capacity.** A per-endpoint in-flight cap of `ceil(concurrency / endpoints-with-work)` bounds a
  **slow-but-succeeding** endpoint — the case no failure-counting breaker can catch. Derived rather
  than a fixed constant, so a single-partner deployment still gets all 8 workers instead of being
  throttled for the benefit of partners that do not exist. Override with
  `gmepay.webhook.dispatcher.max-in-flight-per-endpoint`.

### Added - `WebhookEndpointCircuitBreaker`
Per `partnerId` (which is the endpoint identity T5-4's per-endpoint signing established): after
`breaker.failure-threshold` consecutive failed deliveries an endpoint's rows are skipped entirely for
`breaker.open-duration`, then exactly one half-open probe is allowed; a success closes it. A skipped
row is **not** a failed attempt — its `attempt` and `last_attempted_at` are untouched — so an outage no
longer burns the ten-attempt retry budget. Stated consequence: `webhook_dlq` promotion for a dead
endpoint becomes **slower** (driven by the probes), not impossible. `webhook_dlq` remains the only
terminal state; nothing parallel was added.

### Added - `WEBHOOK_ENDPOINT_CIRCUIT_OPEN` alert (`alert_event`, P2, deduped per partner)
Raised on the OPEN transition only. It exists because the fix deliberately stops attempting
deliveries: suppressing work silently would trade one invisible failure (everyone stalled behind one
dead partner) for another (one partner quietly not delivered to).

### Fixed - the shipped drain interval was still 30 s
`gmepay.webhook.dispatcher.interval-ms` shipped `${...:30000}`, which **overrode** the 5 s
`@Scheduled` default T3-11 introduced — so that cycle change was not in effect in any deployment. Now
`5000`, pinned by a test that reads the shipped properties file.

### Changed
- `WebhookPersistenceService.enqueuePendingIfAbsent` stamps `partner_id` from the payload, once, at
  enqueue — so the drain never has to parse every candidate row's JSON to decide whose share it is in.
- New `WebhookPayloads.partnerId(..)` is the single copy of the partner-extraction rule; the resolver
  and the DLQ alert now delegate to it instead of each carrying their own parse. The resolver still
  reads the **payload** (not the column) to decide where to deliver: if the two ever disagreed, the
  consequence is a row in the wrong fair share (harmless) rather than a webhook sent to the wrong
  partner (not).
- The pre-existing `WebhookDispatcher` constructors keep the **old** global-FIFO selection and no
  breaker. A constructor kept for backwards compatibility that quietly changed selection would not be
  backwards compatible.

### Tests
- `WebhookEndpointFairnessTest` — a **real timing test** (real virtual threads, real semaphores,
  deliveries that really block): a healthy partner's rows complete while a stalled endpoint is still
  in flight, in a fraction of the time the old shared pool needed; one endpoint still gets the whole
  pool; a failing endpoint trips after 5 failures without tripping anyone else and without spending
  the retry budget; the shipped configuration is pinned.
- `WebhookFairSelectionQueryTest` — the composition half against a real H2 + full Flyway set (V009
  applied): the old global query provably excludes the healthy partner, the per-endpoint reads do not,
  the DISTINCT scan includes the unattributed group, and the enqueue path stamps both envelope shapes.

### Not done
Fairness is per partner, not per event type, so one partner's slow endpoint still delays that
partner's other events. Nothing here was exercised against a running fleet.

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
