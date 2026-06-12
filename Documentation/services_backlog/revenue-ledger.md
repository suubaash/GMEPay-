# revenue-ledger  (backend)

**Scope:** Double-entry ledger, 70/30 fee share, FX margin capture

**Owned WBS work-packages:** 7.2, 7.3  ·  **Tickets:** 46  ·  **Est:** 29.2h

## Service contract (MSA: own DB, API-only communication)

- **Datastore (owned by this service):** PostgreSQL `ledger` (double-entry)
- **APIs / events I EXPOSE:** /v1/revenue
- **APIs / events I CONSUME:** events payment.approved, settlement.completed (async)
- **Integration rule:** never read another service's database or import its private entities — call its API or consume its event; stub consumed services with WireMock in tests.

> Self-contained backlog for this service. Build it as its own repo/module with its own DB + Flyway migrations, against the `shared-libs` contracts (lib-money / lib-errors / lib-events / lib-api-contracts only). Each ticket has a deliverable + acceptance checks.


## WBS 7.2 — Scheme fee share (70/30) computation
### 7.2-T01 — Add scheme_fee_config table migration: merchant fee rates and VAN fee rates per scheme  _(35 min)_
**Context:** WBS 7.2 delivers the 70/30 scheme fee-share computation. The qr_scheme table already holds gme_fee_share_pct DECIMAL(6,4) (e.g. 0.70) plus range columns merchant_fee_domestic_min/max and merchant_fee_crossborder_min/max. To compute GME net fee share per transaction the engine needs the exact merchant-type-level fee rates and VAN fee rates stored as a lookup table. BRD-01 §8.3 (ZeroPay): merchant fee 0.80%-2.00% domestic / 1.70%-2.20% cross-border; VAN fee 0.08%-0.20% domestic / 0.17%-0.22% cross-border. Net merchant fee = gross merchant fee - VAN fee; GME share = net merchant fee x gme_fee_share_pct (0.70 for ZeroPay). merchant.merchant_type (VARCHAR 30, e.g. GENERAL, FRANCHISE) and merchant.fee_tier (DOMESTIC/CROSSBORDER) are the lookup keys.
**Steps:** Create migration file db/migrations/V7_2_001__scheme_fee_config.sql.; Define table scheme_fee_config: id BIGINT PK, scheme_id BIGINT FK -> qr_scheme NOT NULL, merchant_type VARCHAR(30) NOT NULL, fee_tier VARCHAR(20) NOT NULL CHECK IN ('DOMESTIC','CROSSBORDER'), merchant_fee_rate DECIMAL(6,4) NOT NULL, van_fee_rate DECIMAL(6,4) NOT NULL, effective_from DATE NOT NULL DEFAULT CURRENT_DATE, effective_to DATE NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by VARCHAR(120) NOT NULL, updated_by VARCHAR(120) NOT NULL.; Add UNIQUE constraint on (scheme_id, merchant_type, fee_tier, effective_from).; Add index on (scheme_id, merchant_type, fee_tier, effective_from DESC) for point-in-time lookups.; Seed ZeroPay GENERAL DOMESTIC (merchant_fee_rate=0.0080, van_fee_rate=0.0008) and GENERAL CROSSBORDER (merchant_fee_rate=0.0170, van_fee_rate=0.0017) rows as reference data in a separate seed script V7_2_001__scheme_fee_config_seed.sql.
**Deliverable:** Migration file db/migrations/V7_2_001__scheme_fee_config.sql and seed file V7_2_001__scheme_fee_config_seed.sql that apply cleanly via Flyway/Liquibase.
**Acceptance / logic checks:**
- Migration applies on a clean schema with no errors and creates the scheme_fee_config table with all columns and constraints.
- INSERT of duplicate (scheme_id, merchant_type, fee_tier, effective_from) is rejected by the unique constraint.
- Seed rows for ZeroPay GENERAL DOMESTIC and GENERAL CROSSBORDER exist after running the seed script.
- Rollback migration removes the table cleanly with no orphan indexes.
- merchant_fee_rate and van_fee_rate accept values in range 0.0000-1.0000 and reject values outside that range (add CHECK constraints).

### 7.2-T02 — Add transaction_fee_snapshot table migration to store per-transaction fee inputs at commit  _(30 min)_
**Context:** WBS 7.2 requires GME 70% of net merchant fee to be computed and stored per transaction at commit time. The canonical fields are: gross_merchant_fee_krw (payout_amount_krw x merchant_fee_rate, KRW BIGINT, 0 decimals per KRW scale), van_fee_krw (payout_amount_krw x van_fee_rate, BIGINT), net_merchant_fee_krw (gross - van, BIGINT), gme_fee_share_krw (net x gme_fee_share_pct, BIGINT rounded towards zero), zeropay_fee_share_krw (net - gme_fee_share_krw, BIGINT). These are locked at commit and never recomputed. The existing transaction table FK is the anchor.
**Steps:** Create migration db/migrations/V7_2_002__transaction_fee_snapshot.sql.; Define table transaction_fee_snapshot: id BIGINT PK, txn_id BIGINT UNIQUE FK -> transaction NOT NULL, merchant_fee_rate DECIMAL(6,4) NOT NULL, van_fee_rate DECIMAL(6,4) NOT NULL, gme_fee_share_pct DECIMAL(6,4) NOT NULL, gross_merchant_fee_krw BIGINT NOT NULL, van_fee_krw BIGINT NOT NULL, net_merchant_fee_krw BIGINT NOT NULL, gme_fee_share_krw BIGINT NOT NULL, zeropay_fee_share_krw BIGINT NOT NULL, fee_config_id BIGINT FK -> scheme_fee_config NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now().; Add CHECK constraint: gross_merchant_fee_krw >= van_fee_krw AND net_merchant_fee_krw >= 0 AND gme_fee_share_krw + zeropay_fee_share_krw = net_merchant_fee_krw.; Add index on txn_id (already unique but explicit for query plans).; Comment each column with its formula in the migration SQL.
**Deliverable:** Migration file db/migrations/V7_2_002__transaction_fee_snapshot.sql that applies cleanly and creates the locked snapshot table.
**Acceptance / logic checks:**
- Migration applies on top of V7_2_001 with no errors; UNIQUE(txn_id) prevents duplicate snapshots for the same transaction.
- CHECK constraint rejects rows where gme_fee_share_krw + zeropay_fee_share_krw != net_merchant_fee_krw (e.g. insert gme=70, zeropay=29, net=100 is rejected).
- CHECK constraint rejects rows where van_fee_krw > gross_merchant_fee_krw.
- All BIGINT fee columns store KRW values with 0 decimal places (no DECIMAL type used for KRW amounts).
- fee_config_id FK references a valid scheme_fee_config row; orphan inserts are rejected.
**Depends on:** 7.2-T01

### 7.2-T03 — Add revenue_record columns for scheme fee share fields  _(25 min)_
**Context:** WBS 7.2 must also surface the scheme fee share in the revenue_record table (DAT-03 §8.2). The existing revenue_record has: txn_id, partner_id, scheme_id, revenue_date, fx_margin_usd DECIMAL(20,4), service_charge_amount DECIMAL(20,4), service_charge_ccy CHAR(3), fee_share_pct DECIMAL(6,4) (the 70%), estimated_fee_share_usd DECIMAL(20,4). The spec notes estimated_fee_share_usd is 'estimated at commit; confirmed from scheme settlement'. KRW amounts cannot be stored as USD precisely - add estimated_fee_share_krw BIGINT to hold the KRW-denominated estimate and fee_share_confirmed BOOLEAN DEFAULT FALSE to track whether reconciliation has confirmed the actual figure.
**Steps:** Create migration db/migrations/V7_2_003__revenue_record_fee_share_cols.sql.; Add column estimated_fee_share_krw BIGINT NULL to revenue_record (NULL until computed at commit; non-null after T05 logic is wired).; Add column fee_share_confirmed BOOLEAN NOT NULL DEFAULT FALSE to revenue_record.; Add column fee_snapshot_id BIGINT NULL FK -> transaction_fee_snapshot (links revenue line to the detailed snapshot; NULL until T05 wired).; Add index on (scheme_id, revenue_date) for monthly aggregation queries.
**Deliverable:** Migration file db/migrations/V7_2_003__revenue_record_fee_share_cols.sql adding the three new columns to revenue_record.
**Acceptance / logic checks:**
- Migration applies cleanly on top of V7_2_002; existing revenue_record rows gain the new nullable/defaulted columns with no data loss.
- estimated_fee_share_krw BIGINT column accepts KRW integer values (e.g. 560) and rejects fractional input.
- fee_share_confirmed defaults to FALSE for all pre-existing rows after migration.
- fee_snapshot_id FK insert of a non-existent snapshot id is rejected by FK constraint.
- Index on (scheme_id, revenue_date) is visible in EXPLAIN for a query filtering on both columns.
**Depends on:** 7.2-T02

### 7.2-T04 — Implement FeeConfigRepository: point-in-time lookup of scheme_fee_config row  _(35 min)_
**Context:** WBS 7.2 core logic needs to resolve the merchant fee rate and VAN fee rate for a transaction at its committed_at date. scheme_fee_config has: scheme_id, merchant_type, fee_tier, merchant_fee_rate DECIMAL(6,4), van_fee_rate DECIMAL(6,4), effective_from DATE, effective_to DATE NULL. Lookup rule: find the row where scheme_id matches AND merchant_type matches AND fee_tier matches AND effective_from <= :as_of AND (effective_to IS NULL OR effective_to >= :as_of) ORDER BY effective_from DESC LIMIT 1. fee_tier is derived from the transaction's partner type: LOCAL partner -> 'DOMESTIC', OVERSEAS partner -> 'CROSSBORDER'. merchant_type comes from merchant.merchant_type joined via the transaction's merchant_id.
**Steps:** Create interface FeeConfigRepository in package com.gme.gmepay.fees with method: Optional<SchemeFeeConfig> findEffectiveConfig(long schemeId, String merchantType, String feeTier, LocalDate asOf).; Implement FeeConfigRepositoryJpa using Spring Data JPA or a named @Query with the point-in-time SQL defined in context.; Map result to SchemeFeeConfig value object: schemeId, merchantType, feeTier, merchantFeeRate (BigDecimal), vanFeeRate (BigDecimal), feeConfigId (Long).; Throw FeeConfigNotFoundException (unchecked) if no row matches; include schemeId, merchantType, feeTier, asOf in the message.; Add Javadoc on the interface method explaining the effective_from/effective_to range semantics.
**Deliverable:** FeeConfigRepository interface and FeeConfigRepositoryJpa implementation in com.gme.gmepay.fees package.
**Acceptance / logic checks:**
- findEffectiveConfig returns the row with the latest effective_from <= asOf when two overlapping rows exist (e.g. row A effective_from=2025-01-01, row B effective_from=2026-01-01; asOf=2026-06-01 returns B).
- findEffectiveConfig returns Optional.empty() (which triggers FeeConfigNotFoundException) when no row matches the given schemeId+merchantType+feeTier combination.
- findEffectiveConfig with asOf = effective_from date (boundary) returns that row (inclusive lower bound).
- If effective_to is set and asOf > effective_to, the row is NOT returned.
- FeeConfigNotFoundException message contains all four lookup parameters for debuggability.
**Depends on:** 7.2-T01

### 7.2-T05 — Implement SchemeFeeSplitCalculator: compute gross/net merchant fee and 70/30 split  _(40 min)_
**Context:** WBS 7.2 core logic. Formula (all values in KRW BIGINT, 0 decimals): gross_merchant_fee_krw = floor(payout_amount_krw x merchant_fee_rate); van_fee_krw = floor(payout_amount_krw x van_fee_rate); net_merchant_fee_krw = gross_merchant_fee_krw - van_fee_krw; gme_fee_share_krw = floor(net_merchant_fee_krw x gme_fee_share_pct); zeropay_fee_share_krw = net_merchant_fee_krw - gme_fee_share_krw. Use floor (truncate towards zero) for all intermediate multiplications - this preserves whole KRW and ensures gme + zeropay always equals net exactly. gme_fee_share_pct is read from qr_scheme.gme_fee_share_pct (0.70 for ZeroPay). Inputs come from SchemeFeeConfig (T04) plus the qr_scheme.gme_fee_share_pct. All arithmetic uses java.math.BigDecimal with ROUND_DOWN.
**Steps:** Create class SchemeFeeSplitCalculator in com.gme.gmepay.fees with a single public method: FeeShareResult calculate(long payoutAmountKrw, BigDecimal merchantFeeRate, BigDecimal vanFeeRate, BigDecimal gmeFeeSharePct).; FeeShareResult is a record/value object with fields: grossMerchantFeeKrw (long), vanFeeKrw (long), netMerchantFeeKrw (long), gmeFeeShareKrw (long), zeropayFeeShareKrw (long).; Implement using BigDecimal.multiply with setScale(0, RoundingMode.FLOOR) at each step.; Guard: throw IllegalArgumentException if payoutAmountKrw < 0, any rate < 0, gmeFeeSharePct not in (0,1], or vanFeeRate >= merchantFeeRate (VAN fee must not exceed gross fee).; Assert invariant: gmeFeeShareKrw + zeropayFeeShareKrw == netMerchantFeeKrw; throw ArithmeticException if violated (should never happen given floor logic, but belt-and-suspenders).
**Deliverable:** SchemeFeeSplitCalculator class and FeeShareResult record in com.gme.gmepay.fees package.
**Acceptance / logic checks:**
- calculate(15000, 0.0080, 0.0008, 0.70): gross=120, van=12, net=108, gme=75 (floor(108x0.70)=75), zeropay=33. Verify gme+zeropay=108.
- calculate(100000, 0.0200, 0.0020, 0.70): gross=2000, van=200, net=1800, gme=1260 (floor(1800x0.70)=1260), zeropay=540.
- calculate(1, 0.0080, 0.0008, 0.70): gross=0 (floor), van=0, net=0, gme=0, zeropay=0 - no negative amounts.
- calculate(-1, ...) throws IllegalArgumentException.
- Invariant gme+zeropay==net holds for all valid inputs including odd/prime payout amounts.
**Depends on:** 7.2-T04

