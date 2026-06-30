> 작업: payment-executor backlog 완성 / 출처: agent

# payment-executor — agent build run (2026-06-30)

Branch: `agent/payment-executor` (worktree `D:/GMEPay+/wt/payment-executor`).
Base: `2a98ca8`. Three commits added this run.

## Build / test status
`./gradlew :services:payment-executor:test` — **BUILD SUCCESSFUL** (green) after every
commit and at end of run. Unit/slice tests only (no Docker/Postgres/Testcontainers available;
`@Tag("docker")` ITs compile but are skipped locally as designed).

## Starting state (important)
The PRD P0 "orchestrated POST /v1/payments wiring" named in the brief was **already done**
on the base commit and merged into this branch's base:
- `ClientBeans` already wires the full multi-arg `PaymentOrchestrator` ctor with the real
  `SettlementBookingService` + `RevenueLedgerClient` + `PartnerConfigClient`.
- `PaymentController` already resolves partner type from config-registry by `X-Partner-Code`
  (fail-open to `X-Partner-Type`), and the MPM flow is the two-phase authorize/confirm spine
  with an atomic compare-and-set claim (idempotency enforced) + a same-day cancel + refund path.
So the P0 items were not re-done; I advanced the next-highest-value NOT-DONE partner-facing
surface gaps that are buildable without infra.

## Tickets completed this run
1. **5.2-T16 — GET /v1/payments/{id} status retrieval.** Owner-scoped repo finder
   `findByPaymentIdAndPartnerId`; a foreign/absent payment → HTTP 404 `PAYMENT_NOT_FOUND`
   (never 403, no ownership leak). Entity status mapped to the lowercase API contract
   (CONFIRMED→approved, FAILED→failed, UNCERTAIN→uncertain, RELEASED/EXPIRED→cancelled,
   else pending); `prefund_deducted_usd` only for OVERSEAS+CONFIRMED; null fields omitted.
   New `PaymentDetailResponse`, `PaymentNotFoundException`, handler mapping, `GetPaymentControllerTest` (3 cases).
2. **5.2-T13 / 5.6-T11 — payment lifecycle event emission.** The service now EXPOSES its
   contract events through a `lib-events` `EventPublisher` seam: `payment.approved` (confirm
   capture+approve), `payment.failed` (scheme decline at confirm), `payment.cancelled`
   (successful same-day cancel). New `PaymentEvents` records + `EventPublisherConfig`
   (no-infra `LogEventPublisher`, `@ConditionalOnMissingBean` so an outbox→Kafka publisher
   supersedes it at integration with no caller change). `PaymentControllerIdempotencyTest`
   now asserts exactly-once `payment.approved` on a won claim, none on a lost claim.
3. **5.2-T27 — GET /v1/balance prefunding balance inquiry.** New `BalanceController`
   delegating to the prefunding service via a new `PrefundingClient.balance(partnerCode)`
   seam (default-throws so existing fakes stay valid; `RestPrefundingClient` implements it
   against `GET /v1/prefunding/{code}/balance`, mapping the `BalanceView` wire shape).
   LOCAL → HTTP 403 `FORBIDDEN`; OVERSEAS → balance + `is_below_threshold`; money as decimal
   strings per MONEY_CONVENTION. `BalanceControllerTest` (3) + `RestPrefundingClientTest` (+2).

## Backlog % estimate
The 150-ticket backlog predates the architecture (it describes a single-shot deduct-before-submit
`POST /v1/payments`; the service was rebuilt as a two-phase authorize→confirm spine, so many
tickets are satisfied by equivalent-or-better implementations rather than literal matches). On a
capability basis the core MPM/CPM execution, commit/rate-lock, prefunding deduct/reverse/reserve/
capture/release, same-day cancel, refund, settlement booking, revenue residual posting, AML limits,
idempotency, the 7 REST client adapters, and now status-read + balance-read + lifecycle events are
all present. Estimated **~80–85% of the service's realisable (non-infra-gated) backlog is DONE**.
The remainder is dominated by Docker/compose/Testcontainers-gated work I cannot run here.

## INTEGRATION REQUESTS
1. **lib-errors (`com.gme.pay.errors.ErrorCode`)** — add enum constants `PAYMENT_NOT_FOUND(404,false)`
   and `FORBIDDEN(403,false)`. Why: GET /v1/payments/{id} (5.2-T16) and GET /v1/balance (5.2-T27)
   need these canonical codes; the enum is frozen so I emitted them via the `ApiError(String code,…)`
   constructor as string literals. Adding the constants lets the handlers use `ApiError.of(ErrorCode,…)`
   consistently with every other mapping.
2. **prefunding service — deduction-history endpoint.** 5.2-T27's optional `?include_history=true`
   (last 10 `prefunding_ledger_entry` rows) is not implementable: prefunding exposes the `BalanceView`
   read but `recentDeductions` is documented as "null until the prefunding service wires the
   deduction-history endpoint." Request: prefunding expose `GET /v1/prefunding/{code}/deductions?limit=10`
   (or populate `BalanceView.recentDeductions`). I shipped the balance read without history; the
   `balance_usd`/`is_below_threshold`/threshold acceptance checks are met.
3. **lib-events-kafka wiring (integration phase, not a code change request).** Event emission uses the
   no-infra `LogEventPublisher` behind `@ConditionalOnMissingBean`. At integration, an outbox→Kafka
   `EventPublisher` bean (per the event/webhook pipeline) will supersede it automatically — no caller
   change. Flagged so the integration owner knows the seam exists and the topic is `gmepay.<eventType>`.

## What remains (top items)
- **17.2-G08** — swap H2 for real PostgreSQL ITs (Testcontainers). BLOCKED: no Docker locally.
- **17.5-G01** — verify the 7 REST clients E2E over the compose stack. BLOCKED: no compose/server lifecycle.
- **5.2-T27 history** — `?include_history=true` (see INTEGRATION REQUEST #2).
- Literal-vs-equivalent reconciliation of the older 5.2/5.5/8.4 single-shot-`POST /v1/payments`
  tickets against the as-built two-phase spine (documentation/test-naming cleanup, not new behaviour).
- HMAC-SHA256 auth pre-filter + IP allowlist + per-partner rate limiting (5.2-T18/T19/T26) — these are
  gateway/security-filter concerns; partial/likely owned at the gateway tier, not verified this run.

## Notes
- No files outside `services/payment-executor/` were modified. lib-* and other services read-only.
- Pre-existing `When.MAYBE` JSR-305 compile warning is unrelated and harmless.
