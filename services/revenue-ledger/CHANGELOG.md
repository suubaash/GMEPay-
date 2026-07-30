# revenue-ledger — CHANGELOG

All notable changes to the revenue-ledger service. Newest first.

## 2026-07-28 — both `/v1/journals` POSTs returned 406 on every call (T3-12, feat/exec-gap-closure-2026-07-28)

No schema change, no Flyway migration, no behaviour change to any posting. The JSON wire shape is
unchanged — only the Java type used to produce it.

### Fixed
- **`POST /v1/journals/rounding-residual` and `POST /v1/journals/reversal` returned HTTP 406 Not
  Acceptable on every successful post**, so the rounding-residual journal (the ₩500-class remainder)
  and the refund/cancel reversal journal were **never written**. Found by the T3-5 footprint run over
  200 real payments: `payment-executor.revenue_posting_failures` grew by exactly 1.00 rows/payment
  because `RestRevenueLedgerClient` swallows posting failures by design (T2-1) and diverted every
  residual to the replay queue. Nobody noticed and the whole suite stayed green.
  - **Root cause: the controller returned the domain `Journal`.** `Journal` and `LedgerEntry` are plain
    final classes with record-*style* accessors (`journalId()`, `entries()`, `amount()`) and are not
    Java records, so Jackson discovers **zero** properties. With default `FAIL_ON_EMPTY_BEANS`,
    `ObjectMapper.canSerialize(Journal.class)` is `false` ⇒ `MappingJackson2HttpMessageConverter.canWrite`
    is `false` ⇒ Spring MVC finds no converter able to produce a representation and raises
    `HttpMediaTypeNotAcceptableException`. It is a **406, not a 500**, because the failure is in converter
    *selection*, before serialization is ever attempted.
  - **Fix: new `JournalResponse` web DTO** (a record — natively introspectable) returned by both POSTs.
    Content negotiation was **not** loosened and `FAIL_ON_EMPTY_BEANS` was **not** disabled: that would
    have turned the 406 into a silently-empty `{}` body, which is the same defect wearing a 200. It also
    keeps the domain model off the wire, like `RevenueCaptureResponse` and `JournalView` already do.
  - **The client was correct and is unchanged** — it sends `Content-Type: application/json` and no
    `Accept` (= `*/*`). Nothing in `payment-executor` was touched. settlement-reconciliation's per-batch
    `RestRoundingResidualClient` was hitting the same 406 and is cured by this server-side fix alone.

### Tests
- **New `RevenueLedgerHttpContractTest` — a `@WebMvcTest` slice, not another standalone MockMvc test.**
  It runs against the real Boot-configured message converters and real content negotiation, and covers
  all four endpoints the cross-service clients post to (`/v1/journals/rounding-residual`,
  `/v1/journals/reversal`, `/v1/revenue/capture`, `/v1/revenue/commission-split`), asserting response
  **bodies** rather than just statuses, plus the no-`Accept`-header request shape the real client sends
  and the 204/400 branches. Against the pre-fix controller the three journal cases fail with
  `Status expected:<200> but was:<406>`.
- The gap existed because the two `/v1/journals` POSTs **had no HTTP-level test at all** — they were
  covered only at service level (`RoundingResidualTest`, `RevenueReversalRoundingResidualTest`), which
  never goes through a message converter. `/v1/revenue/capture` and `/v1/revenue/commission-split` were
  verified over real HTTP here and were always fine.

## 2026-07-28 — the main P&L now reaches the double-entry journal (T2-4, feat/exec-gap-closure-2026-07-28)

Additive. **No schema change** — no new table or column was needed, so no Flyway migration was added
(next free version in this module remains `V007`; there are no vendor-specific migration dirs here).