### 7.2-T06 — Implement FeeSnapshotService: persist transaction_fee_snapshot at transaction commit  _(45 min)_
**Context:** WBS 7.2 wiring: at transaction commit time, FeeSnapshotService must (1) look up SchemeFeeConfig for the transaction via FeeConfigRepository (T04) using the transaction's schemeId, the merchant's merchantType, the fee_tier derived from partner type (LOCAL=DOMESTIC, OVERSEAS=CROSSBORDER), and the transaction's committed_at date; (2) invoke SchemeFeeSplitCalculator.calculate (T05) with payout_amount_krw=transaction.targetPayout (KRW), merchantFeeRate, vanFeeRate, and qrScheme.gmeFeeSharePct; (3) INSERT into transaction_fee_snapshot; (4) UPDATE revenue_record.estimated_fee_share_krw and fee_snapshot_id for the same txn_id. All four steps must execute within the same DB transaction (atomic commit). If FeeConfigNotFoundException is thrown, re-throw as FeeComputationException (checked) with the txn_id in the message - do not silently skip.
**Steps:** Create FeeSnapshotService in com.gme.gmepay.fees with method: void captureFeeSplit(long txnId) throws FeeComputationException.; Load Transaction, Merchant (for merchantType), Partner (for type LOCAL/OVERSEAS), and QrScheme (for gmeFeeSharePct) from their respective repositories.; Derive feeTier: partner.type == LOCAL -> 'DOMESTIC'; OVERSEAS -> 'CROSSBORDER'.; Call FeeConfigRepository.findEffectiveConfig; on empty result throw FeeComputationException.; Call SchemeFeeSplitCalculator.calculate; persist TransactionFeeSnapshot entity; update RevenueRecord.estimatedFeeShareKrw and feeSnapshotId.; Annotate the method @Transactional(rollbackFor = FeeComputationException.class) to ensure atomicity.
**Deliverable:** FeeSnapshotService class in com.gme.gmepay.fees that atomically captures the fee split and updates the revenue record.
**Acceptance / logic checks:**
- After captureFeeSplit(txnId) succeeds, exactly one transaction_fee_snapshot row exists for txnId and revenue_record.estimated_fee_share_krw is non-null.
- If FeeConfigNotFoundException is thrown mid-method, both transaction_fee_snapshot and revenue_record.estimated_fee_share_krw remain unchanged (full rollback).
- Calling captureFeeSplit twice for the same txnId throws a DataIntegrityViolationException due to the UNIQUE(txn_id) constraint on transaction_fee_snapshot.
- For a LOCAL partner transaction (KRW domestic), feeTier is resolved as DOMESTIC and the DOMESTIC fee config row is used.
- For an OVERSEAS partner transaction, feeTier is resolved as CROSSBORDER and the CROSSBORDER fee config row is used.
**Depends on:** 7.2-T05, 7.2-T03

### 7.2-T07 — Wire FeeSnapshotService into transaction commit flow  _(45 min)_
**Context:** WBS 7.2 integration: the transaction commit flow (TransactionService.commitTransaction) runs after scheme approval is received and before the TRANSACTION_COMMITTED event is recorded in transaction_event. FeeSnapshotService.captureFeeSplit must be called within the commit @Transactional boundary so the fee snapshot is part of the same DB commit. If captureFeeSplit throws FeeComputationException the whole commit must roll back and the caller receives a 422 error code FEE_CONFIG_MISSING. The existing commit flow is in TransactionService (or equivalent service handling POST /v1/payments/{id}/commit). Do not alter any rate-engine pool fields - those are already locked before this step.
**Steps:** Locate the TransactionService.commitTransaction method (or equivalent).; Inject FeeSnapshotService into TransactionService.; After scheme approval confirmation and before writing the TRANSACTION_COMMITTED event, call feeSnapshotService.captureFeeSplit(txn.getId()).; Catch FeeComputationException; translate to a 422 PaymentApiException with error code FEE_CONFIG_MISSING and message including txnId.; Verify @Transactional annotation on commitTransaction covers the captureFeeSplit call (same transaction boundary).; Add the new event step to TRANSACTION_COMMITTED event detail JSONB: include gme_fee_share_krw from the snapshot.
**Deliverable:** Updated TransactionService with FeeSnapshotService wired into the commit path; FEE_CONFIG_MISSING error returned on fee config absence.
**Acceptance / logic checks:**
- A complete commit for a GENERAL DOMESTIC transaction results in a transaction_fee_snapshot row visible immediately after the HTTP 200 response.
- If the merchant has no fee config row (novel merchant_type), commit returns HTTP 422 with error_code FEE_CONFIG_MISSING and no transaction_fee_snapshot row is created.
- The TRANSACTION_COMMITTED transaction_event detail JSONB contains a gme_fee_share_krw field matching the snapshot value.
- Removing the @Transactional annotation (test-only) and forcing captureFeeSplit to fail after the scheme call leaves no orphan snapshot row.
- A cross-border (OVERSEAS) transaction uses the CROSSBORDER fee tier and fee rates differ from the DOMESTIC rates.
**Depends on:** 7.2-T06

### 7.2-T08 — Implement edge-case handling: zero payout, same-currency short-circuit, and refund transactions  _(45 min)_
**Context:** WBS 7.2 edge cases. (1) Zero/tiny payout: payout_amount_krw=0 should produce all-zero fee fields - valid, not an error. (2) Same-currency short-circuit (is_same_ccy_shortcircuit=true, always LOCAL/DOMESTIC KRW): scheme fee share still applies per spec (BRD-01 §8.1: fee share applies to ALL transactions domestic + cross-border). Fee is computed the same way - use DOMESTIC rate. (3) Refund transactions: a refund reverses the payout, so gross/net fees should be negative (or zero if the original transaction fee snapshot handles it). Rule: for refund transactions, create a transaction_fee_snapshot with all fee fields negated from the original txn's snapshot. (4) Cancelled transactions (same-day cancel): no fee snapshot should be created - cancel means scheme was never charged. Guard in FeeSnapshotService: check transaction.status; only proceed for COMPLETED/APPROVED status.
**Steps:** Update FeeSnapshotService.captureFeeSplit to guard on transaction.status: throw FeeComputationException with message STATUS_INELIGIBLE if status is not COMPLETED or APPROVED.; For refund transactions (transaction.isRefund=true or a Refund entity), implement a separate method captureRefundFeeSplit(long refundTxnId, long originalTxnId) that reads the original snapshot and writes a mirrored row with all fee amounts negated.; For payout_amount_krw=0 (valid edge case), allow all-zero fee snapshot to be written without error.; For same-currency short-circuit transactions (is_same_ccy_shortcircuit=true), confirm feeTier=DOMESTIC and fee rates from DOMESTIC config are used (no bypass).; Add a guard: if transaction.schemeFeeShareApplicable=false (future config flag, default true), skip captureFeeSplit silently and leave revenue_record.estimated_fee_share_krw null.
**Deliverable:** Updated FeeSnapshotService with edge-case guards for cancelled transactions, refunds, zero payout, and same-currency short-circuit.
**Acceptance / logic checks:**
- A cancelled transaction (status=CANCELLED) results in no transaction_fee_snapshot row and no error logged.
- A domestic same-currency short-circuit transaction (is_same_ccy_shortcircuit=true) still creates a fee snapshot using DOMESTIC rates.
- payout_amount_krw=0 produces a fee snapshot with all BIGINT fields = 0 and no exception.
- A refund snapshot has gme_fee_share_krw = -(original gme_fee_share_krw) and zeropay_fee_share_krw = -(original zeropay_fee_share_krw); gme+zeropay still equals net (both negative).
- captureFeeSplit on a COMPLETED transaction with no matching fee config still throws FeeComputationException (fee config missing is always fatal for completed transactions).
**Depends on:** 7.2-T07

### 7.2-T09 — Add FeeConfigRepository unit tests  _(40 min)_
**Context:** WBS 7.2 unit tests for the repository layer (T04). Test with H2 or Testcontainers PostgreSQL. Seed three rows for the same scheme/merchant_type/fee_tier with different effective_from dates to verify point-in-time logic. ZeroPay scheme_id=1, merchant_type=GENERAL, fee_tier=DOMESTIC. Row A: effective_from=2025-01-01, effective_to=2025-12-31. Row B: effective_from=2026-01-01, effective_to=null. Row C: effective_from=2027-01-01, effective_to=null (future). Test asOf dates: 2025-06-01 (returns A), 2026-06-05 (returns B), 2026-12-31 (returns B), 2024-12-31 (returns empty), 2025-12-31 (boundary - returns A), 2026-01-01 (boundary - returns B).
**Steps:** Create FeeConfigRepositoryTest in src/test/java/com/gme/gmepay/fees/.; Use @DataJpaTest with Testcontainers (PostgreSQL) or H2 in PostgreSQL-compatible mode.; Seed rows A, B, C as described in context before each test using @BeforeEach.; Write 6 parameterised tests covering each asOf date in context.; Add a test for a different merchant_type (FRANCHISE) that has no rows: expect Optional.empty() and verify FeeConfigNotFoundException is thrown by the service wrapper.
**Deliverable:** FeeConfigRepositoryTest class with 7+ passing tests covering point-in-time lookups.
**Acceptance / logic checks:**
- asOf=2025-06-01 returns row A (merchant_fee_rate=row A value, not row B).
- asOf=2026-06-05 returns row B (most recent effective row).
- asOf=2024-12-31 (before any row) returns Optional.empty().
- asOf=2025-12-31 (last day of row A effective_to) returns row A (inclusive upper bound).
- asOf=2026-01-01 (first day of row B) returns row B (inclusive lower bound).
- FRANCHISE merchant_type with no rows returns Optional.empty().
- All 7 tests pass with zero failures on CI.
**Depends on:** 7.2-T04

### 7.2-T10 — Add SchemeFeeSplitCalculator unit tests with exact numeric vectors  _(45 min)_
**Context:** WBS 7.2 unit tests for the calculation engine (T05). All arithmetic uses floor (ROUND_DOWN). Test vectors must be exact. Vector set: (A) payout=15000, merchant_fee_rate=0.0080, van_fee_rate=0.0008, gme_fee_share_pct=0.70 -> gross=120, van=12, net=108, gme=75, zeropay=33. (B) payout=100000, merchant_fee_rate=0.0200, van_fee_rate=0.0020, gme_fee_share_pct=0.70 -> gross=2000, van=200, net=1800, gme=1260, zeropay=540. (C) payout=1, merchant_fee_rate=0.0080, van_fee_rate=0.0008, gme_fee_share_pct=0.70 -> gross=0, van=0, net=0, gme=0, zeropay=0. (D) payout=999, merchant_fee_rate=0.0080, van_fee_rate=0.0008, gme_fee_share_pct=0.70 -> gross=7 (floor(999*0.008)=7), van=0 (floor(999*0.0008)=0), net=7, gme=4 (floor(7*0.70)=4), zeropay=3. (E) payout=0, any rates -> all zeros. Guard tests: negative payout, vanFeeRate>=merchantFeeRate, gmeFeeSharePct=0, gmeFeeSharePct=1.01.
**Steps:** Create SchemeFeeSplitCalculatorTest in src/test/java/com/gme/gmepay/fees/.; Implement @ParameterizedTest for vectors A-E verifying each output field exactly.; Add guard tests: negative payout throws IllegalArgumentException; vanFeeRate >= merchantFeeRate throws IllegalArgumentException; gmeFeeSharePct=1.01 throws IllegalArgumentException.; Verify invariant gme+zeropay==net holds for all valid vectors programmatically within each test.; Add a stress test: loop 10,000 random payout amounts 1-1000000 KRW with fixed rates and assert invariant never breaks.
**Deliverable:** SchemeFeeSplitCalculatorTest class with parameterised vectors A-E, guard tests, and invariant stress test - all passing.
**Acceptance / logic checks:**
- Vector A produces gross=120, van=12, net=108, gme=75, zeropay=33 exactly.
- Vector D produces gross=7, van=0, net=7, gme=4, zeropay=3 exactly (tests floor behaviour for sub-unit VAN fee).
- Vector E (payout=0) produces all zeros with no exception.
- negative payout test throws IllegalArgumentException with message containing 'payoutAmountKrw'.
- 10,000-iteration invariant stress test completes with zero assertion failures.
**Depends on:** 7.2-T05

