# reporting-compliance  (backend)

**Scope:** BOK FX1014/1015, AML/KYC hooks, revenue reports

**Owned WBS work-packages:** 7.5, 13.7, 13.8  ·  **Tickets:** 77  ·  **Est:** 48.8h

## Service contract (MSA: own DB, API-only communication)

- **Datastore (owned by this service):** Object storage (reports/exports)
- **APIs / events I EXPOSE:** /v1/reports, BOK FX1014/1015 export
- **APIs / events I CONSUME:** revenue-ledger, transaction-mgmt (sync/event)
- **Integration rule:** never read another service's database or import its private entities — call its API or consume its event; stub consumed services with WireMock in tests.

> Self-contained backlog for this service. Build it as its own repo/module with its own DB + Flyway migrations, against the `shared-libs` contracts (lib-money / lib-errors / lib-events / lib-api-contracts only). Each ticket has a deliverable + acceptance checks.


## WBS 7.5 — Revenue reporting & exports
### 7.5-T01 — Add DB migration: revenue_record table with all required columns  _(30 min)_
**Context:** The revenue_record table (DAT-03 §8.2) tracks GMEPay+ revenue per committed transaction. Columns: id BIGINT PK, txn_id BIGINT FK -> transaction NOT NULL UNIQUE, partner_id BIGINT FK -> partner NOT NULL, scheme_id BIGINT FK -> qr_scheme NOT NULL, revenue_date DATE NOT NULL (= committed_at::date in KST), fx_margin_usd DECIMAL(20,4) (= collection_margin_usd + payout_margin_usd; NULL for same-currency short-circuit transactions), service_charge_amount DECIMAL(20,4) NOT NULL, service_charge_ccy CHAR(3) NOT NULL, fee_share_pct DECIMAL(6,4) NOT NULL (e.g. 0.7000 for 70% ZeroPay), estimated_fee_share_usd DECIMAL(20,4) (estimated at commit; confirmed from scheme settlement), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(). Indexes: (partner_id, revenue_date), (scheme_id, revenue_date), (revenue_date). Service charge is always in settle_a_ccy and never enters the USD pool (RATE-04 §4).
**Steps:** Create Flyway migration V7_5_001__create_revenue_record.sql; Define revenue_record with all columns per spec; set DECIMAL precision DECIMAL(20,4) for amounts, DECIMAL(6,4) for fee_share_pct; Add FK constraints to transaction, partner, qr_scheme with ON DELETE RESTRICT; Add composite indexes (partner_id, revenue_date), (scheme_id, revenue_date), single index on revenue_date; Add trigger or application note: updated_at auto-refreshed on UPDATE
**Deliverable:** V7_5_001__create_revenue_record.sql Flyway migration file
**Acceptance / logic checks:**
- Migration applies cleanly against schema baseline with no errors
- SELECT * FROM revenue_record WHERE false returns columns: id, txn_id, partner_id, scheme_id, revenue_date, fx_margin_usd, service_charge_amount, service_charge_ccy, fee_share_pct, estimated_fee_share_usd, created_at, updated_at
- INSERT with txn_id already present in table violates UNIQUE constraint
- INSERT with partner_id not in partner table violates FK constraint
- EXPLAIN on WHERE partner_id = 1 AND revenue_date = '2026-01-15' uses the composite index

### 7.5-T02 — Add confirmed_fee_share_krw column to revenue_record via migration  _(20 min)_
**Context:** The revenue_record.estimated_fee_share_usd is set at commit time. A second column confirmed_fee_share_krw DECIMAL(20,4) is populated later when ZeroPay settlement result files (ZP0062/ZP0064) are received and reconciled. Until then it is NULL. This is part of the same revenue_record table but is added as a separate migration to avoid blocking T01. The fee share is: GME gets 70% of net merchant fee from ZeroPay (scheme.gme_fee_share_pct = 0.70). Domestic settlement is net (GME deducts fee before settlement); international is gross (GME invoices monthly). Both streams must be separately reportable.
**Steps:** Create Flyway migration V7_5_002__revenue_record_add_confirmed_fee_share.sql; ALTER TABLE revenue_record ADD COLUMN confirmed_fee_share_krw DECIMAL(20,4) NULL; ALTER TABLE revenue_record ADD COLUMN settlement_model VARCHAR(20) NULL CHECK (settlement_model IN ('NET','GROSS')) to distinguish domestic from international revenue; Add an index on (revenue_date, settlement_model) for filtered report queries
**Deliverable:** V7_5_002__revenue_record_add_confirmed_fee_share.sql Flyway migration file
**Acceptance / logic checks:**
- Migration applies cleanly; revenue_record now has confirmed_fee_share_krw and settlement_model columns
- confirmed_fee_share_krw can be NULL (pre-settlement) and updated to a positive decimal after reconciliation
- settlement_model rejects values other than NET and GROSS
- SELECT confirmed_fee_share_krw FROM revenue_record WHERE confirmed_fee_share_krw IS NOT NULL returns only post-settlement rows
**Depends on:** 7.5-T01

### 7.5-T03 — Define RevenueRecord JPA entity and repository  _(35 min)_
**Context:** The revenue_record table (T01, T02) is the backing store for all revenue reporting. Java entity: RevenueRecord with @Entity @Table(name='revenue_record'). BigDecimal fields for all amounts (never double). revenue_date is LocalDate. fee_share_pct is BigDecimal(6,4). Field fx_margin_usd is nullable (null for same-currency short-circuit where collection_ccy = settle_a_ccy = settle_b_ccy = payout_ccy). Repository must support: findByPartnerIdAndRevenueDateBetween, findBySchemeIdAndRevenueDateBetween, and a custom JPQL query for the revenue summary (aggregated sums grouped by partner, scheme, revenue_date).
**Steps:** Create RevenueRecord @Entity in com.gmepayplus.reporting.domain with all mapped columns from T01+T02; Add @ManyToOne lazy to Partner and QrScheme; @OneToOne to Transaction via txn_id; Create RevenueRecordRepository extends JpaRepository<RevenueRecord, Long> with findAllByPartnerIdAndRevenueDateBetween(Long partnerId, LocalDate from, LocalDate to); Add findAllBySchemeIdAndRevenueDateBetween and findAllByRevenueDateBetween; Ensure all BigDecimal columns use @Column(precision=20, scale=4) or matching precisions per spec
**Deliverable:** RevenueRecord.java entity and RevenueRecordRepository.java in com.gmepayplus.reporting
**Acceptance / logic checks:**
- Entity has field fxMarginUsd annotated @Column(nullable=true) — must not throw when null
- Entity has field feeSharePct annotated @Column(precision=6, scale=4)
- findAllByPartnerIdAndRevenueDateBetween returns correct subset when called with partnerId=2, from=2026-01-01, to=2026-01-31 in a test DB
- Saving a RevenueRecord with txn_id already mapped throws DataIntegrityViolationException (UNIQUE on txn_id)
- Entity maps settlement_model as enum or String with CHECK constraint enforced at DB level
**Depends on:** 7.5-T01, 7.5-T02

### 7.5-T04 — Implement RevenueRecordService.createFromTransaction()  _(40 min)_
**Context:** On every successful CommitTransaction, a revenue_record row must be created atomically before the commit completes. Inputs: the committed Transaction object (which carries all locked rate-pool values: collection_margin_usd, payout_margin_usd, service_charge, service_charge_ccy, rule.fee_share_pct, payment mode, direction). Rules: fx_margin_usd = collection_margin_usd + payout_margin_usd (NULL if transaction.is_same_ccy_shortcircuit = true). revenue_date = transaction.committed_at converted to KST date (UTC+9). settlement_model = NET if direction = DOMESTIC, GROSS if direction = INBOUND or OUTBOUND. estimated_fee_share_usd = NULL at this stage (confirmed later from settlement). service_charge_amount and service_charge_ccy copied from the transaction record.
**Steps:** Create RevenueRecordService in com.gmepayplus.reporting.service with method createFromTransaction(Transaction txn); Compute revenue_date = txn.committedAt.atZone(ZoneId.of('Asia/Seoul')).toLocalDate(); Compute fx_margin_usd: if txn.isSameCcyShortcircuit then null, else txn.collectionMarginUsd.add(txn.payoutMarginUsd); Derive settlement_model from txn.direction: DOMESTIC -> NET, INBOUND/OUTBOUND/HUB -> GROSS; Persist RevenueRecord via RevenueRecordRepository; method must be called within the same transaction as CommitTransaction (use @Transactional propagation MANDATORY)
**Deliverable:** RevenueRecordService.java with createFromTransaction() in com.gmepayplus.reporting.service
**Acceptance / logic checks:**
- Cross-border txn with collection_margin_usd=10.00, payout_margin_usd=10.00 produces revenue_record.fx_margin_usd=20.00
- Same-currency txn (is_same_ccy_shortcircuit=true) produces revenue_record.fx_margin_usd=NULL
- DOMESTIC direction produces settlement_model=NET; INBOUND produces GROSS
- revenue_date is the KST date of committed_at (e.g. committed_at=2026-01-15T15:30:00Z -> revenue_date=2026-01-16 in KST+9)
- Calling createFromTransaction outside a transaction context throws TransactionRequiredException (due to MANDATORY propagation)
**Depends on:** 7.5-T03

### 7.5-T05 — Implement updateConfirmedFeeShare() after settlement reconciliation  _(35 min)_
**Context:** After ZeroPay batch files ZP0062 (morning settlement result) or ZP0064 (afternoon settlement result) are received and matched, the revenue_record.confirmed_fee_share_krw column must be updated with the actual GME fee share. Fee share formula: GME net fee share = gross_payout_krw * merchant_fee_rate * gme_fee_share_pct (0.70 for ZeroPay). For NET settlement (domestic), this has already been deducted; for GROSS settlement (international), it is invoiced monthly. Method signature: updateConfirmedFeeShare(Long txnId, BigDecimal grossPayoutKrw, BigDecimal merchantFeeRate, BigDecimal gmeSharePct). This is called by the settlement reconciliation service (WBS 6.x) after batch processing.
**Steps:** Add updateConfirmedFeeShare(Long txnId, BigDecimal grossPayoutKrw, BigDecimal merchantFeeRate, BigDecimal gmeSharePct) to RevenueRecordService; Compute confirmedFeeShareKrw = grossPayoutKrw.multiply(merchantFeeRate).multiply(gmeSharePct) rounded to 0 decimal places (KRW has 0 decimal scale); Fetch revenue_record by txnId; throw NotFoundException if missing; set confirmed_fee_share_krw and persist; Log at INFO: txn_id, computed fee share, merchant_fee_rate, gme_fee_share_pct used; Annotate @Transactional; throw exception if txnId maps to a record where confirmed_fee_share_krw is already non-null (prevent double-update)
**Deliverable:** updateConfirmedFeeShare() method in RevenueRecordService.java
**Acceptance / logic checks:**
- grossPayoutKrw=10000, merchantFeeRate=0.0170, gmeSharePct=0.70 -> confirmedFeeShareKrw=119 (10000*0.017*0.70=119.0, rounded to 0dp = 119)
- grossPayoutKrw=100000, merchantFeeRate=0.0080, gmeSharePct=0.70 -> confirmedFeeShareKrw=560
- Calling updateConfirmedFeeShare with a txnId whose revenue_record already has confirmed_fee_share_krw set throws DuplicateUpdateException
- Calling with unknown txnId throws NotFoundException
- Update is @Transactional and rolls back if an exception occurs mid-method
**Depends on:** 7.5-T04

### 7.5-T06 — Define RevenueReportQuery DTO with all filter parameters  _(25 min)_
**Context:** The revenue report (PRD-07 §11.3.2) supports filtering by: period granularity (DAILY/WEEKLY/MONTHLY/CUSTOM), date range (from/to inclusive, LocalDate), partner_id (nullable -> all partners), scheme_id (nullable -> all schemes), direction (nullable -> all directions: INBOUND/OUTBOUND/DOMESTIC/HUB), revenue_stream (nullable -> both; or FX_MARGIN / SCHEME_FEE). Validation rules: from must not be after to; max range for non-async report = 366 days; from and to are required. This DTO is used by the report query service and serialised to/from the Admin API.
**Steps:** Create RevenueReportQuery record/class in com.gmepayplus.reporting.dto with fields: LocalDate from, LocalDate to, Long partnerId (nullable), Long schemeId (nullable), Direction direction (nullable), RevenueStream revenueStream (nullable), PeriodGranularity granularity; Create enums: PeriodGranularity { DAILY, WEEKLY, MONTHLY, CUSTOM } and RevenueStream { FX_MARGIN, SCHEME_FEE }; Add @Valid Bean Validation: @NotNull on from and to; custom validator that from <= to; max range 366 days; Add a helper method getEffectivePeriodLabel() that returns a String label for display (e.g. '2026-01' for monthly, '2026-01-15' for daily)
**Deliverable:** RevenueReportQuery.java DTO and supporting enums in com.gmepayplus.reporting.dto
**Acceptance / logic checks:**
- Query with from=2026-01-01, to=2025-12-31 fails Bean Validation (from after to)
- Query with from=2024-01-01, to=2026-01-01 (>366 days) fails Bean Validation
- Query with from=2026-01-01, to=2026-01-31, granularity=MONTHLY, partnerId=null passes validation
- All fields except from, to, granularity are nullable (verified by constructing with only mandatory fields)
- PeriodGranularity.values() = {DAILY, WEEKLY, MONTHLY, CUSTOM}

### 7.5-T07 — Define RevenueReportRow and RevenueReportResponse DTOs  _(30 min)_
**Context:** The revenue report (PRD-07 §11.3.2) returns rows with these columns per reporting period/partner/scheme: period (String label), partner_name, scheme_name, transaction_count (Long), gross_payout_krw (BigDecimal), merchant_fee_total_krw (BigDecimal), gme_scheme_share_krw (BigDecimal), collection_margin_usd (BigDecimal), payout_margin_usd (BigDecimal), total_fx_margin_usd (BigDecimal = collection + payout margin), service_charges_settle_a (BigDecimal), service_charge_ccy (String), total_revenue_krw_equiv (BigDecimal = scheme share + FX margin converted to KRW + service charges converted to KRW). The response wraps a list of rows plus summary totals and metadata (query params echoed back, generated_at timestamp).
**Steps:** Create RevenueReportRow with all listed fields; use BigDecimal for all monetary amounts; annotate with @JsonProperty for stable API names; Create RevenueReportResponse with fields: List<RevenueReportRow> rows, RevenueReportRow totals (sum row), RevenueReportQuery query (echo), LocalDateTime generatedAt; Ensure BigDecimal fields use proper serialization (no scientific notation); configure Jackson ObjectMapper with WRITE_BIGDECIMAL_AS_PLAIN; Add totalRevenue() convenience method on the totals row
**Deliverable:** RevenueReportRow.java and RevenueReportResponse.java in com.gmepayplus.reporting.dto
**Acceptance / logic checks:**
- RevenueReportRow has all 13 named fields; each monetary field is BigDecimal
- total_fx_margin_usd field value equals collection_margin_usd + payout_margin_usd when constructed in a test
- JSON serialisation of total_revenue_krw_equiv = 1234567.89 renders as '1234567.89' not '1.23456789E+6'
- RevenueReportResponse.getTotals() returns a row whose transaction_count equals the sum of all row transaction counts
- generatedAt is set to current UTC time at construction
**Depends on:** 7.5-T06

### 7.5-T08 — Implement RevenueReportQueryService with JPQL aggregation query  _(50 min)_
**Context:** The query service executes the aggregated revenue report. It must query revenue_record joined to transaction (for merchant_fee from the scheme) grouped by (period bucket, partner_id, scheme_id). Key aggregations: SUM(fx_margin_usd) as total_fx_margin, SUM(service_charge_amount) by ccy, SUM(confirmed_fee_share_krw) as gme_scheme_share, COUNT(*) as transaction_count. Period bucketing: DAILY = revenue_date, WEEKLY = date_trunc('week', revenue_date), MONTHLY = date_trunc('month', revenue_date). Filters map directly to WHERE clauses from RevenueReportQuery. For the FX_MARGIN stream filter: include only rows where fx_margin_usd IS NOT NULL; for SCHEME_FEE: include only rows where confirmed_fee_share_krw IS NOT NULL; no filter = all rows.
**Steps:** Create RevenueReportQueryService in com.gmepayplus.reporting.service; Build a JPQL or native SQL query with dynamic WHERE clause built from RevenueReportQuery fields (use JPA Criteria API or a QueryDSL predicate builder to avoid SQL injection); Implement period bucketing by appending the appropriate date_trunc expression based on granularity; Map result set rows to RevenueReportRow DTOs; compute totals row as in-memory sum after fetching; Apply revenue stream filter per spec: FX_MARGIN -> WHERE fx_margin_usd IS NOT NULL; SCHEME_FEE -> WHERE confirmed_fee_share_krw IS NOT NULL
**Deliverable:** RevenueReportQueryService.java in com.gmepayplus.reporting.service
**Acceptance / logic checks:**
- Query with partnerId=1, from=2026-01-01, to=2026-01-31, granularity=DAILY returns one row per day that has revenue records for that partner
- Query with revenueStream=FX_MARGIN excludes domestic (same-ccy) transactions which have fx_margin_usd=NULL
- Query with granularity=MONTHLY groups all January records into a single row with summed amounts
- SQL/JPQL parameters are bound (not string-concatenated) to prevent injection; verify with a partnerId containing a SQL fragment like '1 OR 1=1'
- Totals row transaction_count equals sum of all individual rows transaction_count
**Depends on:** 7.5-T03, 7.5-T07

