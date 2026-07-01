> 작업: Ops webhook replay+backlog (notification-webhook) / 출처: agent

# Ops wave — notification-webhook: operator replay + backlog alert

Branch `ops/notification-webhook` (off `ops/contracts`). Additive only; libs + other services untouched.

## 1. Operator replay endpoint
- `POST /v1/webhooks/deliveries/{id}/replay` — replay by delivery-log id.
- `POST /v1/webhooks/deliveries/replay?reference=` — replay by logical webhook id (prefers the parked DLQ/FAILED row when several share the reference).
- Re-enqueues a `DLQ`/`FAILED` row → `PENDING` (resets `attempt=0`, `lastAttemptedAt=null`, clears error) so the **existing** `WebhookDispatcher` drain re-sends it next cycle. No new dispatcher.
- Idempotent-safe: live `PENDING`/`DELIVERED` = 200 no-op (`NOOP_ALREADY_*`), never a duplicate in-flight send; missing = 404.
- Audit: operator (`X-Operator` header or body `operator`) + optional `reason` + outcome → new `webhook_replay_audit` table (V006). One audit row per request, no-ops included.
- Files: `replay/WebhookReplayService`, `api/WebhookReplayController`, `replay/WebhookReplayAuditEntity`/`Repository`, `db/migration/V006__…sql`.

## 2. Backlog alert
- `alert/WebhookBacklogMonitor` — `@Scheduled`, **config-gated default OFF** (`gmepay.webhook.backlog-monitor.enabled=true`).
- Backlog = overdue PENDING (created before `now − overdue-window-seconds`, default 300) + DLQ count. Strictly `> threshold` (default 100) → emit `OpsAlertPayload` (`alertType=WEBHOOK_BACKLOG`; severity INFO/WARN/CRITICAL at 1x/2x/5x; `subjectRef="global"`; `detail`=counts) on `EventPublisher` seam → topic `gmepay.ops.alert` via `alert/OpsAlertEvent` DomainEvent adapter. Under/at threshold = nothing.
- Log-fallback: service-local `LogEventPublisher` bean (`@ConditionalOnMissingBean`) so it runs broker-free; `@Primary KafkaEventPublisher` wins when `spring.kafka.bootstrap-servers` set.

## Test status
`./gradlew :services:notification-webhook:test` → BUILD SUCCESSFUL, all suites green.
- `WebhookReplayServiceTest` (5): DLQ re-enqueue+audit, PENDING/DELIVERED no-ops, NOT_FOUND, reference-prefers-parked.
- `WebhookBacklogMonitorTest` (4): over-threshold emits, under/at emits nothing, severity scaling.
- Broker-free (mocked repos + mocked publisher). Full-context `WebhookAlertPersistenceIT` still green (new beans wire cleanly).

## Remaining (≤3)
1. Ops-UI / BFF surface for the replay endpoint + an audit-history read API are not built (backend only).
2. `WEBHOOK_BACKLOG` alert has no dedup/suppression window (unlike the durable `WEBHOOK_QUEUE_DEPTH` ledger) — every over-threshold tick republishes; add a suppressor if the topic gets noisy.
3. Bulk replay (replay all DLQ for a partner) not implemented — single delivery per call.