### 7.2-T11 — Add FeeSnapshotService unit tests: success path, rollback on missing config, and refund mirroring  _(45 min)_
**Context:** WBS 7.2 unit tests for FeeSnapshotService (T06). Use Mockito for repository mocks. Tests cover: (1) Happy-path LOCAL domestic: payout=15000 KRW, GENERAL merchant, LOCAL partner -> snapshot written with gme=75, zeropay=33, revenue_record.estimated_fee_share_krw=75. (2) Happy-path OVERSEAS cross-border: payout=50000 KRW, GENERAL merchant, OVERSEAS partner -> snapshot written using CROSSBORDER rates (merchant_fee_rate=0.0170, van_fee_rate=0.0017) -> gross=850, van=85, net=765, gme=535 (floor(765*0.70)), zeropay=230. (3) FeeConfigNotFoundException propagates as FeeComputationException. (4) Refund mirroring: original snapshot gme=75, zeropay=33; refund snapshot gme=-75, zeropay=-33; net=-108. (5) Cancelled transaction: no snapshot written, method returns cleanly.
**Steps:** Create FeeSnapshotServiceTest in src/test/java/com/gme/gmepay/fees/.; Mock FeeConfigRepository, SchemeFeeSplitCalculator, TransactionFeeSnapshotRepository, RevenueRecordRepository, TransactionRepository.; Write test (1): LOCAL domestic happy path - verify snapshot persisted with exact values and revenue_record updated.; Write test (2): OVERSEAS cross-border - verify CROSSBORDER feeTier used and snapshot values correct.; Write test (3): FeeConfigRepository returns empty -> verify FeeComputationException thrown and no persist calls made.; Write test (4): captureRefundFeeSplit called with refundTxnId and originalTxnId -> verify mirrored negative values.; Write test (5): cancelled transaction (status=CANCELLED) -> captureFeeSplit returns without persisting.
**Deliverable:** FeeSnapshotServiceTest with 5+ passing tests covering all documented scenarios.
**Acceptance / logic checks:**
- Test (1) asserts revenue_record.setEstimatedFeeShareKrw(75L) is called exactly once.
- Test (2) asserts FeeConfigRepository was called with feeTier='CROSSBORDER' and produces gme=535.
- Test (3) asserts no call to TransactionFeeSnapshotRepository.save() when config is missing.
- Test (4) asserts refund snapshot gme_fee_share_krw = -75 and zeropay_fee_share_krw = -33 and net_merchant_fee_krw = -108.
- Test (5) asserts zero interactions with TransactionFeeSnapshotRepository when status=CANCELLED.
**Depends on:** 7.2-T06, 7.2-T08

### 7.2-T12 — Add integration test: end-to-end commit with fee snapshot verification  _(55 min)_
**Context:** WBS 7.2 integration test covering the full commit path from POST /v1/payments/{id}/commit through to transaction_fee_snapshot and revenue_record persistence. Use Testcontainers PostgreSQL. Seed: ZeroPay scheme with gme_fee_share_pct=0.70; scheme_fee_config rows for GENERAL DOMESTIC (merchant_fee_rate=0.0080, van_fee_rate=0.0008) and GENERAL CROSSBORDER (merchant_fee_rate=0.0170, van_fee_rate=0.0017). Merchant: merchant_type=GENERAL, fee_tier=DOMESTIC. Partners: GME_REMIT (LOCAL) and SENDMN (OVERSEAS). Test scenario A: LOCAL GME_REMIT payment payout=15000 KRW -> commit -> assert transaction_fee_snapshot.gme_fee_share_krw=75. Test scenario B: OVERSEAS SENDMN payment payout=50000 KRW -> commit -> assert snapshot uses CROSSBORDER rates.
**Steps:** Create FeeSnapshotIntegrationTest in src/test/java/com/gme/gmepay/fees/integration/.; Use @SpringBootTest + Testcontainers PostgreSQL; apply all Flyway migrations including V7_2_*.; Seed schemes, fee configs, merchants, partners, and transactions in @BeforeEach.; Scenario A: call TransactionService.commitTransaction for LOCAL txn; query transaction_fee_snapshot and assert gme_fee_share_krw=75, zeropay_fee_share_krw=33.; Scenario B: call commitTransaction for OVERSEAS txn payout=50000; assert fee_tier=CROSSBORDER was used (verify via snapshot.merchant_fee_rate=0.0170).; Add scenario C: commit with no matching fee config -> assert FeeComputationException thrown and transaction remains uncommitted (status unchanged).
**Deliverable:** FeeSnapshotIntegrationTest with 3 scenarios, all passing against a real PostgreSQL instance via Testcontainers.
**Acceptance / logic checks:**
- Scenario A: transaction_fee_snapshot row exists with gme_fee_share_krw=75, van_fee_krw=12, net_merchant_fee_krw=108.
- Scenario B: snapshot.merchant_fee_rate=0.0170 confirms CROSSBORDER config was used (not DOMESTIC).
- Scenario C: transaction.status is unchanged (not COMMITTED) after FeeComputationException; no orphan snapshot row.
- All three scenarios run within 60 seconds total (Testcontainers startup excluded).
- revenue_record.estimated_fee_share_krw is populated and matches gme_fee_share_krw in the snapshot after scenarios A and B.
**Depends on:** 7.2-T07, 7.2-T08

### 7.2-T13 — Implement SchemeFeeSplitQueryService: aggregate fee share by scheme and period  _(40 min)_
**Context:** WBS 7.2 reporting layer. The revenue report (PRD-07 §11.3.2) shows 'GME Scheme Share (KRW)' = 70% of net merchant fee. This requires aggregating transaction_fee_snapshot.gme_fee_share_krw grouped by scheme_id and period. Provide a query service method: FeeShareSummary aggregateByScheme(long schemeId, LocalDate from, LocalDate to). FeeShareSummary contains: schemeId, fromDate, toDate, transactionCount (long), totalGrossMerchantFeeKrw (long), totalVanFeeKrw (long), totalNetMerchantFeeKrw (long), totalGmeFeeShareKrw (long), totalZeropayFeeShareKrw (long). Use revenue_record.revenue_date for the period filter (join transaction_fee_snapshot via txn_id). Exclude refund snapshot rows from the aggregate (gme_fee_share_krw < 0) - they reduce the total automatically when summed, so actually include them (sum handles negatives). Add a separate method for partner-level breakdowns.
**Steps:** Create SchemeFeeSplitQueryService in com.gme.gmepay.fees.reporting.; Implement aggregateByScheme using a JPQL or native SQL GROUP BY query on transaction_fee_snapshot JOIN revenue_record on txn_id, filtered by scheme_id and revenue_date BETWEEN from AND to.; Implement aggregateByPartner(long partnerId, long schemeId, LocalDate from, LocalDate to) -> FeeShareSummary (same shape, adds partnerId field).; Add a helper isReconciled(long schemeId, LocalDate month): returns true if all revenue_record.fee_share_confirmed=TRUE for the period.; Return FeeShareSummary with all-zero values (not null) if no rows found for the period.
**Deliverable:** SchemeFeeSplitQueryService class with aggregateByScheme, aggregateByPartner, and isReconciled methods.
**Acceptance / logic checks:**
- aggregateByScheme for ZeroPay, 2026-06-01 to 2026-06-30 sums gme_fee_share_krw across all transaction_fee_snapshot rows linked to that scheme and period.
- Refund snapshots (negative gme_fee_share_krw) reduce the aggregate total correctly (e.g. 100 + (-30) = 70).
- Period with no transactions returns FeeShareSummary with all fields = 0 (not null).
- aggregateByPartner with partnerId filter only includes snapshots for transactions belonging to that partner.
- isReconciled returns false if any revenue_record.fee_share_confirmed=FALSE in the period, true if all are TRUE.
**Depends on:** 7.2-T03

### 7.2-T14 — Expose internal fee share summary REST endpoint for Ops Admin portal  _(40 min)_
**Context:** WBS 7.2 reporting wiring. The Ops Admin portal (PRD-07 §11.3.2) needs a REST endpoint to retrieve scheme fee share aggregates. Add GET /internal/v1/fee-share/summary?scheme_id=&from=&to= (YYYY-MM-DD dates) returning FeeShareSummary JSON. This is an internal endpoint (not partner-facing) authenticated via Ops session/JWT with role FINANCE or SUPER_ADMIN. Response fields: scheme_id, from_date, to_date, transaction_count, total_gross_merchant_fee_krw, total_van_fee_krw, total_net_merchant_fee_krw, total_gme_fee_share_krw, total_zeropay_fee_share_krw, is_reconciled. Do not expose individual transaction-level fee data; only aggregates. Add a second endpoint GET /internal/v1/fee-share/summary/by-partner?partner_id=&scheme_id=&from=&to= for partner-level drill-down.
**Steps:** Create FeeShareController in com.gme.gmepay.admin.controllers package.; Implement GET /internal/v1/fee-share/summary calling SchemeFeeSplitQueryService.aggregateByScheme; validate that from <= to and date range <= 366 days.; Implement GET /internal/v1/fee-share/summary/by-partner calling aggregateByPartner.; Secure both endpoints with @PreAuthorize requiring FINANCE or SUPER_ADMIN role.; Return HTTP 400 with error code INVALID_DATE_RANGE if from > to; HTTP 400 DATE_RANGE_TOO_LARGE if range > 366 days.; Map FeeShareSummary to a response DTO with snake_case JSON field names.
**Deliverable:** FeeShareController with two GET endpoints, secured and validated, returning aggregate fee share data.
**Acceptance / logic checks:**
- GET /internal/v1/fee-share/summary?scheme_id=1&from=2026-06-01&to=2026-06-30 returns HTTP 200 with correct totals for seeded data.
- A request with PARTNER role returns HTTP 403 Forbidden.
- from > to returns HTTP 400 with error_code INVALID_DATE_RANGE.
- date range of 400 days returns HTTP 400 with error_code DATE_RANGE_TOO_LARGE.
- by-partner endpoint returns only aggregates for the specified partner_id; a different partner_id returns zeros for the same period.
**Depends on:** 7.2-T13

### 7.2-T15 — Add monthly fee-share reconciliation flag update: mark revenue_record.fee_share_confirmed  _(45 min)_
**Context:** WBS 7.2 reconciliation. Per RATE-04 §13.4: 'Scheme fee share: 70% x (ZeroPay reported net merchant fee total for the period). Reconcile against monthly ZeroPay fee share statement.' When ZeroPay's monthly fee share statement is loaded (manually by Finance in Phase 1), the system must compare the statement total against sum(gme_fee_share_krw) for the period and mark revenue_record.fee_share_confirmed=TRUE on all matched rows. Add a service method: ReconciliationResult reconcileSchemeShare(long schemeId, LocalDate month, long schemeReportedNetFeeKrw). If abs(schemeReportedNetFeeKrw - sum(gme_fee_share_krw)) > tolerance (tolerance = 100 KRW, i.e. 1 KRW rounding per transaction is acceptable for large batches), return DISCREPANCY; otherwise update all revenue_record.fee_share_confirmed=TRUE for the month and return MATCHED.
**Steps:** Create FeeShareReconciliationService in com.gme.gmepay.fees.reconciliation.; Implement reconcileSchemeShare: call aggregateByScheme for the month, compare totalGmeFeeShareKrw against schemeReportedNetFeeKrw within tolerance.; On MATCHED: batch-UPDATE revenue_record SET fee_share_confirmed=TRUE WHERE scheme_id=? AND revenue_date BETWEEN first-of-month AND last-of-month AND fee_share_confirmed=FALSE.; On DISCREPANCY: do NOT update; return ReconciliationResult with delta_krw field showing the difference.; Log the reconciliation action to audit_log: actor=SYSTEM, action=FEE_SHARE_RECONCILIATION, entity_type=REVENUE_RECORD, detail JSON with scheme_id, month, computed_total, reported_total, result.
**Deliverable:** FeeShareReconciliationService with reconcileSchemeShare method; audit log entry on every reconciliation attempt.
**Acceptance / logic checks:**
- reconcileSchemeShare with schemeReportedNetFeeKrw exactly equal to sum(gme_fee_share_krw) returns MATCHED and all revenue_record rows for the month have fee_share_confirmed=TRUE.
- reconcileSchemeShare with delta > 100 KRW returns DISCREPANCY and no revenue_record rows are updated.
- reconcileSchemeShare with delta <= 100 KRW (tolerance) returns MATCHED.
- An audit_log row is written for both MATCHED and DISCREPANCY outcomes.
- Calling reconcileSchemeShare twice for the same MATCHED month is idempotent (already-confirmed rows are not double-updated; return MATCHED).
**Depends on:** 7.2-T13, 7.2-T03