### 7.5-T09 — Implement total_revenue_krw_equiv conversion in report service  _(40 min)_
**Context:** The revenue report column total_revenue_krw_equiv (PRD-07 §11.3.2) = GME scheme share (KRW) + FX margin (USD converted to KRW) + service charges (converted to KRW if not already in KRW). Conversion uses the treasury rate at the time of the report query (current rate from treasury_rate table: SELECT rate FROM treasury_rate WHERE ccy_pair='usd_krw' ORDER BY effective_at DESC LIMIT 1). For service charges already in KRW (settle_a_ccy='KRW'), no conversion needed. For service charges in USD (settle_a_ccy='USD'), multiply by usd_krw rate. This conversion is for display/reporting only; the underlying stored values are NOT modified. USD precision is 2dp for final display; KRW is 0dp.
**Steps:** Add a private computeTotalRevenueKrwEquiv(RevenueReportRow row, BigDecimal usdKrwRate) method to RevenueReportQueryService; Fetch current usd_krw rate once per report run (not per row); log the rate used in the report response metadata; Compute: fxMarginKrw = row.totalFxMarginUsd.multiply(usdKrwRate) rounded to 0dp; serviceChargeKrw = row.serviceChargeCcy=='KRW' ? row.serviceChargesSettleA : row.serviceChargesSettleA.multiply(usdKrwRate) rounded 0dp; total = row.gmeSchemeShareKrw + fxMarginKrw + serviceChargeKrw; Set row.totalRevenueKrwEquiv = total; Add usdKrwRateUsed and usdKrwRateEffectiveAt fields to RevenueReportResponse for auditability
**Deliverable:** computeTotalRevenueKrwEquiv() method wired into RevenueReportQueryService.java
**Acceptance / logic checks:**
- totalFxMarginUsd=100.00, usdKrwRate=1350.00, serviceChargesSettleA=5000 in KRW, gmeSchemeShareKrw=7000 -> totalRevenueKrwEquiv = 7000 + 135000 + 5000 = 147000
- totalFxMarginUsd=50.00, usdKrwRate=1350.00, serviceChargesSettleA=10.00 in USD -> serviceChargeKrw=13500, total = gmeSchemeShareKrw + 67500 + 13500
- If usd_krw treasury rate is missing, service throws ReportGenerationException with message 'USD/KRW rate not available'
- usdKrwRateUsed is included in response JSON for audit verification
- Same-ccy row with fx_margin_usd=NULL has fxMarginKrw=0 in total (null treated as 0)
**Depends on:** 7.5-T08

### 7.5-T10 — Add GET /admin/v1/reports/revenue endpoint handler  _(45 min)_
**Context:** Admin API endpoint for querying the revenue report. Role access: FINANCE_ANALYST, OPS_OPERATOR, SUPER_ADMIN (permission: 'Export Revenue Report' per PRD-07 §12.3). Request parameters map to RevenueReportQuery: from (ISO date, required), to (ISO date, required), granularity (DAILY/WEEKLY/MONTHLY/CUSTOM, default DAILY), partner_id (optional Long), scheme_id (optional Long), direction (optional String), revenue_stream (optional String: FX_MARGIN/SCHEME_FEE). Response: RevenueReportResponse JSON. Synchronous for up to 366-day range. The endpoint must log the export event to audit_log (category: 'data_export', entity_type: 'revenue_report', actor_id from JWT). ADMIN_VIEWER role does NOT have access (permission matrix PRD-07 §12.3).
**Steps:** Create RevenueReportController in com.gmepayplus.admin.controller with @GetMapping('/admin/v1/reports/revenue'); Bind query params to RevenueReportQuery; run @Valid validation; return 400 on violations; Enforce role check: require FINANCE_ANALYST, OPS_OPERATOR, or SUPER_ADMIN; return 403 for ADMIN_VIEWER or unauthenticated; Delegate to RevenueReportQueryService.query(RevenueReportQuery); Write audit log entry on every successful call: actor_id, event_type='revenue_report.exported', metadata including date range and filter params
**Deliverable:** RevenueReportController.java in com.gmepayplus.admin.controller
**Acceptance / logic checks:**
- GET with valid params and FINANCE_ANALYST JWT returns 200 with RevenueReportResponse JSON
- GET with ADMIN_VIEWER JWT returns 403
- GET with from=2026-01-31&to=2026-01-01 (from > to) returns 400 with field-level error
- GET with a range > 366 days returns 400 with message containing 'maximum range is 366 days'
- Audit log entry is written with correct actor_id and date range metadata on every 200 response
**Depends on:** 7.5-T08, 7.5-T09

### 7.5-T11 — Implement CSV export for revenue report (streaming, up to 100k rows)  _(45 min)_
**Context:** The revenue report must be exportable as CSV (PRD-07 §11.3.2, §15.8). The same filter parameters as the JSON report apply. CSV columns (in order): period, partner_name, scheme_name, transaction_count, gross_payout_krw, merchant_fee_total_krw, gme_scheme_share_krw, collection_margin_usd, payout_margin_usd, total_fx_margin_usd, service_charges_settle_a, service_charge_ccy, total_revenue_krw_equiv. The export must complete within 10 seconds for a 30-day period (PRD-07 §15.8). Use streaming (write rows to response OutputStream as they are fetched) to avoid OOM on large result sets. The endpoint is GET /admin/v1/reports/revenue/export?format=csv. Audit log entry required (same as JSON endpoint).
**Steps:** Add GET /admin/v1/reports/revenue/export to RevenueReportController with produces='text/csv'; Use Spring's StreamingResponseBody to stream rows directly to response without buffering entire result in memory; Set Content-Disposition header: attachment; filename=revenue_report_{from}_{to}.csv; Write CSV header row then data rows using a lightweight CSV writer (e.g. OpenCSV or manual comma-escaping); escape fields with commas or quotes; Enforce same role check (FINANCE_ANALYST, OPS_OPERATOR, SUPER_ADMIN) and write audit log entry
**Deliverable:** CSV export endpoint wired into RevenueReportController.java; streaming via StreamingResponseBody
**Acceptance / logic checks:**
- GET /admin/v1/reports/revenue/export?from=2026-01-01&to=2026-01-31 returns Content-Type: text/csv and Content-Disposition with filename revenue_report_2026-01-01_2026-01-31.csv
- CSV first row is the header row with exactly the 13 column names in specified order
- A field containing a comma (e.g. partner name 'GME, Remit') is wrapped in double-quotes in the CSV output
- ADMIN_VIEWER JWT returns 403
- An audit log entry with event_type='revenue_report.csv_exported' is created on each successful export
**Depends on:** 7.5-T10

### 7.5-T12 — Add DB indexes and query plan check for revenue report performance  _(35 min)_
**Context:** The revenue report query (T08) must return within 10 seconds for a 30-day period (PRD-07 §15.8). The primary query joins revenue_record with partner and qr_scheme, filters on revenue_date range and optionally partner_id/scheme_id, and groups by date bucket. Required indexes (beyond those in T01): partial index on revenue_record (revenue_date) WHERE fx_margin_usd IS NOT NULL (speeds FX_MARGIN stream filter); partial index WHERE confirmed_fee_share_krw IS NOT NULL (speeds SCHEME_FEE filter). Also verify the (partner_id, revenue_date) composite index from T01 is used by EXPLAIN ANALYZE.
**Steps:** Create Flyway migration V7_5_003__revenue_record_report_indexes.sql; Add: CREATE INDEX CONCURRENTLY idx_rr_fx_margin_notnull ON revenue_record(revenue_date) WHERE fx_margin_usd IS NOT NULL; Add: CREATE INDEX CONCURRENTLY idx_rr_fee_share_notnull ON revenue_record(revenue_date) WHERE confirmed_fee_share_krw IS NOT NULL; Run EXPLAIN (ANALYZE, BUFFERS) on the representative report query for 30-day range with partnerId filter; paste plan into a code comment in the migration file; Assert query plan uses at least one index scan (not sequential scan) for the 30-day/partner filter scenario
**Deliverable:** V7_5_003__revenue_record_report_indexes.sql Flyway migration
**Acceptance / logic checks:**
- Migration applies without error; \d revenue_record shows 5 indexes total (PK + 2 from T01 + 2 new)
- EXPLAIN on WHERE partner_id=1 AND revenue_date BETWEEN '2026-01-01' AND '2026-01-31' shows Index Scan on idx_rr_partner_date
- EXPLAIN on WHERE fx_margin_usd IS NOT NULL AND revenue_date BETWEEN '2026-01-01' AND '2026-01-31' shows Index Scan on idx_rr_fx_margin_notnull
- EXPLAIN on WHERE confirmed_fee_share_krw IS NOT NULL shows Index Scan on idx_rr_fee_share_notnull
- No Seq Scan on revenue_record for any of the above queries on a dataset > 10k rows
**Depends on:** 7.5-T01, 7.5-T08

### 7.5-T13 — Implement revenue report Settlement Batch Status view data query  _(45 min)_
**Context:** PRD-07 §11.2.2 describes a Settlement Batch Status table with columns: settlement_date, transaction_count, gross_payout_krw, gme_fee_share_krw (70% of net merchant fee), net_settlement_krw (domestic net model), ZP0011/ZP0061/ZP0062 statuses, reconciliation_status. This data is sourced from settlement_batch (one row per file per date) and revenue_record (aggregated). The query service must return a SettlementBatchSummary DTO per settlement_date. Domestic NET: net_settlement = gross_payout - gme_fee_share. International GROSS: net_settlement = gross_payout (fee invoiced monthly separately).
**Steps:** Create SettlementBatchSummaryDTO with fields: settlementDate (LocalDate), schemeCode (String), transactionCount (Long), grossPayoutKrw (BigDecimal), gmeFeeShareKrw (BigDecimal), netSettlementKrw (BigDecimal), zp0011Status, zp0061Status, zp0062Status (all String), reconciliationStatus (String), actions (List<String>); Create SettlementBatchQueryService.getSettlementBatchSummaries(LocalDate from, LocalDate to, Long schemeId) that joins settlement_batch and aggregates revenue_record; Compute netSettlementKrw: if settlement_model=NET -> grossPayoutKrw - gmeFeeShareKrw; if GROSS -> grossPayoutKrw; Return results ordered by settlementDate DESC
**Deliverable:** SettlementBatchSummaryDTO.java and SettlementBatchQueryService.java in com.gmepayplus.reporting.service
**Acceptance / logic checks:**
- For a domestic (NET) batch with grossPayoutKrw=1000000, gmeFeeShareKrw=8000: netSettlementKrw=992000
- For an international (GROSS) batch with grossPayoutKrw=500000: netSettlementKrw=500000 regardless of fee share
- Results are ordered by settlementDate DESC
- Query for from=2026-01-01 to=2026-01-31 does not include rows with settlementDate outside that range
- Missing ZP0062 result shows zp0062Status='Not yet received'
**Depends on:** 7.5-T03, 7.5-T05

### 7.5-T14 — Add GET /admin/v1/reports/settlement-batches endpoint  _(40 min)_
**Context:** Admin API endpoint for the Settlement & Revenue module's batch status table (PRD-07 §11.2.2). Role access: FINANCE_ANALYST, OPS_OPERATOR, SUPER_ADMIN. Parameters: from (ISO date, required), to (ISO date, required, max 90-day range), scheme_id (optional). Response: list of SettlementBatchSummaryDTO ordered by settlementDate DESC. Pagination: 50 rows per page (page/size params). The endpoint also exposes an action for flagging a discrepancy: PUT /admin/v1/reports/settlement-batches/{batchId}/flag-exception requires OPS_OPERATOR or SUPER_ADMIN role.
**Steps:** Add GET /admin/v1/reports/settlement-batches to a new SettlementBatchController in com.gmepayplus.admin.controller; Bind from/to/scheme_id/page/size params; validate max 90-day range; return 400 on violation; Delegate to SettlementBatchQueryService.getSettlementBatchSummaries(); wrap in Page response with pagination metadata; Add PUT /admin/v1/reports/settlement-batches/{batchId}/flag-exception (body: {reason: String}); call reconciliation service to create exception record; require OPS_OPERATOR or SUPER_ADMIN; Write audit log on flag-exception action
**Deliverable:** SettlementBatchController.java in com.gmepayplus.admin.controller
**Acceptance / logic checks:**
- GET with valid params and FINANCE_ANALYST JWT returns 200 with paginated list
- GET with ADMIN_VIEWER JWT returns 403
- GET with from/to range > 90 days returns 400
- PUT flag-exception with FINANCE_ANALYST JWT returns 403 (Finance cannot flag)
- PUT flag-exception with OPS_OPERATOR JWT returns 200 and creates reconciliation_item with resolution_status=ESCALATED
**Depends on:** 7.5-T13

### 7.5-T15 — Implement merchant fee schedule reference view in Settlement module  _(30 min)_
**Context:** PRD-07 §11.3.3: the Settlement & Revenue module displays the merchant fee schedule configured per scheme as a reference for revenue calculations. Data comes from the qr_scheme merchant fee table (stored as JSON in qr_scheme.merchant_fee_table or as a structured table per scheme config in WBS 4.x). The fee schedule is READ ONLY from this module (changes go through Schemes module). The view shows: merchant_type, domestic_partner_rate%, cross_border_partner_rate%. ZeroPay reference values: General=0.80%/1.70%, Large Franchise=operator-configured. FINANCE_ANALYST and above can view.
**Steps:** Create GET /admin/v1/reports/settlement-batches/fee-schedule?scheme_id={id} endpoint in SettlementBatchController; Query qr_scheme merchant fee table for the given scheme_id; return as MerchantFeeScheduleDTO list (merchant_type, domesticRate, crossBorderRate); Require FINANCE_ANALYST, OPS_OPERATOR, or SUPER_ADMIN; return 403 otherwise; Return 404 if scheme_id not found; Document that this is read-only; do not expose any PUT/POST/DELETE on this path
**Deliverable:** GET /admin/v1/reports/settlement-batches/fee-schedule endpoint in SettlementBatchController.java
**Acceptance / logic checks:**
- GET with scheme_id for ZeroPay returns merchant fee rows including General at domesticRate=0.0080 and crossBorderRate=0.0170
- GET with ADMIN_VIEWER JWT returns 403
- GET with unknown scheme_id returns 404
- Response is read-only: no PUT/POST/DELETE method registered on this path (405 if attempted)
- domesticRate and crossBorderRate fields are BigDecimal with scale 4 (e.g. 0.0080 not 0.8)
**Depends on:** 7.5-T14

### 7.5-T16 — Unit tests: RevenueRecordService.createFromTransaction() edge cases  _(40 min)_
**Context:** Tests for T04 logic. Key test vectors: (1) Cross-border INBOUND with collection_margin_usd=10.50, payout_margin_usd=9.50 -> fx_margin_usd=20.00, settlement_model=GROSS. (2) DOMESTIC same-currency (is_same_ccy_shortcircuit=true) -> fx_margin_usd=NULL, settlement_model=NET. (3) Timezone edge case: committed_at=2026-01-15T15:00:00Z -> revenue_date=2026-01-16 (KST is UTC+9). (4) committed_at=2026-01-15T14:59:00Z -> revenue_date=2026-01-15. (5) Missing rule.fee_share_pct -> exception before persist. All tests use mocked RevenueRecordRepository (no DB).
**Steps:** Create RevenueRecordServiceTest in src/test/java; Test case 1: cross-border INBOUND transaction -> assert fx_margin_usd=20.00, settlement_model=GROSS; Test case 2: domestic same-ccy transaction -> assert fx_margin_usd=null, settlement_model=NET; Test case 3: timezone edge case committed_at=2026-01-15T15:00:00Z -> assert revenue_date=2026-01-16; Test case 4: committed_at=2026-01-15T14:59:00Z -> assert revenue_date=2026-01-15; Test case 5: transaction with null fee_share_pct on rule -> assert IllegalArgumentException or DataIntegrityViolationException before save
**Deliverable:** RevenueRecordServiceTest.java with 5 test methods, all passing
**Acceptance / logic checks:**
- Test 1 passes: fx_margin_usd=20.00 (BigDecimal equality, scale-independent)
- Test 2 passes: fx_margin_usd is null
- Test 3 passes: revenue_date = LocalDate.of(2026,1,16)
- Test 4 passes: revenue_date = LocalDate.of(2026,1,15)
- Test 5 passes: exception is thrown before repository.save() is called (mock verify zero interactions after exception)
**Depends on:** 7.5-T04

### 7.5-T17 — Unit tests: updateConfirmedFeeShare() calculation vectors  _(35 min)_
**Context:** Tests for T05 logic. Key vectors: (1) grossPayoutKrw=10000, merchantFeeRate=0.0170, gmeSharePct=0.70 -> confirmedFeeShareKrw=119. (2) grossPayoutKrw=100000, merchantFeeRate=0.0080, gmeSharePct=0.70 -> 560. (3) grossPayoutKrw=50000, merchantFeeRate=0.0170, gmeSharePct=0.70 -> 595 (50000*0.017*0.7=595.0). (4) Already-set confirmed_fee_share_krw -> DuplicateUpdateException. (5) Unknown txnId -> NotFoundException. Rounding: KRW is 0 decimal places, use HALF_UP. All tests use mocked repository.
**Steps:** Create UpdateConfirmedFeeShareTest in src/test/java; Test vector 1: 10000 * 0.0170 * 0.70 = 119; Test vector 2: 100000 * 0.0080 * 0.70 = 560; Test vector 3: 50000 * 0.0170 * 0.70 = 595; Test already-set guard: revenue_record with confirmed_fee_share_krw=100 -> DuplicateUpdateException; Test unknown txnId -> NotFoundException
**Deliverable:** UpdateConfirmedFeeShareTest.java with 5 test methods, all passing
**Acceptance / logic checks:**
- Vector 1 passes: confirmedFeeShareKrw = 119 (BigDecimal with scale 0)
- Vector 2 passes: 560
- Vector 3 passes: 595
- DuplicateUpdateException test passes; repository.save() is NOT called (mock verify)
- NotFoundException test passes when repository.findByTxnId() returns empty
**Depends on:** 7.5-T05