### Fixed
- **Revenue capture now posts a balanced journal.** `RevenueCaptureService.capture` is `@Transactional`
  and calls the new `LedgerPostingService.postCapturedRevenueJournal` in the SAME transaction as the
  `revenue_records` insert, so the record and its journal commit together or not at all. Before this the
  class documented itself as "Not double-entry" and `LedgerPostingService.postRevenueCapture` /
  `postFeeShareSplit` had **zero production callers** — journals received only rounding residuals and
  cancel/refund reversals, so `RECEIVABLE_PARTNER` was credited by reversals that were never debited by a
  capture and no trial balance was possible (CFO#7).
  - `DEBIT RECEIVABLE_PARTNER / CREDIT REVENUE_FX_MARGIN` (USD) and
    `DEBIT RECEIVABLE_PARTNER / CREDIT REVENUE_SERVICE_CHARGE` (service-charge ccy) — the same accounts
    and sides `postRevenueCapture` always used. **No account code and no accounting policy was invented.**
  - Idempotent on `txnRef`: a CREDIT to an income account is only ever produced by an original capture
    (a reversal mirrors the sides), so a replay/Kafka redelivery adds nothing. DB backstop is the existing
    `UNIQUE(revenue_records.txn_ref)`, since both writes share one transaction.
  - Zero-revenue transactions post **nothing** rather than the nominal zero journal `postRevenueCapture`
    emits (CFO#14) — they are reported as `zeroAmount` by the reconciliation self-check instead.
  - A replay also **back-fills** a journal for a record that has none, so pre-T2-4 rows are repairable by
    re-posting the capture.
- **The commission split is no longer record-only** (CFO#4). `CommissionSplitRecordService.recordIfAbsent`
  posts the scheme-side leg via the new `LedgerPostingService.postCommissionSplitJournal`, in its existing
  transaction, from the amounts already stored on the record (so journal and record cannot drift):
  `DEBIT RECEIVABLE_PARTNER net / CREDIT REVENUE_GME_FEE_SHARE gmeGross / CREDIT PAYABLE_SCHEME scheme` —
  again the exact shape `postFeeShareSplit` used. An input that does not satisfy
  `gmeGross + scheme == net` is **refused**, never silently balanced.

### Added
- **`GET /v1/journals/trial-balance?startDate=&endDate=[&strict=true]`** → per `(account, currency)`
  debit/credit totals, plus per-currency whole-book totals with `difference` and a top-level `balanced`
  flag and an `imbalances` list. An imbalance logs at ERROR and is reported explicitly; `strict=true`
  additionally returns **409** so an automated day-close check cannot ignore it. This is the artifact that
  was impossible before: every `Journal` is validated balanced before storage, so a non-zero difference
  means ledger rows exist that no balanced journal produced.
- **`GET /v1/revenue/journal-reconciliation?startDate=&endDate=[&strict=true]`** → the finance-team
  self-check: per-table coverage (`total` / `journalled` / `notJournalled` + the offending `txnRef`s,
  capped at 100 with an honest `truncated` flag / `zeroAmount`), per-stream recorded-vs-journalled
  `tieOuts` with a signed `variance`, and `unmappedComponents` — money that is recorded but cannot be
  journalled for want of an account code. Tie-outs are scoped by **reference set**, not journal post date,
  so business-date vs post-date skew cannot masquerade as a variance; they compare gross CREDITs, since a
  revenue record is never reversed while a reversal DEBITs the income account.
- `TrialBalanceService`, `RevenueJournalReconciliationService`, DTOs `TrialBalanceView` /
  `RevenueJournalReconciliationView`, and additive date-ranged finders on
  `LedgerEntryEntityRepository` (`trialBalanceRows`), `RevenueRecordJpaRepository` and
  `CommissionSplitRecordRepository`.
- 29 tests (114 total, 0 failures): balanced lines per revenue type, idempotent replay, journal back-fill,
  record+journal rollback atomicity (induced by the real `NUMERIC(20,8)` vs `NUMERIC(20,4)` column
  mismatch, not a mock), trial balance zero over a fixture period **and** a deliberate orphan-debit
  imbalance reported with the exact difference, recorded-but-not-journalled rows surfacing in the
  self-check, and the HTTP contracts including the `strict` 409.

### Known gap — awaiting a finance-owner decision (deliberately NOT invented)
- The **partner-side leg of the two-sided commission split** (`commission_splits.partner_share_krw`, the
  wallet partner's carve out of GME's gross commission) has **no account code in this module**, so it is
  not journalled. `REVENUE_GME_FEE_SHARE` therefore carries GME's **gross** commission and overstates
  retained commission by exactly that amount. Rather than adding a plausible account, the amount is
  reported per period as `unmappedComponents[PARTNER_COMMISSION_SHARE]` and keeps the reconciliation's
  `clean` flag **false** while it carries money. `CommissionSplitJournalTest` asserts its absence on
  purpose, so deciding the account forces the mapping in rather than letting the gap be forgotten.

## 2026-07-03 — journal view read API (feat/journal-view-be)

Additive, read-only. No new dependency, no schema change.

### Added
- **`GET /v1/journals?from=&to=&reference=&page=0&size=50`** → `{ items:[ { journalId, reference,
  createdAt, lines:[ { account, side, amount, currency } ] } ], page, size, total }`. Lists posted
  double-entry journals with their ledger lines so the Admin UI can show the DR/CR breakdown of every
  money movement. All params optional; default window = last 30 days (`to` = now), `size` capped at 200,
  newest-first by `createdAt` (= `journals.posted_at`).
  - `side` = `ledger_entries.entry_type` verbatim (`"DEBIT"`/`"CREDIT"`) — the explicit stored DR/CR
    column, NOT derived from the sign of `amount` (amount is always the non-negative magnitude).
  - `currency` = `ledger_entries.currency`; `createdAt` = `journals.posted_at`. Money rides as a
    decimal string per `MONEY_CONVENTION.md`.
- **`JournalQueryService`** — pages only the journal HEADS (sorted `posted_at` DESC) then batch-loads
  that page's lines in ONE query, so `ledger_entries` is never fully loaded into memory.
- Additive repo finders: `JournalEntityRepository.findByPostedAt…Between` (+ `…AndReference…`) with
  `Pageable`; `LedgerEntryEntityRepository.findByJournalIdInOrderByJournalIdAscIdAsc` (batch line load).
- New web DTOs `JournalView` / `JournalPage` and `JournalViewController` (GET on the existing
  `/v1/journals` base path alongside the POST rounding-residual/reversal endpoints).

## 2026-07-02 — reversing journal on payment.reversed (fix/revenue-ledger)

### Added
- **`payment.reversed` consumer** (`PaymentReversedEventHandler` + `PaymentReversedKafkaConsumer`) on
  topic `gmepay.payment.reversed`, consuming the canonical `PaymentReversedPayload` (lib-api-contracts).
  When a payment's terminal outcome becomes `REVERSED` — including an operator force-resolve of an
  `UNCERTAIN` txn — it books a **reversing journal**: a balanced contra-entry that backs out the original
  revenue capture for that txnRef (FX margin + service charge + fee-share). Registered as a bean only by
  `RevenueLedgerKafkaConsumerConfig` (gated on `spring.kafka.bootstrap-servers`), reusing the existing
  MANUAL-ack container factory + DLT error handler — broker-free by default, exactly like the
  `payment.approved` consumer.
- **`RevenueReversalService.reverseCapture(txnRef)`** — finds every original capture journal for the
  txnRef via `JournalStore.findByReference` and posts a single balanced journal mirroring all their lines
  with the DEBIT/CREDIT side flipped, so the net across capture + reversal is exactly zero on every
  account and currency. **Idempotent on txnRef**: a reversing line is the only DEBIT ever posted to a
  `REVENUE_*` income account (capture only CREDITs them), so a prior reversal is detected and a repeat
  `payment.reversed` is a no-op (no second contra). **No original capture → safe no-op** (logged, acked,
  never dead-lettered), since a reversal before/without a capture is a benign ordering artifact.

### Notes
- **Operator-COMPLETED needs no new code.** transaction-mgmt emits the normal `payment.approved` for an
  operator COMPLETED; the existing `PaymentApprovedEventHandler` (which gates only on
  `eventType == payment.approved`, not on source/status) books the capture unchanged.

### Tests
- `PaymentReversedEventHandlerTest` (+8, broker-free, real `InMemoryJournalStore`): reversal nets the
  original capture to zero on every account/currency; second reversal is an idempotent no-op (no second
  contra); reversal for an unknown txnRef is a safe no-op (nothing posted); txnRef record-key fallback;
  poison cases (wrong eventType, invalid JSON, empty payload, missing txnRef).

## 2026-06-30 — Wave-3: idempotent rounding-residual posting (w3/revenue-ledger)

### Changed
- **`postRoundingResidual(reference, residual, currency)` is now IDEMPOTENT on `reference`.** A repeat
  post with a reference that already has a rounding journal is a no-op: it returns the existing journal
  (same id) and creates NO second line, so the running `total_rounding_usd` aggregate counts the residual
  exactly once. settlement-reconciliation (per settlement batch id) and payment-executor (per TXN ref)
  can both retry safely regardless of caller-side guards. `LedgerPostingService` pre-checks via the new
  port method `JournalStore.findRoundingResidualByReference`; on a hit it short-circuits before posting.
- Guard is **scoped to the `REVENUE_ROUNDING` account only** — revenue-capture / fee-share / reversal
  journals that carry the SAME `reference` on other accounts are unaffected, so the per-TXN and per-batch
  keying schemes coexist without collision.

### Added
- **Flyway `V006__rounding_residual_idempotency.sql`** — `rounding_residual_keys(reference PRIMARY KEY,
  journal_id, posted_at)` as the DATABASE backstop against a concurrent double-post racing the app-level
  pre-check (the PK trips and rolls back the second writer). A dedicated key table (not a partial /
  expression UNIQUE INDEX) is used because H2 in PostgreSQL MODE — the no-Docker `@DataJpaTest` engine —
  supports neither; a plain PK is portable across H2 and PostgreSQL. `JpaJournalStore.save` inserts the
  guard row in the SAME transaction when (and only when) the journal posts to `REVENUE_ROUNDING`.

### Tests
- `JournalPersistenceIT` (+3, H2): double-post same reference → exactly one journal + same id returned;
  distinct references → distinct journals; retry does NOT double-count the `total_rounding_usd` aggregate.
- `RoundingResidualTest` (+1, unit): repeat post returns the existing journal id.

## 2026-06-30 — Phase 2 cross-service wiring (p2/revenue-ledger)

### Changed
- **Re-targeted the `payment.approved` consumer onto the canonical
  `com.gme.pay.contracts.events.PaymentApprovedPayload`** (lib-api-contracts) — `PaymentApprovedEventHandler`
  now deserializes the event directly into that shared DTO via Jackson (`JavaTimeModule`,
  `FAIL_ON_UNKNOWN_PROPERTIES` off) instead of plucking JSON fields by hand, so payment-executor
  (producer) and revenue-ledger (consumer) agree at the type level. Field set
  (partnerId, schemeId, collectionMarginUsd, payoutMarginUsd, serviceChargeAmount, serviceChargeCcy,
  feeSharePct) maps 1:1 onto `RevenueCaptureService.capture(...)`. Defensive defaults preserved: null
  money → ZERO, serviceChargeCcy → "USD", txnRef → aggregateId → record key, revenueDate → occurredAt
  UTC date. Poison handling unchanged (still `IllegalArgumentException` → DLT); a bad-money value now
  surfaces via `InvalidFormatException` carrying the offending field path. Added `lib-api-contracts`
  as an `implementation` dependency.
- **`GET /v1/revenue` now returns the canonical shared `com.gme.pay.contracts.RevenueSummaryView`**
  (incl. `totalRoundingUsd`) so ops-partner-bff and the reporting revenue board bind one type. The
  service-local `RevenueSummaryResponse` is retained only as the value source. Money rides as decimal
  STRINGs via the view's `@JsonFormat(STRING)` (the prior local DTO emitted unquoted numbers); field
  names unchanged (camelCase — the old "snake_case" Javadoc was inaccurate, no naming strategy was set).

### Confirmed (no code change)
- **Rounding-residual reference-key shape (settlement-reconciliation IR-2).** `postRoundingResidual(reference,
  residual, currency)` takes `reference` as an opaque audit handle (ledger `reference` column `length=64`),
  accepting EITHER a per-txn ref (`TXN-…`, payment-executor) OR a settlement batch id
  (`ZP00NN-YYYYMMDD-WINDOW`, ≤25 chars; settlement-reconciliation's per-batch aggregate residual).
  Documented on `RoundingResidualController`; not idempotent on `reference` (callers post once).

### Tests
- `RevenueControllerTest` (new, 3) — asserts the `RevenueSummaryView` wire shape incl. money-as-string
  and `totalRoundingUsd` (populated + null-coalesced-to-zero), plus the 400 date-range guard.
- `PaymentApprovedEventHandlerTest` — retargeted onto the DTO path (11 green; bad-money assertion still
  surfaces the field name).
- `RoundingResidualTest` — added a batch-id-keyed case proving the reference is audited verbatim on
  every ledger line (IR-2 proof).

## 2026-06-30 — surface REVENUE_ROUNDING in GET /v1/revenue (7.3-T27)

### Added
- `JournalStore.sumRoundingByDateRange(start, end, currency)` — signed net rounding gain/loss over a
  date range (CREDIT adds = gain, DEBIT subtracts = loss). Implemented in both `JpaJournalStore`
  (JPQL aggregate joining `ledger_entries`→`journals` on the `posted_at` window, end-exclusive UTC)
  and `InMemoryJournalStore` (stream fold). Reconciles to the sum of the period's posted residuals.
- `RevenueRecordService.getRoundingTotalUsd(start, end)` and a new `total_rounding_usd` field on the
  `GET /v1/revenue` response (`RevenueSummaryResponse`). `REVENUE_ROUNDING` was already in the chart
  of accounts; this completes T27 by surfacing it in the revenue report.
- `RoundingAggregationTest` — 4 tests (signed reconciliation, currency isolation, out-of-range
  exclusion, zero-not-null) driving the real `LedgerPostingService` + `InMemoryJournalStore`.

## 2026-06-30 — async payment.approved revenue capture (agent/revenue-ledger)

### Added
- **`gmepay.payment.approved` Kafka consumer** (`com.gme.pay.ledger.consumer`) — the async
  ingestion path mandated by `docs/INTER_SERVICE_CONTRACTS.md` ("revenue-ledger consumes
  events payment.approved … async"). Previously only the sync `POST /v1/revenue/capture`
  endpoint existed; this closes the documented event-driven contract.
  - `PaymentApprovedKafkaConsumer` — `@KafkaListener` on `gmepay.payment.approved`, MANUAL ack
    (offset committed only after the revenue row persists), consumer group `revenue-ledger`.
  - `RevenueLedgerKafkaConsumerConfig` — gated on `spring.kafka.bootstrap-servers` (no broker →
    no listener container, so unit slices and the local default stay broker-free). DLT to
    `gmepay.payment.approved.DLT` after 3 total attempts via `DefaultErrorHandler`.
  - `PaymentApprovedEventHandler` — parses the event JSON, maps revenue fields (txnRef→aggregateId
    →record-key fallback; revenueDate→occurredAt-UTC fallback; serviceChargeCcy default USD),
    rejects poison records (bad JSON / wrong eventType / missing txnRef / bad-or-negative money).
- **`RevenueCaptureService`** — single idempotent write path (by `txnRef`) now shared by both the
  sync capture controller and the async consumer, so a transaction captured by either surface
  yields exactly one row. Safe under Kafka at-least-once redelivery.
- `PaymentApprovedEventHandlerTest` — 11 unit tests (happy cross-border, idempotent redelivery,
  same-currency zero-margin, occurredAt date fallback, record-key txnRef fallback, and 6 poison
  cases). Broker-free, runs against the in-memory store wrapped in the real capture service.

### Changed
- `RevenueCaptureController` now delegates to `RevenueCaptureService` (was inlined store logic);
  201 Created on fresh capture, 200 OK on idempotent replay — behaviour unchanged.
- `build.gradle`: `spring-kafka` promoted to `implementation` (the consumer lives in `src/main`).