### 7.2-T16 — Add unit tests for FeeShareReconciliationService  _(35 min)_
**Context:** WBS 7.2 unit tests for reconciliation (T15). Mock SchemeFeeSplitQueryService and RevenueRecordRepository. Test vectors: (A) reported=108, computed=108 -> MATCHED, confirm update called. (B) reported=200, computed=108 -> DISCREPANCY (delta=92 <= 100, so MATCHED by tolerance). Wait - tolerance is 100 KRW: abs(200-108)=92 < 100 -> MATCHED. (C) reported=209, computed=108 -> delta=101 > 100 -> DISCREPANCY, no update. (D) No transactions for month (totalGmeFeeShareKrw=0), reported=0 -> MATCHED. (E) No transactions for month, reported=500 -> DISCREPANCY. Audit log: verify audit_log entry written for both MATCHED and DISCREPANCY cases.
**Steps:** Create FeeShareReconciliationServiceTest in src/test/java/com/gme/gmepay/fees/reconciliation/.; Mock SchemeFeeSplitQueryService to return FeeShareSummary with totalGmeFeeShareKrw=108.; Test A: reported=108 -> MATCHED; verify batch-update called.; Test B: reported=200 -> delta=92 -> MATCHED (within tolerance); verify update called.; Test C: reported=209 -> delta=101 -> DISCREPANCY; verify update NOT called.; Test D: totalGmeFeeShareKrw=0, reported=0 -> MATCHED.; Test E: totalGmeFeeShareKrw=0, reported=500 -> DISCREPANCY.; Verify audit_log.save() called for all 5 scenarios with correct result field.
**Deliverable:** FeeShareReconciliationServiceTest with 5 passing tests covering tolerance boundary and audit log verification.
**Acceptance / logic checks:**
- Test A: MATCHED returned and revenueRecordRepository.bulkConfirm called once.
- Test B: reported=200, computed=108, delta=92 < 100 returns MATCHED (tolerance boundary inclusive).
- Test C: reported=209, computed=108, delta=101 > 100 returns DISCREPANCY and bulkConfirm NOT called.
- Test D and E: zero-transaction scenarios return correct result without NPE.
- All 5 tests: auditLogRepository.save() called exactly once per reconciliation attempt regardless of result.
**Depends on:** 7.2-T15

### 7.2-T17 — Add scheme_fee_config admin API: view and upsert fee rates via Ops portal  _(45 min)_
**Context:** WBS 7.2 operational support. Ops must be able to view and update the merchant fee rate table and VAN fee rate table per scheme without code deployment (BRD-01: config-not-code). Add internal endpoints: GET /internal/v1/schemes/{schemeId}/fee-config returns all scheme_fee_config rows for the scheme. POST /internal/v1/schemes/{schemeId}/fee-config upserts a row (or inserts with new effective_from date to create a new rate period). Secured with SUPER_ADMIN role only. All changes must be audit-logged: actor, timestamp, previous value, new value. gme_fee_share_pct is on qr_scheme not scheme_fee_config - a separate PATCH /internal/v1/schemes/{schemeId} endpoint (if not already implemented) handles that field.
**Steps:** Create FeeConfigAdminController in com.gme.gmepay.admin.controllers.; Implement GET /internal/v1/schemes/{schemeId}/fee-config: return list of FeeConfigResponse DTOs ordered by fee_tier, merchant_type, effective_from DESC.; Implement POST /internal/v1/schemes/{schemeId}/fee-config: accept FeeConfigRequest (merchantType, feeTier, merchantFeeRate, vanFeeRate, effectiveFrom); validate vanFeeRate < merchantFeeRate; insert new row.; On insert: write audit_log entry with action=CREATE, entity_type=SCHEME_FEE_CONFIG, previous_value=null, new_value=JSON of the new row.; Secure both endpoints with @PreAuthorize(SUPER_ADMIN); return HTTP 403 for other roles.; Return HTTP 409 CONFLICT if a row with identical (schemeId, merchantType, feeTier, effectiveFrom) already exists.
**Deliverable:** FeeConfigAdminController with GET and POST endpoints, audit logging, and role enforcement.
**Acceptance / logic checks:**
- GET /internal/v1/schemes/1/fee-config returns all seeded rows with correct merchantFeeRate and vanFeeRate values.
- POST with new merchantType=FRANCHISE, feeTier=DOMESTIC, effectiveFrom=2027-01-01 creates a new row and audit_log entry with action=CREATE.
- POST with vanFeeRate >= merchantFeeRate returns HTTP 400 with error_code INVALID_FEE_RATES.
- POST with duplicate (schemeId, merchantType, feeTier, effectiveFrom) returns HTTP 409.
- A request authenticated with FINANCE role returns HTTP 403 for the POST endpoint.
**Depends on:** 7.2-T04, 7.2-T01

### 7.2-T18 — Add fee share display to Ops Admin settlement batch status view  _(40 min)_
**Context:** WBS 7.2 UI data wiring. PRD-07 §11.2.2 'Batch Status View' requires a column 'GME Fee Share (KRW) = 70% of net merchant fee'. This is an aggregate: sum(transaction_fee_snapshot.gme_fee_share_krw) for all transactions in the settlement batch for the given settlement_date and scheme. The existing batch status endpoint (if implemented) must be extended; if not yet present, add GET /internal/v1/settlement/batch-status?settlement_date=&scheme_id= and include fee_share_krw in the response. The endpoint is read-only. Join transaction_fee_snapshot to transactions via txn_id, filter by settlement_date (use transaction.completed_at::date = settlement_date or transaction.settlement_date if that field exists).
**Steps:** Locate or create the settlement batch status endpoint handler.; Add a query to sum transaction_fee_snapshot.gme_fee_share_krw for all transactions with the given settlement_date and scheme_id.; Add field gme_fee_share_krw (long) to the BatchStatusResponse DTO.; Add field net_settlement_krw (long) = gross_payout_krw - gme_fee_share_krw for DOMESTIC transactions; for INTERNATIONAL (OVERSEAS) net_settlement_krw = gross_payout_krw (fee not deducted from settlement).; Secure with FINANCE or SUPER_ADMIN role.; Return gme_fee_share_krw=0 (not null) if no fee snapshots exist for the date/scheme.
**Deliverable:** Updated settlement batch status endpoint returning gme_fee_share_krw and net_settlement_krw for the given date and scheme.
**Acceptance / logic checks:**
- GET /internal/v1/settlement/batch-status?settlement_date=2026-06-05&scheme_id=1 returns gme_fee_share_krw equal to the sum of all snapshot gme_fee_share_krw values for that date.
- For a date with no transactions, gme_fee_share_krw=0 is returned (not null, not 404).
- net_settlement_krw for a DOMESTIC batch = gross_payout_krw - gme_fee_share_krw.
- net_settlement_krw for a CROSSBORDER/OVERSEAS batch = gross_payout_krw (GME does not deduct from settlement for international; invoices separately).
- PARTNER role returns HTTP 403.
**Depends on:** 7.2-T13

### 7.2-T19 — Document SchemeFeeSplitCalculator and 70/30 formula in code-level Javadoc and package README  _(30 min)_
**Context:** WBS 7.2 docs ticket. A developer joining the team must be able to understand the scheme fee share formula purely from code-level documentation without reading spec_full.txt. Document: (1) The formula chain: gross_merchant_fee_krw = floor(payout_amount_krw x merchant_fee_rate); net_merchant_fee_krw = gross - van; gme_fee_share_krw = floor(net x gme_fee_share_pct); zeropay_fee_share_krw = net - gme; (2) why floor is used (whole KRW, invariant preservation); (3) that gme_fee_share_pct=0.70 for ZeroPay (ZeroPay retains 30%); (4) that VAN fee is deducted before the split (per spec assumption A-05); (5) that service_charge is separate and never enters this computation. Add a package-level README.md in src/main/java/com/gme/gmepay/fees/.
**Steps:** Add class-level Javadoc to SchemeFeeSplitCalculator explaining the formula chain with a worked numeric example (payout=15000, rate=0.80%, van=0.08%, gme=70%: gross=120, van=12, net=108, gme=75, zeropay=33).; Add method-level Javadoc to calculate() listing each parameter, return type, and the RoundingMode.FLOOR rationale.; Add class-level Javadoc to FeeSnapshotService explaining when captureFeeSplit is called (at commit, within @Transactional) and the status guard.; Create src/main/java/com/gme/gmepay/fees/README.md: one-page overview of the fees package, classes, formula, and configuration (pointing to scheme_fee_config table).; Add a NOTE in the README: 'gme_fee_share_pct is a ZeroPay-specific agreement (70/30); other schemes may configure a different value via qr_scheme.gme_fee_share_pct'.
**Deliverable:** Javadoc on SchemeFeeSplitCalculator, FeeSnapshotService, and a fees/README.md file covering the formula, rounding rationale, and configuration pointers.
**Acceptance / logic checks:**
- SchemeFeeSplitCalculator class Javadoc includes the worked numeric example with all 5 output values.
- calculate() method Javadoc names every parameter and states RoundingMode.FLOOR is used.
- fees/README.md exists under src/main/java/com/gme/gmepay/fees/ and contains the formula chain in a readable format.
- README explicitly states that service_charge is outside this computation (no double-counting risk).
- README notes that gme_fee_share_pct is scheme-specific and configurable via qr_scheme.gme_fee_share_pct.
**Depends on:** 7.2-T05, 7.2-T06


## WBS 7.3 — FX margin + service fee capture
### 7.3-T01 — Create revenue_record table migration (Flyway V7_3_1)  _(35 min)_
**Context:** WBS 7.3. DAT-03 §8.2 defines the revenue_record table: tracks one row per committed transaction for FX margin and service fee revenue reporting. Columns: id BIGINT PK, txn_id BIGINT FK->transaction (UNIQUE, NOT NULL), partner_id BIGINT FK->partner (NOT NULL), scheme_id BIGINT FK->qr_scheme (NOT NULL), revenue_date DATE NOT NULL, fx_margin_usd DECIMAL(20,4) NOT NULL (= collection_margin_usd + payout_margin_usd; 0.0000 for same-currency), service_charge_amount DECIMAL(20,4) NOT NULL (flat fee in settle_a_ccy; 0 allowed), service_charge_ccy CHAR(3) NOT NULL, fee_share_pct DECIMAL(6,4) NOT NULL (e.g. 0.7000 for ZeroPay 70%), estimated_fee_share_usd DECIMAL(20,4) (estimated at commit; confirmed from scheme settlement), created_at TIMESTAMPTZ DEFAULT now(), updated_at TIMESTAMPTZ DEFAULT now(). IMPORTANT: use DECIMAL/NUMERIC only — no FLOAT. txn_id must have a UNIQUE constraint to prevent duplicate revenue rows per transaction.
**Steps:** Create db/migrations/V7_3_1__create_revenue_record.sql.; Write CREATE TABLE revenue_record with all columns per the schema above; DECIMAL for all monetary amounts.; Add UNIQUE constraint on txn_id column (one revenue row per transaction).; Add FK constraints: txn_id -> transaction(id), partner_id -> partner(id), scheme_id -> qr_scheme(id).; Add indexes: (partner_id, revenue_date), (scheme_id, revenue_date), (revenue_date) for reporting queries.; Run Flyway migrate in dev; verify table exists with \d revenue_record.
**Deliverable:** db/migrations/V7_3_1__create_revenue_record.sql applied cleanly to local PostgreSQL
**Acceptance / logic checks:**
- Table revenue_record exists after migration; all 14 columns present with correct types (DECIMAL not FLOAT).
- UNIQUE constraint on txn_id — inserting two rows with the same txn_id raises a unique-violation error.
- FKs are enforced: inserting a row with a non-existent txn_id raises a FK-violation error.
- Indexes (partner_id, revenue_date) and (scheme_id, revenue_date) appear in pg_indexes.
- Migration is idempotent via Flyway checksum — re-running does not duplicate the table.

### 7.3-T02 — Define RevenueRecord JPA entity  _(30 min)_
**Context:** WBS 7.3. The revenue_record table (created in 7.3-T01) needs a JPA entity. Fields map directly: id (Long, @GeneratedValue), txnId (Long, unique), partnerId (Long), schemeId (Long), revenueDate (LocalDate), fxMarginUsd (BigDecimal, precision=20, scale=4), serviceChargeAmount (BigDecimal, precision=20, scale=4), serviceChargeCcy (String, length=3), feeSharePct (BigDecimal, precision=6, scale=4), estimatedFeeShareUsd (BigDecimal, precision=20, scale=4, nullable), createdAt (OffsetDateTime), updatedAt (OffsetDateTime). Use BigDecimal for all monetary fields — never Double or Float. fxMarginUsd must be NOT NULL with @Column(nullable=false).
**Steps:** Create domain/model/RevenueRecord.java annotated with @Entity @Table(name="revenue_record").; Map all columns per the schema; annotate monetary fields with @Column(precision=20, scale=4).; Add @Column(unique=true, nullable=false) on txnId.; Annotate createdAt with @CreationTimestamp and updatedAt with @UpdateTimestamp.; Add a static factory method RevenueRecord.of(Transaction txn, BigDecimal fxMarginUsd, BigDecimal serviceChargeAmount, String serviceChargeCcy, BigDecimal feeSharePct, BigDecimal estimatedFeeShareUsd) that sets revenueDate = txn.committedAt.toLocalDate() and populates all fields.
**Deliverable:** domain/model/RevenueRecord.java — JPA entity with static factory method
**Acceptance / logic checks:**
- Class compiles and Hibernate schema-validation passes against the V7_3_1 migration.
- factory method sets fxMarginUsd = provided value, revenueDate = committedAt.toLocalDate() of the passed transaction.
- BigDecimal field precision/scale annotations match the SQL DECIMAL(20,4) definition — mismatches would cause Hibernate validation failure.
- txnId has @Column(unique=true, nullable=false) — entity validation rejects null txnId at persist time.
- No primitive double or float fields present.
**Depends on:** 7.3-T01

