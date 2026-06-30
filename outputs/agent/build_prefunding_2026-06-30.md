> 작업: prefunding backlog 완성 / 출처: agent

# prefunding build report — 2026-06-30 (branch agent/prefunding)

## Build status
`./gradlew :services:prefunding:test` — **BUILD SUCCESSFUL**, 38 tests, 0 failures.
H2 (PostgreSQL mode) test scope; Docker-tagged ITs untouched. No live Postgres/Kafka required.

## Assessment of actual code state
Real source is far ahead of the backlog %s, as warned. Already built & live before this run:
atomic deduct/credit (SELECT FOR UPDATE, non-negative invariant via lib-prefunding
`PrefundingAccount`), reserve/capture/release two-phase holds + credit-limit headroom,
same-day reverse (idempotent), AML cumulative daily/monthly/annual caps + velocity cap
(`chargeCumulative`/`reverseCumulative`), tier alerts (95/85/70 + BREACH) via `TierAlertEvaluator`,
transactional outbox (`OutboxWriter`/`OutboxPublisher`, Kafka gated behind config), balance
provisioning + canonical `BalanceView` read surface, config-registry client (Rest + Stub).

## PRIORITY integration request — DONE
**deduct/reverse internal endpoints: COMPLETE.**
- `POST /internal/v1/prefunding/{partnerId}/deduct` — atomic (row lock), idempotent
  (`idempotencyKey` body OR `Idempotency-Key` header, persisted as ledger `txn_ref`), returns
  `{partnerId, balance, currency, ledgerEntryId, replayed}`. Replay does NOT double-charge.
  402 INSUFFICIENT_PREFUNDING on overdraw; 400 on missing key.
- `POST /internal/v1/prefunding/{partnerId}/reverse` — atomic, idempotent by `txnRef`, restores
  the debited amount, returns `{partnerId, reversedUsd, balance, currency, ledgerEntryId}`.
- New `PrefundingInternalController`; `PrefundingService.deductIdempotent(...)->DeductResult`;
  `ReverseResult` extended with `ledgerEntryId`. Existing `/v1/prefunding/*` callers unaffected.

## Tickets advanced this run
1. **3.5-T13 / 6.x internal REST** — `/internal/v1/prefunding/{partnerId}/deduct` + `/reverse` with
   `{newBalance, ledgerEntryId}` shape and idempotency. (The doc-named `/credit` and GET balance
   internal variants already exist via `/v1/prefunding/credit` + `BalanceProvisioningController`.)
2. **JUnit coverage** — `PrefundingInternalApiTest` (MockMvc: deduct+replay via body & header,
   402, 400, reverse + idempotent no-op), `InternalDeductConcurrencyIT` (H2, no Docker:
   concurrent-deduct-non-negative + concurrent-replay-debits-once), 2 added `PrefundingServiceIT` cases.

## % estimate
Core prefunding money-flow + the cross-service contract: **~90% of buildable scope**. Remaining is
mostly externally-gated or separate epics (recon/statements 6.5, admin top-up/threshold CRUD UI).

## INTEGRATION REQUESTS
1. **transaction-mgmt** — bind the new internal contract:
   `POST /internal/v1/prefunding/{partnerId}/deduct` body `{idempotencyKey, amountUsd}` (or
   `Idempotency-Key` header) → 200 `{partnerId, balance, currency, ledgerEntryId, replayed}` /
   402 `INSUFFICIENT_PREFUNDING`; `POST /internal/v1/prefunding/{partnerId}/reverse` body `{txnRef}`
   → 200 `{partnerId, reversedUsd, balance, currency, ledgerEntryId}`. Use the SAME idempotency key
   on deduct as the `txnRef` on reverse. Both internal-network only (not gateway-routed).
2. **config-registry** — confirm the per-partner `credit_limit_usd` and AML cap fields
   (daily/monthly/annual + daily txn-count) are pushed to prefunding via `PUT .../credit-limit` and
   supplied on `cumulative-charge`; today they arrive per-request from the caller.
3. **ops/admin BFF** — no dedicated prefunding admin top-up / alert-config CRUD controller exists
   (top-ups go through `/v1/prefunding/{code}/credit`, provisioning via `/provision`). If the admin
   portal needs threshold edit / explicit top-up endpoints (6.4-T24, 6.1-T19), request the shape.

## Remaining (top 3)
1. **WBS 6.5 reconciliation/statements** — entirely absent (no recon_run/discrepancy/statement
   tables, jobs, or `/internal/v1/recon/*` endpoints). Largest untouched epic.
2. **Admin top-up + threshold-config endpoints/UI** (6.1-T19, 6.4-T22/T24) — not built as a
   dedicated surface.
3. **BREACH→config-registry auto-suspend** — currently log-only via stub; wire the real suspend call
   once config-registry exposes it.