### 7.5-T18 — Unit tests: RevenueReportQueryService aggregation and filtering  _(50 min)_
**Context:** Tests for T08/T09. Key test vectors: (1) 3 revenue_record rows for partner_id=1, dates 2026-01-01/02/03 with fx_margin_usd=10.00, 20.00, 30.00 each, service_charge=500 KRW each, confirmed_fee_share_krw=100 each -> DAILY report returns 3 rows; MONTHLY returns 1 row with totals: fx_margin_usd=60.00, service_charges=1500, gme_scheme_share=300. (2) RevenueStream=FX_MARGIN filter excludes same-ccy row (fx_margin_usd=NULL). (3) Total revenue KRW equiv: usdKrwRate=1350, total_fx_margin=60.00 -> fxMarginKrw=81000; service 1500 KRW; scheme 300 KRW -> total=82800. All tests use an in-memory H2 or test-slice with preloaded fixtures.
**Steps:** Create RevenueReportQueryServiceTest using @DataJpaTest or a service test with preloaded H2 data; Fixture: insert 3 revenue_record rows (partner_id=1, dates Jan 1/2/3 2026) + 1 same-ccy row (fx_margin_usd=null); Test DAILY granularity: expect 3 rows each with fx_margin_usd=10/20/30; Test MONTHLY granularity: expect 1 row with fx_margin_usd=60.00, service_charges=1500, confirmed_fee_share=300; Test RevenueStream=FX_MARGIN filter: expect 3 rows (same-ccy row excluded); Test total revenue KRW: usdKrwRate=1350, expect totalRevenueKrwEquiv=81000+1500+300=82800
**Deliverable:** RevenueReportQueryServiceTest.java with 5 test methods, all passing
**Acceptance / logic checks:**
- DAILY test: 3 rows returned with correct per-day values
- MONTHLY test: 1 row with fx_margin_usd=60.00 (sum of 10+20+30)
- FX_MARGIN filter: exactly 3 rows (4th same-ccy row excluded)
- Total revenue KRW test: 82800 matches formula (60*1350=81000 + 1500 service + 300 scheme share)
- SCHEME_FEE filter test (bonus): excludes rows where confirmed_fee_share_krw IS NULL
**Depends on:** 7.5-T08, 7.5-T09

### 7.5-T19 — Unit tests: CSV export format and column ordering  _(40 min)_
**Context:** Tests for T11 CSV streaming export. Verify: (1) header row has exactly 13 columns in correct order (period, partner_name, scheme_name, transaction_count, gross_payout_krw, merchant_fee_total_krw, gme_scheme_share_krw, collection_margin_usd, payout_margin_usd, total_fx_margin_usd, service_charges_settle_a, service_charge_ccy, total_revenue_krw_equiv). (2) Data row values are correct for a known fixture row. (3) A partner_name containing a comma is CSV-quoted. (4) BigDecimal values do not use scientific notation. (5) Response Content-Type is text/csv and Content-Disposition contains 'attachment'.
**Steps:** Create RevenueReportCsvExportTest using @SpringBootTest with MockMvc; Inject a fixture RevenueReportResponse with one known row (all field values set); Call GET /admin/v1/reports/revenue/export?from=2026-01-01&to=2026-01-31 with FINANCE_ANALYST auth; Parse the CSV response body using a CSV reader; Assert header row column names; assert data row values; assert comma-containing name is quoted
**Deliverable:** RevenueReportCsvExportTest.java with 5 test methods, all passing
**Acceptance / logic checks:**
- Header row: split(',') returns exactly 13 tokens matching the specified column names in order
- Data row: transaction_count column parses to Long correctly
- Partner name 'GME, Test' appears as '"GME, Test"' in the CSV output
- total_revenue_krw_equiv value 1234567.89 appears as '1234567.89' not in scientific notation
- Content-Disposition header contains 'attachment; filename=revenue_report_2026-01-01_2026-01-31.csv'
**Depends on:** 7.5-T11

### 7.5-T20 — Unit tests: revenue report endpoint RBAC enforcement  _(35 min)_
**Context:** Tests for T10/T11 access control. PRD-07 §12.3 permission matrix: Export Revenue Report = SUPER_ADMIN yes, OPS_OPERATOR yes, FINANCE_ANALYST yes, ADMIN_VIEWER no. Tests must cover all four roles for both the JSON endpoint (GET /admin/v1/reports/revenue) and the CSV export endpoint (GET /admin/v1/reports/revenue/export). Additionally: unauthenticated requests return 401; valid FINANCE_ANALYST token with expired JWT returns 401; flag-exception PUT with FINANCE_ANALYST returns 403.
**Steps:** Create RevenueReportRbacTest using @SpringBootTest with MockMvc and per-test JWT fixtures for each role; Test SUPER_ADMIN on JSON endpoint -> 200; Test OPS_OPERATOR on JSON endpoint -> 200; Test FINANCE_ANALYST on JSON endpoint -> 200; Test ADMIN_VIEWER on JSON endpoint -> 403; Test unauthenticated (no token) on JSON endpoint -> 401; Repeat ADMIN_VIEWER and unauthenticated tests for CSV export endpoint
**Deliverable:** RevenueReportRbacTest.java with 7 test methods, all passing
**Acceptance / logic checks:**
- SUPER_ADMIN returns 200 on both JSON and CSV endpoints
- FINANCE_ANALYST returns 200 on both endpoints
- ADMIN_VIEWER returns 403 on both endpoints
- No auth token returns 401 on both endpoints
- OPS_OPERATOR returns 200 on JSON endpoint
- Expired JWT returns 401 (not 403)
- FINANCE_ANALYST PUT /flag-exception returns 403
**Depends on:** 7.5-T10, 7.5-T11

### 7.5-T21 — Implement revenue report audit log entries for CSV and JSON exports  _(40 min)_
**Context:** Every successful export (both JSON view and CSV download) must create an audit_log entry per PRD-07 §13.2 (category: 'Data exports'). The audit_log table schema: id BIGINT PK, actor_id VARCHAR(120), actor_type VARCHAR(20) (OPERATOR), action VARCHAR(20) (EXPORT), entity_type VARCHAR(50) ('revenue_report'), entity_id BIGINT (NULL for reports), before_value JSONB (NULL), after_value JSONB (query parameters as JSON), occurred_at TIMESTAMPTZ, ip_address INET, request_id VARCHAR(64). The AuditLogService.logExport() method must be called AFTER the response is committed (do not block the response on audit write failure — use async best-effort logging with error alert on failure).
**Steps:** Create or extend AuditLogService with method logRevenueReportExport(String actorId, String actorIp, RevenueReportQuery query, String exportFormat, String requestId); Serialize query to JSONB and store as after_value; set entity_type='revenue_report', action='EXPORT'; Call this method from RevenueReportController after successful response dispatch (use @Async or a post-commit hook); Ensure audit write failure (DB down) logs an ERROR but does NOT propagate to the caller (response already sent); Add integration test verifying audit record is created after a successful export call
**Deliverable:** AuditLogService.logRevenueReportExport() method wired into RevenueReportController.java
**Acceptance / logic checks:**
- After a successful JSON report call, exactly one audit_log row with action=EXPORT, entity_type=revenue_report exists for that actor
- after_value JSONB contains from, to, and granularity from the query params
- actor_ip is captured from the HTTP request X-Forwarded-For or REMOTE_ADDR
- A forced DB failure during audit write does NOT return 500 to the caller (response was already 200)
- Audit log entry for CSV export has exportFormat='CSV' in after_value JSONB
**Depends on:** 7.5-T10, 7.5-T11

### 7.5-T22 — Implement RevenueRecord population hook in CommitTransaction flow  _(50 min)_
**Context:** The createFromTransaction() method (T04) must be called as part of the existing CommitTransaction service, in the same DB transaction. The CommitTransaction flow (implemented in WBS 4.x or 5.x) currently: locks the rate quote, deducts prefunding (OVERSEAS), writes the transaction row, fires events. Revenue record creation must be added AFTER the transaction row is written but BEFORE the transaction commits. If createFromTransaction() throws, the entire commit must roll back (no orphan transactions without revenue records). The wiring point is in TransactionCommitService or equivalent.
**Steps:** Locate TransactionCommitService (or equivalent) in com.gmepayplus.payment.service; Inject RevenueRecordService and call createFromTransaction(transaction) immediately after transaction.persist(); Ensure the call is within the same @Transactional boundary (MANDATORY propagation on createFromTransaction already enforces this); Add a compensating check: after both saves, assert revenue_record.txnId == transaction.id before returning to caller; Write an integration test: simulate a DB failure in createFromTransaction and verify the transaction row is also rolled back (not committed without revenue record)
**Deliverable:** CommitTransaction flow updated to call RevenueRecordService.createFromTransaction(); integration test verifying atomicity
**Acceptance / logic checks:**
- After a successful CommitTransaction call, exactly one revenue_record row exists with txn_id matching the new transaction
- If createFromTransaction throws RuntimeException, the transaction row is NOT persisted (rollback verified by querying transaction table in the same test)
- Duplicate CommitTransaction call (idempotency re-play) does NOT create a second revenue_record (UNIQUE constraint on txn_id)
- Revenue record revenue_date matches the KST date of committed_at
- The performance overhead of adding createFromTransaction is < 20ms under normal load (assert in a timing test against a mock repo)
**Depends on:** 7.5-T04, 7.5-T03

### 7.5-T23 — Add revenue_record_summary materialized view for dashboard KPIs  _(50 min)_
**Context:** The Admin System dashboard (PRD-07 §3) shows real-time KPIs including revenue. To avoid hitting the raw revenue_record table on every dashboard load, create a PostgreSQL materialized view revenue_record_daily_summary that pre-aggregates: revenue_date, partner_id, scheme_id, SUM(fx_margin_usd) as total_fx_margin_usd, SUM(service_charge_amount) as total_service_charge, SUM(confirmed_fee_share_krw) as total_confirmed_fee_share_krw, COUNT(*) as txn_count. The view is refreshed by a scheduled job every 15 minutes. The revenue report query service can use this view for DAILY/MONTHLY granularity when the query covers a historical (fully settled) period; for today's date it must fall back to the raw table.
**Steps:** Create Flyway migration V7_5_004__revenue_record_daily_summary_view.sql; CREATE MATERIALIZED VIEW revenue_record_daily_summary AS SELECT revenue_date, partner_id, scheme_id, SUM(fx_margin_usd), SUM(service_charge_amount), SUM(confirmed_fee_share_krw), COUNT(*) FROM revenue_record GROUP BY revenue_date, partner_id, scheme_id; Add UNIQUE INDEX on (revenue_date, partner_id, scheme_id) to support REFRESH CONCURRENTLY; Add a scheduler in RevenueReportScheduler @Scheduled(cron='0 */15 * * * *') that calls REFRESH MATERIALIZED VIEW CONCURRENTLY revenue_record_daily_summary; Add fallback logic to RevenueReportQueryService: use materialized view for dates < today (KST); raw table for today
**Deliverable:** V7_5_004__revenue_record_daily_summary_view.sql migration + RevenueReportScheduler.java
**Acceptance / logic checks:**
- Materialized view exists and SELECT returns grouped rows after initial refresh
- REFRESH MATERIALIZED VIEW CONCURRENTLY executes without locking the view for reads
- Dashboard query using the view for yesterday's date is faster than raw table query by >= 50% on a 100k-row dataset
- For revenue_date = today (KST), query falls back to raw revenue_record table (verify via query plan or instrumentation log)
- Scheduler fires every 15 minutes (test with @Scheduled test support or a fixed clock stub)
**Depends on:** 7.5-T01, 7.5-T08

### 7.5-T24 — Add Merchant Fee Schedule display query for Settlement module  _(35 min)_
**Context:** PRD-07 §11.3.3: the Admin System displays the merchant fee schedule per scheme as a read-only reference. This requires a query on qr_scheme.merchant_fee_table (or a structured fee table entity from scheme config). The fee rates vary by merchant_type and payment origin (domestic vs cross-border). The display shows: scheme_name, merchant_type code, domestic_rate%, cross_border_rate%, effective_from. For ZeroPay: General 0.80%/1.70%, Large Franchise (operator-configured). This data is also used by T05 (updateConfirmedFeeShare) to look up the correct merchant_fee_rate at revenue confirmation time. Query service method: getMerchantFeeRate(Long schemeId, String merchantType, String settlementModel) -> BigDecimal.
**Steps:** Create MerchantFeeQueryService in com.gmepayplus.reporting.service; Implement getMerchantFeeRate(Long schemeId, String merchantType, String settlementModel): query qr_scheme merchant fee rows; match on schemeId + merchantType; return domestic_rate if settlementModel=NET, cross_border_rate if GROSS; Throw FeeRateNotFoundException if no matching row found (log as WARN for Ops visibility); Wire getMerchantFeeRate into updateConfirmedFeeShare (T05) to supply the merchantFeeRate parameter; Add unit test: ZeroPay + GENERAL + NET -> 0.0080; ZeroPay + GENERAL + GROSS -> 0.0170
**Deliverable:** MerchantFeeQueryService.java in com.gmepayplus.reporting.service + unit tests
**Acceptance / logic checks:**
- getMerchantFeeRate(zeropayId, 'GENERAL', 'NET') returns 0.0080
- getMerchantFeeRate(zeropayId, 'GENERAL', 'GROSS') returns 0.0170
- getMerchantFeeRate with unknown merchantType throws FeeRateNotFoundException
- getMerchantFeeRate with unknown schemeId throws FeeRateNotFoundException
- Unit test coverage >= 80% on MerchantFeeQueryService
**Depends on:** 7.5-T05

### 7.5-T25 — Integration test: end-to-end revenue record creation and report retrieval  _(55 min)_
**Context:** End-to-end integration test covering the full WBS 7.5 revenue pipeline: (1) Commit a cross-border INBOUND transaction (collection_margin_usd=10.00, payout_margin_usd=10.00, service_charge=500 KRW, direction=INBOUND). (2) Assert revenue_record is created with fx_margin_usd=20.00, settlement_model=GROSS. (3) Call updateConfirmedFeeShare with grossPayoutKrw=10000, merchantFeeRate=0.0170, gmeSharePct=0.70 -> confirmedFeeShareKrw=119. (4) Call GET /admin/v1/reports/revenue?from={today}&to={today} with FINANCE_ANALYST JWT. (5) Assert response contains 1 row with totalFxMarginUsd=20.00, serviceChargesSettleA=500, gmeSchemeShareKrw=119, totalRevenueKrwEquiv=20.00*1350+500+119=27619 (using usdKrwRate=1350).
**Steps:** Create RevenueEndToEndIntegrationTest using @SpringBootTest with a real embedded PostgreSQL (TestContainers); Step 1: call CommitTransaction service with mocked scheme step (skip actual ZeroPay call); verify transaction persisted; Step 2: query revenue_record table directly; assert fx_margin_usd=20.00, settlement_model=GROSS; Step 3: call updateConfirmedFeeShare; assert confirmedFeeShareKrw=119; Step 4: mock treasury_rate table with usd_krw=1350.00; call GET /admin/v1/reports/revenue; Step 5: assert response JSON matches expected values (tolerance 0.01 USD for FX conversion)
**Deliverable:** RevenueEndToEndIntegrationTest.java with 1 test method (5 sequential steps), passing
**Acceptance / logic checks:**
- Step 2: revenue_record.fx_margin_usd = 20.00 (exact)
- Step 3: revenue_record.confirmed_fee_share_krw = 119 (exact, KRW 0dp)
- Step 4: API returns 200 with 1 row in the report
- Step 5: totalRevenueKrwEquiv = 27619 (20*1350 + 500 + 119 = 27619; tolerance 1 KRW for rounding)
- Full test runs in < 30 seconds on CI (TestContainers startup included)
**Depends on:** 7.5-T22, 7.5-T09, 7.5-T10

### 7.5-T26 — Add OpenAPI documentation for revenue report endpoints  _(35 min)_
**Context:** The Admin API uses OpenAPI 3 (Springdoc or equivalent). The revenue report endpoints (T10, T11, T14, T15) must have complete OpenAPI annotations: @Operation(summary, description), @Parameter for each query param (name, description, required, example), @ApiResponse for 200/400/401/403, and example schemas for RevenueReportResponse and SettlementBatchSummaryDTO. The API docs are the primary reference for the Admin UI team consuming these endpoints. Each parameter must document its constraints (e.g. 'from: ISO-8601 date, required; max range 366 days when combined with to').
**Steps:** Add Springdoc @Operation annotations to GET /admin/v1/reports/revenue and /admin/v1/reports/revenue/export; Add @Parameter(description, example, required) to all query params: from, to, granularity, partner_id, scheme_id, direction, revenue_stream; Add @ApiResponse(responseCode='400', description='Validation error: from must be before to; max range 366 days') and 403/401 responses; Add @Schema examples to RevenueReportRow and SettlementBatchSummaryDTO showing realistic data values; Verify docs render correctly at /v3/api-docs and /swagger-ui.html with all new endpoints visible
**Deliverable:** OpenAPI annotations on all 4 revenue/settlement endpoints; docs verified at /swagger-ui.html
**Acceptance / logic checks:**
- GET /v3/api-docs returns JSON with paths /admin/v1/reports/revenue, /admin/v1/reports/revenue/export, /admin/v1/reports/settlement-batches, /admin/v1/reports/settlement-batches/fee-schedule
- Each endpoint has at least one @ApiResponse for 200, 400, 401, and 403
- RevenueReportRow schema in OpenAPI has all 13 field names
- The 'from' parameter description mentions 'ISO-8601' and 'required'
- Swagger UI renders without errors and the Try-it-out works for GET /admin/v1/reports/revenue
**Depends on:** 7.5-T10, 7.5-T11, 7.5-T14, 7.5-T15


