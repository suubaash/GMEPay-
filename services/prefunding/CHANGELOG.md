# prefunding — CHANGELOG

## 2026-07-02 (fix/prefunding — harden: release-on-reversal + FLOAT_LOW ops alert + ShedLock)

### Fixed — #1 release the held float on payment reversal
- New `payment.reversed` consumer (`PaymentReversedKafkaConsumer` + `PaymentReversedEventHandler`,
  topic `gmepay.payment.reversed`, deserialises canonical `PaymentReversedPayload`). On receipt it
  credits `reversedUsd` back onto the partner balance via new
  `PrefundingService.releaseReversedFloat(partnerId, txnRef, reversedUsd)` — closing the leak where an
  operator force-resolve →REVERSED never returned the held USD. Idempotent on `txnRef` (a CREDIT
  tagged with the txnRef is the reversal marker, shared with the operator `reverse` path), so Kafka
  redelivery / an already-handled reversal never double-credits. Null/absent `reversedUsd` logs + no-ops;
  missing txnRef/partnerId or bad JSON is poison → DLT. Consumer wiring
  (`PrefundingKafkaConsumerConfig`) is `@ConditionalOnProperty(spring.kafka.bootstrap-servers)`, MANUAL
  ack, retry→`.DLT` (mirrors revenue-ledger); no broker needed for local/tests.

### Added — #5 FLOAT_LOW ops alert (converged onto the ops control tower)
- `TierAlertEvaluator` now ALSO emits an `OpsAlertPayload`(alertType `FLOAT_LOW`) outbox row on topic
  `gmepay.ops.alert` for every low-balance crossing it already raises — the existing per-partner
  `prefunding.alert` stream is unchanged (converge, not replace). Severity scales with depth below
  threshold: TIER_95→INFO, TIER_85/TIER_70→WARN, BREACH→CRITICAL; `subjectRef`=partner code,
  `detail`=balance/threshold (decimal strings).

### Added — #3 ShedLock on the scheduled outbox drain
- `OutboxPublisher.publishPending()` (the one `@Scheduled` in this service) now carries
  `@SchedulerLock`; `OutboxConfig` gains `@EnableSchedulerLock` + a `JdbcTemplateLockProvider`. New
  `V008__create_shedlock.sql` (additive) backs the lock. With >1 replica exactly one instance drains
  per tick — no double-publish. Deps: `shedlock-spring` + `shedlock-provider-jdbc-template` 5.16.0,
  plus `lib-events-kafka` + `spring-kafka` for the consumer.

### Tests
- `PaymentReversedEventHandlerTest`: reversal credits held USD; idempotent on redelivery; null
  reversedUsd no-op; unknown partner throws; missing txnRef poison.
- `FloatLowOpsAlertTest`: crossing emits FLOAT_LOW on `gmepay.ops.alert` alongside `prefunding.alert`;
  INFO/WARN/CRITICAL severity by tier/breach.
- `OutboxPublisherShedLockTest`: `@SchedulerLock` present + named on the drain.
- Existing `TierAlertEvaluatorTest` outbox-count assertions filtered to `prefunding.alert` (now two
  outbox rows per crossing). `./gradlew :services:prefunding:test` green (61 tests).

## 2026-06-30 (w3/prefunding — Wave-3 credit-limit / AML-cap push, IR-pf-2)

### Added — config-registry → prefunding limit push
- `PUT /internal/v1/prefunding/{partnerId}/credit-limit` on `PrefundingInternalController`
  (`CreditLimitPushRequest` → `CreditLimitPushResponse`) accepts per-partner `creditLimitUsd` +
  AML caps (`amlDailyCapUsd` / `amlMonthlyCapUsd` / `amlAnnualCapUsd` / `amlDailyTxnCountCap`),
  pushed once from config-registry instead of arriving per-request. Idempotent upsert: re-PUT
  overwrites (a null cap clears it); if the partner has no balance row yet (push can precede
  provisioning) one is created with a zero opening balance. Money as decimal strings; negative
  values → 400 VALIDATION_ERROR. Returns stored limits + derived available + balance.
- `PrefundingService.pushPartnerLimits(...)` (upsert under the per-partner row lock) +
  `getPartnerLimits(...)` (read). New record `PartnerLimits`.
- V007 migration: `partner_balance` gains `aml_daily_cap_usd` / `aml_monthly_cap_usd` /
  `aml_annual_cap_usd` NUMERIC(19,4) + `aml_daily_txn_count_cap` INTEGER (all NULLABLE = no cap).
  Entity `PartnerBalanceEntity` extended with the matching fields.

### Applied — stored limits now take effect
- Stored `credit_limit` already feeds the deduct/reserve gate (available = balance + credit_limit −
  reserved) via the existing `PrefundingAccount` (lib-prefunding, untouched) — so PUT-ing a credit
  limit immediately raises a partner's deduction headroom.