### 7.3-T03 — Create RevenueRecordRepository with query methods  _(30 min)_
**Context:** WBS 7.3. The revenue_record table needs a Spring Data JPA repository for (a) save/insert at commit time and (b) aggregate queries for the Finance reporting view (Admin Portal §11.3.2). Required query methods: findByTxnId(Long txnId) for idempotency check; sumFxMarginUsdByPartnerIdAndRevenueDateBetween(Long partnerId, LocalDate start, LocalDate end) using a @Query; sumServiceChargeAmountBySchemeIdAndRevenueDateBetween for the daily close reconciliation. Also provide findAllByRevenueDateBetween(LocalDate start, LocalDate end, Pageable) for CSV export.
**Steps:** Create repository/RevenueRecordRepository.java extending JpaRepository<RevenueRecord, Long>.; Add Optional<RevenueRecord> findByTxnId(Long txnId).; Add @Query("SELECT COALESCE(SUM(r.fxMarginUsd), 0) FROM RevenueRecord r WHERE r.partnerId = :partnerId AND r.revenueDate BETWEEN :start AND :end") BigDecimal sumFxMarginUsdByPartnerAndDateRange(...).; Add equivalent sumServiceChargeAmountBySchemeIdAndDateRange query.; Add Page<RevenueRecord> findAllByRevenueDateBetween(LocalDate start, LocalDate end, Pageable pageable).
**Deliverable:** repository/RevenueRecordRepository.java with 5 query methods
**Acceptance / logic checks:**
- findByTxnId returns Optional.empty() when no matching row; returns the record when one exists.
- sumFxMarginUsd returns COALESCE 0 (BigDecimal) when no rows match the date range — not null.
- Aggregate query groups correctly: partner A rows are not included in partner B sum.
- findAllByRevenueDateBetween respects pageable parameters (page 0 size 10 returns at most 10 rows).
- Spring context loads without JPQL parse errors.
**Depends on:** 7.3-T02

### 7.3-T04 — Define RevenueCapture value object for rate-engine output  _(25 min)_
**Context:** WBS 7.3. The rate engine (5-step sequence per RATE-04) produces collection_margin_usd and payout_margin_usd (both DECIMAL(20,8)) plus service_charge (DECIMAL(20,4) in settle_a_ccy). At commit time these values are locked onto the transaction record. A dedicated value object carries these fields through the commit pipeline so each layer receives strongly-typed revenue data. fx_margin_usd = collection_margin_usd + payout_margin_usd (computed at construction). For same-currency short-circuit transactions collection_margin_usd = payout_margin_usd = 0 and fx_margin_usd = 0; service_charge is still present.
**Steps:** Create domain/model/RevenueCapture.java as an immutable value object (final class, all-args constructor, getters only).; Fields: collectionMarginUsd (BigDecimal), payoutMarginUsd (BigDecimal), fxMarginUsd (BigDecimal, = collectionMarginUsd + payoutMarginUsd), serviceChargeAmount (BigDecimal), serviceChargeCcy (String).; Validate in constructor: collectionMarginUsd >= 0, payoutMarginUsd >= 0, serviceChargeAmount >= 0; throw IllegalArgumentException on violation.; Add static factory RevenueCapture.fromPoolValues(BigDecimal collMargin, BigDecimal payMargin, BigDecimal serviceCharge, String serviceChargeCcy).; Add static RevenueCapture zeroCrossBorder(BigDecimal serviceCharge, String serviceChargeCcy) for same-currency short-circuit (margins = 0).
**Deliverable:** domain/model/RevenueCapture.java — immutable value object with two factory methods
**Acceptance / logic checks:**
- fromPoolValues(0.3697, 0.3697, 0.36, "USD").getFxMarginUsd() == 0.7394 (BigDecimal equality).
- zeroCrossBorder(500, "KRW").getFxMarginUsd() == 0 and getCollectionMarginUsd() == 0.
- Constructor throws IllegalArgumentException for negative collectionMarginUsd (e.g. -0.01).
- fxMarginUsd is always exactly collectionMarginUsd + payoutMarginUsd — no separate assignment path can diverge.
- Class is final and all fields are final — no mutators.

### 7.3-T05 — Implement service-charge tier resolution logic  _(35 min)_
**Context:** WBS 7.3. DAT-03 §4.7 describes optional volume-tier overrides for service_charge indexed by collection_usd. Table service_charge_tier: rule_id FK, min_collection_usd DECIMAL(20,4) inclusive, max_collection_usd DECIMAL(20,4) exclusive (NULL = no cap), charge_amount DECIMAL(20,4) in rule's settle_a_ccy. If no tiers exist OR tier table is disabled, fall back to rule.service_charge_amount (the flat charge). Tier selection: find the single tier where collection_usd >= min AND (collection_usd < max OR max IS NULL). If no tier matches (gap in range), fall back to flat. If two tiers overlap (misconfiguration), take the first by min_collection_usd ascending.
**Steps:** Create service/rate/ServiceChargeTierResolver.java with method: BigDecimal resolve(Rule rule, List<ServiceChargeTier> tiers, BigDecimal collectionUsd).; If tiers is null or empty, return rule.getServiceChargeAmount().; Sort tiers by minCollectionUsd ascending.; Iterate; return tier.chargeAmount for the first tier where collectionUsd >= tier.min AND (tier.max == null OR collectionUsd < tier.max).; If no tier matches, fall back to rule.getServiceChargeAmount() (gap or disabled table).; Add Javadoc: same-currency short-circuit passes collectionUsd = null -> always returns flat charge.
**Deliverable:** service/rate/ServiceChargeTierResolver.java with resolve() method
**Acceptance / logic checks:**
- resolve(rule[flat=500], [], 36.97) returns 500 (no tiers, flat).
- resolve(rule[flat=500], [tier: 0-50 -> 400, 50-null -> 300], 36.97) returns 400 (falls in 0-50 range).
- resolve(rule[flat=500], [tier: 0-50 -> 400, 50-null -> 300], 75.00) returns 300 (above 50).
- resolve(rule[flat=500], [tier: 10-50 -> 400], 5.00) returns 500 (below tier min, gap fallback).
- resolve(rule, tiers, null) returns flat charge (same-currency short-circuit path).

### 7.3-T06 — Add revenue extraction to RateEngineService.computeQuote()  _(50 min)_
**Context:** WBS 7.3. The rate engine executes the 5-step RECEIVE-mode sequence (RATE-04): Step 1 payout_usd_cost = target_payout / cost_rate_pay; Step 2 collection_usd = payout_usd_cost / (1 - m_a - m_b); Step 3a collection_margin_usd = collection_usd * m_a; Step 3b payout_margin_usd = collection_usd * m_b; Step 4 send_amount = collection_usd * cost_rate_coll; Step 5 collection_amount = send_amount + service_charge. After Step 3, the engine must construct a RevenueCapture value object (7.3-T04) from collection_margin_usd, payout_margin_usd, service_charge (resolved via ServiceChargeTierResolver, 7.3-T05). For same-currency short-circuit, use RevenueCapture.zeroCrossBorder(). The RevenueCapture is included in the RateQuote return value so it travels to CommitTransaction without recalculation.
**Steps:** Locate (or create) service/rate/RateEngineService.java computeQuote() method.; After computing collection_margin_usd and payout_margin_usd (Step 3), call ServiceChargeTierResolver.resolve() to get effective service_charge.; Call RevenueCapture.fromPoolValues(collectionMarginUsd, payoutMarginUsd, resolvedServiceCharge, rule.getServiceChargeCcy()).; For same-currency path call RevenueCapture.zeroCrossBorder(resolvedServiceCharge, rule.getServiceChargeCcy()).; Add revenueCapture field to RateQuote (or existing quote response DTO) and populate it.; Ensure service_charge used in Step 5 (collection_amount = send_amount + service_charge) equals the same resolved value stored in RevenueCapture.
**Deliverable:** RateEngineService.computeQuote() returns a RateQuote containing a non-null RevenueCapture
**Acceptance / logic checks:**
- For target_payout=13500 KRW, cost_rate_pay=1350, cost_rate_coll=3500, m_a=0.015, m_b=0.010, flat service_charge=500 MNT: revenueCapture.collectionMarginUsd = 0.1538, payoutMarginUsd = 0.1026, fxMarginUsd = 0.2564, serviceChargeAmount = 500, serviceChargeCcy = MNT.
- Same-currency path (collection=settle_a=settle_b=payout=KRW): revenueCapture.fxMarginUsd = 0, serviceChargeAmount = 500 KRW.
- Tiered service charge overrides flat: if tier for collectionUsd=10.2564 returns 400 MNT, serviceChargeAmount = 400.
- collection_amount in the quote equals send_amount + revenueCapture.serviceChargeAmount (not the flat rule value when tier applies).
- RevenueCapture is not null for any path (cross-border or same-currency).
**Depends on:** 7.3-T04, 7.3-T05

### 7.3-T07 — Persist revenue_record atomically in CommitTransactionService  _(50 min)_
**Context:** WBS 7.3. DAT-03 §8.2: revenue_record is populated on transaction commitment. The CommitTransaction flow: (1) validate quote not expired; (2) rate-lock all USD-pool values onto transaction row; (3) INSERT revenue_record in the same DB transaction so revenue capture is atomic with transaction commit. If the transaction rolls back (e.g. pool identity failure), the revenue_record row must also roll back. Inputs: the locked transaction entity (carrying collection_margin_usd, payout_margin_usd, service_charge, service_charge_ccy from rate-lock) and the scheme's gme_fee_share_pct (from qr_scheme table, e.g. 0.7000 for ZeroPay). fx_margin_usd = collection_margin_usd + payout_margin_usd. estimated_fee_share_usd is not populated by this work-package (scheme fee share is a separate revenue stream — leave as NULL or delegate to WBS 7.4).
**Steps:** Locate (or create) service/payment/CommitTransactionService.java.; After the transaction entity has been rate-locked (all USD-pool fields written), call RevenueRecordRepository.findByTxnId(txn.getId()) — if a row already exists, skip INSERT (idempotency guard).; Build a RevenueRecord using RevenueRecord.of(...) with fx_margin_usd = txn.collectionMarginUsd + txn.payoutMarginUsd, serviceChargeAmount = txn.serviceCharge, serviceChargeCcy = txn.serviceChargeCcy, feeSharePct = scheme.gmeFeeSharePct.; Call RevenueRecordRepository.save(revenueRecord) inside the same @Transactional boundary as the transaction row update.; Log at INFO: txn_ref, fx_margin_usd, service_charge_amount, revenue_date for observability.; Do NOT call any external system or send events during this step — pure DB write.
**Deliverable:** CommitTransactionService inserts revenue_record atomically with the transaction commit
**Acceptance / logic checks:**
- After CommitTransactionService.commit(txn), a revenue_record row exists with txn_id = txn.id and fx_margin_usd = collection_margin_usd + payout_margin_usd.
- If CommitTransactionService throws (simulated DB error after rate-lock but before revenue INSERT), both the transaction update and revenue_record INSERT are rolled back (no orphan revenue row).
- Calling commit() twice for the same txn_id inserts only one revenue_record row (idempotency guard fires on second call).
- Same-currency transaction: revenue_record row has fx_margin_usd = 0.0000 and service_charge_amount = actual service charge (e.g. 500 KRW).
- revenue_date = committed_at.toLocalDate() in the server timezone (UTC or configured zone — consistent with transaction.committed_at).
**Depends on:** 7.3-T02, 7.3-T03, 7.3-T06

### 7.3-T08 — Validate pool identity before revenue capture at commit  _(40 min)_
**Context:** WBS 7.3 / RATE-04 §11.2. Before persisting a revenue_record the CommitTransactionService must assert the pool identity invariant: |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| < 0.01 USD. If the assertion fails, throw PoolIdentityFailureException (internal error code POOL_IDENTITY_FAILURE) and do NOT commit the transaction or insert a revenue_record. For same-currency short-circuit transactions (is_same_ccy_shortcircuit = true), collection_usd / payout_usd_cost are NULL — skip the check. The tolerance is exactly 0.01 USD (use BigDecimal.valueOf(0.01)).
**Steps:** Create service/rate/PoolIdentityValidator.java with void assertValid(TransactionEntity txn) method.; If txn.isSameCcyShortcircuit() return immediately (no check needed).; Compute delta = txn.collectionUsd.subtract(txn.collectionMarginUsd).subtract(txn.payoutMarginUsd).subtract(txn.payoutUsdCost).abs().; If delta.compareTo(BigDecimal.valueOf(0.01)) > 0, throw new PoolIdentityFailureException(txn.getTxnRef(), delta).; Create PoolIdentityFailureException extending RuntimeException with fields txnRef and delta (BigDecimal).; Call assertValid() in CommitTransactionService BEFORE the revenue_record INSERT (7.3-T07).
**Deliverable:** PoolIdentityValidator.java and PoolIdentityFailureException.java; integrated into commit pipeline
**Acceptance / logic checks:**
- assertValid passes for: collection_usd=10.2564, collection_margin_usd=0.1538, payout_margin_usd=0.1026, payout_usd_cost=10.0000 (delta=0.0000 < 0.01).
- assertValid throws PoolIdentityFailureException for delta=0.0150 (exceeds tolerance).
- assertValid passes at boundary: delta=0.0099 < 0.01 (within tolerance).
- assertValid skips check and returns normally when isSameCcyShortcircuit=true (even if USD pool fields are null).
- CommitTransactionService rolls back transaction and produces no revenue_record row when PoolIdentityFailureException is thrown.
**Depends on:** 7.3-T07

