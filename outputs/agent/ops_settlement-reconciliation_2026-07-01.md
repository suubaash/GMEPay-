> 작업: Ops recon re-run (settlement) / 출처: agent

# Ops: operator recon re-run + reconciliation-break alert — settlement-reconciliation

## Scope
Additive operator surface on the existing reconciliation engine. Libs and other services untouched.
No server/docker. Real SFTP/IDD remain externally blocked (scheme files re-read from the configured inbox).

## 1. Operator recon re-run
- **Endpoint** `POST /v1/settlements/recon/rerun` — body `{batchId}` **or** `{settlementDate}` (exactly one;
  both/neither → `400`, unknown batch → `404`), plus `operatorId` / `reason`.
- **Scope** `batchId` → that one batch; `settlementDate` → every ZP0061/ZP0063 request batch generated for
  the day.
- **Idempotency** reuses the existing `ReconDiffEngine.runDiffForBatch` (delete-then-reinsert of exception
  rows keyed on `batchId`, per Phase 1). Two runs yield the same match/exception result and a single
  exception row — no double-post, no duplicate lines. The Addendum-001 residual post stays guarded
  (`residual_posted_at`), so a re-run never re-posts.
- **Audit** who/why (`operatorId`, `reason`) logged per run and per batch. Returns per-batch
  match/exception summary + totals (`ReconRerunResponse`).
- **File source** extracted `ReconFileSource` (shared with `ReconScheduler`) resolves the confirmation file
  (ZP0061→ZP0062, ZP0063→ZP0064). A missing file re-diffs against an empty scheme (surfaces MISSING_SCHEME —
  re-raises the break rather than silently passing).

## 2. Reconciliation-break alert
- `ReconBreakAlerter` (+ `ReconAlertEvent`) emits an `OpsAlertPayload` on **any** open exception from a recon
  run — scheduled or operator (wired into both `runDiff` and `runDiffForBatch`).
- `alertType=RECON_BREAK`, `subjectRef=batchId`, `detail` = exception-count/missing/total-break-₩ summary,
  `eventType="ops.alert"` → topic `gmepay.ops.alert`.
- **Severity** CRITICAL on any MISSING line or total break ≥ ₩10M; WARN on ≥5 lines or ≥ ₩1M; else INFO.
- **Transport** via `EventPublisher` seam (`ReconAlertConfig`, same selection as the outbox): Kafka when
  configured, else `LoggingEventPublisher` log-fallback — no broker needed locally. Clean batch emits nothing.

## Tests — `./gradlew :services:settlement-reconciliation:test` GREEN
- `ReconRerunServiceTest` (7): idempotent re-run (same result, one row); forced mismatch → RECON_BREAK alert
  + persisted exception; clean batch → no alert, batch RECONCILED; scope validation; by-date scope.
- `ReconBreakAlerterTest` (6): alert shape/subjectRef, INFO/WARN/CRITICAL severity, clean-run no-emit.
- Updated 3 existing engine test call sites for the new alerter ctor arg. Full module suite passes.

## Remaining (≤3)
1. Real SFTP pull of ZeroPay result files (currently inbox file-system read) — externally blocked (IDD).
2. Final ZP0062/ZP0064 column widths pending ZeroPay IDD (parser layout is a placeholder).
3. Admin-ui surface for the re-run endpoint + a downstream ops.alert consumer (out of this service's scope).