## WBS 13.7 — BOK FX reporting capture
### 13.7-T01 — Add offer_rate_coll and cross_rate columns to transaction table  _(20 min)_
**Context:** The transaction table is the central fact table. Two derived BOK columns must be stored as immutable locked values: offer_rate_coll DECIMAL(20,8) = send_amount / (collection_usd - collection_margin_usd) and cross_rate DECIMAL(20,8) = target_payout / send_amount. Both are NULL for same-currency transactions (is_same_ccy_shortcircuit=TRUE). These are permanently set at CommitTransaction; no UPDATE is permitted after commit. FX1015 field #14 = offer_rate_coll.
**Steps:** Create a Flyway migration V<next>__add_bok_derived_rates_to_transaction.sql; Add column offer_rate_coll DECIMAL(20,8) NULL to transaction table; Add column cross_rate DECIMAL(20,8) NULL to transaction table; Add a database comment on offer_rate_coll: BOK FX1015 field 14 - locked at commit; Run migration against dev DB and verify schema with \d transaction
**Deliverable:** Flyway migration file adding offer_rate_coll and cross_rate to the transaction table
**Acceptance / logic checks:**
- Migration applies cleanly; both columns exist with type DECIMAL(20,8) and are nullable
- Existing rows have NULL in both new columns after migration
- Column comment on offer_rate_coll reads BOK FX1015 field 14 or equivalent
- Re-running migration is a no-op (idempotent via IF NOT EXISTS or Flyway checksum)

### 13.7-T02 — Add offer_rate_coll and cross_rate to rate_quote table  _(20 min)_
**Context:** rate_quote holds the computed quote before commit. offer_rate_coll DECIMAL(20,8) = send_amount / (collection_usd - collection_margin_usd); cross_rate DECIMAL(20,8) = target_payout / send_amount. Both NULL when is_same_ccy_shortcircuit is TRUE. These values are computed by the rate engine and stored in rate_quote so they can be copied to transaction at commit (rate-lock step).
**Steps:** Create Flyway migration V<next>__add_bok_derived_rates_to_rate_quote.sql; Add offer_rate_coll DECIMAL(20,8) NULL and cross_rate DECIMAL(20,8) NULL to rate_quote; Run migration and confirm columns present
**Deliverable:** Flyway migration file adding offer_rate_coll and cross_rate to rate_quote
**Acceptance / logic checks:**
- Both columns exist with DECIMAL(20,8) nullable type
- Migration is idempotent
- Existing rate_quote rows have NULL in both columns after migration

