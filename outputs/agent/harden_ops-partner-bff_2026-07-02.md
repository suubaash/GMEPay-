> 작업: harden fail-closed RBAC + audit + alert consumer / 출처: agent

# Harden ops-partner-bff — fail-closed RBAC + fail-closed audit + ops.alert consumer

Branch `fix/ops-partner-bff` (off `fix/contracts`). Additive; only `services/ops-partner-bff/`
touched. `./gradlew :services:ops-partner-bff:test` green.

## Defect #2a — Fail-CLOSED RBAC
- New `OpsRbacGuard` (`@Component`) replaces the static `OpsActionController.guard(...)` that
  **allowed the action when `X-Gme-Permissions` was absent**.
- `requireOps(header)` now DENIES 403 when the header is absent **or** lacks `ops:operate`.
  No permission ⇒ no privileged action.
- Config-overridable only via the dev flag `gmepay.ops.rbac.enforce` (default `true` = enforce).
  With the gate off, an *absent* header is allowed but a *present-but-wrong* header is still denied.
- All three action controllers (`OpsActionController`, `OpsTransactionController`,
  `OpsWebhookActionController`) inject the guard.

## Defect #2b — Fail-CLOSED audit for money-affecting actions
- Added `OperatorActionAuditClient.recordDurable(...)` throwing `AuditWriteException`
  (`@ResponseStatus(500)`) when the record can't be durably persisted.
- Every money/state-affecting action (pause/resume/maintenance/suspend/unsuspend, txn resolve,
  webhook replay, recon rerun) now calls `recordDurable` **before** delegating; on audit failure
  the action FAILS with 5xx and the **upstream is NOT called**.
- REST client fails closed on `recordDurable`; stub is always durable; best-effort `record(...)`
  retained for reads.

## Defect #5 — ops.alert consumer + endpoint
- `OpsAlertKafkaConsumer` + gated `OpsAlertKafkaConsumerConfig`
  (`@ConditionalOnProperty("spring.kafka.bootstrap-servers")`, MANUAL ack, `.DLT` poison handling)
  mirrors the revenue-ledger/notification-webhook pattern. No broker ⇒ no listener beans
  (no-broker fallback); the store/endpoint still work (empty list).
- `OpsAlertEventHandler` deserializes canonical `OpsAlertPayload` → bounded, newest-first,
  thread-safe in-memory `OpsAlertStore` (`gmepay.ops.alerts.capacity`, default 200).
- `GET /v1/admin/ops/alerts` — newest-first, filter by `severity`/`type`, `limit` (default 100).
- Control tower gains `recentAlerts` (total, critical count, newest 10).

## Tests (all green, no skips)
- `OpsRbacGuardTest` (4): absent→403, wrong→403, ops→ok, dev gate-off nuance.
- `OpsActionControllerTest` (12): incl. enforce=true missing-perm → 403 + upstream not called;
  ops:operate → proceeds; durable-audit failure on recon-rerun → 5xx + `rerunRecon` never called.
- `OpsAlertEventHandlerTest` (5), `OpsAlertControllerTest` (3), gating test (1),
  `ControlTowerControllerTest` +1 (alerts reflected newest-first). Existing suite unchanged.

## Remaining (≤3)
1. Durable (JPA) alert store — current store is in-memory, non-durable across restarts.
2. Real pager / on-call push for CRITICAL alerts (endpoint + tower only for now).
3. Wire live env: set `GMEPAY_OPERATOR_ACTION_AUDIT_CLIENT=rest` +
   `SPRING_KAFKA_BOOTSTRAP_SERVERS` so fail-closed audit + consumer are active in prod.