### 7.3-T09 — Handle same-currency short-circuit revenue path in CommitTransactionService  _(35 min)_
**Context:** WBS 7.3. For same-currency transactions (e.g. GME Remit on ZeroPay, all KRW, is_same_ccy_shortcircuit=true): USD pool is skipped entirely; collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd are all NULL or 0 (RATE-04 §7.1). Revenue capture for this case: fx_margin_usd = 0.0000 (no FX margin); service_charge_amount = flat charge from rule (e.g. 500 KRW) — still recorded as GMEPay+ revenue per business rule A-04. The revenue_record row MUST still be inserted. collection_amount = target_payout + service_charge. Margin fields on transaction record are NULL for same-currency. CommitTransactionService must branch on is_same_ccy_shortcircuit when computing fx_margin_usd for the revenue_record.
**Steps:** In CommitTransactionService, add an if-branch: if txn.isSameCcyShortcircuit(), set fxMarginUsd = BigDecimal.ZERO; else fxMarginUsd = txn.collectionMarginUsd.add(txn.payoutMarginUsd).; Ensure service_charge_amount and service_charge_ccy are still read from txn.serviceCharge and txn.serviceChargeCcy for both branches.; Pass fxMarginUsd = 0, serviceChargeAmount = txn.serviceCharge into RevenueRecord.of() for the same-currency case.; Add an integration assertion: for same-currency txn, revenue_record.fx_margin_usd = 0 and revenue_record.service_charge_amount > 0 when service charge is configured.; Add logging: 'same-currency revenue: fx_margin=0, service_charge=<amount> <ccy>'.
**Deliverable:** CommitTransactionService correctly handles same-currency revenue path with fx_margin_usd=0
**Acceptance / logic checks:**
- For a KRW same-currency transaction with service_charge=500, revenue_record.fx_margin_usd = 0.0000 and service_charge_amount = 500.
- For a same-currency transaction with service_charge=0, revenue_record.service_charge_amount = 0.0000 (valid — GME Remit may have zero charge in some rules).
- No NullPointerException when txn.collectionMarginUsd is NULL (same-currency path stores NULL).
- Cross-border path unaffected: fx_margin_usd = collection_margin_usd + payout_margin_usd as before.
- revenue_record row is still inserted for same-currency transactions (not skipped).
**Depends on:** 7.3-T07

### 7.3-T10 — Validate m_a + m_b >= 2% minimum margin at rule save (Admin guard)  _(35 min)_
**Context:** WBS 7.3 / RATE-04 §11.3. Business rule: m_a + m_b >= 2.0% (0.02 as decimal fraction) is a hard constraint for all cross-border rules. 0% is allowed only for same-currency rules (is_same_ccy_shortcircuit = true). This is enforced at rule-save time in the Admin System backend, not just at runtime. The rule entity has fields m_a DECIMAL(6,6) and m_b DECIMAL(6,6). The database also has a CHECK constraint (from WBS 1.x rule migration): CHECK (is_same_ccy_shortcircuit OR (m_a + m_b) >= 0.02). The service-layer guard must catch this BEFORE the DB round-trip. Error to surface to Admin UI: MARGIN_BELOW_MINIMUM.
**Steps:** Create (or extend) service/admin/RuleValidationService.java with method void validateMargins(Rule rule).; If rule.isSameCcyShortcircuit(): assert m_a == 0 AND m_b == 0; else throw MarginValidationException(SAME_CCY_NONZERO_MARGIN).; Else: assert m_a.add(m_b).compareTo(BigDecimal.valueOf(0.02)) >= 0; else throw MarginValidationException(MARGIN_BELOW_MINIMUM).; Also assert m_a >= 0 and m_b >= 0 (individual non-negativity).; Also assert m_a.add(m_b).compareTo(BigDecimal.ONE) < 1 (sum < 100%, division-by-zero guard).; Call validateMargins() from the rule create/update service path before any DB write.
**Deliverable:** RuleValidationService.validateMargins() integrated into rule-save path; MarginValidationException defined
**Acceptance / logic checks:**
- m_a=0.010, m_b=0.010 (combined 2.0%) passes without exception.
- m_a=0.010, m_b=0.009 (combined 1.9%) throws MarginValidationException with code MARGIN_BELOW_MINIMUM.
- Same-currency rule with m_a=0, m_b=0 passes.
- Same-currency rule with m_a=0.005 throws MarginValidationException with code SAME_CCY_NONZERO_MARGIN.
- m_a=-0.005 throws (negative margin guard).

### 7.3-T11 — Validate margins at runtime in RateEngineService (guard layer)  _(30 min)_
**Context:** WBS 7.3 / RATE-04 §11.1. The rate engine must also guard against invalid margins at quote time (defence-in-depth: Admin can be bypassed by direct DB writes). Guards required: (a) m_a >= 0 and m_b >= 0 else INVALID_MARGIN; (b) m_a + m_b < 1.0 else INVALID_MARGIN (division-by-zero guard); (c) for cross-border: m_a + m_b >= 0.02 else MARGIN_BELOW_MINIMUM; (d) for same-currency: m_a == 0 and m_b == 0 else INVALID_MARGIN. These checks occur BEFORE the 5-step pool calculation. Throw typed RateEngineException(errorCode) where errorCode is one of the above string constants.
**Steps:** In RateEngineService.computeQuote(), add pre-calculation margin guards as the first executable block.; Check m_a.compareTo(BigDecimal.ZERO) < 0 or m_b.compareTo(BigDecimal.ZERO) < 0 -> throw RateEngineException(INVALID_MARGIN).; Check m_a.add(m_b).compareTo(BigDecimal.ONE) >= 0 -> throw RateEngineException(INVALID_MARGIN).; If !isSameCurrency: check m_a.add(m_b).compareTo(BigDecimal.valueOf(0.02)) < 0 -> throw RateEngineException(MARGIN_BELOW_MINIMUM).; If isSameCurrency: check m_a != 0 or m_b != 0 -> throw RateEngineException(INVALID_MARGIN).; Ensure all guard checks use BigDecimal.compareTo(), never == or .equals() on raw doubles.
**Deliverable:** RateEngineService.computeQuote() with all 5 margin guard checks before pool calculation
**Acceptance / logic checks:**
- m_a=0.015, m_b=0.010 on cross-border rule: no exception thrown, engine proceeds to Step 1.
- m_a=0.010, m_b=0.009 on cross-border rule: throws RateEngineException with code MARGIN_BELOW_MINIMUM.
- m_a=-0.001 throws RateEngineException with code INVALID_MARGIN.
- m_a=0.60, m_b=0.50 (sum=1.1) throws RateEngineException with code INVALID_MARGIN (division-by-zero guard).
- Same-currency rule with m_a=0.005 throws RateEngineException with code INVALID_MARGIN.
**Depends on:** 7.3-T06

### 7.3-T12 — Validate service charge non-negativity in rate engine  _(25 min)_
**Context:** WBS 7.3 / RATE-04 §11.1. Guard: service_charge >= 0 is required before Step 5. A negative service charge (data entry error in Admin) must cause error INVALID_SERVICE_CHARGE rather than producing a silently wrong collection_amount. Check occurs after tier resolution (ServiceChargeTierResolver returns the effective charge) and before Step 5 in computeQuote(). Both the flat charge and any tier-resolved override must be non-negative. Zero is valid (domestic rules may have no service charge).
**Steps:** After ServiceChargeTierResolver.resolve() returns effectiveServiceCharge in computeQuote(), add: if effectiveServiceCharge.compareTo(BigDecimal.ZERO) < 0, throw RateEngineException(INVALID_SERVICE_CHARGE).; Add the same guard in ServiceChargeTierResolver.resolve(): if a tier's chargeAmount < 0, throw RateEngineException(INVALID_SERVICE_CHARGE).; Ensure zero service charge passes without exception.; Add a check that service_charge_ccy is a 3-char ISO code (non-blank); throw INVALID_SERVICE_CHARGE if blank.; Unit tests for this guard are in 7.3-T20.
**Deliverable:** RateEngineService and ServiceChargeTierResolver throw INVALID_SERVICE_CHARGE on negative or blank-ccy service charges
**Acceptance / logic checks:**
- Flat service_charge = 0 passes (zero is valid).
- Flat service_charge = 500 KRW passes.
- Flat service_charge = -1 throws RateEngineException(INVALID_SERVICE_CHARGE).
- Tier with chargeAmount = -100 throws RateEngineException(INVALID_SERVICE_CHARGE) at resolve time.
- service_charge_ccy = blank string throws RateEngineException(INVALID_SERVICE_CHARGE).
**Depends on:** 7.3-T05, 7.3-T06, 7.3-T11

### 7.3-T13 — Verify rate-lock immutability: locked revenue fields cannot be updated post-commit  _(45 min)_
**Context:** WBS 7.3 / RATE-04 §9.4. Once a transaction is committed, the fields collection_margin_usd, payout_margin_usd, service_charge, collection_amount on the transaction record are permanently locked (rate-lock). No code path may UPDATE these columns after commit. Enforce via: (a) a @PreUpdate JPA listener that throws if any locked field differs from the persisted value; (b) a DB trigger or check ensuring revenue_record rows are insert-only (no UPDATE except updated_at). The transaction entity already has committed_at; if committed_at is NOT NULL, the locked fields are immutable.
**Steps:** Create infrastructure/jpa/RateLockImmutabilityListener.java annotated @EntityListeners on TransactionEntity.; In @PreUpdate: if entity.committedAt != null, verify entity.collectionMarginUsd, payoutMarginUsd, serviceCharge, collectionAmount have not changed vs DB-loaded snapshot; throw RateLockViolationException if any differ.; Add a snapshot mechanism: store the original locked values in @PostLoad into transient fields.; Create RateLockViolationException extending RuntimeException with field names and values.; In V7_3_2 migration, add a DB-level rule: after INSERT on revenue_record, block UPDATE of fx_margin_usd and service_charge_amount (trigger or application constraint via @Column(updatable=false) annotation).; Add @Column(updatable=false) on RevenueRecord.fxMarginUsd and serviceChargeAmount.
**Deliverable:** RateLockImmutabilityListener.java and @Column(updatable=false) on revenue_record monetary fields
**Acceptance / logic checks:**
- Attempting to update collectionMarginUsd on a committed (committedAt not null) transaction entity throws RateLockViolationException.
- Updating a non-locked field (e.g. status) on a committed transaction succeeds without exception.
- A JPA flush that sets revenue_record.fxMarginUsd to a different value is rejected by @Column(updatable=false) at the Hibernate layer.
- Changing treasury rate via Admin after a transaction is committed does not alter the stored collection_margin_usd or payout_margin_usd (integration smoke test).
- RateLockViolationException message includes the field name and both old and attempted new values.
**Depends on:** 7.3-T07

### 7.3-T14 — Add RevenueRecordService with aggregate revenue query methods  _(40 min)_
**Context:** WBS 7.3. The Admin Portal Revenue Report view (PRD-07 §11.3.2) needs aggregates: total FX margin (USD) and service charge by partner, scheme, and date range. Service methods needed: (1) getRevenueByPartner(Long partnerId, LocalDate start, LocalDate end) returning RevenueAggregate DTO; (2) getRevenueByScheme(Long schemeId, LocalDate start, LocalDate end); (3) getRevenueSummary(LocalDate start, LocalDate end) returning a list of per-partner rows. The RevenueAggregate DTO must carry: txnCount (Long), totalFxMarginUsd (BigDecimal), totalServiceChargeAmount (BigDecimal), serviceChargeCcy (String). These are read-only queries with no side-effects.
**Steps:** Create service/revenue/RevenueRecordService.java.; Add method RevenueAggregate getRevenueByPartner(Long partnerId, LocalDate start, LocalDate end): call repository aggregate queries; return populated DTO.; Add List<PartnerRevenueRow> getRevenueSummary(LocalDate start, LocalDate end): call a @Query returning per-partner aggregates.; Create dto/RevenueAggregate.java with fields: txnCount, totalFxMarginUsd, totalServiceChargeAmount, serviceChargeCcy.; Annotate service with @Transactional(readOnly=true).; Add a validation guard: start must be <= end; throw IllegalArgumentException otherwise.
**Deliverable:** RevenueRecordService.java with 3 query methods; RevenueAggregate DTO
**Acceptance / logic checks:**
- getRevenueByPartner for partnerId with 3 revenue rows (fxMarginUsd: 0.25, 0.30, 0.20) returns totalFxMarginUsd = 0.75.
- getRevenueByPartner for a partner with zero rows returns txnCount=0, totalFxMarginUsd=0 (COALESCE, not null).
- start > end throws IllegalArgumentException before any DB call.
- getRevenueSummary returns one row per distinct partner in the date range — partners with no rows excluded.
- Service is annotated @Transactional(readOnly=true) — write operations in same test transaction do not use this service.
**Depends on:** 7.3-T03