### 13.7-T03 — Add bok_report_record table migration  _(35 min)_
**Context:** bok_report_record stores one row per cross-border transaction requiring BOK FX reporting. Domestic (same-currency) transactions are exempt. Schema per DAT-03 section 8.1: id BIGINT PK, txn_id BIGINT FK->transaction, report_type VARCHAR(10) CHECK IN ('FX1014','FX1015'), report_date DATE NOT NULL, partner_id BIGINT FK->partner, collection_amount DECIMAL(20,4), collection_ccy CHAR(3), payout_amount DECIMAL(20,4), payout_ccy CHAR(3), offer_rate_coll DECIMAL(20,8) (BOK FX1015 field #14), usd_amount DECIMAL(20,4) (intermediary USD), submission_status VARCHAR(20) CHECK IN ('PENDING','SUBMITTED','CONFIRMED','FAILED') DEFAULT 'PENDING', submitted_at TIMESTAMPTZ NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(). Note: schema is provisional pending OI-03 confirmation; additional columns will be added later without requiring data loss.
**Steps:** Create Flyway migration V<next>__create_bok_report_record.sql; Define all columns per spec above with correct types and constraints; Add FK constraints to transaction and partner tables; Add index on txn_id and on (report_type, submission_status, report_date) for query performance; Apply migration and verify table structure
**Deliverable:** Flyway migration creating the bok_report_record table
**Acceptance / logic checks:**
- Table exists with all columns and correct types
- CHECK constraint on report_type rejects values other than FX1014 and FX1015
- CHECK constraint on submission_status rejects invalid values
- FK to transaction table enforces referential integrity
- Index on (report_type, submission_status, report_date) exists

### 13.7-T04 — Define RateQuoteResult domain object with offer_rate_coll and cross_rate fields  _(25 min)_
**Context:** The rate engine returns a RateQuoteResult value object. It must include all 5-step pool values plus the two derived BOK fields: offer_rate_coll (BigDecimal, scale 8, BOK FX1015 #14) and cross_rate (BigDecimal, scale 8). For same-currency short-circuit, both fields are null. The object is immutable (no setters). Formula: offer_rate_coll = send_amount / (collection_usd - collection_margin_usd); cross_rate = target_payout / send_amount.
**Steps:** Locate or create the RateQuoteResult class/record in the rate-engine module; Add fields BigDecimal offerRateColl (nullable) and BigDecimal crossRate (nullable); Annotate or document: offerRateColl maps to BOK FX1015 field #14; Ensure the object is immutable (final fields or Java record); Update any existing builder/factory with the new fields
**Deliverable:** Updated RateQuoteResult domain class with offerRateColl and crossRate fields
**Acceptance / logic checks:**
- Class compiles with offerRateColl and crossRate as nullable BigDecimal fields
- No public setters exist on the class (immutable)
- Javadoc or comment on offerRateColl states: BOK FX1015 field #14 = send_amount / (collection_usd - collection_margin_usd)
- Existing unit tests for RateQuoteResult still pass after change
**Depends on:** 13.7-T01, 13.7-T02

### 13.7-T05 — Implement offer_rate_coll and cross_rate computation in rate engine (cross-border path)  _(40 min)_
**Context:** After the 5-step RECEIVE-mode calculation, the rate engine must compute two derived BOK fields using fixed-precision decimal arithmetic (BigDecimal, no floating point). Formula: offer_rate_coll = send_amount / (collection_usd - collection_margin_usd), scale 8, HALF_UP rounding. cross_rate = target_payout / send_amount, scale 8, HALF_UP. These are computed only when is_same_ccy_shortcircuit is FALSE. Example: send_amount=36.9714, collection_usd=36.9714, collection_margin_usd=0.3697 -> offer_rate_coll = 36.9714 / (36.9714-0.3697) = 36.9714 / 36.6017 = 1.01010103 (approx). Pool identity check must pass before computing: abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) <= 0.01 USD, else throw POOL_IDENTITY_FAILURE.
**Steps:** In the rate engine compute method, after step 4 (send_amount) and step 5 (collection_amount), add the derived BOK block; Compute collection_net_usd = collection_usd.subtract(collection_margin_usd); Compute offerRateColl = send_amount.divide(collection_net_usd, 8, HALF_UP); Compute crossRate = target_payout.divide(send_amount, 8, HALF_UP); Assign both fields in the returned RateQuoteResult
**Deliverable:** Rate engine method updated to compute and return offerRateColl and crossRate for cross-border quotes
**Acceptance / logic checks:**
- For send_amount=36.9714 USD, collection_usd=36.9714, collection_margin_usd=0.3697: offerRateColl = 1.01010103 +/- 0.00000001
- For target_payout=50000 KRW, send_amount=36.9714 USD: crossRate = 1352.24xxx KRW/USD (+/- 0.01)
- Pool identity failure (abs difference > 0.01 USD) causes POOL_IDENTITY_FAILURE before computing derived rates
- BigDecimal used throughout; no double or float in the computation path
**Depends on:** 13.7-T04

### 13.7-T06 — Return null for offer_rate_coll and cross_rate on same-currency short-circuit path  _(25 min)_
**Context:** When collection_ccy = settle_a_ccy = settle_b_ccy = payout_ccy (is_same_ccy_shortcircuit=TRUE), the USD pool is entirely skipped and collection_amount = target_payout + service_charge. offer_rate_coll and cross_rate MUST be null in this case (no FX conversion occurred, no BOK report is generated). Example: target_payout=15000 KRW, service_charge=500 KRW -> collection_amount=15500 KRW; offerRateColl=null, crossRate=null.
**Steps:** In the rate engine short-circuit branch, ensure offerRateColl and crossRate are set to null (not zero, not 1.0); Confirm the RateQuoteResult builder/constructor sets both to null on this path; Add a guard: if is_same_ccy_shortcircuit is true and either field is non-null, throw an IllegalStateException
**Deliverable:** Same-currency branch of rate engine returns null for offerRateColl and crossRate
**Acceptance / logic checks:**
- For GME Remit domestic KRW case (target=15000, sc=500): offerRateColl=null, crossRate=null, collection_amount=15500
- No NullPointerException occurs downstream when these fields are null
- offerRateColl and crossRate stored as NULL (not 0) in rate_quote for same-currency quotes
**Depends on:** 13.7-T05

### 13.7-T07 — Compute offer_rate_coll for identity-leg (Settle A = USD) scenario  _(30 min)_
**Context:** When Settle A = USD, cost_rate_coll = 1.0 (IDENTITY). Step 4: send_amount = collection_usd * 1.0 = collection_usd. offer_rate_coll = send_amount / (collection_usd - collection_margin_usd) = collection_usd / (collection_usd - collection_margin_usd). Example: target_payout=100 USD, m_a=0.01, m_b=0.01 -> payout_usd_cost=100, collection_usd=102.0408, collection_margin_usd=1.0204, send_amount=102.0408, offerRateColl=102.0408/101.0204=1.01010... cross_rate=100/102.0408=0.98000. The formula is unchanged; this ticket verifies the identity-leg path specifically.
**Steps:** Write a focused unit test for the identity-leg scenario with the values above; Run the test against the rate engine implementation from T05; Confirm computed values match expected within 0.00000002 tolerance; Document the identity-leg case in a test comment explaining the semantic: offerRateColl > 1.0 reflects the collection-side margin on a USD-settled leg
**Deliverable:** Passing unit test covering the identity-leg (cost_rate_coll=1.0) offer_rate_coll computation
**Acceptance / logic checks:**
- offerRateColl = 1.01010000 +/- 0.00000002 for the scenario above
- crossRate = 0.98000000 +/- 0.00000002
- send_amount = collection_usd when cost_rate_coll = 1.0
- Test uses BigDecimal inputs and comparisons only
**Depends on:** 13.7-T05

### 13.7-T08 — Lock offer_rate_coll and cross_rate to transaction record at CommitTransaction  _(40 min)_
**Context:** At CommitTransaction (step 6 of the 8-step event trail), all rate_quote fields are copied to transaction as immutable values. This must include offer_rate_coll and cross_rate. After commit, no UPDATE on these columns is permitted. For same-currency transactions, both are NULL. The rate_locked_at timestamp must be set at the same time.
**Steps:** In the CommitTransaction service method, after copying existing pool fields from rate_quote to transaction, also copy offerRateColl and crossRate; Set transaction.rateLocked_at = now_utc(); Add a DB-level trigger or application-level guard that prevents UPDATE on offer_rate_coll and cross_rate after committed_at is set; Confirm the transaction entity JPA mapping includes both fields as updatable=false after commit (or use column-level DDL DEFAULT + immutability pattern)
**Deliverable:** CommitTransaction service updated to lock offer_rate_coll and cross_rate on the transaction record
**Acceptance / logic checks:**
- After CommitTransaction, transaction.offerRateColl equals rate_quote.offerRateColl
- After CommitTransaction, transaction.crossRate equals rate_quote.crossRate
- Attempting to UPDATE offer_rate_coll on a committed transaction is rejected (application or DB level)
- Same-currency transactions have NULL in both columns after commit
- rate_locked_at is set at the same moment both derived fields are written
**Depends on:** 13.7-T01, 13.7-T05, 13.7-T06

### 13.7-T09 — Populate bok_report_record row on CommitTransaction for cross-border transactions  _(45 min)_
**Context:** For every cross-border transaction (is_same_ccy_shortcircuit=FALSE, direction IN ('INBOUND','OUTBOUND','HUB')), a bok_report_record row must be inserted immediately after CommitTransaction succeeds. Domestic (same-currency) transactions are exempt. For Phase 1 Inbound ZeroPay: report_type='FX1015', offer_rate_coll from transaction (BOK FX1015 #14), payout_amount=target_payout, payout_ccy, collection_amount, collection_ccy, usd_amount=payout_usd_cost, report_date=committed_at::DATE, submission_status='PENDING'. FX1014 fields (Outbound) must be captured identically but Phase 1 will not route Outbound; rows may have report_type='FX1014' for future use.
**Steps:** In CommitTransaction service, after writing the rate-lock fields, call BokReportService.createRecord(transaction); In BokReportService.createRecord: if transaction.isSameCcyShortcircuit return without inserting; Determine report_type: INBOUND -> FX1015; OUTBOUND -> FX1014; HUB -> FX1015 (pending OI-03a, use FX1015 as default and log warning); Build and insert the bok_report_record row with all required fields; Wrap insertion in the same DB transaction as CommitTransaction to ensure atomicity
**Deliverable:** BokReportService.createRecord inserted as part of CommitTransaction for every cross-border transaction
**Acceptance / logic checks:**
- Inbound ZeroPay commit produces exactly one bok_report_record row with report_type=FX1015 and submission_status=PENDING
- offer_rate_coll in bok_report_record equals transaction.offer_rate_coll
- Same-currency (domestic) commit produces zero bok_report_record rows
- DB rollback of CommitTransaction also rolls back the bok_report_record insert
- report_date equals the date part of committed_at in UTC
**Depends on:** 13.7-T03, 13.7-T08

### 13.7-T10 — Add offer_rate_coll to RateQuote API response (as offer_rate field)  _(30 min)_
**Context:** Per API-05 spec, the /rates and /payments response returns offer_rate_coll as the field named offer_rate to partners. This is the value the partner uses to display the rate to its customer and compute collection_amount. GMEPay+ never validates the partner computed collection_amount. The field is a decimal string. For same-currency rules, offer_rate is omitted or null. cross_rate is admin-portal-only and not exposed in the partner API.
**Steps:** Locate the RateQuoteResponse DTO (or equivalent) used in the partner API layer; Add field offerRate (serialized as offer_rate in JSON) of type BigDecimal; Map from RateQuoteResult.offerRateColl -> offerRate when building the response DTO; For same-currency quotes, set offerRate to null and omit from JSON output (use @JsonInclude(NON_NULL)); Do not include cross_rate in the partner API response (admin-only)
**Deliverable:** Partner API RateQuoteResponse DTO updated with offer_rate field mapped from offer_rate_coll
**Acceptance / logic checks:**
- GET /rates response for cross-border rule includes offer_rate as a non-null decimal
- GET /rates response for same-currency rule omits offer_rate (key absent or null)
- cross_rate is absent from the partner API response body
- offer_rate value matches the locked offer_rate_coll in the transaction after commit
**Depends on:** 13.7-T05, 13.7-T06

### 13.7-T11 — Expose offer_rate_coll and cross_rate in Admin Portal transaction detail API  _(30 min)_
**Context:** The Admin Portal transaction detail view (PRD-07 section 8.5.2) must display all locked rate and pool values, including offer_rate_coll (labeled Derived BOK FX1015 #14 rate) and cross_rate (labeled target_payout / send_amount). For same-currency transactions, both are displayed as N/A with note: Same-currency short-circuit applied. These are read-only; no edit permitted.
**Steps:** In the Admin API transaction detail endpoint, add offer_rate_coll and cross_rate to the response DTO; Map from transaction entity fields directly; For same-currency transactions (is_same_ccy_shortcircuit=TRUE), return these fields as null (UI will render N/A); Add descriptive labels in the response metadata or rely on frontend constants
**Deliverable:** Admin API transaction detail endpoint returns offer_rate_coll and cross_rate in the locked-rates section
**Acceptance / logic checks:**
- Cross-border transaction detail response includes offerRateColl and crossRate as non-null decimals
- Same-currency transaction detail response returns null for both fields
- Values match the stored transaction columns exactly (no recalculation)
- Endpoint is RBAC-protected (Ops/Finance roles only)
**Depends on:** 13.7-T08

### 13.7-T12 — Add NOT NULL constraint and write guard for offer_rate_coll on committed cross-border transactions  _(35 min)_
**Context:** offer_rate_coll must be non-null for all committed cross-border transactions (is_same_ccy_shortcircuit=FALSE). A DB-level check constraint and an application-level validation must both enforce this. The DB constraint: CHECK (is_same_ccy_shortcircuit = TRUE OR offer_rate_coll IS NOT NULL). The application guard: BokFieldValidator.validateCommitFields(transaction) throws ValidationException if is_same_ccy_shortcircuit=FALSE and offer_rate_coll is null.
**Steps:** Create Flyway migration to add CHECK constraint on transaction table: (is_same_ccy_shortcircuit = TRUE OR offer_rate_coll IS NOT NULL); Create BokFieldValidator.validateCommitFields(transaction) method; Call validator in CommitTransaction before the DB write; Write an integration test that attempts to commit a cross-border transaction with offer_rate_coll=null and verifies rejection
**Deliverable:** DB constraint and application validator preventing null offer_rate_coll on cross-border committed transactions
**Acceptance / logic checks:**
- Inserting a committed cross-border row with offer_rate_coll=NULL violates the DB check constraint
- BokFieldValidator throws ValidationException with code BOK_OFFER_RATE_MISSING when offer_rate_coll is null for cross-border
- Same-currency transaction with offer_rate_coll=NULL passes validation
- Constraint is present in the Flyway migration and applied to the dev DB
**Depends on:** 13.7-T08

### 13.7-T13 — Implement BokReportQueryService: fetch pending bok_report_records by date range  _(40 min)_
**Context:** The BOK reporting export (required per SEC-09 section 8.1.3 pending OI-03) needs a service method to retrieve PENDING bok_report_records for a given date range. Signature: List<BokReportRecord> findPending(LocalDate from, LocalDate to, String reportType). Must use the index on (report_type, submission_status, report_date). Results must include all joined transaction fields needed for export: txn_ref, partner_id, collection_amount, collection_ccy, payout_amount, payout_ccy, offer_rate_coll, usd_amount, report_date.
**Steps:** Create BokReportQueryService class with method findPending(LocalDate from, LocalDate to, String reportType); Implement repository query using the indexed columns (report_type, submission_status, report_date BETWEEN from AND to); Map results to BokReportRecordDto including joined transaction.txn_ref; Add pagination support (page/size params) to prevent unbounded result sets; Write a unit test with 3 records (2 PENDING FX1015, 1 SUBMITTED FX1015) and verify only 2 returned for PENDING query
**Deliverable:** BokReportQueryService with findPending method and unit test
**Acceptance / logic checks:**
- findPending('FX1015', 2026-06-01, 2026-06-05) returns only PENDING FX1015 rows in date range
- SUBMITTED rows are excluded from PENDING query results
- Result DTO contains offer_rate_coll, txn_ref, collection_amount, payout_amount, usd_amount
- Empty date range returns empty list without exception
- Pagination params respected: page=0, size=100 returns at most 100 rows
**Depends on:** 13.7-T03, 13.7-T09

### 13.7-T14 — Implement BokReportExportService: produce CSV extract of FX1014/FX1015 fields  _(45 min)_
**Context:** Per SEC-09 section 8.1.3, until OI-03 is resolved the system must produce a configurable CSV extract of all FX1014/FX1015 source fields per transaction. The export must include: report_type, report_date, txn_ref, partner_id, collection_amount, collection_ccy, payout_amount, payout_ccy, offer_rate_coll (BOK FX1015 #14), usd_amount, submission_status. No hard-coded submission logic. Output is a UTF-8 CSV with header row. The service must NOT change submission_status during export (read-only extract).
**Steps:** Create BokReportExportService.exportCsv(LocalDate from, LocalDate to, String reportType, OutputStream out); Use BokReportQueryService.findPending to fetch records (or accept all statuses for admin re-export); Write CSV header row with exact field names as listed above; Write one data row per record using BigDecimal.toPlainString() for decimal fields; Do not update submission_status during export; log export action to audit log with actor and timestamp
**Deliverable:** BokReportExportService.exportCsv producing UTF-8 CSV with all required BOK fields
**Acceptance / logic checks:**
- CSV header contains: report_type, report_date, txn_ref, partner_id, collection_amount, collection_ccy, payout_amount, payout_ccy, offer_rate_coll, usd_amount, submission_status
- offer_rate_coll written as plain decimal string (not scientific notation)
- Export of 0 records produces header row only (no exception)
- submission_status in DB is unchanged after export
- Audit log entry created with actor, timestamp, date range, and record count
**Depends on:** 13.7-T13

### 13.7-T15 — Add Admin API endpoint to trigger on-demand BOK report CSV export  _(40 min)_
**Context:** GME Finance/Ops staff need an on-demand Admin System endpoint to download the BOK FX1014/FX1015 CSV export. Endpoint: GET /admin/v1/bok-reports/export?from=YYYY-MM-DD&to=YYYY-MM-DD&report_type=FX1015 returns Content-Type: text/csv with attachment filename bok_report_{type}_{from}_{to}.csv. RBAC: Finance and Super Admin roles only. No direct submission to BOK (pending OI-03); export only.
**Steps:** Create BokReportExportController with GET /admin/v1/bok-reports/export endpoint; Validate query params: from and to are valid dates, to >= from, date range <= 31 days to prevent unbounded queries, report_type IN ('FX1014','FX1015'); Call BokReportExportService.exportCsv with HttpServletResponse OutputStream; Set response headers: Content-Type text/csv; charset=UTF-8, Content-Disposition attachment; filename=bok_report_{type}_{from}_{to}.csv; Enforce RBAC: Finance and Super Admin roles only; return 403 for other roles
**Deliverable:** GET /admin/v1/bok-reports/export endpoint returning CSV download
**Acceptance / logic checks:**
- Request with valid params and Finance role returns 200 with text/csv content type
- Response Content-Disposition header contains correct filename including date range
- Date range > 31 days returns 400 Bad Request
- Invalid report_type (e.g. FX9999) returns 400
- Request with Ops role (not Finance) returns 403
**Depends on:** 13.7-T14

### 13.7-T16 — Unit test: offer_rate_coll computation - standard cross-border case  _(35 min)_
**Context:** Rate engine unit tests must cover the standard Inbound ZeroPay scenario. Inputs: target_payout=50000 KRW, cost_rate_pay=1350.0 (KRW/USD), cost_rate_coll=1.0 (Settle A=USD, IDENTITY), m_a=0.01, m_b=0.015, service_charge=1.00 USD. Expected: payout_usd_cost=50000/1350=37.0370 USD, collection_usd=37.0370/(1-0.025)=37.9867 USD, collection_margin_usd=37.9867*0.01=0.3799 USD, payout_margin_usd=37.9867*0.015=0.5698 USD, send_amount=37.9867*1.0=37.9867 USD, collection_amount=37.9867+1.00=38.9867 USD, offer_rate_coll=37.9867/(37.9867-0.3799)=37.9867/37.6068=1.01010... cross_rate=50000/37.9867=1316.25... Pool check: 37.9867-0.3799-0.5698=37.0370=payout_usd_cost OK.
**Steps:** Add test class RateEngineOfferRateTest; Write test method standardInboundZeroPay_offerRateColl_isCorrect with the exact inputs above; Assert all intermediate values and final offerRateColl within 0.00000002 tolerance using BigDecimal comparison; Assert crossRate within 0.01 tolerance; Assert pool identity holds within 0.01 USD
**Deliverable:** Unit test RateEngineOfferRateTest.standardInboundZeroPay_offerRateColl_isCorrect passing
**Acceptance / logic checks:**
- offerRateColl value is 1.01010xxx +/- 0.00000002
- crossRate is 1316.xx +/- 0.01
- Pool identity check passes (no POOL_IDENTITY_FAILURE)
- All BigDecimal assertions use compareTo not equals to avoid scale mismatch
- Test passes in CI
**Depends on:** 13.7-T05

### 13.7-T17 — Unit test: offer_rate_coll null for same-currency short-circuit  _(25 min)_
**Context:** The same-currency short-circuit (all of collection_ccy, settle_a_ccy, settle_b_ccy, payout_ccy = KRW) must produce null for both offerRateColl and crossRate. Test the GME Remit domestic scenario: target_payout=15000 KRW, service_charge=500 KRW, m_a=0, m_b=0. Expected: collection_amount=15500 KRW, offerRateColl=null, crossRate=null, is_same_ccy_shortcircuit=true.
**Steps:** Add test method sameCurrencyKRW_offerRateColl_isNull to RateEngineOfferRateTest; Build input with all four ccy fields = KRW, m_a=0, m_b=0, target_payout=15000, service_charge=500; Assert collection_amount = 15500; Assert offerRateColl is null; Assert crossRate is null
**Deliverable:** Unit test RateEngineOfferRateTest.sameCurrencyKRW_offerRateColl_isNull passing
**Acceptance / logic checks:**
- collection_amount = 15500 KRW exactly
- offerRateColl is Java null (not zero, not BigDecimal.ZERO)
- crossRate is Java null
- No pool-identity check is triggered for same-currency path
- Test passes in CI
**Depends on:** 13.7-T06

### 13.7-T18 — Unit test: offer_rate_coll for dual-identity-leg USD/USD case  _(25 min)_
**Context:** When both Settle A and Settle B = USD and target_payout=100 USD, m_a=0.01, m_b=0.01, service_charge=0.50 USD, cost_rate_coll=1.0, cost_rate_pay=1.0. Expected: payout_usd_cost=100, collection_usd=102.0408, collection_margin_usd=1.0204, payout_margin_usd=1.0204, send_amount=102.0408, collection_amount=102.5408, offerRateColl=102.0408/101.0204=1.01010..., crossRate=100/102.0408=0.98000. Pool: 102.0408-1.0204-1.0204=100.0000 OK.
**Steps:** Add test method dualIdentityLegUSD_offerRateColl to RateEngineOfferRateTest; Use exact input values above; Assert offerRateColl = 1.01010000 +/- 0.00000002; Assert crossRate = 0.98000000 +/- 0.00000002; Assert pool identity holds
**Deliverable:** Unit test RateEngineOfferRateTest.dualIdentityLegUSD_offerRateColl passing
**Acceptance / logic checks:**
- offerRateColl = 1.01010000 +/- 0.00000002
- crossRate = 0.98000000 +/- 0.00000002
- collection_amount = 102.5408 exactly
- Pool identity: abs(102.0408-1.0204-1.0204-100.0000) <= 0.01
- Test passes in CI
**Depends on:** 13.7-T07

### 13.7-T19 — Unit test: pool identity failure prevents offer_rate_coll computation  _(30 min)_
**Context:** If the pool identity invariant fails (abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) > 0.01 USD), the engine must throw POOL_IDENTITY_FAILURE before computing offer_rate_coll or cross_rate. This is a programming-error guard, not a user-facing validation. Test by injecting a doctored intermediate value that breaks the invariant.
**Steps:** Add test method poolIdentityFailure_throwsBeforeOfferRateComputation to RateEngineOfferRateTest; Inject a manipulated pool value (e.g. set collection_usd artificially high by 0.05) to break the invariant; Assert that POOL_IDENTITY_FAILURE (or equivalent exception) is thrown; Assert the exception is thrown before any offerRateColl value is computed; Verify the error is not a user-facing error code (document it as a programming error)
**Deliverable:** Unit test confirming POOL_IDENTITY_FAILURE is thrown before BOK field computation when pool invariant fails
**Acceptance / logic checks:**
- Test throws the expected exception type (not a generic RuntimeException)
- Exception message or code identifies POOL_IDENTITY_FAILURE
- offerRateColl is never set when the exception is thrown
- Test passes in CI
**Depends on:** 13.7-T05

### 13.7-T20 — Unit test: bok_report_record insertion on cross-border commit  _(40 min)_
**Context:** BokReportService.createRecord must insert exactly one bok_report_record row for cross-border commits with correct field values. Use an in-memory H2 or test-container PostgreSQL for the repository layer test. Scenario: Inbound ZeroPay commit, txn_id=1001, report_type=FX1015, offer_rate_coll=1.01010103, collection_amount=38.99 USD, payout_amount=50000 KRW.
**Steps:** Add test class BokReportServiceTest with method inboundCommit_insertsOneFX1015Row; Set up test transaction with is_same_ccy_shortcircuit=FALSE, direction=INBOUND, all required fields populated; Call BokReportService.createRecord(transaction); Query bok_report_record and assert exactly one row exists; Assert all fields: report_type=FX1015, offer_rate_coll=1.01010103, submission_status=PENDING, report_date = committed_at date
**Deliverable:** BokReportServiceTest.inboundCommit_insertsOneFX1015Row passing
**Acceptance / logic checks:**
- Exactly one bok_report_record row inserted
- report_type = FX1015
- offer_rate_coll = value from transaction (1.01010103)
- submission_status = PENDING
- Domestic transaction (is_same_ccy_shortcircuit=TRUE) call inserts zero rows
**Depends on:** 13.7-T09

### 13.7-T21 — Unit test: CSV export includes correct offer_rate_coll value  _(30 min)_
**Context:** BokReportExportService.exportCsv must produce a CSV where offer_rate_coll is written as a plain decimal string (no scientific notation) and matches the stored value. Test with two FX1015 records: offer_rate_coll=1.01010103 and 1.02050000. Verify CSV content.
**Steps:** Add test BokReportExportServiceTest.exportCsv_includesOfferRateCollAsPlainDecimal; Insert two test bok_report_records with known offer_rate_coll values; Call exportCsv and capture output to ByteArrayOutputStream; Parse CSV lines and verify header row contains offer_rate_coll; Verify data rows contain 1.01010103 and 1.02050000 as plain strings
**Deliverable:** BokReportExportServiceTest.exportCsv_includesOfferRateCollAsPlainDecimal passing
**Acceptance / logic checks:**
- CSV header line contains field name offer_rate_coll
- First data row offer_rate_coll column = 1.01010103 (plain decimal, not 1.01010103E0 or similar)
- Second data row offer_rate_coll column = 1.02050000
- Zero records export produces header row and no data rows
- Test passes in CI
**Depends on:** 13.7-T14

### 13.7-T22 — Integration test: end-to-end commit locks offer_rate_coll into transaction and bok_report_record  _(50 min)_
**Context:** An end-to-end integration test (Spring Boot test slice with test DB) must verify that after a complete CommitTransaction flow: the transaction row has offer_rate_coll locked, a matching bok_report_record exists, and the values agree. Subsequent treasury rate changes must NOT alter the locked offer_rate_coll.
**Steps:** Add integration test CommitTransactionBokIntegrationTest; Create a rate quote for an Inbound cross-border payment; Call CommitTransaction service; Assert transaction.offer_rate_coll is non-null and equals rate_quote.offer_rate_coll; Assert one bok_report_record row exists with matching offer_rate_coll; Update treasury rate in DB and re-query transaction: assert offer_rate_coll is unchanged
**Deliverable:** Integration test CommitTransactionBokIntegrationTest covering rate-lock and bok_report_record consistency
**Acceptance / logic checks:**
- transaction.offer_rate_coll == rate_quote.offer_rate_coll after commit
- bok_report_record.offer_rate_coll == transaction.offer_rate_coll
- After treasury rate update, transaction.offer_rate_coll is unchanged
- bok_report_record.submission_status = PENDING immediately after commit
- Test passes in CI with test-container or H2 DB
**Depends on:** 13.7-T08, 13.7-T09

### 13.7-T23 — Add is_same_ccy_shortcircuit flag to bok_report_record exemption logic documentation comment  _(20 min)_
**Context:** The bok_report_record exemption rule (domestic/same-currency transactions do not get a BOK record) must be clearly documented in code so future developers do not accidentally report domestic transactions. Add inline documentation to BokReportService.createRecord and a comment in the DB migration for bok_report_record. This is a code quality and compliance ticket, not a logic change.
**Steps:** Add Javadoc to BokReportService.createRecord: Domestic (same-currency) transactions are exempt from BOK FX reporting. No record is inserted when is_same_ccy_shortcircuit = TRUE. FX1014 covers Outbound; FX1015 covers Inbound.; Add SQL comment to the bok_report_record migration: One row per cross-border transaction. Domestic (is_same_ccy_shortcircuit=TRUE) transactions are exempt per SEC-09 section 8.1.; Add a TODO comment referencing OI-03: Schema is provisional pending OI-03 BOK format confirmation.
**Deliverable:** Inline documentation on BokReportService and bok_report_record migration explaining exemption logic and OI-03 status
**Acceptance / logic checks:**
- BokReportService.createRecord Javadoc mentions same-currency exemption and FX1014/FX1015 mapping
- bok_report_record migration SQL contains OI-03 reference comment
- No logic changes introduced by this ticket (diff is comments only)
- Code review passes without requesting logic change
**Depends on:** 13.7-T09

### 13.7-T24 — Add offer_rate_coll to payment.pending_debit webhook payload (CPM flow)  _(35 min)_
**Context:** For CPM payments, GMEPay+ sends a payment.pending_debit webhook to the partner at QR-token-issuance time. This webhook must include offer_rate_coll (field name offer_rate) so the partner can display the rate to the customer before the payment is confirmed. Same-currency rules: offer_rate omitted. Per spec API-05 the partner uses this rate to compute their collection_amount; GMEPay+ never validates the partner computation.
**Steps:** Locate the payment.pending_debit webhook payload builder; Add offer_rate field mapped from rate_quote.offerRateColl; For same-currency rules, omit offer_rate from the webhook payload; Add field description in any webhook schema/OpenAPI definition: offer_rate = offer_rate_coll = BOK FX1015 #14 = send_amount / (collection_usd - collection_margin_usd); Write a unit test asserting the field is present for cross-border and absent for same-currency webhooks
**Deliverable:** payment.pending_debit webhook builder updated with offer_rate field; unit test passing
**Acceptance / logic checks:**
- Cross-border CPM webhook includes offer_rate as a non-null decimal
- Same-currency CPM webhook does not include offer_rate key
- offer_rate value matches rate_quote.offer_rate_coll
- No change to any other webhook fields
- Unit test passes in CI
**Depends on:** 13.7-T05, 13.7-T06

### 13.7-T25 — Add database index on bok_report_record(report_type, submission_status, report_date)  _(20 min)_
**Context:** BokReportQueryService queries bok_report_record filtered by (report_type, submission_status, report_date BETWEEN). Without an index, this becomes a full table scan as volume grows. Add a composite index idx_bok_report_type_status_date on (report_type, submission_status, report_date). This is separate from the table creation migration (T03) to allow independent tuning.
**Steps:** Create Flyway migration V<next>__add_bok_report_record_query_index.sql; Add CREATE INDEX IF NOT EXISTS idx_bok_report_type_status_date ON bok_report_record (report_type, submission_status, report_date); Run migration on dev DB; Run EXPLAIN ANALYZE on the expected query to confirm index is used
**Deliverable:** Flyway migration adding composite index on bok_report_record
**Acceptance / logic checks:**
- Index idx_bok_report_type_status_date exists after migration
- EXPLAIN ANALYZE on SELECT * FROM bok_report_record WHERE report_type='FX1015' AND submission_status='PENDING' AND report_date BETWEEN '2026-06-01' AND '2026-06-05' shows Index Scan or Bitmap Index Scan
- Migration is idempotent (IF NOT EXISTS)
- No existing functionality broken by index addition
**Depends on:** 13.7-T03

### 13.7-T26 — Record submission_status transitions on bok_report_record (PENDING->SUBMITTED->CONFIRMED/FAILED)  _(45 min)_
**Context:** When BOK submission is eventually implemented (post OI-03), bok_report_record rows must transition through states: PENDING -> SUBMITTED -> CONFIRMED or FAILED. Create BokReportStatusService.markSubmitted(Long id) and markConfirmed(Long id) and markFailed(Long id, String reason). Each transition must validate the prior state (e.g. markConfirmed only from SUBMITTED), set submitted_at on markSubmitted, and write an audit log entry. Invalid transitions throw BokReportStatusException.
**Steps:** Create BokReportStatusService with three transition methods; Implement state-machine validation: PENDING->SUBMITTED only; SUBMITTED->CONFIRMED or SUBMITTED->FAILED only; Set submitted_at = now() when transitioning to SUBMITTED; Write audit log entry (actor, from_status, to_status, bok_record_id, timestamp) for each transition; Throw BokReportStatusException for invalid transitions (e.g. CONFIRMED->SUBMITTED)
**Deliverable:** BokReportStatusService with state-machine transitions and audit logging
**Acceptance / logic checks:**
- markSubmitted on PENDING record sets status=SUBMITTED and submitted_at to current time
- markConfirmed on SUBMITTED record sets status=CONFIRMED
- markFailed on SUBMITTED record sets status=FAILED
- markSubmitted on CONFIRMED record throws BokReportStatusException
- Each valid transition creates one audit log entry with actor and timestamp
**Depends on:** 13.7-T03, 13.7-T09

### 13.7-T27 — Unit test: bok_report_record status transitions  _(40 min)_
**Context:** BokReportStatusService state machine must be tested with all valid and invalid transitions. Tests must use a test DB (H2 or test-container). Cover: PENDING->SUBMITTED (valid), SUBMITTED->CONFIRMED (valid), SUBMITTED->FAILED (valid), PENDING->CONFIRMED (invalid), CONFIRMED->SUBMITTED (invalid), FAILED->CONFIRMED (invalid).
**Steps:** Add test class BokReportStatusServiceTest; Write one test per transition case (6 tests total); For valid transitions: assert new status, assert submitted_at set when applicable, assert audit log entry created; For invalid transitions: assert BokReportStatusException thrown, assert DB record status unchanged; Use @Transactional rollback to isolate tests
**Deliverable:** BokReportStatusServiceTest with 6 transition tests all passing
**Acceptance / logic checks:**
- 3 valid transition tests pass with correct state and audit log
- 3 invalid transition tests throw BokReportStatusException and leave DB unchanged
- submitted_at is non-null after PENDING->SUBMITTED transition
- submitted_at is not overwritten on SUBMITTED->CONFIRMED transition
- All 6 tests pass in CI
**Depends on:** 13.7-T26

### 13.7-T28 — Add OI-03 placeholder comment and FIXME to BokReportExportService for future channel integration  _(15 min)_
**Context:** Per OI-03, the exact BOK submission channel (API, SFTP, portal upload), file format, and frequency are unconfirmed. BokReportExportService must contain a clearly marked placeholder indicating where the submission adapter will be wired once OI-03 is resolved. This prevents accidental premature hardcoding and ensures the integration point is discoverable.
**Steps:** Add a clearly marked comment block at the top of BokReportExportService: OI-03 PENDING - BOK submission channel and format not yet confirmed. This service produces a generic CSV extract. When OI-03 is resolved, add a BokSubmissionAdapter that consumes this output. Do not hardcode API/SFTP/portal logic here.; Add a FIXME tag referencing the OI-03 open item in PM-14 RAID log; Ensure the export method has a Javadoc parameter noting: format is provisional CSV; field order may change pending OI-03 confirmation
**Deliverable:** BokReportExportService source file with OI-03 placeholder comments at the integration boundary
**Acceptance / logic checks:**
- Source file contains the OI-03 comment block at class level
- FIXME tag is present referencing OI-03 and PM-14
- Export method Javadoc mentions provisional format
- No submission logic (HTTP calls, SFTP) is present in this service
- Code compiles and existing tests pass
**Depends on:** 13.7-T14


## WBS 13.8 — AML/KYC hooks & monitoring
### 13.8-T01 — Create DB migration: aml_monitoring_rule table  _(30 min)_
**Context:** WBS 13.8 AML/KYC hooks. GMEPay+ is responsible for hub-level transaction monitoring (SEC-09 §8.2): alert rules on velocity, large amounts, and unusual partner patterns. KYC of end-users is the partner's responsibility; GMEPay+ never receives KYC data. Phase 1 monitoring is rule-based only (velocity, amount thresholds). Need a config table to hold per-partner or global monitoring rules.
**Steps:** Create Flyway migration V13_8_001__create_aml_monitoring_rule.sql; Define columns: id UUID PK default gen_random_uuid(), partner_id UUID nullable FK references partner(id) (null = global rule), rule_type VARCHAR(50) NOT NULL (VELOCITY_PER_MINUTE, VELOCITY_PER_5MIN, SINGLE_TXN_AMOUNT_USD, DAILY_VOLUME_USD), threshold_value NUMERIC(20,4) NOT NULL, severity VARCHAR(10) NOT NULL (P1, P2, P3), enabled BOOLEAN NOT NULL DEFAULT true, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(); Add CHECK constraint: rule_type IN ('VELOCITY_PER_MINUTE','VELOCITY_PER_5MIN','SINGLE_TXN_AMOUNT_USD','DAILY_VOLUME_USD','ROLLING_AVG_MULTIPLIER'); Add CHECK constraint: severity IN ('P1','P2','P3','P4'); Add index on (partner_id, rule_type, enabled) for fast lookup at payment time
**Deliverable:** Flyway migration file V13_8_001__create_aml_monitoring_rule.sql applying cleanly on the target schema
**Acceptance / logic checks:**
- Migration applies without error on empty schema and on schema with existing partner rows
- Table accepts a row with partner_id = NULL (global rule) for rule_type = 'VELOCITY_PER_5MIN', threshold_value = 300, severity = 'P3'
- Table accepts a row with a specific partner_id UUID and rule_type = 'SINGLE_TXN_AMOUNT_USD', threshold_value = 50000.00
- INSERT with severity = 'P5' is rejected by the CHECK constraint
- Index on (partner_id, rule_type, enabled) is present in pg_indexes

### 13.8-T02 — Create DB migration: aml_alert_event table  _(25 min)_
**Context:** WBS 13.8 AML/KYC hooks. When a monitoring rule fires, an aml_alert_event record is written for Ops visibility. SEC-09 §10.4 defines alert triggers including unusual transaction velocity (>3x 7-day average per 5 minutes, P3) and large single-transaction amounts. Each event must link back to the rule that fired and optionally to the triggering transaction.
**Steps:** Create Flyway migration V13_8_002__create_aml_alert_event.sql; Define columns: id UUID PK default gen_random_uuid(), rule_id UUID NOT NULL FK references aml_monitoring_rule(id), partner_id UUID NOT NULL FK references partner(id), transaction_id UUID nullable FK references transaction(id), alert_type VARCHAR(50) NOT NULL, severity VARCHAR(10) NOT NULL, observed_value NUMERIC(20,4) NOT NULL, threshold_value NUMERIC(20,4) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'OPEN' (OPEN, ACKNOWLEDGED, RESOLVED), acknowledged_by UUID nullable FK references operator_user(id), acknowledged_at TIMESTAMPTZ nullable, resolved_at TIMESTAMPTZ nullable, created_at TIMESTAMPTZ NOT NULL DEFAULT now(); Add CHECK constraint: status IN ('OPEN','ACKNOWLEDGED','RESOLVED'); Add index on (partner_id, status, created_at DESC) for Ops queue queries; Add index on (transaction_id) for join from transaction detail
**Deliverable:** Flyway migration file V13_8_002__create_aml_alert_event.sql
**Acceptance / logic checks:**
- Migration applies cleanly after T01 migration
- INSERT with rule_id, partner_id, alert_type='VELOCITY_PER_5MIN', severity='P3', observed_value=450, threshold_value=300 succeeds with status defaulting to 'OPEN'
- INSERT with status='INVALID' is rejected by CHECK constraint
- Nullable fields (transaction_id, acknowledged_by, acknowledged_at, resolved_at) all accept NULL
- Index on (partner_id, status, created_at DESC) visible in pg_indexes
**Depends on:** 13.8-T01

### 13.8-T03 — Create DB migration: partner_monitoring_baseline table for rolling averages  _(20 min)_
**Context:** WBS 13.8 AML/KYC hooks. SEC-09 §10.4 specifies the unusual-velocity alert threshold as greater than 3x the 7-day rolling average for a partner per 5-minute window. To compute this efficiently at runtime, a pre-computed daily baseline is stored per partner. A nightly job refreshes baselines. This table holds the rolling-average snapshot used at alert evaluation time.
**Steps:** Create Flyway migration V13_8_003__create_partner_monitoring_baseline.sql; Define columns: id UUID PK, partner_id UUID NOT NULL FK references partner(id), baseline_date DATE NOT NULL, avg_txn_count_per_5min NUMERIC(10,4) NOT NULL DEFAULT 0, avg_volume_usd_per_5min NUMERIC(20,4) NOT NULL DEFAULT 0, sample_days INTEGER NOT NULL DEFAULT 0, computed_at TIMESTAMPTZ NOT NULL DEFAULT now(); Add UNIQUE constraint on (partner_id, baseline_date) so nightly upsert is safe; Add index on (partner_id, baseline_date DESC) for latest-baseline lookup
**Deliverable:** Flyway migration file V13_8_003__create_partner_monitoring_baseline.sql
**Acceptance / logic checks:**
- Migration applies cleanly after T01 and T02
- UNIQUE constraint on (partner_id, baseline_date) prevents duplicate rows for same partner+date
- Insert two rows for the same partner_id and baseline_date: second insert is rejected
- A partner with no transaction history can have sample_days = 0 and avg values = 0 without constraint violation
- Index on (partner_id, baseline_date DESC) is present in pg_indexes
**Depends on:** 13.8-T01

### 13.8-T04 — Implement MonitoringRuleRepository: CRUD and lookup by partner  _(45 min)_
**Context:** WBS 13.8 AML/KYC hooks. The aml_monitoring_rule table (T01) needs a Spring Data JPA repository and service-layer methods. Rules can be global (partner_id = null) or partner-specific. At evaluation time the system merges both sets, with partner-specific rules overriding global ones of the same rule_type. Entities use Java UUID fields and BigDecimal for threshold_value.
**Steps:** Create JPA entity AmlMonitoringRule mapping to aml_monitoring_rule table with fields matching T01 schema; use @Enumerated(STRING) for ruleType and severity; Create MonitoringRuleRepository extends JpaRepository with custom query: findByPartnerIdOrPartnerIdIsNullAndEnabledTrue(UUID partnerId) returning all enabled rules applicable to the given partner; Create MonitoringRuleService with methods: createRule(CreateRuleRequest), updateRule(UUID id, UpdateRuleRequest), deleteRule(UUID id), getEffectiveRules(UUID partnerId) - the last method merges global and partner-specific rules, partner-specific wins on rule_type tie; Annotate service methods with @Transactional where writes occur; Write Javadoc on getEffectiveRules explaining merge priority
**Deliverable:** AmlMonitoringRule entity, MonitoringRuleRepository, and MonitoringRuleService class with getEffectiveRules merge logic
**Acceptance / logic checks:**
- getEffectiveRules(partnerX) returns the partner-specific VELOCITY_PER_5MIN rule (threshold=100) rather than the global one (threshold=300) when both exist for the same rule_type
- getEffectiveRules(partnerY) returns the global VELOCITY_PER_5MIN rule when no partner-specific override exists for partnerY
- createRule with rule_type not in the enum throws a validation exception before hitting the DB
- deleteRule marks enabled=false (soft delete) rather than issuing a DELETE SQL, confirmed by checking DB row still present with enabled=false
- getEffectiveRules only returns rows where enabled=true
**Depends on:** 13.8-T01

### 13.8-T05 — Implement AmlAlertEventRepository and AlertService write path  _(45 min)_
**Context:** WBS 13.8 AML/KYC hooks. When a monitoring rule fires, an AmlAlertEvent row is written (T02 schema). The write must be non-blocking to the payment flow: the alert is written asynchronously after the payment processing outcome. The service must also provide acknowledgement and resolution transitions for Ops use.
**Steps:** Create JPA entity AmlAlertEvent mapping to aml_alert_event table; Create AmlAlertEventRepository extends JpaRepository; Create AmlAlertService with method: fireAlert(UUID ruleId, UUID partnerId, UUID transactionId, String alertType, String severity, BigDecimal observedValue, BigDecimal thresholdValue) - persist alert event with status OPEN; Add acknowledgeAlert(UUID alertId, UUID operatorUserId) and resolveAlert(UUID alertId) methods that enforce state machine: OPEN -> ACKNOWLEDGED -> RESOLVED; reject invalid transitions; Annotate fireAlert with @Async so it does not add latency to the payment path; use @Transactional(propagation = REQUIRES_NEW) so alert write does not roll back if called from within a payment transaction
**Deliverable:** AmlAlertEvent entity, AmlAlertEventRepository, and AmlAlertService with async fire and sync state-transition methods
**Acceptance / logic checks:**
- fireAlert called from a test writes a row with status=OPEN and correct observed_value and threshold_value
- acknowledgeAlert on an OPEN alert transitions it to ACKNOWLEDGED and records acknowledged_by and acknowledged_at
- acknowledgeAlert on an already-RESOLVED alert throws IllegalStateException
- resolveAlert on an OPEN alert (skipping ACKNOWLEDGED) succeeds without error (direct OPEN->RESOLVED allowed)
- The @Async annotation means fireAlert returns immediately; the DB row appears after the async executor completes, not before the calling method returns
**Depends on:** 13.8-T02, 13.8-T04

### 13.8-T06 — Implement single-transaction amount limit check hook  _(50 min)_
**Context:** WBS 13.8 AML/KYC hooks. SEC-09 §5.3 requires a configurable per-partner single-transaction amount limit enforced BEFORE the rate engine call. The limit is stored as rule_type=SINGLE_TXN_AMOUNT_USD in aml_monitoring_rule. The check converts target_payout to USD using the treasury rate at request time and compares to the threshold. Rejection returns HTTP 422 with error code TXN_AMOUNT_LIMIT_EXCEEDED. A large-amount near-limit event (>80% of threshold) fires an advisory alert (P3) but does not block the payment.
**Steps:** In the payment service pre-processing pipeline (before rate engine invocation), inject MonitoringRuleService and retrieve the effective SINGLE_TXN_AMOUNT_USD rule for the partner; Convert target_payout to USD: usd_equiv = target_payout / treasury.usd_{payout_ccy}; use BigDecimal with HALF_UP rounding, scale 4; If usd_equiv > threshold: throw PaymentRejectionException(TXN_AMOUNT_LIMIT_EXCEEDED) with detail fields {partner_id, usd_equiv, threshold}; log at WARN; If usd_equiv >= threshold * 0.80 and usd_equiv <= threshold: call amlAlertService.fireAlert(rule.id, partner_id, null, 'SINGLE_TXN_AMOUNT_ADVISORY', 'P3', usd_equiv, threshold) asynchronously; If no SINGLE_TXN_AMOUNT_USD rule exists for the partner (and no global rule), the check is skipped (no default limit imposed)
**Deliverable:** Amount limit check method in the payment pre-processing pipeline, integrated before rate engine call
**Acceptance / logic checks:**
- Payment with usd_equiv = 50001 USD against threshold = 50000 USD is rejected with TXN_AMOUNT_LIMIT_EXCEEDED before rate engine is invoked
- Payment with usd_equiv = 49999 USD against threshold = 50000 USD proceeds normally with no alert
- Payment with usd_equiv = 41000 USD against threshold = 50000 USD (82% of threshold) proceeds AND fires a P3 advisory alert asynchronously
- Payment for a partner with no SINGLE_TXN_AMOUNT_USD rule at all proceeds without error
- usd_equiv conversion uses treasury rate: if target_payout = 5000000 KRW and treasury.usd_krw = 1380.00, then usd_equiv = 5000000 / 1380.00 = 3623.19 USD (rounded to 4dp)
**Depends on:** 13.8-T04, 13.8-T05

### 13.8-T07 — Implement per-partner velocity limit check hook (transactions per minute)  _(50 min)_
**Context:** WBS 13.8 AML/KYC hooks. SEC-09 §5.3 requires a configurable max transactions per minute per partner. This is stored as rule_type=VELOCITY_PER_MINUTE in aml_monitoring_rule. The check counts committed+in-flight transactions for the partner in the past 60 seconds using a Redis counter (INCR with 60s TTL). If the count exceeds the threshold, the payment is rejected with HTTP 429 and error code PARTNER_VELOCITY_EXCEEDED. The API Gateway also has a rate limit (300 rpm default per SEC-09 §5.4) but this hook operates at the application layer on committed payment attempts.
**Steps:** In payment pre-processing pipeline, after HMAC/auth and before amount-limit check, inject VelocityCheckService; VelocityCheckService.checkVelocityPerMinute(UUID partnerId): use Redis INCR on key aml:vel:min:{partnerId} with TTL 60s (set TTL only on key creation using SET NX EX pattern); read effective VELOCITY_PER_MINUTE rule threshold via MonitoringRuleService; If counter value > threshold: throw PaymentRejectionException(PARTNER_VELOCITY_EXCEEDED); do NOT increment (decrement or use a separate read-then-compare); log at WARN with partner_id and current count; If no rule exists for this partner and no global rule, default threshold = Integer.MAX_VALUE (no limit); Return current counter value so caller can include it in response headers if desired
**Deliverable:** VelocityCheckService.checkVelocityPerMinute method wired into payment pre-processing pipeline
**Acceptance / logic checks:**
- Counter increments from 0 to threshold+1 within 60s window causes the (threshold+1)-th call to throw PARTNER_VELOCITY_EXCEEDED
- Counter resets automatically after 60s TTL expires (submit threshold requests; wait 61s; submit one more; it succeeds)
- Partner with VELOCITY_PER_MINUTE threshold=5: first 5 calls in 30s all succeed; 6th call within the same window is rejected
- Partner with no VELOCITY_PER_MINUTE rule has no limit applied (Integer.MAX_VALUE path)
- Redis key pattern is aml:vel:min:{partnerId} and TTL is set to 60 seconds confirmed via TTL command
**Depends on:** 13.8-T04, 13.8-T05

### 13.8-T08 — Implement 5-minute velocity monitoring and rolling-average alert hook  _(55 min)_
**Context:** WBS 13.8 AML/KYC hooks. SEC-09 §10.4 specifies the unusual-velocity alert: fire a P3 alert when transaction count for a partner in any 5-minute window exceeds 3x the partner's 7-day rolling average (from partner_monitoring_baseline, T03). This does NOT block the payment; it only fires an async alert. Uses Redis counter with 300s TTL keyed aml:vel:5min:{partnerId}. Baseline is fetched from DB once per partner per hour (cached in Redis key aml:baseline:{partnerId}).
**Steps:** In VelocityCheckService, add checkVelocity5MinWindow(UUID partnerId, UUID transactionId): increment Redis key aml:vel:5min:{partnerId} (300s TTL, same SET NX EX pattern as T07); Fetch partner baseline from Redis cache key aml:baseline:{partnerId}; on cache miss, query partner_monitoring_baseline WHERE partner_id=? ORDER BY baseline_date DESC LIMIT 1; cache result for 3600s; If baseline.sample_days < 3 (insufficient history), skip the rolling-average check; If counter > 3.0 * baseline.avg_txn_count_per_5min: call amlAlertService.fireAlert(..., 'VELOCITY_5MIN_ELEVATED', 'P3', counter, threshold) asynchronously; the payment is NOT blocked; Wrap entire method in try-catch so a Redis or DB error never propagates to the payment path; log error at ERROR level and continue
**Deliverable:** VelocityCheckService.checkVelocity5MinWindow method wired into payment post-acceptance hook (fires after payment is accepted, not before)
**Acceptance / logic checks:**
- With avg_txn_count_per_5min=10.0 and sample_days=7, submitting 31 transactions in a 5-min window fires the alert (31 > 3x10=30)
- With avg_txn_count_per_5min=10.0, submitting exactly 30 transactions in a 5-min window does NOT fire an alert (30 is not > 30)
- Partner with sample_days=2 (< 3) has no alert fired regardless of volume
- If Redis throws an exception during counter increment, the exception is caught, logged, and the payment proceeds normally (no exception propagates to caller)
- The alert event row written has observed_value=31, threshold_value=30.0 when 31 transactions trigger it
**Depends on:** 13.8-T03, 13.8-T05, 13.8-T07

### 13.8-T09 — Implement nightly partner monitoring baseline refresh job  _(55 min)_
**Context:** WBS 13.8 AML/KYC hooks. The partner_monitoring_baseline table (T03) must be refreshed nightly so the rolling-average velocity check (T08) uses current data. The job computes, for each active partner, the average 5-minute transaction count over the past 7 days of committed transactions, then upserts the result. Uses Spring @Scheduled or a Quartz job running at 02:00 KST daily.
**Steps:** Create BaselineRefreshJob as a Spring @Component with @Scheduled(cron = '0 0 2 * * *', zone = 'Asia/Seoul'); Query: for each partner_id, aggregate transaction rows with status IN ('COMMITTED','SETTLED') and committed_at >= now() - INTERVAL '7 days'; compute count per 5-minute bucket; average the bucket counts; record sample_days = count(distinct date(committed_at)); Upsert into partner_monitoring_baseline: ON CONFLICT (partner_id, baseline_date) DO UPDATE SET avg_txn_count_per_5min=EXCLUDED.avg_txn_count_per_5min, avg_volume_usd_per_5min=EXCLUDED.avg_volume_usd_per_5min, sample_days=EXCLUDED.sample_days, computed_at=EXCLUDED.computed_at; After upsert, invalidate Redis cache keys aml:baseline:{partnerId} for all updated partners; Log job start, number of partners processed, and duration at INFO level; log any per-partner error at ERROR without aborting other partners
**Deliverable:** BaselineRefreshJob Spring component with nightly @Scheduled execution at 02:00 KST
**Acceptance / logic checks:**
- Job processes partner A with 100 transactions over 7 days spread across 20 distinct 5-min buckets: avg_txn_count_per_5min = 100/20 = 5.0 stored correctly
- Partner with zero transactions in past 7 days gets avg_txn_count_per_5min=0, sample_days=0 without error
- Running the job twice for the same date produces an upsert (no duplicate key error)
- After job runs, Redis key aml:baseline:{partnerId} is deleted (cache invalidated) for processed partners
- Job is annotated with zone='Asia/Seoul' so it runs at 02:00 local KST regardless of server timezone
**Depends on:** 13.8-T03, 13.8-T08

### 13.8-T10 — Implement daily volume monitoring check hook  _(50 min)_
**Context:** WBS 13.8 AML/KYC hooks. SEC-09 §5.3 requires a configurable per-partner daily volume limit in USD (rule_type=DAILY_VOLUME_USD). This check accumulates the USD equivalent of all committed transactions for the partner on the current calendar day (KST) and fires a P2 alert when the running total exceeds the threshold. Unlike velocity checks this does NOT reject payments - it is advisory only, surfacing unusual aggregate exposure to Ops. Running daily total is maintained in Redis key aml:daily_vol:{partnerId}:{YYYYMMDD_KST} with TTL 172800s (48h to survive day boundary).
**Steps:** In VelocityCheckService add checkDailyVolume(UUID partnerId, UUID transactionId, BigDecimal transactionUsdEquiv): get KST date string YYYYMMDD, INCRBYFLOAT Redis key aml:daily_vol:{partnerId}:{date} by transactionUsdEquiv; set TTL 172800 on key creation; Fetch effective DAILY_VOLUME_USD rule threshold via MonitoringRuleService; if no rule, skip; If running total > threshold: call amlAlertService.fireAlert(..., 'DAILY_VOLUME_EXCEEDED', 'P2', runningTotal, threshold) asynchronously; Alert should fire at most once per hour per partner per day to avoid alert storm: check Redis key aml:daily_vol_alerted:{partnerId}:{date}:{hour}; only fire if key absent; set key with TTL 3600s after firing; Wrap in try-catch; Redis/DB errors never propagate to payment path
**Deliverable:** VelocityCheckService.checkDailyVolume method wired as post-acceptance hook alongside the 5-minute check
**Acceptance / logic checks:**
- Accumulating USD 100001 on a partner with DAILY_VOLUME_USD threshold=100000 fires a P2 alert; USD 99999 does not
- The alert de-duplication key prevents a second P2 alert from firing within the same hour even if 50 more transactions arrive after the first breach
- De-duplication key expires after 3600s so alerts can re-fire in the next hour if volume continues to grow
- With no DAILY_VOLUME_USD rule, checkDailyVolume returns without querying Redis or the alert table
- Redis key format for a partner with id=abc123 on 2026-06-05 KST is aml:daily_vol:abc123:20260605
**Depends on:** 13.8-T05, 13.8-T08

### 13.8-T11 — Wire all AML hooks into payment pre/post processing pipeline  _(55 min)_
**Context:** WBS 13.8 AML/KYC hooks. The four hooks implemented in T06-T10 must be wired into the payment processing pipeline at the correct points. Pre-payment (blocking): single-txn amount limit (T06), velocity-per-minute (T07). Post-acceptance (async, non-blocking): 5-minute rolling-average check (T08), daily volume check (T10). The hook chain must be ordered and any single hook failure must not abort the payment. SEC-09 §5.3 states the prefunding gate must also run before rate engine; hooks run before the prefunding gate.
**Steps:** In PaymentService.processPayment (or equivalent entry point), define hook execution order: 1=velocityPerMinute (throws on breach), 2=singleTxnAmountLimit (throws on breach), 3=prefunding gate, 4=rate engine + scheme call; After successful payment commit, in a @Async post-processing step call: velocity5MinWindow check, dailyVolume check; Ensure the two blocking checks (velocity, amount) both throw PaymentRejectionException with appropriate error codes; these are caught at the controller layer and mapped to HTTP 429 and HTTP 422 respectively; Wrap each post-processing hook in individual try-catch so one failure does not prevent others from running; Add a unit test that mocks all hooks and verifies invocation order on the happy path
**Deliverable:** Updated PaymentService with all four AML hooks wired at correct pipeline positions with documented order
**Acceptance / logic checks:**
- A payment rejected by velocityPerMinute never reaches the amount-limit check (confirm via mock: amount-limit mock is never called)
- A payment rejected by amount-limit check never reaches the prefunding gate (prefunding mock is never called)
- A payment that passes all blocking checks triggers both post-processing hooks even if one of them throws a RuntimeException
- The HTTP response for PARTNER_VELOCITY_EXCEEDED is 429 with error code field set to PARTNER_VELOCITY_EXCEEDED
- The HTTP response for TXN_AMOUNT_LIMIT_EXCEEDED is 422 with error code TXN_AMOUNT_LIMIT_EXCEEDED
**Depends on:** 13.8-T06, 13.8-T07, 13.8-T08, 13.8-T10

### 13.8-T12 — Add Admin API endpoints for monitoring rule CRUD  _(55 min)_
**Context:** WBS 13.8 AML/KYC hooks. GME Ops (Admin role) must be able to create, update, enable/disable, and delete AML monitoring rules per partner or globally. SEC-09 §3.4 RBAC: only Admin role can create/update rules; Ops role has read-only access. Endpoints live under /v1/admin/aml/rules. All mutations are audit-logged (SEC-09 §6.1: actor_id, actor_role, event_type=AML_RULE_CREATED/UPDATED/DELETED, entity_type=aml_monitoring_rule, previous_value, new_value).
**Steps:** Create AdminAmlRuleController with @PreAuthorize checking Admin or Ops roles; POST /v1/admin/aml/rules (Admin only): accepts {partner_id?, rule_type, threshold_value, severity, enabled}; calls MonitoringRuleService.createRule; returns 201 with created rule; PUT /v1/admin/aml/rules/{id} (Admin only): accepts {threshold_value?, severity?, enabled?}; calls updateRule; returns 200; GET /v1/admin/aml/rules?partner_id= (Admin + Ops): returns list of effective rules for partner or all global rules if partner_id omitted; DELETE /v1/admin/aml/rules/{id} (Admin only): soft-deletes (enabled=false); returns 204; After each mutating operation write audit_log row with all SEC-09 §6.1 required fields; include previous_value as JSON of the prior rule state
**Deliverable:** AdminAmlRuleController with 4 endpoints and audit-log writes on all mutations
**Acceptance / logic checks:**
- Ops-role JWT calling POST /v1/admin/aml/rules receives HTTP 403
- Admin-role JWT calling POST with valid body receives 201 and an audit_log row is written with event_type=AML_RULE_CREATED and previous_value=null
- Admin-role JWT calling PUT to update threshold from 50000 to 60000: response is 200 and audit_log row shows previous_value={threshold_value:50000} and new_value={threshold_value:60000}
- GET /v1/admin/aml/rules?partner_id={uuid} returns both global and partner-specific rules merged (partner-specific rule overrides global of same type)
- DELETE on non-existent rule ID returns 404
**Depends on:** 13.8-T04, 13.8-T05

### 13.8-T13 — Add Admin API endpoints to query and manage AML alert events  _(50 min)_
**Context:** WBS 13.8 AML/KYC hooks. Ops must be able to view open alerts, acknowledge them, and mark them resolved via the Admin API. SEC-09 §3.4: Ops and Admin roles can view all transaction monitoring data. Endpoints under /v1/admin/aml/alerts. Alert acknowledgement and resolution are also audit-logged. Pagination required: alert queue may have many entries.
**Steps:** Create AdminAmlAlertController; GET /v1/admin/aml/alerts?partner_id=&status=&page=&size= (Admin + Ops): paginated list ordered by created_at DESC; filter by partner_id and/or status; default page size 50; GET /v1/admin/aml/alerts/{id} (Admin + Ops): single alert detail including linked transaction summary if transaction_id present; POST /v1/admin/aml/alerts/{id}/acknowledge (Admin + Ops): calls AmlAlertService.acknowledgeAlert(id, operatorId); returns 200 with updated alert; POST /v1/admin/aml/alerts/{id}/resolve (Admin + Ops): calls AmlAlertService.resolveAlert(id); returns 200; Write audit_log entry for acknowledge and resolve actions with actor_id, actor_role, event_type=AML_ALERT_ACKNOWLEDGED or AML_ALERT_RESOLVED
**Deliverable:** AdminAmlAlertController with 5 endpoints including pagination on list endpoint
**Acceptance / logic checks:**
- GET /v1/admin/aml/alerts returns only OPEN alerts when status=OPEN filter applied; returns all when no status filter
- GET with page=0&size=10 on 25 alerts returns 10 results and a page metadata field indicating totalElements=25
- POST .../acknowledge on an OPEN alert transitions it to ACKNOWLEDGED and writes audit_log row
- POST .../acknowledge on an already-RESOLVED alert returns HTTP 409 (conflict) with error message
- Partner-Viewer JWT calling any endpoint receives HTTP 403
**Depends on:** 13.8-T05, 13.8-T12

### 13.8-T14 — Implement KYC responsibility boundary enforcement: strip and reject inbound KYC fields  _(40 min)_
**Context:** WBS 13.8 AML/KYC hooks. SEC-09 §8.2 and §7.1 state GMEPay+ never receives or processes end-user KYC data (name, national ID, passport, bank account, phone, address, biometrics). If a partner accidentally submits these fields in a payment request body, they must be rejected with HTTP 422 (not silently stripped) to prompt the partner to fix their integration. A schema-level check in the API deserialization layer enforces this.
**Steps:** Define a KycFieldGuard list of forbidden JSON property names: customer_name, end_user_name, national_id, passport_number, bank_account, phone_number, home_address, biometric_data, dob, date_of_birth; In the payment request DTO (POST /v1/payments, POST /v1/payments/cpm/generate, POST /v1/payments/cpm/commit), add a @JsonAnySetter method that collects unrecognized fields; In a @Valid constraint or request filter, check if any collected unknown field name matches the KycFieldGuard list; if yes, return HTTP 422 with error code KYC_DATA_NOT_ACCEPTED and a message listing the offending field names; Log the violation at WARN with partner_id and field names (NOT the field values, which may be real PII); Ensure the field values are NEVER written to any log, audit record, or DB column
**Deliverable:** KYC field guard implemented in payment request validation layer; offending requests rejected 422 with KYC_DATA_NOT_ACCEPTED
**Acceptance / logic checks:**
- POST /v1/payments with body containing customer_name field returns HTTP 422 with error code KYC_DATA_NOT_ACCEPTED and lists customer_name in the error detail
- POST /v1/payments with a completely unknown field that is not in the KycFieldGuard list (e.g. custom_ref) is accepted (HTTP 2xx or 422 for other reasons, not KYC error)
- The warning log entry for a violation contains partner_id and field name but NOT the value of national_id or customer_name
- POST /v1/payments with no extra fields proceeds normally
- Two forbidden fields submitted together both appear in the error detail field list
**Depends on:** 13.8-T11

### 13.8-T15 — Implement transaction data export endpoint for STR/SAR support  _(55 min)_
**Context:** WBS 13.8 AML/KYC hooks. SEC-09 §8.2 states: GMEPay+ provides transaction data export to support GME's STR/SAR reporting obligations for hub-level patterns. GME Compliance must be able to extract a full transaction dataset for a partner over a date range in CSV. Export fields: transaction_id, partner_id, scheme_id, direction, payment_mode, target_payout, payout_ccy, send_amount, collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, offer_rate_coll, committed_at, status. No end-user PII is included.
**Steps:** Create AdminComplianceController with GET /v1/admin/compliance/export/transactions (Admin + Finance roles only); Accept query params: partner_id (required), from_date (required, ISO date), to_date (required, ISO date, max range 31 days per request), format=csv (only CSV in Phase 1); Stream results as CSV using Spring's StreamingResponseBody to avoid loading all rows into memory; set Content-Type: text/csv; charset=UTF-8 and Content-Disposition: attachment; filename=txn_export_{partner_id}_{from}_{to}.csv; Validate: to_date - from_date <= 31 days; from_date and to_date are in the past; partner_id exists; Write audit_log row for every export: actor_id, event_type=COMPLIANCE_EXPORT, entity_type=transaction, metadata: {partner_id, from_date, to_date, row_count}
**Deliverable:** GET /v1/admin/compliance/export/transactions endpoint returning streamed CSV with audit log entry
**Acceptance / logic checks:**
- Request with to_date - from_date = 32 days returns HTTP 422 with error EXPORT_DATE_RANGE_EXCEEDED
- Response for a 100-row result set is streamed (Content-Disposition header present, Transfer-Encoding: chunked or equivalent)
- CSV header row contains exactly: transaction_id, partner_id, scheme_id, direction, payment_mode, target_payout, payout_ccy, send_amount, collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, offer_rate_coll, committed_at, status (no PII fields)
- Audit_log row is written with row_count matching actual CSV data rows
- Partner-Viewer JWT calling this endpoint receives HTTP 403
**Depends on:** 13.8-T12

### 13.8-T16 — Implement monitoring rule seeding: default global rules on first startup  _(20 min)_
**Context:** WBS 13.8 AML/KYC hooks. SEC-09 defines default thresholds: velocity limit 300 rpm per partner (API Gateway) but at application layer the default is no hard block. However, the unusual-velocity alert fires at >3x 7-day average (rule_type=ROLLING_AVG_MULTIPLIER, default threshold=3.0), and a Prefunding deduction anomaly P2 alert fires at single deduction > USD 50000. These default global rules must be seeded on first startup via a Flyway data migration so the system has sensible defaults without manual Ops configuration.
**Steps:** Create Flyway migration V13_8_004__seed_default_aml_rules.sql; Insert global rule: partner_id=NULL, rule_type='ROLLING_AVG_MULTIPLIER', threshold_value=3.0, severity='P3', enabled=true; Insert global rule: partner_id=NULL, rule_type='SINGLE_TXN_AMOUNT_USD', threshold_value=50000.00, severity='P2', enabled=true; Insert global rule: partner_id=NULL, rule_type='DAILY_VOLUME_USD', threshold_value=500000.00, severity='P2', enabled=true; Use INSERT ... ON CONFLICT DO NOTHING so re-running the migration on an existing DB with customised rules does not overwrite them
**Deliverable:** Flyway migration V13_8_004__seed_default_aml_rules.sql inserting 3 default global rules
**Acceptance / logic checks:**
- Fresh schema after all 4 migrations has 3 rows in aml_monitoring_rule with partner_id=NULL and enabled=true
- Re-running the migration (simulated by executing the INSERT statements again) results in no rows changed (ON CONFLICT DO NOTHING)
- ROLLING_AVG_MULTIPLIER global rule has threshold_value=3.0 and severity='P3'
- SINGLE_TXN_AMOUNT_USD global rule has threshold_value=50000.00 and severity='P2'
- DAILY_VOLUME_USD global rule has threshold_value=500000.00 and severity='P2'
**Depends on:** 13.8-T01, 13.8-T04

### 13.8-T17 — Unit tests: single-transaction amount limit check (T06 logic)  _(45 min)_
**Context:** WBS 13.8 AML/KYC hooks. Test vectors for the amount-limit hook in T06. Treasury rate used: treasury.usd_krw = 1380.00. Rule threshold = 50000.00 USD. Tests use Mockito to stub MonitoringRuleService and AmlAlertService; no DB or Redis required.
**Steps:** Create AmountLimitCheckServiceTest in src/test/java; Mock MonitoringRuleService.getEffectiveRules to return a SINGLE_TXN_AMOUNT_USD rule with threshold=50000.00; Test case A: target_payout=69000000 KRW, treasury.usd_krw=1380.00 -> usd_equiv=50000.00 -> exactly at threshold -> payment proceeds, no alert; Test case B: target_payout=69001380 KRW, treasury.usd_krw=1380.00 -> usd_equiv=50001.00 -> above threshold -> PaymentRejectionException(TXN_AMOUNT_LIMIT_EXCEEDED) thrown; Test case C: target_payout=55452000 KRW, treasury.usd_krw=1380.00 -> usd_equiv=40182.61 -> 80.4% of 50000 -> advisory P3 alert fired asynchronously, payment proceeds; Test case D: no rule configured (getEffectiveRules returns empty list for this rule_type) -> payment proceeds, no exception, no alert
**Deliverable:** AmountLimitCheckServiceTest with 4 test cases all passing
**Acceptance / logic checks:**
- Test A passes: usd_equiv = 69000000/1380.00 = 50000.0000, no exception, no alert
- Test B passes: usd_equiv = 69001380/1380.00 = 50001.0000, PaymentRejectionException thrown with code TXN_AMOUNT_LIMIT_EXCEEDED
- Test C passes: usd_equiv = 55452000/1380.00 = 40182.6087 (rounded 4dp: 40182.6087), which is 80.37% of 50000 so >= 80%, fireAlert called with alertType='SINGLE_TXN_AMOUNT_ADVISORY'
- Test D passes: no exception, AmlAlertService.fireAlert never called
- All 4 tests pass with mvn test -pl hub-core -Dtest=AmountLimitCheckServiceTest
**Depends on:** 13.8-T06

### 13.8-T18 — Unit tests: velocity-per-minute check (T07 logic)  _(40 min)_
**Context:** WBS 13.8 AML/KYC hooks. Test vectors for velocity-per-minute check in T07. Tests use an in-memory Redis (e.g. embedded-redis or mock via Lettuce test utilities) and mock MonitoringRuleService. Rule: threshold=5 txns/min for partner A.
**Steps:** Create VelocityPerMinuteCheckServiceTest; Set up mock MonitoringRuleService returning VELOCITY_PER_MINUTE rule with threshold=5 for partner A; Test case A: call checkVelocityPerMinute(partnerA) 5 times within 1s; all 5 succeed; Test case B: call 6th time within same window; PaymentRejectionException(PARTNER_VELOCITY_EXCEEDED) thrown; Test case C: expire the Redis key manually (del command or advance mock TTL to 61s); call once more; it succeeds (counter reset); Test case D: partner B has no rule (empty list); call 100 times; no exception
**Deliverable:** VelocityPerMinuteCheckServiceTest with 4 test cases all passing
**Acceptance / logic checks:**
- Test A: calls 1-5 return without exception; Redis counter reaches 5
- Test B: call 6 throws PaymentRejectionException with code PARTNER_VELOCITY_EXCEEDED; Redis counter is at 5 (was not incremented past threshold)
- Test C: after key deletion, next call succeeds and counter is 1
- Test D: 100 calls for partner B with no rule all succeed (no exceptions)
- All tests pass with mvn test
**Depends on:** 13.8-T07

### 13.8-T19 — Unit tests: 5-minute rolling-average alert (T08 logic)  _(40 min)_
**Context:** WBS 13.8 AML/KYC hooks. Test vectors for the rolling-average velocity check in T08. Rule: ROLLING_AVG_MULTIPLIER threshold=3.0 (from seed T16). Baseline: avg_txn_count_per_5min=10.0, sample_days=7. Tests mock baseline service, Redis counter, and AmlAlertService.
**Steps:** Create Velocity5MinWindowCheckServiceTest; Mock baseline lookup to return avg=10.0, sample_days=7 for partner A; stub Redis counter to return configurable values; Test case A: counter=30 -> 30 is not > 3.0*10=30 -> no alert; Test case B: counter=31 -> 31 > 30 -> fireAlert called with observed=31, threshold=30.0, alertType='VELOCITY_5MIN_ELEVATED', severity='P3'; Test case C: baseline sample_days=2 (< 3) -> no alert regardless of counter value; Test case D: Redis throws RedisConnectionFailureException -> exception is caught and swallowed; fireAlert is never called; no exception propagates; Test case E: counter=31 called twice in same window -> fireAlert called twice (de-duplication is handled at daily-volume level, not here)
**Deliverable:** Velocity5MinWindowCheckServiceTest with 5 test cases all passing
**Acceptance / logic checks:**
- Test A: fireAlert never called when counter=30
- Test B: fireAlert called once with observed_value=31 and threshold_value=30.0
- Test C: fireAlert never called when sample_days=2
- Test D: RedisConnectionFailureException is swallowed; test confirms no exception thrown by checkVelocity5MinWindow
- Test E: fireAlert called twice
**Depends on:** 13.8-T08

### 13.8-T20 — Unit tests: daily volume check with alert de-duplication (T10 logic)  _(45 min)_
**Context:** WBS 13.8 AML/KYC hooks. Test vectors for the daily-volume check in T10. Rule: DAILY_VOLUME_USD threshold=100000.00. Tests use embedded/mock Redis. Key pattern: aml:daily_vol:{partnerId}:{YYYYMMDD_KST}. De-duplication key: aml:daily_vol_alerted:{partnerId}:{date}:{hour}.
**Steps:** Create DailyVolumeCheckServiceTest; Mock MonitoringRuleService to return DAILY_VOLUME_USD rule with threshold=100000.00 for partner A; Test case A: add transactions summing to USD 99999.99 -> no alert; Test case B: add one more transaction of USD 1.00 bringing total to USD 100000.99 -> fireAlert called once with P2, observed=100000.99, threshold=100000.00; Test case C: add another transaction within the same hour -> de-duplication Redis key present -> fireAlert NOT called again; Test case D: advance simulated time to next hour, delete de-duplication key -> add transaction -> fireAlert called again; Test case E: partner with no DAILY_VOLUME_USD rule -> 1000 transactions -> no alert, no Redis writes
**Deliverable:** DailyVolumeCheckServiceTest with 5 test cases all passing
**Acceptance / logic checks:**
- Test A: after 99999.99 total, fireAlert never called
- Test B: after 100000.99 total, fireAlert called once with severity='P2'
- Test C: second breach transaction in same hour does not result in a second fireAlert call
- Test D: after hour boundary and de-duplication key expiry, breach fires again
- Test E: no calls to Redis INCRBYFLOAT and fireAlert never called when no rule exists
**Depends on:** 13.8-T10

### 13.8-T21 — Unit tests: KYC field guard (T14 logic)  _(40 min)_
**Context:** WBS 13.8 AML/KYC hooks. Test vectors for the KYC field guard in T14. Tests use MockMvc to exercise the payment endpoint deserialization layer. Forbidden fields list: customer_name, end_user_name, national_id, passport_number, bank_account, phone_number, home_address, biometric_data, dob, date_of_birth.
**Steps:** Create KycFieldGuardTest using @WebMvcTest or MockMvc slice; Test case A: POST /v1/payments with valid body plus customer_name field -> HTTP 422 with error_code=KYC_DATA_NOT_ACCEPTED, offending_fields=[customer_name]; Test case B: POST with national_id AND passport_number -> HTTP 422 with offending_fields=[national_id,passport_number]; Test case C: POST with unknown non-KYC field partner_ref_custom -> HTTP proceeds normally (not 422 for KYC reason); Test case D: valid POST body with no extra fields -> no KYC error; Test case E: verify via log capture that the value of national_id (e.g. 123456789) does NOT appear in any log output when test B is run
**Deliverable:** KycFieldGuardTest with 5 test cases all passing
**Acceptance / logic checks:**
- Test A: response status 422, error_code=KYC_DATA_NOT_ACCEPTED, offending_fields contains customer_name
- Test B: response status 422, offending_fields contains both national_id and passport_number
- Test C: request proceeds without HTTP 422 due to KYC guard (may fail for other reasons but not KYC error code)
- Test D: no 422 KYC error on a well-formed request
- Test E: log output captured during Test B does not contain the string 123456789
**Depends on:** 13.8-T14

### 13.8-T22 — Integration test: full AML hook chain through payment pipeline  _(60 min)_
**Context:** WBS 13.8 AML/KYC hooks. End-to-end integration test that exercises the full hook chain (T11 wiring) against an in-process Spring Boot test with H2 or embedded Postgres and embedded Redis. Verifies that blocking hooks reject, post-processing hooks fire asynchronously, and the aml_alert_event table is populated correctly.
**Steps:** Create AmlHookIntegrationTest with @SpringBootTest using test application context with embedded Postgres (TestContainers) and embedded Redis; Seed: partner A with VELOCITY_PER_MINUTE rule threshold=2, SINGLE_TXN_AMOUNT_USD rule threshold=100.00 (USD), valid treasury rate usd_krw=1380.00, ROLLING_AVG_MULTIPLIER baseline avg=1.0 sample_days=7; Test A: submit 2 valid payments (KRW 50000 each = ~36.23 USD) -> both succeed; alert_event table has 0 rows; Test B: submit 3rd payment within same minute -> HTTP 429, PARTNER_VELOCITY_EXCEEDED; rate engine never invoked; Test C: submit payment with target_payout = KRW 200000 (~144.93 USD > 100.00 threshold) -> HTTP 422, TXN_AMOUNT_LIMIT_EXCEEDED; Test D: reset velocity counter; submit 4 payments rapidly (>3x rolling average of 1.0) -> all 4 succeed; alert_event table has at least 1 VELOCITY_5MIN_ELEVATED row with status=OPEN; Wait for @Async tasks to complete (use CountDownLatch or Awaitility)
**Deliverable:** AmlHookIntegrationTest with 4 scenarios all passing in CI
**Acceptance / logic checks:**
- Test A: HTTP 200 for both payments, zero aml_alert_event rows
- Test B: HTTP 429 returned; no transaction row committed in DB for the 3rd payment
- Test C: HTTP 422 returned; no transaction row committed for the high-amount payment
- Test D: 4 successful payments; Awaitility.await().atMost(5s).until(() -> amlAlertEventRepo.count() >= 1) passes; alert has alert_type=VELOCITY_5MIN_ELEVATED and status=OPEN
- CI pipeline reports all assertions green
**Depends on:** 13.8-T11, 13.8-T16

### 13.8-T23 — Add AML monitoring section to SEC-09 developer runbook (internal ops doc)  _(35 min)_
**Context:** WBS 13.8 AML/KYC hooks. An internal developer and Ops runbook section is needed describing: the responsibility split (partner does KYC; GMEPay+ does hub-level monitoring), the four hook types and their Redis key patterns, the nightly baseline job schedule, how to configure rules via Admin API, and how to action open alerts. This is a Markdown section to be appended to the existing docs/runbook/sec-09-compliance.md file.
**Steps:** Locate or create docs/runbook/sec-09-compliance.md in the repository; Add section: ## AML / Transaction Monitoring with subsections: Responsibility Boundary, Hook Types and Thresholds (table), Redis Key Reference (table), Nightly Baseline Job, Rule Management (Admin API commands), Alert Queue Management; Document hook execution order from T11: velocity-per-minute -> amount-limit -> prefunding gate -> rate engine; async: 5-min rolling avg, daily volume; Include table: Redis key | Purpose | TTL with rows for aml:vel:min:{p}, aml:vel:5min:{p}, aml:daily_vol:{p}:{d}, aml:daily_vol_alerted:{p}:{d}:{h}, aml:baseline:{p}; State explicitly: GMEPay+ does not receive or store end-user name, national ID, passport, bank account, phone, address, or biometric data; KYC is the partner's sole responsibility
**Deliverable:** docs/runbook/sec-09-compliance.md updated with AML monitoring section (min 200 words, all tables present)
**Acceptance / logic checks:**
- File contains section header ## AML / Transaction Monitoring
- Redis key reference table lists all 5 key patterns with correct TTL values: 60s, 300s, 172800s, 3600s, 3600s
- Hook execution order is documented as: velocity-per-minute (blocking) then amount-limit (blocking) then prefunding then rate engine; async checks listed separately
- KYC responsibility statement explicitly names the 8 forbidden data categories: name, national ID, passport, bank account, phone, address, biometric, date of birth
- Nightly baseline job cron schedule (02:00 KST) is documented with the SQL aggregation logic described in plain English
**Depends on:** 13.8-T11, 13.8-T09

---

<!-- ws-21-partner-setup-rebaseline -->

## Partner Setup re-baseline tickets (WS 21)

These tickets close Partner Setup audit gaps under the 8-slice vertical plan in `docs/PARTNER_SETUP_PLAN.md` (approved 2026-06-11). Each ticket id `21.{slice}-Pxx` maps to a wizard slice; ADR references point at `docs/adr/`. Tickets owned by **reporting-compliance** live here; cross-service contributions are listed at the bottom for awareness.

> Note: legacy WP 10.3 entries on the WBS spreadsheet remain in place but are flagged *superseded by WS 21 — see docs/PARTNER_SETUP_PLAN.md*.

### Slice 8 tickets owned by this service

### 21.8-P12 — KoFIU STR/CTR feed config on partner + reporting-compliance consumer
*Slice:* **8** · *Est:* 75 min · *Role:* Backend · *Owner:* reporting-compliance · *ADR refs:* —

**Context.** KoFIU reporting: CTR threshold + per-corridor STR enable flag. Reporting-compliance subscribes to gmepay.kyb.screening and gmepay.txn.completed to build reports.

**Steps.** V239__partner_kofiu_fields.sql: kofiu_entity_id VARCHAR(40) NULL, ctr_threshold_krw NUMERIC(20,4) DEFAULT 10000000, str_enabled_corridors JSONB DEFAULT '[]'; update services/reporting-compliance/src/main/java/com/gme/pay/reporting/KoFiuFeedBuilder.java to consume txn events + apply per-partner threshold + emit XML report.

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V239__partner_kofiu_fields.sql; services/reporting-compliance/src/main/java/com/gme/pay/reporting/KoFiuFeedBuilder.java`

**Acceptance.**
- CTR triggered when daily aggregate per end-user crosses 10M KRW threshold
- STR triggered when partner-corridor in str_enabled_corridors list and txn flagged
- Report XML validates against KoFIU schema
- ctr_threshold_krw can be overridden per partner (some partners regulated stricter)

### 21.8-P15 — reporting-compliance: BOK 외환거래보고 XML generator + scheduler
*Slice:* **8** · *Est:* 120 min · *Role:* Backend · *Owner:* reporting-compliance · *ADR refs:* —

**Context.** Generate BOK FX-trade report XML monthly, classify by partner.bok_txn_code.

**Steps.** Add `services/reporting-compliance/src/main/java/com/gme/pay/reporting/BokFxReportBuilder.java`; @Scheduled monthly batch reads completed txns + partner.bok_* fields; emits XML matching BOK template (XSD-validated); files to SFTP gateway for upload.

**Deliverable.** `services/reporting-compliance/src/main/java/com/gme/pay/reporting/BokFxReportBuilder.java`

**Acceptance.**
- Monthly batch produces one XML per fx_reporting_category
- XML validates against BOK XSD
- Files land in sftp-gateway outbox
- Txns from partners with no bok_txn_code raise WARN + Slack alert before report finalises

### 21.8-P16 — reporting-compliance: Hometax e-tax-invoice issuance on settled fee
*Slice:* **8** · *Est:* 90 min · *Role:* Backend · *Owner:* reporting-compliance · *ADR refs:* —

**Context.** When a fee settles, issue a Hometax e-tax-invoice on behalf of the partner. Uses hometax_issuer_cert_id loaded from lib-vault.

**Steps.** Add HometaxInvoiceIssuer.java consuming gmepay.settlement.completed events; fetch issuer cert from lib-vault; build invoice XML; POST to Hometax API; record invoice id in invoices table.

**Deliverable.** `services/reporting-compliance/src/main/java/com/gme/pay/reporting/HometaxInvoiceIssuer.java`

**Acceptance.**
- Settled fee triggers an issued invoice within 5 minutes
- Invoice number from Hometax stored on revenue_ledger entry
- Hometax 5xx retried 3x with backoff
- Failed issuance produces a manual-action alert

