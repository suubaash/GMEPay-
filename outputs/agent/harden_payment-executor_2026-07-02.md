> 작업: harden fail-closed gate + DECLINE_SPIKE / 출처: agent

# payment-executor — harden kill-switch (defect #4) + DECLINE_SPIKE alert (defect #5)

Branch `fix/payment-executor` (off `fix/contracts`). Additive only; libs + other services untouched.

## Defect #4 — kill-switch safety inversion → fail-CLOSED for security

`OperationalGate` reads config-registry `GET /v1/ops/operational-status` per new authorization. The
client (`RestOperationalStatusClient`) defaulted to fail-**OPEN** with **no client timeout**: a cold
executor (no cache) during a config-registry outage let a suspended/paused partner transact.

Fix (client, not the gate — the gate stays a pure precedence evaluator):
- **Default fail-CLOSED for the security flags** (`systemPaused` / `maintenanceMode` / partner/scheme/
  route *suspended*). On unreachable/unknown **and no fresh or last-known-good cache**, the client
  returns a synthetic `systemPaused` view → the gate DENIES with the existing `SYSTEM_PAUSED` code.
  Last-known-good cache is still preferred over either policy, so a brief blip does not flip behaviour;
  only a genuinely no-signal cold executor fails closed. `gmepay.ops.status.fail-open` default flipped
  `true → false` (config-overridable back to legacy allow-on-outage).
- **Hard client timeout**: explicit connect + read timeouts via `ClientHttpRequestFactorySettings`
  (`gmepay.ops.status.connect/read-timeout-millis`, default **500ms** each). A hung registry times out →
  `ResourceAccessException` → treated as unreachable → fail-closed-security applies.

## Defect #5 — DECLINE_SPIKE ops alert

New `alert/DeclineSpikeMonitor` (+ local `alert/OpsAlertEvent`): in-memory rolling window per partner
AND per classified scheme/network. When decline rate over `window-seconds` (60s) exceeds
`threshold-rate` (0.5) with ≥ `min-samples` (20) in-window, emits `OpsAlertPayload`
(`DECLINE_SPIKE`, severity WARN / CRITICAL≥0.8, subjectRef=partner/scheme) on `gmepay.ops.alert` via
the `EventPublisher` seam (LogEventPublisher fallback). Per-subject `cooldown-seconds` (300s) throttles
repeats. **Default OFF** (`gmepay.decline-spike.enabled`); injected `@Nullable` into `WalletPayController`,
recorded after each pay outcome. Never throws into the pay path. Added a system-UTC `Clock`
`@ConditionalOnMissingBean`.

## Test status

`./gradlew :services:payment-executor:test` — **GREEN**. New/updated:
- `RestOperationalStatusClientTest` (6): default fail-CLOSED on unreachable+no-cache; last-known-good
  preferred on a later outage; real hung local HTTP server proves read timeout fires within budget →
  unreachable → fail-closed.
- `OperationalGateTest` (8): synthetic unreachable `systemPaused` → `SYSTEM_PAUSED` denial.
- `DeclineSpikeMonitorTest` (4): burst emits alert; below-min-samples + all-approved silent; cooldown
  suppresses repeat.

## Remaining (≤3)
1. DECLINE_SPIKE is wired only on the wallet `/v1/pay` path (the explicit APPROVED/DECLINED surface);
   the orchestrated confirm-phase scheme declines are not yet fed into the monitor.
2. In-memory counter is per-instance (not shared across executor replicas) — fine for a spike signal,
   but multi-instance aggregation would need a shared store.
3. `SYSTEM_PAUSED` et al. are still string codes (lib-errors frozen) — promote to `ErrorCode` enum when
   the lib unfreezes.