### 7.3-T15 — Expose revenue aggregates via Admin API endpoint GET /internal/v1/revenue/summary  _(45 min)_
**Context:** WBS 7.3. The Admin Portal backend (PRD-07 §11.3.2) needs a REST endpoint to serve the Revenue Report view. Endpoint: GET /internal/v1/revenue/summary?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD&partnerId={optional}&schemeId={optional}. Returns JSON array of RevenueAggregate rows. Requires FINANCE_ANALYST or higher role (RBAC). Internal API — not partner-facing; must not expose m_a, m_b, or individual transaction details. Response format: { partner_id, partner_name, scheme_id, scheme_name, txn_count, total_fx_margin_usd, total_service_charge_amount, service_charge_ccy }.
**Steps:** Create web/admin/RevenueReportController.java with @GetMapping("/internal/v1/revenue/summary").; Bind query params: startDate (LocalDate), endDate (LocalDate), optional partnerId (Long), optional schemeId (Long).; Validate startDate <= endDate; return HTTP 400 with error body on violation.; Require FINANCE_ANALYST or SUPER_ADMIN role via @PreAuthorize; return 403 otherwise.; Delegate to RevenueRecordService; map results to response JSON using RevenueSummaryResponse DTO.; Return HTTP 200 with JSON array; empty array (not 404) when no results in range.
**Deliverable:** RevenueReportController.java with GET /internal/v1/revenue/summary endpoint
**Acceptance / logic checks:**
- GET /internal/v1/revenue/summary?startDate=2026-01-01&endDate=2026-01-31 with FINANCE_ANALYST token returns HTTP 200 and correct aggregates.
- Same request with OPS_OPERATOR token returns HTTP 200 (OPS_OPERATOR has read access).
- Same request with unauthenticated call returns HTTP 401/403.
- startDate > endDate returns HTTP 400 with error body.
- Response JSON does not contain fields m_a, m_b, cost_rate_coll, or any individual transaction margin values.
**Depends on:** 7.3-T14

### 7.3-T16 — Implement daily revenue reconciliation job (pool identity check over prior 24h)  _(50 min)_
**Context:** WBS 7.3 / RATE-04 §13.1 + OPS-13. A background job runs daily to verify pool identity invariant for all cross-border transactions committed in the prior 24 hours: |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| < 0.01 USD. Any breach is logged as a P1 CRITICAL alert. Job also checks revenue_record count == committed cross-border transaction count for the period (revenue ledger completeness). For same-currency transactions, skip the pool identity check; verify revenue_record exists with fx_margin_usd = 0. Alert channel: log at ERROR with structured fields; raise an application metric counter revenue.reconciliation.failure.
**Steps:** Create job/DailyRevenueReconciliationJob.java with @Scheduled(cron="0 0 2 * * *") (2 AM daily UTC).; Query all committed cross-border transactions in the past 24 hours (committed_at between now-24h and now).; For each: compute delta = |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost|; if > 0.01 log ERROR with txn_ref, delta, and all pool values; increment counter.; Query revenue_record for the same period; compare count against cross-border transaction count; log ERROR if mismatch.; For same-currency transactions in period: verify a revenue_record with fx_margin_usd=0 exists; log ERROR if missing.; Emit summary log at INFO: checked N transactions, M violations found.
**Deliverable:** DailyRevenueReconciliationJob.java with @Scheduled reconciliation logic and alerting
**Acceptance / logic checks:**
- Job runs without error for a dataset with 3 committed cross-border transactions all having pool identity delta < 0.01.
- Job logs ERROR and increments counter for a transaction with injected delta = 0.015 (breach).
- Job detects and logs revenue_record count mismatch if one transaction has no corresponding revenue row.
- Same-currency transaction with revenue_record.fx_margin_usd=0 passes without error.
- Job is idempotent: running twice in the same period produces the same alerts without double-counting.
**Depends on:** 7.3-T07, 7.3-T08

### 7.3-T17 — Unit test: RevenueCapture value object correctness  _(30 min)_
**Context:** WBS 7.3. RevenueCapture (7.3-T04) must be tested with exact numeric vectors from RATE-04 §4.3. Test vectors: (A) cross-border inbound MNT->KRW via USD: collection_margin_usd=0.1538, payout_margin_usd=0.1026, fx_margin_usd=0.2564, service_charge=500 MNT; (B) both-legs identity (USD->USD): collection_margin_usd=1.0204, payout_margin_usd=1.0204, fx_margin_usd=2.0408, service_charge=0.50 USD (from RATE-04 §6.2 worked example target_payout=100 USD, m_a=m_b=0.01); (C) same-currency short-circuit: all margins=0, service_charge=500 KRW. Also test the IllegalArgumentException path for negative margins.
**Steps:** Create test/unit/domain/model/RevenueCaptureTest.java.; Test vector A: assert fxMarginUsd = 0.1538 + 0.1026 = 0.2564 using BigDecimal compareTo within tolerance 0.0001.; Test vector B: assert fxMarginUsd = 1.0204 + 1.0204 = 2.0408.; Test vector C (zeroCrossBorder): assert collectionMarginUsd=0, payoutMarginUsd=0, fxMarginUsd=0, serviceChargeAmount=500, serviceChargeCcy=KRW.; Test negative collectionMarginUsd: fromPoolValues(-0.01, 0.01, 0, "KRW") throws IllegalArgumentException.; Test negative serviceChargeAmount: fromPoolValues(0.10, 0.10, -1, "USD") throws IllegalArgumentException.
**Deliverable:** test/unit/domain/model/RevenueCaptureTest.java with 6 test cases all passing
**Acceptance / logic checks:**
- Test vector A fxMarginUsd assertion passes (0.2564 within 0.0001 tolerance).
- Test vector B fxMarginUsd assertion passes (2.0408 within 0.0001 tolerance).
- zeroCrossBorder factory sets all margin fields to 0 exactly.
- Negative collectionMarginUsd throws IllegalArgumentException.
- Negative serviceChargeAmount throws IllegalArgumentException.
- All 6 test cases pass with mvn test or gradle test.
**Depends on:** 7.3-T04

### 7.3-T18 — Unit test: ServiceChargeTierResolver with exact tier boundary vectors  _(35 min)_
**Context:** WBS 7.3. ServiceChargeTierResolver (7.3-T05) must be tested with boundary vectors. Setup: Rule flat=500, tiers: [0, 20)->600; [20, 50)->500; [50, null)->400. Test: (a) collection_usd=10.00 -> tier 0-20 -> 600; (b) collection_usd=20.00 -> tier 20-50 -> 500; (c) collection_usd=49.99 -> tier 20-50 -> 500; (d) collection_usd=50.00 -> tier 50-null -> 400; (e) collection_usd=100.00 -> tier 50-null -> 400; (f) no tiers -> flat 500; (g) gap in tiers (15-20 missing), collection_usd=16 -> fallback to flat 500; (h) null collection_usd (same-currency path) -> flat 500.
**Steps:** Create test/unit/service/rate/ServiceChargeTierResolverTest.java.; Build Rule stub and ServiceChargeTier list per the setup above.; Assert each of the 8 test vectors (a)-(h) against expected charge amount.; Include boundary: collection_usd exactly at min_collection_usd (inclusive) of the next tier.; Include gap test: tier table [0,10)->600, [20,50)->500 (gap 10-20); collection_usd=15 -> fallback flat 500.; All assertions use BigDecimal.compareTo(expected) == 0.
**Deliverable:** test/unit/service/rate/ServiceChargeTierResolverTest.java with 8 test cases all passing
**Acceptance / logic checks:**
- collection_usd=10.00 returns 600 (falls in [0,20) tier).
- collection_usd=20.00 returns 500 (boundary: min inclusive in [20,50) tier, not [0,20)).
- collection_usd=50.00 returns 400 (unbounded upper tier).
- No-tiers case returns flat 500.
- Gap case collection_usd=15 returns flat 500 (fallback).
- null collection_usd returns flat 500.
- All 8 assertions pass.
- No test uses == on BigDecimal (uses compareTo).
**Depends on:** 7.3-T05

### 7.3-T19 — Unit test: pool identity validator with boundary and failure cases  _(30 min)_
**Context:** WBS 7.3. PoolIdentityValidator (7.3-T08) must be tested with exact numeric vectors. Test cases: (A) pass: collection_usd=10.2564, margin_a=0.1538, margin_b=0.1026, payout_usd_cost=10.0000 -> delta=0.0000 < 0.01 -> no exception; (B) pass at boundary: delta=0.0099 < 0.01; (C) fail at boundary: delta=0.0100 is NOT < 0.01 -> throws (tolerance is strictly less than 0.01); (D) fail: delta=0.0150 > 0.01 -> throws PoolIdentityFailureException; (E) same-currency short-circuit: isSameCcyShortcircuit=true, all USD fields null -> no exception, no delta computed; (F) constructed failure: collection_usd=10.26, margin_a=0.15, margin_b=0.10, payout=10.00 -> delta=0.01 -> throws.
**Steps:** Create test/unit/service/rate/PoolIdentityValidatorTest.java.; Build minimal TransactionEntity stubs (or use a mock) for each vector with the required BigDecimal fields and isSameCcyShortcircuit boolean.; Assert vectors A and B pass without exception.; Assert vectors C, D, F throw PoolIdentityFailureException.; Assert vector E (same-currency) passes even with null USD-pool fields.; Verify PoolIdentityFailureException message contains the delta value for vector D.
**Deliverable:** test/unit/service/rate/PoolIdentityValidatorTest.java with 6 test cases all passing
**Acceptance / logic checks:**
- Vector A: assertDoesNotThrow.
- Vector B (delta=0.0099): assertDoesNotThrow.
- Vector C (delta=0.0100): assertThrows PoolIdentityFailureException (tolerance is strictly less than 0.01).
- Vector D (delta=0.0150): assertThrows PoolIdentityFailureException; exception message contains '0.0150'.
- Vector E (same-currency): assertDoesNotThrow with null USD fields.
- All 6 tests pass.
**Depends on:** 7.3-T08

### 7.3-T20 — Unit test: margin guard and service-charge guard in RateEngineService  _(35 min)_
**Context:** WBS 7.3. Guards from 7.3-T11 and 7.3-T12 need targeted unit tests with exact error codes. Error codes: INVALID_MARGIN (negative m_a/m_b, sum>=1, same-currency nonzero), MARGIN_BELOW_MINIMUM (cross-border sum < 0.02), INVALID_SERVICE_CHARGE (negative or blank-ccy). Test vectors: (A) m_a=0.015, m_b=0.010, cross-border -> no exception; (B) m_a=0.010, m_b=0.009, cross-border -> MARGIN_BELOW_MINIMUM; (C) m_a=-0.001 -> INVALID_MARGIN; (D) m_a=0.60, m_b=0.50 -> INVALID_MARGIN; (E) same-currency, m_a=0, m_b=0 -> no exception; (F) same-currency, m_a=0.005 -> INVALID_MARGIN; (G) service_charge=-1 -> INVALID_SERVICE_CHARGE; (H) service_charge_ccy=blank -> INVALID_SERVICE_CHARGE.
**Steps:** Create test/unit/service/rate/RateEngineGuardsTest.java.; Mock or stub the rule and treasury rate inputs to isolate the guard layer.; Test each vector A-H; assert exception type and errorCode field for failure cases.; For vectors A and E, assert computeQuote() proceeds past the guard block (no exception thrown at guard stage).; Verify all BigDecimal comparisons use compareTo, not equals or ==.; Add a test that m_a=0, m_b=0.02 (combined 2%) cross-border passes the minimum guard.
**Deliverable:** test/unit/service/rate/RateEngineGuardsTest.java with 9 test cases all passing
**Acceptance / logic checks:**
- Vector B throws RateEngineException with errorCode=MARGIN_BELOW_MINIMUM.
- Vector C throws RateEngineException with errorCode=INVALID_MARGIN.
- Vector D (sum=1.1) throws RateEngineException with errorCode=INVALID_MARGIN.
- Vector F (same-currency nonzero margin) throws RateEngineException with errorCode=INVALID_MARGIN.
- Vector G (service_charge=-1) throws RateEngineException with errorCode=INVALID_SERVICE_CHARGE.
- All 9 tests pass; no test uses floating-point literals.
**Depends on:** 7.3-T11, 7.3-T12

### 7.3-T21 — Unit test: CommitTransactionService revenue_record insertion (cross-border and same-currency)  _(50 min)_
**Context:** WBS 7.3. CommitTransactionService (7.3-T07 and 7.3-T09) needs unit tests covering: (A) cross-border: verifies revenue_record is saved with correct fx_margin_usd = collection_margin_usd + payout_margin_usd and service_charge_amount; (B) same-currency: fx_margin_usd=0, service_charge_amount = rule service charge; (C) idempotency: second call to commit() for the same txn_id does not insert a second revenue_record; (D) rollback: if pool identity check fails (PoolIdentityFailureException thrown), no revenue_record row is persisted. Use a Spring test slice with an in-memory database (H2 or Testcontainers Postgres).
**Steps:** Create test/integration/service/payment/CommitTransactionServiceTest.java.; Seed a transaction row in DEBITED state with rate-engine fields populated for a cross-border case.; Call commitTransaction(); verify revenue_record count = 1 with expected fx_margin_usd value.; Seed a same-currency transaction; verify revenue_record.fx_margin_usd = 0.; For idempotency: call commitTransaction() twice with same txn; verify still only 1 revenue_record row.; For rollback: inject a PoolIdentityFailureException from PoolIdentityValidator mock; assert no revenue_record row and transaction row unchanged.
**Deliverable:** test/integration/service/payment/CommitTransactionServiceTest.java with 4 test cases all passing
**Acceptance / logic checks:**
- Cross-border case: revenue_record.fx_margin_usd = sum of margin fields from transaction (BigDecimal equality).
- Same-currency case: revenue_record.fx_margin_usd = 0.0000 and service_charge_amount = expected value.
- Idempotency: exactly 1 revenue_record row after 2 commit calls.
- Rollback case: 0 revenue_record rows and transaction.status unchanged after PoolIdentityFailureException.
- All tests pass under mvn test or gradle test with in-memory DB.
**Depends on:** 7.3-T07, 7.3-T08, 7.3-T09