- `PrefundingService.chargeCumulative` now falls back to the STORED AML caps when the caller omits
  them per-request; a non-null per-request cap still overrides (purely additive, no behaviour change
  for existing callers that pass caps).

### Tests
- `PrefundingCreditLimitApiTest` (MockMvc, H2 PG-mode): PUT stores limits; re-PUT updates + clears
  null caps; upsert creates a missing partner row; stored credit limit lets a deduct exceed raw
  balance (and 402s beyond limit); stored daily AML cap enforced on `/cumulative-charge` with no
  per-request cap.

## 2026-06-30 (p2/prefunding — Phase-2 cross-service wiring)

### Added — deduction history (IR-pe-2)
- `GET /v1/prefunding/{code}/deductions?limit=N` on `PrefundingController` → canonical
  `PrefundingDeductionHistoryView` (lib-api-contracts) wrapping most-recent-first
  `BalanceDeductionEntry` rows (amountUsd / at / txnRef). Unblocks payment-executor balance
  `?include_history=true`. `limit` defaults to 20, clamped 1..500; unknown partner ⇒ empty list.
- `PrefundingService.recentDeductions(partnerId, limit)` + repo
  `findByPartnerIdAndEntryTypeOrderByCreatedAtDescIdDesc` (Pageable-bounded DEBIT query).

### Added — CPM reserve/release (IR-qr-3)
- `POST /internal/v1/prefunding/{partnerId}/reserve` (`PrefundingReserveRequest` →
  `PrefundingReserveResponse`) soft-holds funds at OVERSEAS CPM/QR token issuance; idempotent on
  `idempotencyKey` (CPM token/session id, falls back to txnRef then `Idempotency-Key` header).
  Returns the RESERVE-ledger-entry-id `reservationId` handle + availableUsd + total reservedUsd.
- `POST /internal/v1/prefunding/{partnerId}/release` (`PrefundingReleaseRequest`) frees the hold on
  expiry/decline; idempotent on `idempotencyKey`; a release with no active hold is a 0 no-op.
- Backed by the same per-partner `SELECT ... FOR UPDATE` lock + `available = balance + credit_limit −
  reserved` invariant as the existing two-phase reserve/capture/release; 402 INSUFFICIENT_PREFUNDING on
  overdraw, nothing held. New service methods `reserveForCpm` / `releaseForCpm` reuse the RESERVE/RELEASE
  ledger machinery without disturbing the existing txnRef-keyed `reserve`/`release`.

### Tests
- `PrefundingCpmReserveApiTest`: reserve holds + returns reservationId; idempotent reserve replay;
  release restores available; idempotent release; reserve-overdraw → 402; reserve+deduct interaction
  stays non-negative against available.
- `PrefundingDeductionHistoryApiTest`: most-recent-first + limit cap; default limit; unknown partner ⇒
  empty list.

## 2026-06-30 (agent/prefunding)

### Added — internal cross-service deduct/reverse (transaction-mgmt integration request)
- `POST /internal/v1/prefunding/{partnerId}/deduct` and `POST /internal/v1/prefunding/{partnerId}/reverse`
  on a new `PrefundingInternalController`, letting transaction-mgmt drive PENDING_DEBIT→DEBITED and
  reversal without cross-DB access (MSA rule).
  - Atomic: backed by the existing per-partner `SELECT ... FOR UPDATE` row lock.
  - Idempotent: deduct keys on `idempotencyKey` (request body) or the `Idempotency-Key` header,
    persisted as the ledger `txn_ref`; a replay returns the original DEBIT entry id + unchanged
    balance (`replayed=true`) and does NOT double-charge. Reverse is idempotent by `txnRef`.
  - Both responses carry the resulting `balance` (+`currency`) and the `ledgerEntryId` so the caller
    can record the concrete ledger reference. 402 INSUFFICIENT_PREFUNDING on overdraw; 400 when the
    idempotency key is missing from both body and header.
- `PrefundingService.deductIdempotent(...)` returning `DeductResult(balanceAfter, ledgerEntryId, replayed)`;
  `deduct(...)` now delegates to it (unchanged behaviour for existing callers).
- `PrefundingService.ReverseResult` now also carries `ledgerEntryId` (the reversal CREDIT entry id,
  or the existing one on a replay; null when there was nothing to reverse).

### Tests
- `PrefundingInternalApiTest` (MockMvc): deduct returns balance+ledgerEntryId; idempotent replay via
  body key and via header; insufficient→402; missing key→400; reverse restores balance + reports
  credit id; second reverse is a 0 no-op.
- `InternalDeductConcurrencyIT` (H2 PG mode, no Docker): 10 concurrent deducts on a 500-USD balance
  admit exactly 5 winners, balance ends at exactly 0 and never goes negative; a concurrent burst of
  replays for one idempotency key debits at most once.
- `PrefundingServiceIT`: added idempotent-replay-no-double-charge and reverse-reports-credit-id cases.
