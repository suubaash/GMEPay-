> 작업: ops alert paging dispatcher / 출처: agent

# Ops alert → real on-call paging (ops-partner-bff)

Closes the gap: `gmepay.ops.alert` was consumed into an in-memory store + `GET /v1/admin/ops/alerts`,
but nothing paged a human. Additive; only `services/ops-partner-bff/` touched; libs + other
services untouched. Branch `feat/ops-paging`.

## Port + adapters (ADR-015, vendor-agnostic, no cloud SDK)
- `PagingPort.page(PageRequest) → PageOutcome` (never throws). `PageRequest` = small stable shape
  `{alertType, severity, subjectRef, detail, occurredAt, link}`.
- `WebhookPagingAdapter` — POSTs that JSON to one configured on-call webhook URL. One generic hook
  covers Slack / PagerDuty Events API / Opsgenie / MS Teams (no vendor hardcoded). Explicit
  connect+read timeout, retries on 5xx/transport, 4xx = permanent (no retry).
- `LogPagingAdapter` — `@ConditionalOnMissingBean(PagingPort)` fallback (both selected in
  `PagingConfig` for deterministic ordering) so paging works with zero config.

## Dispatch / dedupe / severity
- `OpsPagingDispatcher.onStored(...)` called by `OpsAlertEventHandler` AFTER store.add. Pages only
  when `severity >= min-severity` (default CRITICAL; can be WARN). INFO/below stored only.
- Naturally single-fire across replicas: Kafka consumer group delivers each record once.
- Dedupe: suppresses repeat page for same `(alertType|subjectRef)` within the cooldown window;
  suppressed pages recorded as `SUPPRESSED`.
- Delivery record (`OpsAlertView.Paging` = status/channel/attempts/lastAt/detail) stamped on the
  stored alert and returned by `GET /v1/admin/ops/alerts`.

## Ack API + escalation
- `POST /v1/admin/ops/alerts/{id}/ack {operator, note}` → marks `acked` (stops escalation),
  reflected in alerts list + control tower. Fail-closed `ops:operate` RBAC (`OpsRbacGuard`) +
  durable operator-action audit (`ops.alert.ack`), same as other ops actions. 403 without perm;
  404 if evicted.
- `OpsPagingEscalationScheduler` (config-gated, default OFF) re-pages un-acked CRITICAL alerts
  older than N min. **Single-replica-only** — ops-partner-bff has NO DataSource, so NO ShedLock:
  run on one replica; cooldown still bounds duplicates. Documented to ShedLock-guard (shedlock +
  migration) if a DataSource is ever added.

## Config keys
`gmepay.ops.paging.webhook-url` (unset → log adapter) · `.timeout-ms`=3000 · `.max-attempts`=3 ·
`.min-severity`=CRITICAL · `.dedupe-window`=15m · `.link-base`= (optional deep-link) ·
`.escalation.enabled`=false · `.escalation.after`=10m · `.escalation.sweep-ms`=60000.

## Tests — `./gradlew :services:ops-partner-bff:test` GREEN (265+ tests)
CRITICAL→port invoked; INFO→not paged; WARN pages when threshold lowered; dedupe suppresses repeat
+ expires after window; WebhookPagingAdapter POSTs right shape + retries 5xx (MockRestServiceServer);
LogPagingAdapter is fallback when no URL / Webhook when URL set (PagingConfig); ack marks acked +
audits, 403 without `ops:operate`; escalation re-pages un-acked, ack stops it.

## Remaining (≤3)
1. Durable alert store (in-memory rolling buffer loses ack/paging state on restart).
2. ShedLock-guard escalation if/when the BFF gains a DataSource (currently single-replica-only).
3. Admin-ui: surface paging status + `acked/open` + an Ack button on the alerts strip.