### 7.3-T22 — Unit test: RuleValidationService margin checks at rule save  _(30 min)_
**Context:** WBS 7.3. RuleValidationService.validateMargins() (7.3-T10) needs unit tests for all 5 guard branches: (A) cross-border m_a=0.010, m_b=0.010 passes; (B) cross-border m_a=0.010, m_b=0.009 -> MARGIN_BELOW_MINIMUM; (C) same-currency m_a=0, m_b=0 passes; (D) same-currency m_a=0.005 -> SAME_CCY_NONZERO_MARGIN; (E) m_a=-0.001 -> INVALID_MARGIN (negative individual margin); (F) m_a=0, m_b=0.02 exactly at 2% -> passes (boundary: >= 0.02 is accepted); (G) m_a=0.60, m_b=0.50 (sum=1.10) -> INVALID_MARGIN (sum >= 1.0 is rejected).
**Steps:** Create test/unit/service/admin/RuleValidationServiceTest.java.; Build Rule stubs for each vector with is_same_ccy_shortcircuit set appropriately.; Assert assertDoesNotThrow for vectors A, C, F.; Assert assertThrows MarginValidationException with matching error code for B, D, E, G.; Verify MarginValidationException.getErrorCode() returns the expected constant string for each failure case.; All assertions use BigDecimal.valueOf() for margin literals, not double literals.
**Deliverable:** test/unit/service/admin/RuleValidationServiceTest.java with 7 test cases all passing
**Acceptance / logic checks:**
- Vector B throws MarginValidationException; getErrorCode() == MARGIN_BELOW_MINIMUM.
- Vector D throws MarginValidationException; getErrorCode() == SAME_CCY_NONZERO_MARGIN.
- Vector E throws MarginValidationException; getErrorCode() == INVALID_MARGIN.
- Vector F (exactly 2.0%) does not throw (boundary is inclusive).
- Vector G (sum=1.10) throws MarginValidationException; getErrorCode() == INVALID_MARGIN.
- All 7 tests pass.
**Depends on:** 7.3-T10

### 7.3-T23 — Integration test: end-to-end revenue capture for cross-border MPM payment  _(55 min)_
**Context:** WBS 7.3. End-to-end integration test wiring all revenue components: RateEngineService.computeQuote() -> CommitTransactionService -> revenue_record INSERT. Scenario (RATE-04 §4.3 worked example, SendMN OVERSEAS INBOUND): target_payout=50805 KRW, cost_rate_pay=treasury.usd_krw=1350.00, cost_rate_coll=1.0 (IDENTITY, Settle A=USD), m_a=0.01, m_b=0.01, service_charge=0.36 USD (flat). Expected: payout_usd_cost=37.6333 USD, collection_usd=38.0134 USD, collection_margin_usd=0.3801 USD, payout_margin_usd=0.3801 USD, fx_margin_usd=0.7602 USD, collection_amount=38.3734 USD. Revenue record must reflect these locked values.
**Steps:** Create test/integration/RevenueCaptureIntegrationTest.java using @SpringBootTest with Testcontainers PostgreSQL.; Seed Rule (SENDMN, ZEROPAY, INBOUND): m_a=0.01, m_b=0.01, service_charge=0.36 USD, settle_a=USD, settle_b=KRW.; Seed treasury_rate usd_krw=1350.00, usd_usd=1.0.; Call rateEngineService.computeQuote() with target_payout=50805 KRW; assert intermediate values within 0.01 USD tolerance.; Call commitTransactionService.commit() with the resulting quote; assert revenue_record is persisted.; Assert revenue_record.fx_margin_usd = collection_margin_usd + payout_margin_usd (within 0.0001 tolerance).
**Deliverable:** test/integration/RevenueCaptureIntegrationTest.java with full pipeline test passing
**Acceptance / logic checks:**
- collection_usd value in quote is within 0.01 of expected 38.0134.
- revenue_record.fx_margin_usd is within 0.0001 of collection_margin_usd + payout_margin_usd from the committed transaction.
- revenue_record.service_charge_amount = 0.36 and service_charge_ccy = USD.
- revenue_record.revenue_date = the date portion of the committed_at timestamp.
- Pool identity holds: |collection_usd - margin_a - margin_b - payout_usd_cost| < 0.01 (assert in test).
**Depends on:** 7.3-T07, 7.3-T08, 7.3-T09, 7.3-T21

### 7.3-T24 — Integration test: revenue capture for same-currency domestic payment  _(35 min)_
**Context:** WBS 7.3. Integration test for same-currency short-circuit path (GME Remit on ZeroPay, all KRW, is_same_ccy_shortcircuit=true). Per RATE-04 §7.1 and DAT-03 §6.2: USD pool is skipped; collection_amount = target_payout + service_charge; collection_margin_usd = payout_margin_usd = null/0. Revenue record: fx_margin_usd=0.0000, service_charge_amount=500 (KRW), service_charge_ccy=KRW. No pool identity check for same-currency. Scenario: target_payout=13500 KRW, service_charge=500 KRW, m_a=0, m_b=0. Expected collection_amount=14000 KRW.
**Steps:** Add test case in test/integration/RevenueCaptureIntegrationTest.java (or a new test class).; Seed Rule (GME_REMIT, ZEROPAY, DOMESTIC): m_a=0, m_b=0, is_same_ccy_shortcircuit=true, service_charge=500 KRW, settle_a=KRW.; Call computeQuote() with target_payout=13500 KRW; assert collection_amount=14000 KRW; assert collection_usd is null or not computed.; Call commit(); assert revenue_record persisted.; Assert revenue_record.fx_margin_usd = 0.0000 and service_charge_amount = 500.0000 and service_charge_ccy = KRW.; Assert no PoolIdentityFailureException is raised for this transaction.
**Deliverable:** Same-currency revenue_record integration test passing (appended to integration test class)
**Acceptance / logic checks:**
- collection_amount in quote = 14000 KRW (13500 + 500).
- revenue_record.fx_margin_usd = 0.0000 exactly.
- revenue_record.service_charge_amount = 500.0000 and service_charge_ccy = KRW.
- No exception thrown during commit (pool identity validator skips same-currency path).
- revenue_record row exists in DB after commit (not absent for same-currency).
**Depends on:** 7.3-T23

### 7.3-T25 — Update Admin API revenue report to include service_charge_ccy grouping  _(35 min)_
**Context:** WBS 7.3. The revenue report endpoint (7.3-T15) returns service_charge_amount but service charges can be in different currencies depending on the Settle A currency (e.g. USD for SendMN, KRW for GME Remit). The aggregate must group by service_charge_ccy to avoid summing KRW and USD together. The response must include one row per (partner, scheme, service_charge_ccy) combination. A partner using USD settle_a and KRW settle_a under different rules produces separate rows. RevenueAggregate DTO needs serviceChargeCcy field. SQL aggregate query must include GROUP BY service_charge_ccy.
**Steps:** Update the @Query in RevenueRecordRepository to GROUP BY service_charge_ccy in addition to partner_id and scheme_id.; Update RevenueAggregate DTO to include serviceChargeCcy (String) field.; Update RevenueRecordService.getRevenueSummary() to return one row per (partner, scheme, ccy) triple.; Update RevenueSummaryResponse to include serviceChargeCcy in JSON output.; Update RevenueReportController to pass through the extra grouping dimension.; Add a unit test: two revenue rows for same partner/scheme but different ccys produce two summary rows.
**Deliverable:** Revenue summary groups by service_charge_ccy; RevenueAggregate DTO carries serviceChargeCcy field
**Acceptance / logic checks:**
- Two revenue records for partner=SENDMN, scheme=ZEROPAY with ccy USD and KRW respectively produce two summary rows (not one summed row).
- Each summary row has totalServiceChargeAmount in its own currency (not cross-currency sum).
- Existing cross-border USD summary rows unaffected.
- RevenueSummaryResponse JSON includes service_charge_ccy field.
- Unit test with mixed-ccy data passes.
**Depends on:** 7.3-T14, 7.3-T15

### 7.3-T26 — Write Javadoc for RevenueCapture, RevenueRecord, and CommitTransactionService revenue path  _(30 min)_
**Context:** WBS 7.3. Developer documentation must be inline so that the next developer understands the revenue capture contract without reading the full spec. Key rules to document: (1) fx_margin_usd = collection_margin_usd + payout_margin_usd, never a percentage of the transaction amount; (2) service_charge is always in Settle A currency and never enters the USD pool; (3) revenue_record is inserted atomically with the transaction commit; (4) locked fields on transaction and revenue_record are immutable after commit; (5) same-currency short-circuit always produces fx_margin_usd=0; (6) pool identity tolerance is 0.01 USD.
**Steps:** Add class-level Javadoc to RevenueCapture.java explaining that fxMarginUsd = collectionMarginUsd + payoutMarginUsd and citing RATE-04 §5.; Add class-level Javadoc to RevenueRecord.java explaining that it is insert-only, populated at CommitTransaction, with a note that fx_margin_usd=0 for same-currency rules.; Add method-level Javadoc to CommitTransactionService.commit() documenting the atomic write sequence: rate-lock -> pool identity check -> revenue_record insert.; Add a @see RATE-04 tag on the 5-step formula reference in RateEngineService.computeQuote().; Add a note on PoolIdentityValidator.assertValid() that POOL_IDENTITY_FAILURE is a programming error, not a user error.; Review all Javadoc for accuracy against the spec; no prose must contradict the tested behaviour.
**Deliverable:** Javadoc on RevenueCapture, RevenueRecord, CommitTransactionService, PoolIdentityValidator classes/methods
**Acceptance / logic checks:**
- RevenueCapture class Javadoc mentions the formula fxMarginUsd = collectionMarginUsd + payoutMarginUsd explicitly.
- RevenueRecord class Javadoc states it is insert-only and that fx_margin_usd=0 for same-currency transactions.
- CommitTransactionService.commit() Javadoc documents the three-step atomic write sequence.
- PoolIdentityValidator.assertValid() Javadoc labels POOL_IDENTITY_FAILURE as an internal programming error requiring investigation.
- No Javadoc contradicts the passing unit/integration tests for this work-package.
**Depends on:** 7.3-T04, 7.3-T07, 7.3-T08

### 7.3-T27 — Register REVENUE_ROUNDING in chart of accounts + report  _(30 min)_
**Context:** Rounding gains/losses post to REVENUE_ROUNDING. Ensure the account is in the chart of accounts and surfaced in GET /v1/revenue.
**Steps:** Add REVENUE_ROUNDING to chart of accounts; Include rounding gain/loss in the revenue summary
**Deliverable:** REVENUE_ROUNDING reported in /v1/revenue
**Acceptance / logic checks:**
- account present
- rounding total in revenue report reconciles to sum of residuals


<!-- wbs-v3-gap-closure -->

---

## WBS v3 gap-closure tickets (re-baseline, 2026-06-10)

These tickets convert this service's PARTIAL audit findings into DONE and add work discovered during the build. Statuses live on the `Backlog` sheet of `GMEPay+_Task_Backlog.xlsx`; phase sequencing on the `Completion Plan v3` sheet of `GMEPay+_WBS.xlsx`.

### 17.2-G06 — revenue-ledger: swap H2 for real PostgreSQL ITs
*Completion phase:* **R1** · *Est:* 120 min · *Role:* Backend · *Deps:* 17.1-G02

**Context.** Tests currently run on H2 in PostgreSQL mode. Acceptance requires real PG. Scope: journal/postings + outbox (V001-V003).

**Steps.**
- Add Testcontainers postgres:16 to the service's ITs
- Run Flyway migrations against it; fix PG-only syntax drift
- Keep H2 only for pure unit slices

**Deliverable.** Repository/migration ITs green on PostgreSQL 16

**Acceptance.**
- ./gradlew :services:revenue-ledger:test green with Testcontainers
- Migration checksum stable; no H2-mode workarounds left

### 17.4-G03 — Outbox publisher drains to Kafka (ledger)
*Completion phase:* **R1** · *Est:* 80 min · *Role:* Backend · *Deps:* 17.4-G01

**Context.** Same as transaction-mgmt but for revenue-ledger's V003 outbox (journal.posted events).

**Steps.**
- Reuse drain pattern
- IT with Testcontainers kafka

**Deliverable.** Ledger events on Kafka

**Acceptance.**
- journal.posted consumable from topic

