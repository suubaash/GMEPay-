# qa-platform  (platform)

**Scope:** Test strategy/data, integration/E2E/perf harness, UAT

**Owned WBS work-packages:** 15.1, 15.2, 15.3, 15.4, 15.5, 15.6, 15.7, 15.8, 15.9, 15.10  ·  **Tickets:** 365  ·  **Est:** 252.5h

## Service contract (MSA: own DB, API-only communication)

- **Datastore (owned by this service):** n/a
- **APIs / events I EXPOSE:** test strategy, data, integration/E2E/perf harness, UAT
- **APIs / events I CONSUME:** all services (under test, via their APIs)
- **Integration rule:** never read another service's database or import its private entities — call its API or consume its event; stub consumed services with WireMock in tests.

> Self-contained backlog for this service. Build it as its own repo/module with its own DB + Flyway migrations, against the `shared-libs` contracts (lib-money / lib-errors / lib-events / lib-api-contracts only). Each ticket has a deliverable + acceptance checks.


## WBS 15.1 — Test strategy, plan & environments
### 15.1-T01 — Define test-plan document structure and section outline  _(20 min)_
**Context:** WBS 15.1 produces a formal test-plan document (QA-12) for GMEPay+ Global QR Payment Hub. The plan must cover: purpose/scope/quality goals, test strategy and levels (Unit, Integration, Contract, System/E2E, UAT, Performance, Security, Regression), test data management, rate-engine vectors, functional scenarios per component (Hub Core, Admin, Partner Portal, Partner API, ZeroPay batches, Prefunding), traceability matrix, API contract testing, NFT testing, batch/settlement testing, UAT scenarios/entry-exit, defect management, deliverables, and go-live readiness checklist.
**Steps:** Create the master document skeleton at docs/test-plan/QA-12.md with numbered H2 sections: 1-Purpose, 2-Strategy, 3-Test-Data, 4-Rate-Vectors, 5-Functional, 6-Traceability, 7-Contract, 8-NFT, 9-Batch, 10-UAT, 11-Defects, 12-Deliverables, 13-Assumptions; Add a one-sentence placeholder under each heading describing what it will contain; Record the doc code QA-12 and version 0.1 DRAFT in the document header with date and owner fields
**Deliverable:** docs/test-plan/QA-12.md skeleton with 13 H2 sections, each with a placeholder sentence, version header, and owner field
**Acceptance / logic checks:**
- File exists at docs/test-plan/QA-12.md
- All 13 H2 sections are present and numbered
- Document header shows doc code QA-12, version, date, and Owner placeholder
- No section is empty (each has at least one sentence placeholder)

### 15.1-T02 — Write QA-12 section 1: Purpose, scope, and quality goals  _(30 min)_
**Context:** QA-12 section 1 must state: purpose (defines test strategy, levels, data, vectors, and traceability for GMEPay+); in-scope components (Hub Core backend, Admin System, Partner Portal, Partner API northbound, ZeroPay integration southbound); out-of-scope (partner app UIs, merchant UIs, QR schemes beyond ZeroPay in Phase 1, live FX feed integration in Phase 2); test objectives (rate-engine correctness, atomic prefunding, ZeroPay batch formats and reconciliation, Admin mapping page with 2% margin rule, API contract, NFR targets); and quality goals table linking BRD-01 metrics to test level and pass criteria (payment success > 98% verified by 72h soak; settlement 100% on-time by batch tests; operator setup < 30min by UAT; config-without-code by UAT regression).
**Steps:** Fill in QA-12 section 1.1 with the stated purpose paragraph; Fill in section 1.2 with the in-scope components table (5 rows) and the out-of-scope bullet list; Fill in section 1.3 with the 7 test objectives as bullet points; Fill in section 1.4 with the quality goals table: 4 rows mapping BRD-01 metric to target, test level, and pass criterion
**Deliverable:** QA-12 sections 1.1-1.4 fully written with no placeholders remaining
**Acceptance / logic checks:**
- Section 1.2 lists all 5 in-scope components and 4 out-of-scope items
- Section 1.3 contains exactly 7 test objectives, including rate-engine correctness and atomic prefunding
- Section 1.4 quality-goals table has 4 rows; payment success row references 72h soak at < 2% terminal failure
- Operator setup-time row states < 30 min and UAT as the test level
**Depends on:** 15.1-T01

### 15.1-T03 — Write QA-12 section 2.1-2.2: Test strategy overview and all 8 test levels  _(45 min)_
**Context:** QA-12 section 2 documents the test pyramid: Unit -> Integration -> Contract -> System/E2E -> UAT, with Performance and Security as a parallel track and Regression continuous. For each of the 8 levels, the spec requires: scope, entry criteria, exit criteria, and tools. Key details: Unit exit requires 100% RATE-04 formula coverage and >=85% line coverage and all 8+ rate-engine vectors passing. Integration entry needs docker-compose or equivalent plus test DB seeded. Contract entry needs OpenAPI spec in repo. System/E2E entry needs ZeroPay/KFTC test environment available. UAT exit requires all P1 scenarios signed off and setup-time target confirmed. Performance entry needs production-scale staging env. Security exit requires all CRITICAL and HIGH findings remediated. Regression scope is full automated suite on every PR.
**Steps:** Add the ASCII test-pyramid diagram under section 2.1; Write section 2.2.1 Unit Tests: scope (rate-engine steps 1-5, margin validation, pool-identity check, service-charge separation, same-currency short-circuit, TTL, ZP formatters, prefunding arithmetic), entry, exit (>=85% line, all RV vectors pass), tools (JUnit/pytest/Jest); Write sections 2.2.2-2.2.8 following the same entry/scope/exit/tools structure for Integration, Contract, System/E2E, UAT, Performance, Security, Regression; Ensure exit criteria for each level are objectively verifiable and reference exact thresholds
**Deliverable:** QA-12 sections 2.1 and 2.2.1-2.2.8 fully written with scope, entry, exit, and tools for all 8 test levels
**Acceptance / logic checks:**
- Unit exit criteria state >=85% line coverage AND all RV-xx vectors pass
- Integration entry criteria explicitly mention docker-compose and test DB seeded with synthetic data
- Contract exit criteria state zero schema mismatches and all documented error codes exercised
- Performance exit criteria state all NFR-10 thresholds met and no memory/connection-pool leak in 72h soak
- Security exit criteria state all CRITICAL and HIGH findings remediated before sign-off
**Depends on:** 15.1-T02

### 15.1-T04 — Write QA-12 section 2.3: Environments table and assumption A-01  _(25 min)_
**Context:** QA-12 section 2.3 maps each test level to an environment. Four environments exist per OPS-13: Local dev (unit, integration; uses mocked SFTP and ZeroPay stub), Sandbox (contract and E2E happy path and partner integration; uses KFTC sandbox SFTP), Staging (UAT, performance, security DR drill; uses KFTC pre-production test env), Production (go-live smoke and canary; uses KFTC production). Assumption A-01: KFTC sandbox SFTP and ZeroPay test API must be available by 15 May 2026 per PM-14 critical path; any delay directly delays E2E and UAT entry; risk tracked in PM-14 RAID log.
**Steps:** Create a 4-row environments table with columns: Environment, Used-for, ZeroPay-KFTC-dependency; Fill in each row per the details above; Add assumption A-01 as a callout block immediately after the table, citing the 15 May 2026 date and PM-14 RAID reference; Cross-reference section 2.2 entries that depend on each environment
**Deliverable:** QA-12 section 2.3 with environments table (4 rows) and A-01 assumption block
**Acceptance / logic checks:**
- Table has exactly 4 rows: Local, Sandbox, Staging, Production
- Local row states mocked SFTP and ZeroPay stub, not a live KFTC connection
- Staging row lists UAT, performance, and security DR drill as uses
- A-01 assumption explicitly states 15 May 2026 and PM-14 RAID log reference
**Depends on:** 15.1-T03

### 15.1-T05 — Write QA-12 section 3.1-3.2: Synthetic partners and merchants for test data  _(25 min)_
**Context:** QA-12 section 3 defines synthetic test data. Section 3.1 lists 5 synthetic partners: P-TEST-001 TestRemit LOCAL KRW ZeroPay (domestic short-circuit), P-TEST-002 TestSendMN OVERSEAS USD ZeroPay (inbound FX 2%), P-TEST-003 TestHub OVERSEAS USD ZeroPay (Hub direction), P-TEST-004 TestManual OVERSEAS EUR ZeroPay (MANUAL rate override), P-TEST-005 TestPartnerB OVERSEAS USD ZeroPay (Partner B quote). Section 3.2 lists 5 synthetic merchants: M-TEST-0001 TestCafe Active Individual 0.80%, M-TEST-0002 TestMart Inactive Individual 0.80%, M-TEST-0003 TestChain Franchise Active 1.20%, M-TEST-0004 TestBig CrossBorder Active 1.70%, M-TEST-0005 TestDeact QR Active but QR-TEST-0005 deactivated 0.80%. Note: do not use production credentials.
**Steps:** Write section 3.1 as a table with columns: Partner-ID, Name, Type, Settle-A-ccy, Prefunding, Schemes, Notes; Write section 3.2 as a table with columns: Merchant-ID, Name, Type, Status, QR-Code, Fee-rate; Add a note in section 3.1 that production credentials must never be used in test environments
**Deliverable:** QA-12 sections 3.1 and 3.2 with synthetic-partner and synthetic-merchant tables fully populated
**Acceptance / logic checks:**
- Section 3.1 table has exactly 5 partner rows with all 7 columns populated
- P-TEST-001 is type LOCAL with KRW settlement and domestic-short-circuit note
- Section 3.2 table has 5 merchant rows; M-TEST-0002 status is Inactive
- M-TEST-0005 has an Active merchant status but a deactivated QR code (QR-TEST-0005)
**Depends on:** 15.1-T04

### 15.1-T06 — Write QA-12 section 3.3-3.4: Treasury-rate and prefunding test fixtures  _(20 min)_
**Context:** QA-12 section 3.3 defines test treasury rates (all stored as treasury.usd_{ccy} = units of ccy per 1 USD): treasury.usd_krw=1350.00, treasury.usd_mnt=3500.00, treasury.usd_usd=1.0000 (identity), treasury.usd_eur=0.9200, treasury.usd_thb=35.500. Assumption A-02: these are illustrative and used ONLY in test; real rates loaded by GME Ops before any live transaction. Section 3.4 defines prefunding balance states for P-TEST-002: Normal=50000.00 USD (happy-path), Low=9500.00 USD (low-balance alert), Depleted=0.00 USD (insufficient-balance rejection); and P-TEST-003: Normal=100000.00 USD (hub-direction tests).
**Steps:** Write section 3.3 as a table with columns: Rate-key, Value, Meaning; Add assumption A-02 callout after the table; Write section 3.4 as a table with columns: Partner, Balance-state, USD-amount, Purpose
**Deliverable:** QA-12 sections 3.3 and 3.4 with treasury-rate and prefunding-fixture tables plus A-02 assumption callout
**Acceptance / logic checks:**
- Section 3.3 table has exactly 5 rate keys, each matching the format treasury.usd_{ccy}
- treasury.usd_usd value is 1.0000 and labelled Identity
- Section 3.4 has 4 rows; P-TEST-002 Low balance is 9500.00 USD (triggers low-balance alert test, threshold assumed 10000 USD)
- A-02 callout states these rates are illustrative and test-only, and that GME Ops must load real rates before go-live
**Depends on:** 15.1-T05

### 15.1-T07 — Write QA-12 section 3.5: Sandbox seeding requirements  _(20 min)_
**Context:** QA-12 section 3.5 specifies what must be seeded into the sandbox before E2E and contract tests can run. Required: (1) all 5 synthetic partners and their rules configured in Admin, (2) treasury rates from section 3.3 loaded and active, (3) merchant records from section 3.2 synced via ZP0041/ZP0051 test files, (4) prefunding balances from section 3.4 injected directly into test DB. This section also cross-references API-05 sandbox section and SCH-06 test-environment section.
**Steps:** Write section 3.5 as a 4-item bulleted checklist with cross-references to sections 3.1, 3.2, 3.3, 3.4 respectively; Add a note that seeding must be automated (e.g. via a seed script or CI fixture) and repeatable; Cross-reference API-05 and SCH-06 for sandbox SFTP details
**Deliverable:** QA-12 section 3.5 as a concrete 4-item seeding checklist with automation note and cross-references
**Acceptance / logic checks:**
- Section 3.5 lists all 4 seeding requirements as distinct checklist items
- Item 3 references ZP0041 and ZP0051 file types for merchant sync
- Note states seeding must be automated and repeatable (not manual-only)
- Cross-references to API-05 and SCH-06 are present
**Depends on:** 15.1-T06

### 15.1-T08 — Write QA-12 section 4.1: Rate-engine formula reference for test vectors  _(25 min)_
**Context:** QA-12 section 4.1 recaps the canonical RATE-04 5-step RECEIVE-mode formulas used for all test vectors. Formulas: STEP1: payout_usd_cost = target_payout / cost_rate_pay. STEP2: collection_usd = payout_usd_cost / (1 - m_a - m_b). STEP3a: collection_margin_usd = collection_usd * m_a. STEP3b: payout_margin_usd = collection_usd * m_b. POOL-ID: collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost (tolerance <= 0.01 USD). STEP4: send_amount = collection_usd * cost_rate_coll. STEP5: collection_amount = send_amount + service_charge (service_charge is flat in Settle-A ccy, never enters USD pool). DERIVED: offer_rate_coll = send_amount / (collection_usd - collection_margin_usd). DERIVED: cross_rate = target_payout / send_amount. Identity leg: if settle = USD then cost_rate = 1.0. Same-currency short-circuit: skip USD pool, collection_amount = target_payout + service_charge.
**Steps:** Write section 4.1 as a code block containing all 5 steps, pool-identity assertion, and 2 derived formulas; Add inline notes for identity-leg short-circuit and same-currency short-circuit; State pool-identity tolerance explicitly as <= 0.01 USD; Cross-reference RATE-04 section 5 as the authoritative source
**Deliverable:** QA-12 section 4.1 formula reference block with all 5 steps, pool-identity, 2 derived formulas, and short-circuit notes
**Acceptance / logic checks:**
- All 5 STEP labels are present and numbered
- Pool-identity tolerance stated as <= 0.01 USD
- Identity-leg note states cost_rate = 1.0 when settle = USD
- Same-currency short-circuit note states USD pool is skipped and collection_amount = target_payout + service_charge
- Cross-reference to RATE-04 section 5 is present
**Depends on:** 15.1-T07

### 15.1-T09 — Write QA-12 section 4.2 Table A: Rate-engine test vector inputs (RV-01 to RV-10)  _(30 min)_
**Context:** QA-12 section 4.2 Table A lists inputs for all 10 rate-engine test vectors using treasury.usd_krw=1350.00 and treasury.usd_mnt=3500.00 from section 3.3. Vectors: RV-01 cross-border inbound (MNT->KRW) target_payout=13500 KRW m_a=0.015 m_b=0.010 svc=500 MNT. RV-02 identity leg A (USD->KRW) same target m_a m_b svc=0.50 USD cost_rate_coll=1.0. RV-03 both legs identity (USD->USD) target=100 USD same margins svc=0.50 USD. RV-04 same-currency short-circuit (KRW->KRW) target=13500 KRW m_a=0.0 m_b=0.0 svc=500 KRW rates IDENTITY. RV-05 Partner B within tolerance (0.8% dev) same as RV-01 inputs. RV-06 Partner B over tolerance (1.2% dev) same as RV-01 inputs. RV-07 min-margin boundary m_a=0.010 m_b=0.010 combined=2.0% else like RV-01. RV-08 below-min margin m_a=0.010 m_b=0.009 combined=1.9% rejected at config. RV-09 rounding edge target=10001 KRW like RV-01. RV-10 large service charge svc=5000 MNT else like RV-01.
**Steps:** Create Table A with columns: Vector-ID, Scenario, target_payout, coll_ccy/payout_ccy, cost_rate_coll, cost_rate_pay, m_a, m_b, service_charge; Populate all 10 rows with exact numeric values as specified; Mark IDENTITY in rate columns where applicable; Add a note that all rates come from section 3.3 test fixtures
**Deliverable:** QA-12 section 4.2 Table A with 10 rows of vector inputs, all numeric values matching the spec exactly
**Acceptance / logic checks:**
- Table has exactly 10 rows (RV-01 through RV-10)
- RV-04 has m_a=0.0, m_b=0.0, and both rate columns marked IDENTITY
- RV-07 has m_a=0.010 and m_b=0.010 (exactly 2.0% combined)
- RV-08 has m_a=0.010 and m_b=0.009 (1.9% combined, below minimum)
- RV-09 shows target_payout=10001 KRW (the odd rounding amount)
**Depends on:** 15.1-T08

### 15.1-T10 — Write QA-12 section 4.2 Table B: Rate-engine test vector expected outputs (RV-01 to RV-10)  _(40 min)_
**Context:** QA-12 section 4.2 Table B specifies expected outputs for all 10 vectors. RV-01: payout_usd_cost=10.0000, collection_usd=10.2564, collection_margin_usd=0.1538, payout_margin_usd=0.1026, pool-delta<=0.01, send_amount=35897.44 MNT, collection_amount=36397.44 MNT. RV-02: cost_rate_coll=IDENTITY, send_amount=collection_usd=10.2564, collection_amount=10.7564 USD. RV-03: both legs IDENTITY, collection_usd=102.5641, collection_amount=103.0641 USD. RV-04: USD pool null/skipped, collection_amount=14000 KRW, offer_rate_coll=null. RV-05: commits with cost_rate_pay=1360.80 (0.8% deviation within tolerance), rate-source=PARTNER. RV-06: returns PARTNER_B_QUOTE_DEVIATION, no commit, no prefunding deduction. RV-07: rule accepted at config, transaction commits, combined margin=2.0% recorded, collection_usd=10.2041. RV-08: Admin rejects rule at config time with validation error referencing 2.0% minimum. RV-09: no overflow, values at >=4 decimal places, pool-delta<=0.01, rounding only at collection_amount layer. RV-10: pool-identity uses collection_usd-margins vs payout_usd_cost only (not service_charge), send_amount unchanged vs RV-01.
**Steps:** Create Table B as per-vector sub-sections (not a single wide table) listing each expected intermediate and final output as bullet points; Include the pool-identity assertion result for each cross-border vector; Include pass criteria statements for each vector; Add section 4.3 pool-identity assertion pseudocode block
**Deliverable:** QA-12 section 4.2 Table B with expected outputs for all 10 vectors and section 4.3 pool-identity assertion
**Acceptance / logic checks:**
- RV-01 expected collection_amount=36397.44 MNT (35897.44 + 500)
- RV-04 explicitly states USD pool fields are null/not-computed in response
- RV-06 pass criteria states no commit and no prefunding deduction
- RV-08 pass criteria states Admin rejects at config time, not at transaction time
- RV-10 pass criteria states service_charge does not affect pool-identity calculation
**Depends on:** 15.1-T09

### 15.1-T11 — Write QA-12 section 5.1-5.2: Hub Core and Admin System functional scenarios  _(40 min)_
**Context:** QA-12 section 5 lists functional test scenarios per component. Section 5.1 Hub Core has 15 scenarios (HC-001 to HC-015): MPM domestic (collection=payout+svc), CPM domestic, MPM inbound OVERSEAS (prefunding deducted), CPM inbound (deduction at QR generate), expired rate (RATE_QUOTE_EXPIRED no deduction), idempotency (same key same response no duplicate), cancel same-day (CANCELLED balance restored), rate-lock (committed rates unchanged after treasury update), insufficient prefunding (rejected before scheme), low-balance alert (continues), inactive merchant (MERCHANT_INACTIVE), deactivated QR (QR_DEACTIVATED), Partner B deviation (PARTNER_B_QUOTE_DEVIATION no commit), Partner B unavailable (PARTNER_B_QUOTE_UNAVAILABLE no fallback), pool-identity assertion passes. Section 5.2 Admin System has 15 scenarios (AD-001 to AD-015) covering scheme creation, partner onboarding, currency derivation, rate-config auto-assignment, MANUAL override, 2% margin save, 1.9% rejection, 0% domestic, service charge flat and tiered, audit log, new-txn-only rule change, setup timer, refund processing, settlement exception.
**Steps:** Write section 5.1 as a 4-column table: Scenario-ID, Scenario, Steps-summary, Expected-result for HC-001 to HC-015; Write section 5.2 as a 3-column table: Scenario-ID, Scenario, Expected-result for AD-001 to AD-015; Ensure HC-004 states prefunding deducted at QR generate not at scheme approval; Ensure AD-007 states validation error when m_a + m_b = 1.9%
**Deliverable:** QA-12 sections 5.1 and 5.2 fully written scenario tables, 15 rows each
**Acceptance / logic checks:**
- HC-009 expected result states INSUFFICIENT_PREFUNDING is returned before scheme call and balance is unchanged
- HC-013 expected result states PARTNER_B_QUOTE_DEVIATION, no commit, no prefunding deduction
- AD-006 expected result states rule saved successfully for m_a+m_b=2.0%
- AD-007 expected result states validation error and rule not saved for m_a+m_b=1.9%
- AD-013 expected result states timed walkthrough must not exceed 30 minutes
**Depends on:** 15.1-T10

### 15.1-T12 — Write QA-12 section 5.3-5.4: Partner Portal and Partner API functional scenarios  _(30 min)_
**Context:** QA-12 section 5.3 Partner Portal has 7 scenarios PP-001 to PP-007: valid login (dashboard, own data only), invalid login (access denied), prefunding balance inquiry (USD balance, threshold, deduction history), transaction history date filter (CSV export matches), internal fields hidden (m_a, m_b, cost rates, GME margin NOT visible), transaction detail (permitted fields only), cross-partner IDOR (Partner A cannot query Partner B transactions). Section 5.4 Partner API has 8 scenarios PA-001 to PA-008: valid auth (200 token), invalid auth (401 INVALID_CREDENTIALS), GET /v1/rates MPM inbound (USD pool breakdown, validUntil), POST /v1/payments MPM inbound (prefunding deducted, payment.approved webhook), POST /v1/payments/cpm/generate (QR token, prefunding deducted), webhook delivery+retry (exponential backoff on non-2xx), idempotency replay (identical response no side effects), rate TTL expiry (RATE_QUOTE_EXPIRED after validUntil).
**Steps:** Write section 5.3 as a 3-column table: Scenario-ID, Scenario, Expected-result for PP-001 to PP-007; Write section 5.4 as a 3-column table: Scenario-ID, Scenario, Expected-result for PA-001 to PA-008; Ensure PP-005 explicitly lists m_a, m_b, cost rates, and GME margin as fields that must not appear; Ensure PP-007 explicitly tests IDOR (partner A querying partner B transaction ID)
**Deliverable:** QA-12 sections 5.3 and 5.4 fully written scenario tables (7 and 8 rows)
**Acceptance / logic checks:**
- PP-005 expected result names m_a, m_b, cost_rates, and GME_margin as fields that must not appear in any response
- PP-007 explicitly verifies cross-partner data isolation (IDOR test)
- PA-006 scenario describes webhook retry with non-2xx response triggering exponential backoff
- PA-007 explicitly states identical response and no side effects for duplicate idempotency key
- PA-008 expected result states RATE_QUOTE_EXPIRED and no prefunding deduction
**Depends on:** 15.1-T11

### 15.1-T13 — Write QA-12 section 5.5: ZeroPay batch integration scenarios (ZP-001 to ZP-014)  _(35 min)_
**Context:** QA-12 section 5.5 covers 14 ZeroPay batch scenarios. ZP-001 payment result (ZP0011/ZP0012, by 02:00/05:00 KST). ZP-002 refund result (ZP0021/ZP0022, same timing). ZP-003 morning settlement (ZP0061/ZP0062, by 05:00/10:00 KST, GME totals match). ZP-004 afternoon settlement (ZP0063/ZP0064, by 14:00/19:00 KST). ZP-005 settlement detail (ZP0065/ZP0066, by 22:00 KST, line-by-line reconciliation). ZP-006 merchant sync incremental (ZP0041, new/changed merchants upserted). ZP-007 franchise merchant sync (ZP0045/ZP0047, hierarchy persisted). ZP-008 full merchant list sync (ZP0051/ZP0055, full rebuild matches golden dataset). ZP-009 QR deactivation (ZP0043, deactivated QR blocked immediately). ZP-010 full QR list sync (ZP0053). ZP-011 SFTP failure (retry+alert, settlement not blocked). ZP-012 ZeroPay rejection (ZP0012 failure, ops alert, batch blocked until resolved). ZP-013 reconciliation discrepancy (auto-flagged, ops alert, exception management). ZP-014 file late (ZP0012 after 05:00, ops alert, previous-day flag).
**Steps:** Write section 5.5 as a 4-column table: Scenario-ID, Scenario, File(s), Expected-result; Include KST timestamps for all timing-critical scenarios; Ensure ZP-011 states retry triggered AND settlement not blocked by first retry; Ensure ZP-013 states auto-flagged AND routed to exception management
**Deliverable:** QA-12 section 5.5 with 14-row ZeroPay scenario table including file codes and KST deadlines
**Acceptance / logic checks:**
- ZP-001 states ZP0011 must be transmitted by 02:00 KST and ZP0012 confirmation received by 05:00 KST
- ZP-003 references both ZP0061 (by 05:00 KST) and ZP0062 (by 10:00 KST)
- ZP-011 expected result states both retry triggered AND settlement not blocked
- ZP-013 expected result states auto-flagged AND ops alert AND exception-management routing
- ZP-014 expected result states ops alert AND previous-day flag set
**Depends on:** 15.1-T12

### 15.1-T14 — Write QA-12 section 5.6-5.7: Prefunding and refund/cancel scenarios  _(30 min)_
**Context:** QA-12 section 5.6 Prefunding has 8 scenarios PF-001 to PF-008. PF-001 atomic MPM deduction (SELECT FOR UPDATE, balance = balance_before - collection_usd). PF-002 atomic CPM deduction at QR generate (deduction timestamp predates scheme call timestamp). PF-003 concurrent race condition (exactly one deduction per transaction, no double-spend, second request waits for lock). PF-004 insufficient balance (balance=9000 USD, collection_usd=10000 USD, rejected before scheme, INSUFFICIENT_PREFUNDING). PF-005 balance=0 (all OVERSEAS payments suspended, alert fired). PF-006 low-balance alert (drops below threshold e.g. 10000 USD, email sent, transaction continues). PF-007 configurable threshold (Ops changes threshold, new threshold applies to next deduction). PF-008 cancel restores balance (exact collection_usd amount). Section 5.7 Refund/Cancel has 4 scenarios RF-001 same-day cancel via Partner API (CANCELLED, prefunding restored, ZP cancel sent), RF-002 refund via Admin Portal (ZP0021 includes refund record), RF-003 amount validation (refund > original amount rejected), RF-004 cancel after settlement (rejected, Ops must use refund path).
**Steps:** Write section 5.6 as a 3-column table: Scenario-ID, Scenario, Expected-result for PF-001 to PF-008; Write section 5.7 as a 3-column table: Scenario-ID, Scenario, Expected-result for RF-001 to RF-004; Ensure PF-003 explicitly mentions SELECT FOR UPDATE and no double-spend; Ensure PF-004 states balance=9000 USD and collection_usd=10000 USD as the specific test values
**Deliverable:** QA-12 sections 5.6 and 5.7 with prefunding (8 rows) and refund/cancel (4 rows) scenario tables
**Acceptance / logic checks:**
- PF-001 expected result states balance = balance_before - collection_usd using SELECT FOR UPDATE
- PF-003 expected result states exactly one deduction per transaction and second request waits for lock
- PF-004 uses specific values balance=9000 USD and collection_usd=10000 USD
- PF-008 expected result states balance restored by exact collection_usd amount (not approximation)
- RF-003 expected result states validation error when refund amount exceeds original amount
**Depends on:** 15.1-T13

### 15.1-T15 — Write QA-12 section 6: Traceability matrix (UC-to-test and rule-to-test)  _(40 min)_
**Context:** QA-12 section 6 has two sub-sections. Section 6.1 maps use-cases to test cases: e.g. UC-01-01 (CPM GME Remit) maps to HC-002, HC-011, HC-012, PA-005, RV-04; UC-02-01 (CPM SendMN) maps to HC-004, HC-009, HC-010, PA-005, RV-01; UC-06-01 (prefunding) maps to PF-001 to PF-008, HC-009, HC-010. Section 6.2 maps business rules to test cases: volume-based margin -> RV-01 to RV-03, RV-07, RV-09; pool identity (<=0.01 USD) -> RV-01 to RV-07, RV-09, RV-10, HC-015; service-charge separation -> RV-10, HC-001; same-currency short-circuit -> RV-04, HC-001, HC-002; min combined margin 2% -> RV-07, RV-08, AD-006, AD-007; rate lock at commit -> HC-008; atomic deduction SELECT FOR UPDATE -> PF-001, PF-003; partner-facing fields hide internal rates -> PP-005; config-without-code -> AD-001, AD-002, AD-013. Priority column: P1-Critical for payment processing, P1-High for admin and config.
**Steps:** Write section 6.1 as a 5-column table: UC-Ref, Description, Test-Case-IDs, Component, Priority; include all 18 UC rows from the spec; Write section 6.2 as a 3-column table: Rule, Test-Case-IDs, Priority; include all 17 rule rows
**Deliverable:** QA-12 sections 6.1 and 6.2 traceability tables with 18 UC rows and 17 rule rows
**Acceptance / logic checks:**
- UC-01-01 row maps to HC-002, HC-011, HC-012, PA-005, RV-04
- UC-06-01 row maps to PF-001 through PF-008 plus HC-009 and HC-010
- Pool-identity rule row maps to RV-01 through RV-07, RV-09, RV-10, HC-015
- Atomic-deduction rule row references SELECT FOR UPDATE and maps to PF-001 and PF-003
- All UC rows have a Priority column value (P1-Critical or P1-High or P2-Medium)
**Depends on:** 15.1-T14

### 15.1-T16 — Write QA-12 section 7: API contract testing scope, endpoints, webhook, and error codes  _(35 min)_
**Context:** QA-12 section 7 defines API contract testing. Section 7.1 scope: ground truth is openapi/partner-api.yaml (API-05); contract tests verify every endpoint exists, response body matches schema including optional fields, every error code is returned under its failure condition, idempotency key behavior, webhook payload conformance. Section 7.2 lists 8 endpoints: POST /v1/auth/token (valid->200, invalid->401), GET /v1/rates (valid->200 with USD pool, missing param->400), POST /v1/payments (happy path, insufficient prefund, expired quote, duplicate key), GET /v1/payments/{id} (200, 403 wrong partner, 404), POST /v1/payments/{id}/cancel (200 same-day, 422 post-settlement), POST /v1/payments/cpm/generate (200 with QR token, 422 insufficient), GET /v1/balance (OVERSEAS partner ok, LOCAL partner appropriate response), GET /v1/transactions (date filter, status, pagination, CSV header). Section 7.3 webhook events: payment.pending_debit (txn_id, offer_rate, collection_amount, validUntil), payment.approved (txn_id, status, collection_amount, cross_rate), payment.failed (txn_id, status, error_code), payment.cancelled (txn_id, status). Retry: 500 three attempts then deliver on fourth; payload identical on retry. Section 7.4 minimum error codes to trigger: INVALID_CREDENTIALS, RATE_QUOTE_EXPIRED, INSUFFICIENT_PREFUNDING, MERCHANT_INACTIVE, QR_DEACTIVATED, PARTNER_B_QUOTE_DEVIATION, PARTNER_B_QUOTE_UNAVAILABLE, NO_SCHEME_FOR_LOCATION, DUPLICATE_IDEMPOTENCY_KEY, PAYMENT_NOT_FOUND, CANCEL_NOT_ALLOWED.
**Steps:** Write section 7.1 as a bullet list of 5 contract-test verification requirements; Write section 7.2 as a 3-column table: Endpoint, Method, Test-scenarios for all 8 endpoints; Write section 7.3 with a webhook-events table (4 rows) and a webhook-retry scenario block; Write section 7.4 as a bullet list of all 11 mandatory error codes
**Deliverable:** QA-12 sections 7.1-7.4 with endpoint table, webhook table, retry scenario, and 11 error codes
**Acceptance / logic checks:**
- Section 7.2 table has exactly 8 endpoint rows
- GET /v1/payments/{id} row lists 403 (wrong partner) as a test scenario, verifying IDOR protection
- Section 7.3 webhook table lists all 4 events with their required fields
- Webhook retry scenario states mock returns 500 for first 3 attempts, succeeds on 4th, and payload is identical
- Section 7.4 lists all 11 error codes including PARTNER_B_QUOTE_DEVIATION and CANCEL_NOT_ALLOWED
**Depends on:** 15.1-T15

### 15.1-T17 — Write QA-12 section 8.1: Performance testing targets and test types  _(25 min)_
**Context:** QA-12 section 8.1 defines performance tests against NFR-10 targets. Four test types in the staging environment at production scale: (1) Load test: payment API p95 latency <=3s at steady-state estimated peak TPS, pass threshold p95<=3s and p99<=5s. (2) Throughput test: step load 10% to 150% of peak, no error rate increase below 100% peak load. (3) Stress test: 200% of peak for 15 min, 4xx/5xx <=1%, no data corruption. (4) Soak test: steady-state for 72h, success rate remains >98%, heap and DB connections stable. (5) Batch performance: simulate max daily transaction count, ZP0011 generated and transmitted before 02:00 KST within test window. Assumption A-03: exact peak TPS targets are in NFR-10; if NFR-10 not finalised, authors must obtain targets from GME Product Team before committing pass thresholds.
**Steps:** Write section 8.1 as a 4-column table: Test-type, Target-metric, Load-profile, Pass-threshold for all 5 test types; Add assumption A-03 callout after the table; Cross-reference NFR-10 for exact TPS values
**Deliverable:** QA-12 section 8.1 performance testing table with 5 rows and A-03 assumption callout
**Acceptance / logic checks:**
- Load test row states p95<=3s AND p99<=5s as pass thresholds
- Soak test row states 72h duration and >98% success rate and heap/DB connections stable
- Stress test row states 200% of peak for 15 min and 4xx/5xx <=1%
- Batch performance row references ZP0011 and 02:00 KST deadline
- A-03 callout states that authors must obtain peak TPS from GME Product Team if NFR-10 is not finalised
**Depends on:** 15.1-T16

### 15.1-T18 — Write QA-12 section 8.2: Security testing categories and DR drill requirements  _(25 min)_
**Context:** QA-12 section 8.2 lists 8 security test categories per SEC-09 threat model: OWASP API Top 10 (all Partner API endpoints), Authentication bypass (access /v1/balance and /v1/transactions without token or with another partner's token), Prefunding manipulation (negative collection_usd, overflow values, race-condition attacks), Rate injection (crafted rate quote bypassing margin enforcement), SFTP path traversal (files outside ZeroPay SFTP directory), Admin privilege escalation (Admin endpoints with Partner API credentials), Sensitive field exposure (m_a, m_b, cost rates, GME margin not in any Partner API or Portal response), Secret management (API secrets, SFTP credentials, DB passwords not in logs or error messages). Section 8.3 DR drill: per OPS-13 RTO/RPO, must confirm: recovery from primary DB failure within stated RTO, ZeroPay SFTP credentials and batch-job state restored from backup, prefunding balances correctly recovered to last committed state. Assumption A-04: DR test must be done in staging before go-live UAT sign-off.
**Steps:** Write section 8.2 as a 2-column table: Category, Test-focus for all 8 categories; Write section 8.3 DR drill requirements as a 3-item bullet list; Add assumption A-04 callout in section 8.3; Cross-reference SEC-09 in section 8.2 header
**Deliverable:** QA-12 sections 8.2 and 8.3 with security table (8 rows), DR checklist (3 items), and A-04 callout
**Acceptance / logic checks:**
- Section 8.2 table has exactly 8 rows
- Sensitive-field-exposure row explicitly names m_a, m_b, cost rates, and GME margin
- Authentication-bypass row covers both no-token access and cross-partner token use
- Section 8.3 bullet 3 states prefunding balances recovered to last committed state (not just any state)
- A-04 callout states DR drill must be completed in staging before go-live UAT sign-off
**Depends on:** 15.1-T17

### 15.1-T19 — Write QA-12 section 9: Batch and settlement testing requirements  _(35 min)_
**Context:** QA-12 section 9 covers batch testing in 4 sub-sections. Section 9.1 ZeroPay file generation and parsing: each ZP-series file type needs a test that seeds DB, triggers batch job, validates file against KFTC layout (SCH-06), injects synthetic inbound file, validates DB state after parsing. Assumption A-05: exact KFTC layouts are in SCH-06; missing field = OI-04. Section 9.2 timing-window adherence table: ZP0011 and ZP0021 by 02:00 KST (run batch at 01:55 and confirm SFTP put completes), ZP0061 by 05:00 KST, ZP0063 by 14:00 KST, ZP0065/ZP0066 by 22:00 KST; inbound ZP0012 by 05:00 KST, ZP0062 by 10:00 KST, ZP0064 by 19:00 KST. Section 9.3 reconciliation tests: perfect match (no alert), one missing transaction (auto-flag+alert), amount discrepancy (auto-flag), extra record in ZP result (auto-flag), net vs gross settlement. Section 9.4 net vs gross: Domestic ZP0061 amount = sum of target_payout (net, ZeroPay credits merchant); International ZP0061 = full KRW payout amounts (gross, GME invoices merchant, 0.21% shared to ZeroPay).
**Steps:** Write section 9.1 as a 5-step checklist applicable to every ZP-series file type, with A-05 callout; Write section 9.2 as a table with columns: File, Direction, Deadline-KST, Test-method; Write section 9.3 as a table with columns: Test, Condition, Expected-behaviour for 5 reconciliation scenarios; Write section 9.4 as two bullet points distinguishing domestic net vs international gross
**Deliverable:** QA-12 sections 9.1-9.4 with file-testing checklist, 9-row timing table, 5-row reconciliation table, and net/gross clarification
**Acceptance / logic checks:**
- Section 9.1 lists 5 steps and includes A-05 assumption about missing KFTC field definitions
- Section 9.2 table shows ZP0011 deadline as 02:00 KST with test method run-at-01:55
- Section 9.3 amount-discrepancy row states auto-flag response
- Section 9.4 domestic bullet states ZP0061 = sum of target_payout and ZeroPay credits merchant
- Section 9.4 international bullet states gross amount and 0.21% shared to ZeroPay
**Depends on:** 15.1-T18

### 15.1-T20 — Write QA-12 section 10: UAT scope, participants, scenarios, and entry/exit criteria  _(40 min)_
**Context:** QA-12 section 10 covers UAT. Section 10.1 participants: GME Ops Settlement (settlement analyst, sign-off on ZeroPay batch and reconciliation), GME Ops Configuration (partner setup operator, sign-off on Admin mapping page and setup timer), GME Ops Monitoring (transaction monitor, sign-off on dashboard and alerts), GME Remit team (technical representative, sign-off on domestic payment E2E and API), GME Product Team (product owner, sign-off on all critical flows). Section 10.2 has 13 UAT scenarios (UAT-001 to UAT-013): UAT-001 new OVERSEAS partner end-to-end < 30min; UAT-002 add ZeroPay scheme no deployment; UAT-003 MPM domestic payment.approved collection=payout+KRW500; UAT-004 MPM inbound prefunding deducted webhook received; UAT-005 CPM inbound prefunding at QR generate; UAT-006 low-balance alert email; UAT-007 ZP0061 by 05:00 ZP0062 received and reconciled; UAT-008 exception resolution with audit trail; UAT-009 search and detail internal fields hidden; UAT-010 portal balance and CSV; UAT-011 refund in ZP0021; UAT-012 rate change new transactions only; UAT-013 config-without-code no merge or deploy. Section 10.3 entry: E2E green in staging, data seeded, Ops trained, scripts reviewed. Exit: all P1 UAT scenarios signed off, no P1/P2 open, setup-time confirmed, GME Product Owner signs UAT completion certificate.
**Steps:** Write section 10.1 as a 3-column table: Role, Participant, Sign-off-authority for 5 roles; Write section 10.2 as a 4-column table: UAT-ID, Scenario, Role, Acceptance-criterion for all 13 scenarios; Write section 10.3 entry and exit criteria as two clearly labelled paragraphs
**Deliverable:** QA-12 sections 10.1-10.3 with participants table, 13-row UAT scenarios table, and entry/exit criteria
**Acceptance / logic checks:**
- Section 10.1 has 5 rows; GME Product Owner row states sign-off authority for all critical flows
- UAT-001 acceptance criterion states completed in < 30 min and partner can make test payment
- UAT-003 acceptance criterion states collection = payout + KRW 500 (specific numeric example)
- UAT-013 acceptance criterion states no merge or deploy occurred during UAT-001
- Section 10.3 exit criteria explicitly require GME Product Owner to sign a UAT completion certificate
**Depends on:** 15.1-T19

### 15.1-T21 — Write QA-12 section 11: Defect management severity, triage, and exit gates  _(30 min)_
**Context:** QA-12 section 11 defines defect management. Section 11.1 severity definitions: P1-Critical (production blocker, data loss, security breach, payment not processing; examples: pool identity violated, prefunding double-spend, ZP file not generated), P2-High (core feature broken, no workaround, settlement delayed; examples: Admin cannot save rule, webhook not delivered), P3-Medium (feature impaired, workaround available, no data risk; example: CSV export formatting), P4-Low (cosmetic, documentation; example: typo in error message). Section 11.2 triage process: developer raises defect with steps to reproduce + severity + component; daily triage meeting (QA lead, tech lead, product owner) reviews all new P1/P2; P1 requires immediate fix and re-test within same sprint; P2 requires fix before UAT entry; P3/P4 scheduled per sprint priority. Section 11.3 exit gates by level: Unit (all green, 0 P1/P2, coverage >=85%), Integration (all green, 0 P1/P2), Contract (0 schema mismatches, all error codes exercised), System/E2E (all section 5 scenarios pass, 0 P1 open, P2 <=2 with mitigations), Performance (all NFR-10 thresholds met, no memory leak), Security (0 CRITICAL or HIGH open), UAT (all P1 signed off, 0 P1/P2 open, setup-time met).
**Steps:** Write section 11.1 as a 3-column table: Severity, Definition, Example for all 4 levels; Write section 11.2 as a numbered process list (5 steps) for defect triage; Write section 11.3 as a 2-column table: Level, Gate for all 7 test levels
**Deliverable:** QA-12 sections 11.1-11.3 with severity table, triage process, and exit-gates table
**Acceptance / logic checks:**
- P1-Critical example row includes pool identity violated and prefunding double-spend
- Section 11.2 triage step for P1 states fix and re-test within same sprint
- System/E2E exit gate states P2 count <=2 allowed with documented mitigations
- Security exit gate states 0 CRITICAL or HIGH findings open (not merely no CRITICAL)
- Unit exit gate states coverage >=85% as a numeric threshold
**Depends on:** 15.1-T20

### 15.1-T22 — Write QA-12 section 12: Test deliverables, go-live readiness checklist, and reporting  _(30 min)_
**Context:** QA-12 section 12 defines deliverables and reporting. Section 12.1 deliverables table: unit test suite with all RV-xx vectors (owner: Developer, timing: with each feature), integration test suite (QA engineer, sprint-by-sprint), contract test suite API-05 coverage (QA engineer, before E2E entry), E2E automation scripts for section 5 scenarios (QA engineer, before UAT), performance test scripts and results report (QA/DevOps, before UAT), security assessment report (Security team/third party, before go-live), UAT test scripts (QA engineer, 2 weeks before UAT start), UAT sign-off certificate (GME Product Owner, after UAT exit), go-live readiness checklist (QA lead, day before go-live). Section 12.2 go-live readiness checklist has 14 items: all P1 UAT signed off, zero P1/P2 defects, RV-01 to RV-10 pass in production-equivalent env, ZeroPay SFTP credentials live and tested against production KFTC endpoint, GME Remit record configured in Admin not sandbox, SendMN prefunding confirmed with Finance, all ZP00xx batch jobs scheduled per OPS-13, low-balance alert email addresses confirmed, DR drill completed, monitoring dashboards live, BOK reporting tested pending OI-03, pool-identity assertion alert wired to OPS channel, rate-lock verified in production, Ops team walkthrough confirmed. Section 12.3 reporting: sprint test summary (CI/CD on every PR and nightly), weekly test status (QA lead to PMO), UAT daily progress.
**Steps:** Write section 12.1 as a 3-column table: Deliverable, Owner, Timing for all 9 deliverables; Write section 12.2 as a numbered checklist with 14 items, each with a [ ] checkbox; Write section 12.3 as a 3-bullet reporting cadence list
**Deliverable:** QA-12 sections 12.1-12.3 with deliverables table, 14-item go-live checklist, and reporting cadence
**Acceptance / logic checks:**
- Section 12.1 has exactly 9 rows; UAT sign-off certificate owner is GME Product Owner
- Section 12.2 has exactly 14 numbered checklist items each with a [ ] checkbox
- Checklist item 3 states RV-01 to RV-10 pass in production-equivalent environment
- Checklist item 12 states pool-identity assertion alert wired to OPS alerting channel
- Section 12.3 distinguishes three reporting cadences: per-PR, weekly, and daily-during-UAT
**Depends on:** 15.1-T21

### 15.1-T23 — Write QA-12 section 13: Assumptions and open items register  _(25 min)_
**Context:** QA-12 section 13 contains 5 assumptions plus open items. A-01: KFTC sandbox SFTP and ZeroPay test API available by 15 May 2026 per PM-14; delay pushes E2E and UAT entry; track in PM-14 RAID; owner: GME Business/KFTC; status: Open. A-02: Illustrative treasury rates in sections 3.3 and 4.2 are test-only; real rates loaded by GME Ops before go-live; no operational impact in test env. A-03: Exact peak TPS from NFR-10; if not finalised, authors must obtain from GME Product Team before setting pass thresholds. A-04: DR procedure and RTO/RPO from OPS-13; DR test must be done in staging before go-live UAT sign-off. A-05: KFTC file layouts (field widths, encodings, trailer checksums) for all ZP00xx are in SCH-06; missing field = OI-04 and must be resolved before ZeroPay integration test phase. Open item OI-04: any missing KFTC layout definition blocks ZeroPay integration test entry.
**Steps:** Write section 13 as a table with columns: ID, Item, Impact, Owner, Status for all 5 assumptions; Add OI-04 as a separate open item row or sub-section; Cross-reference PM-14 RAID log for A-01 and OPS-13 for A-04
**Deliverable:** QA-12 section 13 with 5 assumption rows plus OI-04 open item, all with impact, owner, and status
**Acceptance / logic checks:**
- A-01 row states 15 May 2026 and owner GME Business/KFTC and status Open
- A-02 row states illustrative rates are test-only and GME Ops must load real rates
- A-05 row states missing field definition = OI-04 and blocks ZeroPay integration test entry
- OI-04 is present either as a row or sub-section
- All 5 assumption rows have Impact, Owner, and Status columns populated
**Depends on:** 15.1-T22

### 15.1-T24 — Create test-environment specification document aligned with OPS-13  _(35 min)_
**Context:** WBS 15.1 deliverable includes environment specifications. Four environments are required per QA-12 section 2.3 and OPS-13: Local dev (unit and integration; mocked SFTP and ZeroPay stub; docker-compose), Sandbox (contract and E2E; KFTC sandbox SFTP; deployed service instances), Staging (UAT, performance, security, DR; KFTC pre-production; production-scale infrastructure), Production (go-live smoke and canary; live KFTC). Each environment spec must document: purpose, test levels it supports, infrastructure requirements (docker-compose / cloud instances), ZeroPay/KFTC connectivity type, test-data state (synthetic only / seeded / production-like), access controls, and spin-up/teardown procedure summary. This document supplements OPS-13 from a QA perspective.
**Steps:** Create docs/test-plan/environments.md; Define a template section for each environment with fields: Purpose, Test-levels, Infrastructure, ZeroPay-connectivity, Test-data-state, Access-controls, Spin-up-procedure; Fill in all 4 environment sections using the details above; Add a cross-reference to OPS-13 for full infrastructure details and to QA-12 section 2.3 for the summary table
**Deliverable:** docs/test-plan/environments.md with 4 environment specification sections each covering 7 fields
**Acceptance / logic checks:**
- All 4 environments (Local, Sandbox, Staging, Production) are documented
- Local environment states docker-compose and mocked SFTP and ZeroPay stub, not a live KFTC connection
- Staging environment lists production-scale infrastructure and KFTC pre-production connectivity
- Each section has a Spin-up-procedure field (even if it says: see OPS-13 runbook)
- Cross-references to OPS-13 and QA-12 section 2.3 are present
**Depends on:** 15.1-T04

### 15.1-T25 — Create local-dev docker-compose environment setup for unit and integration tests  _(50 min)_
**Context:** The local dev environment for WBS 15.1 requires: PostgreSQL on port 5433 (per project memory), a mocked SFTP server, and a ZeroPay stub service. The docker-compose configuration must allow developers to run unit and integration tests without any external network dependencies. The test DB must be seeded with synthetic partners P-TEST-001 to P-TEST-005 (from QA-12 section 3.1), treasury rates from section 3.3, and prefunding balances from section 3.4. A seed script must be idempotent (safe to run multiple times).
**Steps:** Create or update docker-compose.test.yml with services: postgres (port 5433), sftp-mock, zeropay-stub; Add health checks to each service so tests wait for readiness; Create db/seeds/test_fixtures.sql (or equivalent) that inserts P-TEST-001 to P-TEST-005, treasury rates, and prefunding balances using INSERT ... ON CONFLICT DO NOTHING for idempotency; Document run instructions in the environment spec or a README section; Verify the compose file starts cleanly with docker-compose -f docker-compose.test.yml up -d
**Deliverable:** docker-compose.test.yml with postgres, sftp-mock, and zeropay-stub services; db/seeds/test_fixtures.sql seed script
**Acceptance / logic checks:**
- postgres service maps to host port 5433
- All 3 services have health checks defined
- db/seeds/test_fixtures.sql inserts all 5 synthetic partners with correct partner_id and type values
- Seed script uses INSERT ... ON CONFLICT DO NOTHING or equivalent idempotency guard
- docker-compose -f docker-compose.test.yml up -d starts all services without error
**Depends on:** 15.1-T24

### 15.1-T26 — Write test-data seed script for sandbox environment  _(40 min)_
**Context:** The sandbox environment must be seeded for contract and E2E testing per QA-12 section 3.5. Required seed data: (1) synthetic partners P-TEST-001 to P-TEST-005 with all rule configurations in Admin (type, Settle-A ccy, prefunding setup, ZeroPay scheme mapping); (2) treasury rates treasury.usd_krw=1350.00, treasury.usd_mnt=3500.00, treasury.usd_usd=1.0000, treasury.usd_eur=0.9200, treasury.usd_thb=35.500; (3) merchant records M-TEST-0001 to M-TEST-0005 (to be synced via ZP0041/ZP0051 test files); (4) prefunding balances P-TEST-002 Normal=50000.00, Low=9500.00, Depleted=0.00 and P-TEST-003 Normal=100000.00. The script must be runnable in CI (non-interactive) and idempotent.
**Steps:** Create scripts/seed-sandbox.sh (or .sql / Java main) that calls Admin API endpoints or inserts directly to DB to create all synthetic partners; Add treasury rate inserts for all 5 rate keys; Add prefunding balance inserts for P-TEST-002 and P-TEST-003 with the 4 balance states; Add instructions for injecting ZP0041/ZP0051 test files to trigger merchant sync; Add a README comment that this script must not run against production
**Deliverable:** scripts/seed-sandbox.sh (or equivalent) that creates all synthetic partners, treasury rates, prefunding balances, and triggers merchant sync
**Acceptance / logic checks:**
- Script inserts exactly 5 partners (P-TEST-001 to P-TEST-005) with correct types and settlement currencies
- treasury.usd_krw=1350.00 and treasury.usd_mnt=3500.00 are present in treasury-rates inserts
- P-TEST-002 prefunding balance for the Low state is inserted as 9500.00 USD
- Script is idempotent (safe to run twice without duplicate-key errors)
- Script contains a guard or comment preventing accidental execution against production
**Depends on:** 15.1-T25

### 15.1-T27 — Define entry and exit criteria config file for CI gate enforcement  _(30 min)_
**Context:** QA-12 sections 2.2 and 11.3 define specific exit criteria per test level that must be enforced in CI/CD. For automated enforcement, each gate needs a machine-readable representation: Unit gate (coverage >=85%, all RV vectors pass, 0 P1/P2 failures), Integration gate (all green, 0 P1/P2), Contract gate (0 schema mismatches, all 11 error codes exercised), Regression gate (no new failures, coverage does not decrease). The config file allows the CI pipeline (OPS-13) to read gate thresholds without hardcoding them in pipeline scripts. Format: YAML or JSON with fields per gate: level, coverage_threshold, allowed_p1_failures, allowed_p2_failures, required_test_ids.
**Steps:** Create config/test-gates.yml with a top-level key per gate: unit, integration, contract, regression; For unit gate set coverage_threshold: 85, allowed_p1_failures: 0, allowed_p2_failures: 0, required_test_ids listing RV-01 through RV-10; For contract gate set schema_mismatch_threshold: 0 and required_error_codes listing all 11 codes from QA-12 section 7.4; For regression gate set coverage_regression_allowed: false; Add a comment header referencing QA-12 section 2.2 and 11.3
**Deliverable:** config/test-gates.yml with 4 gate sections (unit, integration, contract, regression) including numeric thresholds and required test IDs
**Acceptance / logic checks:**
- unit gate has coverage_threshold: 85
- unit gate required_test_ids lists RV-01 through RV-10 (10 entries)
- contract gate required_error_codes lists all 11 error codes from QA-12 section 7.4
- regression gate has coverage_regression_allowed: false
- File header comment references QA-12 sections 2.2 and 11.3
**Depends on:** 15.1-T03

### 15.1-T28 — Unit test: verify rate-engine test vectors RV-01 cross-border inbound  _(35 min)_
**Context:** QA-12 section 4.2 specifies rate-engine test vector RV-01. Inputs: target_payout=13500 KRW, cost_rate_coll=3500.00 MNT/USD (treasury.usd_mnt), cost_rate_pay=1350.00 KRW/USD (treasury.usd_krw), m_a=0.015, m_b=0.010, service_charge=500 MNT. Expected outputs: payout_usd_cost=10.0000, collection_usd=10.2564, collection_margin_usd=0.1538, payout_margin_usd=0.1026, send_amount=35897.44 MNT, collection_amount=36397.44 MNT, pool-identity delta<=0.01 USD, offer_rate_coll=send_amount/(collection_usd-collection_margin_usd), cross_rate=target_payout/send_amount. Use decimal types (not float) to avoid floating-point drift. Tolerance for all intermediate values: +-0.01 of expected.
**Steps:** In the rate-engine unit test file, add test_RV01_cross_border_inbound(); Set inputs as BigDecimal/Decimal constants matching the values above; Call the rate-engine calculate() function with these inputs; Assert each output field within +-0.01 tolerance; Assert pool-identity: abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) <= 0.01; Assert offer_rate_coll = send_amount / (collection_usd - collection_margin_usd) within 0.01
**Deliverable:** Unit test test_RV01_cross_border_inbound() in the rate-engine test file, green on run
**Acceptance / logic checks:**
- Test asserts collection_amount == 36397.44 MNT within +-0.01 tolerance
- Test asserts payout_usd_cost == 10.0000 USD within +-0.01
- Pool-identity assertion is present and passes
- Test uses decimal/BigDecimal types for all monetary values, not float/double
- Test is isolated (no DB or network call required)
**Depends on:** 15.1-T10

### 15.1-T29 — Unit test: verify rate-engine test vectors RV-02 and RV-03 identity legs  _(30 min)_
**Context:** QA-12 section 4.2 specifies: RV-02 (identity leg A, Settle A=USD): target_payout=13500 KRW, cost_rate_coll=1.0 (IDENTITY), cost_rate_pay=1350.00, m_a=0.015, m_b=0.010, service_charge=0.50 USD. Expected: send_amount=collection_usd=10.2564, collection_amount=10.7564 USD, IDENTITY flag recorded for cost_rate_coll, pool identity holds. RV-03 (both legs identity, USD->USD): target_payout=100.00 USD, cost_rate_coll=1.0, cost_rate_pay=1.0, m_a=0.015, m_b=0.010, service_charge=0.50 USD. Expected: collection_usd=102.5641, collection_amount=103.0641 USD, both legs flagged IDENTITY, margins apply normally, pool identity holds.
**Steps:** Add test_RV02_identity_leg_A() with the inputs and assertions described; Add test_RV03_both_legs_identity() with the inputs and assertions described; For RV-02 assert that the rate-source field for cost_rate_coll is IDENTITY; For RV-03 assert both rate-source fields are IDENTITY and margins still apply (collection_usd != target_payout); Assert pool identity for both vectors
**Deliverable:** Two unit tests test_RV02 and test_RV03 in the rate-engine test file, both green
**Acceptance / logic checks:**
- RV-02 asserts send_amount == collection_usd (i.e. 10.2564 USD within 0.01)
- RV-02 asserts rate-source for cost_rate_coll == IDENTITY
- RV-03 asserts collection_amount == 103.0641 USD within 0.01
- RV-03 asserts collection_margin_usd > 0 (margins still deducted even on identity legs)
- Pool-identity assertion passes for both RV-02 and RV-03
**Depends on:** 15.1-T28

### 15.1-T30 — Unit test: verify rate-engine test vector RV-04 same-currency short-circuit  _(30 min)_
**Context:** QA-12 section 4.2 RV-04: target_payout=13500 KRW, coll_ccy=KRW, payout_ccy=KRW, settle_A=KRW, settle_B=KRW, m_a=0.0, m_b=0.0, service_charge=500 KRW. Expected: USD pool entirely skipped (collection_usd=null), collection_amount=13500+500=14000 KRW, offer_rate_coll=null, no payout_usd_cost, no collection_margin_usd, no payout_margin_usd, both rate slots recorded as IDENTITY in audit, no margin deducted from prefunding. The same-currency short-circuit triggers when collection_ccy == settle_A_ccy == settle_B_ccy == payout_ccy.
**Steps:** Add test_RV04_same_currency_short_circuit() with KRW-only inputs; Assert collection_amount == 14000 KRW; Assert collection_usd is null or not populated; Assert offer_rate_coll is null; Assert no pool-identity calculation was invoked (e.g. by checking a flag or absence of intermediate fields in result); Assert both rate-source fields are IDENTITY
**Deliverable:** Unit test test_RV04_same_currency_short_circuit() in the rate-engine test file, green
**Acceptance / logic checks:**
- collection_amount asserted as exactly 14000 KRW (13500 + 500)
- collection_usd is null or absent in the result object
- offer_rate_coll is null or absent
- Both rate-source fields are IDENTITY in the result
- No pool-identity assertion error is thrown (USD pool was skipped, not zero)
**Depends on:** 15.1-T29

### 15.1-T31 — Unit test: verify rate-engine vectors RV-05 and RV-06 Partner B quote tolerance  _(35 min)_
**Context:** QA-12 section 4.2 RV-05: same inputs as RV-01 but cost_rate_pay sourced from partner B. At /rates time quote=1350.00; at commit partner B quote=1360.80 (deviation 0.80%, within 1.0% tolerance). Expected: transaction commits, payout_usd_cost computed with 1360.80 (= 13500/1360.80 = 9.9206 USD illustrative), rate-source = PARTNER, recorded cost_rate_pay = 1360.80. RV-06: at /rates time quote=1350.00; commit-time partner B quote=1366.20 (deviation 1.2%, exceeds 1.0% tolerance). Expected: system raises PARTNER_B_QUOTE_DEVIATION, transaction NOT committed, no prefunding deduction. Tolerance formula: abs(commit_rate - quote_rate) / quote_rate > 0.01 -> reject.
**Steps:** Add test_RV05_partner_b_within_tolerance() simulating stub partner B returning 1360.80 at commit; Assert transaction completes and recorded cost_rate_pay == 1360.80; Assert rate-source field == PARTNER; Add test_RV06_partner_b_over_tolerance() simulating partner B returning 1366.20; Assert PARTNER_B_QUOTE_DEVIATION error is raised; Assert no transaction record is committed and no prefunding deduction occurred
**Deliverable:** Two unit tests test_RV05 and test_RV06 in the rate-engine test file, both green
**Acceptance / logic checks:**
- RV-05 asserts recorded cost_rate_pay == 1360.80 and rate-source == PARTNER
- RV-05 asserts transaction committed = true
- RV-06 asserts error code == PARTNER_B_QUOTE_DEVIATION
- RV-06 asserts transaction committed == false
- RV-06 asserts no prefunding deduction was made (balance unchanged)
**Depends on:** 15.1-T30

### 15.1-T32 — Unit test: verify rate-engine vectors RV-07, RV-08 margin boundary and RV-09 rounding  _(35 min)_
**Context:** QA-12 section 4.2: RV-07 min-margin boundary m_a=0.010 m_b=0.010 (combined exactly 2.0%): rule is accepted at config time, transaction commits, collection_usd=13500/1350/0.980=10.2041 USD, pool identity holds. RV-08 below-min m_a=0.010 m_b=0.009 (combined 1.9%): Admin rejects rule with validation error at config time, error message references 2.0% minimum. RV-09 rounding edge case: target_payout=10001 KRW, same rates as RV-01. payout_usd_cost=10001/1350=7.40815, collection_usd=7.40815/0.975=7.59810, send_amount=7.59810*3500=26593.33 MNT. Expected: no integer overflow, values stored >=4 decimal places, pool-delta<=0.01, rounding applied only at collection_amount layer not at intermediate steps.
**Steps:** Add test_RV07_min_margin_boundary() with m_a=0.010 m_b=0.010; assert rule accepted and transaction commits with collection_usd==10.2041 within 0.01; Add test_RV08_below_min_margin() calling the rule-validation function with m_a=0.010 m_b=0.009; assert validation error with message referencing 2.0% minimum; Add test_RV09_rounding_edge_case() with target_payout=10001 KRW; assert send_amount==26593.33 MNT within 0.01, pool-delta<=0.01, and that intermediate steps use >=4 decimal places
**Deliverable:** Three unit tests test_RV07, test_RV08, test_RV09 in the rate-engine test file, all green
**Acceptance / logic checks:**
- RV-07 asserts collection_usd == 10.2041 within 0.01 and pool identity holds
- RV-08 asserts a validation error is thrown at rule-config time (not at payment time)
- RV-08 error message or error code references the 2.0% minimum combined margin constraint
- RV-09 asserts send_amount == 26593.33 MNT within 0.01
- RV-09 asserts intermediate values are stored with at least 4 decimal places (not rounded to 2)
**Depends on:** 15.1-T31

### 15.1-T33 — Unit test: verify rate-engine vector RV-10 service-charge separation and pool-identity assertion  _(35 min)_
**Context:** QA-12 section 4.2 RV-10: same inputs as RV-01 except service_charge=5000 MNT (large). Expected: USD pool computation identical to RV-01 (collection_usd=10.2564, send_amount=35897.44 MNT), collection_amount=35897.44+5000=40897.44 MNT, pool-identity check uses collection_usd - margins vs payout_usd_cost only (service_charge does NOT enter the USD pool), service_charge recorded separately in revenue ledger, send_amount unchanged vs RV-01. Also verify QA-12 section 4.3: the pool-identity assertion (abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) <= 0.01) must be embedded in the production code path and must fire for every committed cross-border transaction.
**Steps:** Add test_RV10_service_charge_separation() with service_charge=5000 MNT; Assert collection_amount==40897.44 MNT; Assert send_amount==35897.44 MNT (same as RV-01; service_charge did not change send_amount); Assert pool-identity uses only collection_usd, margins, and payout_usd_cost (assert a method that excludes service_charge); Assert service_charge is recorded in a separate revenue field, not as part of collection_usd; Add test_pool_identity_assertion_embedded() that calls the production code path and verifies the assertion fires on every cross-border committed transaction
**Deliverable:** Two unit tests test_RV10 and test_pool_identity_assertion_embedded, both green
**Acceptance / logic checks:**
- RV-10 asserts send_amount == 35897.44 MNT (unchanged from RV-01 despite large service_charge)
- RV-10 asserts collection_amount == 40897.44 MNT
- RV-10 asserts service_charge is not added to collection_usd in the pool computation
- Pool-identity assertion test confirms assertion is invoked on every cross-border transaction path
- Pool-identity test confirms that a violation (e.g. manually perturbed collection_usd) raises a CRITICAL alert
**Depends on:** 15.1-T32

### 15.1-T34 — Unit test: verify prefunding atomic deduction and race-condition guard  _(50 min)_
**Context:** QA-12 section 5.6 PF-001 to PF-004. PF-001: SELECT FOR UPDATE acquired, balance decremented by collection_usd (e.g. balance=50000, deduction=10.2564, new balance=49989.7436). PF-002: CPM deduction occurs at QR generate, timestamp predates scheme call. PF-003: race condition - two concurrent requests from same partner; exactly one deduction per transaction; second request waits for lock. PF-004: balance=9000 USD, collection_usd=10000 USD; rejected before any scheme call; INSUFFICIENT_PREFUNDING error; balance unchanged. The SELECT FOR UPDATE mechanism must be verified at the service/repository layer using a test transaction or mock.
**Steps:** Add test_prefund_atomic_deduction_mpm() calling the prefunding service with balance=50000 and collection_usd=10.2564; assert new balance==49989.7436 and SELECT FOR UPDATE was used (verify via mock or DB wrapper); Add test_prefund_deduction_predates_scheme() asserting deduction timestamp < scheme call timestamp (use mock clock or captured timestamps); Add test_prefund_concurrent_race() spawning two threads/coroutines simultaneously; assert exactly one deduction per transaction and no double-spend; Add test_prefund_insufficient() with balance=9000 and collection_usd=10000; assert INSUFFICIENT_PREFUNDING and balance unchanged
**Deliverable:** Four unit/integration tests for prefunding covering PF-001 to PF-004, all green
**Acceptance / logic checks:**
- test_prefund_atomic_deduction_mpm asserts new balance == 50000 - 10.2564 == 49989.7436 within 0.01
- test_prefund_concurrent_race asserts exactly one successful deduction and one rejected request
- test_prefund_insufficient asserts INSUFFICIENT_PREFUNDING error and balance remains 9000
- test_prefund_deduction_predates_scheme asserts deduction_timestamp < scheme_call_timestamp
**Depends on:** 15.1-T33

### 15.1-T35 — Unit test: verify TTL computation, rate-lock, and rate-quote expiry  _(40 min)_
**Context:** QA-12 section 5.1 HC-005 and HC-008. Rate-quote TTL: validUntil = quote_issued_at + ttl_seconds (default 60s for aggregator-bound, 300s otherwise; configurable 60-1800s). HC-005: partner waits beyond TTL before committing; system returns RATE_QUOTE_EXPIRED; no prefunding deduction. HC-008 rate lock: once committed, all USD-pool values and derived rates are permanently recorded; subsequent treasury rate changes do not affect the committed transaction. These are pure-logic tests at the rate-engine and payment-service layer.
**Steps:** Add test_rate_quote_expired() with a quote issued_at=T, ttl=60s, commit_at=T+61s; assert RATE_QUOTE_EXPIRED and no prefunding deduction; Add test_rate_quote_within_ttl() with commit_at=T+59s; assert no expiry error; Add test_rate_lock_after_commit() that commits a transaction at treasury.usd_krw=1350.00, then updates treasury.usd_krw to 1400.00, then retrieves the committed transaction; assert recorded cost_rate_pay is still 1350.00; Add test_configurable_ttl() verifying ttl=300s is accepted and ttl=59s is rejected as below minimum
**Deliverable:** Four unit tests for TTL and rate-lock in the payment or rate-engine test file, all green
**Acceptance / logic checks:**
- test_rate_quote_expired asserts RATE_QUOTE_EXPIRED when commit_at > validUntil
- test_rate_quote_expired asserts no prefunding deduction occurred
- test_rate_lock_after_commit asserts committed cost_rate_pay == 1350.00 even after treasury rate changes
- test_configurable_ttl rejects ttl=59s (below 60s minimum) with a validation error
**Depends on:** 15.1-T34

### 15.1-T36 — Integration test: verify prefunding SELECT FOR UPDATE and low-balance alert  _(50 min)_
**Context:** QA-12 section 5.6 PF-005 to PF-008 and PF-003 race condition at integration level (with real DB). PF-005: balance=0, any OVERSEAS payment attempt suspended with INSUFFICIENT_PREFUNDING and alert fired. PF-006: balance drops below partner threshold (e.g. P-TEST-002 threshold=10000 USD), email alert sent to partner contact, transaction continues. PF-007: Ops changes threshold for P-TEST-002; new threshold applied to next deduction. PF-008: cancel of a payment restores prefunding balance by exact collection_usd amount. Tests use the local docker-compose DB (port 5433) with synthetic partner P-TEST-002 seeded at various balance states from section 3.4.
**Steps:** Seed P-TEST-002 with balance=0 (Depleted state from section 3.4); attempt OVERSEAS payment; assert INSUFFICIENT_PREFUNDING and balance unchanged; Seed P-TEST-002 with balance=9500 (Low state); make payment with collection_usd=100; assert email alert triggered (mock email service); assert payment succeeds; Update P-TEST-002 threshold to 5000 via Admin API; make payment that drops balance to 4900; assert new threshold applied (alert fires at 4900, not 10000); Make a payment then cancel; assert prefunding balance is restored by exact collection_usd amount
**Deliverable:** Integration test suite covering PF-005 to PF-008 (4 scenarios), all green against local docker-compose DB
**Acceptance / logic checks:**
- PF-005 test asserts INSUFFICIENT_PREFUNDING and balance remains 0
- PF-006 test asserts email alert mock was called exactly once and payment succeeded
- PF-007 test asserts old threshold=10000 no longer triggers alert but new threshold=5000 does
- PF-008 test asserts balance_after_cancel == balance_before_payment exactly
**Depends on:** 15.1-T35, 15.1-T25

### 15.1-T37 — Integration test: Admin System rule save and audit log for 2% margin enforcement  _(50 min)_
**Context:** QA-12 scenarios AD-006 (m_a+m_b=2.0% saved), AD-007 (m_a+m_b=1.9% rejected), AD-011 (audit log shows actor, timestamp, old value, new value), AD-012 (rule change applies to new transactions only). These require an integration test hitting the Admin API with the local docker-compose environment. Rule structure: partner_id, scheme_id, direction, m_a (decimal), m_b (decimal). Validation constraint: for cross-border rules m_a + m_b >= 0.020; for same-currency rules m_a + m_b >= 0.0.
**Steps:** POST to Admin API to save a rule with m_a=0.010 m_b=0.010 for P-TEST-002; assert HTTP 200 and rule persisted; POST a rule with m_a=0.010 m_b=0.009 (combined=1.9%); assert HTTP 422 and error message references 2.0% minimum; Update an existing rule m_a from 0.010 to 0.012; query audit log; assert log entry contains actor_id, timestamp, old_value=0.010, new_value=0.012; Make a payment that uses the old rule (via an in-flight rate quote), then change the rule m_a; verify the committed payment used the old m_a value and a new quote uses the new m_a
**Deliverable:** Integration test suite for Admin rule save and audit log (4 scenarios), all green
**Acceptance / logic checks:**
- m_a+m_b=2.0% rule save returns HTTP 200 and rule is retrievable via GET
- m_a+m_b=1.9% save returns HTTP 422 with error message referencing 2.0% minimum
- Audit log entry for m_a change contains actor_id, timestamp, old_value, new_value fields
- In-flight payment committed before rule change uses old m_a; new payment after change uses new m_a
**Depends on:** 15.1-T36

### 15.1-T38 — Contract test: Partner API schema validation against openapi/partner-api.yaml  _(55 min)_
**Context:** QA-12 section 7 requires contract tests against openapi/partner-api.yaml (API-05). All 8 endpoints must be covered: POST /v1/auth/token, GET /v1/rates, POST /v1/payments, GET /v1/payments/{id}, POST /v1/payments/{id}/cancel, POST /v1/payments/cpm/generate, GET /v1/balance, GET /v1/transactions. Tests must use a contract-test framework (Dredd, Pact, or schemathesis). Zero schema mismatches and all documented error codes must be exercised (11 codes from QA-12 section 7.4). Run against the sandbox environment.
**Steps:** Set up schemathesis (or Dredd) to point to the deployed sandbox and load openapi/partner-api.yaml; Configure schemathesis to run stateful tests across all 8 endpoints; Add explicit test cases for each of the 11 required error codes: INVALID_CREDENTIALS, RATE_QUOTE_EXPIRED, INSUFFICIENT_PREFUNDING, MERCHANT_INACTIVE, QR_DEACTIVATED, PARTNER_B_QUOTE_DEVIATION, PARTNER_B_QUOTE_UNAVAILABLE, NO_SCHEME_FOR_LOCATION, DUPLICATE_IDEMPOTENCY_KEY, PAYMENT_NOT_FOUND, CANCEL_NOT_ALLOWED; Run the suite and assert 0 schema mismatches; Add GET /v1/payments/{id} test with another partner's token asserting HTTP 403
**Deliverable:** Contract test suite configured against openapi/partner-api.yaml, runnable via CI, passing with 0 schema mismatches
**Acceptance / logic checks:**
- Suite covers all 8 endpoints
- All 11 error codes are triggered by at least one test case
- GET /v1/payments/{id} with wrong-partner token asserts HTTP 403 (IDOR protection)
- Suite exits with 0 schema mismatches when run against sandbox
- Test results are output in a JUnit-compatible XML report for CI ingestion
**Depends on:** 15.1-T26

### 15.1-T39 — Contract test: webhook delivery, payload schema, and retry behavior  _(50 min)_
**Context:** QA-12 section 7.3 defines 4 webhook events: payment.pending_debit (required fields: txn_id, offer_rate, collection_amount, validUntil), payment.approved (txn_id, status, collection_amount, cross_rate), payment.failed (txn_id, status, error_code), payment.cancelled (txn_id, status). Retry policy: if partner webhook returns non-2xx, system retries with exponential backoff; test scenario: mock returns 500 for first 3 attempts, delivers on 4th; payload is identical on each retry (idempotent). Tests run against sandbox environment with a mock webhook receiver.
**Steps:** Create a mock webhook receiver that records incoming payloads and returns configurable HTTP status codes; Trigger a Domestic MPM payment and assert payment.approved webhook is delivered with txn_id, status, collection_amount, cross_rate; Trigger a CPM payment and assert payment.pending_debit contains offer_rate and validUntil; Configure mock to return 500 for first 3 calls then 200; assert 4 attempts total and payload identical on all attempts; Trigger a failed payment and assert payment.failed contains error_code
**Deliverable:** Webhook contract test suite with mock receiver, covering all 4 event types and retry behavior, running in sandbox
**Acceptance / logic checks:**
- payment.approved webhook contains txn_id, status, collection_amount, cross_rate fields
- payment.pending_debit webhook contains offer_rate and validUntil fields
- Retry test asserts exactly 4 delivery attempts when first 3 return 500
- Retry payload on attempt 4 is byte-for-byte identical to attempt 1 (idempotency)
- payment.failed webhook contains error_code field
**Depends on:** 15.1-T38

### 15.1-T40 — Create UAT test scripts document for all 13 UAT scenarios  _(50 min)_
**Context:** QA-12 section 10.2 defines 13 UAT scenarios (UAT-001 to UAT-013) to be exercised by GME Ops and GME Remit in the staging environment. UAT test scripts must be prepared two weeks before UAT start (QA-12 section 12.1). Each script must be usable by a non-technical Ops role and must contain: scenario title, role, preconditions, step-by-step instructions in plain English, expected result, and pass/fail sign-off field. Key scenarios: UAT-001 new OVERSEAS partner in < 30 min (timed), UAT-003 MPM domestic collection=payout+KRW 500, UAT-013 config-without-code (no deployment).
**Steps:** Create docs/test-plan/uat-scripts.md with one H2 section per UAT-001 to UAT-013; Each section must include: Scenario-ID, Title, Role, Preconditions, Steps (numbered plain-English instructions), Expected-result, Actual-result field, Pass/Fail checkbox, Tester signature line; UAT-001 must include a stopwatch/timer instruction and a < 30 min pass criterion; UAT-013 must include an instruction to verify no Git merge or deployment occurred during UAT-001 (e.g. check CI/CD pipeline log); UAT-003 expected result must state collection_amount = target_payout + KRW 500
**Deliverable:** docs/test-plan/uat-scripts.md with 13 UAT script sections, each containing all 8 required fields
**Acceptance / logic checks:**
- All 13 UAT scenarios (UAT-001 to UAT-013) have a dedicated section
- UAT-001 includes a timer instruction and states pass criterion is < 30 minutes
- UAT-003 expected result states collection_amount = target_payout + KRW 500 (specific formula)
- UAT-013 includes a step to verify no Git merge or deploy occurred
- Each section has a Pass/Fail checkbox and Tester signature line
**Depends on:** 15.1-T20

### 15.1-T41 — Create go-live readiness checklist as a trackable document  _(30 min)_
**Context:** QA-12 section 12.2 defines a 14-item go-live readiness checklist that must be completed the day before go-live (per section 12.1). Items include: all P1 UAT signed off, zero P1/P2 defects, RV-01 to RV-10 pass in production-equivalent env, ZeroPay SFTP credentials live against production KFTC, GME Remit record configured in Admin (not sandbox), SendMN prefunding confirmed with Finance, all ZP00xx batch jobs scheduled per OPS-13 runbook, low-balance alert email addresses confirmed, DR drill completed in staging, monitoring dashboards and alert thresholds live in production, BOK reporting tested (pending OI-03), pool-identity assertion alert wired to OPS channel, rate-lock verified in production env, Ops walkthrough completed with setup-time confirmed.
**Steps:** Create docs/test-plan/go-live-checklist.md; List all 14 items as a numbered checklist with [ ] checkboxes; For each item add: item number, criterion text, responsible role, and verification method column; Item 3 verification method: run rate-engine test suite in production-equivalent env and check all RV vectors green; Item 12 verification method: trigger a test cross-border transaction and confirm CRITICAL alert fires if pool-identity is violated; Add a footer with: QA Lead sign-off, GME Product Owner sign-off, and date fields
**Deliverable:** docs/test-plan/go-live-checklist.md with 14 numbered items each having criterion, responsible role, verification method, and checkbox, plus sign-off footer
**Acceptance / logic checks:**
- Checklist has exactly 14 numbered items
- Item 3 states RV-01 to RV-10 and production-equivalent environment
- Item 12 states pool-identity assertion wired to OPS alerting channel
- Item 9 (DR drill) states must be completed in staging before go-live UAT sign-off
- Footer includes QA Lead sign-off, GME Product Owner sign-off, and date fields
**Depends on:** 15.1-T22

### 15.1-T42 — Write defect report template for P1 and P2 defects  _(25 min)_
**Context:** QA-12 section 11.2 defines the defect triage process: developer raises defect with steps to reproduce, severity, and affected component. Daily triage for P1/P2. P1 requires fix and re-test within same sprint. P2 requires fix before UAT entry. A standardised defect report template ensures all required fields are present and triage can proceed efficiently. Template must cover: defect ID, title, severity (P1/P2/P3/P4), affected component (Hub Core/Admin/Partner Portal/Partner API/ZeroPay/Prefunding), steps to reproduce, expected result, actual result, environment, test case ID(s), reporter, date raised, assignee, fix-by date (auto-set based on severity), status, resolution notes.
**Steps:** Create docs/test-plan/defect-report-template.md with all fields listed above as a Markdown table or form; Add a severity-based fix-by-date rule: P1 = same sprint, P2 = before UAT entry, P3/P4 = sprint priority; Add a checklist of mandatory fields that must be filled before triage: severity, component, steps to reproduce, test case ID; Add example P1 entry: pool identity violated in HC-015 with steps that trigger it
**Deliverable:** docs/test-plan/defect-report-template.md with all fields, severity rules, mandatory checklist, and example P1 entry
**Acceptance / logic checks:**
- Template includes all fields: defect-ID, severity, component, steps-to-reproduce, expected, actual, environment, test-case-ID, reporter, date, fix-by
- Fix-by rule states P1 = same sprint and P2 = before UAT entry
- Mandatory fields checklist is present (minimum 4 items)
- Example P1 entry references HC-015 and pool-identity violation
- Template is usable without any other document (self-contained)
**Depends on:** 15.1-T21

### 15.1-T43 — Create CI sprint test summary report template  _(30 min)_
**Context:** QA-12 section 12.3 requires three reporting cadences: (1) Sprint test summary generated from CI/CD on every PR and every nightly E2E run, showing pass count, fail count, coverage delta. (2) Weekly test status report from QA lead to PMO with open defects, test-level progress, and blockers. (3) UAT daily progress report during UAT with scenario status and defect count by severity. The sprint test summary must be machine-generated; the weekly and UAT reports are human-authored from a template.
**Steps:** Create docs/test-plan/report-templates/sprint-summary.md with fields: run-date, branch, commit-SHA, test-level, pass-count, fail-count, coverage-percent, coverage-delta, new-failures, blockers; Create docs/test-plan/report-templates/weekly-status.md with fields: week-ending, test-level-status-table (Unit/Integration/Contract/System-E2E/Performance/Security), open-defects-by-severity, blockers, next-week-goals; Create docs/test-plan/report-templates/uat-daily.md with fields: UAT-day, scenarios-completed, scenarios-passed, scenarios-failed, open-defects-by-severity, blockers, next-day-plan; Add instructions at the top of each template on how/when to fill it
**Deliverable:** Three report template files in docs/test-plan/report-templates/ covering sprint summary, weekly status, and UAT daily progress
**Acceptance / logic checks:**
- sprint-summary.md includes coverage-delta field (not just absolute coverage)
- weekly-status.md has a test-level-status table covering all 6 automated levels
- uat-daily.md has open-defects-by-severity broken down into P1, P2, P3, P4 columns
- All three templates have a usage-instructions header
- sprint-summary.md notes it should be auto-generated by CI, not filled manually
**Depends on:** 15.1-T41

### 15.1-T44 — Perform QA-12 internal consistency review and cross-reference check  _(45 min)_
**Context:** After all QA-12 sections are written, an internal consistency review is required to ensure: all scenario IDs referenced in section 6 (traceability) exist in section 5; all RV vector IDs in section 4 are referenced in section 6.2; all UAT IDs in section 10.2 align with section 11.3 exit gates; all environment names in section 2.3 match the environments document; all 14 go-live checklist items align with section 12.2; the 11 error codes in section 7.4 match a valid entry in each of sections 5.1, 5.4, or 7.2.
**Steps:** Read sections 5, 6, 4, 7, 10, 11, 12 of QA-12.md; Build a cross-reference table: each scenario ID in section 6 -> verify it exists in section 5; Verify all 10 RV vector IDs (RV-01 to RV-10) appear in section 6.2 rule-to-test mapping; Verify all 11 error codes from section 7.4 are present in at least one section 5 scenario expected result or section 7.2 test scenario; Verify section 2.3 environment names match environments.md; Document any gap as a defect in the review log and fix it in QA-12.md
**Deliverable:** QA-12 internal consistency review log (inline comments or a review-log.md) confirming zero dangling cross-references, plus any corrections applied
**Acceptance / logic checks:**
- All scenario IDs in section 6 traceability table exist in section 5
- All 10 RV vector IDs are referenced in section 6.2
- All 11 mandatory error codes from section 7.4 appear in at least one section 5 or 7.2 test scenario
- Section 2.3 environment names match environments.md (4 environments with consistent names)
- Any gaps found during review are fixed and documented in the review log
**Depends on:** 15.1-T23, 15.1-T43


## WBS 15.2 — Test data management
### 15.2-T01 — Define SQL schema for synthetic partner seed table  _(30 min)_
**Context:** WBS 15.2 Test Data Management. QA-12 §3.1 specifies 5 synthetic partner records (P-TEST-001 to P-TEST-005) to be loaded into the test DB. Partners have fields: partner_id (VARCHAR PK), name, type (LOCAL|OVERSEAS), settle_a_ccy (VARCHAR 3), prefunding_model (INTERNAL|EXTERNAL), is_active (BOOLEAN). A migration file must create table test_seed_partners with a unique constraint on partner_id and an environment guard (env != 'prod').
**Steps:** Create migration file db/migrations/V9900__test_seed_partners.sql; Define table test_seed_partners with columns: partner_id VARCHAR(20) PK, name VARCHAR(100), type VARCHAR(10) CHECK (type IN ('LOCAL','OVERSEAS')), settle_a_ccy CHAR(3), prefunding_model VARCHAR(10), is_active BOOLEAN DEFAULT TRUE; Add check constraint to block insertion when current_database() matches the production DB name pattern; Document column meanings in SQL comments
**Deliverable:** Migration file db/migrations/V9900__test_seed_partners.sql
**Acceptance / logic checks:**
- Table creates successfully in local PostgreSQL on port 5433 via Flyway or equivalent
- INSERT of a row with type='INVALID' is rejected by the CHECK constraint
- Schema matches partner_id, name, type, settle_a_ccy, prefunding_model, is_active columns exactly
- Migration is idempotent when re-run (V-versioned, not repeatable)

### 15.2-T02 — Write SQL seed script for synthetic partner rows (P-TEST-001 to P-TEST-005)  _(25 min)_
**Context:** QA-12 §3.1 defines 5 synthetic partners. P-TEST-001 TestRemit: LOCAL, KRW, INTERNAL, ZeroPay. P-TEST-002 TestSendMN: OVERSEAS, USD, EXTERNAL, ZeroPay, 50000 USD balance. P-TEST-003 TestHub: OVERSEAS, USD, EXTERNAL, ZeroPay, 100000 USD balance. P-TEST-004 TestManual: OVERSEAS, EUR, EXTERNAL, ZeroPay, MANUAL rate. P-TEST-005 TestPartnerB: OVERSEAS, USD, EXTERNAL, ZeroPay, PARTNER_B quote mode. All records must be idempotent (INSERT ... ON CONFLICT DO NOTHING) and tagged with a source label 'QA-12' in a notes column.
**Steps:** Create seed file db/seeds/test_partners.sql; Write INSERT ... ON CONFLICT (partner_id) DO NOTHING statements for all 5 rows; Include source_doc='QA-12' in each row; Verify the seed file runs cleanly twice in a row without error in the local test DB; Cross-check partner_id values against QA-12 §3.1 table exactly
**Deliverable:** SQL seed file db/seeds/test_partners.sql with 5 idempotent partner rows
**Acceptance / logic checks:**
- Running the file twice produces exactly 5 rows total, not 10
- P-TEST-001 has type='LOCAL' and settle_a_ccy='KRW'
- P-TEST-002 has type='OVERSEAS' and settle_a_ccy='USD'
- P-TEST-004 has settle_a_ccy='EUR' (EUR leg, MANUAL rate source)
- SELECT COUNT(*) FROM test_seed_partners WHERE source_doc='QA-12' returns 5
**Depends on:** 15.2-T01

### 15.2-T03 — Define SQL schema for synthetic scheme seed table  _(25 min)_
**Context:** WBS 15.2. QA-12 §3.2 requires a synthetic ZeroPay scheme record. Scheme table (if not already in production schema, use a seed record in the existing schemes table) must have: scheme_id VARCHAR PK, name, operator_name, is_active BOOLEAN, env_guard. The synthetic scheme is scheme_id='SCH-TEST-ZEROPAY', name='ZeroPay Test', operator_name='KFTC-TEST'. The seed must be loadable in dev/int/staging only and must not be present in production.
**Steps:** Check whether a schemes table already exists in the production migration history; If it exists, write an idempotent INSERT for the test record into db/seeds/test_schemes.sql; If it does not exist, create migration db/migrations/V9901__test_seed_schemes.sql with table definition and insert; Add a runtime env check: abort insert if APP_ENV=prod; Document the scheme_id value used in comments for downstream tickets
**Deliverable:** File db/seeds/test_schemes.sql containing idempotent ZeroPay test scheme seed row
**Acceptance / logic checks:**
- SCH-TEST-ZEROPAY row present after seed in dev/int environment
- Running seed twice returns 1 row, not 2
- scheme_id='SCH-TEST-ZEROPAY' and operator_name='KFTC-TEST' match spec values exactly
- Seed aborts or skips when APP_ENV environment variable equals 'prod'
**Depends on:** 15.2-T01

### 15.2-T04 — Define SQL schema for synthetic merchant seed table  _(30 min)_
**Context:** QA-12 §3.2 specifies 5 synthetic merchants. Fields needed: merchant_id VARCHAR PK, name, merchant_type (Individual|Franchise), status (Active|Inactive), qr_code VARCHAR UNIQUE, fee_rate NUMERIC(6,4), source_doc VARCHAR. Records: M-TEST-0001 TestCafe Active Individual Active QR-TEST-0001 0.80%; M-TEST-0002 TestMart Inactive Individual Inactive QR-TEST-0002 0.80%; M-TEST-0003 TestChain Franchise Franchise Active QR-TEST-0003 1.20%; M-TEST-0004 TestBig CrossBorder Individual Active QR-TEST-0004 1.70%; M-TEST-0005 TestDeact QR Individual Active QR-TEST-0005 (qr_code deactivated) 0.80%.
**Steps:** Create migration db/migrations/V9902__test_seed_merchants.sql if merchants table not already defined; Define columns: merchant_id, name, merchant_type, status, qr_code, fee_rate, qr_active BOOLEAN, source_doc; Ensure qr_code has UNIQUE constraint; Add CHECK constraint: fee_rate BETWEEN 0.0 AND 0.10 (max 10%)
**Deliverable:** Migration or schema check for merchants table with qr_active column and fee_rate constraint
**Acceptance / logic checks:**
- fee_rate=1.80 (>10%) is rejected by CHECK constraint
- qr_code duplicate insert raises unique constraint violation
- Columns merchant_id, name, merchant_type, status, qr_code, fee_rate, qr_active, source_doc all present
- Migration is idempotent
**Depends on:** 15.2-T01

### 15.2-T05 — Write SQL seed script for synthetic merchant rows (M-TEST-0001 to M-TEST-0005)  _(25 min)_
**Context:** QA-12 §3.2 defines 5 synthetic merchants. M-TEST-0001: name='TestCafe Active', merchant_type='Individual', status='Active', qr_code='QR-TEST-0001', fee_rate=0.0080, qr_active=TRUE. M-TEST-0002: name='TestMart Inactive', status='Inactive', qr_code='QR-TEST-0002', qr_active=TRUE. M-TEST-0003: name='TestChain Franchise', merchant_type='Franchise', status='Active', qr_code='QR-TEST-0003', fee_rate=0.0120, qr_active=TRUE. M-TEST-0004: name='TestBig CrossBorder', status='Active', qr_code='QR-TEST-0004', fee_rate=0.0170, qr_active=TRUE. M-TEST-0005: name='TestDeact QR', status='Active', qr_code='QR-TEST-0005', fee_rate=0.0080, qr_active=FALSE (QR deactivated).
**Steps:** Create file db/seeds/test_merchants.sql; Write idempotent INSERT ... ON CONFLICT (merchant_id) DO NOTHING for all 5 rows; Set qr_active=FALSE for M-TEST-0005 (used in test HC-012 for QR_DEACTIVATED scenario); Set status='Inactive' for M-TEST-0002 (used in HC-011 for MERCHANT_INACTIVE scenario); Tag all rows source_doc='QA-12'
**Deliverable:** SQL seed file db/seeds/test_merchants.sql with 5 idempotent merchant rows
**Acceptance / logic checks:**
- M-TEST-0002 has status='Inactive' and qr_active=TRUE confirming merchant-status vs QR-status are separate fields
- M-TEST-0005 has status='Active' and qr_active=FALSE confirming deactivated QR on active merchant
- M-TEST-0003 has merchant_type='Franchise' and fee_rate=0.0120
- Running the file twice leaves exactly 5 rows
- M-TEST-0004 has fee_rate=0.0170 (highest, for cross-border merchant fee test)
**Depends on:** 15.2-T04

### 15.2-T06 — Write SQL seed script for treasury rate test fixtures  _(25 min)_
**Context:** QA-12 §3.3 defines 5 treasury rate fixtures stored as key-value pairs in a treasury_rates table with columns: rate_key VARCHAR PK, rate_value NUMERIC(18,6), env, source_doc. Convention: treasury.usd_{ccy} = units of ccy per 1 USD. Values: treasury.usd_krw=1350.000000, treasury.usd_mnt=3500.000000, treasury.usd_usd=1.000000, treasury.usd_eur=0.920000, treasury.usd_thb=35.500000. These are TEST FIXTURES ONLY — must not propagate to production. Rate engine uses cost_rate_coll = treasury.usd_{settle_a_ccy} and cost_rate_pay = treasury.usd_{settle_b_ccy}.
**Steps:** Create file db/seeds/test_treasury_rates.sql; Write idempotent INSERT ... ON CONFLICT (rate_key) DO UPDATE SET rate_value=EXCLUDED.rate_value for all 5 rows; Set env='test' on all rows so production rate queries can filter by env; Include a comment block quoting QA-12 A-02: illustrative rates for test fixtures only; real rates loaded by GME Ops
**Deliverable:** SQL seed file db/seeds/test_treasury_rates.sql with 5 treasury rate rows
**Acceptance / logic checks:**
- treasury.usd_krw=1350.000000 exactly (6 decimal places stored)
- treasury.usd_usd=1.000000 exactly (identity rate)
- Running file twice updates values in-place, no duplicate rows
- All 5 rows have env='test'
- treasury.usd_eur=0.920000 (not 0.92, full precision stored)
**Depends on:** 15.2-T01

### 15.2-T07 — Write SQL seed script for prefunding balance test fixtures  _(35 min)_
**Context:** QA-12 §3.4 defines prefunding balance states for test. Table partner_prefunding_balances (or equivalent) needs rows: P-TEST-002 Normal 50000.00 USD, P-TEST-002 Low 9500.00 USD, P-TEST-002 Depleted 0.00 USD, P-TEST-003 Normal 100000.00 USD. Because one partner can only have one live balance, seed inserts a balance for each named state as separate seed profiles keyed by (partner_id, balance_profile). A test helper must be able to restore a specific balance state by profile name before each test scenario.
**Steps:** Create file db/seeds/test_prefunding_balances.sql; Define a seed profiles table: test_prefunding_profiles(partner_id, profile_name, usd_amount); Insert 4 rows per the QA-12 §3.4 table; Create a helper SQL function set_test_prefunding(p_partner_id TEXT, p_profile TEXT) that copies the profile amount into the live partner_prefunding_balances table; Tag all rows source_doc='QA-12'
**Deliverable:** SQL seed file db/seeds/test_prefunding_balances.sql plus helper function set_test_prefunding()
**Acceptance / logic checks:**
- set_test_prefunding('P-TEST-002', 'Low') sets live balance to 9500.00 USD exactly
- set_test_prefunding('P-TEST-002', 'Depleted') sets live balance to 0.00 USD
- P-TEST-003 Normal profile has usd_amount=100000.00
- Calling the helper with unknown partner_id raises a meaningful error, not a silent no-op
- Running seed file twice does not duplicate rows (ON CONFLICT DO NOTHING)
**Depends on:** 15.2-T02

### 15.2-T08 — Write SQL seed script for partner-to-scheme routing rules (test fixtures)  _(35 min)_
**Context:** QA-12 §3.5 requires all synthetic partners to have routing rules configured in Admin. Rules link a partner to a scheme with direction, margin, and rate-source config. Required rules: P-TEST-001 to ZeroPay, Domestic, m_a=0.0, m_b=0.0, settle_a=KRW, settle_b=KRW (same-currency short-circuit). P-TEST-002 to ZeroPay, Inbound, m_a=0.015, m_b=0.010, settle_a=USD, settle_b=KRW. P-TEST-003 to ZeroPay, Hub, m_a=0.015, m_b=0.010, settle_a=USD, settle_b=USD. P-TEST-004 to ZeroPay, Inbound, MANUAL rate source, settle_a=EUR, settle_b=KRW, m_a=0.015, m_b=0.010. P-TEST-005 to ZeroPay, Inbound, PARTNER_B rate source, settle_a=USD, settle_b=KRW, m_a=0.015, m_b=0.010.
**Steps:** Create file db/seeds/test_routing_rules.sql; Insert one rule per synthetic partner using the fields: rule_id, partner_id, scheme_id, direction, settle_a_ccy, settle_b_ccy, m_a, m_b, rate_source (LIVE|MANUAL|PARTNER_B), is_active; Use scheme_id='SCH-TEST-ZEROPAY' from T03 for all rules; Verify P-TEST-001 rule has m_a+m_b=0.0 (domestic, allowed to be zero); Verify P-TEST-002, P-TEST-003, P-TEST-004, P-TEST-005 rules each have m_a+m_b >= 0.02 (cross-border minimum)
**Deliverable:** SQL seed file db/seeds/test_routing_rules.sql with 5 routing rule rows
**Acceptance / logic checks:**
- P-TEST-001 rule has m_a=0.000 and m_b=0.000 and settle_a_ccy=settle_b_ccy='KRW' (same-currency)
- P-TEST-002 rule has m_a=0.015, m_b=0.010, m_a+m_b=0.025 >= 0.020 (cross-border OK)
- P-TEST-004 rule has rate_source='MANUAL' and settle_a_ccy='EUR'
- P-TEST-005 rule has rate_source='PARTNER_B'
- SELECT COUNT(*) FROM test_routing_rules returns 5
**Depends on:** 15.2-T02, 15.2-T03

### 15.2-T09 — Define sandbox QR trigger values seed for simulated scheme responses  _(25 min)_
**Context:** API-05 §10.3 specifies special merchant_qr values in sandbox that trigger scheme simulation. Required entries: ZPQR_TEST_APPROVED (approved immediately), ZPQR_TEST_PENDING (pending 30s then approved), ZPQR_TEST_DECLINED (SCHEME_UNAVAILABLE), ZPQR_TEST_TIMEOUT (timeout, payment uncertain), ZPQR_TEST_INACTIVE (MERCHANT_NOT_FOUND). These must be stored in a sandbox_qr_triggers table: qr_code VARCHAR PK, simulated_outcome VARCHAR, delay_seconds INT DEFAULT 0.
**Steps:** Create migration db/migrations/V9903__sandbox_qr_triggers.sql defining table sandbox_qr_triggers; Insert 5 rows with exact qr_code values and outcomes matching API-05 §10.3; Set delay_seconds=30 for ZPQR_TEST_PENDING, 0 for all others; Add env guard: table only created when APP_ENV IN ('dev','int','staging'); Cross-reference each trigger value to the test scenario it supports (HC-001, HC-004, HC-011, HC-012)
**Deliverable:** Migration db/migrations/V9903__sandbox_qr_triggers.sql with 5 trigger rows
**Acceptance / logic checks:**
- ZPQR_TEST_APPROVED row has simulated_outcome='APPROVED' and delay_seconds=0
- ZPQR_TEST_PENDING row has delay_seconds=30
- ZPQR_TEST_DECLINED row has simulated_outcome='SCHEME_UNAVAILABLE'
- ZPQR_TEST_TIMEOUT row has simulated_outcome='TIMEOUT'
- ZPQR_TEST_INACTIVE row has simulated_outcome='MERCHANT_NOT_FOUND'
**Depends on:** 15.2-T01

### 15.2-T10 — Build SeedLoader Java class to orchestrate all seed scripts in order  _(45 min)_
**Context:** WBS 15.2. All seed SQL files (T02, T05, T06, T07, T08, T09) must be applied in dependency order when setting up a fresh test environment. A SeedLoader utility class is needed in src/test/java/.../testdata/SeedLoader.java. It must: (1) detect APP_ENV and abort if env=prod; (2) apply each seed file in topological order via JDBC; (3) be callable from integration test setUp() and from a CLI command; (4) be idempotent (safe to call multiple times).
**Steps:** Create src/test/java/com/gmepayplus/testdata/SeedLoader.java; Implement loadAll(DataSource ds, String env) method that runs each seed SQL file in order: test_schemes, test_partners, test_merchants, test_treasury_rates, test_routing_rules, test_prefunding_balances, sandbox_qr_triggers; Add a guard: throw IllegalStateException if env.equals('prod'); Expose a reset(DataSource ds, String env) method that truncates seed tables then reloads; Add a main() method for CLI use: java -jar ... SeedLoader --env=int
**Deliverable:** Class com.gmepayplus.testdata.SeedLoader with loadAll() and reset() methods
**Acceptance / logic checks:**
- loadAll() called twice on a clean DB results in exactly 5 partner rows, 5 merchant rows, 5 treasury rates
- reset() followed by loadAll() leaves DB in canonical seed state (row counts match seed files)
- Calling loadAll() with env='prod' throws IllegalStateException with message containing 'prod'
- Method completes without exception when all seed SQL files are present on classpath
- Logging output shows each seed file applied in the correct dependency order
**Depends on:** 15.2-T02, 15.2-T05, 15.2-T06, 15.2-T07, 15.2-T08, 15.2-T09

### 15.2-T11 — Implement SandboxControlApi endpoint to set prefunding balance for a test partner  _(45 min)_
**Context:** API-05 §sandbox specifies a sandbox control API allowing partners and test tooling to set prefunding balance to any value. Endpoint: POST /v1/sandbox/partners/{partner_id}/prefunding with body {usd_amount: 50000.00}. This endpoint must only be active when APP_ENV != 'prod'. It writes directly to the partner_prefunding_balances table. Auth: requires a sandbox-admin API key (separate from production partner keys, prefixed sk_test_). Updates must be logged in the audit table with actor='SANDBOX_CONTROL'.
**Steps:** Create handler SandboxPrefundingController in src/main/java/.../sandbox/SandboxPrefundingController.java; Implement POST /v1/sandbox/partners/{partner_id}/prefunding accepting JSON {usd_amount: Decimal}; Add environment guard: return HTTP 404 if APP_ENV=prod; Validate usd_amount >= 0; reject negative values with HTTP 400; Write update to partner_prefunding_balances; insert audit record actor='SANDBOX_CONTROL'
**Deliverable:** SandboxPrefundingController.java implementing POST /v1/sandbox/partners/{partner_id}/prefunding
**Acceptance / logic checks:**
- POST with usd_amount=50000.00 for P-TEST-002 sets balance to 50000.00 USD in DB
- POST with usd_amount=-100 returns HTTP 400 with validation error
- Request in env=prod returns HTTP 404 (endpoint not routed)
- Audit table contains actor='SANDBOX_CONTROL' entry after successful call
- Subsequent GET /v1/balance for P-TEST-002 returns updated balance
**Depends on:** 15.2-T07

### 15.2-T12 — Implement SandboxControlApi endpoint to reset treasury rates to test fixture values  _(35 min)_
**Context:** QA-12 §3.3 and §3.5 require treasury rates to be set to test fixtures (usd_krw=1350, usd_mnt=3500, usd_usd=1.0, usd_eur=0.92, usd_thb=35.5) in sandbox. Endpoint: POST /v1/sandbox/treasury/reset — requires no body; sets all treasury rates to QA-12 §3.3 values atomically. Only active when APP_ENV != 'prod'. Auth: sandbox-admin key. Idempotent. Logs actor='SANDBOX_CONTROL' in audit.
**Steps:** Create handler SandboxTreasuryController in src/main/java/.../sandbox/SandboxTreasuryController.java; Implement POST /v1/sandbox/treasury/reset with no request body; Hard-code the 5 fixture rate values from QA-12 §3.3 as constants in the handler; Execute UPDATE treasury_rates SET rate_value=? WHERE rate_key=? for each of the 5 keys in a single transaction; Return HTTP 200 with body {reset: true, rates_updated: 5} and log audit entry
**Deliverable:** SandboxTreasuryController.java implementing POST /v1/sandbox/treasury/reset
**Acceptance / logic checks:**
- After reset, SELECT rate_value FROM treasury_rates WHERE rate_key='treasury.usd_krw' returns 1350.000000
- After reset, SELECT rate_value FROM treasury_rates WHERE rate_key='treasury.usd_usd' returns 1.000000
- Endpoint returns HTTP 200 with rates_updated=5
- In env=prod, endpoint returns HTTP 404
- Reset is atomic: no partial update if the transaction is rolled back mid-write
**Depends on:** 15.2-T06

### 15.2-T13 — Implement SandboxControlApi endpoint to configure routing rule margins for a test partner  _(40 min)_
**Context:** QA-12 §3.5 requires sandbox routing rules to be configurable per test. Endpoint: PATCH /v1/sandbox/partners/{partner_id}/rules/{rule_id} with body {m_a: 0.015, m_b: 0.010}. Allows test scripts to override m_a and m_b without going through the Admin UI. Enforces same validation as Admin: m_a+m_b >= 0.02 for cross-border, 0.0 allowed for same-currency (settle_a_ccy == settle_b_ccy). Only active when APP_ENV != 'prod'. Logs actor='SANDBOX_CONTROL'.
**Steps:** Create handler SandboxRuleController in src/main/java/.../sandbox/SandboxRuleController.java; Implement PATCH /v1/sandbox/partners/{partner_id}/rules/{rule_id} accepting {m_a, m_b}; Call existing margin validation logic: if settle_a_ccy != settle_b_ccy and m_a+m_b < 0.02, return HTTP 422 with error MIN_MARGIN_VIOLATED; Update routing_rules table; write audit entry actor='SANDBOX_CONTROL'; Return HTTP 200 with updated rule snapshot
**Deliverable:** SandboxRuleController.java implementing PATCH /v1/sandbox/partners/{partner_id}/rules/{rule_id}
**Acceptance / logic checks:**
- PATCH m_a=0.009, m_b=0.010 on a cross-border rule returns HTTP 422 (combined 1.9% < 2%)
- PATCH m_a=0.015, m_b=0.010 on P-TEST-002's Inbound rule succeeds and DB row updated
- PATCH m_a=0.0, m_b=0.0 on P-TEST-001 Domestic rule succeeds (same-currency, zero margin allowed)
- In env=prod, endpoint returns HTTP 404
- Audit table shows old and new m_a, m_b values in the change record
**Depends on:** 15.2-T08

### 15.2-T14 — Unit test: SeedLoader loads all 5 partner rows with correct field values  _(40 min)_
**Context:** WBS 15.2 unit test. SeedLoader (T10) must be verified by a unit test that loads all seed data into an in-memory or test-container PostgreSQL instance and asserts row-level correctness for all 5 partners from QA-12 §3.1.
**Steps:** Create test class SeedLoaderPartnerTest in src/test/java/.../testdata/SeedLoaderPartnerTest.java; Use Testcontainers or an embedded H2 (if schema-compatible) to spin up a fresh DB; Call SeedLoader.loadAll(ds, 'test'); Assert partner field values for all 5 rows; Assert that a second call to loadAll() does not increase the row count
**Deliverable:** Test class SeedLoaderPartnerTest.java with 5 per-row assertions and idempotency check
**Acceptance / logic checks:**
- P-TEST-001 row: type='LOCAL', settle_a_ccy='KRW', prefunding_model='INTERNAL'
- P-TEST-002 row: type='OVERSEAS', settle_a_ccy='USD', prefunding_model='EXTERNAL'
- P-TEST-004 row: settle_a_ccy='EUR'
- Second call to loadAll() leaves row count at 5, not 10
- loadAll() with env='prod' throws IllegalStateException
**Depends on:** 15.2-T10

### 15.2-T15 — Unit test: SeedLoader loads all 5 merchant rows with correct field values  _(35 min)_
**Context:** WBS 15.2 unit test. SeedLoader (T10) must be verified for merchant rows from QA-12 §3.2. Key checks: M-TEST-0002 has status=Inactive; M-TEST-0005 has qr_active=FALSE (used by HC-012 QR_DEACTIVATED test); M-TEST-0003 has merchant_type=Franchise. Fee rates must be stored with 4 decimal precision.
**Steps:** Create test class SeedLoaderMerchantTest.java; Use Testcontainers to run a fresh DB with migrations and seed applied; Assert each of the 5 merchant rows by merchant_id; Verify qr_active=FALSE for M-TEST-0005 (QR deactivated, merchant still active); Verify qr_active=TRUE and status=Inactive for M-TEST-0002 (inactive merchant, QR code exists)
**Deliverable:** Test class SeedLoaderMerchantTest.java with 5 per-row assertions
**Acceptance / logic checks:**
- M-TEST-0001: status='Active', qr_code='QR-TEST-0001', fee_rate=0.0080, qr_active=TRUE
- M-TEST-0002: status='Inactive', qr_active=TRUE
- M-TEST-0003: merchant_type='Franchise', fee_rate=0.0120
- M-TEST-0005: status='Active', qr_active=FALSE (QR deactivated on active merchant)
- M-TEST-0004: fee_rate=0.0170
**Depends on:** 15.2-T10

### 15.2-T16 — Unit test: SeedLoader loads treasury rate fixtures with correct precision  _(35 min)_
**Context:** WBS 15.2 unit test. Treasury rates from QA-12 §3.3 must be loaded with 6-decimal precision. Rate engine uses these as cost_rate_coll and cost_rate_pay. A test must confirm each of the 5 fixture rates is stored at full precision and that the identity rate for USD is exactly 1.000000.
**Steps:** Create test class SeedLoaderTreasuryRateTest.java; Apply SeedLoader in a fresh Testcontainers PostgreSQL instance; Query treasury_rates table for each of the 5 keys; Assert exact numeric equality to 6 decimal places using BigDecimal comparison; Assert that SeedLoader.reset() followed by manual update of treasury.usd_krw=1400.0 and then reset() again returns it to 1350.000000
**Deliverable:** Test class SeedLoaderTreasuryRateTest.java with rate precision and reset assertions
**Acceptance / logic checks:**
- treasury.usd_krw = 1350.000000 (BigDecimal, scale 6)
- treasury.usd_usd = 1.000000 exactly
- treasury.usd_eur = 0.920000 exactly
- treasury.usd_thb = 35.500000 exactly
- reset() restores overwritten rate back to QA-12 §3.3 values
**Depends on:** 15.2-T10

### 15.2-T17 — Unit test: routing rule seed loads correct margin and rate-source values  _(35 min)_
**Context:** WBS 15.2 unit test. Routing rules from T08 must be verified: P-TEST-001 domestic rule has m_a=m_b=0.0; all cross-border rules have m_a+m_b >= 0.02; P-TEST-004 has rate_source='MANUAL'; P-TEST-005 has rate_source='PARTNER_B'. These values drive rate-engine behaviour and must be exact.
**Steps:** Create test class SeedLoaderRoutingRuleTest.java; Apply SeedLoader, query routing_rules joined to test_partners; Assert m_a+m_b for each rule against expected value; Assert rate_source column values match spec; Assert that settle_a_ccy and settle_b_ccy for P-TEST-001 are both 'KRW'
**Deliverable:** Test class SeedLoaderRoutingRuleTest.java with per-rule margin and rate-source assertions
**Acceptance / logic checks:**
- P-TEST-001 rule: m_a=0.000, m_b=0.000, settle_a_ccy='KRW', settle_b_ccy='KRW'
- P-TEST-002 rule: m_a=0.015, m_b=0.010, m_a+m_b=0.025
- P-TEST-004 rule: rate_source='MANUAL', settle_a_ccy='EUR'
- P-TEST-005 rule: rate_source='PARTNER_B'
- No cross-border rule has m_a+m_b < 0.020
**Depends on:** 15.2-T10

### 15.2-T18 — Unit test: prefunding balance seed sets correct balances and helper function works  _(35 min)_
**Context:** WBS 15.2 unit test. set_test_prefunding() helper from T07 must be verified. Scenarios from QA-12 §3.4: P-TEST-002 Normal=50000.00, Low=9500.00, Depleted=0.00; P-TEST-003 Normal=100000.00. Critically, the Depleted profile (0.00) is used in test PF-005 to verify all payments suspended.
**Steps:** Create test class SeedLoaderPrefundingTest.java; Call set_test_prefunding('P-TEST-002','Normal') and assert balance=50000.00; Call set_test_prefunding('P-TEST-002','Low') and assert balance=9500.00; Call set_test_prefunding('P-TEST-002','Depleted') and assert balance=0.00; Call set_test_prefunding('P-TEST-003','Normal') and assert balance=100000.00; Call set_test_prefunding with unknown profile 'INVALID' and assert meaningful exception
**Deliverable:** Test class SeedLoaderPrefundingTest.java with 5 balance state assertions
**Acceptance / logic checks:**
- P-TEST-002 Depleted profile results in live balance=0.00 USD
- P-TEST-002 Low profile results in live balance=9500.00 USD
- P-TEST-003 Normal profile results in live balance=100000.00 USD
- Unknown profile name throws exception with descriptive message
- Two successive calls to set_test_prefunding() for the same partner update in place, not duplicate
**Depends on:** 15.2-T10

### 15.2-T19 — Integration test: sandbox prefunding control API sets and reflects balance correctly  _(45 min)_
**Context:** WBS 15.2 integration test for T11. POST /v1/sandbox/partners/{partner_id}/prefunding must set the live balance for the partner. The integration test runs against the local test environment with Docker Compose. It chains: set balance via sandbox API, then call GET /v1/balance as the test partner, and confirm the reported balance matches.
**Steps:** Create test class SandboxPrefundingControlApiIT.java using REST Assured or equivalent HTTP client; POST /v1/sandbox/partners/P-TEST-002/prefunding with body {usd_amount: 25000.00} using sandbox-admin key; Assert HTTP 200 response; GET /v1/balance with P-TEST-002 partner key; Assert returned balance.usd_available = 25000.00; POST negative usd_amount=-1 and assert HTTP 400
**Deliverable:** Integration test SandboxPrefundingControlApiIT.java with 4 assertions
**Acceptance / logic checks:**
- After POST usd_amount=25000.00, GET /v1/balance returns 25000.00
- POST usd_amount=-1 returns HTTP 400
- POST in APP_ENV=prod returns HTTP 404 (tested by setting env header or profile)
- POST with unknown partner_id returns HTTP 404
- Audit table contains actor='SANDBOX_CONTROL' entry after successful set
**Depends on:** 15.2-T11, 15.2-T10

### 15.2-T20 — Integration test: sandbox treasury reset API restores fixture rates  _(45 min)_
**Context:** WBS 15.2 integration test for T12. POST /v1/sandbox/treasury/reset must restore all 5 treasury rates to QA-12 §3.3 values. The integration test verifies this by first writing a different rate, calling reset, then querying the rate engine's observable output (GET /v1/rates) which uses the rates.
**Steps:** Create test class SandboxTreasuryResetApiIT.java; Directly update treasury.usd_krw to 1400.000000 via DB or control API; Call POST /v1/sandbox/treasury/reset with sandbox-admin key; Assert HTTP 200 {reset: true, rates_updated: 5}; Call GET /v1/rates for a KRW-payout request from P-TEST-002 and confirm the payout_usd_cost uses rate 1350.00 (expected: 13500/1350 = 10.0000 USD); Confirm all 5 rate keys are reset by querying treasury_rates table directly
**Deliverable:** Integration test SandboxTreasuryResetApiIT.java verifying treasury reset restores all 5 fixture rates
**Acceptance / logic checks:**
- After reset, treasury.usd_krw = 1350.000000 in DB
- GET /v1/rates for 13500 KRW payout returns payout_usd_cost=10.0000 (within 0.01 USD tolerance)
- HTTP 200 response contains rates_updated=5
- In prod environment profile, endpoint returns HTTP 404
- Second reset() call is idempotent and also returns rates_updated=5
**Depends on:** 15.2-T12, 15.2-T10

### 15.2-T21 — Create Docker Compose service for test data seeding in CI pipeline  _(40 min)_
**Context:** WBS 15.2. The CI pipeline (see OPS-13) needs a one-command way to seed all test data into the integration database before running integration/E2E tests. A Docker Compose service 'db-seed' must run SeedLoader as a one-shot container that exits 0 on success. The compose file must support env=dev and env=int profiles. Depends on: postgres service healthy check.
**Steps:** Add a 'db-seed' service to docker-compose.test.yml using the application image with ENTRYPOINT overridden to run SeedLoader --env=${APP_ENV}; Add depends_on: postgres with condition: service_healthy; Set command to exit with code 0 on success, non-zero on failure; Verify it runs cleanly in CI by running: docker compose -f docker-compose.test.yml run --rm db-seed; Document the seed command in the project README test-setup section
**Deliverable:** docker-compose.test.yml with 'db-seed' service definition
**Acceptance / logic checks:**
- docker compose run --rm db-seed exits with code 0 on clean DB
- docker compose run --rm db-seed run twice exits 0 both times (idempotent)
- Service depends_on postgres with healthy condition, not just started
- APP_ENV=prod causes db-seed to exit with non-zero code
- Container logs show each seed file applied and final row counts
**Depends on:** 15.2-T10

### 15.2-T22 — Write test helper class TestDataFixtures for use in unit and integration tests  _(35 min)_
**Context:** WBS 15.2. Test code across all unit and integration tests (T14-T20 and beyond) will need programmatic access to the canonical test fixture values from QA-12 §3. A TestDataFixtures Java class must expose typed constants and factory methods: partners(), merchants(), treasuryRates(), prefundingProfiles(). All field values must exactly match the seed scripts.
**Steps:** Create src/test/java/com/gmepayplus/testdata/TestDataFixtures.java; Define inner static classes: PartnerFixture, MerchantFixture, TreasuryRateFixture, PrefundingProfile; Expose static lists: TestDataFixtures.partners() returning List of all 5 PartnerFixtures; Expose static method TestDataFixtures.treasuryRate(String ccy) returning BigDecimal for the given currency; Expose TestDataFixtures.merchant(String merchantId) returning MerchantFixture by ID
**Deliverable:** Class TestDataFixtures.java with typed constants for all QA-12 §3 fixture values
**Acceptance / logic checks:**
- TestDataFixtures.treasuryRate('KRW') returns new BigDecimal('1350.000000')
- TestDataFixtures.treasuryRate('USD') returns new BigDecimal('1.000000')
- TestDataFixtures.merchant('M-TEST-0005').isQrActive() returns false
- TestDataFixtures.partners() returns a list of size 5
- TestDataFixtures.merchant('M-TEST-0002').getStatus() returns 'Inactive'

### 15.2-T23 — Unit test: rate engine vector RV-01 (cross-border inbound MNT to KRW) using seed fixtures  _(45 min)_
**Context:** QA-12 §4.2 RV-01. Cross-border inbound: target_payout=13500 KRW, cost_rate_coll=3500.00 MNT/USD, cost_rate_pay=1350.00 KRW/USD, m_a=0.015, m_b=0.010, service_charge=500 MNT. Expected: payout_usd_cost=10.0000 USD, collection_usd=10.2564 USD, collection_margin_usd=0.1538 USD, payout_margin_usd=0.1026 USD, send_amount=35897.44 MNT, collection_amount=36397.44 MNT. Pool identity: abs(10.2564-0.1538-0.1026-10.0000) <= 0.01. Rate engine uses 5-step RECEIVE mode.
**Steps:** Create test class RateEngineRV01Test.java; Load fixture values from TestDataFixtures (T22); Invoke the rate engine with RV-01 inputs using P-TEST-002 rule parameters; Assert each output field against expected values with tolerance: monetary fields within 0.01 USD or 1 MNT; Assert pool identity: abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) <= 0.01; Assert offer_rate_coll derived as send_amount / (collection_usd - collection_margin_usd) within 0.01
**Deliverable:** Test class RateEngineRV01Test.java asserting all RV-01 expected outputs
**Acceptance / logic checks:**
- payout_usd_cost = 10.0000 USD (within 0.01)
- collection_usd = 10.2564 USD (within 0.01)
- send_amount = 35897.44 MNT (within 1 MNT)
- collection_amount = 36397.44 MNT (within 1 MNT)
- Pool identity delta <= 0.01 USD
**Depends on:** 15.2-T22

### 15.2-T24 — Unit test: rate engine vector RV-04 (same-currency short-circuit KRW to KRW)  _(35 min)_
**Context:** QA-12 §4.2 RV-04. Same-currency short-circuit: collection_ccy=payout_ccy=settle_a=settle_b=KRW. m_a=0.0, m_b=0.0, service_charge=500 KRW, target_payout=13500 KRW. Expected: USD pool skipped entirely; collection_amount = 13500 + 500 = 14000 KRW; payout_usd_cost=null; collection_usd=null; collection_margin_usd=null; payout_margin_usd=null; offer_rate_coll=null. Rate engine uses same-currency branch per RATE-04 canonical spec.
**Steps:** Create test class RateEngineRV04Test.java; Invoke rate engine with KRW/KRW same-currency parameters from P-TEST-001 rule; Assert collection_amount = 14000 KRW exactly (no tolerance needed: integer KRW); Assert all USD pool fields (payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd) are null or absent from response; Assert offer_rate_coll is null; Assert no pool-identity assertion is triggered (short-circuit branch does not invoke pool check)
**Deliverable:** Test class RateEngineRV04Test.java asserting same-currency short-circuit behaviour
**Acceptance / logic checks:**
- collection_amount = 14000 KRW exactly
- payout_usd_cost is null
- collection_margin_usd is null
- offer_rate_coll is null
- No IllegalStateException from pool-identity assertion on this path
**Depends on:** 15.2-T22

### 15.2-T25 — Unit test: rate engine vector RV-08 (below-minimum margin rejected at config time)  _(35 min)_
**Context:** QA-12 §4.2 RV-08. m_a=0.010, m_b=0.009 combined=0.019 < 0.020. For cross-border rules the combined margin must be >= 2.0%. The Admin System must reject the rule with a validation error before save. This test verifies the validation function that enforces the minimum combined margin constraint.
**Steps:** Create test class RateEngineRV08Test.java; Invoke the routing rule validation function directly (not the full rate engine) with m_a=0.010, m_b=0.009 and settle_a_ccy='MNT', settle_b_ccy='KRW' (cross-border); Assert that a validation exception is thrown with error code MIN_MARGIN_VIOLATED or equivalent; Assert that the error message references the 2.0% minimum; Also test the boundary case RV-07: m_a=0.010, m_b=0.010 (combined=0.020) must pass validation
**Deliverable:** Test class RateEngineRV08Test.java asserting margin validation at rule-save time
**Acceptance / logic checks:**
- m_a=0.010, m_b=0.009 on cross-border rule throws validation exception
- Error code or message references 2.0% minimum constraint
- m_a=0.010, m_b=0.010 (combined exactly 2%) passes validation without exception
- m_a=0.0, m_b=0.0 on same-currency rule (settle_a=settle_b=KRW) passes validation
- m_a=0.010, m_b=0.009 on same-currency rule passes validation (0% floor for domestic)
**Depends on:** 15.2-T22

### 15.2-T26 — Unit test: rate engine vector RV-09 (rounding edge case, non-divisible payout)  _(40 min)_
**Context:** QA-12 §4.2 RV-09. target_payout=10001 KRW (odd amount not divisible by 1350). Expected: payout_usd_cost=7.40815 USD, collection_usd=7.59810 USD, send_amount=26593.33 MNT, collection_amount=27093.33 MNT (send+500). Rounding must only occur at collection_amount layer; intermediate steps must use full BigDecimal precision (at least 4 decimal places). Pool-identity delta <= 0.01 USD.
**Steps:** Create test class RateEngineRV09Test.java; Invoke rate engine with target_payout=10001 KRW, cost_rate_coll=3500.00, cost_rate_pay=1350.00, m_a=0.015, m_b=0.010, service_charge=500 MNT; Assert payout_usd_cost has at least 4 significant decimal places in the stored value (no premature rounding to integer); Assert collection_amount = 27093.33 MNT (within 1 MNT); Assert pool identity delta <= 0.01 USD; Assert no integer overflow or ArithmeticException
**Deliverable:** Test class RateEngineRV09Test.java asserting non-divisible payout precision handling
**Acceptance / logic checks:**
- payout_usd_cost stored as 7.4082 or higher precision (not rounded to 7 or 8)
- collection_amount = 27093.33 MNT (within 1 MNT)
- Pool identity delta <= 0.01 USD
- No ArithmeticException or integer overflow thrown
- Rounding is only applied at collection_amount and NOT at send_amount or payout_usd_cost steps
**Depends on:** 15.2-T22

### 15.2-T27 — Unit test: rate engine vector RV-10 (service charge separation, large charge)  _(40 min)_
**Context:** QA-12 §4.2 RV-10. Inputs same as RV-01 but service_charge=5000 MNT (large). Expected: send_amount=35897.44 MNT (unchanged from RV-01), collection_amount=35897.44+5000=40897.44 MNT. The USD pool is computed identically to RV-01 (collection_usd=10.2564). The pool-identity check must use send_amount only (not collection_amount) so the large service charge does not affect the identity. Service charge must be recorded separately in the revenue ledger.
**Steps:** Create test class RateEngineRV10Test.java; Invoke rate engine with RV-01 inputs except service_charge=5000 MNT; Assert send_amount = 35897.44 MNT (identical to RV-01 within 1 MNT); Assert collection_amount = 40897.44 MNT (send_amount + 5000 service charge); Assert pool identity: abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) <= 0.01 (service charge not in pool); Assert service_charge is recorded as a separate field in the transaction or revenue ledger
**Deliverable:** Test class RateEngineRV10Test.java asserting service charge separation from USD pool
**Acceptance / logic checks:**
- send_amount = 35897.44 MNT (within 1 MNT, same as RV-01)
- collection_amount = 40897.44 MNT (within 1 MNT)
- Pool identity delta <= 0.01 USD (service charge outside pool)
- service_charge recorded separately from USD pool fields
- USD pool fields (collection_usd, collection_margin_usd, payout_margin_usd) are identical to RV-01
**Depends on:** 15.2-T22

### 15.2-T28 — Unit test: rate engine vectors RV-05 and RV-06 (Partner B quote tolerance)  _(45 min)_
**Context:** QA-12 §4.2 RV-05 and RV-06. RV-05: quote-time rate=1350.00, commit-time partner B rate=1360.80, deviation=0.80% < 1.0% tolerance — transaction must commit using 1360.80. RV-06: quote-time=1350.00, commit-time=1366.20, deviation=1.2% > 1.0% — must return PARTNER_B_QUOTE_DEVIATION, no commit, no prefunding deduction. Tolerance default=1.0%, configurable per partner rule. Formula: deviation = abs(commit_rate - quote_rate) / quote_rate.
**Steps:** Create test class RateEnginePartnerBQuoteTest.java; Mock the Partner B quote API to return 1360.80 (RV-05 case); invoke commit; assert success and recorded cost_rate_pay=1360.80; Mock to return 1366.20 (RV-06 case); invoke commit; assert PARTNER_B_QUOTE_DEVIATION error thrown; Verify no prefunding deduction occurs on RV-06 path; Verify rate_source field = 'PARTNER' on RV-05 committed transaction; Mock partner B unreachable; assert PARTNER_B_QUOTE_UNAVAILABLE (no fallback to quote-time rate)
**Deliverable:** Test class RateEnginePartnerBQuoteTest.java with RV-05, RV-06, and unavailable cases
**Acceptance / logic checks:**
- RV-05: commit succeeds with recorded cost_rate_pay=1360.80
- RV-05: rate_source='PARTNER' on committed record
- RV-06: PARTNER_B_QUOTE_DEVIATION error returned; transaction NOT written to DB
- RV-06: prefunding balance unchanged after rejection
- Unreachable partner B: PARTNER_B_QUOTE_UNAVAILABLE error; no fallback to stored quote-time rate
**Depends on:** 15.2-T22

### 15.2-T29 — Unit test: rate engine pool-identity assertion fires on corrupted intermediate value  _(40 min)_
**Context:** QA-12 §4.3 requires a programmatic pool-identity assertion in the production code path: abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) <= 0.01. If violated, a CRITICAL alert must be raised. This unit test verifies the assertion fires when a deliberately corrupted intermediate value is passed.
**Steps:** Create test class PoolIdentityAssertionTest.java; Test case 1: valid RV-01 values — assert no exception or alert; Test case 2: corrupt collection_margin_usd to 0.30 USD (delta becomes 10.2564-0.30-0.1026-10.0000=0.1462 > 0.01) — assert CRITICAL alert or exception raised; Test case 3: delta exactly 0.01 USD — assert assertion passes (boundary: tolerance is <=0.01, not <0.01); Test case 4: delta 0.011 USD — assert assertion fails; Verify the assertion is called on every cross-border transaction result (inspect code coverage)
**Deliverable:** Test class PoolIdentityAssertionTest.java with 4 pool-identity assertion scenarios
**Acceptance / logic checks:**
- Valid RV-01 values pass assertion without exception
- Corrupted value delta=0.1462 triggers CRITICAL alert or PoolIdentityViolationException
- Delta exactly 0.010 passes (tolerance is <=0.01)
- Delta 0.011 fails assertion
- Same-currency short-circuit (RV-04) does NOT invoke pool-identity assertion
**Depends on:** 15.2-T22

### 15.2-T30 — Unit test: rate engine RV-02 and RV-03 identity legs (Settle A=USD and both legs USD)  _(40 min)_
**Context:** QA-12 §4.2 RV-02 and RV-03. RV-02: Settle A=USD, cost_rate_coll=1.0 (IDENTITY flag). target_payout=13500 KRW, cost_rate_pay=1350.00. Expected: send_amount=collection_usd=10.2564 USD; collection_amount=10.7564 USD (+0.50 USD service charge). RV-03: both legs USD, target_payout=100.00 USD, cost_rate_coll=1.0, cost_rate_pay=1.0. Expected: collection_usd=102.5641 USD; send_amount=102.5641 USD; collection_amount=103.0641 USD.
**Steps:** Create test class RateEngineIdentityLegTest.java; RV-02: invoke engine with cost_rate_coll marked as IDENTITY (1.0) and assert send_amount = collection_usd; RV-03: invoke engine with both rates marked IDENTITY and assert all USD values consistent; Assert pool identity holds for both cases; Assert cost_rate_coll is recorded as rate_source=IDENTITY in transaction; Verify service charge (0.50 USD) appears in collection_amount but NOT in USD pool
**Deliverable:** Test class RateEngineIdentityLegTest.java with RV-02 and RV-03 assertions
**Acceptance / logic checks:**
- RV-02: send_amount = collection_usd = 10.2564 USD (within 0.01)
- RV-02: collection_amount = 10.7564 USD (within 0.01)
- RV-03: both cost rates recorded as IDENTITY; send_amount=102.5641
- RV-03: pool identity holds (delta <= 0.01 USD)
- Margins still applied to collection_usd in both RV-02 and RV-03 (not bypassed by identity leg)
**Depends on:** 15.2-T22

### 15.2-T31 — Integration test: HC-009 insufficient prefunding rejects payment before scheme call  _(45 min)_
**Context:** QA-12 §5.1 HC-009, PF-004. OVERSEAS partner P-TEST-002 attempts MPM payment with collection_usd > balance. balance=9000 USD, collection_usd=10000 USD. Expected: INSUFFICIENT_PREFUNDING error returned; scheme is never called; balance unchanged. Uses sandbox treasury rates and routing rules from seed data.
**Steps:** Create test class PrefundingInsufficientBalanceIT.java; Use sandbox control API to set P-TEST-002 balance to 9000.00 (set_test_prefunding 'Depleted' profile with custom amount via T11); Mock ZeroPay scheme call and assert it is never invoked; POST /v1/payments with amount that produces collection_usd=10000 USD (target_payout=13500000 KRW at test rates); Assert HTTP 422 response with error code INSUFFICIENT_PREFUNDING; Assert P-TEST-002 balance unchanged at 9000.00 after the rejection
**Deliverable:** Integration test PrefundingInsufficientBalanceIT.java asserting pre-scheme rejection
**Acceptance / logic checks:**
- HTTP 422 returned with code=INSUFFICIENT_PREFUNDING
- Scheme mock receives zero calls
- Partner balance unchanged at 9000.00 USD after rejection
- Transaction record NOT created in payments table
- Error response body contains available_balance and required_amount fields for clarity
**Depends on:** 15.2-T11, 15.2-T10

### 15.2-T32 — Integration test: HC-010 low-balance alert fires when deduction crosses threshold  _(45 min)_
**Context:** QA-12 §5.1 HC-010, PF-006. When a deduction causes the OVERSEAS partner balance to fall below the configured threshold (default 10000 USD), a low-balance alert email must be sent to the partner contact. The transaction itself must still succeed. Test uses P-TEST-002 Low profile (balance=9500 USD). A payment with collection_usd=600 USD brings balance to 8900 USD (below 10000 threshold).
**Steps:** Create test class PrefundingLowBalanceAlertIT.java; Set P-TEST-002 balance to 9500.00 USD using sandbox control API; Mock email/alert dispatch and capture sent messages; POST /v1/payments with collection_usd ~= 600 USD (target_payout=810000 KRW at treasury.usd_krw=1350); Assert HTTP 200 payment approved; Assert email alert was dispatched to P-TEST-002 partner contact; Assert balance = 8900.00 USD after deduction
**Deliverable:** Integration test PrefundingLowBalanceAlertIT.java asserting alert and continued transaction
**Acceptance / logic checks:**
- Payment HTTP 200 approved (transaction not blocked by low balance)
- Alert email dispatched to partner contact email address
- Alert email contains partner_id=P-TEST-002 and current balance
- Balance after deduction = 9500.00 - 600.00 = 8900.00 USD (within 0.01)
- Alert fires exactly once, not twice (idempotent)
**Depends on:** 15.2-T11, 15.2-T10

### 15.2-T33 — Integration test: HC-011 and HC-012 merchant validation rejections using seed merchants  _(40 min)_
**Context:** QA-12 §5.1 HC-011, HC-012. HC-011: payment for M-TEST-0002 (status=Inactive) must return MERCHANT_INACTIVE. HC-012: payment using QR code QR-TEST-0005 (qr_active=FALSE on M-TEST-0005) must return QR_DEACTIVATED. Both rejections must occur before any prefunding deduction.
**Steps:** Create test class MerchantValidationRejectionIT.java; POST /v1/payments with merchant_id=M-TEST-0002; assert HTTP 422 and error code MERCHANT_INACTIVE; POST /v1/payments with qr_code=QR-TEST-0005; assert HTTP 422 and error code QR_DEACTIVATED; For both cases, assert P-TEST-002 prefunding balance is unchanged (no deduction before validation); For both cases, assert no ZeroPay scheme call is made
**Deliverable:** Integration test MerchantValidationRejectionIT.java asserting MERCHANT_INACTIVE and QR_DEACTIVATED
**Acceptance / logic checks:**
- M-TEST-0002 payment returns HTTP 422 with code=MERCHANT_INACTIVE
- QR-TEST-0005 payment returns HTTP 422 with code=QR_DEACTIVATED
- Prefunding balance unchanged after both rejections
- Scheme mock receives zero calls for both rejections
- M-TEST-0001 (active merchant, active QR) accepts payment for positive control
**Depends on:** 15.2-T05, 15.2-T11, 15.2-T10

### 15.2-T34 — Write sandbox data setup runbook and seed command reference  _(40 min)_
**Context:** WBS 15.2 deliverable: Test data sets. QA-12 §3.5 and §2.2.2 require the sandbox environment to be seeded with all synthetic partners, rules, merchants, and treasury rates before integration and E2E tests can begin. A concise runbook is needed in docs/test-data-management.md. It must reference all seed files, commands, and sandbox control API endpoints defined in T01-T13.
**Steps:** Create docs/test-data-management.md (this is a docs deliverable, an exception to the no-docs-files rule as it is an explicit QA-12 deliverable called out in §12.1 Test Deliverables); Document the seed file execution order with exact file paths; Document sandbox control API endpoints: POST /v1/sandbox/partners/{id}/prefunding, POST /v1/sandbox/treasury/reset, PATCH /v1/sandbox/partners/{id}/rules/{rule_id}; List all 5 partner fixtures with their IDs, types, and intended test scenarios; List all 5 merchant fixtures with their IDs, QR codes, status, and which test scenarios they support; Note assumption A-02: fixture rates are for test only; real rates loaded by GME Ops
**Deliverable:** docs/test-data-management.md with seed order, sandbox control endpoints, and fixture reference tables
**Acceptance / logic checks:**
- All 5 partner IDs (P-TEST-001 to P-TEST-005) listed with correct type and settle_a_ccy
- All 5 merchant IDs (M-TEST-0001 to M-TEST-0005) listed with QR codes and status
- Seed execution order matches dependency graph from T10 SeedLoader
- Sandbox control API endpoints documented with example request bodies and expected responses
- Assumption A-02 text quoted: illustrative rates for test fixtures only
**Depends on:** 15.2-T10, 15.2-T11, 15.2-T12, 15.2-T13


## WBS 15.3 — Rate-engine test vectors execution
### 15.3-T01 — Create test-fixture seed script: treasury rates and partner records  _(30 min)_
**Context:** WBS 15.3 runs rate-engine test vectors RV-01..RV-10 (QA-12 §4). All vectors rely on treasury rates stored as treasury.usd_{ccy} = units of ccy per 1 USD. Required fixtures: treasury.usd_krw=1350.00, treasury.usd_mnt=3500.00, treasury.usd_usd=1.0000, treasury.usd_eur=0.9200, treasury.usd_thb=35.500. Synthetic partners: P-TEST-001 (TestRemit, LOCAL, KRW), P-TEST-002 (TestSendMN, OVERSEAS, USD, prefunding 50000.00), P-TEST-005 (TestPartnerB, OVERSEAS, USD).
**Steps:** Create a SQL or migration seed file (e.g. test/fixtures/15_3_seed.sql) that inserts all five treasury rate rows with the exact values above.; Insert partner records P-TEST-001 through P-TEST-005 as defined in QA-12 §3.1, including type, settle_a_ccy, and prefunding amounts.; Insert prefunding balance rows: P-TEST-002 Normal=50000.00 USD, P-TEST-002 Low=9500.00 USD, P-TEST-002 Depleted=0.00 USD, P-TEST-003 Normal=100000.00 USD.; Add a README comment in the file stating rates are illustrative test-only and must never be loaded to production.; Verify the script is idempotent (uses INSERT ... ON CONFLICT DO UPDATE or equivalent).
**Deliverable:** test/fixtures/15_3_seed.sql (or equivalent migration) that seeds all treasury rates and partner/prefunding rows needed by RV-01..RV-10
**Acceptance / logic checks:**
- SELECT value FROM treasury_rates WHERE rate_key='treasury.usd_mnt' returns 3500.00
- SELECT prefunding_balance FROM prefunding WHERE partner_id='P-TEST-002' AND state='Normal' returns 50000.00
- Script runs twice without error (idempotency check)
- No production environment flag is set in the seed file; a comment explicitly marks rates as test-only

### 15.3-T02 — Create partner-rule config entries for RV-01..RV-10 scenarios  _(35 min)_
**Context:** Each rate-engine vector requires a Rule record: (partner x scheme x direction) with m_a, m_b, service_charge, and rate-source fields. RV-01/05/07/09/10 use MNT->KRW rules (m_a+m_b >= 2%). RV-07 uses exactly m_a=0.010, m_b=0.010 (2.0% combined). RV-08 uses m_a=0.010, m_b=0.009 (1.9% combined -- must be rejected at save). RV-02 uses USD->KRW identity-leg-A rule. RV-03 uses USD->USD both-legs identity. RV-04 uses KRW->KRW same-currency rule (m_a=0, m_b=0 allowed). RV-05/RV-06 use PARTNER B quote source for cost_rate_pay. Rules stored in table rule_configs with fields: partner_id, scheme_id, direction, m_a DECIMAL(6,4), m_b DECIMAL(6,4), service_charge DECIMAL(20,4), service_charge_ccy CHAR(3), rate_source ENUM(LIVE,IDENTITY,MANUAL,PARTNER).
**Steps:** Create seed entries (SQL or config fixture) for all distinct rule configurations needed by RV-01..RV-10, keyed by a test-vector label.; For RV-08, attempt to insert m_a=0.010 m_b=0.009; verify the application-layer validation (or DB check constraint) rejects it before commit -- do not include the rejected row in the fixture.; For RV-05 and RV-06, set rate_source=PARTNER for the payout leg so the engine fetches cost_rate_pay from the partner B quote API stub.; For RV-04, set both m_a=0.000 and m_b=0.000 and coll_ccy=payout_ccy=KRW to trigger same-currency short-circuit.; Confirm that each rule record is linked to the partner IDs seeded in 15.3-T01.
**Deliverable:** test/fixtures/15_3_rules.sql containing valid rule rows for RV-01..RV-07, RV-09, RV-10; plus a test assertion documenting that the RV-08 row is rejected by the validation layer
**Acceptance / logic checks:**
- Rules for RV-01 show m_a=0.0150, m_b=0.0100, service_charge=500.00 MNT, rate_source=LIVE
- Rule for RV-07 shows m_a=0.0100, m_b=0.0100 (combined exactly 2.0%) and is accepted
- Attempting to save a rule with m_a=0.010, m_b=0.009 (1.9% combined) returns a validation error referencing the 2.0% minimum constraint -- the row does not appear in rule_configs
- Rule for RV-04 shows m_a=0.0000, m_b=0.0000 and coll_ccy=payout_ccy=KRW
- Rules for RV-05 and RV-06 have rate_source=PARTNER on the payout leg
**Depends on:** 15.3-T01

### 15.3-T03 — Implement rate-engine test harness: input struct and vector loader  _(45 min)_
**Context:** WBS 15.3 requires a deterministic test harness that loads RV-01..RV-10 inputs and runs them through the production rate engine. Each vector has: target_payout, coll_ccy, payout_ccy, cost_rate_coll, cost_rate_pay, m_a, m_b, service_charge. Special flags: IDENTITY means cost_rate = 1.0 and leg is flagged accordingly; SAME_CCY triggers the short-circuit branch (collection = settle_A = settle_B = payout). The harness must use Decimal (not float) arithmetic with at least 10 significant figures. Language: Java (BigDecimal) per remit-platform-stack memory.
**Steps:** Define a RateEngineVector record/class with fields: id String, scenario String, target_payout BigDecimal, coll_ccy String, payout_ccy String, cost_rate_coll BigDecimal (null for IDENTITY), cost_rate_pay BigDecimal (null for IDENTITY or PARTNER), m_a BigDecimal, m_b BigDecimal, service_charge BigDecimal, expected_collection_amount BigDecimal, expected_send_amount BigDecimal, expected_pool_identity_delta BigDecimal, expected_error_code String.; Create a JSON or YAML file test/resources/rate_engine_vectors.json containing all 10 vectors with exact numeric values from QA-12 §4.2.; Write a VectorLoader utility class that parses the JSON file into List<RateEngineVector>.; Write a base test class RateEngineVectorTestBase that iterates loaded vectors, skips vectors with expected_error_code set (handled separately), and calls the production RateEngineService for each.; Assert that the VectorLoader parses all 10 records without error and that each record has non-null id and scenario.
**Deliverable:** src/test/java/.../RateEngineVector.java, VectorLoader.java, rate_engine_vectors.json with all 10 vectors' inputs and expected outputs
**Acceptance / logic checks:**
- VectorLoader.load() returns exactly 10 records with ids RV-01 through RV-10
- RV-01 record has target_payout=13500 KRW, cost_rate_coll=3500.00, cost_rate_pay=1350.00, m_a=0.015, m_b=0.010, service_charge=500 MNT
- RV-04 record has coll_ccy=payout_ccy=KRW, m_a=0.000, m_b=0.000, and expected_collection_amount=14000 KRW
- No vector uses float or double types -- all numeric fields are BigDecimal in Java and decimal strings in JSON
- RV-08 record has expected_error_code=MARGIN_BELOW_MINIMUM (or equivalent) and no expected_collection_amount
**Depends on:** 15.3-T01, 15.3-T02

### 15.3-T04 — Execute RV-01: cross-border inbound MNT->KRW via USD -- verify all outputs  _(40 min)_
**Context:** RV-01 is the baseline cross-border vector. Inputs: target_payout=13500 KRW, cost_rate_coll=3500.00 MNT/USD (treasury.usd_mnt), cost_rate_pay=1350.00 KRW/USD (treasury.usd_krw), m_a=0.015 (1.5%), m_b=0.010 (1.0%), service_charge=500 MNT. Expected 5-step outputs (QA-12 §4.2): payout_usd_cost=10.0000 USD, collection_usd=10.2564 USD, collection_margin_usd=0.1538 USD, payout_margin_usd=0.1026 USD, send_amount=35897.44 MNT, collection_amount=36397.44 MNT. Derived: offer_rate_coll=3553.28 MNT/USD, cross_rate=0.37609 KRW/MNT. Pool identity: |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| <= 0.01 USD.
**Steps:** In a JUnit test class RV01Test, call RateEngineService.compute(RV01_inputs) using the production code path.; Assert payout_usd_cost=10.0000 within tolerance 0.0001 USD.; Assert collection_usd=10.2564 within tolerance 0.0001 USD.; Assert send_amount=35897.44 within tolerance 0.01 MNT and collection_amount=36397.44 within tolerance 0.01 MNT.; Assert offer_rate_coll=35897.44/(10.2564-0.1538)=3553.28 within tolerance 0.01; assert cross_rate=13500/35897.44=0.37609 within tolerance 0.00001.; Assert pool identity: |10.2564-0.1538-0.1026-10.0000| <= 0.01 USD.
**Deliverable:** JUnit test class RV01Test with green passing assertions for all RV-01 expected outputs and derived rates
**Acceptance / logic checks:**
- payout_usd_cost equals 10.0000 USD within 0.0001
- collection_amount equals 36397.44 MNT within 0.01
- Pool identity delta is <= 0.01 USD
- offer_rate_coll equals 3553.28 MNT/USD within 0.01 and matches formula send_amount/(collection_usd-collection_margin_usd)
- cross_rate equals 0.37609 KRW/MNT within 0.00001 and matches formula target_payout/send_amount
**Depends on:** 15.3-T03

### 15.3-T05 — Execute RV-02: identity leg A (Settle A = USD) -- verify cost_rate_coll = 1.0  _(35 min)_
**Context:** RV-02 tests the identity-leg-A code path where collection currency = USD (Settle A = USD). Inputs: target_payout=13500 KRW, coll_ccy=USD, payout_ccy=KRW, cost_rate_coll=1.0 (IDENTITY flag), cost_rate_pay=1350.00, m_a=0.015, m_b=0.010, service_charge=0.50 USD. Expected outputs (QA-12 §4.2): payout_usd_cost=10.0000 USD, collection_usd=10.2564 USD, send_amount=10.2564 USD (= collection_usd because cost_rate_coll=1.0), collection_amount=10.7564 USD. Pool identity must hold. The rate engine must record cost_rate_coll as IDENTITY in the rate_source field, not as the numeric 1.0.
**Steps:** In RV02Test, call RateEngineService.compute() with cost_rate_coll flagged as IDENTITY.; Assert send_amount == collection_usd (within tolerance 0.0001 USD), confirming the identity leg produces no FX conversion.; Assert collection_amount = 10.2564 + 0.50 = 10.7564 USD within tolerance 0.001.; Assert the returned RateResult has rate_source_coll = IDENTITY (not LIVE or MANUAL).; Assert pool identity holds: |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| <= 0.01 USD.
**Deliverable:** JUnit test class RV02Test with green passing assertions for all RV-02 outputs and identity-leg flag
**Acceptance / logic checks:**
- send_amount equals collection_usd within 0.0001 (no FX applied to collection leg)
- collection_amount equals 10.7564 USD within 0.001
- rate_source_coll field is set to IDENTITY enum value, not to numeric 1.0
- Pool identity delta <= 0.01 USD
- No MNT amounts appear in the result -- all monetary outputs are USD or KRW
**Depends on:** 15.3-T03

### 15.3-T06 — Execute RV-03: both legs identity (USD->USD) -- verify margins apply to collection_usd  _(35 min)_
**Context:** RV-03 tests the both-legs-identity path (Settle A = USD, Settle B = USD). Inputs: target_payout=100.00 USD, coll_ccy=USD, payout_ccy=USD, cost_rate_coll=1.0 (IDENTITY), cost_rate_pay=1.0 (IDENTITY), m_a=0.015, m_b=0.010, service_charge=0.50 USD. Expected (QA-12 §4.2): payout_usd_cost=100.00 USD, collection_usd=102.5641 USD (=100/0.975), send_amount=102.5641 USD, collection_amount=103.0641 USD. Margins still apply to collection_usd even though both legs are identity -- this is NOT the same-currency short-circuit (currencies differ in the rule even if both are USD in this vector). Pool identity must hold.
**Steps:** In RV03Test, call RateEngineService.compute() with both cost_rate_coll and cost_rate_pay set to IDENTITY.; Assert payout_usd_cost=100.0000 USD and collection_usd=102.5641 USD (tolerance 0.0001).; Assert send_amount=102.5641 USD and collection_amount=103.0641 USD (tolerance 0.001).; Assert both rate_source_coll and rate_source_pay fields are IDENTITY.; Assert pool identity: |102.5641 - 102.5641*0.015 - 102.5641*0.010 - 100.0000| <= 0.01 USD.; Assert the engine did NOT trigger the same-currency short-circuit (collection_margin_usd and payout_margin_usd are both non-zero).
**Deliverable:** JUnit test class RV03Test with green passing assertions for both-legs-identity outputs and margin computation
**Acceptance / logic checks:**
- collection_usd = 100.00/0.975 = 102.5641 within 0.0001 -- margins applied even with identity legs
- collection_amount = 103.0641 USD within 0.001
- Both rate_source_coll and rate_source_pay are IDENTITY
- Pool identity delta <= 0.01 USD
- collection_margin_usd = 102.5641*0.015 = 1.5385 (non-zero), confirming short-circuit was NOT triggered
**Depends on:** 15.3-T03

### 15.3-T07 — Execute RV-04: same-currency short-circuit (KRW->KRW) -- verify USD pool is skipped  _(35 min)_
**Context:** RV-04 tests the same-currency short-circuit: when collection ccy = settle_A ccy = settle_B ccy = payout ccy = KRW, the entire USD pool is skipped. Formula: collection_amount = target_payout + service_charge only. Inputs (QA-12 §4.2): target_payout=13500 KRW, coll_ccy=KRW, payout_ccy=KRW, m_a=0.000, m_b=0.000, service_charge=500 KRW. Expected: collection_amount=14000 KRW; payout_usd_cost=null; collection_usd=null; collection_margin_usd=null; payout_margin_usd=null; offer_rate_coll=null. This models GME Remit domestic ZeroPay payments. No prefunding deducted (LOCAL partner). Rate source for both legs recorded as IDENTITY in audit.
**Steps:** In RV04Test, call RateEngineService.compute() with the same-currency inputs (coll_ccy=payout_ccy=KRW).; Assert collection_amount = 13500 + 500 = 14000 KRW exactly (integer, KRW has 0 decimal places).; Assert that payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd, offer_rate_coll, and cross_rate are all null or absent from the result.; Assert the engine set the short_circuit=true flag (or equivalent) in the result metadata.; Assert both rate slots are recorded as IDENTITY in the audit event.
**Deliverable:** JUnit test class RV04Test confirming USD pool is fully bypassed and collection_amount = 14000 KRW exactly
**Acceptance / logic checks:**
- collection_amount = 14000 KRW exactly (no rounding needed, KRW is integer)
- payout_usd_cost is null -- USD pool was not entered
- offer_rate_coll is null -- not applicable for same-currency
- Both rate_source fields are IDENTITY in audit output
- No margin amounts are computed (collection_margin_usd and payout_margin_usd are null/absent)
**Depends on:** 15.3-T03

### 15.3-T08 — Execute RV-05: partner B quote within 1.0% tolerance -- verify transaction commits  _(40 min)_
**Context:** RV-05 tests the partner B quote path where the commit-time rate deviates within tolerance. Inputs: same as RV-01 (target_payout=13500 KRW, coll_ccy=MNT, payout_ccy=KRW, cost_rate_coll=3500.00, m_a=0.015, m_b=0.010, service_charge=500 MNT) except cost_rate_pay is sourced from the partner B quote API (rate_source=PARTNER). Quote-time rate=1350.00; commit-time partner B quote=1360.80 (deviation=(1360.80-1350.00)/1350.00 = 0.80%, within 1.0% tolerance). Commit-time expected: payout_usd_cost=13500/1360.80=9.9206 USD (illustrative). Transaction must commit; recorded cost_rate_pay=1360.80; rate_source_pay=PARTNER.
**Steps:** Configure a partner B quote API stub that returns 1360.80 KRW/USD at commit time.; In RV05Test, call the rate engine at quote time (returning 1350.00) then call commit with the stub returning 1360.80.; Assert deviation calculation: |(1360.80-1350.00)/1350.00| = 0.0080 < 0.010 tolerance.; Assert transaction commits successfully (no error code returned).; Assert recorded cost_rate_pay = 1360.80 and rate_source_pay = PARTNER in the persisted transaction record.
**Deliverable:** JUnit test class RV05Test with a partner B quote stub configured at 1360.80, confirming commit succeeds and recorded rate is 1360.80
**Acceptance / logic checks:**
- Deviation of 0.80% is below the 1.0% tolerance threshold -- no error is raised
- Transaction commits (result status = COMMITTED or equivalent)
- Persisted cost_rate_pay = 1360.80 KRW/USD
- rate_source_pay field = PARTNER in the transaction record
- payout_usd_cost computed with commit-time rate 1360.80, not quote-time rate 1350.00
**Depends on:** 15.3-T03

### 15.3-T09 — Execute RV-06: partner B quote over 1.0% tolerance -- verify PARTNER_B_QUOTE_DEVIATION error  _(35 min)_
**Context:** RV-06 tests the partner B quote rejection path. Inputs same as RV-01 except rate_source=PARTNER for cost_rate_pay. Quote-time rate=1350.00; commit-time partner B quote=1366.20 (deviation=(1366.20-1350.00)/1350.00=1.2%, exceeds 1.0% tolerance). Expected: system returns error PARTNER_B_QUOTE_DEVIATION; transaction is NOT committed; no prefunding deducted; error code matches the API-05 error catalog. The system must never fall back to the quote-time rate if the partner B API returns an over-tolerance value.
**Steps:** Configure a partner B quote stub that returns 1366.20 KRW/USD at commit time.; In RV06Test, call the rate engine quote phase (1350.00), then attempt commit with the stub returning 1366.20.; Assert the engine returns error code PARTNER_B_QUOTE_DEVIATION without committing.; Assert no transaction row is persisted (or the transaction status is REJECTED, not COMMITTED).; Assert prefunding balance for the test partner is unchanged before and after the call.; Assert the system did NOT fall back to the quote-time rate 1350.00 to bypass the deviation check.
**Deliverable:** JUnit test class RV06Test confirming that a 1.2% deviation causes PARTNER_B_QUOTE_DEVIATION and blocks the transaction
**Acceptance / logic checks:**
- Returned error code is exactly PARTNER_B_QUOTE_DEVIATION (string match)
- Transaction is not committed -- no committed transaction row exists in DB after the test
- Prefunding balance unchanged after the failed commit attempt
- No fallback to quote-time rate 1350.00 occurs
- Deviation value 1.2% is logged for diagnostics
**Depends on:** 15.3-T03

### 15.3-T10 — Execute RV-07: min-margin boundary exactly 2.0% -- verify rule accepted and transaction commits  _(35 min)_
**Context:** RV-07 tests the lower boundary of the combined margin rule. Inputs: target_payout=13500 KRW, coll_ccy=MNT, payout_ccy=KRW, cost_rate_coll=3500.00, cost_rate_pay=1350.00, m_a=0.010 (1.0%), m_b=0.010 (1.0%), combined=2.0% exactly, service_charge=500 MNT. The rule with exactly 2.0% combined margin MUST be accepted at configuration time and the transaction MUST commit. Expected (QA-12 §4.2): collection_usd=10.0000/0.980=10.2041 USD, send_amount=10.2041*3500=35714.29 MNT, collection_amount=35714.29+500=36214.29 MNT. Pool identity must hold.
**Steps:** In RV07Test, load the rule with m_a=0.010, m_b=0.010 (seeded in 15.3-T02) and verify no validation error was raised for this rule.; Call RateEngineService.compute() with RV-07 inputs.; Assert collection_usd = 10.0000/0.980 = 10.2041 within tolerance 0.0001.; Assert send_amount = 10.2041*3500 = 35714.29 MNT within tolerance 0.01.; Assert collection_amount = 35714.29+500 = 36214.29 MNT within tolerance 0.01.; Assert pool identity: |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| <= 0.01 USD.
**Deliverable:** JUnit test class RV07Test confirming combined margin of exactly 2.0% is accepted and produces correct outputs
**Acceptance / logic checks:**
- Rule with m_a=0.010 + m_b=0.010 = 2.0% was saved without validation error (verified by querying rule_configs)
- collection_usd = 10.2041 within 0.0001
- collection_amount = 36214.29 MNT within 0.01
- Pool identity delta <= 0.01 USD
- combined margin recorded in transaction as 0.0200 (2.00%)
**Depends on:** 15.3-T02, 15.3-T03

### 15.3-T11 — Execute RV-08: below-minimum margin 1.9% -- verify rule rejected at configuration time  _(35 min)_
**Context:** RV-08 verifies the 2% combined margin floor enforcement. Inputs: m_a=0.010, m_b=0.009, combined=1.9%, cross-border rule (MNT->KRW). The Admin System (or API validation layer) MUST reject this rule before save -- no transaction is ever possible under it. Expected: validation error referencing the 2.0% minimum constraint for cross-border rules; row not persisted in rule_configs. Note: 0% is allowed only for same-currency rules (m_a+m_b=0 allowed when coll_ccy=payout_ccy as in RV-04).
**Steps:** In RV08Test, attempt to save a rule_config row with m_a=0.010, m_b=0.009, coll_ccy=MNT, payout_ccy=KRW via the Admin API or service layer.; Assert the call returns a validation error (HTTP 422 or equivalent application exception).; Assert the error message or error code references the 2.0% minimum combined margin constraint.; Assert no row with m_a=0.010 m_b=0.009 exists in rule_configs after the attempt.; Assert that attempting to compute a rate using this (nonexistent) rule also fails -- no transaction possible.
**Deliverable:** JUnit test class RV08Test confirming the 1.9% combined margin rule is rejected with a clear error before persistence
**Acceptance / logic checks:**
- Save attempt returns a validation error -- no HTTP 200/201 success
- Error references the 2.0% minimum constraint for cross-border rules
- rule_configs table contains no row with m_a=0.0100 m_b=0.0090 for MNT->KRW
- Same-currency rule with m_a=0 m_b=0 (as in RV-04) is NOT affected -- it remains valid
- Error is raised at the service/validation layer, not as a DB constraint error visible to the caller
**Depends on:** 15.3-T02, 15.3-T03

### 15.3-T12 — Execute RV-09: rounding edge case (payout=10001 KRW not divisible by 1350) -- verify no overflow  _(40 min)_
**Context:** RV-09 tests numeric precision when target_payout is not divisible by cost_rate_pay. Inputs: target_payout=10001 KRW, coll_ccy=MNT, payout_ccy=KRW, cost_rate_coll=3500.00, cost_rate_pay=1350.00, m_a=0.015, m_b=0.010, service_charge=500 MNT. Expected (QA-12 §4.2): payout_usd_cost=10001/1350=7.40815 USD, collection_usd=7.40815/0.975=7.59810 USD, send_amount=7.59810*3500=26593.33 MNT. Pass criteria: no integer overflow; all intermediate values stored with at least 4 decimal places; pool-identity delta <= 0.01 USD; rounding applied at collection_amount layer only (not at intermediate steps 1-4).
**Steps:** In RV09Test, call RateEngineService.compute() with target_payout=10001 KRW.; Assert payout_usd_cost=7.40815 within tolerance 0.00001 (non-integer result stored with precision).; Assert collection_usd=7.59810 within tolerance 0.00001 and send_amount=26593.33 within tolerance 0.01.; Assert collection_amount = 26593.33 + 500 = 27093.33 MNT (rounded to 0 or 2 decimal places per MNT precision, no intermediate rounding).; Assert that intermediate values payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd, and send_amount are stored at DECIMAL(20,8) precision (at least 4 decimal places in the result).; Assert pool identity delta <= 0.01 USD.
**Deliverable:** JUnit test class RV09Test confirming non-integer KRW amounts produce precise intermediates with rounding only at final layer
**Acceptance / logic checks:**
- payout_usd_cost = 7.40815 stored with at least 4 decimal places (no premature rounding to 7.41)
- collection_usd = 7.59810 within 0.00001
- Pool identity delta <= 0.01 USD
- collection_amount rounding occurs only at Step 5 (final); Steps 1-4 retain full precision
- No ArithmeticException or overflow for the odd KRW amount
**Depends on:** 15.3-T03

### 15.3-T13 — Execute RV-10: large service charge (5000 MNT) -- verify USD pool unchanged from RV-01  _(35 min)_
**Context:** RV-10 confirms that service_charge is isolated from the USD pool math. Inputs: same as RV-01 (target_payout=13500 KRW, cost_rate_coll=3500.00, cost_rate_pay=1350.00, m_a=0.015, m_b=0.010) but service_charge=5000 MNT (vs 500 MNT in RV-01). Expected (QA-12 §4.2): USD pool outputs IDENTICAL to RV-01 (collection_usd=10.2564, send_amount=35897.44 MNT); collection_amount=35897.44+5000=40897.44 MNT (vs 36397.44 in RV-01). Pool identity check uses (collection_usd - margins) vs payout_usd_cost ONLY -- service_charge is excluded from this check. service_charge must be recorded separately in the revenue_ledger table.
**Steps:** In RV10Test, call RateEngineService.compute() with service_charge=5000 MNT.; Assert collection_usd=10.2564 and send_amount=35897.44 MNT -- IDENTICAL to RV-01 (tolerance 0.001) confirming service_charge does not alter the USD pool.; Assert collection_amount=40897.44 MNT (= 35897.44 + 5000).; Assert pool identity check: |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| <= 0.01 USD (service_charge is excluded from this assertion).; Assert service_charge=5000 MNT is recorded as a separate row or field in revenue_ledger (not included in collection_margin_usd or payout_margin_usd).
**Deliverable:** JUnit test class RV10Test confirming large service_charge does not affect USD pool math and is ledgered separately
**Acceptance / logic checks:**
- collection_usd = 10.2564 and send_amount = 35897.44 -- same as RV-01, confirming service_charge isolation
- collection_amount = 40897.44 MNT (= send_amount + 5000)
- Pool identity delta <= 0.01 USD (service_charge excluded from the identity assertion)
- revenue_ledger contains a row with service_charge=5000 MNT for this transaction
- Pool identity assertion code path does NOT add service_charge to collection_usd before comparison
**Depends on:** 15.3-T03, 15.3-T04

### 15.3-T14 — Implement programmatic pool-identity assertion embedded in production code path  _(50 min)_
**Context:** QA-12 §4.3 requires that the pool-identity assertion be embedded in the production code path (not only in tests) and fire for every committed cross-border transaction. Assertion: assert abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) <= 0.01 USD. Any violation must raise an internal CRITICAL alert (per OPS-13 alerting spec). The assertion must NOT fire for same-currency short-circuit transactions (RV-04) where USD pool fields are null.
**Steps:** In RateEngineService (or a PoolIdentityValidator component), add a method validatePoolIdentity(RateResult result) that computes the delta and throws an InternalCriticalException (or equivalent) if delta > 0.01.; The method must skip validation when result.isShortCircuit() is true (same-currency path).; Emit a CRITICAL-level alert event (to OPS-13 alerting channel) in the exception handler -- include transaction_id, delta value, and all pool components in the alert payload.; Ensure validatePoolIdentity is called inside the commit transaction boundary -- if the assertion fails, the DB transaction rolls back.; Write a unit test PoolIdentityAssertionTest with: (a) a valid cross-border result that passes, (b) a tampered result (delta=0.015) that triggers the exception, (c) a same-currency result that skips the assertion without error.
**Deliverable:** PoolIdentityValidator.validatePoolIdentity() method in production code, plus PoolIdentityAssertionTest with three sub-cases
**Acceptance / logic checks:**
- Valid RV-01 result (delta=0.000) passes without exception
- Tampered result with delta=0.015 USD throws InternalCriticalException and emits a CRITICAL alert event
- Same-currency result (isShortCircuit=true) passes without triggering the assertion
- The assertion fires INSIDE the DB transaction boundary (verified by inspecting call stack or transaction scope annotation)
- Alert payload includes transaction_id, delta, and all four pool component values
**Depends on:** 15.3-T04, 15.3-T07

### 15.3-T15 — Write parametrized JUnit test running all 10 RV vectors via single test method  _(50 min)_
**Context:** WBS 15.3 requires all 10 vectors to pass as a regression gate. A parametrized test iterates RV-01..RV-10, loads each vector from the JSON fixture (built in 15.3-T03), routes to the appropriate assertion logic, and reports per-vector pass/fail. Error-expected vectors (RV-06 PARTNER_B_QUOTE_DEVIATION, RV-08 MARGIN_BELOW_MINIMUM) assert the error code and no-commit behavior. All numeric tolerance is 0.01 USD or 0.01 in coll_ccy for amounts; pool identity tolerance is 0.01 USD.
**Steps:** Create a parametrized JUnit 5 test class RateEngineVectorSuiteTest using @MethodSource to supply all 10 RateEngineVector records from VectorLoader.; For vectors without expected_error_code: assert collection_amount, send_amount, and pool identity as defined per-vector.; For vectors with expected_error_code: assert the exception/error response matches and no transaction is committed.; Run the full suite and confirm 10/10 vectors pass (0 failures, 0 skipped).; Include a summary log line per vector: PASS/FAIL, vector ID, scenario, and (for failures) the delta between actual and expected.
**Deliverable:** RateEngineVectorSuiteTest.java -- parametrized test class that runs all 10 vectors and reports per-vector results; all 10 must be green
**Acceptance / logic checks:**
- Test suite reports exactly 10 parametrized test cases
- RV-01 through RV-07, RV-09, RV-10 each assert collection_amount within 0.01 of expected
- RV-06 asserts PARTNER_B_QUOTE_DEVIATION error code and no committed transaction
- RV-08 asserts MARGIN_BELOW_MINIMUM (or equivalent) validation error
- Zero test failures in CI run
**Depends on:** 15.3-T04, 15.3-T05, 15.3-T06, 15.3-T07, 15.3-T08, 15.3-T09, 15.3-T10, 15.3-T11, 15.3-T12, 15.3-T13

### 15.3-T16 — Verify rate-lock: changing treasury rates after commit does not alter RV-01 recorded values  _(40 min)_
**Context:** QA-12 HC-008 / TICKET_BRIEF rate-lock rule: all USD-pool values and derived rates are permanently recorded at commit time; later treasury/margin changes never affect committed transactions. After executing RV-01, update treasury.usd_mnt to 3600.00 (was 3500.00) and re-query the committed transaction. All stored rate fields (cost_rate_coll, cost_rate_pay, collection_usd, send_amount, offer_rate_coll, cross_rate) must remain at their RV-01 committed values. New transactions computed after the update must use 3600.00.
**Steps:** Execute RV-01 fully via the rate engine and commit the transaction; record the returned transaction_id.; Update treasury.usd_mnt from 3500.00 to 3600.00 via the Admin API or direct DB update.; Re-query the committed transaction by transaction_id and assert all stored rate fields are unchanged from commit time.; Compute a NEW rate quote after the treasury update and assert cost_rate_coll = 3600.00 for the new quote.; Revert treasury.usd_mnt to 3500.00 to restore test fixture state.
**Deliverable:** JUnit test class RateLockTest (or a method in RV01Test) verifying committed transaction rates are immutable after treasury update
**Acceptance / logic checks:**
- Committed RV-01 transaction still shows cost_rate_coll=3500.00 after treasury update to 3600.00
- Committed send_amount=35897.44 MNT is unchanged in the DB
- New rate quote after update returns cost_rate_coll=3600.00 (rate-lock only applies to committed transactions)
- Treasury update is audit-logged with actor, timestamp, old value 3500.00, and new value 3600.00
- No scheduled job or retroactive recalculation touches committed transaction rate fields
**Depends on:** 15.3-T04

### 15.3-T17 — Verify offer_rate_coll and cross_rate derived fields for RV-01 match BOK FX1015 formula  _(35 min)_
**Context:** The rate engine derives two BOK-required rates (never configured, always computed). offer_rate_coll = send_amount / (collection_usd - collection_margin_usd) -- this is the BOK FX1015 #14 collection-side FX offer rate. cross_rate = target_payout / send_amount -- the implied KRW/MNT cross rate. For RV-01: offer_rate_coll = 35897.44 / (10.2564 - 0.1538) = 35897.44 / 10.1026 = 3553.28 MNT/USD; cross_rate = 13500 / 35897.44 = 0.37609 KRW/MNT. These derived values must be stored in the transaction record and must NOT be configurable or user-supplied.
**Steps:** Using the committed RV-01 transaction, retrieve the stored offer_rate_coll and cross_rate fields from the transaction record.; Assert offer_rate_coll = send_amount / (collection_usd - collection_margin_usd) within tolerance 0.01.; Assert cross_rate = target_payout / send_amount within tolerance 0.00001.; Assert neither offer_rate_coll nor cross_rate appears as an input field in the rate request payload (they are output-only).; Assert that the offer_rate_coll formula matches BOK FX1015 #14 as documented: the denominator is collection_usd minus collection_margin_usd (not total collection_usd).
**Deliverable:** JUnit test assertions within RV01Test (or a dedicated DerivedRateTest) confirming offer_rate_coll and cross_rate are computed-only and match formulas
**Acceptance / logic checks:**
- offer_rate_coll = 3553.28 MNT/USD within tolerance 0.01
- cross_rate = 0.37609 KRW/MNT within tolerance 0.00001
- offer_rate_coll denominator is (collection_usd - collection_margin_usd), NOT collection_usd -- verified by injecting a result with non-zero m_a and confirming the denominator shifts
- Neither field is present in the inbound rate request schema (GET /v1/rates response may include them but they are not inputs)
- Values are stored in the transaction record and cannot be overridden by the caller
**Depends on:** 15.3-T04

### 15.3-T18 — Verify decimal precision and rounding rules: intermediates DECIMAL(20,8), final DECIMAL(20,4)  _(45 min)_
**Context:** DB and money-handling rules (SAD-02 §2.3, §2.4): intermediate values (payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd, send_amount) stored as DECIMAL(20,8). Final collection_amount rounded to settle_A ccy scale: KRW = 0 decimal places (integer), USD = 2 decimal places, MNT = 2 decimal places. All division uses BigDecimal with HALF_UP rounding, min 10 significant figures. No float/double in any money path. This ticket verifies the column types and rounding behavior using RV-09 (the odd-amount vector).
**Steps:** Query the DB schema for the transactions table and assert: payout_usd_cost DECIMAL(20,8), collection_usd DECIMAL(20,8), send_amount DECIMAL(20,8), collection_amount DECIMAL(20,4).; Run RV-09 (target_payout=10001 KRW) and query the persisted row; assert payout_usd_cost has exactly 8 decimal places in the stored value (e.g. 7.40815xxx not rounded to 7.41).; Assert collection_amount for MNT-collection vectors uses 2 decimal places (e.g. 36397.44 not 36397).; Assert collection_amount for KRW-collection vectors (RV-04) is an integer (0 decimal places).; Assert no float or double type appears in the Java RateEngineService class (grep for double/float literals in the rate computation methods).
**Deliverable:** Schema assertion test and code audit confirming DECIMAL(20,8) intermediates, DECIMAL(20,4) final, and no float arithmetic in money paths
**Acceptance / logic checks:**
- payout_usd_cost column is DECIMAL(20,8) in DB schema
- RV-09 stored payout_usd_cost = 7.40815xxx with at least 5 significant digits after the decimal (no premature truncation)
- collection_amount for MNT vectors has exactly 2 decimal places
- collection_amount for KRW same-currency vector is integer (RV-04: 14000, not 14000.00 or 14000.0000)
- Grep finds zero occurrences of primitive double or float in rate computation methods
**Depends on:** 15.3-T03, 15.3-T12

### 15.3-T19 — Verify PARTNER_B_QUOTE_UNAVAILABLE error when partner B API is unreachable  _(35 min)_
**Context:** When rate_source=PARTNER for cost_rate_pay and the partner B quote API is unreachable (timeout, network error), the engine MUST return PARTNER_B_QUOTE_UNAVAILABLE and MUST NOT fall back to any cached or quote-time rate. This is a hard no-fallback rule (TICKET_BRIEF). No prefunding deduction must occur. This covers HC-014 from QA-12 §5.1.
**Steps:** In RV05Test or a new PartnerBUnavailableTest, configure the partner B quote stub to simulate a connection timeout (throw IOException or equivalent).; Call RateEngineService.compute() with rate_source=PARTNER for cost_rate_pay.; Assert the returned error code is exactly PARTNER_B_QUOTE_UNAVAILABLE.; Assert no transaction row is committed.; Assert no prefunding deduction occurred (balance unchanged).; Assert the engine did NOT use the quote-time rate 1350.00 as a fallback.
**Deliverable:** JUnit test PartnerBUnavailableTest confirming PARTNER_B_QUOTE_UNAVAILABLE is returned on API timeout with no fallback and no commit
**Acceptance / logic checks:**
- Returned error code is PARTNER_B_QUOTE_UNAVAILABLE (exact string match)
- No transaction is committed after the call
- Prefunding balance is unchanged
- No fallback to the quote-time or treasury rate occurs
- Error is returned within the normal API response time (not a hung request -- stub must simulate a fast failure or configured timeout)
**Depends on:** 15.3-T08

### 15.3-T20 — Verify rate-quote TTL: RATE_QUOTE_EXPIRED error after TTL elapses before commit  _(40 min)_
**Context:** Rate quote TTL is 60s for aggregator-bound partners, 300s otherwise; configurable 60-1800s. validUntil = quote_issued_at + ttl. If a partner attempts to commit after validUntil, the engine must return RATE_QUOTE_EXPIRED without committing and without deducting prefunding. This covers HC-005 from QA-12 §5.1. The TTL check must use server-side time, not partner-supplied time.
**Steps:** In a RateQuoteTTLTest, set the rule TTL to 60 seconds.; Call GET /v1/rates (or equivalent) to issue a quote; record quote_issued_at.; Advance mock/test clock by 61 seconds (or use a configurable TTL of 1 second for the test).; Attempt to commit the payment and assert the response is RATE_QUOTE_EXPIRED.; Assert no transaction is committed and no prefunding is deducted.; Assert that a commit within TTL (at T+59s) succeeds normally (control case).
**Deliverable:** JUnit test RateQuoteTTLTest confirming expired quotes return RATE_QUOTE_EXPIRED and valid quotes within TTL commit successfully
**Acceptance / logic checks:**
- Commit at T+61s (after TTL=60s) returns error RATE_QUOTE_EXPIRED
- No transaction committed and no prefunding deducted on expiry
- Commit at T+59s succeeds with status COMMITTED
- Server-side clock is used for validUntil check (not a partner-supplied timestamp)
- validUntil stored on the quote record equals quote_issued_at + ttl_seconds
**Depends on:** 15.3-T03

### 15.3-T21 — Verify prefunding atomic deduction: no double-deduction under concurrent requests  _(55 min)_
**Context:** OVERSEAS partners (P-TEST-002) require prefunding deduction before any scheme call. Deduction is ATOMIC using SELECT ... FOR UPDATE (TICKET_BRIEF). Two concurrent payment requests must not both succeed if the total exceeds the balance. Insufficient balance must reject before calling ZeroPay scheme. This covers HC-009 (insufficient balance) and the concurrency invariant from QA-12 §1.3.
**Steps:** Seed P-TEST-002 with prefunding balance = 10.50 USD (enough for exactly one RV-01 payment at collection_usd=10.2564, but not two).; Launch two concurrent threads each calling RateEngineService.compute() + prefunding deduction for RV-01 (collection_usd=10.2564 USD each) against P-TEST-002.; Assert exactly one request succeeds and exactly one returns INSUFFICIENT_PREFUNDING.; Assert final prefunding balance = 10.50 - 10.2564 = 0.2436 USD (one deduction only).; Assert the scheme (ZeroPay stub) was called exactly once, not twice.
**Deliverable:** JUnit concurrency test PrefundingAtomicDeductionTest with two-thread race confirming at most one deduction succeeds
**Acceptance / logic checks:**
- Exactly one of the two concurrent requests returns success status
- Exactly one returns INSUFFICIENT_PREFUNDING
- Final prefunding balance = initial - one deduction (no double-spend)
- ZeroPay stub invocation count = 1 (scheme never called if prefunding fails)
- Test is repeatable across 10 runs (no race-condition flakiness -- use CountDownLatch or equivalent synchronization in the test harness)
**Depends on:** 15.3-T07, 15.3-T04

### 15.3-T22 — Verify service_charge is recorded in revenue_ledger separate from margin amounts  _(35 min)_
**Context:** service_charge is a flat fee in Settle A ccy that NEVER enters the USD pool (TICKET_BRIEF). It is added only at Step 5: collection_amount = send_amount + service_charge. It must be recorded in the revenue_ledger table as a separate line item, not merged into collection_margin_usd or payout_margin_usd. RV-10 (service_charge=5000 MNT) is the primary test case. Also verify RV-01 (service_charge=500 MNT).
**Steps:** After running RV-10, query the revenue_ledger table for the transaction_id.; Assert a row exists with type=SERVICE_CHARGE, amount=5000 MNT, not included in any margin field.; Assert collection_margin_usd and payout_margin_usd for RV-10 are IDENTICAL to RV-01 (confirming service_charge did not affect margin calculation).; After running RV-01, assert a revenue_ledger row with amount=500 MNT for that transaction.; Assert there is no service_charge contribution to payout_usd_cost, collection_usd, or any USD pool field.
**Deliverable:** JUnit test ServiceChargeLedgerTest confirming service_charge rows in revenue_ledger are isolated from USD pool fields for RV-01 and RV-10
**Acceptance / logic checks:**
- revenue_ledger contains a row with type=SERVICE_CHARGE, amount=5000 MNT for the RV-10 transaction
- collection_margin_usd for RV-10 equals collection_margin_usd for RV-01 (same USD pool, different service_charge)
- payout_margin_usd for RV-10 equals payout_margin_usd for RV-01
- No service_charge amount appears in collection_usd, payout_usd_cost, collection_margin_usd, or payout_margin_usd
- revenue_ledger type field is SERVICE_CHARGE (not MARGIN or FEE)
**Depends on:** 15.3-T04, 15.3-T13

### 15.3-T23 — Validate Admin UI margin enforcement: RV-07 accepted, RV-08 rejected with correct error message  _(45 min)_
**Context:** The Admin System must enforce the 2% minimum combined margin at rule save time for cross-border rules. This ticket tests the Admin API endpoint (not just the service layer) by sending HTTP requests. RV-07 (m_a=0.010, m_b=0.010, combined=2.0%) must return HTTP 200/201. RV-08 (m_a=0.010, m_b=0.009, combined=1.9%) must return HTTP 422 with error body referencing the 2% minimum. Same-currency rules (m_a=0, m_b=0) must be accepted.
**Steps:** POST a rule_config via Admin API: partner_id=P-TEST-002, m_a=0.010, m_b=0.010, coll_ccy=MNT, payout_ccy=KRW; assert HTTP 201 and rule_id returned.; POST a rule_config via Admin API: partner_id=P-TEST-002, m_a=0.010, m_b=0.009, coll_ccy=MNT, payout_ccy=KRW; assert HTTP 422 with error code MARGIN_BELOW_MINIMUM or equivalent.; Verify the HTTP 422 response body contains a human-readable message referencing the 2.0% minimum combined margin requirement.; POST a same-currency rule: m_a=0.000, m_b=0.000, coll_ccy=KRW, payout_ccy=KRW; assert HTTP 201 (0% allowed for same-currency).; Assert the audit log contains an entry for the successful RV-07 rule creation with actor, timestamp, and new values.
**Deliverable:** Integration test AdminMarginEnforcementTest covering HTTP-level acceptance and rejection of Admin API rule_config submissions
**Acceptance / logic checks:**
- POST with m_a=0.010 m_b=0.010 returns HTTP 201
- POST with m_a=0.010 m_b=0.009 returns HTTP 422
- HTTP 422 body contains error code MARGIN_BELOW_MINIMUM and references 2.0% constraint
- POST with m_a=0.000 m_b=0.000 and coll_ccy=payout_ccy=KRW returns HTTP 201
- Audit log entry exists for the successful rule creation with actor and previous_value=null (new entry)
**Depends on:** 15.3-T02, 15.3-T10, 15.3-T11

### 15.3-T24 — Produce vector execution report: structured results for all RV-01..RV-10  _(45 min)_
**Context:** WBS 15.3 parent deliverable is a Vector Results report. After all individual vector tests pass, generate a structured execution report (JSON or CSV) capturing per-vector results: vector_id, scenario, status (PASS/FAIL), key computed values, expected values, delta, and pool_identity_delta. This report serves as the audit artifact for the QA-12 gate and for GME Finance sign-off (per PM-14). The report must be generated automatically by the test suite, not handcrafted.
**Steps:** Add a JUnit 5 TestWatcher or custom reporter to RateEngineVectorSuiteTest that collects per-vector results after each parametrized test case runs.; At end of suite, serialize results to test/reports/vector_execution_report.json with fields: vector_id, scenario, status, collection_amount_actual, collection_amount_expected, delta, pool_identity_delta, error_code_actual, error_code_expected, run_timestamp.; Assert the generated file contains exactly 10 entries.; Assert all 10 entries have status=PASS.; Include the report path and pass/fail summary in the build output (e.g. printed to stdout after the test run).
**Deliverable:** test/reports/vector_execution_report.json auto-generated by RateEngineVectorSuiteTest, containing per-vector PASS/FAIL results and key numeric deltas
**Acceptance / logic checks:**
- vector_execution_report.json contains exactly 10 entries with ids RV-01 through RV-10
- All 10 entries have status=PASS
- Each entry includes pool_identity_delta (numeric) for cross-border vectors
- Error-expected vectors (RV-06, RV-08) show error_code_actual matching error_code_expected and status=PASS
- Report file is regenerated on every CI run (not a static artifact)
**Depends on:** 15.3-T15

### 15.3-T25 — Rounding-residual reconciliation test  _(45 min)_
**Context:** Verify across a batch that sum(booked) + sum(residual) == sum(precise) and that REVENUE_ROUNDING equals the net residual, proving no money is silently lost.
**Steps:** Generate N transactions with mixed partner rounding modes; Assert booked+residual reconstructs precise; Assert REVENUE_ROUNDING net == sum(residual)
**Deliverable:** Reconciliation test for rounding residual
**Acceptance / logic checks:**
- booked+residual == precise for all
- REVENUE_ROUNDING net == sum residual
**Depends on:** 7.3, 5.5

### 15.3-T26 — Partner round-down booking scenario test  _(35 min)_
**Context:** A partner configured DOWN(2dp) on a precise 10500.567 settlement books 10500.56 with +0.007 residual posted as a rounding gain.
**Steps:** Configure partner mode DOWN; Run a settlement of precise 10500.567; Assert booked 10500.56 and residual 0.007 gain posted
**Deliverable:** End-to-end round-down booking test
**Acceptance / logic checks:**
- booked == 10500.56
- residual 0.007 credited to REVENUE_ROUNDING
**Depends on:** 5.5


## WBS 15.4 — Functional test suites per component
### 15.4-T01 — Scaffold test-data seed script: synthetic partners, merchants, treasury rates  _(45 min)_
**Context:** QA-12 §3 defines all test fixtures required before any functional suite can run. Five synthetic partners must exist: P-TEST-001 (LOCAL/KRW, ZeroPay), P-TEST-002 (OVERSEAS/USD, prefunding 50000/9500/0), P-TEST-003 (OVERSEAS/USD, prefunding 100000), P-TEST-004 (OVERSEAS/EUR, MANUAL rate), P-TEST-005 (OVERSEAS/USD, PARTNER B). Five merchants: M-TEST-0001 (Active, 0.80%), M-TEST-0002 (Inactive, 0.80%), M-TEST-0003 (Franchise/Active, 1.20%), M-TEST-0004 (Active, 1.70%), M-TEST-0005 (Active, QR-TEST-0005 deactivated). Treasury fixtures: treasury.usd_krw=1350.00, treasury.usd_mnt=3500.00, treasury.usd_usd=1.0, treasury.usd_eur=0.9200, treasury.usd_thb=35.500.
**Steps:** Create seed SQL / migration file under src/test/resources/db/seed_qa_fixtures.sql (or equivalent for your stack).; Insert all 5 partner rows with correct type, settle_a_ccy, prefunding_enabled flags.; Insert all 5 merchant rows with correct status and fee_rate values; set QR-TEST-0005 status=DEACTIVATED.; Insert treasury rate rows for all 5 ccy pairs above.; Insert three prefunding balance rows for P-TEST-002 (normal=50000, low=9500, depleted=0) and one for P-TEST-003 (100000).; Add a README comment block in the file explaining that these rates are test-only and must NOT be loaded to production.
**Deliverable:** src/test/resources/db/seed_qa_fixtures.sql (or equivalent seed file) containing all partner, merchant, QR, treasury-rate, and prefunding rows required by QA-12 §3
**Acceptance / logic checks:**
- Running the seed script against a clean test DB and querying partners returns exactly 5 rows with correct type and settle_a_ccy values.
- Querying treasury table returns usd_krw=1350.00, usd_mnt=3500.00, usd_usd=1.0, usd_eur=0.92, usd_thb=35.5.
- M-TEST-0002 has status=INACTIVE; M-TEST-0005 has status=ACTIVE but QR-TEST-0005 has qr_status=DEACTIVATED.
- P-TEST-002 has three separate prefunding balance records with amounts 50000.00, 9500.00, and 0.00 USD.
- Seed script is idempotent: running it twice does not create duplicate rows (use INSERT ... ON CONFLICT DO NOTHING or equivalent).

### 15.4-T02 — Hub Core functional suite: HC-001 MPM Domestic payment happy path  _(40 min)_
**Context:** HC-001: Partner calls GET /v1/rates then POST /v1/payments for a same-currency (KRW/KRW) MPM Domestic transaction using P-TEST-001. Same-currency short-circuit applies: collection_amount = target_payout + service_charge; no USD pool computed. Service charge = 500 KRW flat. Target payout = 13,500 KRW. Expected collection_amount = 14,000 KRW. payment.approved webhook must fire. No prefunding deduction for LOCAL partners.
**Steps:** Seed DB with QA fixtures (15.4-T01).; Call GET /v1/rates with partner=P-TEST-001, scheme=ZeroPay, direction=Domestic, target_payout=13500 KRW.; Assert response has no USD pool fields (payout_usd_cost, collection_usd, margin fields all absent or null).; Call POST /v1/payments with the returned quote token.; Assert transaction status=APPROVED and payment.approved webhook fired with txn_id, status, collection_amount=14000, cross_rate=null.; Assert no prefunding row changed for P-TEST-001.
**Deliverable:** Automated functional test HC-001 in the Hub Core test suite (e.g. HubCoreFunctionalTest.java or hub_core_functional_test.py), covering MPM Domestic happy path
**Acceptance / logic checks:**
- GET /v1/rates response for KRW/KRW contains no payout_usd_cost or collection_margin_usd fields.
- POST /v1/payments returns 200 with status=APPROVED.
- payment.approved webhook contains collection_amount=14000 (KRW, 0 decimals) and cross_rate is absent or null.
- Prefunding table for P-TEST-001 is unchanged after the call.
- Stored transaction record shows same_currency_shortcircuit=true (or equivalent flag) in audit trail.
**Depends on:** 15.4-T01

### 15.4-T03 — Hub Core functional suite: HC-002 CPM Domestic payment happy path  _(40 min)_
**Context:** HC-002: CPM flow for P-TEST-001 (LOCAL/KRW). Partner calls POST /v1/payments/cpm/generate; the scheme scans the QR and commits. System must emit payment.pending_debit with offer_rate after QR generate, then payment.approved after commit. Same-currency short-circuit: offer_rate is null/inapplicable; collection_amount = target_payout + service_charge = 14,000 KRW.
**Steps:** Seed DB with QA fixtures (15.4-T01).; Call POST /v1/payments/cpm/generate with partner=P-TEST-001, target_payout=13500 KRW.; Assert response contains a QR token and payment status=PENDING_DEBIT.; Simulate scheme scan by triggering the CPM commit path (call internal or external scheme stub).; Assert payment.approved webhook fires; collection_amount=14000 KRW.; Verify event trail has exactly 2 relevant events: CPM_GENERATED and APPROVED.
**Deliverable:** Automated functional test HC-002 in HubCoreFunctionalTest covering CPM Domestic happy path
**Acceptance / logic checks:**
- POST /v1/payments/cpm/generate returns 200 with a non-null qr_token.
- payment.pending_debit webhook fires with txn_id and offer_rate absent or null (same-currency).
- After scheme commit, payment.approved fires with collection_amount=14000.
- Transaction event trail contains CPM_GENERATED event before APPROVED event.
- No prefunding deduction occurs for LOCAL partner P-TEST-001.
**Depends on:** 15.4-T01

### 15.4-T04 — Hub Core functional suite: HC-003 MPM Inbound (OVERSEAS) payment happy path  _(45 min)_
**Context:** HC-003: OVERSEAS partner P-TEST-002 (USD settle-A, ZeroPay, prefunding 50000 USD). MPM Inbound cross-border: MNT collection, KRW payout. Rate vectors: cost_rate_coll=3500 MNT/USD, cost_rate_pay=1350 KRW/USD, m_a=0.015, m_b=0.010, service_charge=500 MNT, target_payout=13500 KRW. Expected: collection_usd=10.2564 USD, collection_amount=36397.44 MNT. Prefunding must be reduced by collection_usd=10.2564 USD (SELECT FOR UPDATE). Scheme must be called only after successful deduction.
**Steps:** Seed DB; set P-TEST-002 prefunding balance to 50000 USD.; Call GET /v1/rates with partner=P-TEST-002, target_payout=13500 KRW, coll_ccy=MNT.; Assert payout_usd_cost=10.0000, collection_usd=10.2564, collection_amount=36397.44, validUntil is populated.; Call POST /v1/payments with the returned quote.; Assert prefunding balance for P-TEST-002 = 50000 - 10.2564 = 49989.7436 USD (within 0.01 USD).; Assert payment.approved webhook fires; scheme notification timestamp is after deduction timestamp.
**Deliverable:** Automated functional test HC-003 in HubCoreFunctionalTest covering MPM Inbound OVERSEAS happy path
**Acceptance / logic checks:**
- GET /v1/rates returns collection_usd=10.2564 (+/-0.01), collection_amount=36397.44 MNT.
- Prefunding balance after commit = 49989.74 USD (+/-0.01).
- Scheme call (or stub invocation) has timestamp >= prefunding deduction timestamp.
- payment.approved webhook received with correct collection_amount.
- Pool identity assertion: abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) <= 0.01 USD.
**Depends on:** 15.4-T01

### 15.4-T05 — Hub Core functional suite: HC-004 CPM Inbound (OVERSEAS) prefund at QR generate  _(45 min)_
**Context:** HC-004: OVERSEAS CPM for P-TEST-002. Prefunding must be deducted at POST /v1/payments/cpm/generate time, NOT at scheme approval. If balance is zero, scheme must never be called. This test verifies the correct deduction timing: deduction_timestamp < scheme_call_timestamp. Also verifies: if balance=0 at generate time, INSUFFICIENT_PREFUNDING is returned and no scheme call is made.
**Steps:** Set P-TEST-002 balance to 50000 USD. Call POST /v1/payments/cpm/generate for OVERSEAS inbound; capture deduction_timestamp and scheme_call_timestamp from transaction log.; Assert deduction_timestamp < scheme_call_timestamp.; Now set P-TEST-002 balance to 0 USD. Call POST /v1/payments/cpm/generate again.; Assert HTTP 422 with error_code=INSUFFICIENT_PREFUNDING.; Assert no scheme stub was invoked after the zero-balance attempt.; Assert P-TEST-002 balance remains 0 USD.
**Deliverable:** Automated functional tests HC-004a (deduction timing) and HC-004b (zero-balance block) in HubCoreFunctionalTest
**Acceptance / logic checks:**
- CPM generate with normal balance: deduction_timestamp appears in transaction event trail before scheme_call_timestamp.
- CPM generate with balance=0: response is 422 with error_code=INSUFFICIENT_PREFUNDING.
- Scheme stub call count is 0 for the zero-balance scenario.
- Prefunding balance is unchanged at 0 after the failed generate.
- Transaction event trail for zero-balance attempt has at most PREFUND_CHECK_FAILED event; no SCHEME_CALLED event.
**Depends on:** 15.4-T01

### 15.4-T06 — Hub Core functional suite: HC-005 expired rate quote rejection  _(35 min)_
**Context:** HC-005: Rate quote TTL default is 60 s (aggregator-bound) or 300 s (otherwise). After validUntil, committing a payment must return RATE_QUOTE_EXPIRED with no prefunding deduction. Test uses a short TTL (configured to e.g. 5 s in test profile) or by manipulating the quote's validUntil timestamp to be in the past.
**Steps:** Configure test environment with a short quote TTL (e.g. 5 s) or use a test helper to backdate validUntil.; Call GET /v1/rates; receive a quote with validUntil in the near future.; Wait or backdate the quote past validUntil.; Call POST /v1/payments with the expired quote token.; Assert HTTP 422 with error_code=RATE_QUOTE_EXPIRED.; Assert prefunding balance for P-TEST-002 is unchanged.
**Deliverable:** Automated functional test HC-005 in HubCoreFunctionalTest for expired rate quote path
**Acceptance / logic checks:**
- POST /v1/payments with expired quote returns 422 and error_code=RATE_QUOTE_EXPIRED.
- No prefunding deduction recorded (balance unchanged).
- No scheme call made.
- Transaction record (if created) has status=FAILED with reason RATE_QUOTE_EXPIRED.
- validUntil field in the GET /v1/rates response is = quote_issued_at + configured TTL (verified in a separate assertion).
**Depends on:** 15.4-T01

### 15.4-T07 — Hub Core functional suite: HC-006 idempotency key deduplication  _(35 min)_
**Context:** HC-006: Duplicate POST /v1/payments with identical idempotency key must return the same approved response without creating a second transaction or making a second prefunding deduction. Partner-API idempotency key is a header (X-Idempotency-Key or equivalent per API-05).
**Steps:** Seed DB; set P-TEST-002 balance to 50000 USD.; Call POST /v1/payments with idempotency_key=TEST-IDEM-001; capture txn_id and balance_after.; Call POST /v1/payments again with the same idempotency_key=TEST-IDEM-001 and identical body.; Assert second call returns 200 with the same txn_id.; Assert prefunding balance is still balance_after (not deducted twice).; Assert only one transaction row exists with idempotency_key=TEST-IDEM-001.
**Deliverable:** Automated functional test HC-006 in HubCoreFunctionalTest covering idempotency deduplication
**Acceptance / logic checks:**
- Both calls return HTTP 200 with identical txn_id.
- Prefunding deducted exactly once; balance after second call equals balance after first call.
- Database contains exactly one transaction row with idempotency_key=TEST-IDEM-001.
- Webhook fired exactly once (not twice) for the approved event.
- Second call response body is byte-identical to first response body (or at least has same status and txn_id).
**Depends on:** 15.4-T01

### 15.4-T08 — Hub Core functional suite: HC-007 same-day cancel and prefunding restore  _(45 min)_
**Context:** HC-007: POST /v1/payments/{id}/cancel on a same-day OVERSEAS transaction must set status=CANCELLED and restore the prefunding balance by exactly collection_usd. Cancel after settlement (next day) must be rejected with CANCEL_NOT_ALLOWED.
**Steps:** Create an OVERSEAS MPM payment for P-TEST-002; record txn_id and collection_usd from the response.; Call POST /v1/payments/{txn_id}/cancel within the same day.; Assert status=CANCELLED and payment.cancelled webhook fires.; Assert prefunding balance restored by collection_usd (balance_before_payment == balance_after_cancel within 0.01 USD).; Attempt to cancel the same transaction again; assert 422 with CANCEL_NOT_ALLOWED.; Attempt to cancel a post-settlement transaction (status=SETTLED); assert 422 with CANCEL_NOT_ALLOWED.
**Deliverable:** Automated functional tests HC-007a (same-day cancel) and HC-007b (post-settlement cancel rejected) in HubCoreFunctionalTest
**Acceptance / logic checks:**
- POST /v1/payments/{id}/cancel for same-day txn returns 200 with status=CANCELLED.
- Prefunding balance after cancel equals prefunding balance before the original payment (+/-0.01 USD).
- payment.cancelled webhook fires with txn_id and status=CANCELLED.
- Second cancel of same txn returns 422 with CANCEL_NOT_ALLOWED.
- Cancel of a SETTLED transaction returns 422 with CANCEL_NOT_ALLOWED.
**Depends on:** 15.4-T01

### 15.4-T09 — Hub Core functional suite: HC-008 rate lock after treasury rate change  _(40 min)_
**Context:** HC-008: After a transaction is committed, changing the treasury rate (treasury.usd_krw, treasury.usd_mnt) must NOT affect the recorded transaction rates. The committed transaction must retain its original cost_rate_pay, cost_rate_coll, collection_usd, and derived rates. New transactions issued after the rate change must use the new rate.
**Steps:** Record current treasury.usd_krw=1350.00. Create and commit a payment for P-TEST-002; capture txn_id, cost_rate_pay, collection_usd.; Via Admin API or DB, change treasury.usd_krw to 1400.00.; Call GET /v1/transactions/{txn_id}; assert cost_rate_pay is still 1350.00.; Call GET /v1/rates for a new quote; assert new cost_rate_pay reflects 1400.00.; Assert all USD-pool values (collection_usd, collection_margin_usd, payout_margin_usd) on the old txn are unchanged.
**Deliverable:** Automated functional test HC-008 in HubCoreFunctionalTest for rate-lock invariant
**Acceptance / logic checks:**
- GET /v1/transactions/{txn_id} after rate change still shows cost_rate_pay=1350.00.
- New GET /v1/rates call returns cost_rate_pay reflecting the updated rate (1400.00).
- collection_usd, collection_margin_usd, payout_margin_usd on the old txn are bit-exact to values at commit time.
- Admin audit log shows the rate change with actor, timestamp, old_value=1350.00, new_value=1400.00.
- No recomputation job runs on committed transactions (assert no updated_at change on txn row after rate change).
**Depends on:** 15.4-T01

### 15.4-T10 — Hub Core functional suite: HC-009 insufficient prefunding rejection before scheme call  _(35 min)_
**Context:** HC-009: OVERSEAS partner P-TEST-002 with balance set to 0.00 USD. Any payment attempt must be rejected with INSUFFICIENT_PREFUNDING before the scheme is called. balance_usd (0.00) < collection_usd (10.2564 for 13500 KRW target). Scheme stub must record zero calls.
**Steps:** Set P-TEST-002 prefunding balance to 0.00 USD.; Call POST /v1/payments for P-TEST-002 with target_payout=13500 KRW.; Assert HTTP 422 with error_code=INSUFFICIENT_PREFUNDING.; Assert scheme stub has received 0 calls.; Assert prefunding balance remains 0.00 USD.; Repeat with balance=5.00 USD (less than collection_usd=10.2564); assert same rejection.
**Deliverable:** Automated functional test HC-009 in HubCoreFunctionalTest for insufficient prefunding path
**Acceptance / logic checks:**
- POST /v1/payments returns 422 with error_code=INSUFFICIENT_PREFUNDING when balance=0.00.
- Scheme stub call count is 0.
- Prefunding balance is unchanged after the failed attempt.
- Same result when balance=5.00 USD (below collection_usd of 10.2564 USD).
- No transaction record created with status=APPROVED or PENDING for the failed attempts.
**Depends on:** 15.4-T01

### 15.4-T11 — Hub Core functional suite: HC-010 low-balance alert email on deduction below threshold  _(40 min)_
**Context:** HC-010: When a deduction causes P-TEST-002 balance to fall below the configured low-balance threshold (e.g. 10,000 USD), an email alert must be sent to the partner contact. The transaction itself must continue and succeed. Use the fixture balance 9,500 USD (below the 10,000 USD threshold already) or set balance just above threshold and do a deduction that crosses it.
**Steps:** Set P-TEST-002 balance to 10,005 USD and low_balance_threshold to 10,000 USD.; Create a payment for P-TEST-002 with collection_usd > 5.00 USD (e.g. target_payout=13500 KRW, collection_usd~10.26 USD).; Deduction brings balance to ~9994.74 USD, below 10,000 USD threshold.; Assert transaction completes successfully (status=APPROVED).; Assert email alert (captured in test mail sink) was sent to the partner contact for P-TEST-002.; Assert alert contains partner_id=P-TEST-002 and current_balance in the message.
**Deliverable:** Automated functional test HC-010 in HubCoreFunctionalTest for low-balance alert trigger
**Acceptance / logic checks:**
- Transaction returns status=APPROVED; prefunding deduction applied correctly.
- Exactly one alert email sent to the P-TEST-002 partner contact when threshold crossed.
- Email subject or body references P-TEST-002 and current_balance.
- No alert sent when balance remains above threshold on a subsequent smaller deduction.
- Alert is NOT sent again on the very next deduction if balance is already below threshold (or follows partner-defined cooldown).
**Depends on:** 15.4-T01

### 15.4-T12 — Hub Core functional suite: HC-011 / HC-012 inactive merchant and deactivated QR rejection  _(35 min)_
**Context:** HC-011: Payment against M-TEST-0002 (status=INACTIVE) must return MERCHANT_INACTIVE. HC-012: Payment with QR-TEST-0005 (qr_status=DEACTIVATED on an otherwise ACTIVE merchant M-TEST-0005) must return QR_DEACTIVATED. Both errors must be returned before scheme call and without prefunding deduction.
**Steps:** Seed DB with QA fixtures (15.4-T01) ensuring M-TEST-0002 is INACTIVE and QR-TEST-0005 is DEACTIVATED.; Call POST /v1/payments with merchant_id=M-TEST-0002; assert 422 with error_code=MERCHANT_INACTIVE.; Call POST /v1/payments with qr_code=QR-TEST-0005; assert 422 with error_code=QR_DEACTIVATED.; Assert scheme stub has 0 calls for both attempts.; Assert P-TEST-002 prefunding balance unchanged after both attempts.
**Deliverable:** Automated functional tests HC-011 and HC-012 in HubCoreFunctionalTest
**Acceptance / logic checks:**
- Payment with merchant_id=M-TEST-0002 returns 422 MERCHANT_INACTIVE.
- Payment with qr_code=QR-TEST-0005 returns 422 QR_DEACTIVATED.
- Scheme stub receives 0 calls for both scenarios.
- Prefunding balance unchanged after both failed attempts.
- M-TEST-0001 (ACTIVE) accepts a payment with no MERCHANT_INACTIVE error (control check).
**Depends on:** 15.4-T01

### 15.4-T13 — Hub Core functional suite: HC-013 / HC-014 Partner B quote deviation and unavailability  _(40 min)_
**Context:** HC-013: At commit time, Partner B rate deviates >1.0% from the /v1/rates quote rate. Example: quote cost_rate_pay=1350.00; commit-time Partner B quote=1366.20 (deviation=(1366.20-1350)/1350=1.19%>1%). Must return PARTNER_B_QUOTE_DEVIATION; no commit, no prefunding deduction. HC-014: Partner B API is unreachable at commit time. Must return PARTNER_B_QUOTE_UNAVAILABLE; no fallback to stored rate.
**Steps:** Configure Partner B stub to return 1366.20 at commit time (>1% deviation from 1350.00 quote rate).; GET /v1/rates returns quote with cost_rate_pay=1350.00. Call POST /v1/payments.; Assert 422 with error_code=PARTNER_B_QUOTE_DEVIATION; no transaction committed; balance unchanged.; Configure Partner B stub to return connection-refused/timeout.; GET /v1/rates; call POST /v1/payments.; Assert 422 with error_code=PARTNER_B_QUOTE_UNAVAILABLE; no fallback; no transaction committed.
**Deliverable:** Automated functional tests HC-013 and HC-014 in HubCoreFunctionalTest for Partner B quote edge cases
**Acceptance / logic checks:**
- Deviation=1.19% returns 422 PARTNER_B_QUOTE_DEVIATION; no committed transaction.
- Deviation=0.80% (RV-05) commits successfully with recorded cost_rate_pay=1360.80 and rate_source=PARTNER.
- PARTNER_B_QUOTE_UNAVAILABLE returned when stub is unreachable; no fallback rate used.
- Prefunding balance unchanged for both failure scenarios.
- Error codes match API-05 error catalog exactly.
**Depends on:** 15.4-T01

### 15.4-T14 — Hub Core functional suite: HC-015 pool-identity assertion for all cross-border transactions  _(45 min)_
**Context:** HC-015: For every cross-border transaction, the production code must assert abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) <= 0.01 USD. A violation must raise a CRITICAL internal alert. Test by running all RV-01 to RV-03, RV-07, RV-09, RV-10 happy-path transactions and verifying the assertion passes (no alert fired). Then inject a transaction with a synthetic pool violation and verify the alert fires.
**Steps:** Run transactions using vectors RV-01, RV-02, RV-03, RV-07, RV-09, RV-10 (see 15.4-T15 through 15.4-T24 for values).; For each, query the transaction record and compute abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost).; Assert all deltas <= 0.01 USD and no CRITICAL alert was emitted.; Use a test backdoor to inject a transaction record where the pool identity is violated by 0.05 USD.; Assert a CRITICAL alert is emitted (captured in alert sink) with message referencing pool_identity_violation and the txn_id.
**Deliverable:** Automated functional test HC-015 in HubCoreFunctionalTest verifying pool-identity assertion in production code path
**Acceptance / logic checks:**
- All RV-01 to RV-03, RV-07, RV-09, RV-10 transactions produce pool-identity delta <= 0.01 USD.
- No CRITICAL alert emitted for valid transactions.
- Injected violation of 0.05 USD triggers exactly one CRITICAL alert with pool_identity_violation tag.
- Alert payload contains the violating txn_id and the computed delta.
- Pool-identity check is confirmed to run in the production code path (not test-only), verified by code inspection or coverage marker.
**Depends on:** 15.4-T01

### 15.4-T15 — Rate engine unit tests: RV-01 cross-border inbound MNT to KRW via USD  _(30 min)_
**Context:** RV-01 vector: target_payout=13500 KRW, cost_rate_coll=3500 MNT/USD, cost_rate_pay=1350 KRW/USD, m_a=0.015, m_b=0.010, service_charge=500 MNT. Expected: payout_usd_cost=10.0000, collection_usd=10.2564, collection_margin_usd=0.1538, payout_margin_usd=0.1026, send_amount=35897.44 MNT, collection_amount=36397.44 MNT, offer_rate_coll=3553.28 MNT/USD, cross_rate=0.37609 KRW/MNT. Pool identity delta <= 0.01 USD.
**Steps:** In the rate-engine unit test class, write test method testRV01_CrossBorderInbound().; Call the rate engine compute function with the RV-01 inputs.; Assert each output field against expected value with tolerance +/-0.01 on USD amounts and +/-0.01 on MNT amounts.; Assert pool identity: abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) <= 0.01.; Assert offer_rate_coll = send_amount / (collection_usd - collection_margin_usd) = 35897.44 / 10.1026 ~= 3553.28.; Assert cross_rate = target_payout / send_amount = 13500 / 35897.44 ~= 0.37609.
**Deliverable:** Unit test testRV01_CrossBorderInbound in RateEngineUnitTest class
**Acceptance / logic checks:**
- payout_usd_cost = 10.0000 USD (+/-0.01).
- collection_usd = 10.2564 USD (+/-0.01).
- collection_amount = 36397.44 MNT (+/-0.01).
- offer_rate_coll = 3553.28 MNT/USD (+/-1.0, as it is a rate).
- Pool identity delta <= 0.01 USD.
**Depends on:** 15.4-T01

### 15.4-T16 — Rate engine unit tests: RV-02 identity leg A (Settle A = USD, Settle B = KRW)  _(25 min)_
**Context:** RV-02: target_payout=13500 KRW, cost_rate_coll=1.0 (IDENTITY), cost_rate_pay=1350 KRW/USD, m_a=0.015, m_b=0.010, service_charge=0.50 USD. Expected: payout_usd_cost=10.0000, collection_usd=10.2564, send_amount=10.2564 USD (equals collection_usd because cost_rate_coll=1.0), collection_amount=10.7564 USD. cost_rate_coll must be stored as IDENTITY flag. Pool identity holds.
**Steps:** Write testRV02_IdentityLegA() in RateEngineUnitTest.; Call rate engine with cost_rate_coll=IDENTITY (1.0 or enum), cost_rate_pay=1350.00, target_payout=13500.; Assert send_amount = collection_usd = 10.2564 USD (+/-0.01).; Assert collection_amount = 10.7564 USD (+/-0.01).; Assert the rate-source field for settle_a leg is stored as IDENTITY.; Assert pool identity holds.
**Deliverable:** Unit test testRV02_IdentityLegA in RateEngineUnitTest
**Acceptance / logic checks:**
- send_amount equals collection_usd within 0.01 USD (no FX applied on collection side).
- collection_amount = 10.7564 USD (+/-0.01).
- Rate-source field for the collection leg is IDENTITY.
- Pool identity delta <= 0.01 USD.
- service_charge (0.50 USD) is added only to collection_amount, not to collection_usd (step 5 separation).
**Depends on:** 15.4-T01

### 15.4-T17 — Rate engine unit tests: RV-03 both legs identity (USD to USD)  _(25 min)_
**Context:** RV-03: target_payout=100.00 USD, cost_rate_coll=1.0 (IDENTITY), cost_rate_pay=1.0 (IDENTITY), m_a=0.015, m_b=0.010, service_charge=0.50 USD. Expected: payout_usd_cost=100.0000, collection_usd=102.5641, send_amount=102.5641 USD, collection_amount=103.0641 USD. Margins still apply to collection_usd even though both legs are identity.
**Steps:** Write testRV03_BothLegsIdentity() in RateEngineUnitTest.; Call rate engine with both cost rates = IDENTITY (1.0), target_payout=100.00 USD, m_a=0.015, m_b=0.010.; Assert payout_usd_cost=100.0000, collection_usd=102.5641 (+/-0.01).; Assert send_amount=102.5641 USD (+/-0.01) and collection_amount=103.0641 USD (+/-0.01).; Assert both rate-source fields stored as IDENTITY.; Assert pool identity holds.
**Deliverable:** Unit test testRV03_BothLegsIdentity in RateEngineUnitTest
**Acceptance / logic checks:**
- payout_usd_cost=100.0000 USD (+/-0.01).
- collection_usd=102.5641 USD (+/-0.01).
- collection_amount=103.0641 USD (+/-0.01).
- Both legs flagged IDENTITY in stored record.
- Pool identity delta <= 0.01 USD.
**Depends on:** 15.4-T01

### 15.4-T18 — Rate engine unit tests: RV-04 same-currency short-circuit (KRW to KRW)  _(25 min)_
**Context:** RV-04: collection=settle_A=settle_B=payout=KRW, m_a=0, m_b=0, service_charge=500 KRW, target_payout=13500 KRW. USD pool must be entirely skipped. Expected: collection_amount=14000 KRW; payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd all null/absent; offer_rate_coll=null; cross_rate=null.
**Steps:** Write testRV04_SameCurrencyShortCircuit() in RateEngineUnitTest.; Call rate engine with collection_ccy=KRW, payout_ccy=KRW, m_a=0, m_b=0, service_charge=500.; Assert collection_amount=14000 KRW (no USD fields computed).; Assert payout_usd_cost is null or 0.; Assert offer_rate_coll and cross_rate are null.; Assert no USD pool values are set on the result object.
**Deliverable:** Unit test testRV04_SameCurrencyShortCircuit in RateEngineUnitTest
**Acceptance / logic checks:**
- collection_amount = target_payout + service_charge = 14000 KRW exactly.
- payout_usd_cost is null or absent.
- collection_usd, collection_margin_usd, payout_margin_usd all null or absent.
- offer_rate_coll and cross_rate null or absent.
- No intermediate USD computation performed (verify via coverage or log absence).
**Depends on:** 15.4-T01

### 15.4-T19 — Rate engine unit tests: RV-05 and RV-06 Partner B quote within and over tolerance  _(30 min)_
**Context:** RV-05: quote cost_rate_pay=1350.00; commit-time Partner B quote=1360.80 (deviation=0.80%<1.0%). Transaction commits with recorded cost_rate_pay=1360.80, rate_source=PARTNER. RV-06: commit-time quote=1366.20 (deviation=1.19%>1.0%). System must throw/return PARTNER_B_QUOTE_DEVIATION without committing. Deviation formula: abs(commit_rate - quote_rate) / quote_rate. Tolerance default=1.0%.
**Steps:** Write testRV05_PartnerBWithinTolerance(): supply quote_rate=1350.00, commit_rate=1360.80; call rate engine commit phase; assert success with recorded_rate=1360.80 and rate_source=PARTNER.; Write testRV06_PartnerBOverTolerance(): supply quote_rate=1350.00, commit_rate=1366.20; call rate engine commit phase; assert PARTNER_B_QUOTE_DEVIATION error is raised.; For RV-05, also verify the recomputed USD pool values use 1360.80 (not 1350.00).; For RV-06, assert no transaction record is persisted.; Assert tolerance boundary at exactly 1.00% deviation: deviation=0.01000 should succeed; deviation=0.01001 should fail.
**Deliverable:** Unit tests testRV05_PartnerBWithinTolerance and testRV06_PartnerBOverTolerance in RateEngineUnitTest
**Acceptance / logic checks:**
- RV-05: commits successfully with cost_rate_pay=1360.80 and rate_source=PARTNER.
- RV-06: throws/returns PARTNER_B_QUOTE_DEVIATION; no transaction persisted.
- Deviation at exactly 1.0000% succeeds (boundary inclusive).
- Deviation at 1.0001% fails (boundary exclusive).
- Pool identity holds for RV-05 with the corrected commit-time rate.
**Depends on:** 15.4-T01

### 15.4-T20 — Rate engine unit tests: RV-07 minimum margin boundary exactly 2%  _(30 min)_
**Context:** RV-07: m_a=0.010, m_b=0.010, combined=2.0% exactly. target_payout=13500 KRW, cost_rate_coll=3500, cost_rate_pay=1350, service_charge=500 MNT. Expected: collection_usd=10.0000/0.980=10.2041 USD. Rule with combined=2.0% must be accepted at configuration time. Rule with combined=1.9% (RV-08: m_a=0.010, m_b=0.009) must be rejected at config time with a validation error referencing 2.0% minimum.
**Steps:** Write testRV07_MinMarginBoundary(): compute rate engine with m_a=0.010, m_b=0.010; assert succeeds with collection_usd=10.2041 (+/-0.01).; Write testRV08_BelowMinMarginRejected(): call margin-validation function with m_a=0.010, m_b=0.009 (combined=1.9%); assert validation error with message containing '2%' or '2.0%' or 'minimum combined margin'.; Assert that m_a=0.020, m_b=0.000 (combined=2.0%) also passes validation.; Assert that m_a=0.0, m_b=0.0 passes validation only when same-currency (domestic) rule is configured.
**Deliverable:** Unit tests testRV07_MinMarginBoundary and testRV08_BelowMinMarginRejected in RateEngineUnitTest
**Acceptance / logic checks:**
- m_a=0.010, m_b=0.010: collection_usd=10.2041 (+/-0.01); no validation error.
- m_a=0.010, m_b=0.009 (1.9%): validation error referencing 2.0% minimum constraint.
- m_a=0.020, m_b=0.000 (2.0%): passes validation.
- m_a=0.0, m_b=0.0 for same-currency rule: passes validation (domestic exception).
- m_a=0.0, m_b=0.0 for cross-border rule: rejected with 2% minimum error.
**Depends on:** 15.4-T01

### 15.4-T21 — Rate engine unit tests: RV-09 rounding edge case with non-divisible payout  _(30 min)_
**Context:** RV-09: target_payout=10001 KRW (not divisible by 1350). payout_usd_cost=10001/1350=7.40815 USD (non-integer), collection_usd=7.40815/0.975=7.59810 USD, send_amount=7.59810*3500=26593.33 MNT. All intermediate values must retain at least 4 decimal places. Rounding applied only at collection_amount layer. Pool identity tolerance <= 0.01 USD.
**Steps:** Write testRV09_RoundingEdgeCase() in RateEngineUnitTest.; Call rate engine with target_payout=10001, cost_rate_pay=1350.00, cost_rate_coll=3500.00, m_a=0.015, m_b=0.010, service_charge=500.; Assert payout_usd_cost=7.40815 (+/-0.0001).; Assert collection_usd=7.59810 (+/-0.0001); send_amount=26593.33 MNT (+/-0.01).; Assert intermediate values (payout_usd_cost, collection_usd) stored with at least 4 decimal places.; Assert pool identity delta <= 0.01 USD.
**Deliverable:** Unit test testRV09_RoundingEdgeCase in RateEngineUnitTest
**Acceptance / logic checks:**
- payout_usd_cost=7.40815 (+/-0.0001 USD); no integer overflow or truncation.
- collection_usd=7.59810 (+/-0.0001 USD).
- send_amount=26593.33 MNT (+/-0.01 MNT).
- Intermediate values stored with >=4 decimal places (use BigDecimal or equivalent, not float).
- Pool identity delta <= 0.01 USD.
**Depends on:** 15.4-T01

### 15.4-T22 — Rate engine unit tests: RV-10 service charge separation from USD pool  _(30 min)_
**Context:** RV-10: Same inputs as RV-01 except service_charge=5000 MNT (large). USD pool computed identically: collection_usd=10.2564 USD, send_amount=35897.44 MNT (same as RV-01). collection_amount=35897.44+5000=40897.44 MNT. Pool identity check uses only (collection_usd - margins - payout_usd_cost), NOT collection_amount. service_charge recorded separately in revenue ledger, not in USD pool.
**Steps:** Write testRV10_ServiceChargeSeparation() in RateEngineUnitTest.; Call rate engine with same inputs as RV-01 except service_charge=5000 MNT.; Assert send_amount=35897.44 MNT (identical to RV-01, service_charge has no effect on send_amount).; Assert collection_amount=40897.44 MNT (+/-0.01).; Assert pool identity check uses collection_usd (not collection_amount): delta <= 0.01 USD.; Assert service_charge is recorded in a separate revenue_ledger field/table, not added to collection_usd.
**Deliverable:** Unit test testRV10_ServiceChargeSeparation in RateEngineUnitTest
**Acceptance / logic checks:**
- send_amount=35897.44 MNT (+/-0.01), identical to RV-01 result.
- collection_amount=40897.44 MNT (+/-0.01).
- Pool identity check operates on collection_usd (not collection_amount): delta <= 0.01 USD.
- service_charge (5000 MNT) recorded in separate revenue ledger field, not embedded in collection_usd.
- offer_rate_coll and cross_rate unchanged vs RV-01 (service_charge does not affect derived rates).
**Depends on:** 15.4-T01

### 15.4-T23 — Admin System functional suite: AD-001 to AD-005 scheme/partner creation and rule mapping  _(50 min)_
**Context:** AD-001: Create new QR scheme; scheme appears in list. AD-002: Onboard OVERSEAS partner with API credentials and webhook URL. AD-003: Map partner to scheme; currency section auto-derives collect/settle-A/settle-B/payout ccys (read-only). AD-004: Rate config section auto-assigns LIVE slots for non-identity legs; IDENTITY flag set when settle=USD. AD-005: Override LIVE to MANUAL rate source; audit log entry created with actor, timestamp, old=LIVE, new=MANUAL.
**Steps:** Via Admin API, POST a new scheme record; assert it appears in GET /admin/schemes.; POST a new OVERSEAS partner with api_key, api_secret, webhook_url; assert partner retrievable with credentials.; POST a partner-scheme mapping; assert currency section shows collection_ccy, settle_a_ccy, settle_b_ccy, payout_ccy derived and no manual edit fields exposed.; Assert rate config shows LIVE source for MNT/KRW legs; IDENTITY flag for USD legs.; PATCH the rate source from LIVE to MANUAL on the collection leg; assert audit log entry with actor, timestamp, old_value=LIVE, new_value=MANUAL.
**Deliverable:** Automated functional tests AD-001 through AD-005 in AdminFunctionalTest class
**Acceptance / logic checks:**
- New scheme appears in GET /admin/schemes list without restart or redeploy.
- New partner retrievable with API credentials; webhook_url stored correctly.
- Currency section fields are read-only and auto-derived (no manual currency input accepted).
- Rate config IDENTITY flag set when settle_a_ccy=USD; LIVE assigned for non-identity legs.
- Audit log entry for rate-source override contains actor, timestamp, old_value, new_value.
**Depends on:** 15.4-T01

### 15.4-T24 — Admin System functional suite: AD-006 to AD-008 margin validation at rule save  _(35 min)_
**Context:** AD-006: m_a+m_b=2.0% (cross-border rule) must save successfully. AD-007: m_a+m_b=1.9% (cross-border) must be rejected at save time with a validation error referencing the 2% minimum. AD-008: m_a=0, m_b=0 for a domestic (same-currency) rule must save successfully (same-currency short-circuit). Admin API endpoint is POST/PUT /admin/rules/{id}.
**Steps:** POST /admin/rules with m_a=0.010, m_b=0.010, cross_border=true; assert 200 OK and rule persisted.; POST /admin/rules with m_a=0.010, m_b=0.009, cross_border=true; assert 422 with validation error message referencing 2% minimum.; POST /admin/rules with m_a=0.0, m_b=0.0, cross_border=false (domestic); assert 200 OK.; Attempt GET /v1/rates using the rejected 1.9% rule (should not exist); assert NO_RULE_FOR_PARTNER or similar.; Assert the 2.0% rule is usable for a new payment quote.
**Deliverable:** Automated functional tests AD-006, AD-007, AD-008 in AdminFunctionalTest
**Acceptance / logic checks:**
- m_a=0.010, m_b=0.010 cross-border: rule saved, status 200.
- m_a=0.010, m_b=0.009 cross-border: rejected 422 with error referencing 2.0% minimum.
- m_a=0, m_b=0 domestic: saved successfully.
- Rejected rule not persisted (GET /admin/rules returns no rule with combined_margin=1.9%).
- Domestic rule (0%) works correctly for same-currency payment quote.
**Depends on:** 15.4-T01

### 15.4-T25 — Admin System functional suite: AD-009 / AD-010 service charge flat and volume-tier configuration  _(40 min)_
**Context:** AD-009: Flat service charge KRW 500 saved and visible in rule's service-charge section. AD-010: Volume-tier table saved; correct tier selected per transaction amount. Example tiers: 0-50000 KRW = 300 KRW, 50001-200000 KRW = 500 KRW, 200001+ KRW = 800 KRW. For a 13500 KRW transaction the tier 0-50000 applies, yielding service_charge=300 KRW.
**Steps:** POST /admin/rules/{id}/service-charge with type=FLAT, amount=500, ccy=KRW; assert 200 and retrievable.; POST /admin/rules/{id}/service-charge with type=TIERED and tiers: [{min:0,max:50000,charge:300},{min:50001,max:200000,charge:500},{min:200001,charge:800}]; assert 200.; Trigger a payment quote for target_payout=13500 KRW; assert service_charge in response = 300 (tier 1).; Trigger a payment quote for target_payout=100000 KRW; assert service_charge = 500 (tier 2).; Trigger a payment quote for target_payout=250000 KRW; assert service_charge = 800 (tier 3).
**Deliverable:** Automated functional tests AD-009 and AD-010 in AdminFunctionalTest
**Acceptance / logic checks:**
- Flat charge KRW 500: saved and returned as service_charge=500 in rate quote for any amount.
- Tiered config: target_payout=13500 returns service_charge=300; target_payout=100000 returns 500; target_payout=250000 returns 800.
- Boundary values: target_payout=50000 returns 300 (in tier 1); target_payout=50001 returns 500 (in tier 2).
- Tiered config retrieved correctly via GET /admin/rules/{id}/service-charge.
- collection_amount correctly adds selected tier charge to send_amount.
**Depends on:** 15.4-T01

### 15.4-T26 — Admin System functional suite: AD-011 / AD-012 audit log and rule-change applies to new transactions only  _(40 min)_
**Context:** AD-011: Changing m_a must create an audit log entry with actor, timestamp, old_value, new_value. AD-012: A pending payment quote issued before the rule change must use the old m_a; a new quote issued after the change must use the new m_a. In-flight quotes must not be invalidated mid-transaction by a rule change.
**Steps:** Issue a GET /v1/rates quote with m_a=0.015; capture quote token with validUntil.; Ops changes m_a to 0.020 via Admin API; assert audit log entry.; POST /v1/payments using the pre-change quote token; assert collection_usd uses m_a=0.015 (old value).; Issue a new GET /v1/rates quote; assert new quote uses m_a=0.020.; Assert the POST /v1/payments with old token still commits with old rate (rate-lock).; Assert audit log entry contains actor, timestamp, old_value=0.015, new_value=0.020.
**Deliverable:** Automated functional tests AD-011 and AD-012 in AdminFunctionalTest
**Acceptance / logic checks:**
- Audit log entry exists with actor, timestamp, old_value=0.015, new_value=0.020.
- Payment committed with pre-change quote uses m_a=0.015.
- New quote after rule change uses m_a=0.020.
- Audit log change is visible in GET /admin/audit-log with correct filter by entity_type=RULE.
- Rule change applies only to transactions initiated after the change (old committed txns unaffected).
**Depends on:** 15.4-T01

### 15.4-T27 — Admin System functional suite: AD-013 operator setup timer under 30 minutes  _(45 min)_
**Context:** AD-013: Timed walkthrough of the full 4-section Admin flow for onboarding a new OVERSEAS partner and a new scheme. The flow is: (1) create scheme, (2) create partner, (3) map partner to scheme (currency section auto-derives), (4) configure rate section (LIVE/IDENTITY), (5) set margins, (6) set service charge. Total wall-clock time must be under 30 minutes. This is a UAT-style scripted test.
**Steps:** Start a timer. Log into Admin System with Ops credentials.; Create a new scheme named TEST-SCHEME-AD013.; Create a new OVERSEAS partner named TEST-PARTNER-AD013 with API credentials and webhook URL.; Map TEST-PARTNER-AD013 to TEST-SCHEME-AD013; confirm currency section auto-populates.; Configure rate config section with LIVE for MNT leg; set m_a=0.015, m_b=0.010; set flat service charge=500 MNT.; Save rule and stop timer; assert total elapsed time < 30 minutes; assert partner can obtain a rate quote.
**Deliverable:** Scripted functional/UAT test AD-013 in AdminFunctionalTest (or UAT script), demonstrating sub-30-min onboarding with complete 4-section rule
**Acceptance / logic checks:**
- All four Admin sections (currency, rate config, margins, service charge) completed without errors.
- Elapsed time from step 1 to step 6 is < 30 minutes (recorded in test output).
- No code deployment or restart occurred during the walkthrough.
- TEST-PARTNER-AD013 can immediately call GET /v1/rates and receive a valid quote after setup.
- No manual SQL or config-file edit required; entire setup done through Admin UI/API.
**Depends on:** 15.4-T23, 15.4-T24, 15.4-T25

### 15.4-T28 — Admin System functional suite: AD-014 refund processing and AD-015 settlement exception resolution  _(45 min)_
**Context:** AD-014: Ops initiates refund for a prior-day transaction via Admin Portal; ZP0021 file must include the refund record in the next batch run. AD-015: Ops resolves a flagged batch discrepancy in the exception management queue; exception marked RESOLVED with action logged and audit trail.
**Steps:** Create and commit a payment on the previous day. Log in as Ops; navigate to the transaction; initiate refund.; Assert transaction status changes to REFUND_PENDING; refund record appears in ZP0021 on next batch trigger.; Assert refund > original amount is rejected (validation error).; Create a synthetic discrepancy in the exception queue. Log in as Ops; resolve it with a resolution_note.; Assert exception status = RESOLVED; audit trail shows actor, timestamp, resolution_note.; Assert resolved exception not re-raised by next reconciliation run.
**Deliverable:** Automated functional tests AD-014 and AD-015 in AdminFunctionalTest
**Acceptance / logic checks:**
- Refund for prior-day transaction changes status to REFUND_PENDING; ZP0021 includes refund record.
- Refund > original amount rejected with validation error.
- Settlement exception resolved by Ops; status=RESOLVED in DB.
- Audit log entry for exception resolution contains actor, timestamp, resolution_note.
- Refund amount <= original amount validation enforced (e.g. original=36397 MNT; refund attempt of 40000 MNT rejected).
**Depends on:** 15.4-T01

### 15.4-T29 — Partner Portal functional suite: PP-001 to PP-005 login, balance, transaction history, data isolation  _(45 min)_
**Context:** PP-001: Valid partner login returns dashboard with own data only. PP-002: Invalid credentials return 401. PP-003: Prefunding balance inquiry shows current USD balance, threshold status, recent deductions. PP-004: Transaction history date filter; CSV export matches filtered rows. PP-005: Internal fields m_a, m_b, cost_rate_coll, cost_rate_pay, GME margin must NOT appear in any portal response or CSV export.
**Steps:** Login as P-TEST-002 with correct credentials; assert dashboard loads with partner_id=P-TEST-002 data.; Login with wrong password; assert 401 INVALID_CREDENTIALS.; Call GET /portal/balance; assert current_balance_usd, threshold_status, recent_deductions present.; Call GET /portal/transactions?from=2026-01-01&to=2026-03-31; assert results filtered to date range; download CSV; assert CSV row count matches JSON count.; Inspect all portal API response fields; assert m_a, m_b, cost_rate_coll, cost_rate_pay, gme_margin are absent.
**Deliverable:** Automated functional tests PP-001 through PP-005 in PartnerPortalFunctionalTest
**Acceptance / logic checks:**
- Valid login returns 200 with partner's own data; no other partner data.
- Invalid credentials return 401 with error_code=INVALID_CREDENTIALS.
- Balance response contains current_balance_usd and threshold_status.
- CSV export contains exactly the same rows as the filtered JSON response.
- None of m_a, m_b, cost_rate_coll, cost_rate_pay, gme_margin appear in any portal response field or CSV column.
**Depends on:** 15.4-T01

### 15.4-T30 — Partner Portal functional suite: PP-006 transaction detail and PP-007 cross-partner IDOR isolation  _(35 min)_
**Context:** PP-006: GET /portal/transactions/{id} for an own transaction returns all permitted fields; internal revenue fields absent. PP-007 (IDOR test): Partner A (P-TEST-002) attempting to query a transaction belonging to Partner B (P-TEST-003) must receive 403. This is a mandatory IDOR test per SEC-09 and QA-12.
**Steps:** Create a transaction for P-TEST-002; capture txn_id_A.; Login as P-TEST-002; call GET /portal/transactions/{txn_id_A}; assert 200 with permitted fields and no m_a/m_b/cost_rate fields.; Create a transaction for P-TEST-003; capture txn_id_B.; Login as P-TEST-002; call GET /portal/transactions/{txn_id_B}; assert 403.; Login as P-TEST-003; call GET /portal/transactions/{txn_id_A}; assert 403.; Verify GET /portal/transactions (list) for P-TEST-002 returns only P-TEST-002 transactions.
**Deliverable:** Automated functional tests PP-006 and PP-007 in PartnerPortalFunctionalTest
**Acceptance / logic checks:**
- GET /portal/transactions/{own_id} returns 200 with txn_id, status, collection_amount, payout_amount but no m_a, m_b, cost rates.
- GET /portal/transactions/{other_partner_id} returns 403.
- Transaction list for P-TEST-002 contains zero transactions belonging to P-TEST-003 or any other partner.
- IDOR test passes for both numeric sequential IDs and UUID formats (ensure UUIDs used, not sequential).
- 403 response does not leak whether the transaction exists (no 404 that reveals txn_id ownership).
**Depends on:** 15.4-T01

### 15.4-T31 — Partner API contract suite: PA-001/PA-002 authentication endpoints  _(35 min)_
**Context:** PA-001: POST /v1/auth/token with valid api_key + api_secret returns 200 and a bearer token. PA-002: invalid credentials return 401 with error_code=INVALID_CREDENTIALS. Contract test validates response schema against openapi/partner-api.yaml definitions for both success and error paths.
**Steps:** Using a contract-test framework (e.g. schemathesis or Dredd), load openapi/partner-api.yaml.; Test POST /v1/auth/token with valid credentials for P-TEST-002; assert 200 and response body matches the token schema.; Test POST /v1/auth/token with wrong secret; assert 401 with error_code=INVALID_CREDENTIALS and error response matches error schema.; Test POST /v1/auth/token with missing api_key field; assert 400 with appropriate error.; Assert the issued token contains expiry and is usable for subsequent calls.
**Deliverable:** Contract tests PA-001 and PA-002 in PartnerApiContractTest, validated against openapi/partner-api.yaml
**Acceptance / logic checks:**
- Valid credentials: 200 response body matches auth token schema in partner-api.yaml.
- Invalid credentials: 401 with error_code=INVALID_CREDENTIALS; error body matches error schema.
- Missing required field: 400 response with schema-conformant error body.
- Issued token is accepted as Bearer token on a subsequent GET /v1/rates call.
- Zero schema mismatches reported by contract framework.
**Depends on:** 15.4-T01

### 15.4-T32 — Partner API contract suite: PA-003 GET /v1/rates MPM inbound full response schema  _(35 min)_
**Context:** PA-003: GET /v1/rates for MPM Inbound (P-TEST-002, MNT/KRW) must return a full USD pool breakdown: payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd, send_amount, collection_amount, offer_rate_coll, cross_rate, validUntil. Response must match openapi/partner-api.yaml RateQuoteResponse schema. validUntil = quote_issued_at + TTL (default 300s or configured value).
**Steps:** Call GET /v1/rates with partner=P-TEST-002, target_payout=13500, payout_ccy=KRW, collection_ccy=MNT.; Assert response status 200 and body matches RateQuoteResponse schema in partner-api.yaml.; Assert all required USD pool fields are present and non-null.; Assert validUntil is set to approximately quote_issued_at + TTL (within 2 seconds tolerance).; Test GET /v1/rates with missing required param (e.g. no target_payout); assert 400 with schema-conformant error body.
**Deliverable:** Contract test PA-003 in PartnerApiContractTest for GET /v1/rates
**Acceptance / logic checks:**
- 200 response contains payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd, send_amount, collection_amount, offer_rate_coll, cross_rate, validUntil.
- validUntil = quote_issued_at + configured_TTL (within 2 s).
- Response schema is a strict match against RateQuoteResponse in partner-api.yaml (no extra or missing fields).
- Missing required param returns 400 with error response matching error schema.
- Cross_rate and offer_rate_coll present and non-zero for cross-border quote.
**Depends on:** 15.4-T01

### 15.4-T33 — Partner API contract suite: PA-004/PA-005 POST /v1/payments and CPM generate  _(40 min)_
**Context:** PA-004: POST /v1/payments (MPM Inbound) with valid quote token; prefunding deducted; payment.approved webhook fired; response matches PaymentResponse schema. PA-005: POST /v1/payments/cpm/generate with valid params; QR token returned; prefunding deducted; response matches CpmGenerateResponse schema. Both must be validated against openapi/partner-api.yaml.
**Steps:** Call GET /v1/rates to get a quote; then POST /v1/payments; assert 200 and PaymentResponse schema match.; Assert payment.approved webhook fires with required fields: txn_id, status, collection_amount, cross_rate.; Call POST /v1/payments/cpm/generate; assert 200 and CpmGenerateResponse contains qr_token field.; Test POST /v1/payments with insufficient prefund (balance=0); assert 422 with error_code=INSUFFICIENT_PREFUNDING and schema-conformant error body.; Test POST /v1/payments/cpm/generate with insufficient prefund; assert 422 INSUFFICIENT_PREFUNDING.
**Deliverable:** Contract tests PA-004 and PA-005 in PartnerApiContractTest
**Acceptance / logic checks:**
- POST /v1/payments: 200 with PaymentResponse schema match; txn_id, status=APPROVED present.
- payment.approved webhook contains txn_id, status, collection_amount, cross_rate (per §7.3).
- POST /v1/payments/cpm/generate: 200 with qr_token non-null; CpmGenerateResponse schema match.
- Insufficient prefunding: 422 with error_code=INSUFFICIENT_PREFUNDING; schema-conformant error body.
- Zero schema mismatches on both happy-path and error responses.
**Depends on:** 15.4-T01

### 15.4-T34 — Partner API contract suite: PA-006 webhook delivery and retry policy  _(45 min)_
**Context:** PA-006: If partner webhook returns non-2xx, system retries with exponential backoff per API-05. Test: mock webhook returning 500 for first 3 attempts; verify delivery on 4th attempt; verify payload is identical on retry (idempotent delivery). Also verify payment.pending_debit event contains txn_id, offer_rate, collection_amount, validUntil per §7.3.
**Steps:** Set up mock webhook endpoint that returns 500 for first 3 calls and 200 on the 4th.; Trigger a CPM payment flow that emits payment.pending_debit and payment.approved.; Assert payment.pending_debit payload contains txn_id, offer_rate, collection_amount, validUntil.; Assert payment.approved delivered on 4th attempt; payload identical (same txn_id, same collection_amount) across all 4 attempts.; Assert system does not alter the payload on retries; verify delivery timestamp of 4th call.; Assert retry delays follow exponential backoff pattern (first retry ~1s, second ~2s, third ~4s, or per API-05 spec).
**Deliverable:** Contract/integration test PA-006 in PartnerApiContractTest for webhook retry and idempotency
**Acceptance / logic checks:**
- Webhook delivered on 4th attempt after 3 failures.
- Payload identical across all delivery attempts (same txn_id, collection_amount, status).
- payment.pending_debit webhook contains txn_id, offer_rate, collection_amount, validUntil.
- Retry delays are non-decreasing (exponential backoff pattern).
- System does not mark transaction as FAILED after 3 webhook failures (only after max-retry exhaustion per API-05).
**Depends on:** 15.4-T01

### 15.4-T35 — Partner API contract suite: PA-007/PA-008 idempotency key replay and rate TTL expiry  _(40 min)_
**Context:** PA-007: Duplicate POST /v1/payments with same X-Idempotency-Key returns identical response with no side effects (no second deduction). PA-008: POST /v1/payments with a quote where validUntil is in the past returns RATE_QUOTE_EXPIRED. Also tests GET /v1/payments/{id} (own=200, other-partner=403, not-found=404) and POST /v1/payments/{id}/cancel (same-day=200, post-settlement=422 CANCEL_NOT_ALLOWED).
**Steps:** Send POST /v1/payments with X-Idempotency-Key=REPLAY-001; capture txn_id and balance.; Resend with same key; assert 200 with same txn_id; assert balance unchanged (no second deduction).; Send POST /v1/payments with expired quote (backdated validUntil); assert 422 RATE_QUOTE_EXPIRED.; GET /v1/payments/{txn_id} with correct partner token; assert 200 with schema match.; GET /v1/payments/{txn_id} with different partner token; assert 403.; GET /v1/payments/NONEXISTENT-ID; assert 404 PAYMENT_NOT_FOUND. Then test cancel paths.
**Deliverable:** Contract tests PA-007 and PA-008 in PartnerApiContractTest covering idempotency, expiry, and cancel schema
**Acceptance / logic checks:**
- Idempotency replay: same txn_id returned; balance deducted exactly once.
- Expired quote: 422 with error_code=RATE_QUOTE_EXPIRED.
- GET own transaction: 200 with correct schema.
- GET other-partner transaction: 403.
- GET nonexistent: 404 PAYMENT_NOT_FOUND.
**Depends on:** 15.4-T01

### 15.4-T36 — Partner API contract suite: error code full coverage across all documented codes  _(50 min)_
**Context:** QA-12 §7.4 requires every error code in API-05 error catalog to be exercised by at least one contract test. Required codes: INVALID_CREDENTIALS, RATE_QUOTE_EXPIRED, INSUFFICIENT_PREFUNDING, MERCHANT_INACTIVE, QR_DEACTIVATED, PARTNER_B_QUOTE_DEVIATION, PARTNER_B_QUOTE_UNAVAILABLE, NO_SCHEME_FOR_LOCATION, DUPLICATE_IDEMPOTENCY_KEY, PAYMENT_NOT_FOUND, CANCEL_NOT_ALLOWED.
**Steps:** For each error code, write one parameterized or individual test case that triggers the exact condition.; INVALID_CREDENTIALS: wrong secret. RATE_QUOTE_EXPIRED: expired validUntil. INSUFFICIENT_PREFUNDING: balance=0. MERCHANT_INACTIVE: M-TEST-0002. QR_DEACTIVATED: QR-TEST-0005.; PARTNER_B_QUOTE_DEVIATION: stub returning 1366.20. PARTNER_B_QUOTE_UNAVAILABLE: stub offline. NO_SCHEME_FOR_LOCATION: partner with no matching rule.; DUPLICATE_IDEMPOTENCY_KEY: second call with same key but different body (not replay, genuine conflict). PAYMENT_NOT_FOUND: GET on unknown ID. CANCEL_NOT_ALLOWED: post-settlement cancel.; For each, assert: correct HTTP status, exact error_code value, error response body matches schema.
**Deliverable:** Parameterized contract test ErrorCodeCoverageTest in PartnerApiContractTest covering all 11 error codes from API-05 catalog
**Acceptance / logic checks:**
- All 11 error codes triggered and returned at least once.
- Each error response body matches the error schema in partner-api.yaml (no schema mismatches).
- DUPLICATE_IDEMPOTENCY_KEY differs from idempotent replay: body is different but key is same.
- Error code strings match API-05 catalog exactly (no typos or casing differences).
- Test report shows 11/11 error codes exercised.
**Depends on:** 15.4-T01, 15.4-T31, 15.4-T32, 15.4-T33

### 15.4-T37 — ZeroPay batch suite: ZP-001 payment result submission ZP0011/ZP0012 round-trip  _(50 min)_
**Context:** ZP-001: ZP0011 (GME to ZeroPay payment result file) must be generated and SFTP-transmitted before 02:00 KST. ZP0012 (ZeroPay to GME confirmation) injected synthetically; GME must parse it and reconcile all records. File layout per SCH-06. Test seeds transactions, triggers batch, validates file format, injects ZP0012, verifies DB state. Timing: batch triggered at 01:55 KST in test; SFTP put must complete before 02:00 KST wall clock.
**Steps:** Seed 10 payment transactions in APPROVED or SETTLED status for the target batch date.; Trigger ZP0011 batch generation job.; Assert generated file: correct filename pattern, header/trailer per SCH-06, record count in trailer matches seeded transactions.; Assert SFTP put completed within 5 minutes of trigger (simulating 01:55 start, expect completion before 02:00).; Inject synthetic ZP0012 confirmation file with all 10 records acknowledged.; Parse ZP0012; assert all 10 transactions updated to status=CONFIRMED and no unmatched records flagged.
**Deliverable:** Automated batch test ZP-001 in ZeroPayBatchFunctionalTest covering ZP0011 generation and ZP0012 round-trip
**Acceptance / logic checks:**
- ZP0011 file generated with correct header, detail records (one per seeded transaction), and trailer with correct total count.
- File transmitted to SFTP before the 02:00 KST deadline in test run.
- ZP0012 parse updates all 10 transactions to CONFIRMED.
- No unmatched records flagged after ZP0012 ingestion.
- File record layout matches SCH-06 field definitions (field widths, encoding).
**Depends on:** 15.4-T01

### 15.4-T38 — ZeroPay batch suite: ZP-002 refund result submission ZP0021/ZP0022  _(45 min)_
**Context:** ZP-002: ZP0021 (GME to ZP refund result) generated in next batch after refund initiated; ZP0022 (ZP to GME confirmation) parsed and refund records acknowledged. Same 02:00 KST timing window as ZP0011. Refund records must match original payment txn_ids and amounts. File layout per SCH-06.
**Steps:** Create a committed payment then initiate a refund via Admin Portal; set batch date for tomorrow.; Trigger ZP0021 batch generation job.; Assert ZP0021 file contains the refund record with correct original_txn_id and refund_amount.; Assert file transmitted to SFTP before 02:00 KST deadline.; Inject synthetic ZP0022 confirmation for the refund.; Assert refund transaction updated to REFUND_CONFIRMED in DB.
**Deliverable:** Automated batch test ZP-002 in ZeroPayBatchFunctionalTest covering ZP0021/ZP0022 round-trip
**Acceptance / logic checks:**
- ZP0021 includes one refund record with correct original_txn_id and refund_amount.
- File layout matches SCH-06; trailer record count = number of refund records.
- SFTP transmission before 02:00 KST in test.
- ZP0022 parse marks refund as REFUND_CONFIRMED.
- A second ZP0022 injection for the same records does not double-process (idempotent parse).
**Depends on:** 15.4-T01, 15.4-T37

### 15.4-T39 — ZeroPay batch suite: ZP-003/ZP-004 morning and afternoon settlement (ZP0061-ZP0064)  _(50 min)_
**Context:** ZP-003: ZP0061 (morning settlement request) by 05:00 KST; ZP0062 (settlement result) received by 10:00 KST. GME totals must match ZP result totals. ZP-004: ZP0063 (afternoon settlement) by 14:00 KST; ZP0064 result by 19:00 KST. Domestic settlement is NET (sum of target_payout); International is GROSS (full KRW payout). Reconciliation compares GME computed totals vs ZP result totals.
**Steps:** Seed domestic transactions (P-TEST-001) and international transactions (P-TEST-002) for the settlement date.; Trigger morning settlement batch; assert ZP0061 generated and SFTP-transmitted before 05:00 KST.; Assert ZP0061 domestic total = sum(target_payout) for domestic txns; international total = sum(payout_amount) gross.; Inject ZP0062 with matching totals; assert reconciliation passes (no discrepancy flagged).; Trigger afternoon batch; assert ZP0063 transmitted before 14:00; ZP0064 injected and reconciled.; Assert GME marks batch as RECONCILED with no open exceptions.
**Deliverable:** Automated batch tests ZP-003 and ZP-004 in ZeroPayBatchFunctionalTest for morning and afternoon settlement
**Acceptance / logic checks:**
- ZP0061 SFTP transmission before 05:00 KST deadline; ZP0063 before 14:00 KST.
- Domestic settlement total in ZP0061 = sum of target_payout (net); international total = sum of payout_amount (gross).
- ZP0062 (matching totals) leads to RECONCILED status with no exception raised.
- ZP0064 (matching totals) similarly leads to RECONCILED.
- Any mismatch between GME total and ZP total triggers an auto-flag and ops alert (see ZP-013).
**Depends on:** 15.4-T01, 15.4-T37

### 15.4-T40 — ZeroPay batch suite: ZP-005 settlement detail ZP0065/ZP0066 and ZP-013 discrepancy detection  _(50 min)_
**Context:** ZP-005: ZP0065/ZP0066 settlement detail files by 22:00 KST; line-by-line reconciliation of every transaction must pass. ZP-013: If ZP0062 total differs from GME computed total, system must auto-flag, create an exception record, and send an ops alert. Test both: perfect match (no alert) and one-record discrepancy (alert + exception).
**Steps:** Seed transactions for settlement detail. Trigger ZP0065/ZP0066 generation; assert files by 22:00 KST and all records match DB data line by line.; Inject ZP0062 with total matching GME; assert no exception created, no alert.; Inject ZP0062 with total differing by KRW 1000 (e.g. GME total=5,000,000 KRW, ZP total=4,999,000 KRW); assert exception record created and ops alert sent.; Assert exception record in DB contains: discrepancy_amount, batch_date, file_type=ZP0062, status=OPEN.; Assert alert contains discrepancy_amount and batch_date.
**Deliverable:** Automated batch tests ZP-005 and ZP-013 in ZeroPayBatchFunctionalTest
**Acceptance / logic checks:**
- ZP0065/ZP0066 line-by-line reconciliation passes for all seeded transactions.
- Files transmitted by 22:00 KST deadline.
- Perfect-match ZP0062 ingestion: no exception, no alert.
- Discrepant ZP0062 (diff=1000 KRW): exception record created with status=OPEN and discrepancy_amount=1000.
- Ops alert sent with discrepancy_amount and batch_date for the discrepant case.
**Depends on:** 15.4-T01, 15.4-T39

### 15.4-T41 — ZeroPay batch suite: ZP-006 to ZP-010 merchant and QR sync  _(50 min)_
**Context:** ZP-006: Incremental merchant sync (ZP0041) inserts new and updates changed merchants. ZP-007: Franchise merchant sync (ZP0045/ZP0047) persists franchise hierarchy. ZP-008: Full merchant list sync (ZP0051/ZP0055) rebuilds DB to match ZeroPay golden dataset. ZP-009: QR deactivation (ZP0043) blocks the QR immediately. ZP-010: Full QR list sync (ZP0053) refreshes all QR records.
**Steps:** Inject ZP0041 with 3 new merchants and 1 updated merchant; assert DB has 3 new rows and 1 updated row.; Inject ZP0045/ZP0047 with a franchise (parent + 2 child merchants); assert hierarchy persisted with parent_merchant_id links.; Inject ZP0051/ZP0055 (full rebuild); assert DB merchant count matches file record count exactly.; Inject ZP0043 with QR-TEST-0005 set to DEACTIVATED; assert immediate QR status change; payment attempt with QR-TEST-0005 returns QR_DEACTIVATED.; Inject ZP0053 (full QR list); assert all QR records updated to match file.
**Deliverable:** Automated batch tests ZP-006 through ZP-010 in ZeroPayBatchFunctionalTest
**Acceptance / logic checks:**
- ZP0041: new merchants inserted; existing merchant updated (not duplicated).
- ZP0045/ZP0047: franchise parent-child hierarchy stored with correct parent_merchant_id.
- ZP0051 full rebuild: DB merchant count equals file record count after sync.
- ZP0043 deactivation: payment with deactivated QR returns QR_DEACTIVATED error immediately after sync.
- ZP0053 full QR sync: QR records match file dataset; no stale records remain.
**Depends on:** 15.4-T01

### 15.4-T42 — ZeroPay batch suite: ZP-011 SFTP transmission failure retry and ZP-012 registration rejection  _(45 min)_
**Context:** ZP-011: If SFTP put for ZP0011 fails, a retry must be triggered and an ops alert fired; settlement must NOT be blocked by the first retry (system continues processing). ZP-012: If ZP0012 returns a failure/rejection, ops must be alerted and the settlement batch must be blocked until the issue is resolved. Test ZP-014 (late file after 05:00 KST) also covered: ops alert and previous-day-data flag set.
**Steps:** Configure SFTP stub to reject the first put attempt for ZP0011. Trigger batch. Assert retry fired and ops alert sent.; Configure SFTP stub to succeed on retry. Assert file eventually delivered; settlement NOT blocked during retry.; Inject ZP0012 with failure/rejection code. Assert ops alert sent with rejection details. Assert settlement batch for next cycle is blocked (status=BLOCKED_PENDING_RESOLUTION).; Inject ZP0012 arriving after 05:00 KST simulated deadline. Assert ops alert for late arrival; previous_day_data_flag=true set on that batch.
**Deliverable:** Automated batch tests ZP-011, ZP-012, ZP-014 in ZeroPayBatchFunctionalTest
**Acceptance / logic checks:**
- SFTP first-attempt failure triggers retry; ops alert sent with file_type=ZP0011 and retry_count.
- Settlement processing not blocked by single SFTP retry (continues and completes on retry success).
- ZP0012 rejection: ops alert sent; settlement batch status=BLOCKED_PENDING_RESOLUTION.
- ZP0012 late arrival (after 05:00): ops alert; previous_day_data_flag=true in batch record.
- SFTP retry count logged; max retry limit not exceeded silently (alert on max retry exhaustion).
**Depends on:** 15.4-T01, 15.4-T37

### 15.4-T43 — Prefunding functional suite: PF-001/PF-002 atomic deduction for MPM and CPM  _(45 min)_
**Context:** PF-001: MPM payment SELECT FOR UPDATE must acquire a row-level lock on the partner prefunding row; balance decremented atomically; scheme called after successful deduction. PF-002: CPM deduction occurs at POST /v1/payments/cpm/generate (not at scheme approval); deduction_timestamp precedes scheme_call_timestamp. Atomic deduction uses SELECT ... FOR UPDATE on the partner_prefunding table.
**Steps:** Enable query logging on test DB. Call POST /v1/payments for P-TEST-002 (MPM).; Assert query log contains SELECT ... FOR UPDATE on partner_prefunding where partner_id=P-TEST-002.; Assert balance = balance_before - collection_usd (within 0.01 USD) after deduction.; Assert scheme call timestamp > deduction timestamp (from transaction event trail).; For CPM: call POST /v1/payments/cpm/generate; assert deduction_timestamp in event trail predates scheme_call_timestamp.; Assert rollback: if scheme call fails after deduction, balance is restored (deduction is part of the same DB transaction).
**Deliverable:** Automated integration tests PF-001 and PF-002 in PrefundingFunctionalTest
**Acceptance / logic checks:**
- SELECT FOR UPDATE used on partner_prefunding row during MPM deduction (verified via query log or explain plan).
- Balance after MPM deduction = balance_before - collection_usd (+/-0.01 USD).
- CPM deduction timestamp < scheme call timestamp in event trail.
- Scheme not called if deduction fails (e.g. balance=0).
- If scheme call fails after deduction, balance is restored (transactional rollback).
**Depends on:** 15.4-T01

### 15.4-T44 — Prefunding functional suite: PF-003 concurrent request race condition test  _(50 min)_
**Context:** PF-003: Two requests for P-TEST-002 submitted concurrently (at same millisecond or near-simultaneously) must each deduct exactly once; no double-spend; the second request must wait for the lock. Balance after = balance_before - 2 * collection_usd (if both have sufficient balance). SELECT FOR UPDATE must serialize deductions. Test with balance sufficient for both payments.
**Steps:** Set P-TEST-002 balance to 50000 USD. Launch two concurrent POST /v1/payments calls for P-TEST-002 using two threads/async calls at the same time.; Wait for both to complete. Assert both returned 200 or one returned 200 and one returned INSUFFICIENT_PREFUNDING (if balance was tight).; Assert final balance = initial_balance - (number_of_approved * collection_usd) within 0.01 USD.; Assert no race-condition double-deduction: final balance is never less than initial_balance - 2 * collection_usd.; Assert exactly two separate transaction records created (not one shared record).; Repeat with balance sufficient for exactly 1 payment (balance=10.30 USD, collection_usd=10.2564): assert exactly one succeeds, one gets INSUFFICIENT_PREFUNDING.
**Deliverable:** Concurrent race-condition integration test PF-003 in PrefundingFunctionalTest
**Acceptance / logic checks:**
- Both requests complete without deadlock or timeout.
- Final balance = initial - (approved_count * collection_usd) within 0.01 USD; no over-deduction.
- With balance for 1 payment: exactly 1 approved, 1 INSUFFICIENT_PREFUNDING; balance = initial - 1 * collection_usd.
- No duplicate transaction records for the same payment (idempotency preserved).
- SELECT FOR UPDATE confirmed serializing deductions (second thread blocked until first commits).
**Depends on:** 15.4-T01, 15.4-T43

### 15.4-T45 — Prefunding functional suite: PF-004 to PF-008 balance states, alert, threshold config, cancel restore  _(45 min)_
**Context:** PF-004: Balance=9000 USD, collection_usd=10000 USD: rejected before scheme. PF-005: Balance=0: all OVERSEAS payments suspended. PF-006: Balance drops below threshold (e.g. 10000 USD): email alert sent, transaction continues. PF-007: Ops changes threshold for P-TEST-002; new threshold applied to next deduction. PF-008: Cancel of approved payment restores balance by exactly collection_usd.
**Steps:** PF-004: set balance=9000; attempt payment with collection_usd=10000; assert 422 INSUFFICIENT_PREFUNDING; balance unchanged.; PF-005: set balance=0; any payment attempt returns 422 INSUFFICIENT_PREFUNDING.; PF-006: set balance=10005, threshold=10000; perform payment with collection_usd=10.26; assert alert sent; transaction approved.; PF-007: change threshold for P-TEST-002 to 15000 USD via Admin API; perform a deduction that crosses 15000; assert alert sent at new threshold.; PF-008: approve a payment (deduction applied); cancel it; assert balance restored by exact collection_usd amount.
**Deliverable:** Automated functional tests PF-004 through PF-008 in PrefundingFunctionalTest
**Acceptance / logic checks:**
- PF-004: 422 INSUFFICIENT_PREFUNDING when balance (9000 USD) < collection_usd (10000 USD); balance unchanged.
- PF-005: 422 INSUFFICIENT_PREFUNDING when balance=0; no scheme call made.
- PF-006: email alert sent when balance crosses below 10000 USD threshold; transaction approved.
- PF-007: new threshold (15000 USD) triggers alert at the new level, not old 10000 USD level.
- PF-008: after cancel, balance = balance_before_payment exactly (within 0.01 USD); collection_usd fully restored.
**Depends on:** 15.4-T01, 15.4-T43

### 15.4-T46 — Refund/cancel functional suite: RF-001 to RF-004 all cancel and refund scenarios  _(45 min)_
**Context:** RF-001: POST /v1/payments/{id}/cancel same-day: CANCELLED; prefunding restored; ZeroPay cancel notification sent. RF-002: Admin Portal refund for prior-day transaction: ZP0021 includes refund in next batch; ZP0022 confirmation expected. RF-003: Refund > original amount rejected with validation error. RF-004: Cancel of post-settlement transaction rejected (must use refund path). All four scenarios per QA-12 §5.7.
**Steps:** RF-001: create OVERSEAS payment today; cancel same-day; assert CANCELLED; prefunding restored; check ZeroPay cancel event triggered.; RF-002: create and settle prior-day transaction; initiate refund via Admin Portal; trigger ZP0021 batch; assert refund record in file.; RF-003: attempt refund of amount > original (e.g. original=36397.44 MNT; refund=40000 MNT); assert validation error.; RF-004: attempt POST /v1/payments/{id}/cancel on a SETTLED transaction; assert 422 CANCEL_NOT_ALLOWED.; Assert RF-003 error message references maximum refund amount.
**Deliverable:** Automated functional tests RF-001 through RF-004 in RefundCancelFunctionalTest
**Acceptance / logic checks:**
- RF-001: cancel returns 200 with status=CANCELLED; prefunding balance restored by collection_usd; payment.cancelled webhook fires.
- RF-002: ZP0021 next batch contains refund record with correct txn_id and refund_amount.
- RF-003: refund > original amount rejected; validation error referencing max refund constraint.
- RF-004: cancel of SETTLED txn returns 422 CANCEL_NOT_ALLOWED.
- ZeroPay cancel notification event is recorded in transaction event trail for RF-001.
**Depends on:** 15.4-T01, 15.4-T37, 15.4-T43

### 15.4-T47 — Hub Core functional suite: HC-008-extension rate-lock across all RV vectors committed transactions  _(35 min)_
**Context:** Extension of HC-008: After committing transactions for RV-01 (MNT/KRW), RV-02 (USD/KRW), RV-03 (USD/USD), changing treasury rates must not alter the stored cost_rate_coll, cost_rate_pay, collection_usd, send_amount, or derived rates (offer_rate_coll, cross_rate) on any previously committed transaction. New GET /v1/rates calls must use the new treasury rates.
**Steps:** Commit transactions for RV-01, RV-02, and RV-03 inputs; capture each txn_id and stored rates.; Change treasury.usd_krw to 1400 and treasury.usd_mnt to 3600.; For each committed txn, GET /v1/transactions/{txn_id}; assert all stored rates are unchanged from commit-time values.; Issue new GET /v1/rates for MNT/KRW; assert new quote uses treasury.usd_krw=1400 and treasury.usd_mnt=3600.; Assert no background recomputation job altered any committed transaction.
**Deliverable:** Automated rate-lock extension test in HubCoreFunctionalTest covering three RV-vector committed transactions
**Acceptance / logic checks:**
- RV-01 committed txn: cost_rate_pay still 1350.00, cost_rate_coll still 3500.00 after treasury change.
- RV-02 committed txn: cost_rate_pay still 1350.00; cost_rate_coll still IDENTITY.
- RV-03 committed txn: both legs still IDENTITY.
- New rate quote after change returns new treasury rates (1400/3600).
- updated_at on all three committed txn rows is unchanged after treasury update.
**Depends on:** 15.4-T09, 15.4-T15, 15.4-T16, 15.4-T17

### 15.4-T48 — ZeroPay batch suite: net vs gross settlement verification for ZP0061  _(40 min)_
**Context:** QA-12 §9.4: Domestic (GME Remit/ZeroPay): ZP0061 settlement amount = sum of target_payout (NET); ZeroPay credits merchant directly. International (SendMN/ZeroPay): ZP0061 settlement amount = sum of full KRW payout amounts (GROSS); GME invoices merchant separately with 0.21% shared to ZeroPay. Test must verify both settlement models with distinct partner types in the same batch.
**Steps:** Seed 5 domestic transactions for P-TEST-001 with target_payout values: 10000, 20000, 30000, 15000, 25000 KRW. Seed 3 international transactions for P-TEST-002 with payout_amount values: 13500, 27000, 40500 KRW.; Trigger ZP0061 generation.; In the generated file, locate the domestic settlement section; assert total = 100000 KRW (sum of 5 domestic payouts, net).; Locate international settlement section; assert total = 81000 KRW (13500+27000+40500, gross).; Assert 0.21% ZeroPay share is computed on international total (81000 * 0.0021 = 170.10 KRW) and recorded in settlement metadata.
**Deliverable:** Automated batch test NetVsGrossSettlement in ZeroPayBatchFunctionalTest
**Acceptance / logic checks:**
- Domestic section total in ZP0061 = 100000 KRW (sum of target_payout, net).
- International section total = 81000 KRW (sum of payout_amount, gross).
- 0.21% ZeroPay share on international = 170.10 KRW recorded in settlement record.
- Domestic and international sections are separate in the file (not merged).
- A transaction must not appear in both domestic and international sections.
**Depends on:** 15.4-T01, 15.4-T39

### 15.4-T49 — UAT automation script: UAT-001 and UAT-013 new OVERSEAS partner setup under 30 minutes, no deployment  _(55 min)_
**Context:** UAT-001/UAT-013: A new OVERSEAS partner must be fully operational (can make a test payment) within 30 minutes of Ops starting the Admin configuration, with zero code deployments or restarts. This is the config-without-code acceptance test. Uses the Admin System to complete all 4 sections. Script must record wall-clock elapsed time.
**Steps:** Start timer. Verify no deployments or restarts occurred during the test (capture running container image IDs at start and end).; Complete all 4 Admin sections (scheme, partner, currency mapping, rate/margin/service-charge) for a new partner named UAT-PARTNER-001.; Stop timer. Assert elapsed time < 30 minutes.; Call GET /v1/rates as UAT-PARTNER-001; assert 200 with valid quote (confirming partner is live).; Compare container/process image IDs from start and end; assert identical (no redeploy).; Assert no git commit or merge occurred in the application repository during the walkthrough.
**Deliverable:** Scripted UAT test UAT-001-UAT-013 in UatFunctionalTest, recording elapsed time and proving zero deployments
**Acceptance / logic checks:**
- Elapsed time from first Admin action to successful GET /v1/rates < 30 minutes.
- GET /v1/rates for UAT-PARTNER-001 returns 200 with non-null collection_usd and validUntil.
- Container image ID unchanged from test start to end (no redeploy).
- No migration or config file was committed to the repo during the test window.
- All 4 Admin sections completed without errors.
**Depends on:** 15.4-T27

### 15.4-T50 — Go-live readiness checklist: automated verification script for QA-12 §12.2 items 1-14  _(55 min)_
**Context:** QA-12 §12.2 lists 14 go-live readiness criteria. This ticket creates an automated checklist script that verifies the programmatically checkable items: all RV-01 to RV-10 vectors pass, pool-identity assertion alert is wired, rate-lock verified, ZP batch jobs are scheduled (per OPS-13 runbook), low-balance alert email addresses configured, prefunding balances present for all OVERSEAS partners, and partner records configured in Admin (not sandbox).
**Steps:** Write a GoLiveReadinessCheck script/test class that runs against the target environment (staging or production-equivalent).; For each of the 14 checklist items, implement a programmatic check where possible: (3) run all RV-01 to RV-10 vectors; (12) verify pool-identity assertion alert channel configured; (13) verify rate-lock on a committed txn; (7) query scheduled batch jobs for all ZP00xx types.; For items requiring human confirmation (e.g. item 4 - SFTP credentials live, item 9 - DR drill), emit a REQUIRES_MANUAL_SIGN_OFF status.; Generate a checklist output report with PASS/FAIL/MANUAL status per item.; Assert all 14 items are accounted for (no item left unaddressed).
**Deliverable:** GoLiveReadinessCheck script in src/test/integration/GoLiveReadinessCheck.java (or equivalent) that produces a PASS/FAIL/MANUAL report for all 14 QA-12 §12.2 criteria
**Acceptance / logic checks:**
- All 14 checklist items addressed in the script (no item omitted).
- RV-01 to RV-10 vectors all report PASS with zero tolerance failures.
- Pool-identity alert channel check returns PASS when OPS alerting channel is configured.
- Batch job schedule check returns PASS for all 9 ZP00xx file types listed in QA-12 §9.2.
- Items requiring human sign-off (SFTP live, DR drill, Ops training) emit REQUIRES_MANUAL_SIGN_OFF and are not marked PASS automatically.
**Depends on:** 15.4-T14, 15.4-T15, 15.4-T16, 15.4-T17, 15.4-T18, 15.4-T19, 15.4-T20, 15.4-T21, 15.4-T22, 15.4-T37, 15.4-T38, 15.4-T39, 15.4-T40, 15.4-T41, 15.4-T42


## WBS 15.5 — API contract & webhook testing
### 15.5-T01 — Add openapi/partner-api.yaml to repository and configure schemathesis  _(40 min)_
**Context:** GMEPay+ Northbound API contract is defined in openapi/partner-api.yaml (OpenAPI 3.0.3). QA-12 §2.2.3 requires contract tests to be validated against the published spec using a contract-test framework (schemathesis, Dredd, or Pact). The spec file lives in the repo root under openapi/. The sandbox server URL is https://api-sandbox.gmepayplus.com.
**Steps:** Copy the full partner-api.yaml content from Appendix A of the spec into openapi/partner-api.yaml in the repository root.; Validate the file is well-formed: run 'schemathesis validate openapi/partner-api.yaml' (or equivalent OpenAPI linter) and confirm zero errors.; Add a Makefile target (or npm/gradle script) 'contract-test' that runs schemathesis against the sandbox base URL using the spec file.; Commit the spec file and runner script; add openapi/ to .gitignore exclusions for generated client code only.
**Deliverable:** openapi/partner-api.yaml committed to repo root; 'make contract-test' (or equivalent) runs schemathesis against sandbox with zero validation errors on the spec itself.
**Acceptance / logic checks:**
- 'schemathesis validate openapi/partner-api.yaml' exits 0 with zero schema errors.
- File contains all 8 path entries: /v1/rates (POST), /v1/payments (POST), /v1/payments/cpm/generate (POST), /v1/payments/{id} (GET), /v1/payments/{id}/cancel (POST), /v1/merchants/{qr} (GET), /v1/balance (GET), and at minimum one /v1/transactions path per QA-12 §7.2.
- securitySchemes section declares apiKey (X-API-Key header) and signature (X-Signature header) schemes.
- The Error schema enum lists all 23 error codes including INVALID_CREDENTIALS, RATE_QUOTE_EXPIRED, INSUFFICIENT_PREFUNDING, CANCEL_NOT_PERMITTED, DUPLICATE_PARTNER_TXN_REF.
- Runner script accepts BASE_URL as env var so it can be pointed at local, sandbox, or staging.

### 15.5-T02 — Implement contract-test auth helper: HMAC-SHA256 request signing  _(45 min)_
**Context:** All Partner API requests require two security headers: X-API-Key (partner key) and X-Signature (HMAC-SHA256 over canonical string). Canonical string format: {HTTP_METHOD}\n{PATH_WITH_QUERY}\n{X-Timestamp}\n{SHA256_HEX_OF_BODY}. X-Timestamp must be within 300 seconds of server time (ISO-8601 ms precision). Error code INVALID_SIGNATURE is returned if signing is wrong; TIMESTAMP_OUT_OF_RANGE if outside 300 s window.
**Steps:** Create a test helper module (e.g. tests/contract/auth_helper.py or AuthHelper.java) that accepts api_key, api_secret, method, path_with_query, body_bytes and returns the three required headers: X-API-Key, X-Timestamp, X-Signature.; Implement the canonical string as: METHOD + newline + path_with_query + newline + timestamp + newline + hex(sha256(body_bytes)).; Unit-test the helper with a known vector: POST, /v1/rates, timestamp=2026-06-04T09:30:00.000Z, body={} -> verify signature string deterministically.; Expose a convenience wrapper that signs and sends an HTTP request, returning the full response object.
**Deliverable:** auth_helper module with sign_request() function and at least 2 unit tests covering the canonical string construction.
**Acceptance / logic checks:**
- sign_request() with empty body produces X-Signature that matches independently computed HMAC-SHA256 of 'POST\n/v1/rates\n2026-06-04T09:30:00.000Z\ne3b0c44...' (SHA256 of empty string is e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855).
- Changing any one input field (method, path, timestamp, body) produces a different signature.
- X-Timestamp in generated header is within 5 seconds of actual UTC time when called without explicit timestamp override.
- A request signed with a wrong secret results in the server returning 401 with error code INVALID_SIGNATURE (integration smoke test against sandbox).
- A request with X-Timestamp set 301 seconds in the past returns 401 with TIMESTAMP_OUT_OF_RANGE.
**Depends on:** 15.5-T01

### 15.5-T03 — Contract test: POST /v1/rates request schema validation  _(50 min)_
**Context:** POST /v1/rates (operationId: createRateQuote) accepts a QuoteRequest body with required fields: target_payout (decimal string), payout_currency (ISO-4217), scheme_id, direction (enum: domestic/inbound/outbound/hub). Optional: merchant_qr, partner_ref. Returns QuoteResponse with quote_id, offer_rate, send_amount, service_charge, valid_until etc. Missing required fields must return 400 VALIDATION_ERROR. Unknown direction must return 400. Uses P-TEST-002 (OVERSEAS, USD settle) with treasury.usd_krw=1350.00 in sandbox.
**Steps:** Write a contract test that POSTs a valid QuoteRequest for P-TEST-002: target_payout='13500', payout_currency='KRW', scheme_id='zeropay', direction='inbound'. Assert 200 and that response body validates against QuoteResponse schema.; Write tests for each missing required field (omit target_payout; omit payout_currency; omit scheme_id; omit direction) and assert each returns 400 with error.code='VALIDATION_ERROR' and the details array names the offending field.; Write a test with direction='sideways' (invalid enum) and assert 400 VALIDATION_ERROR.; Write a test with target_payout='-100' (negative) and assert 400 or 422 with a validation error.; Assert that the 200 response contains non-null quote_id, valid_until (future datetime), offer_rate (non-zero decimal string), and send_amount.
**Deliverable:** Test file tests/contract/test_rates_schema.py (or equivalent) covering happy-path and all required-field-missing variants.
**Acceptance / logic checks:**
- Happy-path POST returns 200 with quote_id matching pattern 'qte_[A-Za-z0-9]+', valid_until is a future ISO-8601 datetime, offer_rate and send_amount are non-empty decimal strings.
- Each of the 3 missing-required-field requests returns 400; response error.code='VALIDATION_ERROR'; details[].field identifies the missing field by name.
- direction='sideways' returns 400 VALIDATION_ERROR with details referencing the direction field.
- Response body for 200 is a valid instance of QuoteResponse schema as defined in openapi/partner-api.yaml (schemathesis assertion or manual JSON-schema validation).
- No internal fields (m_a, m_b, cost_rate_pay, cost_rate_coll) appear anywhere in the 200 response body.
**Depends on:** 15.5-T02

### 15.5-T04 — Contract test: POST /v1/rates response schema - USD pool fields (cross-border)  _(45 min)_
**Context:** For cross-border quotes (direction=inbound with OVERSEAS partner), QuoteResponse must include USD pool breakdown: collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, cross_rate. These are omitted for domestic (same-currency). Test partner P-TEST-002 (OVERSEAS, m_a=1.5%, m_b=1.0%, service_charge=0.35 USD, treasury.usd_krw=1350.00). Expected: target_payout=13500 KRW -> payout_usd_cost=10.0000, collection_usd=10.2564 (tol 0.01), collection_margin_usd=0.1538, payout_margin_usd=0.1026. For domestic partner P-TEST-001 (LOCAL KRW), these fields must be absent.
**Steps:** POST /v1/rates for P-TEST-002 with target_payout='13500', payout_currency='KRW', direction='inbound'. Parse response and assert USD pool fields present and numerically correct to within 0.01.; Assert pool identity: abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) <= 0.01.; POST /v1/rates for P-TEST-001 (LOCAL domestic) and assert that collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd are all absent from the 200 response.; Assert cross_rate field is present in cross-border response and equals target_payout / send_amount within 0.001 tolerance.; Assert offer_rate is the rate partners use to compute collection_amount (send_amount / (collection_usd - collection_margin_usd) pattern).
**Deliverable:** Test file tests/contract/test_rates_usd_pool.py with cross-border and domestic field-presence assertions.
**Acceptance / logic checks:**
- Cross-border response: collection_usd=10.26 (+/-0.01), payout_usd_cost=10.00 (+/-0.01), pool identity delta <= 0.01 USD.
- Domestic response: collection_usd key absent from JSON body; collection_amount = 13500 + service_charge_in_KRW.
- cross_rate value in cross-border response equals target_payout / send_amount to within 0.001.
- offer_rate is a decimal string, not null, non-zero for both partner types.
- No m_a, m_b, cost_rate_pay, cost_rate_coll fields appear in any response variant.
**Depends on:** 15.5-T03

### 15.5-T05 — Contract test: POST /v1/rates - rate quote TTL and valid_until field  _(40 min)_
**Context:** Rate quote TTL: default 60 s when an aggregator (Partner B) is involved; 300 s otherwise. Configurable 60-1800 s. The response field valid_until = quote_issued_at + TTL (ISO-8601 UTC). Error RATE_QUOTE_EXPIRED is returned if POST /v1/payments is called after valid_until. Error code is in the API error catalog.
**Steps:** POST /v1/rates and capture response; assert valid_until is present and is a datetime at least 59 seconds in the future (allowing for clock skew).; Record quote_id and current timestamp; compute expected valid_until = now + TTL (use 300 s as default for non-Partner-B test partner P-TEST-002); assert valid_until is within 5 seconds of expected.; Wait until valid_until has passed (or set TTL=0 via test fixture to force immediate expiry), then POST /v1/payments with the expired quote_id; assert 422 response with error.code='RATE_QUOTE_EXPIRED'.; Assert error response body has error.code, error.message (non-empty), error.request_id present.; Assert RATE_QUOTE_EXPIRED response details array contains a field entry referencing quote_id with the expiry timestamp.
**Deliverable:** Test file tests/contract/test_rates_ttl.py covering TTL assertion and expired-quote rejection.
**Acceptance / logic checks:**
- valid_until in QuoteResponse is a valid ISO-8601 UTC datetime at least 59 s after response receipt.
- POST /v1/payments with an expired quote_id returns HTTP 422 with error.code='RATE_QUOTE_EXPIRED'.
- Error response body is a valid instance of the Error schema (has error.code, error.message, error.request_id).
- details[0].field='quote_id' in the expired-quote error response.
- A fresh quote used within TTL proceeds to 201 or 202 (no expiry error).
**Depends on:** 15.5-T03

### 15.5-T06 — Contract test: POST /v1/payments request schema and happy-path response  _(55 min)_
**Context:** POST /v1/payments (operationId: createPayment) requires: quote_id, merchant_qr, direction, scheme_id, customer_ref, partner_txn_ref, collection_amount (decimal string), collection_currency. Idempotency-Key header required. Returns 201 Payment (synchronous approval) or 202 (pending). Payment schema has: payment_id, status (enum: pending/approved/failed/cancelled/uncertain), scheme_txn_id, offer_rate, collection_amount, prefund_deducted_usd (OVERSEAS only). Test with P-TEST-002 against M-TEST-0001 (active merchant QR-TEST-0001).
**Steps:** Get a fresh quote for P-TEST-002, capture quote_id. POST /v1/payments with all required fields and a fresh UUID Idempotency-Key. Assert 201 or 202 response.; Validate response body against Payment schema: payment_id present (pattern pay_[A-Za-z0-9]+), status in enum, offer_rate non-null, prefund_deducted_usd present (OVERSEAS partner).; Submit the same request again with the SAME Idempotency-Key; assert identical payment_id and status returned, no new transaction created (HTTP 200 or 201 with same body).; Submit a request omitting each required field in turn (quote_id, merchant_qr, collection_amount, collection_currency, customer_ref, partner_txn_ref); assert each returns 400 VALIDATION_ERROR with the missing field named.; Submit with direction mismatch vs quote direction; assert 422 with appropriate error code.
**Deliverable:** Test file tests/contract/test_payments_schema.py covering happy-path, field validation, and idempotency key replay.
**Acceptance / logic checks:**
- Happy-path returns 201 or 202; response payment_id matches 'pay_[A-Za-z0-9]+'; status is one of [pending, approved, failed, cancelled, uncertain].
- Idempotency key replay returns the same payment_id as the first call; no duplicate Payment row exists in DB.
- Each missing required field produces 400 VALIDATION_ERROR with details[].field naming the missing attribute.
- prefund_deducted_usd is present and non-null in response for OVERSEAS partner P-TEST-002; absent for LOCAL partner P-TEST-001.
- Response body validates against Payment JSON schema in openapi/partner-api.yaml with zero schemathesis violations.
**Depends on:** 15.5-T04, 15.5-T05

### 15.5-T07 — Contract test: POST /v1/payments - INSUFFICIENT_PREFUNDING error code  _(35 min)_
**Context:** OVERSEAS partner P-TEST-002 with balance state Depleted (USD 0.00 per QA-12 §3.4). Any payment attempt must be rejected before calling the scheme. Expected: HTTP 402 with error.code='INSUFFICIENT_PREFUNDING'. The scheme must never be called; prefunding balance must remain unchanged at 0.00.
**Steps:** Seed P-TEST-002 prefunding balance to 0.00 in the test DB (or use the Depleted fixture from QA-12 §3.4).; Get a valid rate quote for P-TEST-002 for target_payout='13500' KRW inbound.; POST /v1/payments with the quote_id; assert HTTP 402 response.; Assert error.code='INSUFFICIENT_PREFUNDING' in response body; assert error.message is non-empty; assert error.request_id is present.; Verify (via GET /v1/balance or DB query) that prefunding balance is still 0.00 - no deduction occurred.
**Deliverable:** Test file tests/contract/test_payments_insufficient_prefunding.py.
**Acceptance / logic checks:**
- HTTP 402 returned for payment attempt with zero-balance partner P-TEST-002.
- error.code exactly equals 'INSUFFICIENT_PREFUNDING' (string comparison).
- Balance query after rejection shows balance_usd='0.00' unchanged.
- No scheme (ZeroPay) call is recorded in the audit log or mock scheme call log for this payment_id.
- Error response body validates against Error schema in partner-api.yaml.
**Depends on:** 15.5-T06

### 15.5-T08 — Contract test: POST /v1/payments - MERCHANT_INACTIVE and QR_DEACTIVATED error codes  _(35 min)_
**Context:** Merchant M-TEST-0002 (TestMart Inactive) has status=inactive; any payment to QR-TEST-0002 must return MERCHANT_INACTIVE. Merchant M-TEST-0005 (TestDeact QR) is active but QR-TEST-0005 is deactivated; any payment must return QR_DEACTIVATED. Both errors must be returned before scheme call.
**Steps:** Get a fresh quote for P-TEST-002. POST /v1/payments with merchant_qr='QR-TEST-0002' (inactive merchant M-TEST-0002). Assert HTTP 422 and error.code='MERCHANT_INACTIVE'.; Get a fresh quote for P-TEST-002. POST /v1/payments with merchant_qr='QR-TEST-0005' (deactivated QR on active merchant M-TEST-0005). Assert HTTP 422 and error.code='QR_DEACTIVATED'.; Assert error.message is non-empty and error.request_id is present in both cases.; Assert neither payment_id is created in the DB (GET /v1/payments/{fabricated-id} returns 404, or DB check shows no row).; Assert no prefunding deduction occurred (GET /v1/balance balance_usd unchanged for P-TEST-002).
**Deliverable:** Test file tests/contract/test_payments_merchant_errors.py.
**Acceptance / logic checks:**
- QR-TEST-0002 payment returns 422 with error.code='MERCHANT_INACTIVE'.
- QR-TEST-0005 payment returns 422 with error.code='QR_DEACTIVATED'.
- prefunding balance for P-TEST-002 unchanged after both rejected calls.
- Both error responses validate against Error schema in partner-api.yaml.
- Neither error triggers a scheme network call (verified via mock or stub call log).
**Depends on:** 15.5-T06

### 15.5-T09 — Contract test: POST /v1/payments - PARTNER_B_QUOTE_DEVIATION and PARTNER_B_QUOTE_UNAVAILABLE  _(50 min)_
**Context:** Partner B quote deviation: at commit time, if cost_rate_pay from Partner B deviates more than 1.0% from the /rates-time quote, return PARTNER_B_QUOTE_DEVIATION; do NOT commit; no prefunding deduction. RV-06 vector: quote-time rate 1350.00, commit-time 1366.20 (1.2% deviation, exceeds tolerance). PARTNER_B_QUOTE_UNAVAILABLE: Partner B API unreachable at commit time; no fallback. Test with P-TEST-005 (TestPartnerB, OVERSEAS, Partner B quote mode).
**Steps:** Configure the Partner B mock stub to return rate 1366.20 at commit time for P-TEST-005 (1.2% deviation from quoted 1350.00). GET /v1/rates for P-TEST-005, then POST /v1/payments; assert HTTP 422 error.code='PARTNER_B_QUOTE_DEVIATION'.; Assert no prefunding deduction occurred for P-TEST-005 (balance unchanged).; Configure the Partner B mock stub to return HTTP 503 (unavailable). GET /v1/rates for P-TEST-005, then POST /v1/payments; assert HTTP 422 error.code='PARTNER_B_QUOTE_UNAVAILABLE'.; Configure the Partner B mock stub to return rate 1360.80 (0.8% deviation, within 1.0% tolerance per RV-05). POST /v1/payments; assert it succeeds (201 or 202) with recorded cost_rate_pay=1360.80.; Assert both deviation and unavailable error responses validate against Error schema.
**Deliverable:** Test file tests/contract/test_payments_partner_b.py covering deviation, unavailable, and within-tolerance cases.
**Acceptance / logic checks:**
- 1.2% deviation returns 422 PARTNER_B_QUOTE_DEVIATION; no prefunding deduction.
- Partner B unavailable returns 422 PARTNER_B_QUOTE_UNAVAILABLE; no prefunding deduction.
- 0.8% deviation (within tolerance) results in successful payment with recorded cost_rate_pay reflecting commit-time Partner B rate.
- PARTNER_B_QUOTE_DEVIATION response includes error.code='PARTNER_B_QUOTE_DEVIATION' and non-empty message.
- PARTNER_B_QUOTE_UNAVAILABLE response has error.code='PARTNER_B_QUOTE_UNAVAILABLE'; no fallback rate used.
**Depends on:** 15.5-T06

### 15.5-T10 — Contract test: Idempotency-Key header - missing key and duplicate key reuse  _(45 min)_
**Context:** Idempotency-Key header is required on all mutating calls (POST). Missing key must return 400 MISSING_IDEMPOTENCY_KEY. A key reused for a DIFFERENT payment (different partner_txn_ref) must return 409 IDEMPOTENCY_KEY_REUSE. Results are deduplicated for 24 hours within Redis. A key reused for the EXACT same request must return the original response (idempotent replay). Error codes per API error catalog.
**Steps:** POST /v1/payments without the Idempotency-Key header; assert 400 with error.code='MISSING_IDEMPOTENCY_KEY'.; POST /v1/rates without Idempotency-Key header; assert 400 MISSING_IDEMPOTENCY_KEY (required on all mutating calls).; Create a successful payment with Idempotency-Key='test-key-001'. Then POST a new /v1/payments request with the SAME Idempotency-Key='test-key-001' but different partner_txn_ref; assert 409 IDEMPOTENCY_KEY_REUSE.; Create a successful payment with Idempotency-Key='test-key-002'. Replay the EXACT same request body with Idempotency-Key='test-key-002'; assert the same payment_id is returned and HTTP 200 or 201.; Assert the idempotent replay does not create a second row in the payments table (DB count unchanged) and does not deduct prefunding a second time.
**Deliverable:** Test file tests/contract/test_idempotency.py covering missing key, key reuse conflict, and idempotent replay.
**Acceptance / logic checks:**
- Missing Idempotency-Key header returns 400 error.code='MISSING_IDEMPOTENCY_KEY'.
- Reusing a key for a different partner_txn_ref returns 409 error.code='IDEMPOTENCY_KEY_REUSE'.
- Idempotent replay of exact same request returns same payment_id as original call.
- Prefunding balance after idempotent replay is the same as after first call (no double deduction).
- Error responses validate against Error schema; idempotent replay response validates against Payment schema.
**Depends on:** 15.5-T06

### 15.5-T11 — Contract test: GET /v1/payments/{id} - happy path, 404, and cross-partner 403  _(40 min)_
**Context:** GET /v1/payments/{id} returns Payment schema for the authenticated partner's own payment. Returns 404 PAYMENT_NOT_FOUND if ID does not exist. Returns 403 FORBIDDEN if the authenticated partner queries another partner's payment_id (IDOR control: partner_id ownership enforced at query layer per SEC-09).
**Steps:** Create a payment for P-TEST-002; capture payment_id. Authenticate as P-TEST-002 and GET /v1/payments/{payment_id}; assert 200 and response validates against Payment schema.; GET /v1/payments/pay_nonexistent999; assert 404 with error.code='PAYMENT_NOT_FOUND'.; Create a payment for P-TEST-002 (payment_id = X). Authenticate as P-TEST-001 and GET /v1/payments/X; assert 403 with error.code='FORBIDDEN' (cross-partner IDOR test).; Assert 200 response contains payment_id, status, offer_rate, created_at fields; no internal fields (m_a, m_b) present.; Assert X-Request-ID header is present in all responses (200, 404, 403).
**Deliverable:** Test file tests/contract/test_get_payment.py covering happy path, not-found, and cross-partner isolation.
**Acceptance / logic checks:**
- GET own payment_id returns 200 with Payment schema; payment_id in response matches queried ID.
- GET non-existent ID returns 404 error.code='PAYMENT_NOT_FOUND'.
- GET another partner's payment_id returns 403 error.code='FORBIDDEN'.
- No m_a, m_b, cost_rate_pay, cost_rate_coll in 200 response body.
- X-Request-ID header present in every response.
**Depends on:** 15.5-T06

### 15.5-T12 — Contract test: POST /v1/payments/{id}/cancel - same-day and post-settlement rejection  _(45 min)_
**Context:** POST /v1/payments/{id}/cancel cancels same-day (KST calendar day) payments. Post-settlement cancel returns 422 CANCEL_NOT_PERMITTED. Cancel response: {payment_id, status:'cancelled', cancelled_at, prefund_returned_usd (OVERSEAS)}. Reason field required: enum [customer_request, merchant_request, timeout, other]. CancelRequest body required.
**Steps:** Create a payment for P-TEST-002; POST /v1/payments/{id}/cancel with body {reason:'customer_request'}; assert 200 response with status='cancelled' and cancelled_at present.; Assert prefund_returned_usd in cancel response equals the prefund_deducted_usd from the original payment (balance restored).; Attempt to cancel the same payment again; assert 409 CANCEL_NOT_PERMITTED (already cancelled).; Create a payment and mark it as post-settlement (via test fixture or by using an old payment_id from a prior day); POST cancel; assert 422 error.code='CANCEL_NOT_PERMITTED'.; POST cancel with missing required reason field; assert 400 VALIDATION_ERROR.
**Deliverable:** Test file tests/contract/test_cancel.py covering successful cancel, duplicate cancel, post-settlement rejection, and bad body.
**Acceptance / logic checks:**
- Successful cancel returns 200 with status='cancelled', cancelled_at is a valid ISO-8601 datetime.
- prefund_returned_usd equals prefund_deducted_usd from the original payment for OVERSEAS partner.
- Cancelling an already-cancelled payment returns 409 CANCEL_NOT_PERMITTED.
- Post-settlement cancel attempt returns 422 CANCEL_NOT_PERMITTED.
- Cancel with missing reason field returns 400 VALIDATION_ERROR with details naming the reason field.
**Depends on:** 15.5-T06

### 15.5-T13 — Contract test: POST /v1/payments/cpm/generate request/response schema  _(45 min)_
**Context:** POST /v1/payments/cpm/generate (operationId: generateCpmToken) required fields: scheme_id, direction, customer_ref, partner_txn_ref, country_code. Optional: prefund_reserve_usd. Returns CpmToken: cpm_token_id, prepare_token, qr_content, expires_at (60 s), prefund_reserved_usd (OVERSEAS), payment_id. For OVERSEAS (P-TEST-002), prefunding is atomically reserved at token issuance. If balance is 0, returns 402 INSUFFICIENT_PREFUNDING.
**Steps:** POST /v1/payments/cpm/generate for P-TEST-002 with all required fields; assert 201 and response validates against CpmToken schema.; Assert cpm_token_id present (pattern cpm_[A-Za-z0-9]+), prepare_token non-empty, expires_at is ~60 s in future, prefund_reserved_usd present for OVERSEAS partner.; POST with missing required fields (omit scheme_id; omit customer_ref); assert each returns 400 VALIDATION_ERROR with field name in details.; Seed P-TEST-002 balance to 0.00; POST /v1/payments/cpm/generate; assert 402 INSUFFICIENT_PREFUNDING; confirm balance still 0.00 (no deduction).; POST for LOCAL partner P-TEST-001; assert 201 with no prefund_reserved_usd field (not applicable).
**Deliverable:** Test file tests/contract/test_cpm_generate.py covering schema, field validation, prefund deduction, and zero-balance rejection.
**Acceptance / logic checks:**
- 201 response validates against CpmToken schema; cpm_token_id matches 'cpm_[A-Za-z0-9]+'; expires_at is within 65 s of request time.
- prefund_reserved_usd present and non-null for OVERSEAS P-TEST-002; absent for LOCAL P-TEST-001.
- Missing required field returns 400 VALIDATION_ERROR; details[].field names the missing attribute.
- Zero-balance P-TEST-002 returns 402 INSUFFICIENT_PREFUNDING; balance remains 0.00.
- payment_id field in CpmToken response is present and matches pattern 'pay_[A-Za-z0-9]+'.
**Depends on:** 15.5-T06

### 15.5-T14 — Contract test: GET /v1/merchants/{qr} - active merchant, inactive, and not-found  _(35 min)_
**Context:** GET /v1/merchants/{qr} resolves a QR code string against the local merchant DB (populated by ZeroPay sync). Returns Merchant schema: merchant_id, merchant_name, merchant_type, scheme_id, qr_code, status (enum: active/inactive), payout_currency, address. Returns 404 if QR not found. Returns 422 MERCHANT_INACTIVE (or 404) if merchant is inactive. Test with QR-TEST-0001 (active), QR-TEST-0002 (inactive), and QR-NONEXISTENT-999 (not in DB).
**Steps:** GET /v1/merchants/QR-TEST-0001; assert 200 and response validates against Merchant schema; assert status='active', payout_currency='KRW'.; GET /v1/merchants/QR-TEST-0002 (inactive merchant M-TEST-0002); assert 404 or 422 per spec; assert error response validates against Error schema.; GET /v1/merchants/QR-NONEXISTENT-999; assert 404 with error.code='MERCHANT_NOT_FOUND'.; GET /v1/merchants/QR-TEST-0005 (deactivated QR on active merchant); assert 422 or specific error indicating QR deactivation.; Assert 200 response includes X-Request-ID header and no internal pricing fields.
**Deliverable:** Test file tests/contract/test_merchants.py covering active lookup, inactive merchant, deactivated QR, and not-found.
**Acceptance / logic checks:**
- GET active QR returns 200 with status='active', merchant_name non-empty, payout_currency='KRW'.
- GET inactive merchant QR returns non-200 error response that validates against Error schema.
- GET non-existent QR returns 404 error.code='MERCHANT_NOT_FOUND'.
- GET deactivated QR returns error response distinguishable from inactive merchant.
- X-Request-ID header present in all responses including errors.
**Depends on:** 15.5-T02

### 15.5-T15 — Contract test: GET /v1/balance - OVERSEAS balance fields and LOCAL partner 403  _(35 min)_
**Context:** GET /v1/balance returns Balance schema for OVERSEAS partners: partner_id, balance_usd (decimal string), low_balance_threshold_usd, is_below_threshold (bool), as_of (datetime). Optional query param include_history=true returns recent_deductions array (DeductionEvent[]). LOCAL partners receive 403 FORBIDDEN. Test with P-TEST-002 (OVERSEAS, Normal balance 50000.00) and P-TEST-001 (LOCAL).
**Steps:** GET /v1/balance as P-TEST-002; assert 200 and response validates against Balance schema; assert balance_usd='50000.00', is_below_threshold=false.; GET /v1/balance?include_history=true as P-TEST-002; assert recent_deductions is an array; each element validates against DeductionEvent schema (payment_id, amount_usd, balance_after_usd, event_at).; GET /v1/balance as P-TEST-001 (LOCAL partner); assert 403 with error.code='FORBIDDEN'.; Seed P-TEST-002 to Low balance state (9500.00 USD, below threshold 10000.00). GET /v1/balance; assert is_below_threshold=true, balance_usd='9500.00'.; Assert X-Request-ID header present in all responses.
**Deliverable:** Test file tests/contract/test_balance.py covering OVERSEAS happy path, history, LOCAL 403, and below-threshold flag.
**Acceptance / logic checks:**
- OVERSEAS partner GET /v1/balance returns 200 with balance_usd as a decimal string, as_of is ISO-8601 datetime, partner_id matches authenticated partner.
- include_history=true returns recent_deductions array; each DeductionEvent has payment_id, amount_usd, balance_after_usd, event_at.
- LOCAL partner returns 403 error.code='FORBIDDEN'.
- Below-threshold partner returns is_below_threshold=true with balance_usd below low_balance_threshold_usd.
- Balance schema response validates against Balance schema in openapi/partner-api.yaml.
**Depends on:** 15.5-T02

### 15.5-T16 — Contract test: authentication error codes - INVALID_CREDENTIALS and INVALID_SIGNATURE  _(35 min)_
**Context:** POST /v1/auth/token (or equivalent auth flow) with invalid API key must return 401 INVALID_CREDENTIALS. A request with a valid API key but wrong HMAC signature must return 401 INVALID_SIGNATURE. A request with a stale X-Timestamp (more than 300 s old) must return 401 TIMESTAMP_OUT_OF_RANGE. Error code INVALID_API_KEY for unknown key. All 401 responses must use Error schema.
**Steps:** POST any protected endpoint (e.g. GET /v1/balance) with a completely fabricated X-API-Key='fake-key-99999'; assert 401 with error.code='INVALID_API_KEY'.; POST with a valid X-API-Key but a wrong X-Signature (tamper the last character); assert 401 with error.code='INVALID_SIGNATURE'.; POST with valid key and valid signature but X-Timestamp set to current_time - 301 s; assert 401 with error.code='TIMESTAMP_OUT_OF_RANGE'.; POST with no X-API-Key header at all; assert 401 error (any auth error code).; Assert all 401 responses validate against Error schema with non-empty error.message.
**Deliverable:** Test file tests/contract/test_auth_errors.py covering INVALID_API_KEY, INVALID_SIGNATURE, TIMESTAMP_OUT_OF_RANGE.
**Acceptance / logic checks:**
- Unknown API key returns 401 error.code='INVALID_API_KEY'.
- Valid key + wrong signature returns 401 error.code='INVALID_SIGNATURE'.
- Valid key + signature + timestamp 301 s old returns 401 error.code='TIMESTAMP_OUT_OF_RANGE'.
- All 401 responses validate against Error schema (error.code, error.message, error.request_id present).
- No stack trace or internal server detail leaked in any 401 response body.
**Depends on:** 15.5-T02

### 15.5-T17 — Contract test: NO_SCHEME_FOR_LOCATION and DIRECTION_NOT_ENABLED error codes  _(30 min)_
**Context:** NO_SCHEME_FOR_LOCATION is returned when no active QR scheme is configured for the requested country_code. DIRECTION_NOT_ENABLED is returned when the partner-scheme rule does not have the requested direction enabled (e.g. a partner tries Outbound when only Inbound is configured). Both must appear in the error catalog. Test via POST /v1/rates with unsupported direction or unknown country.
**Steps:** POST /v1/rates with country_code='XX' (no scheme configured for country XX); assert 422 with error.code='NO_SCHEME_FOR_LOCATION'.; POST /v1/rates with direction='outbound' for P-TEST-002 (only inbound enabled per test rule); assert 422 with error.code='DIRECTION_NOT_ENABLED'.; Assert both error responses validate against Error schema with error.code, error.message, error.request_id.; Assert that for NO_SCHEME_FOR_LOCATION the details array references country_code or scheme_id.; Assert HTTP status code is 422 for both semantic errors (not 400).
**Deliverable:** Test file tests/contract/test_direction_scheme_errors.py.
**Acceptance / logic checks:**
- Unknown country_code='XX' returns 422 error.code='NO_SCHEME_FOR_LOCATION'.
- Disabled direction returns 422 error.code='DIRECTION_NOT_ENABLED'.
- Both error responses validate against Error schema.
- HTTP status is 422 for both (semantic/business error, not validation error).
- error.message is non-empty and human-readable for both codes.
**Depends on:** 15.5-T03

### 15.5-T18 — Implement webhook mock listener for contract tests  _(55 min)_
**Context:** Webhook events are delivered by GMEPay+ to the partner's registered webhook_url via POST (Content-Type: application/json). The partner must respond HTTP 2xx within 10 s. Four event types: payment.approved, payment.pending_debit, payment.failed, payment.cancelled. Every event carries: X-GME-Webhook-Signature: sha256=HMAC-SHA256(body, webhook_signing_secret), X-GME-Webhook-Timestamp, X-GME-Event-ID. Contract tests need a local listener to receive and inspect webhooks.
**Steps:** Implement a lightweight HTTP mock listener (e.g. Python Flask, WireMock, or ngrok+local server) that receives POST requests and stores them in memory indexed by event_id.; Expose a get_received_events(event_type) helper that returns the list of received WebhookEvent objects matching a type.; Implement signature verification: extract body bytes, compute HMAC-SHA256 with the test webhook_signing_secret, compare to X-GME-Webhook-Signature header value; reject mismatches.; Register the listener URL as the webhook_url for P-TEST-002 in the sandbox test DB before contract tests run.; Add a reset_events() helper to clear received events between tests.
**Deliverable:** tests/contract/webhook_listener.py (or equivalent) with get_received_events(), reset_events(), and signature verification.
**Acceptance / logic checks:**
- Listener starts on a configurable port; returns HTTP 200 immediately to any incoming POST.
- get_received_events('payment.approved') returns only events with event_type='payment.approved'.
- Signature verification correctly rejects a tampered body (bit-flipped body should not match HMAC).
- Listener stores event_id, event_type, created_at, partner_id, data from the JSON envelope.
- reset_events() clears all stored events so tests are isolated.
**Depends on:** 15.5-T01

### 15.5-T19 — Contract test: webhook payment.approved event schema validation  _(45 min)_
**Context:** payment.approved event is delivered after scheme confirms MPM payment. Required envelope fields: event_id, event_type='payment.approved', created_at (ISO-8601), partner_id. Data payload required fields: payment_id, partner_txn_ref, scheme_txn_id, merchant_id, merchant_name, direction, scheme_id, target_payout, payout_currency, offer_rate, collection_amount, collection_currency, service_charge, service_charge_currency, prefund_deducted_usd (OVERSEAS), approved_at. No internal fields (m_a, m_b, cost rates) in payload.
**Steps:** Trigger an MPM payment for P-TEST-002 and M-TEST-0001 via POST /v1/payments. Wait for webhook listener to receive payment.approved event (poll up to 30 s per SLA).; Assert envelope has event_id (globally unique string), event_type='payment.approved', created_at (ISO-8601 UTC), partner_id='prt_p_test_002'.; Assert data payload has payment_id, partner_txn_ref, scheme_txn_id, direction='inbound', scheme_id='zeropay', target_payout (non-zero), offer_rate (non-zero decimal string), approved_at (ISO-8601).; Assert prefund_deducted_usd present in data for OVERSEAS partner; value is a positive decimal string.; Assert m_a, m_b, cost_rate_pay, cost_rate_coll absent from data payload.
**Deliverable:** Test file tests/contract/test_webhook_approved.py using the mock listener from 15.5-T18.
**Acceptance / logic checks:**
- payment.approved event received within 30 s of payment POST.
- Envelope fields: event_id non-empty, event_type='payment.approved', partner_id matches test partner.
- data.payment_id, data.offer_rate, data.approved_at all present and non-null.
- data.prefund_deducted_usd present for OVERSEAS partner and equals prefund_deducted_usd from POST /v1/payments response.
- No m_a, m_b, or cost rate fields anywhere in the webhook payload.
**Depends on:** 15.5-T18, 15.5-T06

### 15.5-T20 — Contract test: webhook payment.pending_debit event schema (CPM flow)  _(45 min)_
**Context:** payment.pending_debit is delivered in CPM flow after merchant scans QR and scheme relays payment, before final approval. Required data fields: payment_id, cpm_token_id, partner_txn_ref, merchant_id, merchant_name, target_payout, payout_currency, offer_rate, estimated_collection_amount, collection_currency, service_charge, service_charge_currency. This event allows the partner app to show the debit amount to the customer before confirmation. No prefund_deducted_usd (not yet final).
**Steps:** Trigger a CPM generate for P-TEST-002 via POST /v1/payments/cpm/generate; receive cpm_token_id and payment_id.; Simulate the scheme scanning the QR (via sandbox trigger or test hook); wait for payment.pending_debit webhook on the mock listener (poll up to 30 s).; Assert envelope: event_type='payment.pending_debit', partner_id correct, event_id unique.; Assert data payload has cpm_token_id matching the CPM generate response, offer_rate non-zero, estimated_collection_amount non-zero, target_payout non-zero.; Assert prefund_deducted_usd is NOT in the pending_debit payload (deduction has already occurred at generate time but final confirmation not yet done).
**Deliverable:** Test file tests/contract/test_webhook_pending_debit.py.
**Acceptance / logic checks:**
- payment.pending_debit received after CPM generate and scheme scan simulation; event_type='payment.pending_debit'.
- data.cpm_token_id matches cpm_token_id from POST /v1/payments/cpm/generate response.
- data.offer_rate and data.estimated_collection_amount are non-zero decimal strings.
- data.target_payout and data.payout_currency match the merchant-input amount from the scheme scan.
- No prefund_deducted_usd or m_a/m_b/cost_rate fields in the payload.
**Depends on:** 15.5-T18, 15.5-T13

### 15.5-T21 — Contract test: webhook payment.failed event schema and prefund restoration  _(45 min)_
**Context:** payment.failed is delivered on terminal payment failure (scheme decline, timeout, internal error). Required data: payment_id, partner_txn_ref, failure_code (e.g. SCHEME_UNAVAILABLE), failure_message, prefund_returned_usd (OVERSEAS; amount returned to balance), failed_at. After payment.failed, the prefunding balance for OVERSEAS partner must be restored by the prefund_deducted_usd amount.
**Steps:** Configure sandbox to force a scheme failure for a specific merchant QR or use a designated failure test merchant. Trigger POST /v1/payments for P-TEST-002.; Wait for payment.failed webhook on mock listener (poll up to 30 s).; Assert envelope: event_type='payment.failed', event_id unique, partner_id correct.; Assert data has payment_id, failure_code (non-empty string), failure_message, prefund_returned_usd (positive decimal), failed_at (ISO-8601).; GET /v1/balance for P-TEST-002 and assert balance_usd has been restored by the prefund_returned_usd amount (balance_after = balance_before - original_deduction + prefund_returned_usd).
**Deliverable:** Test file tests/contract/test_webhook_failed.py.
**Acceptance / logic checks:**
- payment.failed webhook received within 30 s; event_type='payment.failed'.
- data.failure_code is a non-empty string; data.prefund_returned_usd is a positive decimal.
- data.failed_at is a valid ISO-8601 datetime.
- GET /v1/balance after payment.failed shows balance increased by exactly prefund_returned_usd.
- Error response schema of the event validates against WebhookEvent envelope schema in partner-api.yaml.
**Depends on:** 15.5-T18, 15.5-T06

### 15.5-T22 — Contract test: webhook payment.cancelled event schema  _(40 min)_
**Context:** payment.cancelled is delivered when a payment is successfully cancelled via POST /v1/payments/{id}/cancel. Required data: payment_id, partner_txn_ref, reason (enum), prefund_returned_usd (OVERSEAS), cancelled_at. The event must be delivered after the cancel API call succeeds; payload must match the cancel response data.
**Steps:** Create a payment for P-TEST-002, capture payment_id and prefund_deducted_usd. POST /v1/payments/{id}/cancel with reason='customer_request'.; Wait for payment.cancelled webhook on mock listener (poll up to 30 s).; Assert envelope: event_type='payment.cancelled', event_id unique, partner_id correct.; Assert data payload: payment_id matches, partner_txn_ref matches original, reason='customer_request', prefund_returned_usd matches prefund_deducted_usd, cancelled_at is ISO-8601 datetime.; Assert GET /v1/balance shows balance restored by prefund_returned_usd after event received.
**Deliverable:** Test file tests/contract/test_webhook_cancelled.py.
**Acceptance / logic checks:**
- payment.cancelled webhook received within 30 s of cancel API call.
- data.payment_id matches the cancelled payment_id.
- data.reason='customer_request'; data.prefund_returned_usd is a positive decimal equal to the original deduction.
- data.cancelled_at is a valid ISO-8601 datetime.
- Balance restored for P-TEST-002 after cancel and webhook receipt.
**Depends on:** 15.5-T18, 15.5-T12

### 15.5-T23 — Contract test: webhook delivery retry on non-2xx partner response  _(55 min)_
**Context:** If partner webhook returns non-2xx, GMEPay+ retries with exponential back-off: attempt 1 immediate, 2 after 30 s, 3 after 2 min, 4 after 10 min, 5 after 30 min (up to 10 attempts total ~6 h). Retry must send identical payload (idempotent). QA-12 §7.3: mock partner webhook returning 500 for first 3 attempts; verify delivery on attempt 4; verify payload identical on retry.
**Steps:** Configure the mock webhook listener to return HTTP 500 for the first 3 delivery attempts for event_type='payment.approved', then return HTTP 200 on the 4th attempt.; Trigger an MPM payment for P-TEST-002; wait for eventual successful delivery (allow up to 15 min for attempts 1-4 in test with accelerated backoff, or use a test mode that compresses delays).; Assert that the listener received exactly 4 POST requests for the same event_id.; Assert that all 4 POST request bodies are byte-identical (same JSON payload, same event_id, same data).; Assert that X-GME-Webhook-Signature is present and valid on each retry attempt; the signature must match the body on every attempt.
**Deliverable:** Test file tests/contract/test_webhook_retry.py using a configurable-response mock listener.
**Acceptance / logic checks:**
- Exactly 4 delivery attempts recorded for the same event_id (3 failures + 1 success).
- All 4 request bodies are identical (same event_id, event_type, created_at, partner_id, data).
- X-GME-Webhook-Signature is present and correctly computes against the body on every retry attempt.
- Successful delivery on attempt 4 marks the event as delivered (no further retries).
- Mock listener returns event_id from the successful delivery matching the original payment_id.
**Depends on:** 15.5-T18, 15.5-T19

### 15.5-T24 — Contract test: webhook signature verification - tampered body rejection  _(40 min)_
**Context:** Every webhook includes X-GME-Webhook-Signature: sha256=HMAC-SHA256(body, webhook_signing_secret). Partners must reject events with invalid signatures. The webhook_signing_secret is distinct from the API secret. Replay protection: reject events where X-GME-Webhook-Timestamp is more than 5 minutes old. Tests verify the Hub emits correct signatures and that old timestamps are detectable.
**Steps:** Receive a genuine payment.approved webhook via mock listener. Extract body, X-GME-Webhook-Signature, and X-GME-Webhook-Timestamp.; Verify the received signature: compute HMAC-SHA256(body_bytes, webhook_signing_secret) and assert it equals the sha256= value in the header.; Simulate tampered body: take the received body, flip one byte, recompute expected HMAC - assert it does NOT match the original X-GME-Webhook-Signature.; Assert X-GME-Webhook-Timestamp is within 5 minutes of actual delivery time (replay protection window).; Assert X-GME-Event-ID header is present and globally unique across multiple webhook deliveries.
**Deliverable:** Test file tests/contract/test_webhook_signature.py validating Hub-emitted signatures are correct and timestamps fresh.
**Acceptance / logic checks:**
- HMAC-SHA256 of received body with known webhook_signing_secret equals X-GME-Webhook-Signature value (minus 'sha256=' prefix).
- One-byte modification to body produces a signature that does NOT match the original header.
- X-GME-Webhook-Timestamp is within 300 s of event receipt time.
- X-GME-Event-ID is a unique string per event; two different events have different X-GME-Event-ID values.
- Signature algorithm prefix is exactly 'sha256=' (lowercase).
**Depends on:** 15.5-T18

### 15.5-T25 — Contract test: all error codes in catalog triggered at least once  _(55 min)_
**Context:** QA-12 §7.4 requires every error code in the API-05 error catalog to be triggered by at least one contract test. Full enum: VALIDATION_ERROR, MISSING_IDEMPOTENCY_KEY, INVALID_SIGNATURE, INVALID_API_KEY, TIMESTAMP_OUT_OF_RANGE, FORBIDDEN, IP_NOT_ALLOWLISTED, PAYMENT_NOT_FOUND, MERCHANT_NOT_FOUND, SCHEME_NOT_FOUND, IDEMPOTENCY_KEY_REUSE, RATE_QUOTE_EXPIRED, RATE_QUOTE_INVALID, PARTNER_B_QUOTE_DEVIATION, PARTNER_B_QUOTE_UNAVAILABLE, SCHEME_UNAVAILABLE, PAYMENT_MODE_NOT_SUPPORTED, DIRECTION_NOT_ENABLED, CANCEL_NOT_PERMITTED, INSUFFICIENT_PREFUNDING, DUPLICATE_PARTNER_TXN_REF, RATE_LIMITED, INTERNAL_ERROR, SERVICE_UNAVAILABLE.
**Steps:** Create a coverage matrix spreadsheet (or code comment table) listing each of the 24 error codes and the ticket/test that triggers it.; For any error codes not yet covered by T03-T17 tests (e.g. SCHEME_NOT_FOUND, PAYMENT_MODE_NOT_SUPPORTED, DUPLICATE_PARTNER_TXN_REF, RATE_LIMITED), write targeted trigger tests.; DUPLICATE_PARTNER_TXN_REF: POST /v1/payments twice with different Idempotency-Key but same partner_txn_ref; assert 409 DUPLICATE_PARTNER_TXN_REF on second call.; RATE_LIMITED: send >N requests per second (per-partner rate limit); assert 429 RATE_LIMITED with Retry-After header present.; Add a CI assertion that counts unique error codes exercised across the full test suite and fails if count < 24.
**Deliverable:** Test file tests/contract/test_error_code_coverage.py plus coverage matrix; CI gate that verifies all 24 codes exercised.
**Acceptance / logic checks:**
- DUPLICATE_PARTNER_TXN_REF triggered: second POST with same partner_txn_ref but different Idempotency-Key returns 409 DUPLICATE_PARTNER_TXN_REF.
- RATE_LIMITED triggered: burst requests return 429 with Retry-After header containing a positive integer.
- Coverage matrix confirms all 24 error codes from the enum are exercised in at least one test.
- CI assertion fails if any error code in the enum is unexercised.
- 429 response validates against Error schema and includes Retry-After, X-RateLimit-Limit, X-RateLimit-Remaining headers.
**Depends on:** 15.5-T07, 15.5-T08, 15.5-T09, 15.5-T10, 15.5-T11, 15.5-T12, 15.5-T16, 15.5-T17

### 15.5-T26 — Contract test: schemathesis automated fuzz sweep of all endpoints  _(60 min)_
**Context:** QA-12 §2.2.3 requires contract tests validated against openapi/partner-api.yaml using schemathesis (or equivalent). Schemathesis generates property-based tests from the spec, sending valid and boundary-case inputs and asserting responses match declared schemas. Entry criteria: integration tests green; sandbox reachable; spec checked in. Exit criteria: zero schema mismatches reported.
**Steps:** Configure schemathesis to run against the sandbox base URL using openapi/partner-api.yaml with authenticated requests (inject X-API-Key, X-Signature, Idempotency-Key via before_call hook).; Run schemathesis with --checks=all (status_code_conformance, content_type_conformance, response_schema_conformance, not_a_server_error) against all 8 endpoints.; Capture full schemathesis output; fix any schema mismatches found (response fields not in spec, undocumented status codes returned, wrong content type).; Add 'make fuzz' target to CI pipeline that runs schemathesis and fails on any mismatch.; Document all schemathesis findings in a defect log; any P1/P2 findings must be fixed before contract test exit gate.
**Deliverable:** CI target 'make fuzz' running schemathesis with zero schema-mismatch findings; schemathesis run log committed to tests/contract/schemathesis_report.txt.
**Acceptance / logic checks:**
- schemathesis reports zero status_code_conformance violations (server returns only declared HTTP status codes).
- schemathesis reports zero response_schema_conformance violations (all response bodies match declared schemas).
- schemathesis reports zero not_a_server_error violations (no unhandled 500s from valid inputs).
- schemathesis covers all 8 documented endpoints (check schemathesis --show-errors output for skipped paths).
- CI pipeline fails and blocks merge if schemathesis exits non-zero.
**Depends on:** 15.5-T01, 15.5-T02

### 15.5-T27 — Contract test: webhook event ordering and idempotency - out-of-order delivery handling  _(40 min)_
**Context:** QA-12 / API-05 §6.4: events are not guaranteed to arrive in order. Partners must use payment_id + event_type to deduplicate. event_id is globally unique. A re-delivered event (same event_id) must not cause double-processing. Test that the Hub assigns a unique event_id per event and that re-delivery uses the same event_id (not a new one).
**Steps:** Trigger a CPM payment; capture event_ids for payment.pending_debit and payment.approved from the mock listener.; Assert event_ids are distinct between pending_debit and approved events for the same payment_id.; Force a re-delivery (configure mock listener to return 500 on first attempt per T23 approach); capture the event_id on the re-delivered request; assert it is identical to the original event_id.; Trigger two separate payments; assert all four event_ids (2 pending_debit + 2 approved) are globally unique strings.; Assert event_id format is consistent (e.g. 'evt_[A-Za-z0-9]+') per the OpenAPI example.
**Deliverable:** Test file tests/contract/test_webhook_idempotency.py asserting event_id uniqueness and re-delivery invariant.
**Acceptance / logic checks:**
- pending_debit and approved events for the same payment have different event_ids.
- Re-delivered event (after 500 response) has the same event_id as the original delivery attempt.
- event_ids across 4 distinct events are all unique strings.
- event_id format matches 'evt_[A-Za-z0-9]+' pattern.
- Two payments do not share any event_id values.
**Depends on:** 15.5-T23

### 15.5-T28 — Contract test: internal fields not exposed - m_a, m_b, cost rates absent from all responses  _(45 min)_
**Context:** SEC-09 and API-05 §OWASP API3: partner-facing fields must never expose internal fields m_a, m_b, cost_rate_pay, cost_rate_coll, GME margin, or treasury rates. QA-12 §5.3 PP-005 and §8.2 test this explicitly. This test sweeps all API responses and webhook payloads to assert absence of these fields.
**Steps:** For each endpoint (GET /v1/rates response, POST /v1/payments response, GET /v1/payments/{id} response, GET /v1/balance response, GET /v1/merchants/{qr} response), serialize the response body to a flat JSON key set.; Assert that none of the following keys appear at any nesting level: m_a, m_b, cost_rate_pay, cost_rate_coll, collection_margin_usd (this field IS allowed per QuoteResponse schema - confirm which internal fields specifically must be hidden), payout_margin_usd (same check), gme_revenue, treasury_rate.; Collect all webhook payloads from mock listener for payment.approved, payment.pending_debit, payment.failed, payment.cancelled events and run the same key sweep.; Write a utility function flatten_keys(json_obj) that recursively collects all JSON key names; use it in all assertions.; Assert the Partner Portal transaction detail endpoint (if surfaced in API) also passes the sweep.
**Deliverable:** Test file tests/contract/test_field_exposure.py with flatten_keys utility and field-absence assertions across all endpoints and webhooks.
**Acceptance / logic checks:**
- m_a and m_b keys absent from all API response bodies and webhook payloads.
- cost_rate_pay and cost_rate_coll keys absent from all partner-facing responses.
- gme_revenue or similar revenue fields absent from all partner-facing responses.
- treasury_rate or usd_krw or similar treasury rate keys absent from all responses.
- Test covers all 8 API endpoints plus all 4 webhook event types.
**Depends on:** 15.5-T19, 15.5-T20, 15.5-T21, 15.5-T22

### 15.5-T29 — Contract test: DUPLICATE_PARTNER_TXN_REF and idempotency vs duplicate reference distinction  _(35 min)_
**Context:** partner_txn_ref is a unique-per-partner transaction reference. Using the same partner_txn_ref in two different POST /v1/payments calls (with different Idempotency-Keys) must return 409 DUPLICATE_PARTNER_TXN_REF. This is different from idempotency key replay (same Idempotency-Key = idempotent 200/201). These two error paths must be distinguishable.
**Steps:** Create payment P1 with Idempotency-Key='ikey-A', partner_txn_ref='TXN-REF-001'. Assert 201.; Create payment P2 with Idempotency-Key='ikey-B' (different key), partner_txn_ref='TXN-REF-001' (same ref). Assert 409 DUPLICATE_PARTNER_TXN_REF.; Replay payment P1 exactly with Idempotency-Key='ikey-A', partner_txn_ref='TXN-REF-001'. Assert idempotent 200/201 (NOT 409).; Create payment P3 with Idempotency-Key='ikey-C', partner_txn_ref='TXN-REF-002' (different ref). Assert 201 (new unique ref accepted).; Assert that DUPLICATE_PARTNER_TXN_REF response has error.code='DUPLICATE_PARTNER_TXN_REF', HTTP 409, validates against Error schema.
**Deliverable:** Test file tests/contract/test_duplicate_txn_ref.py distinguishing the duplicate-ref 409 from idempotent replay.
**Acceptance / logic checks:**
- Same partner_txn_ref with different Idempotency-Key returns 409 DUPLICATE_PARTNER_TXN_REF.
- Same partner_txn_ref with same Idempotency-Key returns idempotent 200/201 (not 409).
- 409 response error.code='DUPLICATE_PARTNER_TXN_REF'; validates against Error schema.
- New unique partner_txn_ref with new Idempotency-Key creates a new payment (201).
- Only one payment row exists in DB for TXN-REF-001 after all calls.
**Depends on:** 15.5-T10

### 15.5-T30 — Contract test: rate limiting - 429 RATE_LIMITED response with headers  _(40 min)_
**Context:** Per-partner rate limits enforced at API Gateway per API-05 and NFR-10. When exceeded, 429 RATE_LIMITED with headers: Retry-After (seconds), X-RateLimit-Limit (per-partner limit), X-RateLimit-Remaining (remaining in window), X-RateLimit-Reset (epoch reset time). error.code='RATE_LIMITED'. Partners should not be able to starve the system. Test by sending burst requests exceeding the per-partner configured limit.
**Steps:** Configure P-TEST-002 rate limit to a testable low value (e.g. 5 req/s) in sandbox config or use existing rate limit configuration.; Send N+1 requests rapidly (where N is the configured limit); assert that at least one returns 429 RATE_LIMITED.; Assert 429 response has error.code='RATE_LIMITED'; error.message non-empty; X-Request-ID present.; Assert Retry-After header is a positive integer (seconds to wait); X-RateLimit-Limit present; X-RateLimit-Remaining=0 or near zero.; Wait Retry-After seconds, then send a single request; assert it succeeds (not 429).
**Deliverable:** Test file tests/contract/test_rate_limiting.py.
**Acceptance / logic checks:**
- Burst of N+1 requests produces at least one 429 RATE_LIMITED response.
- 429 response has error.code='RATE_LIMITED' and validates against Error schema.
- Retry-After header is a positive integer present in 429 response.
- X-RateLimit-Limit and X-RateLimit-Remaining headers present in 429 response.
- Request after Retry-After delay succeeds without 429.
**Depends on:** 15.5-T16

### 15.5-T31 — Unit test: webhook retry schedule - verify exponential backoff delay sequence  _(35 min)_
**Context:** Webhook retry backoff schedule per API-05 §6.2: attempt 1 = immediate, 2 = 30 s, 3 = 2 min, 4 = 10 min, 5 = 30 min, attempts 6-10 = 1 h each. After 10 failed attempts (~6 h), event marked delivery_failed and GME Ops alerted. The Notification/Webhook service must compute next_retry_at correctly. Unit test the retry scheduler without network calls.
**Steps:** Locate or create the WebhookRetryScheduler class/function that computes next_retry_at given attempt_number and last_attempt_at timestamp.; Write unit tests asserting: attempt 1 -> delay=0 s; attempt 2 -> delay=30 s; attempt 3 -> delay=120 s; attempt 4 -> delay=600 s; attempt 5 -> delay=1800 s; attempt 6-10 -> delay=3600 s each.; Write a test asserting that attempt_number > 10 returns null/terminal state (no more retries) and triggers the OPS alert flag.; Write a test asserting that each next_retry_at = last_attempt_at + delay for the given attempt.; Assert that attempt 1 next_retry_at equals last_attempt_at (immediate = 0 delay).
**Deliverable:** Unit test file tests/unit/test_webhook_retry_scheduler.py with 8+ test cases covering the full delay sequence.
**Acceptance / logic checks:**
- attempt_number=2 produces delay of exactly 30 s.
- attempt_number=3 produces delay of exactly 120 s (2 min).
- attempt_number=4 produces delay of exactly 600 s (10 min).
- attempt_number=5 produces delay of exactly 1800 s (30 min).
- attempt_number=11 returns terminal/null indicating delivery_failed and ops alert required.
**Depends on:** 15.5-T18

### 15.5-T32 — Unit test: webhook signature generation - HMAC-SHA256 with test vectors  _(30 min)_
**Context:** Webhook signatures use HMAC-SHA256(body_bytes, webhook_signing_secret) with prefix 'sha256='. The WebhookDispatcher or SigningService must produce a deterministic signature. The webhook_signing_secret is per-partner, distinct from the API secret, issued at onboarding.
**Steps:** Locate or create the WebhookSigningService.sign(body_bytes, secret) function.; Write a unit test with known inputs: body_bytes=b'{"event_type":"payment.approved"}', secret='test-secret-abc', and assert the output equals 'sha256=' + expected_hmac_hex (pre-compute the expected value with a trusted library).; Write a test asserting that different secrets produce different signatures for the same body.; Write a test asserting that different bodies produce different signatures with the same secret.; Write a test asserting that the prefix is exactly 'sha256=' (lowercase, no space).
**Deliverable:** Unit test file tests/unit/test_webhook_signing.py with at least 5 test cases.
**Acceptance / logic checks:**
- sign(b'{"event_type":"payment.approved"}', 'test-secret-abc') returns 'sha256=' + correct HMAC-SHA256 hex (pre-verified value).
- Different secret produces different signature for same body.
- Different body produces different signature for same secret.
- Output always starts with exactly 'sha256=' prefix.
- Empty body b'' produces valid 'sha256=' + HMAC(b'', secret) not null or error.

### 15.5-T33 — Unit test: QuoteResponse field exposure - no internal fields in serialization  _(30 min)_
**Context:** The rate engine computes internal fields m_a, m_b, cost_rate_pay, cost_rate_coll. The QuoteResponse serializer/DTO must exclude these. The Payment serializer must also exclude them. Unit test the serialization layer directly without HTTP stack.
**Steps:** Locate the QuoteResponse serializer/DTO class (or JSON mapper configuration).; Construct a RateEngineResult with all internal fields populated (m_a=0.015, m_b=0.010, cost_rate_pay=1350.0, cost_rate_coll=3500.0, plus all public fields).; Serialize to JSON and assert the resulting string does not contain any of: 'm_a', 'm_b', 'cost_rate_pay', 'cost_rate_coll'.; Locate the Payment serializer/DTO and run the same assertion using a fully populated Payment domain object.; Locate WebhookEvent serializer and assert the same internal fields absent from serialized output.
**Deliverable:** Unit test file tests/unit/test_serializer_field_exclusion.py covering QuoteResponse, Payment, and WebhookEvent serializers.
**Acceptance / logic checks:**
- QuoteResponse JSON string does not contain 'm_a', 'm_b', 'cost_rate_pay', 'cost_rate_coll'.
- Payment JSON string does not contain 'm_a', 'm_b', 'cost_rate_pay', 'cost_rate_coll'.
- WebhookEvent JSON string does not contain 'm_a', 'm_b', 'cost_rate_pay', 'cost_rate_coll'.
- All public fields (quote_id, offer_rate, send_amount, valid_until for QuoteResponse) are present in serialized output.
- Test fails deterministically if a developer accidentally adds m_a to the DTO.

### 15.5-T34 — Unit test: Error response schema - all required fields always present  _(35 min)_
**Context:** The Error schema requires error.code (from enum), error.message (non-empty string), and error.request_id. The details array is optional but when present each element must have field and issue. Every error-generating code path must produce a valid Error object. Unit test the ErrorResponseFactory or equivalent.
**Steps:** Locate or create ErrorResponseFactory.create(code, message, request_id, details=[]) used by all error handlers.; Write unit tests for each error code in the enum (24 codes); assert each produces an Error object with non-null code matching the input, non-empty message, non-null request_id.; Write a test that adds a details entry; assert details[0].field and details[0].issue are both strings.; Write a test asserting that an unknown error code string (not in enum) is rejected at factory creation time (not silently passed through).; Write a test asserting that error.message is never an internal stack trace (does not contain 'Exception' or 'NullPointer' strings).
**Deliverable:** Unit test file tests/unit/test_error_response_factory.py.
**Acceptance / logic checks:**
- ErrorResponseFactory.create('RATE_QUOTE_EXPIRED', 'Quote expired', 'req_123') produces {error:{code:'RATE_QUOTE_EXPIRED', message:'Quote expired', request_id:'req_123'}}.
- All 24 error codes in the enum can be passed to the factory without exception.
- Factory with unknown code string raises a validation error or maps to INTERNAL_ERROR.
- details entry produces correct field and issue properties.
- request_id is always echoed from input; never null or empty string.

### 15.5-T35 — Contract test: X-Request-ID header present in all responses  _(35 min)_
**Context:** API-05 and the OpenAPI spec require X-Request-ID (globally unique request identifier) to be returned in all response headers including errors. This header is used for support escalation. The OpenAPI spec declares it in components/headers/XRequestID and references it in every response's headers section.
**Steps:** For each of the 8 API endpoints, send a valid request and assert the X-Request-ID response header is present and non-empty.; For at least one 4xx error response per endpoint, assert X-Request-ID is present in the error response headers too.; Assert X-Request-ID values are unique across multiple requests (send 10 requests and verify no duplicate X-Request-ID values).; Assert X-Request-ID in the error response body (error.request_id) matches the X-Request-ID response header value exactly.; Assert X-Request-ID format is consistent (e.g. 'req_[A-Za-z0-9]+' per OpenAPI example).
**Deliverable:** Test file tests/contract/test_request_id_header.py.
**Acceptance / logic checks:**
- X-Request-ID header present in all 200/201/202 responses across all 8 endpoints.
- X-Request-ID header present in at least one 4xx error response per endpoint.
- 10 consecutive requests produce 10 unique X-Request-ID values.
- error.request_id in error body matches X-Request-ID header value.
- X-Request-ID format matches 'req_[A-Za-z0-9]+' or equivalent non-empty string pattern.
**Depends on:** 15.5-T03

### 15.5-T36 — CI integration: wire contract-test suite into pipeline with entry/exit gate enforcement  _(45 min)_
**Context:** QA-12 §2.2.3 entry criteria: integration tests green; sandbox environment reachable; OpenAPI spec checked in. Exit criteria: zero schema mismatches; all documented error codes exercised; idempotent replay test passes. The CI pipeline must enforce these gates on every PR and on nightly full runs. The contract-test job should block merges to main if any test fails.
**Steps:** Add a CI job 'contract-tests' in the pipeline config (e.g. .github/workflows/contract.yml or Jenkinsfile) that: (1) starts the sandbox or points to a shared sandbox env, (2) runs all test files under tests/contract/ using the test runner, (3) runs schemathesis fuzz sweep, (4) reports results.; Configure the job to require SANDBOX_BASE_URL, PARTNER_API_KEY_P_TEST_002, PARTNER_API_SECRET_P_TEST_002, WEBHOOK_SIGNING_SECRET as environment secrets.; Add a post-run assertion: count unique error codes exercised; fail if < 24.; Configure the job as a required status check on PR merges to main/release branches.; Add a nightly trigger that runs the full suite including retry tests (with real delay support) and sends results to the team alert channel.
**Deliverable:** CI pipeline config file with contract-tests job; job is a required merge gate; nightly schedule configured.
**Acceptance / logic checks:**
- CI job 'contract-tests' runs on every PR and blocks merge if any test fails.
- Nightly run includes the retry/backoff tests and produces a pass/fail notification.
- Error code coverage gate fails CI if fewer than 24 distinct error codes are triggered.
- Job reads credentials from environment secrets (not hardcoded).
- schemathesis zero-mismatch check is part of the required contract-test job.
**Depends on:** 15.5-T25, 15.5-T26

### 15.5-T37 — Docs: contract-test README with setup, run instructions, and coverage table  _(30 min)_
**Context:** QA-12 §12.1 deliverable: Contract test suite (API-05 coverage) owned by QA engineer, due before E2E entry. A zero-context developer must be able to set up and run the suite from the README alone. The README should document all environment variables, the error code coverage table, and how to interpret schemathesis output.
**Steps:** Create tests/contract/README.md documenting: prerequisites (Python version or JVM version, schemathesis version), environment variables required (SANDBOX_BASE_URL, partner credentials, WEBHOOK_SIGNING_SECRET), and the command to run the full suite.; Add an error code coverage table listing all 24 error codes and which test file/function triggers each.; Document the mock webhook listener setup: how to start it, which port it binds to, how to register its URL with the sandbox partner config.; Document the schemathesis fuzz command and how to interpret its output.; Add a section describing the exit criteria from QA-12 §2.2.3: zero schema mismatches, all error codes exercised, idempotent replay passes.
**Deliverable:** tests/contract/README.md with setup instructions, env var list, error code coverage table, and exit criteria reference.
**Acceptance / logic checks:**
- README contains a complete list of required environment variables.
- README contains a table of all 24 error codes with test file/function references.
- README documents how to start the mock webhook listener and register its URL.
- README includes the exact command to run the schemathesis fuzz sweep.
- README references QA-12 §2.2.3 exit criteria: zero schema mismatches; all error codes exercised; idempotent replay passes.
**Depends on:** 15.5-T36


## WBS 15.6 — ZeroPay batch round-trip testing
### 15.6-T01 — Define ZeroPay batch file type registry and timing constants  _(30 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. GMEPay+ exchanges 16 ZP00xx file types with 한결원 via SFTP. All times KST (UTC+9). Outbound deadlines: ZP0011/ZP0021 by 02:00, ZP0061 by 05:00, ZP0063 by 14:00, ZP0065/ZP0066 by 22:00. Inbound expected: ZP0012/ZP0022 by 05:00, ZP0062 by 10:00, ZP0064 by 19:00. Merchant/QR sync (ZP0041/ZP0043/ZP0045/ZP0047) polled 01:00-06:00 daily; full syncs (ZP0051/ZP0053/ZP0055) weekly. Tests require a canonical registry of these facts so every test can reference them without duplication.
**Steps:** Create enum or constants class BatchFileType with values ZP0011 through ZP0066 (all 16 types).; Add a BatchFileConfig record per type: direction (OUTBOUND/INBOUND), deadlineKst (nullable for polled files), pollWindowStart/End (nullable), frequency (DAILY/WEEKLY), purpose string.; Populate all 16 entries from SCH-06 §7.3 and §8.1 timing table.; Add helper BatchFileType.isOutbound() and BatchFileType.requiresRegistrationConfirmation() (true for ZP0011/ZP0021).; Write unit test asserting all 16 types present and ZP0011.deadline == 02:00 KST, ZP0061.deadline == 05:00 KST.
**Deliverable:** BatchFileType enum/constants with BatchFileConfig for all 16 ZP00xx types, plus a passing unit test.
**Acceptance / logic checks:**
- All 16 file types present with correct direction: ZP0011/ZP0021/ZP0061/ZP0063/ZP0065/ZP0066 = OUTBOUND; ZP0012/ZP0022/ZP0062/ZP0064 = INBOUND; ZP0041/ZP0043/ZP0045/ZP0047/ZP0051/ZP0053/ZP0055 = INBOUND.
- ZP0011.deadlineKst = 02:00 and ZP0061.deadlineKst = 05:00 verified in unit test.
- ZP0051/ZP0053/ZP0055 frequency = WEEKLY; all others DAILY.
- Helper isOutbound() returns false for ZP0012; true for ZP0011.
- No literal time strings appear in test code — all reference the registry constants.

### 15.6-T02 — Define ZP0011 payment-result record layout and Java model  _(35 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. ZP0011 is the daily outbound payment-result file (GME→ZeroPay), deadline 02:00 KST. Per SCH-06 §5.2: fields include record_type CHAR(1), gme_txn_id CHAR(20), zeropay_txn_ref CHAR(20), merchant_id CHAR(10), qr_code_id CHAR(20), txn_date DATE(8) YYYYMMDD, txn_time TIME(6) HHMMSS, payout_amount_krw NUM(12) KRW no-decimal, merchant_fee_amt NUM(12), van_fee_amt NUM(10), partner_type CHAR(1) D=Domestic I=International, approval_code CHAR(12), status_code CHAR(1) A=Approved. File has a header (file_type, business_date, GME institution code, total record count, total payout KRW) and trailer (record count, control sum). Primary match key with ZP0012: zeropay_txn_ref + txn_date.
**Steps:** Create Zp0011Record POJO/data class with all detail fields typed correctly (KRW as long, dates as LocalDate, times as LocalTime).; Create Zp0011Header and Zp0011Trailer classes.; Create Zp0011File wrapper containing header, list of records, and trailer.; Annotate for fixed-width serialisation if using a file formatting library (or note field widths as constants).; Write unit test constructing a Zp0011File with 2 records and asserting header.recordCount == 2 and trailer.controlSum == sum of payout_amount_krw.
**Deliverable:** Zp0011Record, Zp0011Header, Zp0011Trailer, Zp0011File model classes plus unit test.
**Acceptance / logic checks:**
- payout_amount_krw is long (not double/float) to prevent floating-point rounding on KRW integers.
- partner_type field accepts only D or I; constructor or factory rejects other values.
- header.totalPayoutKrw == sum of all records payout_amount_krw in the unit test.
- Match key fields zeropay_txn_ref (CHAR(20)) and txn_date (DATE(8)) are present on Zp0011Record.
- Trailer controlSum in unit test equals header totalPayoutKrw (both sum of payout amounts).
**Depends on:** 15.6-T01

### 15.6-T03 — Define ZP0012 payment-registration-result record layout and Java model  _(35 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. ZP0012 is the inbound registration-confirmation file (ZeroPay→GME), expected by 05:00 KST. Per SCH-06 §5.3: fields include zeropay_txn_ref CHAR(20), gme_txn_id CHAR(20), merchant_id CHAR(10), txn_date DATE(8), result_code CHAR(2) (00=Success, non-zero=failure), result_message VARCHAR(100), registered_amount NUM(12), settlement_date DATE(8). Match key back to ZP0011: zeropay_txn_ref + txn_date. The reconciliation engine compares ZP0012 records against the sent ZP0011 records using these keys. Batch status codes assigned: SETTLEMENT_REGISTERED (result_code 00, amounts match), REGISTRATION_FAILED (non-zero result_code), REGISTRATION_UNKNOWN (record in ZP0011 absent from ZP0012), REGISTRATION_AMOUNT_MISMATCH (amounts differ).
**Steps:** Create Zp0012Record POJO with all fields, result_code as String(2).; Create Zp0012File with header and list of records.; Create BatchRegistrationStatus enum: SETTLEMENT_REGISTERED, REGISTRATION_FAILED, REGISTRATION_UNKNOWN, REGISTRATION_AMOUNT_MISMATCH.; Add Zp0012Record.deriveStatus(Zp0011Record sent) method that applies the four-case classification logic.; Write unit tests: result_code=00 and amounts match -> SETTLEMENT_REGISTERED; result_code=9001 -> REGISTRATION_FAILED; zero registered_amount vs non-zero sent -> REGISTRATION_AMOUNT_MISMATCH.
**Deliverable:** Zp0012Record, Zp0012File, BatchRegistrationStatus enum, deriveStatus() method with unit tests.
**Acceptance / logic checks:**
- result_code=00 and registered_amount == payout_amount_krw returns SETTLEMENT_REGISTERED.
- result_code=9002 returns REGISTRATION_FAILED regardless of amount.
- registered_amount=13000 vs sent payout_amount_krw=13500 returns REGISTRATION_AMOUNT_MISMATCH.
- A Zp0012Record with result_code=00 but registered_amount=0 is classified REGISTRATION_AMOUNT_MISMATCH not SETTLEMENT_REGISTERED.
- REGISTRATION_UNKNOWN is returned by the reconciliation engine (not deriveStatus) when a ZP0011 record has no matching ZP0012 record at all — tested in 15.6-T14.
**Depends on:** 15.6-T02

### 15.6-T04 — Define ZP0021 and ZP0022 refund-result record layouts and models  _(35 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. ZP0021 (GME→ZeroPay, deadline 02:00 KST) contains refund results for prior-day admin-portal refunds (NOT same-day cancels). Per SCH-06 §6.2 fields: record_type CHAR(1), gme_refund_id CHAR(20), original_zeropay_txn_ref CHAR(20), gme_original_txn_id CHAR(20), merchant_id CHAR(10), refund_date DATE(8), refund_time TIME(6), refund_amount_krw NUM(12), merchant_fee_adj_amt NUM(12), partner_type CHAR(1), refund_reason_code CHAR(2), status_code CHAR(1) R=Refund. ZP0022 (ZeroPay→GME, by 05:00 KST) mirrors ZP0012 structure: original_zeropay_txn_ref CHAR(20), gme_refund_id CHAR(20), result_code CHAR(2), result_message VARCHAR(100), registered_refund_amount NUM(12), adjustment_settlement_date DATE(8). Match key: original_zeropay_txn_ref + refund_date.
**Steps:** Create Zp0021Record POJO and Zp0021File (header/records/trailer mirroring ZP0011 pattern).; Create Zp0022Record POJO and Zp0022File.; Reuse BatchRegistrationStatus enum from 15.6-T03 for ZP0022 status derivation.; Add Zp0022Record.deriveStatus(Zp0021Record sent) using same 4-case logic as ZP0012.; Write unit tests: refund success path (result_code=00, amounts match), failure path (non-zero result_code).
**Deliverable:** Zp0021Record, Zp0021File, Zp0022Record, Zp0022File with unit tests.
**Acceptance / logic checks:**
- Zp0021Record status_code accepts only R; constructor/factory rejects other values.
- Match key on Zp0022Record is original_zeropay_txn_ref + refund_date (not zeropay_txn_ref as per ZP0012).
- Same-day cancels are not included: Zp0021Generator (T07) must filter out transactions with cancel_time on same business date as original.
- result_code=00 and registered_refund_amount == refund_amount_krw returns SETTLEMENT_REGISTERED.
- Amount mismatch: registered_refund_amount=500 vs sent refund_amount_krw=1000 returns REGISTRATION_AMOUNT_MISMATCH.
**Depends on:** 15.6-T03

### 15.6-T05 — Define ZP0061/ZP0062 morning settlement request and result models  _(40 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. ZP0061 (GME→ZeroPay, deadline 05:00 KST) is the primary daily settlement request. Per SCH-06 §7.2: per-merchant summary fields include merchant_id CHAR(10), settlement_date DATE(8), gross_txn_count NUM(6), gross_txn_amount NUM(14) KRW, refund_count NUM(6), refund_amount NUM(14) KRW, merchant_fee_total NUM(12) KRW, net_settlement_amount NUM(14) KRW, settlement_type CHAR(1) N=Net/domestic G=Gross/international. Dependency chain: ZP0011 success AND ZP0012 received are prerequisites for ZP0061 generation (SCH-06 §8.2). ZP0062 (ZeroPay→GME, by 10:00 KST) confirms per-merchant settlement status and credited amounts. Net settlement (domestic): net_settlement_amount = gross_txn_amount - refund_amount - merchant_fee_total. Gross settlement (international): net_settlement_amount = gross_txn_amount - refund_amount (full payout; GME invoices merchant separately).
**Steps:** Create Zp0061MerchantRecord POJO with all summary fields; use long for all KRW amounts.; Create Zp0061File with header (business_date, total merchant count, grand total settlement) and list of records.; Create Zp0062MerchantResult POJO with merchant_id, settlement_status CHAR(2), credited_amount NUM(14).; Create Zp0062File with header and list of results.; Write unit test: domestic merchant with gross_txn_amount=100000, refund_amount=5000, merchant_fee_total=800 -> net_settlement_amount=94200 (settlement_type=N); international same amounts -> net_settlement_amount=95000 (settlement_type=G, fee not deducted in file).
**Deliverable:** Zp0061MerchantRecord, Zp0061File, Zp0062MerchantResult, Zp0062File with unit tests covering net vs gross.
**Acceptance / logic checks:**
- Domestic (N) net_settlement_amount = gross_txn_amount - refund_amount - merchant_fee_total (100000-5000-800=94200).
- International (G) net_settlement_amount = gross_txn_amount - refund_amount (100000-5000=95000); merchant_fee_total still populated for reference but not deducted.
- settlement_type accepts only N or G; rejects other values.
- Unit test confirms ZP0061 generation requires prior ZP0011 success flag and ZP0012 receipt flag (modelled as boolean prerequisites in the file builder).
- Zp0061File header grand_total = sum of all net_settlement_amount across records.
**Depends on:** 15.6-T03

### 15.6-T06 — Define ZP0063/ZP0064/ZP0065/ZP0066 afternoon settlement and detail models  _(35 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. ZP0063 (GME→ZeroPay, deadline 14:00 KST) is the supplementary afternoon settlement request for transactions approved after ZP0061 cutoff. ZP0064 (ZeroPay→GME, by 19:00 KST) confirms the afternoon cycle. ZP0065 (GME→ZeroPay, deadline 22:00 KST) is transaction-level detail underlying ZP0061/ZP0063 totals; fields: merchant_id CHAR(10), zeropay_txn_ref CHAR(20), txn_date DATE(8), txn_time TIME(6), payout_amount_krw NUM(12), merchant_fee_amt NUM(12), van_fee_amt NUM(10), partner_type CHAR(1) D/I, settlement_batch_ref CHAR(20) linking back to ZP0061 or ZP0063. ZP0066 (GME→ZeroPay, deadline 22:00 KST) is refund-level detail: merchant_id, original_zeropay_txn_ref CHAR(20), refund_date DATE(8), refund_amount_krw NUM(12), merchant_fee_adj_amt NUM(12), settlement_batch_ref CHAR(20).
**Steps:** Create Zp0063File and Zp0064File models (same field structure as ZP0061/ZP0062 with different file type codes).; Create Zp0065DetailRecord POJO with all payment-detail fields; link settlement_batch_ref to either ZP0061 or ZP0063 batch reference.; Create Zp0066DetailRecord POJO with all refund-detail fields.; Add validation: each Zp0065DetailRecord.settlement_batch_ref must reference an existing ZP0061 or ZP0063 batch_ref.; Write unit test: generate ZP0065 for 3 transactions (2 in morning batch ZP0061_REF, 1 in afternoon ZP0063_REF); assert correct settlement_batch_ref on each record.
**Deliverable:** Zp0063File, Zp0064File, Zp0065DetailRecord, Zp0066DetailRecord models with unit tests.
**Acceptance / logic checks:**
- settlement_batch_ref on Zp0065DetailRecord resolves to a known ZP0061 or ZP0063 batch reference.
- Zp0066DetailRecord uses original_zeropay_txn_ref (not zeropay_txn_ref) for refund linking.
- ZP0065 and ZP0066 deadline is 22:00 KST; this is captured in BatchFileConfig from T01.
- Unit test confirms that a transaction settled in morning batch has settlement_batch_ref = ZP0061 batch reference.
- van_fee_amt field type is long (KRW integer), not float.
**Depends on:** 15.6-T05

### 15.6-T07 — Implement ZP0011 file generator — aggregate prior-day approved transactions  _(55 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. The ZP0011 generator must: query transactions with status=APPROVED for the prior business day (KST), exclude same-day-cancelled transactions, produce a valid Zp0011File (header + detail records + trailer), and record the generation in the batch_runs table (one row per daily batch run per scheme and file type per DAT-03 §batch_run). Scope: all APPROVED transactions not already included in a prior ZP0011 batch (idempotency via batch_runs check). Test synthetic partner P-TEST-001 (LOCAL/Domestic) and P-TEST-002 (OVERSEAS/International) from QA-12 §3.1. The batch_runs table columns relevant here: scheme_id, file_type (ZP0011), business_date, status, record_count, control_sum, generated_at.
**Steps:** Implement Zp0011Generator.generate(businessDate: LocalDate): Zp0011File method.; Query transactions: status=APPROVED, txn_date=businessDate (KST), not already in a successful batch_run for ZP0011 on businessDate.; Map each transaction to Zp0011Record including partner_type (D for LOCAL, I for OVERSEAS).; Compute header total payout and trailer control sum.; Persist a batch_run row with status=GENERATED; update to TRANSMITTED after SFTP success (handled in T10).; Write integration test seeding 3 APPROVED transactions (2 domestic P-TEST-001, 1 international P-TEST-002) and asserting generated file has 3 records with correct partner_type values.
**Deliverable:** Zp0011Generator class plus integration test with DB seeding.
**Acceptance / logic checks:**
- File contains exactly the transactions for the given businessDate and no others (test with 5 seeded transactions: 3 for target date, 2 for other dates).
- Transactions already included in a prior successful ZP0011 batch_run are excluded (idempotency test: run generator twice; second run produces 0-record file).
- partner_type=D for transactions with partner_type LOCAL; partner_type=I for OVERSEAS — verified on individual records.
- header.totalPayoutKrw == sum of all payout_amount_krw in detail records.
- Cancelled same-day transactions (status=CANCELLED, cancel_time same KST date) are excluded from the file.
**Depends on:** 15.6-T02, 15.6-T01

### 15.6-T08 — Implement ZP0021 refund file generator — aggregate prior-day admin-portal refunds  _(50 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. ZP0021 (deadline 02:00 KST) contains refunds processed via the Admin Portal for the prior business day. Same-day cancels are excluded (they use the real-time API cancel path). Refund records must reference the original transaction's zeropay_txn_ref (stored on the original transaction as zeropay_txn_ref) via the field original_zeropay_txn_ref. Each refund must have an original transaction with status SETTLEMENT_REGISTERED or SETTLEMENT_CONFIRMED, else it cannot be included. A refund_reason_code (CHAR(2), scheme-defined) must be present. The generator must record in batch_runs (scheme_id=ZEROPAY, file_type=ZP0021, business_date, record_count, status=GENERATED).
**Steps:** Implement Zp0021Generator.generate(businessDate: LocalDate): Zp0021File.; Query refunds: refund_date=businessDate (KST), refund_type=ADMIN_REFUND (not SAME_DAY_CANCEL), not already in a successful ZP0021 batch_run for businessDate.; For each refund, load original transaction to populate original_zeropay_txn_ref and gme_original_txn_id.; Validate original transaction has zeropay_txn_ref populated; if missing log error and skip record (raise exception).; Compute header totals and write batch_run record.; Integration test: seed 2 admin refunds and 1 same-day cancel; assert generated file has exactly 2 records (same-day cancel excluded).
**Deliverable:** Zp0021Generator class plus integration test.
**Acceptance / logic checks:**
- Same-day cancels (cancel_time same KST date as original txn_date) are absent from the ZP0021 output.
- Each Zp0021Record.original_zeropay_txn_ref matches the refunded transaction zeropay_txn_ref in DB.
- A refund with no original zeropay_txn_ref raises a logged exception and is excluded from the file but does not abort the entire batch.
- Idempotency: running generator twice for same businessDate produces a second run with 0 records.
- header.totalRefundKrw == sum of all refund_amount_krw across all detail records.
**Depends on:** 15.6-T04, 15.6-T07

### 15.6-T09 — Implement ZP0061 morning settlement request generator  _(55 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. ZP0061 (deadline 05:00 KST) is the primary settlement request. Prerequisite: ZP0011 batch_run for businessDate has status=TRANSMITTED AND ZP0012 inbound file has been received and parsed for businessDate (batch_run ZP0012 status=PROCESSED). If either prerequisite is unmet, the generator must abort with a PREREQUISITE_NOT_MET exception and alert Ops. Aggregation logic: group SETTLEMENT_REGISTERED transactions by merchant_id; compute per-merchant gross_txn_amount, refund_amount, merchant_fee_total. For domestic (partner_type=D): net_settlement_amount = gross_txn_amount - refund_amount - merchant_fee_total; settlement_type=N. For international (partner_type=I): net_settlement_amount = gross_txn_amount - refund_amount; settlement_type=G. Record in batch_runs (file_type=ZP0061).
**Steps:** Implement Zp0061Generator.generate(businessDate: LocalDate): Zp0061File.; Check prerequisites: assert batch_runs has ZP0011 status=TRANSMITTED and ZP0012 status=PROCESSED for businessDate; throw if not.; Query transactions with status=SETTLEMENT_REGISTERED and batch_date=businessDate; group by merchant_id.; For each merchant group apply domestic vs international net_settlement_amount formula.; Write Zp0061MerchantRecord per merchant; compute file header grand total.; Integration test: 2 domestic merchants (gross 100000/80000, refund 5000/0, fee 800/640) and 1 international merchant (gross 200000, refund 10000, fee 3400); assert net amounts: 94200, 79360, 190000.
**Deliverable:** Zp0061Generator class with prerequisite-check and aggregation logic, plus integration test.
**Acceptance / logic checks:**
- Domestic net = gross_txn_amount - refund_amount - merchant_fee_total: 100000 - 5000 - 800 = 94200.
- International net = gross_txn_amount - refund_amount: 200000 - 10000 = 190000 (merchant_fee_total still populated on record).
- PREREQUISITE_NOT_MET exception thrown and Ops alert raised if ZP0011 not TRANSMITTED or ZP0012 not PROCESSED.
- Transactions with status != SETTLEMENT_REGISTERED are excluded (e.g. REGISTRATION_FAILED transactions are not settled until exception resolved).
- File header grand_total_settlement == sum of all net_settlement_amount across all merchant records.
**Depends on:** 15.6-T05, 15.6-T07

### 15.6-T10 — Implement SFTP outbound transfer with PGP encryption and retry logic  _(55 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. Per SCH-06 §2.4, every outbound batch job must: (1) generate file content in memory, (2) write to a temp secure scratch dir, (3) PGP-encrypt with 한결원 public key and sign with GME private key, (4) SFTP PUT to /gmepay/outbound/, (5) verify transfer via remote checksum, (6) on success archive locally and log metadata (filename, bytes, timestamp, checksum). On failure: retry up to 3 times with exponential back-off (30s, 2min, 10min); then alert Ops and halt — do NOT proceed to dependent batch jobs. File naming: {FILE_ID}_{YYYYMMDD}_{SEQ}.dat.pgp where SEQ is 01 for first attempt, 02 for retransmission. PGP key refs come from SchemeConfig (pgp_public_key_ref, pgp_private_key_ref) stored in secrets store — never on disk.
**Steps:** Implement SftpBatchTransferService.transmit(batchFile, schemeConfig): TransferResult.; Implement PGP encrypt-and-sign in memory using key refs from schemeConfig; no cleartext intermediate files.; Implement filename construction: FILE_ID + business_date + sequence padded to 2 digits.; Implement SFTP PUT with remote size/checksum verification; throw TransferVerificationException on mismatch.; Implement retry loop: max 3 attempts, delays 30s/120s/600s; on final failure call opsAlertService.raiseP1() and throw BatchHaltException.; Unit test with mock SFTP client: first 2 PUT calls throw IOException, third succeeds; assert exactly 3 attempts and TransferResult.success=true.
**Deliverable:** SftpBatchTransferService with PGP, retry, and alert wiring, plus unit tests.
**Acceptance / logic checks:**
- First two SFTP failures are retried at correct backoff intervals (30s, 2min); third attempt succeeds — no Ops alert raised.
- Fourth consecutive failure (all 3 retries exhausted) calls opsAlertService.raiseP1() exactly once and throws BatchHaltException.
- No cleartext temp file exists on disk after successful PGP operation (temp file deleted in finally block).
- Filename for retransmission has sequence 02 (not 01); test by calling transmit twice for same file and asserting second filename ends _02.dat.pgp.
- TransferResult records filename, byte_count, transfer_timestamp, and sha256_checksum for audit log.
**Depends on:** 15.6-T01

### 15.6-T11 — Implement SFTP inbound poller — download, decrypt, and stage inbound files  _(55 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. Per SCH-06 §2.4 inbound process: poll /gmepay/inbound/, download to local scratch dir, verify file size/checksum, PGP-decrypt using GME private key and verify 한결원 signature, then hand off to the appropriate file parser. The polling schedule: ZP0012/ZP0022 expected by 05:00 KST, ZP0062 by 10:00 KST, ZP0064 by 19:00 KST. Merchant/QR sync files polled 01:00-06:00 hourly. If an expected file has not arrived by deadline + 60 minutes, raise a BATCH_FILE_LATE ops alert. All downloaded files are archived locally; 90-day retention enforced.
**Steps:** Implement SftpInboundPollerService.poll(fileType, businessDate): Optional<byte[]>.; Implement file-size and SHA256 checksum verification post-download; throw CorruptFileException if mismatch.; Implement PGP decrypt + signature verify; throw SignatureVerificationException if signature invalid.; Implement late-file monitor: SftpInboundMonitor.checkDeadline(fileType, businessDate, now) raises BATCH_FILE_LATE alert if now > deadline + 60min and file not yet received.; Write unit tests: mock SFTP returns corrupt file (wrong checksum) -> CorruptFileException; mock SFTP returns valid file -> byte[] returned; late-file check at deadline+61min -> alert fired.; Write test: file arrives at deadline+45min -> no alert; file not present at deadline+61min -> exactly one alert.
**Deliverable:** SftpInboundPollerService and SftpInboundMonitor with unit tests.
**Acceptance / logic checks:**
- CorruptFileException thrown when downloaded file SHA256 does not match remote-provided checksum.
- SignatureVerificationException thrown when 한결원 PGP signature is invalid — transaction NOT processed.
- BATCH_FILE_LATE alert fired if file absent at deadline + 60min: ZP0012 at 06:00 KST, ZP0062 at 11:00 KST, ZP0064 at 20:00 KST.
- Downloaded file is archived to local retention store regardless of parse outcome (for dispute purposes).
- No alert fired if file arrives 45 minutes after deadline (within the 60-minute grace window).
**Depends on:** 15.6-T01, 15.6-T10

### 15.6-T12 — Implement ZP0012 inbound parser and registration-result reconciliation  _(55 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. After ZP0012 is downloaded and decrypted, the parser produces a list of Zp0012Record objects. The reconciliation engine then performs line-by-line match against the ZP0011 records for the same businessDate using key zeropay_txn_ref + txn_date. Four outcomes (SCH-06 §5.4): (1) result_code=00 and amounts match -> update transaction status to SETTLEMENT_REGISTERED; (2) result_code non-zero -> status REGISTRATION_FAILED; (3) ZP0011 record absent from ZP0012 -> status REGISTRATION_UNKNOWN; (4) amounts differ -> status REGISTRATION_AMOUNT_MISMATCH. Any outcome other than (1) creates an exception_record in the exceptions table and triggers an Ops alert. After processing, update batch_runs ZP0012 row status=PROCESSED.
**Steps:** Implement Zp0012FileParser.parse(decryptedBytes): Zp0012File; validate header record_count matches actual records.; Implement PaymentRegistrationReconciler.reconcile(businessDate): ReconciliationResult; load ZP0011 records from DB, load ZP0012 parsed records, execute four-case match.; For each non-success outcome, insert a row into exception_records table and call opsAlertService.raiseAlert().; Update transaction.status in the transactions table for all matched and unmatched records.; Update batch_runs ZP0012 row to status=PROCESSED.; Integration test: seed ZP0011 with 4 records; inject ZP0012 with result_code=00 for 2, non-zero for 1, and 1 absent; assert transaction statuses and exception_records count=2.
**Deliverable:** Zp0012FileParser, PaymentRegistrationReconciler, and integration test.
**Acceptance / logic checks:**
- ZP0011 record with matching ZP0012 result_code=00 and equal amounts -> transaction status=SETTLEMENT_REGISTERED; no exception created.
- ZP0011 record matching ZP0012 result_code=9001 -> transaction status=REGISTRATION_FAILED; 1 exception_record created; Ops alert raised.
- ZP0011 record absent from ZP0012 -> status=REGISTRATION_UNKNOWN; exception_record created.
- Amount mismatch: ZP0012 registered_amount=13000 vs ZP0011 payout_amount_krw=13500 -> REGISTRATION_AMOUNT_MISMATCH; exception_record created.
- Header record_count mismatch (header says 5 records but file has 4) -> CorruptFileException; batch halted; Ops alert.
**Depends on:** 15.6-T03, 15.6-T11, 15.6-T07

### 15.6-T13 — Implement ZP0022 inbound parser and refund registration reconciliation  _(50 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. ZP0022 mirrors ZP0012 for refunds. Match key: original_zeropay_txn_ref + refund_date (not zeropay_txn_ref). Four-case reconciliation logic identical to ZP0012 (SCH-06 §6.4). Unresolved refund registration failure before ZP0061 settlement is a P1 ops incident. After reconciliation, batch_runs ZP0022 row is updated to PROCESSED. ZP0022 receipt is a prerequisite for the ZP0061 refund portion (batch dependency enforced in the settlement generator — T09).
**Steps:** Implement Zp0022FileParser.parse(decryptedBytes): Zp0022File.; Implement RefundRegistrationReconciler.reconcile(businessDate): ReconciliationResult using original_zeropay_txn_ref + refund_date as match key.; Apply four-case logic identical to PaymentRegistrationReconciler; insert exception_records for non-success outcomes.; Update refund transaction statuses and batch_runs ZP0022 row.; Integration test: 3 refunds in ZP0021; ZP0022 has 2 success (result_code=00), 1 failure (result_code=9001); assert 1 exception_record and correct statuses.; Test edge case: ZP0022 record with registered_refund_amount=0 vs sent refund_amount_krw=5000 -> REGISTRATION_AMOUNT_MISMATCH.
**Deliverable:** Zp0022FileParser, RefundRegistrationReconciler with integration test.
**Acceptance / logic checks:**
- Match key is original_zeropay_txn_ref + refund_date (not zeropay_txn_ref) — wrong key would fail integration test.
- P1 exception raised and flagged when any refund registration failure exists before ZP0061 window.
- Idempotency: running reconciler twice on same ZP0022 does not duplicate exception_records (upsert on unique key).
- Batch_runs ZP0022 status updated to PROCESSED after successful reconciliation.
- Integration test confirms exactly 1 exception_record for result_code=9001 failure.
**Depends on:** 15.6-T04, 15.6-T11, 15.6-T08

### 15.6-T14 — Implement ZP0062/ZP0064 settlement result parsers and settlement reconciliation  _(50 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. ZP0062 (by 10:00 KST) confirms morning settlement; ZP0064 (by 19:00 KST) confirms afternoon settlement. Both are parsed and reconciled against the corresponding sent file (ZP0061 vs ZP0062, ZP0063 vs ZP0064). Per SCH-06 §9: zero-tolerance — every merchant total must match. Discrepancy creates a SETTLEMENT_DISCREPANCY exception_record and Ops alert. On success update transaction statuses to SETTLEMENT_CONFIRMED. The reconciliation_items table (DAT-03) stores one row per transaction line in the reconciliation comparison with columns: batch_run_id, transaction_id, gme_amount, scheme_amount, status, reconciled_at.
**Steps:** Implement Zp0062FileParser.parse(decryptedBytes): Zp0062File and Zp0064FileParser.parse(decryptedBytes): Zp0064File.; Implement SettlementReconciler.reconcile(sentFile: Zp0061File, resultFile: Zp0062File): SettlementReconciliationResult.; For each merchant: compare net_settlement_amount (sent) vs credited_amount (result); zero-tolerance check.; On mismatch: update transaction status to SETTLEMENT_DISCREPANCY; insert exception_record; raise P1 alert.; On match: update to SETTLEMENT_CONFIRMED; insert reconciliation_items rows.; Integration test: 3 merchants in ZP0061; ZP0062 confirms 2 exactly, 1 has credited_amount=93000 vs expected 94200; assert SETTLEMENT_DISCREPANCY exception and 1 P1 alert.
**Deliverable:** Zp0062FileParser, Zp0064FileParser, SettlementReconciler with integration test.
**Acceptance / logic checks:**
- Merchant net_settlement_amount=94200 vs credited_amount=94200 -> SETTLEMENT_CONFIRMED; no exception.
- Merchant net_settlement_amount=94200 vs credited_amount=93000 -> SETTLEMENT_DISCREPANCY exception_record created; P1 alert raised.
- Merchant in ZP0061 absent from ZP0062 -> exception_record with type SETTLEMENT_UNKNOWN; P1 alert.
- All reconciliation_items rows have gme_amount and scheme_amount populated for audit.
- SettlementReconciler.reconcile is idempotent: re-running on same ZP0062 does not create duplicate exception_records.
**Depends on:** 15.6-T05, 15.6-T06, 15.6-T11

### 15.6-T15 — Implement ZP0065/ZP0066 detail file generators and internal ledger reconciliation  _(55 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. ZP0065 (GME→ZeroPay, 22:00 KST) provides transaction-level detail underlying ZP0061/ZP0063 totals. ZP0066 provides refund-level detail. Per SCH-06 §9, detail reconciliation is also zero-tolerance: ZP0065 vs internal GMEPay+ transaction ledger must match every line item. Each Zp0065DetailRecord.settlement_batch_ref links the transaction to either the ZP0061 or ZP0063 batch_run reference (records approved before ZP0061 cutoff get ZP0061 ref; records after get ZP0063 ref). The internal ledger is the transactions table in the GMEPay+ DB.
**Steps:** Implement Zp0065Generator.generate(businessDate): Zp0065File; include all SETTLEMENT_CONFIRMED and SETTLEMENT_REGISTERED transactions for businessDate, setting settlement_batch_ref per morning/afternoon batch assignment.; Implement Zp0066Generator.generate(businessDate): Zp0066File for all reconciled refunds.; Implement DetailLedgerReconciler.reconcile(businessDate): compare each ZP0065 record against the transactions table; report any amount mismatch.; Integration test: 5 transactions (3 morning batch, 2 afternoon batch); assert settlement_batch_ref is ZP0061 batch_ref for first 3 and ZP0063 batch_ref for last 2.; Test reconciliation mismatch: manually alter one transaction's payout_amount_krw in DB after ZP0065 generation; reconciler must detect and flag.
**Deliverable:** Zp0065Generator, Zp0066Generator, DetailLedgerReconciler with integration tests.
**Acceptance / logic checks:**
- Transactions approved before ZP0061 cutoff time have settlement_batch_ref = ZP0061 batch reference; those after have ZP0063 reference.
- ZP0065 record count == count of SETTLEMENT_CONFIRMED transactions for businessDate.
- DetailLedgerReconciler detects a 100 KRW discrepancy between ZP0065 record and DB and creates an exception_record.
- ZP0066 original_zeropay_txn_ref matches the original payment zeropay_txn_ref in DB for each refund line.
- ZP0065 + ZP0066 are generated and transmitted after ZP0063 is transmitted (22:00, depends on afternoon cycle).
**Depends on:** 15.6-T06, 15.6-T09

### 15.6-T16 — Implement ZP0041 merchant-delta inbound parser and upsert handler  _(55 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. ZP0041 (ZeroPay→GME, daily delta, polled 01:00-06:00 KST) contains new and changed merchant records. Per SCH-06 §4.2: change_type CHAR(1) I=Insert, U=Update, D=Deactivate. Fields: merchant_id CHAR(10), merchant_name VARCHAR(100), business_reg_no CHAR(10), merchant_type_code CHAR(2), status_code CHAR(1) A/S/T, bank_code CHAR(3), account_no VARCHAR(20), effective_date DATE(8). GME handling: upsert into merchants table keyed on merchant_id. D records set status=INACTIVE. Deactivated merchants must be blocked at payment time immediately upon DB update. Log counts: inserts, updates, deactivations. Reconcile: compare file header record_count vs actual records; mismatch -> alert and reject.
**Steps:** Implement Zp0041FileParser.parse(bytes): list of Zp0041Record with change_type and all fields.; Implement MerchantDeltaSyncHandler.apply(records): upsert merchants table; for D records set status=INACTIVE.; Validate file header record_count against actual parsed record count; throw on mismatch.; Log sync_result to merchant_sync_log table: run_date, file_type=ZP0041, inserts, updates, deactivations.; Integration test: 4 records (1 Insert new merchant, 1 Update name change, 1 Deactivate M-TEST-0002, 1 Deactivate M-TEST-0004); assert M-TEST-0002 status=INACTIVE in DB after handler.; Test payment blocked immediately: after deactivating M-TEST-0002, simulate payment validation; expect MERCHANT_INACTIVE error.
**Deliverable:** Zp0041FileParser, MerchantDeltaSyncHandler with integration test including immediate-block validation.
**Acceptance / logic checks:**
- M-TEST-0002 with change_type=D is set to status=INACTIVE in merchants table immediately after handler completes.
- Payment attempt for M-TEST-0002 returns MERCHANT_INACTIVE after deactivation — no TTL or cache delay.
- Upsert is idempotent: replaying ZP0041 with same records does not create duplicate rows.
- Header record_count mismatch (header=5, actual=4) throws CorruptFileException; batch rejected; Ops alert.
- merchant_sync_log row written with correct insert/update/deactivation counts.
**Depends on:** 15.6-T11

### 15.6-T17 — Implement ZP0045/ZP0047 franchise merchant and group delta handlers  _(50 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. ZP0045 (daily delta) covers franchise merchant branches; ZP0047 (daily delta) covers franchise group master data. Per SCH-06 §4.2: ZP0045 fields include franchise_merchant_id CHAR(10), franchise_code CHAR(6), merchant_name VARCHAR(100), merchant_type_code CHAR(2), status_code CHAR(1), change_type CHAR(1). Franchise merchants are stored in the merchants table with is_franchise=TRUE and franchise_code FK to franchise_groups table. ZP0047 fields: franchise_code CHAR(6) PK, franchise_name VARCHAR(100), franchise_type CHAR(2), head_merchant_id CHAR(10), status_code CHAR(1), change_type CHAR(1). Processing order: ZP0047 (groups) must be processed before ZP0045 (branches) to satisfy FK constraint.
**Steps:** Implement Zp0045FileParser and Zp0047FileParser.; Implement FranchiseDeltaSyncHandler.apply(groupRecords, branchRecords): process ZP0047 records first (upsert franchise_groups), then ZP0045 records (upsert merchants with is_franchise=TRUE).; On FK violation (branch references unknown franchise_code): log error, skip branch record, create exception_record for manual review.; Integration test: ZP0047 adds franchise_code=FC0001 (TestChain), ZP0045 adds franchise_merchant_id=M-TEST-0003 with franchise_code=FC0001; assert M-TEST-0003 in DB with is_franchise=TRUE and franchise_code=FC0001.; Edge case test: ZP0045 record references franchise_code=FC9999 (not in ZP0047); assert exception_record created and branch skipped.
**Deliverable:** Zp0045FileParser, Zp0047FileParser, FranchiseDeltaSyncHandler with integration tests.
**Acceptance / logic checks:**
- ZP0047 (groups) processed before ZP0045 (branches) in every execution path — no FK violations for valid files.
- Franchise merchant M-TEST-0003 stored with is_franchise=TRUE and correct franchise_code after sync.
- Unknown franchise_code in ZP0045 branch record creates exception_record and skips that record but does not abort remaining records.
- Deactivation of a franchise_group (ZP0047 D record) sets status=INACTIVE on both the group and all its member merchants.
- Idempotency: replaying ZP0045/ZP0047 with identical records produces no new DB rows.
**Depends on:** 15.6-T16

### 15.6-T18 — Implement ZP0043 QR registration/deactivation delta handler  _(45 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. ZP0043 (daily delta, polled 01:00-06:00 KST) contains QR code registrations and deactivations. Per SCH-06 §4.2: qr_code_id CHAR(20) PK, merchant_id CHAR(10) FK, qr_type CHAR(1) M=MPM/C=CPM, status_code CHAR(1) A=Active/D=Deactivated, issue_date DATE(8), deactivation_date DATE(8) nullable, change_type CHAR(1) I=New/D=Deactivated. Per SCH-06 §3.5 and §4.2: deactivated QR must be blocked immediately upon DB update — any payment attempt referencing a deactivated QR must return QR_DEACTIVATED. Referential integrity: each qr_code_id must reference a valid merchant_id in merchants table.
**Steps:** Implement Zp0043FileParser.parse(bytes): list of Zp0043Record.; Implement QrDeltaSyncHandler.apply(records): upsert qr_codes table; D records set status=INACTIVE and populate deactivation_date.; Validate merchant_id FK for each record; on violation skip and create exception_record.; Log to merchant_sync_log with file_type=ZP0043.; Integration test: QR-TEST-0005 arrives with change_type=D (deactivation); assert qr_codes table has status=INACTIVE and deactivation_date populated.; Payment validation test: after deactivation, attempt payment with QR-TEST-0005; assert QR_DEACTIVATED error returned immediately.
**Deliverable:** Zp0043FileParser, QrDeltaSyncHandler with integration test.
**Acceptance / logic checks:**
- QR-TEST-0005 deactivated (change_type=D) -> status=INACTIVE in qr_codes table; deactivation_date set to the date in the record.
- Payment referencing QR-TEST-0005 after deactivation returns QR_DEACTIVATED immediately — no cache delay.
- QR record referencing non-existent merchant_id -> exception_record created; QR record skipped; remaining records processed.
- New QR registration (change_type=I) with valid merchant_id -> inserted into qr_codes with status=ACTIVE.
- Idempotency: replaying ZP0043 deactivation record does not toggle status back to ACTIVE.
**Depends on:** 15.6-T16

### 15.6-T19 — Implement ZP0051 full merchant list sync with atomic staging promotion  _(55 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. ZP0051 (weekly full sync, same layout as ZP0041 but contains ALL active merchants) replaces the entire local merchant dataset. Per SCH-06 §4.2: (1) download and validate checksum + record count, (2) load into merchants_staging table, (3) diff staging vs live, (4) apply inserts/updates/soft-deletes for absences, (5) confirm record count matches header, (6) promote staging to live atomically in a single DB transaction, (7) log full-sync result. Full-sync failure must NOT partially overwrite live data — rollback on any error and retain previous day data. ZP0051 supersedes ZP0041 on the same processing run (SCH-06 §4.3).
**Steps:** Implement Zp0051FullSyncHandler.sync(businessDate): perform full-sync sequence using merchants_staging table.; Validate file header record_count == actual parsed records; abort on mismatch.; Within a single DB transaction: truncate merchants_staging, bulk insert from file, diff vs merchants table, apply changes, verify counts, then commit.; On any exception within the transaction: rollback; preserve live merchants table; insert failure row into merchant_sync_log; alert Ops.; Ensure ZP0051 presence on the same run suppresses ZP0041 delta processing for merchants (via scheduler flag).; Integration test: seed live merchants table with 5 records; ZP0051 has 4 records (one existing removed, one new added); assert after sync: 4 live records, old record soft-deleted (status=INACTIVE), new record inserted.
**Deliverable:** Zp0051FullSyncHandler with atomic transaction and rollback behaviour, plus integration test.
**Acceptance / logic checks:**
- File with header record_count=100 but 99 actual records throws CorruptFileException; live merchants table unchanged.
- Simulated DB error mid-promotion: live merchants table retains all pre-sync records (rollback confirmed).
- Merchant in live table but absent from ZP0051 full list is soft-deleted (status=INACTIVE), not physically removed.
- ZP0041 delta handler is skipped (no-op) when ZP0051 is present in the same processing run.
- merchant_sync_log row records total_count, inserts, updates, deactivations, and sync result (SUCCESS/FAILED).
**Depends on:** 15.6-T16

### 15.6-T20 — Implement ZP0053/ZP0055 full QR and franchise list sync handlers  _(50 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. ZP0053 (weekly full QR sync) mirrors ZP0051 logic for the qr_codes table. ZP0053 after promotion must validate referential integrity: every qr_code_id must reference a valid merchant_id (run against merchants table after ZP0051 if both arrive same night). ZP0055 (weekly full franchise sync) mirrors ZP0051 logic for both franchise_groups and franchise-merchant rows. Per SCH-06 §4.2: ZP0053 after staging diff must also check that all qr_code_id records have active merchant FK — orphaned QRs (merchant absent or INACTIVE) are skipped with exception_record. Processing order on full-sync night: ZP0051 first (merchants), then ZP0055 (franchise), then ZP0053 (QR codes) to respect FK dependencies.
**Steps:** Implement Zp0053FullSyncHandler.sync(businessDate) using qr_codes_staging table; after atomic promote, run FK referential integrity check vs merchants table.; Implement Zp0055FullSyncHandler.sync(businessDate) for franchise_groups and franchise-merchants tables; apply ZP0047+ZP0045 combined full-list logic atomically.; Enforce processing order: Zp0051 must be PROCESSED before Zp0053; throw if prerequisite not met.; Integration test: ZP0053 contains QR referencing a merchant_id not in merchants table after ZP0051; assert exception_record created for orphaned QR; QR not promoted to live.; Integration test: ZP0055 deactivates a franchise group; assert all member franchise_merchants set to INACTIVE.
**Deliverable:** Zp0053FullSyncHandler, Zp0055FullSyncHandler with integration tests.
**Acceptance / logic checks:**
- Orphaned QR (merchant_id absent from merchants table) in ZP0053 -> exception_record created; QR skipped; remaining QRs promoted.
- ZP0053 processing before ZP0051 completes throws PREREQUISITE_NOT_MET exception.
- ZP0055 deactivation of franchise_group -> all member merchants in franchise_merchants table set to INACTIVE.
- Rollback test: simulated DB failure during ZP0053 promote -> qr_codes table unchanged; previous day QRs intact.
- ZP0043 and ZP0045 delta handlers skipped for their entity types when ZP0053/ZP0055 are present on same processing run.
**Depends on:** 15.6-T18, 15.6-T17, 15.6-T19

### 15.6-T21 — Implement batch job scheduler with dependency-chain enforcement  _(55 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. The settlement engine schedules cron jobs at 02:00, 05:00, 14:00, and 22:00 KST. Per SCH-06 §8.2, the following dependency chain must be enforced: ZP0011 TRANSMITTED + ZP0012 PROCESSED -> enables ZP0061. ZP0021 TRANSMITTED + ZP0022 PROCESSED -> enables ZP0061 refund portion. ZP0062 RECEIVED -> update morning settlement status. ZP0063 depends on morning cycle complete. ZP0064 RECEIVED -> update afternoon status. ZP0065/ZP0066 depend on all daily transactions settled. If ZP0012/ZP0022 indicate registration failures, settlement is held for affected transactions only — unaffected transactions proceed. Each job reads batch_run statuses from DB before proceeding.
**Steps:** Implement BatchJobScheduler with cron triggers at 02:00, 05:00, 14:00, 22:00 KST.; 02:00 job: run Zp0011Generator then Zp0021Generator; transmit via SftpBatchTransferService.; 05:00 job: poll for ZP0012 and ZP0022; run reconcilers; check prerequisites; if met run Zp0061Generator and transmit.; 14:00 job: poll for ZP0062; run SettlementReconciler; run Zp0063Generator and transmit.; 22:00 job: poll for ZP0064; run SettlementReconciler; run Zp0065Generator and Zp0066Generator; transmit both.; Unit test: mock batch_runs showing ZP0011 NOT TRANSMITTED; assert 05:00 job throws PREREQUISITE_NOT_MET and does not call Zp0061Generator.
**Deliverable:** BatchJobScheduler with cron wiring and dependency-check logic, plus unit tests.
**Acceptance / logic checks:**
- 05:00 job refuses to generate ZP0061 if ZP0011 batch_run status != TRANSMITTED — PREREQUISITE_NOT_MET thrown.
- Transactions with REGISTRATION_FAILED status are excluded from ZP0061 but other SETTLEMENT_REGISTERED transactions proceed.
- 22:00 job ZP0065/ZP0066 generation only runs after afternoon cycle (ZP0064) is received and reconciled.
- On BatchHaltException from any transmit step, the scheduler logs the failure and does NOT proceed to dependent jobs.
- All job runs are recorded in batch_runs table with job_type, scheduled_time, actual_start_time, status.
**Depends on:** 15.6-T07, 15.6-T08, 15.6-T09, 15.6-T10, 15.6-T11, 15.6-T12, 15.6-T13, 15.6-T14, 15.6-T15

### 15.6-T22 — Unit tests — ZP0011 file generation with exact input/output vectors  _(40 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. This ticket implements pure unit tests for ZP0011 generation logic using the QA-12 §3 synthetic test data. Partner P-TEST-001 is LOCAL/Domestic, P-TEST-002 is OVERSEAS/International. treasury.usd_krw=1350.00. Representative vectors: domestic txn D-001: merchant=M-TEST-0001, payout_amount_krw=13500, merchant_fee_amt=108 (0.80% of 13500), van_fee_amt=0, partner_type=D. International txn I-001: merchant=M-TEST-0004, payout_amount_krw=50000, merchant_fee_amt=850 (1.70% of 50000), van_fee_amt=100, partner_type=I. Both have real approval_code and zeropay_txn_ref populated.
**Steps:** Create ZeroPay test data builder helpers: buildDomesticTxn(merchantId, payoutKrw) and buildInternationalTxn(merchantId, payoutKrw).; Write test: generate ZP0011 for businessDate with [D-001, I-001]; assert 2 detail records.; Assert D-001 record: partner_type=D, payout_amount_krw=13500, merchant_fee_amt=108 (floor(13500*0.0080)=108).; Assert I-001 record: partner_type=I, payout_amount_krw=50000, merchant_fee_amt=850 (floor(50000*0.0170)=850).; Assert header total: totalPayoutKrw=63500, recordCount=2. Assert trailer controlSum=63500.; Write negative test: transaction with status=PENDING not included in generated file.
**Deliverable:** Unit test class ZP0011GeneratorTest with minimum 6 test methods and exact numeric assertions.
**Acceptance / logic checks:**
- D-001 merchant_fee_amt=108 (floor(13500 x 0.0080)); I-001 merchant_fee_amt=850 (floor(50000 x 0.0170)).
- header.recordCount=2 and header.totalPayoutKrw=63500 for [D-001, I-001] input.
- trailer.controlSum=63500 matching header total.
- Transaction with status=PENDING absent from output — zero-record file generated.
- File record_type field = 'D' (detail) on both records.
**Depends on:** 15.6-T07

### 15.6-T23 — Unit tests — ZP0012 reconciliation engine with exact mismatch vectors  _(40 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. This ticket implements focused unit tests for the four-case reconciliation logic in PaymentRegistrationReconciler using exact numeric inputs. Test vectors: (A) zeropay_txn_ref=ZP-TXNREF-0001, txn_date=20261015, sent payout=13500 KRW, ZP0012 result_code=00, registered_amount=13500 -> SETTLEMENT_REGISTERED. (B) ZP-TXNREF-0002, sent payout=50000, result_code=9002, registered_amount=49000 -> REGISTRATION_FAILED (non-zero code takes precedence over amount mismatch). (C) ZP-TXNREF-0003, in ZP0011 but absent from ZP0012 -> REGISTRATION_UNKNOWN. (D) ZP-TXNREF-0004, result_code=00 but registered_amount=13000 vs sent=13500 -> REGISTRATION_AMOUNT_MISMATCH. (E) ZP0012 contains ZP-TXNREF-0099 not in ZP0011 -> anomaly log, exception_record, no transaction status update.
**Steps:** Create ZP0011 and ZP0012 test fixtures using the vectors above (5 test cases A-E).; Test A: assert transaction ZP-TXNREF-0001 status=SETTLEMENT_REGISTERED, no exception_record.; Test B: assert ZP-TXNREF-0002 status=REGISTRATION_FAILED, one exception_record with type=REGISTRATION_FAILED.; Test C: assert ZP-TXNREF-0003 status=REGISTRATION_UNKNOWN, one exception_record.; Test D: assert ZP-TXNREF-0004 status=REGISTRATION_AMOUNT_MISMATCH, one exception_record; registered_amount=13000 and expected=13500 recorded on exception.; Test E: ZP-TXNREF-0099 (extra in ZP0012): exception_record created, type=ANOMALY_EXTRA_RECORD; no transaction updated.
**Deliverable:** Unit test class PaymentRegistrationReconcilerTest with 5+ test methods and exact assertions.
**Acceptance / logic checks:**
- Test A: no exception_record created; transaction status exactly SETTLEMENT_REGISTERED.
- Test B: result_code non-zero always yields REGISTRATION_FAILED regardless of amount comparison.
- Test C: REGISTRATION_UNKNOWN exception includes zeropay_txn_ref=ZP-TXNREF-0003 and businessDate=20261015.
- Test D: exception_record stores gme_amount=13500 and scheme_amount=13000 for the discrepancy.
- Test E: anomaly does not update any transaction status; exactly 1 exception_record of type ANOMALY_EXTRA_RECORD.
**Depends on:** 15.6-T12

### 15.6-T24 — Unit tests — net vs gross settlement calculation with exact KRW vectors  _(40 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. This ticket implements unit tests for the domestic-net vs international-gross settlement formula in Zp0061Generator using QA-12 §9.4 logic. Domestic (GME Remit, partner_type=D): net_settlement_amount = gross_txn_amount - refund_amount - merchant_fee_total; settlement_type=N. International (SendMN, partner_type=I): net_settlement_amount = gross_txn_amount - refund_amount; merchant_fee_total recorded but not deducted; settlement_type=G. Vectors: (1) Domestic M-TEST-0001: gross=100000 KRW, refund=5000, fee=800 -> net=94200. (2) Domestic M-TEST-0003: gross=80000, refund=0, fee=640 -> net=79360. (3) International M-TEST-0004: gross=200000, refund=10000, fee=3400 -> net=190000.
**Steps:** Write test for domestic M-TEST-0001: assert net_settlement_amount=94200, settlement_type=N.; Write test for domestic M-TEST-0003: assert net_settlement_amount=79360, settlement_type=N.; Write test for international M-TEST-0004: assert net_settlement_amount=190000, settlement_type=G; merchant_fee_total=3400 still on record.; Write edge case: merchant with gross=0 and refund=0 and fee=0; assert net=0, no exception.; Write edge case: refund > gross (e.g. gross=5000, refund=6000) for same merchant; assert negative net flagged as exception_record (should not occur in normal flow but guard exists).; Assert ZP0061File header grand_total = 94200 + 79360 + 190000 = 363560.
**Deliverable:** Unit test class Zp0061SettlementCalculationTest with 6+ test methods and exact KRW assertions.
**Acceptance / logic checks:**
- Domestic net=94200 (100000-5000-800=94200); international net=190000 (200000-10000=190000).
- International record has merchant_fee_total=3400 populated even though it is not deducted from net.
- File header grand_total=363560 == sum of all three net_settlement_amount values.
- Merchant with all-zero amounts produces net=0 record without throwing exception.
- Negative net (refund > gross) creates an exception_record and raises Ops alert.
**Depends on:** 15.6-T09

### 15.6-T25 — Unit tests — timing-window adherence with mocked clock  _(40 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. Per QA-12 §9.2, each outbound file must be transmitted before its deadline KST. Tests simulate batch runs at specific times using a mocked clock (inject a Clock or Instant into the batch scheduler). Test method: run batch at simulated time T; confirm SFTP PUT timestamp < deadline. Key timing windows to test: ZP0011 at 01:55 KST -> must complete before 02:00; ZP0061 at 04:55 KST -> must complete before 05:00. Also test the late-file detection: ZP0012 not present at 06:01 KST -> BATCH_FILE_LATE alert. ZP0064 not present at 20:01 KST -> BATCH_FILE_LATE alert.
**Steps:** Inject a mutable Clock into SftpInboundMonitor and BatchJobScheduler (constructor injection).; Write test TimingWindowTest.zp0011TransmittedBy0200: set clock to 01:55 KST; run 02:00 batch job; mock SFTP to complete instantly; assert transferResult.transferTimestamp < 02:00 KST.; Write test: set clock to 06:01 KST; ZP0012 not received; assert SftpInboundMonitor fires exactly one BATCH_FILE_LATE alert for ZP0012.; Write test: set clock to 06:00 KST exactly (boundary); assert no alert fired (alert fires only AFTER deadline+60min).; Write test: set clock to 05:59 KST; ZP0012 not received; assert no alert.; Write test: ZP0064 not received at 20:01 KST -> BATCH_FILE_LATE for ZP0064.
**Deliverable:** Unit test class TimingWindowTest with 6+ test methods using injected Clock.
**Acceptance / logic checks:**
- ZP0011 batch at 01:55 KST: transferTimestamp < 02:00:00 KST confirmed via mocked clock.
- BATCH_FILE_LATE alert fires at exactly clock=06:01 KST for ZP0012 (deadline 05:00 + 60min = 06:00; alert at 06:01).
- No alert fired at clock=06:00 KST (boundary is exclusive: alert fires when now > deadline+60min).
- ZP0064 late-file alert fires at 20:01 KST (deadline 19:00 + 60min = 20:00).
- opsAlertService.raiseAlert called exactly once per file type per businessDate even if check runs multiple times.
**Depends on:** 15.6-T11, 15.6-T21

### 15.6-T26 — Unit tests — SFTP retry and batch halt behaviour  _(40 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. Per SCH-06 §2.4, on SFTP PUT failure retry up to 3 times (delays 30s, 2min, 10min); after third failure alert Ops and throw BatchHaltException. The BatchHaltException must prevent dependent jobs from running. Retransmission uses incremented sequence number in filename (e.g. _02.dat.pgp). Tests use a mock SFTP client that can be configured to fail N times then succeed. These tests must run without real SFTP connectivity (no infrastructure dependency).
**Steps:** Write test SftpRetryTest.successOnThirdAttempt: mock SFTP fails twice then succeeds; assert TransferResult.success=true; assert attemptCount=3; assert opsAlertService NOT called.; Write test: mock SFTP fails all 3 times; assert BatchHaltException thrown; assert opsAlertService.raiseP1() called exactly once.; Write test: mock SFTP fails all 3 times; assert downstream job (Zp0061Generator) is NOT invoked.; Write test: retransmission (second call to transmit for same file + businessDate): assert filename contains _02.dat.pgp; first transmission was _01.dat.pgp.; Write test: checksum verification fails after successful PUT (remote checksum differs from local); assert TransferVerificationException; retry triggered.
**Deliverable:** Unit test class SftpRetryTest with 5+ test methods.
**Acceptance / logic checks:**
- 3 SFTP failures -> BatchHaltException and exactly 1 P1 Ops alert; no dependent job executed.
- 2 failures then success -> no alert; TransferResult.success=true; attemptCount=3.
- Second transmit call for same businessDate produces filename ending _02.dat.pgp.
- TransferVerificationException (checksum mismatch) triggers retry (same back-off sequence as IOException).
- Back-off delays are 30s, 120s, 600s in order (asserted via mock time/sleep tracking).
**Depends on:** 15.6-T10

### 15.6-T27 — Unit tests — inbound file idempotency and duplicate detection  _(40 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. Per SCH-06 §9.4, reprocessing the same inbound file (retransmission scenario) must be idempotent — no duplicate DB rows. This applies to ZP0012, ZP0022, ZP0062, ZP0064, and all merchant/QR sync files. Idempotency is achieved via upsert logic keyed on natural keys (e.g. zeropay_txn_ref + txn_date for ZP0012 records, merchant_id for merchant records, qr_code_id for QR records). Tests verify DB state after double-processing.
**Steps:** Write test Idempotency_ZP0012: process same ZP0012 file twice; assert transaction count and status unchanged after second processing; assert no duplicate exception_records.; Write test Idempotency_ZP0041: apply same ZP0041 delta twice; assert merchants table row count unchanged; updated fields reflect the values from the file.; Write test Idempotency_ZP0043: deactivate QR-TEST-0005 via ZP0043; replay same file; assert QR still INACTIVE, deactivation_date unchanged.; Write test Idempotency_ZP0061_settlement: run SettlementReconciler twice on same ZP0062; assert reconciliation_items count unchanged.; Write test: ZP0012 retransmitted with corrected amounts (original had error); second processing must update transaction status and amounts correctly (upsert, not insert-only).
**Deliverable:** Unit test class IdempotencyTest with 5+ test methods covering all inbound file types.
**Acceptance / logic checks:**
- ZP0012 double-processing: transaction status = SETTLEMENT_REGISTERED after both runs; exception_records count unchanged.
- ZP0041 double-processing: merchants table has same row count after second run; merchant_name reflects latest file value.
- QR deactivation idempotency: replaying deactivation does not toggle status to ACTIVE.
- SettlementReconciler double-run: reconciliation_items for businessDate has same count after second run.
- ZP0012 retransmission with corrected amount: transaction registered_amount updated to new value from corrected file.
**Depends on:** 15.6-T12, 15.6-T16, 15.6-T18, 15.6-T14

### 15.6-T28 — Integration test — ZP0011/ZP0012 payment round-trip happy path  _(55 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. This end-to-end integration test covers the full payment registration round-trip: seed transactions in DB -> generate ZP0011 -> transmit via mock SFTP -> inject synthetic ZP0012 response -> reconcile -> verify final transaction statuses. Uses QA-12 scenario ZP-001 and synthetic partners P-TEST-001 (domestic) and P-TEST-002 (international) from QA-12 §3.1. treasury.usd_krw=1350 from §3.3. Three transactions: D-001 (domestic, payout 13500 KRW, M-TEST-0001), I-001 (international, payout 50000 KRW, M-TEST-0004), I-002 (international, payout 20000 KRW, M-TEST-0004). ZP0012 confirms all three with result_code=00 and matching amounts.
**Steps:** Seed DB: 3 transactions with status=APPROVED for businessDate=20261015 using test fixtures.; Run Zp0011Generator for 20261015; assert 3 records in generated file.; Inject mock SFTP: transmit ZP0011; assert batch_runs ZP0011 status=TRANSMITTED.; Inject synthetic ZP0012 with result_code=00 for all 3 transactions; amounts matching.; Run PaymentRegistrationReconciler; assert all 3 transactions status=SETTLEMENT_REGISTERED.; Assert no exception_records exist for businessDate=20261015; assert batch_runs ZP0012 status=PROCESSED.
**Deliverable:** Integration test class ZP001112RoundTripTest with full happy-path scenario.
**Acceptance / logic checks:**
- Generated ZP0011 has exactly 3 records; header.totalPayoutKrw=83500 (13500+50000+20000).
- All 3 transactions status=SETTLEMENT_REGISTERED after ZP0012 processing.
- Zero exception_records for businessDate=20261015.
- Batch_runs table has ZP0011 status=TRANSMITTED and ZP0012 status=PROCESSED.
- partner_type=D for D-001 and partner_type=I for I-001/I-002 in ZP0011 records.
**Depends on:** 15.6-T07, 15.6-T12, 15.6-T22, 15.6-T23

### 15.6-T29 — Integration test — ZP0011/ZP0012 registration failure and exception queue  _(50 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. QA-12 scenario ZP-012: ZeroPay returns failure in ZP0012 for one record. Seed: 3 transactions (same as T28). ZP0012 response: result_code=00 for T001 and T003; result_code=9001 (batch registration failure) for T002. Expected: T002 status=REGISTRATION_FAILED; exception_record created; Ops P1 alert raised; T002 blocked from ZP0061 settlement; T001 and T003 proceed to SETTLEMENT_REGISTERED and are eligible for ZP0061.
**Steps:** Seed 3 transactions, generate and transmit ZP0011 (status=TRANSMITTED).; Inject ZP0012 with result_code=00 for T001/T003, result_code=9001 for T002.; Run PaymentRegistrationReconciler.; Assert T001 and T003 status=SETTLEMENT_REGISTERED.; Assert T002 status=REGISTRATION_FAILED; exactly 1 exception_record with priority=P1; opsAlertService.raiseP1() called once.; Run Zp0061Generator: assert generated file contains only T001 and T003 (T002 excluded); ZP0061 record count=2.
**Deliverable:** Integration test class ZP0012FailureTest with exception queue and partial settlement coverage.
**Acceptance / logic checks:**
- T002 status=REGISTRATION_FAILED; 1 exception_record created with correct zeropay_txn_ref.
- Exactly 1 P1 Ops alert raised (not 2 or 0).
- ZP0061 generated with 2 records only (T002 excluded); header.recordCount=2.
- T001 and T003 proceed to SETTLEMENT_REGISTERED status unaffected by T002 failure.
- Exception_record for T002 has gme_txn_id, businessDate=20261015, and result_code=9001 stored.
**Depends on:** 15.6-T12, 15.6-T09, 15.6-T28

### 15.6-T30 — Integration test — ZP0021/ZP0022 refund round-trip  _(45 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. QA-12 scenario ZP-002: refund result submission and confirmation. Seed: 2 admin refunds for businessDate=20261015 against settled transactions; 1 same-day cancel (cancel_time same KST date as txn_date). ZP0022 confirms both refunds with result_code=00, matching amounts. Expected: ZP0021 contains 2 records (same-day cancel excluded); both refunds reconciled to SETTLEMENT_REGISTERED; ZP0061 refund portion includes both. Refund fields: gme_refund_id, original_zeropay_txn_ref, refund_amount_krw, merchant_fee_adj_amt, refund_reason_code.
**Steps:** Seed 2 admin refunds (R-001 refund_amount_krw=5000, R-002 refund_amount_krw=3000) and 1 same-day cancel.; Run Zp0021Generator for 20261015; assert 2 records (same-day cancel excluded).; Inject synthetic ZP0022 confirming both refunds (result_code=00, registered_refund_amount matching).; Run RefundRegistrationReconciler; assert R-001 and R-002 status=SETTLEMENT_REGISTERED.; Assert batch_runs ZP0021=TRANSMITTED and ZP0022=PROCESSED; no exception_records.; Assert ZP0061 refund_count=2 and refund_amount=8000 on the affected merchant records.
**Deliverable:** Integration test class ZP002122RoundTripTest.
**Acceptance / logic checks:**
- ZP0021 record count=2; same-day cancel not present.
- R-001 and R-002 both status=SETTLEMENT_REGISTERED; zero exception_records.
- ZP0061 merchant record for the affected merchant: refund_count=2, refund_amount=8000.
- R-001 Zp0021Record.original_zeropay_txn_ref matches the original transaction zeropay_txn_ref.
- ZP0022 batch_run status=PROCESSED after reconciliation.
**Depends on:** 15.6-T08, 15.6-T13, 15.6-T09

### 15.6-T31 — Integration test — ZP0061/ZP0062 morning settlement round-trip with reconciliation  _(50 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. QA-12 scenario ZP-003: morning settlement cycle ZP0061 generation, transmission, ZP0062 receipt, and reconciliation. Prerequisite states: ZP0011 TRANSMITTED, ZP0012 PROCESSED (all records SETTLEMENT_REGISTERED). Seed: 3 merchants (domestic M-TEST-0001 gross=100000 refund=5000 fee=800; domestic M-TEST-0003 gross=80000 refund=0 fee=640; international M-TEST-0004 gross=200000 refund=10000 fee=3400). Inject ZP0062 confirming M-TEST-0001 credited=94200, M-TEST-0003 credited=79360, M-TEST-0004 credited=190000 (all matching).
**Steps:** Set batch_runs ZP0011=TRANSMITTED and ZP0012=PROCESSED as prerequisite fixtures.; Run Zp0061Generator; assert 3 merchant records with correct net amounts (94200, 79360, 190000).; Transmit ZP0061 via mock SFTP; assert batch_runs ZP0061=TRANSMITTED.; Inject ZP0062 with all 3 merchants confirmed at exact amounts.; Run SettlementReconciler; assert all transactions for these merchants status=SETTLEMENT_CONFIRMED.; Assert no exception_records; batch_runs ZP0062=PROCESSED; reconciliation_items count=3.
**Deliverable:** Integration test class ZP006162RoundTripTest.
**Acceptance / logic checks:**
- ZP0061 record for M-TEST-0001: settlement_type=N, net_settlement_amount=94200.
- ZP0061 record for M-TEST-0004: settlement_type=G, net_settlement_amount=190000, merchant_fee_total=3400.
- ZP0062 reconciliation: all 3 merchants SETTLEMENT_CONFIRMED; zero exception_records.
- Prerequisite check: if ZP0011 not TRANSMITTED, Zp0061Generator throws PREREQUISITE_NOT_MET.
- Grand total in ZP0061 header = 94200+79360+190000=363560.
**Depends on:** 15.6-T09, 15.6-T14, 15.6-T24

### 15.6-T32 — Integration test — ZP0062 settlement discrepancy and exception handling  _(45 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. QA-12 scenario ZP-013: discrepancy in reconciliation file. ZP0062 returns a credited_amount that differs from the ZP0061 net_settlement_amount for one merchant. Seed: same 3-merchant setup as T31. ZP0062 confirms M-TEST-0001=94200 (correct) and M-TEST-0003=79360 (correct) but M-TEST-0004 credited=188000 vs expected 190000 (2000 KRW discrepancy). Expected: SETTLEMENT_DISCREPANCY exception_record for M-TEST-0004; P1 alert raised; M-TEST-0004 transactions set to SETTLEMENT_DISCREPANCY status; M-TEST-0001 and M-TEST-0003 proceed to SETTLEMENT_CONFIRMED normally.
**Steps:** Set up settlement prerequisites (ZP0011 TRANSMITTED, ZP0012 PROCESSED, ZP0061 TRANSMITTED).; Inject ZP0062 with M-TEST-0004 credited_amount=188000 (expected 190000).; Run SettlementReconciler.; Assert M-TEST-0001 and M-TEST-0003 -> SETTLEMENT_CONFIRMED; no exceptions for them.; Assert M-TEST-0004 -> SETTLEMENT_DISCREPANCY; 1 exception_record with gme_amount=190000, scheme_amount=188000, discrepancy=2000.; Assert opsAlertService.raiseP1() called once with reference to M-TEST-0004.
**Deliverable:** Integration test class ZP0062DiscrepancyTest.
**Acceptance / logic checks:**
- M-TEST-0004 exception_record created with gme_amount=190000 and scheme_amount=188000.
- Exactly 1 P1 alert raised (for M-TEST-0004 only).
- M-TEST-0001 and M-TEST-0003 SETTLEMENT_CONFIRMED without exception.
- SETTLEMENT_DISCREPANCY status persists on M-TEST-0004 transactions until manually resolved (status not auto-corrected).
- ReconciliationResult.discrepancyCount=1 and ReconciliationResult.confirmedCount=2 returned.
**Depends on:** 15.6-T14, 15.6-T31

### 15.6-T33 — Integration test — SFTP transmission failure with retry and batch halt  _(50 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. QA-12 scenario ZP-011: SFTP transmission failure for ZP0011. Per SCH-06 §2.4, on 3 consecutive SFTP failures: alert Ops and halt — settlement must NOT be blocked by the first retry (the retry mechanism itself provides the continuation path). Tests confirm: (a) partial retry success does not duplicate records; (b) full retry exhaustion halts ZP0061 generation; (c) retransmission after manual ops intervention uses sequence _02.
**Steps:** Mock SFTP to fail 3 times for ZP0011 PUT; run 02:00 batch; assert BatchHaltException; opsAlertService.raiseP1() called; ZP0011 batch_run status=FAILED.; Assert ZP0061 generation (05:00 job) is blocked because ZP0011 batch_run is not TRANSMITTED.; Simulate manual retransmission: mark ZP0011 as TRANSMITTED in batch_runs (simulating ops manual re-send); run 05:00 job; assert ZP0061 generated normally.; Retransmission filename test: assert second attempt filename = ZP0011_20261015_02.dat.pgp.; Test partial success (2 failures then success on 3rd): no P1 alert; ZP0011 batch_run=TRANSMITTED; ZP0061 generates normally at 05:00.
**Deliverable:** Integration test class SftpFailureAndRetryTest with 5 scenarios.
**Acceptance / logic checks:**
- 3 consecutive SFTP failures: BatchHaltException thrown; opsAlertService.raiseP1() once; ZP0011 batch_run=FAILED.
- ZP0061 blocked (PREREQUISITE_NOT_MET) when ZP0011 batch_run=FAILED.
- Manual ops override (setting ZP0011=TRANSMITTED) unblocks ZP0061 generation.
- Retransmission filename ends _02.dat.pgp.
- 2 failures + 1 success: no P1 alert; ZP0011 batch_run=TRANSMITTED.
**Depends on:** 15.6-T10, 15.6-T21, 15.6-T26

### 15.6-T34 — Integration test — late inbound file handling and waiting-state behaviour  _(50 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. QA-12 scenario ZP-014: inbound file received after expected window. Per SCH-06 §9.3: if expected file absent after deadline+60min, raise BATCH_FILE_LATE alert; place dependent batch jobs in WAITING state (not FAILED); Ops may manually trigger downstream jobs once file received. Test: ZP0012 not present at 06:01 KST. ZP0061 job at 05:00 attempts to run but prerequisite not met (ZP0012 not PROCESSED) -> ZP0061 waits. At 06:30 KST (simulated) ZP0012 arrives and is processed -> Ops triggers ZP0061 manually. ZP0061 generates and transmits successfully.
**Steps:** Set clock to 06:01 KST; ZP0012 not present; assert SftpInboundMonitor fires BATCH_FILE_LATE for ZP0012.; Assert ZP0061 job status = WAITING (not FAILED) in batch_runs.; Inject ZP0012 at simulated time 06:30 KST; run PaymentRegistrationReconciler; assert ZP0012 batch_run=PROCESSED.; Simulate manual Ops trigger of ZP0061 job; assert ZP0061 generates normally and batch_run=TRANSMITTED.; Assert 'previous-day data flag' is set on ZP0012 batch_run (indicating late processing per QA-12 §5.5 ZP-014).; Assert BATCH_FILE_LATE alert fired exactly once (not repeated on each poll).
**Deliverable:** Integration test class LateFileHandlingTest.
**Acceptance / logic checks:**
- BATCH_FILE_LATE alert fires at clock=06:01 KST for ZP0012 (deadline 05:00+60min=06:00; fires when now > 06:00).
- ZP0061 job status=WAITING when prerequisite ZP0012 not PROCESSED (not FAILED).
- Late ZP0012 processed at 06:30 KST successfully (all records reconciled).
- Manual trigger of ZP0061 after late processing completes successfully.
- BATCH_FILE_LATE alert not duplicated (alert fired only once despite multiple poll cycles with no file).
**Depends on:** 15.6-T11, 15.6-T21, 15.6-T25

### 15.6-T35 — Integration test — merchant sync then payment validation (ZP0041 sync-then-pay)  _(50 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. QA-12 scenario ZP-006 combined with HC-011/HC-012: after applying merchant delta sync, payments to affected merchants reflect the new state immediately. Tests: (1) New merchant inserted via ZP0041 -> payment to new merchant succeeds. (2) Merchant deactivated via ZP0041 -> payment returns MERCHANT_INACTIVE immediately. (3) QR deactivated via ZP0043 -> payment returns QR_DEACTIVATED. Uses synthetic merchants M-TEST-0001 (active), M-TEST-0002 (to be deactivated), and QR-TEST-0005 (to be deactivated).
**Steps:** Seed DB with M-TEST-0001 (ACTIVE), M-TEST-0002 (ACTIVE), QR-TEST-0001 (ACTIVE, merchant M-TEST-0001), QR-TEST-0005 (ACTIVE, merchant M-TEST-0005).; Apply ZP0041 delta: change_type=D for M-TEST-0002; change_type=I for new merchant M-NEW-0006.; Attempt payment for M-TEST-0002; assert MERCHANT_INACTIVE error.; Attempt payment for M-NEW-0006; assert payment proceeds to APPROVED (or route to scheme).; Apply ZP0043 delta: change_type=D for QR-TEST-0005.; Attempt payment with QR-TEST-0005; assert QR_DEACTIVATED error immediately.
**Deliverable:** Integration test class SyncThenPayTest covering merchant and QR deactivation payment blocking.
**Acceptance / logic checks:**
- Payment to deactivated M-TEST-0002 returns MERCHANT_INACTIVE; error returned within same DB transaction as deactivation.
- Payment to newly inserted M-NEW-0006 proceeds (not rejected as MERCHANT_NOT_FOUND).
- Payment referencing deactivated QR-TEST-0005 returns QR_DEACTIVATED immediately after ZP0043 processing.
- No caching delays: deactivation is effective immediately on next payment validation call.
- Idempotency: re-applying same ZP0041 deactivation does not change merchant status from INACTIVE to anything else.
**Depends on:** 15.6-T16, 15.6-T18

### 15.6-T36 — Integration test — ZP0051 full merchant sync with rollback on error  _(50 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. QA-12 scenario ZP-008: full merchant list sync replaces entire local dataset atomically. Tests: (1) Happy path: ZP0051 with 4 valid merchants replaces 5-merchant live table (one removed, one added). (2) Error path: DB failure mid-promote -> live table unchanged. (3) Supersession: ZP0041 delta skipped when ZP0051 present. Seed live merchants: M-TEST-0001 through M-TEST-0005. ZP0051 contains M-TEST-0001, M-TEST-0002 (reactivated), M-TEST-0003, M-TEST-0004 (drops M-TEST-0005; adds M-NEW-0006).
**Steps:** Seed live merchants: M-TEST-0001 ACTIVE, M-TEST-0002 INACTIVE (previously deactivated), M-TEST-0003 ACTIVE, M-TEST-0004 ACTIVE, M-TEST-0005 ACTIVE.; Prepare ZP0051 with M-TEST-0001/0002/0003/0004 and M-NEW-0006 (no M-TEST-0005).; Run Zp0051FullSyncHandler; assert after sync: M-TEST-0002 status=ACTIVE (reactivated from full list), M-TEST-0005 status=INACTIVE (absent from full list), M-NEW-0006 inserted.; Error path test: inject DB failure during staging promote; assert live table still has original 5 merchants; merchant_sync_log records FAILED.; Supersession test: provide both ZP0041 and ZP0051 for same night; assert only ZP0051 applied; ZP0041 skipped.
**Deliverable:** Integration test class Zp0051FullSyncTest with happy path, rollback, and supersession scenarios.
**Acceptance / logic checks:**
- After ZP0051 sync: M-TEST-0005 status=INACTIVE; M-NEW-0006 inserted ACTIVE; M-TEST-0002 status=ACTIVE (full list implies reactivation).
- DB failure during promote: live merchants table unchanged (rollback confirmed by querying M-TEST-0001 still exists with original data).
- Merchant_sync_log FAILED row created on DB error; Ops alert raised.
- ZP0041 delta skipped when ZP0051 present in same processing run (suppression flag set).
- ZP0051 header record_count mismatch: CorruptFileException; sync aborted; live table unchanged.
**Depends on:** 15.6-T19, 15.6-T16

### 15.6-T37 — Integration test — ZP0065/ZP0066 detail file generation and ledger reconciliation  _(45 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. QA-12 scenario ZP-005: settlement detail files ZP0065 and ZP0066 generated at 22:00 KST and reconciled against internal ledger. Seed: 5 transactions (3 settled in morning ZP0061 batch, 2 in afternoon ZP0063 batch) and 2 refunds (both in morning batch). All have status=SETTLEMENT_CONFIRMED. ZP0065 should have 5 records with correct settlement_batch_ref. ZP0066 should have 2 records. Internal ledger reconciliation: compare each ZP0065 line against transactions table payout_amount_krw.
**Steps:** Seed 5 transactions and 2 refunds with SETTLEMENT_CONFIRMED status; assign batch refs (3 to ZP0061_BATCHREF_001, 2 to ZP0063_BATCHREF_001).; Run Zp0065Generator and Zp0066Generator for businessDate=20261015.; Assert ZP0065 record count=5; ZP0066 record count=2.; Assert transactions in morning batch have settlement_batch_ref=ZP0061_BATCHREF_001; afternoon have ZP0063_BATCHREF_001.; Run DetailLedgerReconciler; assert zero discrepancies; reconciliation_items count=7 (5+2).; Inject one tampered transaction (alter DB payout_amount_krw after generation); rerun reconciler; assert 1 discrepancy exception.
**Deliverable:** Integration test class Zp006566DetailTest.
**Acceptance / logic checks:**
- ZP0065 record count=5; ZP0066 record count=2.
- settlement_batch_ref correctly assigned to ZP0061 ref for morning transactions and ZP0063 ref for afternoon.
- Zero discrepancies on first reconciliation run.
- Tampered payout_amount_krw (+100 KRW) detected as discrepancy; exception_record created.
- reconciliation_items count=7 after successful full reconciliation run.
**Depends on:** 15.6-T15, 15.6-T28, 15.6-T31

### 15.6-T38 — Integration test — full daily batch round-trip from 02:00 to 22:00 KST  _(60 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. QA-12 §5.5 full ZeroPay batch round-trip: all batch windows exercised in sequence. This is the primary end-to-end batch test that exercises the complete daily cycle. Uses clock injection to simulate each batch window. Seed: 4 transactions (2 domestic P-TEST-001, 2 international P-TEST-002) and 1 admin refund, all with status=APPROVED for businessDate=20261015. Inject synthetic responses at each step. All files generated and transmitted within windows; all confirmations received and reconciled. Final state: all transactions SETTLEMENT_CONFIRMED.
**Steps:** Seed DB with 4 approved transactions and 1 admin refund.; Simulate 01:55 KST: run ZP0041 merchant delta (no changes); ZP0043 QR delta (no changes).; Simulate 02:00 KST: run ZP0011 generator (4 records) and ZP0021 (1 record); transmit via mock SFTP.; Simulate 04:50 KST: inject ZP0012 (all 4 success) and ZP0022 (1 success); run reconcilers.; Simulate 05:00 KST: run ZP0061 generator (per-merchant net amounts); transmit.; Simulate 10:00 KST: inject ZP0062 (all merchants confirmed); reconcile -> SETTLEMENT_CONFIRMED.; Simulate 14:00 KST: run ZP0063 (0 records if no afternoon txns); transmit; inject ZP0064 immediately.; Simulate 22:00 KST: run ZP0065 (4 records) and ZP0066 (1 record); transmit; run detail reconciler.
**Deliverable:** Integration test class FullDailyBatchRoundTripTest — the master batch cycle test.
**Acceptance / logic checks:**
- ZP0011 transmitted by simulated 02:00 KST with 4 records.
- All 4 transactions status=SETTLEMENT_CONFIRMED after ZP0062 reconciliation.
- ZP0065 contains 4 records linked to correct settlement batch refs.
- batch_runs table has rows for all 10 file types with status=TRANSMITTED or PROCESSED as appropriate.
- Zero exception_records for businessDate=20261015 on happy path.
**Depends on:** 15.6-T21, 15.6-T28, 15.6-T30, 15.6-T31, 15.6-T37

### 15.6-T39 — Write batch test report template and document test execution procedure  _(45 min)_
**Context:** WBS 15.6 — ZeroPay batch round-trip testing. Parent deliverable for WBS 15.6 is the Batch Test Report. The report must document: (1) test environment configuration (ZeroPay mock SFTP, synthetic partners P-TEST-001 through P-TEST-005, treasury rates), (2) test scenario coverage matrix mapping ZP-001 through ZP-014 from QA-12 §5.5 against the test tickets, (3) timing-window adherence results table (one row per file type with deadline, simulated execution time, pass/fail), (4) exception scenario results (ZP-011 SFTP failure, ZP-012 registration rejection, ZP-013 discrepancy, ZP-014 late file), (5) reconciliation results (registration, settlement, detail levels per QA-12 §9). Report confirms all ZP006x files transmitted within window per QA-12 §1.4 (100% on-time target).
**Steps:** Create BatchTestReport.md template with sections: Environment, Test Coverage Matrix, Timing-Window Results, Exception Scenario Results, Reconciliation Results, Open Items, Sign-off.; Populate Coverage Matrix with all 14 QA-12 §5.5 ZP-xxx scenarios mapped to ticket IDs (15.6-T28 through 15.6-T38).; Add Timing-Window table with all 10 outbound/inbound file deadlines and pass criteria (e.g. ZP0011 deadline 02:00 KST, tested via T25).; Add Exception Scenarios section with expected and actual columns for ZP-011 through ZP-014.; Add Reconciliation Results section per QA-12 §9: registration, settlement, and detail levels with zero-tolerance criterion.; Specify test execution command (e.g. mvn test -Dtest=*RoundTrip*,*Discrepancy*,*LateFile*,*FullDaily*) and prerequisite checklist (mock SFTP configured, test DB seeded).
**Deliverable:** BatchTestReport.md template at docs/test-reports/batch/BatchTestReport.md with all sections populated.
**Acceptance / logic checks:**
- All 14 QA-12 ZP-xxx scenarios (ZP-001 through ZP-014) appear in the Coverage Matrix with at least one ticket ID mapped.
- Timing-Window table has all 10 file types with deadline KST, test method reference, and pass criterion.
- QA-12 §1.4 success metric (100% on-time settlement batch delivery) appears as a top-level acceptance criterion in the report.
- Exception scenario section has rows for ZP-011 (SFTP failure), ZP-012 (registration rejection), ZP-013 (discrepancy), ZP-014 (late file).
- Sign-off section has fields for QA Lead, GME Ops Settlement, and date; report not considered complete until signed.
**Depends on:** 15.6-T25, 15.6-T26, 15.6-T28, 15.6-T29, 15.6-T30, 15.6-T31, 15.6-T32, 15.6-T33, 15.6-T34, 15.6-T35, 15.6-T36, 15.6-T37, 15.6-T38


## WBS 15.7 — Integration & end-to-end testing
### 15.7-T01 — Seed test database with synthetic partners, merchants, treasury rates, and prefunding  _(35 min)_
**Context:** QA-12 §3 defines the required test fixtures. Synthetic partners: P-TEST-001 (TestRemit, LOCAL, KRW, domestic), P-TEST-002 (TestSendMN, OVERSEAS, USD, 50000 USD prefunding), P-TEST-003 (TestHub, OVERSEAS, USD, 100000 USD), P-TEST-004 (TestManual, OVERSEAS, EUR), P-TEST-005 (TestPartnerB, OVERSEAS, USD). Merchants: M-TEST-0001 (Active, 0.80%), M-TEST-0002 (Inactive, 0.80%), M-TEST-0003 (Franchise Active, 1.20%), M-TEST-0004 (Active, 1.70%), M-TEST-0005 (Active, QR-TEST-0005 deactivated). Treasury rates: treasury.usd_krw=1350.00, treasury.usd_mnt=3500.00, treasury.usd_usd=1.0000, treasury.usd_eur=0.9200, treasury.usd_thb=35.500. Prefunding states for P-TEST-002: Normal=50000.00, Low=9500.00, Depleted=0.00; P-TEST-003: Normal=100000.00.
**Steps:** Create a SQL seed script (test-fixtures/seed_qa12.sql) with INSERT statements for all 5 synthetic partners and their API credentials; Insert all 5 merchant records with correct fee rates and QR codes; mark QR-TEST-0005 as deactivated; Insert all 5 treasury rate rows in the treasury_rates table; Insert prefunding balance rows for P-TEST-002 (50000.00 USD) and P-TEST-003 (100000.00 USD); Create a second script test-fixtures/seed_qa12_edge_states.sql for low-balance (9500.00) and depleted (0.00) states for P-TEST-002; Verify the scripts are idempotent (use INSERT ... ON CONFLICT DO NOTHING or equivalent)
**Deliverable:** test-fixtures/seed_qa12.sql and test-fixtures/seed_qa12_edge_states.sql
**Acceptance / logic checks:**
- Running seed_qa12.sql twice produces no error and leaves exactly 5 partner rows, 5 merchant rows, 5 treasury rate rows
- P-TEST-002 prefunding balance is 50000.00 USD after running seed_qa12.sql
- QR-TEST-0005 status is deactivated; QR-TEST-0001 through QR-TEST-0004 are active
- treasury.usd_krw=1350.00 and treasury.usd_mnt=3500.00 are present and queryable
- Running seed_qa12_edge_states.sql sets P-TEST-002 balance to 9500.00 without affecting other partners

### 15.7-T02 — Configure synthetic partner-scheme rules in Admin for all test partners via API or migration  _(35 min)_
**Context:** QA-12 §3.5 requires all synthetic partners to have ZeroPay rules configured in Admin before E2E tests run. P-TEST-001: ZeroPay Domestic direction, m_a=0%, m_b=0%, service_charge=500 KRW, same-currency short-circuit. P-TEST-002: ZeroPay Inbound direction, m_a=1.5%, m_b=1.0%, service_charge=500 MNT. P-TEST-003: ZeroPay Hub direction, m_a=1.5%, m_b=1.0%, service_charge=500 MNT. P-TEST-004: ZeroPay Inbound, MANUAL rate source override, m_a=1.5%, m_b=1.0%. P-TEST-005: ZeroPay Inbound, PARTNER B rate source, m_a=1.5%, m_b=1.0%. For cross-border rules m_a+m_b must be >= 2.0%. Domestic rule allows 0%. Each config change must be audit-logged with actor and timestamp.
**Steps:** Using Admin API (or direct DB migration), create ZeroPay Domestic rule for P-TEST-001 with m_a=0.0, m_b=0.0, service_charge=500 KRW; Create ZeroPay Inbound rule for P-TEST-002 with m_a=0.015, m_b=0.010, service_charge=500 MNT, rate_source=LIVE; Create ZeroPay Hub rule for P-TEST-003 with m_a=0.015, m_b=0.010, service_charge=500 MNT; Create ZeroPay Inbound rule for P-TEST-004 with m_a=0.015, m_b=0.010, rate_source=MANUAL; Create ZeroPay Inbound rule for P-TEST-005 with m_a=0.015, m_b=0.010, rate_source=PARTNER_B; Verify all 5 rules appear in the Admin rule list with correct parameters and audit log entries
**Deliverable:** test-fixtures/seed_qa12_rules.sql (or Admin API setup script) and verified rule state in test DB
**Acceptance / logic checks:**
- All 5 rules are queryable via Admin rule-list endpoint with correct m_a, m_b values
- P-TEST-001 rule has rate_source=IDENTITY and combined margin=0.0%
- P-TEST-002 rule has combined margin=2.5% (1.5+1.0) which passes the 2.0% minimum check
- Audit log contains one entry per rule creation with actor and timestamp
- P-TEST-004 rule has rate_source=MANUAL; P-TEST-005 has rate_source=PARTNER_B
**Depends on:** 15.7-T01

### 15.7-T03 — Implement E2E test harness: base class, environment config, and shared test fixtures  _(55 min)_
**Context:** The E2E test suite requires a harness that can start against the sandbox environment (E2 or local docker-compose), authenticate as different synthetic partners, seed/reset state between tests, and capture webhooks for assertion. Partners authenticate via POST /v1/auth/token. Webhooks (payment.approved, payment.pending_debit, payment.failed, payment.cancelled) must be received by a local stub server. Tests must be isolated: each test resets prefunding to the fixture state.
**Steps:** Create test/e2e/base/E2ETestBase class (or equivalent) that reads BASE_URL, partner credentials from environment variables; Implement a webhook stub server (e.g. WireMock or simple HTTP server) that captures payloads by event type; Implement helper methods: authenticateAsPartner(partnerId), resetPrefunding(partnerId, amount), getBalance(partnerId); Implement helper: waitForWebhook(eventType, txnId, timeoutMs=5000) that polls the stub and asserts payload fields; Add a docker-compose.test.yml that wires Hub Core, DB, and stub webhook server; Document in a README.e2e.md how to run: docker-compose up + mvn test -Psuite=e2e (or equivalent)
**Deliverable:** test/e2e/base/E2ETestBase.java (or equivalent), webhook stub, docker-compose.test.yml, README.e2e.md
**Acceptance / logic checks:**
- authenticateAsPartner(P-TEST-001) returns a non-null Bearer token within 2 seconds
- resetPrefunding sets balance to exactly the supplied amount; subsequent getBalance returns the same value
- waitForWebhook times out with a clear assertion error if no webhook arrives within 5000 ms
- docker-compose.test.yml starts cleanly from a clean state with no manual steps beyond docker-compose up
- README.e2e.md contains exact commands to run the full E2E suite locally
**Depends on:** 15.7-T01, 15.7-T02

### 15.7-T04 — E2E test HC-001: MPM Domestic payment happy path (GME Remit / ZeroPay)  _(45 min)_
**Context:** Scenario HC-001 from QA-12 §5.1. Partner P-TEST-001 (LOCAL, KRW). Flow: GET /v1/rates with target_payout=13500 KRW, direction=Domestic; then POST /v1/payments with the returned quote_id. Same-currency short-circuit applies: USD pool is skipped, collection_amount = target_payout + service_charge = 13500 + 500 = 14000 KRW. No prefunding deduction for LOCAL partners. Webhook payment.approved must arrive. Rate lock: rates recorded at commit time; later treasury changes do not affect this transaction.
**Steps:** Call GET /v1/rates as P-TEST-001 with params: target_payout=13500, payout_ccy=KRW, direction=Domestic; assert 200 response and validUntil is in the future; Assert response contains no USD pool fields (collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd must be absent or null); Call POST /v1/payments with the returned quote_id, merchant_id=M-TEST-0001, idempotency_key=MPM-DOM-001; Wait for payment.approved webhook; assert txn_id matches, status=APPROVED, collection_amount=14000 KRW; Query GET /v1/payments/{id}; assert collection_amount=14000, offer_rate_coll is null, target_payout=13500, service_charge=500; Assert P-TEST-001 has no prefunding record affected (LOCAL partner, no deduction expected)
**Deliverable:** test/e2e/hc/HC001_MpmDomesticHappyPathTest.java (or equivalent)
**Acceptance / logic checks:**
- GET /v1/rates response has no USD pool fields and offer_rate_coll is null for same-currency short-circuit
- collection_amount in payment response equals exactly 13500 + 500 = 14000 KRW
- payment.approved webhook received within 5000 ms with txn_id and status=APPROVED
- GET /v1/payments/{id} shows service_charge=500 KRW recorded separately from target_payout
- No prefunding deduction occurs for LOCAL partner P-TEST-001
**Depends on:** 15.7-T03

### 15.7-T05 — E2E test HC-002: CPM Domestic payment happy path (GME Remit / ZeroPay)  _(45 min)_
**Context:** Scenario HC-002 from QA-12 §5.1. Partner P-TEST-001 (LOCAL, KRW). CPM flow: POST /v1/payments/cpm/generate returns QR token; scheme scans QR; system emits payment.pending_debit with offer_rate; final commit triggers payment.approved. Same-currency short-circuit: USD pool skipped. Required webhook fields per QA-12 §7.3: payment.pending_debit must contain txn_id, offer_rate, collection_amount, validUntil; payment.approved must contain txn_id, status, collection_amount, cross_rate. For domestic, offer_rate and cross_rate are null/not-applicable.
**Steps:** POST /v1/payments/cpm/generate as P-TEST-001 with target_payout=13500 KRW, merchant_id=M-TEST-0001; Assert 200 response with qr_token present and non-null; record txn_id; Simulate scheme scan (call internal test endpoint or ZeroPay stub to trigger payment approval); Wait for payment.pending_debit webhook; assert txn_id matches, collection_amount=14000 KRW; Wait for payment.approved webhook; assert txn_id matches, status=APPROVED; Assert no prefunding deduction occurred (LOCAL partner)
**Deliverable:** test/e2e/hc/HC002_CpmDomesticHappyPathTest.java (or equivalent)
**Acceptance / logic checks:**
- POST /v1/payments/cpm/generate returns qr_token within 3 seconds
- payment.pending_debit webhook contains txn_id, collection_amount=14000 KRW, validUntil in the future
- payment.approved webhook arrives with status=APPROVED and collection_amount=14000 KRW
- No USD pool fields appear in any response or webhook payload for domestic transaction
- For LOCAL partner, no prefunding balance change is recorded
**Depends on:** 15.7-T03

### 15.7-T06 — E2E test HC-003: MPM Inbound payment happy path (OVERSEAS partner SendMN)  _(50 min)_
**Context:** Scenario HC-003 from QA-12 §5.1. Partner P-TEST-002 (OVERSEAS, USD, initial balance 50000.00 USD). Cross-border inbound flow (RV-01 formula): target_payout=13500 KRW, cost_rate_pay=1350.00, cost_rate_coll=3500.00 (MNT), m_a=0.015, m_b=0.010, service_charge=500 MNT. Expected: payout_usd_cost=10.0000, collection_usd=10.2564, collection_margin_usd=0.1538, payout_margin_usd=0.1026, send_amount=35897.44 MNT, collection_amount=36397.44 MNT. Prefunding deducted = collection_usd = 10.2564 USD. Webhook payment.approved must carry cross_rate=13500/35897.44=0.37609 KRW/MNT. Rate lock: these values permanently recorded at commit.
**Steps:** Reset P-TEST-002 prefunding to 50000.00 USD; GET /v1/rates as P-TEST-002 with target_payout=13500, payout_ccy=KRW, direction=Inbound; assert full USD pool in response; Assert validUntil is now + TTL (default 60s for aggregator or 300s otherwise); record quote_id; POST /v1/payments with quote_id, merchant_id=M-TEST-0004, idempotency_key=MPM-INB-001; Wait for payment.approved webhook; assert cross_rate approx 0.37609 (tolerance 0.001); Assert P-TEST-002 prefunding balance = 50000.00 - 10.2564 = 49989.74 USD (tolerance 0.01)
**Deliverable:** test/e2e/hc/HC003_MpmInboundHappyPathTest.java (or equivalent)
**Acceptance / logic checks:**
- GET /v1/rates returns collection_usd=10.2564, payout_usd_cost=10.0000, send_amount=35897.44 MNT (all within 0.01)
- Pool identity: collection_usd - collection_margin_usd - payout_margin_usd = 10.0000 within 0.01 USD
- payment.approved webhook contains cross_rate within 0.001 of 0.37609
- Prefunding balance decremented by exactly collection_usd=10.2564 USD (within 0.01)
- Transaction record shows all 5 rate-engine fields stored; rate-lock: changing treasury rates after commit does not alter stored values
**Depends on:** 15.7-T03

### 15.7-T07 — E2E test HC-004: CPM Inbound payment — prefunding deducted at QR generate (OVERSEAS)  _(50 min)_
**Context:** Scenario HC-004 from QA-12 §5.1. Partner P-TEST-002 (OVERSEAS). CPM rule: prefunding deduction happens at POST /v1/payments/cpm/generate (QR token issuance), NOT at scheme approval. This is the authoritative rule per PRD-01 §5.8. Deduction timestamp must predate scheme call timestamp. If balance is zero, scheme is never called. Flow: generate QR → prefunding deducted → scheme scans → payment.approved.
**Steps:** Reset P-TEST-002 prefunding to 50000.00 USD; POST /v1/payments/cpm/generate as P-TEST-002 with target_payout=13500 KRW; record txn_id and deduction_timestamp from response or DB; Assert prefunding balance immediately after generate = 50000.00 - collection_usd (approx 49989.74 USD); Simulate scheme scan via ZeroPay stub; wait for payment.approved webhook; Assert deduction_timestamp < scheme_call_timestamp in the 8-step event trail for this txn_id; Query the transaction event trail; verify event order: PREFUNDING_DEDUCTED before SCHEME_CALLED
**Deliverable:** test/e2e/hc/HC004_CpmInboundPrefundAtGenerateTest.java (or equivalent)
**Acceptance / logic checks:**
- Prefunding balance is reduced immediately after POST /v1/payments/cpm/generate before any scheme call
- deduction_timestamp is earlier than scheme_call_timestamp in the 8-step event trail
- payment.approved webhook received after scheme scan with correct amounts
- If P-TEST-002 balance is reset to 0.00 before generate, generate returns INSUFFICIENT_PREFUNDING and no scheme call is made
- No double-deduction: balance decrements exactly once even if generate is retried with same idempotency key
**Depends on:** 15.7-T03

### 15.7-T08 — E2E test HC-005 and PA-008: Rate quote TTL expiry rejection  _(40 min)_
**Context:** Scenarios HC-005 and PA-008 from QA-12 §5.1 and §5.4. Rate quote TTL is 60s (aggregator-bound) or 300s (otherwise), configurable 60-1800s. The field validUntil = quote_issued_at + ttl. If a partner submits POST /v1/payments after validUntil has passed, the system must return error RATE_QUOTE_EXPIRED. No prefunding deduction occurs. The expired quote must not be reusable.
**Steps:** In test environment, set rate quote TTL to minimum (60s) via config; GET /v1/rates as P-TEST-002; record quote_id and validUntil; Wait for TTL to elapse (sleep 61s or mock time to advance past validUntil); POST /v1/payments with the expired quote_id; assert response is 4xx with error_code=RATE_QUOTE_EXPIRED; Assert P-TEST-002 prefunding balance unchanged after the failed commit attempt; Retry POST /v1/payments with same expired quote_id; assert same RATE_QUOTE_EXPIRED (quote not magically valid on retry)
**Deliverable:** test/e2e/hc/HC005_RateQuoteTtlExpiryTest.java (or equivalent)
**Acceptance / logic checks:**
- Response error_code is RATE_QUOTE_EXPIRED (not a 500 or generic error)
- Prefunding balance is unchanged after the expired-quote commit attempt
- No transaction record is created for the expired quote attempt
- validUntil field in GET /v1/rates response is exactly quote_issued_at + configured TTL in ISO-8601 format
- A fresh GET /v1/rates call after expiry returns a new valid quote with a future validUntil
**Depends on:** 15.7-T03

### 15.7-T09 — E2E test HC-006 and PA-007: Idempotency key replay returns same result without duplicate transaction  _(40 min)_
**Context:** Scenarios HC-006 and PA-007. The idempotency key mechanism (API-05) ensures that a duplicate POST /v1/payments with the same Idempotency-Key header returns the identical response as the original, with no second transaction, no second prefunding deduction, no second webhook. The key must be scoped per partner. A different partner using the same idempotency key string must get their own independent transaction.
**Steps:** POST /v1/payments as P-TEST-002 with idempotency_key=IDEM-TEST-001; record txn_id and collection_amount; Wait for payment.approved webhook; assert webhook received once; POST /v1/payments again as P-TEST-002 with identical body and idempotency_key=IDEM-TEST-001; Assert second response has same txn_id and collection_amount as first (idempotent replay); Assert only one transaction record exists for idempotency_key=IDEM-TEST-001 under P-TEST-002; Assert P-TEST-002 prefunding was deducted exactly once (not twice)
**Deliverable:** test/e2e/hc/HC006_IdempotencyKeyReplayTest.java (or equivalent)
**Acceptance / logic checks:**
- Second POST with same idempotency_key returns HTTP 200 with identical txn_id and collection_amount
- Exactly one payment record exists in DB for idempotency_key=IDEM-TEST-001 scoped to P-TEST-002
- Prefunding deduction count = 1 (not 2) after two identical requests
- Webhook fires exactly once (no duplicate payment.approved event in stub)
- A different partner (P-TEST-003) using idempotency_key=IDEM-TEST-001 creates a separate independent transaction
**Depends on:** 15.7-T03

### 15.7-T10 — E2E test HC-007 and RF-001: Same-day payment cancel via Partner API restores prefunding  _(45 min)_
**Context:** Scenarios HC-007 and RF-001 from QA-12 §5.1 and §5.7. POST /v1/payments/{id}/cancel is available only on the same day (within settlement cutoff). On successful cancel: transaction status becomes CANCELLED, prefunding balance is restored by the exact collection_usd amount, a ZeroPay cancel message is sent (via stub), and payment.cancelled webhook is fired. Post-settlement cancel must be rejected (RF-004).
**Steps:** Create a successful MPM Inbound payment as P-TEST-002; note txn_id and collection_usd deducted; POST /v1/payments/{txn_id}/cancel with valid credentials; assert 200 response; Wait for payment.cancelled webhook; assert txn_id and status=CANCELLED; Assert P-TEST-002 prefunding balance is restored: balance_after_cancel = balance_before_payment; Assert transaction status in DB = CANCELLED; Simulate post-settlement state (mark transaction as settled); attempt cancel again; assert 422 with error_code=CANCEL_NOT_ALLOWED
**Deliverable:** test/e2e/hc/HC007_SameDayCancelTest.java (or equivalent)
**Acceptance / logic checks:**
- POST /v1/payments/{id}/cancel returns 200 and payment.cancelled webhook arrives within 5000 ms
- Prefunding balance after cancel equals balance before the original payment (restored exactly)
- Transaction status in DB is CANCELLED after successful cancel
- ZeroPay stub received a cancel message for the transaction
- POST cancel on a settled transaction returns 422 with error_code=CANCEL_NOT_ALLOWED
**Depends on:** 15.7-T03

### 15.7-T11 — E2E test HC-008: Rate lock — treasury rate change after commit does not alter recorded values  _(45 min)_
**Context:** Scenario HC-008 from QA-12 §5.1. Rate lock rule: at commit time, all USD-pool values and derived rates are permanently recorded. Later changes to treasury rates or margin rules apply only to new transactions. Test: commit a transaction, then Ops changes treasury.usd_krw via Admin API, then verify the committed transaction still shows original rates. Also verify a new quote reflects the updated rate.
**Steps:** Reset treasury.usd_krw to 1350.00; commit a successful MPM Inbound payment as P-TEST-002; record txn_id, cost_rate_pay, collection_usd stored in DB; Via Admin API, update treasury.usd_krw to 1400.00; verify audit log entry created; GET /v1/payments/{txn_id}; assert cost_rate_pay still = 1350.00 (rate-locked); GET /v1/rates for a new quote; assert payout_usd_cost = target_payout / 1400.00 (new rate applied); Assert the new-quote payout_usd_cost differs from the committed transaction value; Restore treasury.usd_krw to 1350.00 after test
**Deliverable:** test/e2e/hc/HC008_RateLockTest.java (or equivalent)
**Acceptance / logic checks:**
- Committed transaction shows cost_rate_pay=1350.00 after treasury update to 1400.00
- New quote after treasury update reflects 1400.00 (payout_usd_cost = 13500/1400.00 = 9.6429 USD)
- Admin rate change creates an audit log entry with actor, timestamp, old_value=1350.00, new_value=1400.00
- GET /v1/payments/{id} endpoint does not recalculate stored amounts on retrieval
- Rule margin change (m_a update) similarly does not affect committed transactions — verify by updating P-TEST-002 m_a and re-querying the old txn
**Depends on:** 15.7-T03

### 15.7-T12 — E2E test HC-009 and PF-004: Insufficient prefunding rejects payment before scheme call  _(40 min)_
**Context:** Scenarios HC-009 and PF-004 from QA-12 §5.1 and §5.6. Rule: OVERSEAS partner payment must be rejected with INSUFFICIENT_PREFUNDING before the scheme is called if balance < collection_usd. Test with balance=9000 USD and collection_usd approx 10.2564 USD. The ZeroPay stub must record zero calls during this test. No prefunding deduction occurs.
**Steps:** Reset P-TEST-002 prefunding to 9.00 USD (well below collection_usd of ~10.26 USD for 13500 KRW target); POST /v1/payments as P-TEST-002 with target_payout=13500 KRW; assert 422 response with error_code=INSUFFICIENT_PREFUNDING; Assert P-TEST-002 prefunding balance is still 9.00 USD (unchanged); Assert ZeroPay stub received zero payment calls (scheme was not contacted); Assert no transaction record was created in DB; Also test PF-005: reset balance to 0.00; any OVERSEAS payment returns INSUFFICIENT_PREFUNDING and fires an ops alert
**Deliverable:** test/e2e/hc/HC009_InsufficientPrefundingTest.java (or equivalent)
**Acceptance / logic checks:**
- Response error_code=INSUFFICIENT_PREFUNDING with HTTP 422 when balance=9.00 USD and required collection_usd approx 10.26 USD
- Prefunding balance is unchanged after rejection (no partial deduction)
- ZeroPay stub call count = 0 for this test run
- No transaction record created for the rejected payment attempt
- Balance=0.00 also returns INSUFFICIENT_PREFUNDING and triggers an alert
**Depends on:** 15.7-T03

### 15.7-T13 — E2E test HC-010 and PF-006: Low-balance alert fires after deduction crosses threshold  _(40 min)_
**Context:** Scenarios HC-010 and PF-006 from QA-12 §5.1 and §5.6. When a deduction causes P-TEST-002 balance to fall below the configured low-balance threshold (e.g. 10000 USD), an email alert must be sent to the partner contact. The transaction itself must continue and be approved. Threshold is configurable (PF-007): Ops can change it and the new threshold applies to the next deduction.
**Steps:** Set P-TEST-002 low-balance threshold to 10000.00 USD via Admin API; Reset P-TEST-002 prefunding to 10005.00 USD (just above threshold); POST /v1/payments as P-TEST-002 for a payment where collection_usd approx 10.26 USD (will push balance below 10000); Wait for payment.approved webhook; assert payment succeeded; Assert alert email (or alert event in test stub) was dispatched to P-TEST-002 contact; Assert alert contains partner_id=P-TEST-002, current_balance (below 10000), threshold=10000.00
**Deliverable:** test/e2e/hc/HC010_LowBalanceAlertTest.java (or equivalent)
**Acceptance / logic checks:**
- payment.approved webhook received — transaction continues despite low balance
- Alert notification dispatched with partner_id=P-TEST-002 and balance below threshold
- Alert contains current_balance and threshold values
- No INSUFFICIENT_PREFUNDING error (balance was above zero but below threshold at deduction time)
- Updating threshold to 5000 USD (PF-007): a deduction to 7000 USD does not trigger alert; deduction to 4000 USD does
**Depends on:** 15.7-T03

### 15.7-T14 — E2E test HC-011 and HC-012: Payment rejected for inactive merchant and deactivated QR  _(40 min)_
**Context:** Scenarios HC-011 and HC-012 from QA-12 §5.1. M-TEST-0002 is Inactive; any payment targeting it must return MERCHANT_INACTIVE. QR-TEST-0005 belongs to M-TEST-0005 (merchant is Active) but the QR code itself is deactivated; payment using that QR must return QR_DEACTIVATED. Neither error should result in a transaction record or prefunding deduction.
**Steps:** POST /v1/payments as P-TEST-002 with merchant_id=M-TEST-0002 (Inactive); assert error_code=MERCHANT_INACTIVE, HTTP 422; Assert no transaction record created and prefunding unchanged; POST /v1/payments as P-TEST-002 with qr_code=QR-TEST-0005 (deactivated QR on active merchant); assert error_code=QR_DEACTIVATED, HTTP 422; Assert no transaction record created and prefunding unchanged; Verify that after ZP0043 deactivates a QR, subsequent payment attempts using that QR immediately return QR_DEACTIVATED (ZP-009 scenario); POST /v1/payments with M-TEST-0001 (Active) and QR-TEST-0001 (Active) as control; assert success
**Deliverable:** test/e2e/hc/HC011HC012_MerchantValidationTest.java (or equivalent)
**Acceptance / logic checks:**
- MERCHANT_INACTIVE returned for M-TEST-0002 with no DB transaction record
- QR_DEACTIVATED returned for QR-TEST-0005 with no DB transaction record
- Prefunding balance unchanged after both rejection scenarios
- Control payment with M-TEST-0001 / QR-TEST-0001 succeeds normally
- ZP0043 QR deactivation takes effect immediately on next payment attempt (no cache serving stale state)
**Depends on:** 15.7-T03

### 15.7-T15 — E2E test HC-013 and RV-06: Partner B quote deviation beyond tolerance rejected  _(45 min)_
**Context:** Scenarios HC-013 and RV-06 from QA-12 §5.1 and §4.2. Rule: if the commit-time Partner B quote deviates more than the tolerance (default 1.0%) from the quote-time rate, return PARTNER_B_QUOTE_DEVIATION and do not commit. Test vector RV-06: quote_time_rate=1350.00, commit_time_partner_b_quote=1366.20 (deviation=1.2%). No prefunding deduction, no transaction record. Separate test for within-tolerance (RV-05): deviation=0.80% (1360.80) must succeed.
**Steps:** Configure P-TEST-005 Partner B stub to return 1366.20 for cost_rate_pay at commit time (1.2% deviation from quote-time 1350.00); GET /v1/rates as P-TEST-005; record quote_id (quote-time rate=1350.00); POST /v1/payments with quote_id; assert HTTP 4xx with error_code=PARTNER_B_QUOTE_DEVIATION; Assert no prefunding deduction and no transaction record; Reconfigure stub to return 1360.80 (0.8% deviation within tolerance); repeat POST /v1/payments with a fresh quote; Assert payment commits successfully and recorded cost_rate_pay=1360.80 with rate_source=PARTNER
**Deliverable:** test/e2e/hc/HC013_PartnerBQuoteDeviationTest.java (or equivalent)
**Acceptance / logic checks:**
- 1.2% deviation returns PARTNER_B_QUOTE_DEVIATION with no committed transaction
- Prefunding balance unchanged after PARTNER_B_QUOTE_DEVIATION error
- 0.8% deviation (within 1.0% tolerance) results in successful commit with recorded cost_rate_pay=1360.80
- rate_source field in committed transaction DB record = PARTNER for Partner B source
- Stored payout_usd_cost uses the Partner B commit-time rate (13500/1360.80=9.9206 USD) not the quote-time rate
**Depends on:** 15.7-T03

### 15.7-T16 — E2E test HC-014: Partner B quote unavailable returns correct error with no fallback  _(35 min)_
**Context:** Scenario HC-014 from QA-12 §5.1. Rule: if the Partner B quote API is unreachable at commit time, return PARTNER_B_QUOTE_UNAVAILABLE. There is NO fallback to treasury rate. No prefunding deduction, no transaction record. This distinguishes from HC-013 (quote available but wrong) vs HC-014 (quote not available at all).
**Steps:** Configure P-TEST-005 Partner B stub to return HTTP 503 (unavailable); GET /v1/rates as P-TEST-005; record quote_id; POST /v1/payments with quote_id; assert HTTP 4xx/5xx with error_code=PARTNER_B_QUOTE_UNAVAILABLE; Assert no transaction record created; Assert no prefunding deduction; Restore P-TEST-005 stub to normal; verify subsequent payment succeeds
**Deliverable:** test/e2e/hc/HC014_PartnerBQuoteUnavailableTest.java (or equivalent)
**Acceptance / logic checks:**
- Error code PARTNER_B_QUOTE_UNAVAILABLE (not PARTNER_B_QUOTE_DEVIATION) when stub returns 503
- No fallback to treasury rate is attempted (verify by checking that no transaction with treasury rate_source is created)
- No prefunding deduction occurs
- No transaction record in DB
- After stub is restored, a new payment with fresh quote commits successfully
**Depends on:** 15.7-T03

### 15.7-T17 — E2E test HC-015: Pool-identity assertion passes for all cross-border transactions  _(45 min)_
**Context:** Scenario HC-015 from QA-12 §5.1 and §4.3. The pool-identity invariant must hold for every committed cross-border transaction: abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) <= 0.01 USD. This assertion is embedded in production code and fires a CRITICAL alert if violated. The E2E test must execute multiple cross-border payments and verify the assertion passes silently (no alert fired).
**Steps:** Execute 5 cross-border MPM Inbound payments as P-TEST-002 with varying target_payout amounts (13500, 10001, 50000, 1000, 99999 KRW); For each committed transaction, query the stored fields: collection_usd, collection_margin_usd, payout_margin_usd, payout_usd_cost; Compute abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) for each; Assert all deltas are <= 0.01 USD; Assert no CRITICAL pool-identity alert was fired in the alerting stub; Also assert that domestic transactions (P-TEST-001) have no USD pool fields stored (short-circuit path)
**Deliverable:** test/e2e/hc/HC015_PoolIdentityAssertionTest.java (or equivalent)
**Acceptance / logic checks:**
- All 5 cross-border transactions satisfy pool identity within 0.01 USD tolerance
- No CRITICAL pool-identity alert fired in alerting stub during test run
- Domestic transaction has null collection_usd, null collection_margin_usd, null payout_margin_usd
- Pool identity pseudocode: assert abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) <= 0.01 passes for each record
- Rounding edge case (target_payout=10001 KRW): pool identity still holds despite non-integer intermediate values
**Depends on:** 15.7-T03

### 15.7-T18 — E2E test PF-001 and PF-003: Atomic prefunding deduction under concurrent requests (SELECT FOR UPDATE)  _(50 min)_
**Context:** Scenarios PF-001 and PF-003 from QA-12 §5.6. The prefunding deduction is ATOMIC via SELECT ... FOR UPDATE. Under two simultaneous requests from P-TEST-002, exactly one deduction per transaction occurs; no double-spend; the second request waits for the lock and then proceeds with the updated balance. Test: fire 2 concurrent requests; assert total deduction = sum of both collection_usd values (not double either one).
**Steps:** Reset P-TEST-002 prefunding to 200.00 USD; Fire 2 concurrent POST /v1/payments requests from P-TEST-002 using different idempotency keys (CONCURRENT-001, CONCURRENT-002) at the same millisecond; Wait for both payment.approved webhooks; Assert exactly 2 transaction records created (one per idempotency key); Compute expected final balance = 200.00 - sum(collection_usd_1 + collection_usd_2); assert actual balance matches within 0.01 USD; Assert no partial/corrupted balance (e.g. same collection_usd deducted twice) by verifying each deduction event in the audit trail
**Deliverable:** test/e2e/pf/PF001PF003_AtomicDeductionConcurrentTest.java (or equivalent)
**Acceptance / logic checks:**
- Exactly 2 approved transactions created from 2 concurrent requests
- Final balance = 200.00 - (collection_usd_1 + collection_usd_2) within 0.01 USD
- No double-deduction: each collection_usd appears exactly once in the deduction audit trail
- Both payment.approved webhooks received (neither request starved)
- If initial balance is 15.00 USD and each payment requires collection_usd approx 10.26 USD, the second request fails with INSUFFICIENT_PREFUNDING after the first deduction
**Depends on:** 15.7-T03

### 15.7-T19 — E2E test PF-002 and PF-008: CPM prefunding deducted at generate; cancel restores balance  _(45 min)_
**Context:** Scenarios PF-002 and PF-008 from QA-12 §5.6. CPM prefunding deduction is at POST /v1/payments/cpm/generate (QR issuance), not at scheme approval. Deduction timestamp must predate scheme call timestamp. On cancel, the exact collection_usd is restored to prefunding balance. Test both the timing invariant and the cancel restore.
**Steps:** Reset P-TEST-002 balance to 50000.00 USD; POST /v1/payments/cpm/generate as P-TEST-002; record txn_id, t_deduction (from event trail); Assert balance = 50000.00 - collection_usd immediately after generate (before scheme scan); Simulate scheme scan; wait for payment.approved; Assert deduction timestamp t_deduction < scheme_call timestamp in 8-step event trail; POST /v1/payments/{txn_id}/cancel (same-day); assert balance restored to 50000.00 USD
**Deliverable:** test/e2e/pf/PF002PF008_CpmPrefundTimingAndCancelTest.java (or equivalent)
**Acceptance / logic checks:**
- Balance decremented immediately after /cpm/generate before any scheme interaction
- Event trail shows PREFUNDING_DEDUCTED event timestamp before SCHEME_CALLED event timestamp
- payment.approved webhook received after scheme scan
- After cancel, balance is restored to exactly 50000.00 USD (original amount)
- No double-restore: cancel called twice with same idempotency key restores balance only once
**Depends on:** 15.7-T03

### 15.7-T20 — E2E test RF-002 and RF-003: Admin portal refund creates ZP0021 record; over-amount rejected  _(45 min)_
**Context:** Scenarios RF-002 and RF-003 from QA-12 §5.7. Phase 1: refunds are Admin-portal-only (no Partner API refund endpoint). Ops initiates refund on a prior-day transaction via Admin portal (or Admin API). A refund record must appear in the next ZP0021 batch file. RF-003: refund amount > original amount must be rejected with a validation error.
**Steps:** Create and approve a payment as P-TEST-002 with collection_amount=36397.44 MNT; note txn_id and original amounts; Via Admin API POST /admin/v1/payments/{txn_id}/refund with refund_amount=13500 KRW (full refund); assert 200; Assert a refund record is created in DB linked to original txn_id; Trigger ZP0021 batch generation; assert refund record appears in generated ZP0021 file; Attempt a refund with refund_amount=14000 KRW (greater than original 13500 KRW); assert validation error; Assert no Partner API refund endpoint exists (GET /v1/payments/{id}/refund returns 404 or 405)
**Deliverable:** test/e2e/rf/RF002RF003_AdminRefundAndOverAmountTest.java (or equivalent)
**Acceptance / logic checks:**
- Successful refund record created in DB with correct original txn_id, refund_amount, and reason
- Refund record appears in ZP0021 file generated by next batch run
- Refund > original amount returns validation error (HTTP 422) with clear message
- No Partner API refund endpoint accessible (HTTP 404 or 405 on partner-facing route)
- Refund status transitions correctly: PENDING_REFUND -> refund record in batch -> awaiting ZP0022 confirmation
**Depends on:** 15.7-T03

### 15.7-T21 — E2E test RF-004: Cancel rejected for post-settlement transaction  _(35 min)_
**Context:** Scenario RF-004 from QA-12 §5.7. Once a transaction has been included in a settlement batch and marked as settled, the Partner API cancel endpoint must reject the request with CANCEL_NOT_ALLOWED. Ops must use the refund path instead. This enforces the settlement state machine boundary.
**Steps:** Create and approve a payment as P-TEST-002; note txn_id; Advance transaction status to SETTLED (via test hook or directly update DB status to simulate post-settlement); POST /v1/payments/{txn_id}/cancel as P-TEST-002; assert HTTP 422 with error_code=CANCEL_NOT_ALLOWED; Assert transaction status remains SETTLED (not changed to CANCELLED); Assert prefunding balance unchanged; Verify the Admin refund path still works for the settled transaction (RF-002 approach)
**Deliverable:** test/e2e/rf/RF004_CancelPostSettlementRejectedTest.java (or equivalent)
**Acceptance / logic checks:**
- POST /v1/payments/{id}/cancel on a settled transaction returns HTTP 422 with error_code=CANCEL_NOT_ALLOWED
- Transaction status remains SETTLED after failed cancel attempt
- Prefunding balance is not restored after rejected cancel
- Admin refund endpoint accepts the settled transaction for refund processing
- Error message in response references the settled state as the reason for rejection
**Depends on:** 15.7-T03

### 15.7-T22 — E2E test ZP-001: ZP0011 payment result file generation and ZP0012 reconciliation round-trip  _(55 min)_
**Context:** Scenario ZP-001 from QA-12 §5.5. Each day, GME generates ZP0011 (payment result, GME->ZP direction) by 02:00 KST and receives ZP0012 (confirmation, ZP->GME) by 05:00 KST. ZP0012 reconciliation: every record in ZP0011 must match a record in ZP0012. Discrepancies (missing record, amount mismatch, extra record) must auto-flag, fire ops alert, and route to exception management (ZP-013). File layout per SCH-06.
**Steps:** Seed 3 approved transactions for previous day in test DB for P-TEST-001 and P-TEST-002; Trigger ZP0011 batch generation job; verify file is written to SFTP stub before simulated 02:00 KST deadline; Validate ZP0011 file structure against SCH-06 layout (header, transaction records, trailer with correct count and checksum); Inject matching ZP0012 synthetic inbound file via SFTP stub; trigger ZP0012 processing; Assert all 3 transactions are marked RECONCILED in DB; Inject a ZP0012 with one missing record; assert auto-flag, ops alert, and exception record created
**Deliverable:** test/e2e/zp/ZP001_ZP0011ZP0012RoundTripTest.java (or equivalent)
**Acceptance / logic checks:**
- ZP0011 file contains exactly 3 records with correct transaction IDs, amounts, and KST deadline respected
- ZP0011 trailer checksum and record count match actual data
- All 3 transactions marked RECONCILED after matching ZP0012 processing
- Missing ZP0012 record triggers auto-flag and ops alert within processing window
- Extra ZP0012 record (not in ZP0011) also triggers auto-flag
**Depends on:** 15.7-T03

### 15.7-T23 — E2E test ZP-003 to ZP-005: Settlement cycle files ZP0061-ZP0066 and net vs gross verification  _(55 min)_
**Context:** Scenarios ZP-003, ZP-004, ZP-005 from QA-12 §5.5 and §9.4. Morning settlement: ZP0061 (GME->ZP by 05:00 KST), ZP0062 (ZP->GME by 10:00 KST). Afternoon: ZP0063 by 14:00 KST, ZP0064 by 19:00 KST. Detail: ZP0065/ZP0066 by 22:00 KST. Net vs gross: Domestic (P-TEST-001) settlement amount = sum of target_payout (net); International (P-TEST-002) settlement amount = sum of full KRW payout amounts (gross). ZP0061 totals must match this model.
**Steps:** Seed 2 domestic transactions (P-TEST-001, target_payout 13500 and 14000 KRW) and 2 inbound transactions (P-TEST-002) for settlement day; Trigger ZP0061 morning settlement job; assert file generated before simulated 05:00 KST; Assert ZP0061 domestic total = 13500 + 14000 = 27500 KRW (net); international total = sum of full KRW payout amounts (gross); Inject synthetic ZP0062 (matching totals); trigger reconciliation; assert GME totals match ZP result, batch marked reconciled; Trigger afternoon ZP0063; assert generated before 14:00 KST; inject ZP0064 and reconcile; Trigger ZP0065/ZP0066 detail files; assert line-by-line reconciliation passes
**Deliverable:** test/e2e/zp/ZP003ZP005_SettlementCycleTest.java (or equivalent)
**Acceptance / logic checks:**
- ZP0061 domestic section total = sum of target_payout for domestic transactions (net settlement model)
- ZP0061 international section total = sum of gross KRW payout amounts (gross settlement model)
- GME totals in ZP0061 match ZP0062 response totals; batch marked reconciled
- All settlement files generated within their respective KST deadline windows in test simulation
- ZP0065/ZP0066 line-by-line reconciliation: each transaction matches within KRW 0 (zero tolerance)
**Depends on:** 15.7-T03

### 15.7-T24 — E2E test ZP-011 and ZP-012: SFTP transmission failure retry and ZeroPay registration rejection  _(50 min)_
**Context:** Scenarios ZP-011 and ZP-012 from QA-12 §5.5. ZP-011: SFTP transmission failure triggers retry; ops alert fired; settlement NOT blocked by first retry (retry must succeed before settlement deadline). ZP-012: ZP0012 returns failure for a ZP0011 record (ZeroPay registration rejected); ops alerted; settlement batch blocked until the rejected record is resolved (P1 incident).
**Steps:** Configure SFTP stub to fail first PUT attempt for ZP0011; assert retry is triggered automatically; Assert ops alert is fired on first SFTP failure with file name and error detail; Assert second retry succeeds and ZP0011 is delivered within the 02:00 KST window; Inject a ZP0012 inbound file where one record has status=REJECTED (ZeroPay registration failure); Assert ops alert is fired: type=REGISTRATION_REJECTION, txn_id, reason from ZP0012; Assert settlement batch for that transaction is BLOCKED pending manual resolution
**Deliverable:** test/e2e/zp/ZP011ZP012_SftpFailureAndRegistrationRejectionTest.java (or equivalent)
**Acceptance / logic checks:**
- First SFTP failure triggers an ops alert with file name, timestamp, error code
- Automatic retry delivers ZP0011 successfully (settlement not permanently blocked by one failure)
- ZP0012 registration rejection fires a distinct ops alert with txn_id and rejection reason
- Transaction with rejected registration has status=SETTLEMENT_BLOCKED in DB
- Manual resolution by Ops (via Admin) changes status from SETTLEMENT_BLOCKED to RESOLVED and clears the block
**Depends on:** 15.7-T03

### 15.7-T25 — E2E test ZP-006 to ZP-010: Merchant and QR sync files round-trip  _(50 min)_
**Context:** Scenarios ZP-006 to ZP-010 from QA-12 §5.5. ZP0041: incremental merchant sync — new or changed merchants inserted/updated in DB. ZP0045/ZP0047: franchise hierarchy persisted correctly. ZP0051/ZP0055: full merchant list sync rebuilds DB to match ZeroPay golden dataset. ZP0043: QR registration/deactivation — deactivated QR blocked immediately. ZP0053: full QR list sync.
**Steps:** Inject ZP0041 with 2 new merchants (M-NEW-001, M-NEW-002) and 1 update to M-TEST-0001 (name change); trigger processing; Assert M-NEW-001 and M-NEW-002 inserted in DB; M-TEST-0001 name updated; Inject ZP0045 franchise parent and ZP0047 franchise member file; assert hierarchy persisted (parent_id foreign key set correctly); Inject ZP0051 full list (5 canonical merchants); trigger full sync; assert DB matches exactly the 5 records (extra records deleted); Inject ZP0043 with QR-TEST-0003 deactivation; assert immediate block (subsequent payment returns QR_DEACTIVATED); Inject ZP0053 full QR list; assert QR table rebuilt to match file contents
**Deliverable:** test/e2e/zp/ZP006ZP010_MerchantQrSyncTest.java (or equivalent)
**Acceptance / logic checks:**
- ZP0041 incremental sync inserts 2 new merchants and updates 1 existing merchant without touching others
- ZP0045/ZP0047 franchise sync correctly sets parent_merchant_id on child merchant records
- ZP0051 full sync removes merchants not in the file and adds/updates all records in the file
- QR deactivated via ZP0043 is blocked immediately: next payment attempt with that QR returns QR_DEACTIVATED
- ZP0053 full QR sync rebuilds the QR table to exactly match the file (no orphaned records)
**Depends on:** 15.7-T03

### 15.7-T26 — E2E test ZP-013 and ZP-014: Reconciliation discrepancy auto-flag and late file handling  _(45 min)_
**Context:** Scenarios ZP-013 and ZP-014 from QA-12 §5.5 and §9.3. ZP-013: ZP0062 settlement total differs from GME total — must be auto-flagged, ops alert sent, routed to exception management. Also covers: missing record, extra record, amount discrepancy. ZP-014: ZP0012 arrives after 05:00 KST — ops alert; previous-day data flag set.
**Steps:** Inject ZP0062 with total amount differing from GME DB total by 1000 KRW; trigger reconciliation; Assert discrepancy auto-flagged with reconciliation_status=DISCREPANCY and discrepancy_amount=1000 KRW; Assert ops alert fired with file_type=ZP0062, expected_total, actual_total, discrepancy_amount; Assert exception record created and routed to exception management queue; Simulate ZP0012 arriving after 05:00 KST (inject synthetic late file); assert ops alert fired with latency details; Assert late ZP0012 sets previous_day_data_flag=true on affected transactions
**Deliverable:** test/e2e/zp/ZP013ZP014_ReconciliationDiscrepancyTest.java (or equivalent)
**Acceptance / logic checks:**
- Amount discrepancy of 1000 KRW in ZP0062 triggers auto-flag with reconciliation_status=DISCREPANCY
- Ops alert contains expected_total, actual_total, discrepancy_amount fields
- Exception record created in exception management table with discrepancy details
- Late ZP0012 (after 05:00 KST window) triggers ops alert with arrival timestamp
- previous_day_data_flag set on transactions in late ZP0012 batch
**Depends on:** 15.7-T03

### 15.7-T27 — E2E test PA-001 to PA-002: Partner API authentication — valid and invalid credentials  _(35 min)_
**Context:** Scenarios PA-001 and PA-002 from QA-12 §5.4. POST /v1/auth/token with valid partner credentials returns HTTP 200 and a Bearer token. Invalid credentials (wrong key, wrong secret, nonexistent partner) must return HTTP 401 with error_code=INVALID_CREDENTIALS. Token must be scoped to the issuing partner (cannot use P-TEST-001 token to access P-TEST-002 resources).
**Steps:** POST /v1/auth/token with P-TEST-001 valid credentials; assert HTTP 200, token non-null, expires_in present; POST /v1/auth/token with invalid API key; assert HTTP 401, error_code=INVALID_CREDENTIALS; POST /v1/auth/token with valid key but wrong secret; assert HTTP 401, error_code=INVALID_CREDENTIALS; Use P-TEST-001 token to GET /v1/payments/{txn_id_of_P-TEST-002}; assert HTTP 403 (cross-partner access denied); Attempt GET /v1/balance without any Authorization header; assert HTTP 401; Assert no internal error details (stack trace, DB error) leak in 401 response body
**Deliverable:** test/e2e/pa/PA001PA002_AuthTest.java (or equivalent)
**Acceptance / logic checks:**
- Valid credentials return HTTP 200 with non-null Bearer token and expires_in field
- Invalid credentials return HTTP 401 with error_code=INVALID_CREDENTIALS (no stack trace in body)
- P-TEST-001 token rejected with HTTP 403 when accessing P-TEST-002 transaction (IDOR prevention)
- Missing Authorization header returns HTTP 401
- No secrets, DB connection strings, or internal paths appear in error response bodies
**Depends on:** 15.7-T03

### 15.7-T28 — E2E test PA-003 and PA-004: GET /v1/rates and POST /v1/payments contract verification  _(40 min)_
**Context:** Scenarios PA-003 and PA-004 from QA-12 §5.4 and §7.2. GET /v1/rates for MPM Inbound must return full USD pool breakdown (collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, send_amount, offer_rate_coll, cross_rate) and a populated validUntil. POST /v1/payments must result in prefunding deduction and a payment.approved webhook. Request and response schemas must match openapi/partner-api.yaml.
**Steps:** GET /v1/rates as P-TEST-002 with target_payout=13500, payout_ccy=KRW, direction=Inbound; assert HTTP 200; Assert response contains all required USD pool fields and none are null; assert validUntil is a valid ISO-8601 datetime in the future; Validate response body against OpenAPI partner-api.yaml /v1/rates response schema (schema compliance check); POST /v1/payments with the returned quote_id; assert HTTP 200 or 201; Wait for payment.approved webhook; assert schema matches openapi/partner-api.yaml webhook definition; Assert internal fields m_a, m_b, cost rates NOT present in GET /v1/rates or POST /v1/payments response
**Deliverable:** test/e2e/pa/PA003PA004_RatesAndPaymentsContractTest.java (or equivalent)
**Acceptance / logic checks:**
- GET /v1/rates returns all 8 USD pool fields with non-null values for cross-border Inbound request
- validUntil is present and is a future datetime (ISO-8601)
- Response body validates against openapi/partner-api.yaml schema (zero schema mismatches)
- Internal fields m_a, m_b, cost_rate_coll, cost_rate_pay are absent from all partner-facing responses
- payment.approved webhook payload matches the documented schema including txn_id, status, collection_amount, cross_rate
**Depends on:** 15.7-T03

### 15.7-T29 — E2E test PA-005 and PA-006: CPM generate endpoint and webhook retry policy  _(45 min)_
**Context:** Scenarios PA-005 and PA-006 from QA-12 §5.4 and §7.3. POST /v1/payments/cpm/generate returns a QR token and prefunding is deducted. Webhook retry policy (API-05): if partner webhook endpoint returns non-2xx, system retries with exponential backoff. Test: mock partner webhook to fail first 3 attempts (HTTP 500) and succeed on 4th; verify eventual delivery with identical payload (idempotent retry).
**Steps:** Configure webhook stub for P-TEST-002 to return HTTP 500 for first 3 calls then HTTP 200; POST /v1/payments/cpm/generate as P-TEST-002; record txn_id; Simulate scheme scan; wait for webhook delivery attempts; Assert webhook stub received exactly 4 calls (3 failures + 1 success) for payment.approved event; Assert all 4 attempts had identical payloads (txn_id, status, collection_amount unchanged); Assert transaction status = APPROVED in DB after successful webhook delivery
**Deliverable:** test/e2e/pa/PA005PA006_CpmGenerateAndWebhookRetryTest.java (or equivalent)
**Acceptance / logic checks:**
- POST /v1/payments/cpm/generate returns HTTP 200 with non-null qr_token
- Webhook stub receives 4 delivery attempts (exponential backoff between attempts)
- All retry payloads are byte-identical (same txn_id, collection_amount, event type)
- Transaction status = APPROVED after eventual successful webhook delivery
- Prefunding deducted at generate time (not at 4th retry attempt)
**Depends on:** 15.7-T03

### 15.7-T30 — E2E test PP-001 to PP-007: Partner Portal authentication, balance, transactions, and data isolation  _(45 min)_
**Context:** Scenarios PP-001 to PP-007 from QA-12 §5.3. Partner Portal login (username, password, TOTP). After login, partner sees only their own data. PP-005 critical: m_a, m_b, cost rates, GME margin must NOT appear in any Partner Portal view. PP-007: Partner A cannot query Partner B transactions (IDOR prevention). PP-004: date-filter and CSV export must return consistent results.
**Steps:** Login as P-TEST-002 portal user with valid credentials + TOTP; assert dashboard loads showing only P-TEST-002 data; Navigate to Transactions; set date filter to today; assert results contain only P-TEST-002 transactions; Click a transaction row; assert detail page shows all permitted fields but NOT m_a, m_b, cost_rate_coll, cost_rate_pay; Export CSV; assert CSV contains same rows as UI filter result and does not expose internal margin fields; Attempt to query a P-TEST-001 transaction ID via URL manipulation; assert access denied (403 or empty result); Login with invalid credentials; assert access denied with no information leakage
**Deliverable:** test/e2e/pp/PP001PP007_PartnerPortalTest.java (or equivalent)
**Acceptance / logic checks:**
- Portal login with valid credentials + TOTP succeeds; invalid credentials denied with no stack trace
- Transaction history shows only P-TEST-002 own transactions; no P-TEST-001 records visible
- Transaction detail page has no m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd fields
- CSV export row count matches UI list row count; no internal fields in CSV columns
- Direct URL access to P-TEST-001 transaction ID returns 403 or empty result (not a 500 or data leak)
**Depends on:** 15.7-T03

### 15.7-T31 — E2E test AD-001 to AD-005: Admin System scheme and partner onboarding, currency section, rate config  _(50 min)_
**Context:** Scenarios AD-001 to AD-005 from QA-12 §5.2. AD-001: create new QR scheme — appears in list, all 4 config sections accessible. AD-002: onboard new OVERSEAS partner — API credentials generated, webhook URL stored. AD-003: map partner to scheme — currency section auto-derives collection/settle A/settle B/payout ccys (read-only, no manual input). AD-004: rate config section — LIVE slots auto-assigned for non-identity legs, IDENTITY flagged when settle=USD. AD-005: override LIVE to MANUAL rate source — saved, audit log entry created.
**Steps:** Via Admin API, create a new scheme TEST-SCHEME-001; assert it appears in GET /admin/v1/schemes; Create new OVERSEAS partner TEST-P-006 via Admin API; assert API key and secret generated, webhook_url stored; Map TEST-P-006 to TEST-SCHEME-001 with direction=Inbound; assert currency section auto-populated (no manual currency input accepted); Assert rate config section shows LIVE for non-identity legs and IDENTITY for any leg where settle=USD; Override collection-side rate source to MANUAL for TEST-P-006; assert saved successfully; Query Admin audit log for TEST-P-006 rule; assert entry with actor, timestamp, old_value=LIVE, new_value=MANUAL
**Deliverable:** test/e2e/ad/AD001AD005_SchemePartnerOnboardingTest.java (or equivalent)
**Acceptance / logic checks:**
- New scheme TEST-SCHEME-001 appears in Admin scheme list immediately after creation
- TEST-P-006 partner record has API key and secret in response; webhook_url stored correctly
- Currency section auto-populates collection/settle_a/settle_b/payout ccys based on partner+scheme config; manual override rejected (422)
- Rate config correctly shows IDENTITY when settle_a or settle_b = USD
- Audit log entry for MANUAL override has actor, timestamp, old_value, new_value fields populated
**Depends on:** 15.7-T03

### 15.7-T32 — E2E test AD-006 to AD-012: Margin validation, service charges, audit trail, and rule change isolation  _(50 min)_
**Context:** Scenarios AD-006 to AD-012 from QA-12 §5.2. AD-006: m_a+m_b=2.0% cross-border saved successfully. AD-007: m_a+m_b=1.9% rejected. AD-008: 0% domestic allowed (same-currency short-circuit). AD-009: flat service charge saved. AD-010: volume-tier table saved with correct tier selection. AD-011: margin change audit-logged with old and new values. AD-012: rule change applies only to new transactions (in-flight transactions use old rule).
**Steps:** Save rule with m_a=0.010, m_b=0.010 (combined 2.0%) for cross-border partner; assert success (AD-006); Attempt to save rule with m_a=0.010, m_b=0.009 (combined 1.9%); assert validation error referencing 2.0% minimum (AD-007); Save rule with m_a=0.0, m_b=0.0 for domestic partner (P-TEST-001); assert success and short-circuit configured (AD-008); Save flat service_charge=500 KRW for P-TEST-001 rule; assert visible in rule detail (AD-009); Initiate a rate quote (GET /v1/rates) using the current m_a; then update m_a to 2.0% via Admin; submit the original quote_id; assert old margin applied (AD-012 rule change isolation); Query Admin audit log after m_a change; assert entry with actor, timestamp, old_value, new_value (AD-011)
**Deliverable:** test/e2e/ad/AD006AD012_MarginValidationAuditRuleIsolationTest.java (or equivalent)
**Acceptance / logic checks:**
- m_a+m_b=2.0% cross-border rule saved; m_a+m_b=1.9% rejected with error referencing 2% minimum
- m_a=0%, m_b=0% domestic rule saved without validation error
- Flat service_charge=500 KRW persisted and returned in rule detail
- Rate quote issued before margin change commits with old margin; new quote after change uses new margin
- Audit log entry after m_a change contains actor, timestamp, old_value, new_value with correct data
**Depends on:** 15.7-T03

### 15.7-T33 — E2E test AD-013: Config-without-code — new scheme and partner added and operational in under 30 minutes  _(45 min)_
**Context:** Scenario AD-013 and UAT-013 from QA-12 §5.2 and §10.2. A new scheme and partner must be added entirely via Admin configuration with zero code deployments and zero merges. The timed walkthrough must complete all 4 Admin sections (currency, rate config, margin, service charge) and result in a working payment within 30 minutes. This is a BRD-01 success metric.
**Steps:** Record start time; create new scheme TIMED-SCHEME-001 via Admin API; Create new OVERSEAS partner TIMED-P-007 with ZeroPay + TIMED-SCHEME-001 mapping; complete all 4 config sections; Seed prefunding for TIMED-P-007 (10000 USD); load treasury rates; Make a test payment GET /v1/rates + POST /v1/payments as TIMED-P-007; assert payment approved; Record end time; assert total elapsed < 30 minutes; Assert zero code deployments or git merges occurred during the walkthrough (verified by Git log or deployment log)
**Deliverable:** test/e2e/ad/AD013_ConfigWithoutCodeTimedTest.java (or equivalent)
**Acceptance / logic checks:**
- All 4 Admin sections (currency, rate config, margin, service charge) completed for TIMED-P-007
- payment.approved webhook received for test payment as TIMED-P-007
- Total elapsed time from scheme creation to first approved payment <= 30 minutes
- Git commit log shows no new commits during the walkthrough window
- Deployment log shows no deployments triggered during the walkthrough window
**Depends on:** 15.7-T31

### 15.7-T34 — E2E test: 8-step transaction event trail completeness for MPM and CPM flows  _(45 min)_
**Context:** QA-12 §1.3 and SAD-02 §5.2 define an 8-step transaction event trail. Every committed transaction must carry a complete ordered event trail linked to the trace_id (W3C TraceContext). The trail for MPM Inbound flow includes: QUOTE_REQUESTED, PREFUNDING_DEDUCTED, SCHEME_CALLED, SCHEME_APPROVED, RATE_LOCKED, WEBHOOK_SENT, WEBHOOK_DELIVERED (or WEBHOOK_RETRY), COMPLETED. CPM adds: QR_GENERATED before PREFUNDING_DEDUCTED.
**Steps:** Execute a complete MPM Inbound payment as P-TEST-002; query the event trail via GET /admin/v1/payments/{txn_id}/events; Assert all 8 events are present in the correct order with timestamps in ascending order; Assert each event has event_type, timestamp, trace_id (same W3C trace_id across all events); Execute a CPM Inbound payment as P-TEST-002; assert QR_GENERATED event appears before PREFUNDING_DEDUCTED; Assert RATE_LOCKED event timestamp equals or follows SCHEME_APPROVED (rate locked at commit); Assert trace_id in all events matches the Traceparent header of the original API request
**Deliverable:** test/e2e/hc/EventTrailCompletenessTest.java (or equivalent)
**Acceptance / logic checks:**
- MPM Inbound trail contains exactly 8 ordered events; no gaps between event types
- CPM trail has QR_GENERATED as first event before PREFUNDING_DEDUCTED
- All events share the same trace_id matching the W3C Traceparent request header
- RATE_LOCKED event timestamp is after SCHEME_APPROVED (never before commit)
- Event timestamps are strictly ascending (no two events share the same millisecond timestamp)
**Depends on:** 15.7-T03

### 15.7-T35 — E2E test: Full quote-to-settle flow for GME Remit (domestic) end-to-end  _(55 min)_
**Context:** Integration scenario combining HC-001, ZP-001, ZP-003 for the GME Remit (P-TEST-001, LOCAL, KRW) domestic flow. Flow: GET /v1/rates (same-currency short-circuit) -> POST /v1/payments -> payment.approved webhook -> ZP0011 batch -> ZP0012 confirmation -> ZP0061 morning settlement -> ZP0062 reconciled. Tests the complete Quote->Execute->Scheme->Approved->Settle lifecycle for domestic partner.
**Steps:** GET /v1/rates as P-TEST-001 for target_payout=13500 KRW; verify same-currency short-circuit (no USD pool fields); POST /v1/payments; wait for payment.approved webhook; record txn_id; Trigger end-of-day ZP0011 batch; verify txn_id appears in ZP0011 file with collection_amount=14000 KRW; Inject matching ZP0012; verify transaction status = RECONCILED; Trigger morning ZP0061 settlement; verify domestic net total in ZP0061 = 13500 KRW (target_payout, not collection_amount); Inject matching ZP0062; verify settlement batch status = RECONCILED and transaction status = SETTLED
**Deliverable:** test/e2e/flows/DomesticFullFlowTest.java (or equivalent)
**Acceptance / logic checks:**
- GET /v1/rates returns collection_amount=14000 with no USD pool fields
- payment.approved received with collection_amount=14000, service_charge=500 KRW visible
- ZP0011 record for txn_id has collection_amount=14000 KRW in correct file layout
- ZP0061 domestic total = 13500 KRW (net settlement — target_payout, not collection_amount)
- Transaction status transitions: PENDING -> APPROVED -> RECONCILED -> SETTLED in order
**Depends on:** 15.7-T03, 15.7-T22, 15.7-T23

### 15.7-T36 — E2E test: Full quote-to-settle flow for SendMN (cross-border inbound) end-to-end  _(55 min)_
**Context:** Integration scenario combining HC-003, ZP-001, ZP-003 for SendMN (P-TEST-002, OVERSEAS, USD) cross-border inbound flow. Flow: GET /v1/rates (full USD pool, RV-01 formula) -> POST /v1/payments (prefunding deducted) -> payment.approved -> ZP0011 -> ZP0012 -> ZP0061 gross settlement -> ZP0062. Tests the complete Quote->Execute->Scheme->Approved->Settle lifecycle for OVERSEAS partner. ZP0061 international gross total = full KRW payout amounts.
**Steps:** Reset P-TEST-002 prefunding to 50000.00 USD; GET /v1/rates as P-TEST-002 for target_payout=13500 KRW Inbound; verify full USD pool fields in response; POST /v1/payments; verify prefunding deducted by collection_usd=10.2564 USD; wait for payment.approved; Trigger ZP0011; verify txn appears in file; inject ZP0012; verify RECONCILED status; Trigger ZP0061; verify international gross total includes full 13500 KRW payout amount (not net); Inject ZP0062 matching; verify settlement batch RECONCILED and transaction SETTLED; verify prefunding not restored after settlement
**Deliverable:** test/e2e/flows/CrossBorderInboundFullFlowTest.java (or equivalent)
**Acceptance / logic checks:**
- GET /v1/rates returns all USD pool values matching RV-01 formulas within 0.01 USD tolerance
- Prefunding deduction = collection_usd = 10.2564 USD (within 0.01)
- ZP0061 international section includes 13500 KRW gross amount for this transaction
- Transaction status transitions: PENDING -> PREFUNDING_DEDUCTED -> APPROVED -> RECONCILED -> SETTLED
- Prefunding balance after settlement = balance after deduction (settlement does not double-restore)
**Depends on:** 15.7-T03, 15.7-T22, 15.7-T23

### 15.7-T37 — E2E test: Admin exception resolution flow end-to-end (AD-015 and UAT-008)  _(45 min)_
**Context:** Scenarios AD-015 and UAT-008 from QA-12 §5.2 and §10.2. When ZP0062 total differs from GME total, an exception is auto-flagged and appears in the Admin exception queue. Ops operator views the flagged exception, reviews the discrepancy details, resolves it (with a resolution note), and the exception is marked RESOLVED with a full audit trail. The resolved status must persist and the transaction must not be re-flagged on subsequent runs.
**Steps:** Inject ZP0062 with total differing by 500 KRW from GME total; trigger reconciliation; assert exception auto-flagged; Login to Admin System as Ops user; navigate to Exception Management; assert the flagged exception appears; Click the exception; assert discrepancy_amount=500 KRW, expected_total, actual_total are displayed; Enter resolution note: Confirmed rounding difference, accepted; click Resolve; Assert exception status = RESOLVED with resolver actor, timestamp, and note in audit log; Re-run reconciliation job; assert RESOLVED exception is not re-flagged
**Deliverable:** test/e2e/ad/AD015_ExceptionResolutionFlowTest.java (or equivalent)
**Acceptance / logic checks:**
- Exception appears in Admin queue with discrepancy_amount=500 KRW and correct expected/actual totals
- Ops can resolve exception with a note; status changes to RESOLVED
- Audit log entry contains resolver_actor, resolved_at timestamp, resolution_note
- Re-run reconciliation does not re-create exception for already-RESOLVED records
- RESOLVED exception is visible in Admin for audit purposes but filtered from active queue
**Depends on:** 15.7-T26, 15.7-T31

### 15.7-T38 — E2E test: Rate engine unit test vectors RV-01 through RV-04 via API  _(45 min)_
**Context:** QA-12 §4.2 rate engine vectors exercised at the API level. RV-01: cross-border MNT->KRW, target_payout=13500 KRW, cost_rate_coll=3500, cost_rate_pay=1350, m_a=0.015, m_b=0.010, service_charge=500 MNT. Expected: collection_usd=10.2564, send_amount=35897.44, collection_amount=36397.44. RV-02: identity leg A (cost_rate_coll=1.0). RV-03: both legs identity. RV-04: same-currency short-circuit (collection_amount=14000 KRW, no USD fields).
**Steps:** Using P-TEST-002 config (m_a=0.015, m_b=0.010, cost_rate_coll=MNT=3500, cost_rate_pay=KRW=1350), GET /v1/rates with target_payout=13500; assert RV-01 outputs within 0.01 tolerance; Using P-TEST-002 with USD Settle A (identity cost_rate_coll=1.0), GET /v1/rates; assert RV-02 outputs (send_amount=collection_usd=10.2564); Using P-TEST-003 config with both legs USD identity, GET /v1/rates with target_payout=100.00 USD; assert RV-03 (collection_usd=102.5641, collection_amount=103.0641); Using P-TEST-001 (domestic), GET /v1/rates with target_payout=13500 KRW; assert RV-04 (no USD fields, collection_amount=14000); For each vector, assert pool identity: abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) <= 0.01 (skip for RV-04); Assert offer_rate_coll derived formula for RV-01: 35897.44 / (10.2564 - 0.1538) = approx 3553.28
**Deliverable:** test/e2e/rate/RV01RV04_RateEngineApiVectorTest.java (or equivalent)
**Acceptance / logic checks:**
- RV-01: collection_usd=10.2564, send_amount=35897.44, collection_amount=36397.44 all within 0.01
- RV-02: send_amount equals collection_usd (identity leg A, cost_rate_coll=1.0)
- RV-03: collection_usd=102.5641, collection_amount=103.0641 within 0.01; both legs flagged IDENTITY
- RV-04: no USD pool fields in response; collection_amount=14000 KRW exactly
- offer_rate_coll for RV-01 = send_amount / (collection_usd - collection_margin_usd) within 0.10 MNT/USD
**Depends on:** 15.7-T03

### 15.7-T39 — E2E test: Rate engine edge cases RV-05 to RV-10 via API and unit assertions  _(50 min)_
**Context:** QA-12 §4.2 vectors RV-05 (Partner B within tolerance), RV-06 (Partner B over tolerance — covered by 15.7-T15), RV-07 (min margin exactly 2%), RV-08 (below-minimum rejected at config), RV-09 (rounding edge case target_payout=10001 KRW), RV-10 (service charge separation). RV-09: payout_usd_cost=7.40815, collection_usd=7.59810, send_amount=26593.33 MNT, pool identity must hold. RV-10: service_charge=5000 MNT, send_amount unchanged vs RV-01, collection_amount=40897.44.
**Steps:** RV-07: Configure rule with m_a=0.010, m_b=0.010 (2.0% combined); GET /v1/rates with target_payout=13500 KRW; assert collection_usd=10.2041 (1/0.980 path); RV-09: GET /v1/rates with target_payout=10001 KRW; assert payout_usd_cost=7.40815, collection_usd=7.59810, send_amount=26593.33 within 0.01; assert pool identity holds; RV-09 additional: assert all intermediate values stored with >= 4 decimal places in DB; rounding applied only at collection_amount; RV-10: Configure service_charge=5000 MNT for P-TEST-002 rule; GET /v1/rates with target_payout=13500 KRW; assert send_amount=35897.44 (unchanged vs RV-01), collection_amount=40897.44; Assert pool identity check for RV-10 uses only send_amount components (not affected by service_charge=5000 MNT); Assert service_charge recorded separately in revenue ledger, not added to payout_usd_cost or collection_usd
**Deliverable:** test/e2e/rate/RV05RV10_RateEngineEdgeCaseTest.java (or equivalent)
**Acceptance / logic checks:**
- RV-07: collection_usd=10.2041 within 0.01 (using 0.980 divisor for 2.0% combined margin)
- RV-09: pool identity delta <= 0.01 USD for odd payout amount 10001 KRW
- RV-09: no integer overflow or precision loss; intermediate values have >= 4 decimal places
- RV-10: service_charge=5000 MNT added only at final collection_amount step; send_amount=35897.44 unchanged
- RV-10: pool identity check passes (service_charge excluded from USD pool invariant computation)
**Depends on:** 15.7-T03

### 15.7-T40 — E2E test: Security — IDOR cross-partner data isolation and internal field exposure prevention  _(45 min)_
**Context:** QA-12 §5.3 PP-007, §7 API-05, and SEC-09. IDOR: Partner A cannot access Partner B transactions via URL manipulation. Internal fields (m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd, GME margin) must not appear in any partner-facing endpoint response or webhook payload. Sensitive data (API secrets, DB passwords) must not appear in error responses.
**Steps:** Create a transaction as P-TEST-001; record txn_id_P001; Authenticate as P-TEST-002; attempt GET /v1/payments/{txn_id_P001}; assert HTTP 403 (not 200 or 404); Attempt GET /v1/transactions with date filter; assert only P-TEST-002 transactions returned; Inspect all Partner API response bodies for GET /v1/rates, POST /v1/payments, GET /v1/payments/{id}: assert m_a, m_b, cost_rate_coll, cost_rate_pay absent; Send an invalid request to trigger a 4xx/5xx error; assert error body contains no stack trace, DB error message, or internal path; Check webhook payloads captured in stub: assert no m_a, m_b, or cost rate fields present
**Deliverable:** test/e2e/sec/SecurityIsolationTest.java (or equivalent)
**Acceptance / logic checks:**
- GET /v1/payments/{txn_id of other partner} returns HTTP 403 (IDOR blocked)
- GET /v1/transactions returns zero records from another partner even with correct date range
- m_a, m_b, cost_rate_coll, cost_rate_pay absent from all GET /v1/rates, POST /v1/payments, GET /v1/payments/{id} responses
- Error responses for invalid requests contain only error_code and message (no stack trace or DB details)
- Webhook payloads contain no internal margin or cost rate fields
**Depends on:** 15.7-T03

### 15.7-T41 — E2E test: /v1/balance endpoint — OVERSEAS partner returns balance, LOCAL partner handled correctly  _(35 min)_
**Context:** QA-12 §7.2 endpoint coverage. GET /v1/balance for OVERSEAS partner (P-TEST-002) must return current USD prefunding balance. For LOCAL partner (P-TEST-001), the appropriate response per API-05 contract must be returned (either 200 with a marker indicating no prefunding required, or a documented 4xx). Real-time accuracy: balance must reflect deductions made in-flight.
**Steps:** Reset P-TEST-002 prefunding to 50000.00 USD; GET /v1/balance as P-TEST-002; assert HTTP 200 with balance=50000.00 USD; Execute a payment as P-TEST-002; GET /v1/balance immediately after; assert balance = 50000.00 - collection_usd (within 0.01); GET /v1/balance as P-TEST-001 (LOCAL); assert response matches documented API-05 behavior (200 with appropriate response or documented 4xx); Validate GET /v1/balance response schema against openapi/partner-api.yaml; Assert P-TEST-001 cannot query P-TEST-002 balance (partner scoping)
**Deliverable:** test/e2e/pa/BalanceEndpointTest.java (or equivalent)
**Acceptance / logic checks:**
- GET /v1/balance for P-TEST-002 returns balance=50000.00 USD immediately after reset
- Balance reflects deduction within 1 second of payment initiation (real-time accuracy)
- GET /v1/balance response schema matches openapi/partner-api.yaml definition (zero schema mismatches)
- P-TEST-001 GET /v1/balance returns documented response per API-05 (not an undocumented 500)
- P-TEST-002 token cannot retrieve P-TEST-001 balance data (partner scoping enforced)
**Depends on:** 15.7-T03

### 15.7-T42 — E2E test: /v1/transactions list with filters, pagination, and CSV export contract  _(40 min)_
**Context:** QA-12 §7.2 and §5.3 PP-004. GET /v1/transactions supports filtering by date range and status, pagination, and CSV export. Filter by status=DECLINED must return only declined transactions. CSV export must contain the same rows as the filtered UI result. Response must validate against openapi/partner-api.yaml. Internal fields must be absent from both JSON and CSV responses.
**Steps:** Create 3 approved and 2 rejected (DECLINED) transactions as P-TEST-002 on different dates; GET /v1/transactions?from=today&to=today&status=DECLINED; assert 2 records returned; GET /v1/transactions?from=today&to=today (no status filter); assert 5 records returned; GET /v1/transactions with page_size=2&page=1; assert 2 records returned with next_page token; GET /v1/transactions?export=csv; assert CSV has same 5 rows and no m_a, m_b, cost rate columns; Validate JSON response schema against openapi/partner-api.yaml /v1/transactions definition
**Deliverable:** test/e2e/pa/TransactionsListTest.java (or equivalent)
**Acceptance / logic checks:**
- Status=DECLINED filter returns exactly 2 records (all declined, none approved)
- Pagination returns correct page size with next_page token for subsequent pages
- CSV export row count = JSON response row count for same filter parameters
- CSV column headers do not include m_a, m_b, cost_rate_coll, cost_rate_pay
- JSON response validates against openapi/partner-api.yaml schema (zero mismatches)
**Depends on:** 15.7-T03

### 15.7-T43 — E2E test: All documented API error codes triggered by contract tests  _(50 min)_
**Context:** QA-12 §7.4 requires every error code in the API-05 error catalog to be triggered by at least one test. Required error codes: INVALID_CREDENTIALS, RATE_QUOTE_EXPIRED, INSUFFICIENT_PREFUNDING, MERCHANT_INACTIVE, QR_DEACTIVATED, PARTNER_B_QUOTE_DEVIATION, PARTNER_B_QUOTE_UNAVAILABLE, NO_SCHEME_FOR_LOCATION, DUPLICATE_IDEMPOTENCY_KEY, PAYMENT_NOT_FOUND, CANCEL_NOT_ALLOWED.
**Steps:** Create a parametrized test class that defines one test method per error code; INVALID_CREDENTIALS: POST /v1/auth/token with wrong key; RATE_QUOTE_EXPIRED: submit POST /v1/payments with an expired quote_id; INSUFFICIENT_PREFUNDING: payment attempt with balance 0.00; MERCHANT_INACTIVE: payment with M-TEST-0002; QR_DEACTIVATED: payment with QR-TEST-0005; PARTNER_B_QUOTE_DEVIATION: see 15.7-T15; PARTNER_B_QUOTE_UNAVAILABLE: see 15.7-T16; NO_SCHEME_FOR_LOCATION: payment with partner having no rule for given direction; DUPLICATE_IDEMPOTENCY_KEY: submit a second payment with a completed transaction idempotency key but different body; PAYMENT_NOT_FOUND: GET /v1/payments/{random-uuid}; CANCEL_NOT_ALLOWED: cancel on settled transaction
**Deliverable:** test/e2e/pa/ErrorCodeCoverageTest.java (or equivalent) with 11 test methods
**Acceptance / logic checks:**
- Each of the 11 error codes is returned with the exact code string (case-sensitive) matching API-05 catalog
- HTTP status codes match API-05 specification for each error (e.g. 401 for INVALID_CREDENTIALS, 422 for INSUFFICIENT_PREFUNDING)
- Error response body contains error_code and message fields; no internal details leaked
- No error code from the catalog is absent from the test suite (all 11 covered)
- DUPLICATE_IDEMPOTENCY_KEY triggered when body differs from original request with same key
**Depends on:** 15.7-T03, 15.7-T08, 15.7-T12, 15.7-T14, 15.7-T15, 15.7-T16, 15.7-T21

### 15.7-T44 — UAT scenario UAT-003 and UAT-004: GME Remit MPM domestic and OVERSEAS MPM inbound with Ops sign-off checklist  _(55 min)_
**Context:** UAT scenarios UAT-003 and UAT-004 from QA-12 §10.2. UAT-003 (GME Remit team): MPM domestic payment in staging environment, payment.approved received, collection = payout + KRW 500. UAT-004 (GME Ops Monitoring): MPM Inbound OVERSEAS, prefunding deducted, webhook received, rate-engine fields match. These are business-facing sign-off scenarios run in staging with the actual GME Remit team and Ops monitoring dashboard.
**Steps:** Seed staging environment with P-TEST-001 (TestRemit) and P-TEST-002 (TestSendMN) using production-equivalent credentials; UAT-003: Execute MPM domestic payment via Partner API as P-TEST-001; assert payment.approved, collection_amount=target_payout+500 KRW; Share payment.approved webhook payload with GME Remit team representative for sign-off; UAT-004: Execute MPM Inbound payment as P-TEST-002; verify prefunding deducted, webhook received with collection_usd matching rate engine formula; GME Ops Monitoring role: verify transaction visible in Admin transaction monitor with all rate-engine fields; Document sign-off confirmation from GME Remit team and GME Ops Monitoring in UAT sign-off sheet
**Deliverable:** UAT-003-004 sign-off checklist completed and signed by GME Remit team and GME Ops Monitoring
**Acceptance / logic checks:**
- UAT-003: collection_amount = target_payout + 500 KRW confirmed by GME Remit team representative
- UAT-004: prefunding deduction matches collection_usd from GET /v1/rates response (within 0.01 USD)
- Rate-engine fields visible in Admin transaction monitor match webhook payload values
- Both scenarios pass without workaround in the staging environment
- Sign-off sheet has named signatory and timestamp for each UAT scenario
**Depends on:** 15.7-T03, 15.7-T04, 15.7-T06

### 15.7-T45 — UAT scenario UAT-007 and UAT-008: ZeroPay morning settlement cycle and exception resolution in staging  _(55 min)_
**Context:** UAT scenarios UAT-007 and UAT-008 from QA-12 §10.2. UAT-007 (GME Ops Settlement): ZP0061 transmitted by 05:00 KST, ZP0062 received and reconciled. UAT-008: Ops resolves a flagged discrepancy in staging with full audit trail. Entry criteria: staging environment seeded; Ops team trained; KFTC pre-production test environment available.
**Steps:** Seed staging with 5 transactions (domestic and inbound mix) for settlement day; Trigger morning settlement batch; verify ZP0061 generated and transmitted to 한결원 pre-prod SFTP by 05:00 KST window; Receive ZP0062 confirmation from 한결원 pre-prod; verify GME totals match ZP result; Inject one discrepancy into a test ZP0062 variant; verify auto-flag and exception queue in Admin; GME Ops Settlement operator resolves the exception with resolution note; verify audit trail; GME Ops Settlement signs off UAT-007 and UAT-008 on UAT sign-off sheet
**Deliverable:** UAT-007-008 sign-off checklist completed and signed by GME Ops Settlement analyst
**Acceptance / logic checks:**
- ZP0061 delivered to 한결원 pre-prod SFTP within 05:00 KST window in staging
- ZP0062 reconciliation marks settlement batch as RECONCILED with zero discrepancy for matching case
- Exception auto-flagged with correct discrepancy_amount for injected mismatch case
- Ops exception resolution recorded in audit log with actor name, timestamp, note
- GME Ops Settlement analyst sign-off captured with name and timestamp on UAT sign-off sheet
**Depends on:** 15.7-T23, 15.7-T37

### 15.7-T46 — Write E2E test execution report and go-live readiness checklist items for WBS 15.7  _(45 min)_
**Context:** QA-12 §12.1 and §12.2 require a test deliverables report and a go-live readiness checklist. The E2E execution report must capture: pass/fail count by scenario ID, any P1/P2 defects found, rate-engine vector pass status (RV-01 to RV-10), pool-identity assertion status, and sign-off status by UAT role. Go-live checklist items 3, 12, and 13 (rate engine vectors pass, pool-identity alert wired, rate-lock verified) are confirmed by this work-package.
**Steps:** Run the full E2E suite (15.7-T04 through 15.7-T45) in CI; capture JUnit/pytest XML report; Generate HTML test report with scenario ID, status (PASS/FAIL), duration, and any failure message; Confirm rate engine vectors RV-01 to RV-10 all PASS; record in go-live checklist item 3; Confirm pool-identity assertion alert is wired to OPS alerting channel; record in checklist item 12; Confirm rate-lock behavior verified in staging; record in checklist item 13; Commit the test report artifact to the repo under test-results/e2e-report-{date}.html
**Deliverable:** test-results/e2e-report-{date}.html with all scenario results; go-live checklist items 3, 12, 13 marked complete
**Acceptance / logic checks:**
- Report contains one row per scenario ID (HC-001 through HC-015, PF-001 to PF-008, ZP-001 to ZP-014, RF-001 to RF-004, PA-001 to PA-008, AD-001 to AD-015, UAT-003 to UAT-013)
- All rate-engine vectors RV-01 to RV-10 marked PASS in report
- Zero P1 defects open at time of report generation
- Go-live checklist items 3 (rate engine vectors), 12 (pool-identity alert), and 13 (rate-lock) checked off with evidence links
- Report artifact committed to repo and accessible via CI artifact store
**Depends on:** 15.7-T04, 15.7-T05, 15.7-T06, 15.7-T07, 15.7-T08, 15.7-T09, 15.7-T10, 15.7-T11, 15.7-T12, 15.7-T13, 15.7-T14, 15.7-T15, 15.7-T16, 15.7-T17, 15.7-T18, 15.7-T19, 15.7-T20, 15.7-T21, 15.7-T22, 15.7-T23, 15.7-T24, 15.7-T25, 15.7-T26, 15.7-T27, 15.7-T28, 15.7-T29, 15.7-T30, 15.7-T31, 15.7-T32, 15.7-T33, 15.7-T34, 15.7-T35, 15.7-T36, 15.7-T37, 15.7-T38, 15.7-T39, 15.7-T40, 15.7-T41, 15.7-T42, 15.7-T43, 15.7-T44, 15.7-T45


## WBS 15.8 — Performance & load testing
### 15.8-T01 — Define performance test environment spec and toolchain selection  _(45 min)_
**Context:** WBS 15.8 covers performance and load testing against NFR-10 targets. The staging environment must be provisioned at production scale before any load test can run (QA-12 §8.1). NFR-10 §3.4 defines Phase 1 minimum: Hub Core API 2 active-active instances, DB 4 vCPU/16 GB RAM + 1 read replica, Redis 1 node 2 GB, batch processor 1 active + 1 standby. Connection pool baselines: Hub Core 20-50 DB / 10-20 Redis per instance. Choose between k6, Gatling, or JMeter as the load-generation tool; document the chosen tool and its version.
**Steps:** Document the production-scale staging environment topology (instance types, replica counts, network zones) matching NFR-10 §3.4 Phase 1 minimums.; Select and document the load-generation tool (k6/Gatling/JMeter) with version and rationale.; Define where metrics are captured: API Gateway response point (excluding partner network round-trip) per NFR-10 §2.1.; Record connection pool baseline config per NFR-10 §3.3: Hub Core 20-50 DB connections and 10-20 Redis connections per instance.; Create a shared test-config file listing all NFR targets as constants: quote p95 500ms, payment-execute p95 1500ms, payment p99 3000ms, status-query p95 300ms, CPM-generate p95 800ms, CPM-commit p95 1200ms.
**Deliverable:** Performance test environment spec document (perf-env-spec.md) and shared NFR constants config file (nfr_targets.yaml or nfr_targets.json) committed to the test repo.
**Acceptance / logic checks:**
- Spec lists at least 2 Hub Core API instances, DB primary + 1 read replica, Redis node, and batch processor as per NFR-10 §3.4.
- nfr_targets file contains all 7 latency targets with exact values from NFR-10 §2.1 (e.g. GET /v1/rates p95=500ms, POST /v1/payments p95=1500ms, p99=3000ms).
- Connection pool baseline values are recorded and match NFR-10 §3.3 ranges exactly.
- Document explicitly states measurement point is at the API Gateway response, excluding partner network.
- Tool choice is recorded with version number and installation steps that CI can replicate.

### 15.8-T02 — Create shared test fixtures: seed partners, rules, and treasury rates  _(40 min)_
**Context:** All performance tests require pre-seeded data: two partners (GME Remit = LOCAL/KRW, SendMN = OVERSEAS/USD), a ZeroPay scheme record, and at least one Rule per partner. Treasury rates needed: treasury.usd_KRW (e.g. 1350.00) and treasury.usd_USD = 1.0. For OVERSEAS tests, SendMN must have a prefunding balance of at least USD 100,000. Merchant records (~500 in local DB) and QR codes must exist for MPM payment tests. Rate-engine rule: m_a=1.5%, m_b=1.0%, service_charge=500 KRW (domestic GME Remit rule). All seeding must be idempotent (safe to re-run).
**Steps:** Write a seed SQL/migration script that inserts ZeroPay scheme, GME Remit (LOCAL) partner, SendMN (OVERSEAS) partner, and their rules; use ON CONFLICT DO NOTHING.; Insert treasury rates: treasury.usd_KRW=1350.00, treasury.usd_USD=1.0.; Set SendMN prefunding_balance=100000.00 USD in the prefunding ledger.; Seed 500 test merchant records and 500 QR codes linked to ZeroPay scheme.; Verify idempotency: running the seed script twice leaves identical DB state with no duplicate rows.
**Deliverable:** Seed script file (perf_seed.sql or equivalent migration) that can be run before any load test to establish baseline state.
**Acceptance / logic checks:**
- After seed: SELECT COUNT(*) FROM merchants returns >= 500.
- SendMN prefunding balance record exists with balance = 100000.00 USD.
- GME Remit rule has m_a=0.015, m_b=0.010, service_charge=500 in KRW settle-A currency.
- Running seed twice does not create duplicate partner, scheme, or merchant records.
- treasury.usd_KRW=1350.00 and treasury.usd_USD=1.0 are present in the rates table.
**Depends on:** 15.8-T01

### 15.8-T03 — Implement load test script: GET /v1/rates quote endpoint at 200 TPS  _(50 min)_
**Context:** NFR-10 §2.2 specifies quote request throughput target of 200 TPS (Phase 1). NFR-10 §2.1 specifies GET /v1/rates p50 < 150ms, p95 < 500ms, p99 < 1000ms. The rate engine performs: 5-step USD-volume margin calculation using treasury.usd_{ccy} rates, m_a/m_b margins from the rule, and target_payout. No scheme call is made. The script must authenticate as SendMN (OVERSEAS) partner and call GET /v1/rates with params: scheme=ZEROPAY, partner_id=SENDMN, target_payout=50000 (KRW), direction=Inbound. Ramp: 0 to 200 VU over 30s, sustain 200 VU for 5 min, ramp down 30s.
**Steps:** Create the load test script file (e.g. load_rates.js for k6 or equivalent) with the ramp profile described.; Configure authentication header using SendMN API key from the test secrets store.; Set the request to GET /v1/rates with query params: scheme=ZEROPAY, target_payout=50000, direction=Inbound.; Add p95 threshold assertion: p95 < 500ms; fail the test if breached.; Add throughput assertion: actual RPS >= 180 (90% of 200 TPS) during steady state.
**Deliverable:** Load test script file load_rates.[ext] in the perf-tests directory, runnable with a single CLI command, with pass/fail thresholds configured.
**Acceptance / logic checks:**
- Script reaches and sustains 200 concurrent virtual users during the 5-minute steady state window.
- p95 latency threshold is declared at 500ms and causes test failure if breached.
- p99 threshold is declared at 1000ms.
- Script uses correct authentication (partner API key, not admin credentials).
- Script targets GET /v1/rates endpoint only; no write operations included.
**Depends on:** 15.8-T01, 15.8-T02

### 15.8-T04 — Implement load test script: POST /v1/payments (MPM execute) at 50 TPS sustained  _(55 min)_
**Context:** NFR-10 §2.2 Phase 1 peak sustained TPS = 50 TPS for all partners combined. NFR-10 §2.1: POST /v1/payments p50 < 500ms, p95 < 1500ms, p99 < 3000ms. The payment call includes: prefunding deduction (SELECT FOR UPDATE on SendMN balance) + scheme (ZeroPay) call. In load tests the ZeroPay scheme call is mocked/stubbed at <= 800ms p95. Use unique Idempotency-Key per request (UUID). Ramp: 0 to 50 VU over 60s, sustain for 30 min (per NFR-10 §12 N-03 requirement: 30-minute run). Payload: {partner_id:SENDMN, scheme:ZEROPAY, merchant_qr:TEST_QR_001, target_payout:50000, currency:KRW, idempotency_key:unique_uuid}.
**Steps:** Create load test script load_payments_mpm.js (or equivalent) with 30-minute sustained run profile at 50 VU.; Generate a unique UUID Idempotency-Key per iteration to avoid DUPLICATE_IDEMPOTENCY_KEY errors.; Mock/stub the ZeroPay scheme endpoint to return HTTP 200 approved response within 200ms (configurable).; Set p95 threshold at 1500ms and p99 at 3000ms; assert error rate < 1% during steady state.; Log prefunding balance at start and end; assert balance decreased by exactly 50 VU * iterations * payout_usd_cost.
**Deliverable:** Load test script load_payments_mpm.[ext] in perf-tests directory with 30-minute run profile and ZeroPay stub configuration.
**Acceptance / logic checks:**
- Test sustains 50 TPS for full 30 minutes without exceeding 1% error rate.
- p95 latency threshold of 1500ms is declared and causes test failure if breached.
- Each iteration uses a distinct Idempotency-Key; zero DUPLICATE_IDEMPOTENCY_KEY errors in results.
- ZeroPay stub is clearly identified in script config so it is not accidentally used against production.
- p99 threshold of 3000ms is declared.
**Depends on:** 15.8-T01, 15.8-T02

### 15.8-T05 — Implement burst TPS test: POST /v1/payments at 100 TPS for 60 seconds  _(40 min)_
**Context:** NFR-10 §2.2: Burst TPS = 100 TPS for up to 60 seconds (Phase 1 target). NFR-10 §12 N-04 requires a dedicated burst load test. This is separate from the sustained test (T04). The burst scenario simulates peak queue-clearing events. Ramp aggressively to 100 VU in 10s, sustain exactly 60s, ramp down in 10s. Pass criterion: p95 <= 3000ms during the 60-second burst window; error rate <= 2% (slightly relaxed vs. sustained). The ZeroPay scheme stub from T04 is reused. Prefunding balance must be sufficient (100000 USD) to avoid INSUFFICIENT_PREFUNDING rejections during burst.
**Steps:** Create burst test script load_payments_burst.js with aggressive ramp (10s) to 100 VU, 60s sustain, 10s ramp-down.; Reuse ZeroPay stub from T04.; Assert p95 during burst window is <= 3000ms; error rate <= 2%.; Assert that zero INSUFFICIENT_PREFUNDING errors occur (prefunding check: 100 TPS * 60s * ~0.04 USD/txn cost = ~240 USD max deducted from 100000 USD balance).; Record the peak actual TPS achieved during the 60-second window.
**Deliverable:** Burst load test script load_payments_burst.[ext] in perf-tests directory targeting 100 TPS / 60-second window.
**Acceptance / logic checks:**
- Script reaches 100 VU within 10 seconds of start.
- Burst sustain window is exactly 60 seconds.
- p95 threshold is 3000ms with explicit pass/fail assertion.
- Zero INSUFFICIENT_PREFUNDING errors (prefunding balance 100000 USD far exceeds burst consumption).
- Script output reports the peak TPS achieved for inclusion in the performance report.
**Depends on:** 15.8-T04

### 15.8-T06 — Implement CPM QR-generate load test: POST /v1/payments/cpm/generate at 50 TPS  _(40 min)_
**Context:** NFR-10 §2.1: POST /v1/payments/cpm/generate p50 < 300ms, p95 < 800ms, p99 < 1500ms. This endpoint triggers prefunding deduction for OVERSEAS partners at QR generation time (not at commit). The prefunding deduction uses SELECT FOR UPDATE on the SendMN balance. No scheme call is made at this step; QR is generated locally. Use 50 TPS (same as sustained payment TPS). Payload: {partner_id:SENDMN, scheme:ZEROPAY, target_payout:50000, currency:KRW}. Ramp: 0 to 50 VU over 30s, sustain 10 minutes.
**Steps:** Create script load_cpm_generate.js targeting POST /v1/payments/cpm/generate.; Set p95 threshold 800ms and p99 threshold 1500ms.; Confirm no ZeroPay scheme call is triggered (verify via mock or response body: response should contain a qr_code field, not a scheme_reference).; Assert error rate < 1% and zero INSUFFICIENT_PREFUNDING errors during 10-min run (at 50 TPS * 600s * 0.04 USD = 1200 USD total < 100000 USD balance).; Record p50 and assert it is < 300ms.
**Deliverable:** Load test script load_cpm_generate.[ext] in perf-tests directory targeting CPM QR-generate endpoint.
**Acceptance / logic checks:**
- p95 threshold 800ms is declared and fails test if breached.
- p99 threshold 1500ms is declared.
- Response body contains qr_code field (not payment_id from scheme) confirming correct endpoint under test.
- Zero INSUFFICIENT_PREFUNDING errors across the 10-minute run.
- p50 is reported in test output.
**Depends on:** 15.8-T04

### 15.8-T07 — Implement CPM commit load test: POST /v1/payments/cpm/commit at 50 TPS  _(50 min)_
**Context:** NFR-10 §2.1: POST /v1/payments/cpm/commit p50 < 400ms, p95 < 1200ms, p99 < 2500ms. This endpoint involves a ZeroPay scheme round-trip (authorisation relay). Stub ZeroPay to respond within 200ms. The commit call requires a valid cpm_token from a prior generate call; pre-generate a pool of 10000 cpm_tokens in the DB before the test. Payload: {cpm_token: <pre-generated>, idempotency_key: unique_uuid}. Ramp: 0 to 50 VU over 30s, sustain 10 minutes.
**Steps:** Pre-generate 10000 CPM tokens in the test DB using the seed tool or a setup script.; Create script load_cpm_commit.js that reads tokens from the pre-generated pool (each token used once).; Stub ZeroPay commit relay to return HTTP 200 within 200ms.; Set p95 threshold 1200ms and p99 threshold 2500ms; error rate < 1%.; Assert that all pre-generated tokens are consumed once only (no re-use that would cause DUPLICATE_IDEMPOTENCY_KEY).
**Deliverable:** Load test script load_cpm_commit.[ext] plus CPM token pre-generation setup script in perf-tests directory.
**Acceptance / logic checks:**
- p95 threshold 1200ms is declared and fails test if breached.
- p99 threshold 2500ms is declared.
- Token pool size (10000) is sufficient for 50 TPS * 600s = 30000 iterations: script must pause or re-seed if pool exhausted (document the re-seed behaviour).
- Zero DUPLICATE_IDEMPOTENCY_KEY errors in results.
- ZeroPay stub is clearly labeled and the stub response time is configurable.
**Depends on:** 15.8-T06

### 15.8-T08 — Implement status-query load test: GET /v1/payments/{id} at 200 TPS  _(35 min)_
**Context:** NFR-10 §2.1: GET /v1/payments/{id} p50 < 100ms, p95 < 300ms, p99 < 600ms. This is a DB-read-only path (no scheme call, no prefunding deduction). It hits the read replica in production under load. In staging, ensure the read replica is enabled. Pre-generate 50000 payment IDs in the DB. VU reads IDs randomly from the pool to avoid query cache saturation. Ramp: 0 to 200 VU over 30s, sustain 10 minutes. Expected throughput 200 TPS (same as quote requests per NFR-10 §2.2 for reads).
**Steps:** Pre-generate or use existing 50000 payment records in the DB from prior test runs or seed.; Create script load_payment_status.js that picks a random payment_id from the pool each iteration.; Set p95 threshold 300ms and p99 threshold 600ms.; Assert error rate = 0% (a DB read on a valid ID should never fail).; Record p50 and assert it is < 100ms.
**Deliverable:** Load test script load_payment_status.[ext] in perf-tests directory targeting GET /v1/payments/{id}.
**Acceptance / logic checks:**
- p95 threshold 300ms is declared and fails test if breached.
- p99 threshold 600ms is declared.
- Script randomises payment_id selection to prevent trivial cache hits.
- Error rate is 0% (any 404 or 5xx counts as a failure; all IDs must exist in DB before test).
- p50 is reported in output and expected to be under 100ms.
**Depends on:** 15.8-T01, 15.8-T02

### 15.8-T09 — Implement concurrent prefunding atomicity test: SELECT FOR UPDATE under 2x peak TPS  _(55 min)_
**Context:** NFR-10 §12 N-08 requires zero over-deductions at 2x peak TPS (100 concurrent requests). The prefunding deduction path uses: BEGIN; SELECT balance FROM prefunding WHERE partner_id=? FOR UPDATE; UPDATE prefunding SET balance=balance-deduction WHERE partner_id=?; COMMIT. Test: SendMN prefunding_balance = USD 50.00 (small balance). Each transaction deducts USD 1.00. 100 concurrent threads fire simultaneously. Expected: exactly 50 succeed (balance reaches 0), remaining 50 return INSUFFICIENT_PREFUNDING. Final DB balance must be exactly 0.00, never negative.
**Steps:** Set SendMN prefunding_balance = 50.00 USD in the test DB.; Write a concurrent test (JMeter thread-group or k6 parallel execution) that fires exactly 100 simultaneous POST /v1/payments calls each requiring deduction of 1.00 USD from SendMN.; Stub ZeroPay to respond 200ms (approved) so the deduction path is the bottleneck.; After test: query SELECT balance FROM prefunding WHERE partner_id='SENDMN'; assert it equals exactly 0.00.; Count HTTP 409 INSUFFICIENT_PREFUNDING responses; assert count = 50.
**Deliverable:** Concurrent atomicity test script (perf_prefunding_atomicity.[ext]) that verifies zero over-deduction at 100 concurrent requests.
**Acceptance / logic checks:**
- Final prefunding balance is exactly 0.00 USD (not negative).
- Exactly 50 requests succeed (HTTP 200) and exactly 50 fail with INSUFFICIENT_PREFUNDING (HTTP 409 or 422).
- No DB constraint violation or negative balance is present in the DB after the test.
- Test is reproducible: running again after resetting balance to 50.00 yields the same outcome.
- Test result is captured in the performance report as evidence for NFR N-08.
**Depends on:** 15.8-T04

### 15.8-T10 — Implement throughput step-load test: ramp from 10% to 150% of peak TPS  _(50 min)_
**Context:** QA-12 §8.1 throughput test: step load from 10% to 150% of peak TPS; pass criterion: no error rate increase below 100% peak load (50 TPS). Steps: 5 TPS -> 10 TPS -> 25 TPS -> 50 TPS -> 75 TPS. Each step holds for 3 minutes. At 50 TPS (100%) error rate must be < 1%. At 75 TPS (150%) some degradation is permitted but no data corruption (4xx/5xx <= 5%). The 150% step exposes the max sustainable TPS and connection pool saturation point. Use POST /v1/payments (MPM) with ZeroPay stub.
**Steps:** Create step-load script load_throughput_step.js with 5 stages: 5, 10, 25, 50, 75 VU each held for 3 minutes.; Record p95 latency and error rate at the end of each step; annotate the results.; Assert error rate = 0% at steps 5/10/25 TPS; < 1% at 50 TPS; <= 5% at 75 TPS.; Log DB connection pool utilisation metric (db_connection_pool_utilisation gauge) at each step.; Identify the TPS at which p95 first exceeds 1500ms (the latency cliff point) and record it.
**Deliverable:** Step-load test script load_throughput_step.[ext] and a per-step results table (CSV or JSON) in perf-tests/results/.
**Acceptance / logic checks:**
- Script executes all 5 ramp stages in sequence without manual intervention.
- Error rate at 50 TPS (100% peak) is < 1%.
- Results table captures p50, p95, p99, error_rate, and db_pool_utilisation for each step.
- Latency cliff point (TPS where p95 first exceeds 1500ms) is identified and documented.
- Test result is captured as evidence for NFR N-03 (50 TPS peak sustained).
**Depends on:** 15.8-T04

### 15.8-T11 — Implement stress test: 200% of peak TPS (100 TPS) for 15 minutes  _(55 min)_
**Context:** QA-12 §8.1 stress test: 200% of peak (100 TPS) for 15 minutes. Pass criteria: 4xx/5xx <= 1%; no data corruption. This differs from the burst test (T05) which is only 60 seconds. The 15-minute duration tests circuit-breaker behaviour and DB connection pool exhaustion. ZeroPay stub must be configured for 200ms response. Prefunding balance must be reset to 500000 USD before this test. Monitor circuit_breaker_state metric; if it opens the test is still valid as long as SCHEME_UNAVAILABLE responses are 503 (not data corruption). Assert pool identity holds on a sample of 1000 committed transactions post-test.
**Steps:** Reset SendMN prefunding_balance = 500000.00 USD before test.; Create stress test script load_stress.js: ramp to 100 VU in 30s, sustain for 15 min, ramp down 30s.; Assert 4xx/5xx rate <= 1% during sustain window; log all non-200 response codes.; After test: query 1000 random committed transactions and verify pool identity: collection_usd - collection_margin_usd - payout_margin_usd = payout_usd_cost within 0.01 USD tolerance.; Record maximum db_connection_pool_utilisation reached; assert it does not exceed 90% (the P2 alert threshold from NFR-10 §7.5).
**Deliverable:** Stress test script load_stress.[ext] and post-test pool identity verification query (pool_identity_check.sql) in perf-tests directory.
**Acceptance / logic checks:**
- 4xx/5xx error rate is <= 1% over the full 15-minute sustain window.
- DB connection pool utilisation does not exceed 90% at any point during the test.
- Pool identity check on 1000 sampled transactions returns 0 violations (all within 0.01 USD tolerance).
- No data corruption: zero entries in pool_identity_breaches_total counter metric after the test.
- Test result captured as evidence for QA-12 §8.1 stress test row.
**Depends on:** 15.8-T10

### 15.8-T12 — Implement soak test: 72-hour sustained load at peak TPS  _(60 min)_
**Context:** NFR-10 §8.4 soak test: sustain target TPS for 4 hours without memory leak or latency degradation. QA-12 §8.1 extends this to 72 hours. Pass criteria: success rate > 98%; heap and DB connections stable. The soak test must be run in the staging environment. The test uses POST /v1/payments at 50 TPS. Every 30 minutes, record: JVM/process heap, DB connection pool utilisation, p95 latency (rolling 5 min), and payment success rate. At end of 72 hours: heap must not have grown by more than 20% vs. the 1-hour mark; DB connections must remain within baseline range (20-50 per Hub Core instance per NFR-10 §3.3).
**Steps:** Configure the sustained load script (reuse load_payments_mpm.js from T04 but with 72-hour duration).; Set up a monitoring snapshot job: every 30 minutes record heap_mb, db_pool_used, p95_latency_ms, success_rate into a CSV file soak_metrics.csv.; Set automated failure condition: if success_rate drops below 98% or p95 latency exceeds 3000ms for 10 consecutive minutes, the test fails and alerts.; After test: assert heap at hour 72 is within 120% of heap at hour 1.; Assert DB pool utilisation at hour 72 is within 120% of utilisation at hour 1 (no pool leak).
**Deliverable:** Soak test configuration (load_soak.yaml or load_soak.js) and monitoring snapshot script (soak_monitor.sh or equivalent) in perf-tests directory.
**Acceptance / logic checks:**
- Test runs for the full 72 hours (or 4 hours minimum for Phase 1 gate) without manual intervention.
- Success rate remains > 98% throughout.
- soak_metrics.csv has entries at every 30-minute interval with heap, pool, p95, and success_rate columns.
- Heap at hour 72 is within 120% of heap at hour 1.
- DB pool utilisation at hour 72 is within 120% of utilisation at hour 1.
**Depends on:** 15.8-T04, 15.8-T08

### 15.8-T13 — Implement batch performance test: ZP0011 generation within 02:00 KST window under max daily volume  _(50 min)_
**Context:** NFR-10 §2.3: ZP0011 (payment result aggregation) must be internally complete by 01:30 KST; transmitted to ZeroPay by 02:00 KST. QA-12 §8.1 batch performance: simulate maximum daily transaction count (~5000 per NFR-10 §3.1 Phase 1). Seed the DB with 5000 committed payment transactions (status=COMPLETED) with created_at timestamps in the prior business day. Trigger the batch job manually; measure wall-clock time from job start to SFTP put completion. Pass: job completes in < 30 minutes (01:30 deadline gives 30-min buffer before 02:00). Also verify ZP0011 file row count = 5000 and trailer checksum is valid per SCH-06.
**Steps:** Seed 5000 COMPLETED payment records with yesterday's date range in the DB using batch_seed.sql.; Trigger the ZP0011 batch job (via scheduled-job API or direct cron trigger) and record start timestamp.; Measure elapsed time until the SFTP put completes; assert elapsed < 1800 seconds (30 minutes).; Verify the generated ZP0011 file: row count = 5000; trailer record contains the correct transaction count and sum of KRW amounts.; Log batch_job_duration_ms metric; assert it is < 1800000ms.
**Deliverable:** Batch performance test script (batch_perf_zp0011.[ext]) and seed SQL (batch_seed_5000.sql) in perf-tests directory.
**Acceptance / logic checks:**
- ZP0011 generation and SFTP put completes in under 30 minutes for 5000 transactions.
- ZP0011 file row count equals 5000 (data rows only, excluding header and trailer).
- Trailer KRW sum matches sum of target_payout in the 5000 seeded transactions.
- batch_job_duration_ms metric is emitted and recorded.
- Test is re-runnable: seeded transactions can be re-used by resetting batch_file_id to NULL before re-run.
**Depends on:** 15.8-T02

### 15.8-T14 — Implement batch performance test: ZP0061 settlement file within 05:00 KST window  _(50 min)_
**Context:** NFR-10 §2.3: ZP0061 (settlement request) internally complete by 04:30 KST; transmitted to ZeroPay by 05:00 KST. Seed 5000 COMPLETED payments eligible for settlement (not yet assigned a settlement_batch_id). The ZP0061 batch aggregates payments into a settlement request. Domestic (GME Remit): net amount = sum of target_payout. International (SendMN): gross amount per transaction. The batch job must set settlement_batch_id atomically on all included transactions to prevent double-submission (exactly-once per NFR-10 §5.3). Pass: job completes in < 30 minutes; zero transactions with duplicate settlement_batch_id assignment.
**Steps:** Seed 5000 COMPLETED payments with settlement_batch_id = NULL using batch_seed_settlement.sql (500 domestic, 4500 overseas split).; Trigger ZP0061 batch job; record start and end timestamps.; Assert job completes in < 1800 seconds (30 minutes).; Query SELECT COUNT(*) FROM payments WHERE settlement_batch_id = <new_batch_id>; assert count = 5000 (all included exactly once).; Verify ZP0061 file: domestic net total = sum of domestic target_payout; overseas gross total = sum of overseas target_payout.
**Deliverable:** Batch performance test script (batch_perf_zp0061.[ext]) and seed SQL (batch_seed_settlement.sql) in perf-tests directory.
**Acceptance / logic checks:**
- ZP0061 generation and SFTP put completes in under 30 minutes for 5000 transactions.
- All 5000 transactions have a non-null settlement_batch_id after the job; none have a duplicate batch assigned.
- Domestic net total in ZP0061 file matches sum(target_payout) for domestic transactions.
- Overseas gross total in ZP0061 file matches sum(target_payout) for overseas transactions.
- Re-running the batch job does not re-include already-settled transactions (idempotency via settlement_batch_id check).
**Depends on:** 15.8-T13

### 15.8-T15 — Implement batch performance test: merchant sync ingest (ZP0041) within 30 minutes of receipt  _(55 min)_
**Context:** NFR-10 §2.3: ZP0041 (merchant sync inbound from ZeroPay) must be ingested within 30 minutes of receipt. Seed a synthetic ZP0041 file with 50000 merchant records (approaching the 500000 Phase 1 capacity in NFR-10 §3.1 for a partial sync). Drop the file onto the SFTP inbound directory. Measure time from file detection to DB ingest completion (all 50000 merchant records created or updated). Pass: elapsed < 1800 seconds. Also verify QR sync (ZP0043): deactivated QRs must be blocked immediately (within 15 minutes per NFR-10 §2.3). Generate a ZP0043 file with 100 deactivated QR codes; after ingest, POST /v1/payments with a deactivated QR must return QR_DEACTIVATED.
**Steps:** Generate synthetic ZP0041 file with 50000 merchant records (field layout per SCH-06) using generate_zp0041.py or equivalent.; Drop file onto staging SFTP inbound directory; record timestamp T0.; Poll DB until merchant record count increases by 50000 or 30 minutes elapse; record timestamp T1.; Assert T1 - T0 < 1800 seconds.; Generate ZP0043 file with 100 deactivated QR codes; ingest it; then POST /v1/payments with one of those QR codes and assert HTTP response contains error code QR_DEACTIVATED.
**Deliverable:** Merchant sync batch performance test script (batch_perf_merchant_sync.[ext]) and synthetic file generators (generate_zp0041.py, generate_zp0043.py) in perf-tests directory.
**Acceptance / logic checks:**
- 50000 merchant records ingested in under 30 minutes.
- QR deactivation takes effect within 15 minutes of ZP0043 file receipt (test by timing the QR_DEACTIVATED rejection).
- POST /v1/payments with a QR code listed in ZP0043 returns QR_DEACTIVATED error code.
- Merchant record count in DB increases by exactly 50000 (no duplicates, no missed records).
- File detection (SFTP polling interval) is <= 1 minute (check polling config).
**Depends on:** 15.8-T13

### 15.8-T16 — Implement DB primary failover test: verify RTO < 5 minutes under load  _(55 min)_
**Context:** NFR-10 §6.3 and §8.4 N-11: DB primary failover must complete within 5 minutes. The test runs a background load at 20 TPS (reduced to avoid data loss during failover), then terminates the primary DB instance. Measure time from termination to first successful payment request completing (indicating new primary is live and app reconnected). Pass: RTO < 300 seconds. Also verify RPO = 0: no committed transactions are lost (synchronous replication required by NFR-10 §6.1).
**Steps:** Start background load at 20 TPS using load_payments_mpm.js with reduced VU count; let it run for 2 minutes to establish baseline.; Record the last committed transaction_id T_last before terminating primary.; Terminate the primary DB instance (e.g. stop the primary container or issue failover command).; Poll every 5 seconds: send GET /v1/health; record the timestamp of the first 200 response after failover.; Assert elapsed time from termination to first 200 health response < 300 seconds; query new primary for T_last to confirm it exists (RPO = 0).
**Deliverable:** Failover test script (failover_test.[ext]) in perf-tests directory with instructions for how to trigger primary termination in staging environment.
**Acceptance / logic checks:**
- Time from primary termination to first successful /v1/health 200 response is < 300 seconds.
- Transaction T_last committed before failover is present in the new primary after failover.
- No committed transaction is missing after failover (RPO = 0 verified by count comparison).
- Test instructions include how to verify synchronous replication was active (e.g. pg_stat_replication lag < 1s before termination).
- Test is documented as requiring manual triggering (not automated in CI) to avoid accidental production failover.
**Depends on:** 15.8-T04

### 15.8-T17 — Implement rate engine pool identity stress test: 10000 transactions  _(55 min)_
**Context:** NFR-10 §8.4 N-09: pool identity invariant must hold for 100% of cross-border transactions. NFR-10 §5.1 states: collection_usd - collection_margin_usd - payout_margin_usd = payout_usd_cost within tolerance 0.01 USD. Test generates 10000 cross-border transactions using randomised inputs: target_payout in range 10000-500000 KRW, m_a in range 1.0%-3.0%, m_b in range 1.0%-3.0% (always m_a+m_b >= 2.0%), cost_rate_pay = treasury.usd_KRW (1350.00), cost_rate_coll = 1.0 (USD settle-A). For each transaction, assert the identity holds. Also verify service_charge (500 KRW) is excluded from pool calculation.
**Steps:** Write a parameterised rate engine test that generates 10000 random input vectors using the ranges above.; For each vector, call the rate engine (directly or via GET /v1/rates) and capture: collection_usd, collection_margin_usd, payout_margin_usd, payout_usd_cost.; Assert |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| <= 0.01 for every vector.; Assert service_charge of 500 KRW does not appear in collection_usd or payout_usd_cost (verify: collection_amount = send_amount + 500 but pool values exclude the 500).; Record and report any vector that violates the identity; the test fails if any violation exists.
**Deliverable:** Pool identity stress test script (pool_identity_stress.[ext]) that generates and validates 10000 random cross-border rate engine invocations.
**Acceptance / logic checks:**
- All 10000 vectors satisfy |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| <= 0.01 USD.
- Zero violations reported; test fails if any violation is found.
- Service charge of 500 KRW is correctly excluded: for each vector, collection_usd * cost_rate_coll = send_amount (not send_amount + 500).
- m_a + m_b >= 0.02 is enforced for all test vectors (any vector with combined margin < 2% must be rejected by the engine with a config error, not silently computed).
- Test produces a summary line: Tested: 10000, Violations: 0, Max deviation: <N> USD.
**Depends on:** 15.8-T02

### 15.8-T18 — Verify webhook delivery throughput: 50 events/sec at 30-second SLA  _(55 min)_
**Context:** NFR-10 §2.2: webhook delivery throughput = 50 events/sec (Phase 1). NFR-10 §11.5: delivery within 30 seconds of transaction commit; 5 retry attempts with exponential backoff. Test: trigger 3000 completed transactions (50 TPS * 60s) via the payment load test; configure a test webhook endpoint (HTTP echo server) that records receipt timestamp. For each webhook, measure lag = receive_timestamp - transaction_committed_at. Assert p99 lag < 30 seconds. Also test delivery failure: configure a second partner with a webhook URL that returns 500; assert exactly 5 retry attempts are made with exponential backoff (1s, 2s, 4s, 8s, 16s) and then the webhook is marked failed.
**Steps:** Deploy an HTTP echo server as the test webhook endpoint; it records request timestamp and partner_id.; Run load_payments_mpm.js at 50 TPS for 60 seconds to generate 3000 transactions; all trigger payment.approved webhooks to the echo server.; Compute delivery lag for each webhook: receive_ts - committed_at; assert p99 < 30 seconds.; Configure a second test partner with a webhook URL returning HTTP 500; fire 1 payment; assert 5 delivery attempts are logged with delays approximately 1s, 2s, 4s, 8s, 16s between attempts.; After 5 failed retries, assert the webhook is marked failed (dead-letter) and a P2 alert is raised.
**Deliverable:** Webhook throughput test script (webhook_delivery_test.[ext]) and retry behaviour verification script (webhook_retry_test.[ext]) in perf-tests directory.
**Acceptance / logic checks:**
- p99 webhook delivery lag (receive_ts - committed_at) is < 30 seconds for 3000 events.
- 50 events/sec throughput is sustained without queue depth growth beyond 2000 (NFR-10 §7.5 alert threshold).
- Failed webhook partner receives exactly 5 delivery attempts (not 4, not 6).
- Exponential backoff delays are approximately 1s, 2s, 4s, 8s, 16s (within 20% tolerance per interval).
- After 5 failed retries, a P2 alert is generated (log entry or metric increment).
**Depends on:** 15.8-T04

### 15.8-T19 — Verify connection pool behaviour under load: assert no exhaustion at peak TPS  _(45 min)_
**Context:** NFR-10 §3.3: Hub Core API baseline DB connections 20-50 per instance; Redis 10-20 per instance. NFR-10 §7.5: P2 alert if db_connection_pool_utilisation > 90% for 2 minutes. Run the 50 TPS sustained test (T04) and simultaneously scrape the db_connection_pool_utilisation and Redis connection metrics every 10 seconds. Assert that during the 30-minute run: max DB pool utilisation <= 85% (below alert threshold); max Redis pool utilisation <= 85%. If either exceeds 90%, the test is a failure and the connection pool sizes must be tuned.
**Steps:** Extend or wrap load_payments_mpm.js to also poll the /metrics endpoint (Prometheus) every 10 seconds for db_connection_pool_utilisation{service=hub_core} and redis_pool_utilisation{service=hub_core}.; Record all sampled values into pool_metrics.csv.; Run the 30-minute sustained test at 50 TPS.; Assert max(db_connection_pool_utilisation) <= 0.85 across all samples.; Assert max(redis_pool_utilisation) <= 0.85 across all samples.
**Deliverable:** Pool monitoring wrapper script (pool_monitor.sh or integrated into k6 plugin) and pool_metrics.csv output format specification in perf-tests directory.
**Acceptance / logic checks:**
- db_connection_pool_utilisation never exceeds 0.85 (85%) during the 30-minute run at 50 TPS.
- Redis pool utilisation never exceeds 0.85 during the same run.
- pool_metrics.csv has at minimum 180 rows (one per 10-second interval over 30 minutes).
- If a sample exceeds 0.85, the test script logs a warning; if it exceeds 0.90, the test fails immediately.
- Test output states the peak utilisation values (e.g. DB peak: 62%, Redis peak: 41%).
**Depends on:** 15.8-T04

### 15.8-T20 — Verify replication lag under load: assert sync replica lag < 1 second  _(40 min)_
**Context:** NFR-10 §6.1 RPO = 0 for payment data requires synchronous database replication with lag < 1 second under load. NFR-10 §12 N-13 verification: verify sync replication lag < 1 second under load. During the 50 TPS sustained test, poll pg_stat_replication (PostgreSQL) every 30 seconds and record: write_lag, flush_lag, replay_lag. Assert all three lag values remain < 1 second throughout the test. If replay_lag exceeds 1 second, log a warning; if it exceeds 5 seconds, fail the test.
**Steps:** Write a DB monitor script (replica_lag_monitor.sh) that polls pg_stat_replication every 30 seconds during a load test run and writes write_lag, flush_lag, replay_lag to replica_lag.csv.; Run alongside load_payments_mpm.js at 50 TPS for 30 minutes.; Assert max(replay_lag) < 1 second across all samples.; Assert max(write_lag) < 1 second.; Document any spikes and their durations in the performance report.
**Deliverable:** Replication lag monitor script (replica_lag_monitor.sh) and replica_lag.csv output specification in perf-tests directory.
**Acceptance / logic checks:**
- max(replay_lag) across all samples is < 1 second.
- max(write_lag) across all samples is < 1 second.
- replica_lag.csv has at minimum 60 rows (one per 30-second interval over 30 minutes).
- Any lag spike > 500ms is flagged in the output with timestamp.
- Test fails if replay_lag exceeds 5 seconds at any point.
**Depends on:** 15.8-T04

### 15.8-T21 — Verify rate lock immutability under concurrent treasury rate update  _(50 min)_
**Context:** NFR-10 §5.4 N-10: once a transaction reaches step 6 (transaction_committed), all rate fields are permanently locked. Test scenario: commit transaction TX1 capturing collection_usd, send_amount, offer_rate_coll, cross_rate. Then update treasury.usd_KRW from 1350.00 to 1400.00 via Admin API. Then query TX1 and assert all locked fields are unchanged. Also verify that a new quote after the rate update uses 1400.00. Run 100 such commit-then-update cycles concurrently to ensure no race condition allows a committed transaction to be re-valued.
**Steps:** Commit one payment (TX1) and record all rate-lock fields: collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, offer_rate_coll, cross_rate, send_amount, service_charge, collection_amount, cost_rate_coll, cost_rate_pay.; Via Admin API, update treasury.usd_KRW = 1400.00.; Re-query TX1 from DB; assert all fields from step 1 are byte-for-byte identical.; Issue GET /v1/rates for a new quote; assert the returned offer_rate uses 1400.00 (not 1350.00).; Run 100 concurrent commit-then-update cycles via a test script; assert all 100 committed transactions retain their original locked values after the rate change.
**Deliverable:** Rate lock immutability test script (rate_lock_immutability_test.[ext]) with 100-cycle concurrent verification in perf-tests directory.
**Acceptance / logic checks:**
- All 11 rate-lock fields are identical before and after the treasury rate update for TX1.
- New quote after rate update returns offer_rate_coll calculated using treasury.usd_KRW=1400.00.
- All 100 concurrently committed transactions retain their original locked fields; zero mutations detected.
- Test queries both the API response and the DB record directly to confirm immutability at DB level.
- Test fails if any locked field differs by even 0.000001 USD.
**Depends on:** 15.8-T02, 15.8-T17

### 15.8-T22 — Verify circuit breaker behaviour under ZeroPay outage at load  _(55 min)_
**Context:** NFR-10 §4.3 and §4.4: if ZeroPay API error rate > 5% over 2 minutes, circuit breaker opens; new payment requests return SCHEME_UNAVAILABLE (HTTP 503). Circuit breaker closes after 3 successful probe requests. GET /v1/rates continues to respond during circuit-open state. Test: run load at 30 TPS; after 1 minute, configure ZeroPay stub to return HTTP 500 for all requests. After 2 minutes of errors, assert circuit breaker opens. Then restore ZeroPay stub; assert circuit breaker closes after 3 successful probes.
**Steps:** Start load_payments_mpm.js at 30 TPS with ZeroPay stub returning HTTP 200.; After 60 seconds, switch ZeroPay stub to return HTTP 500 for all requests.; Monitor circuit_breaker_state metric; assert it changes from 0 (closed) to 1 (open) within 120 seconds of first 500 response.; While circuit is open: assert GET /v1/rates returns HTTP 200 (rate calculation unaffected).; Restore ZeroPay stub to 200; assert circuit_breaker_state returns to 0 after exactly 3 successful probe responses.
**Deliverable:** Circuit breaker test script (circuit_breaker_test.[ext]) in perf-tests directory with stub toggle capability.
**Acceptance / logic checks:**
- circuit_breaker_state transitions to 1 (open) within 120 seconds of ZeroPay error rate exceeding 5%.
- GET /v1/rates returns HTTP 200 with valid rate payload while circuit is open.
- POST /v1/payments returns HTTP 503 with error code SCHEME_UNAVAILABLE while circuit is open.
- Circuit closes (state=0) after exactly 3 successful ZeroPay probe responses (not 2, not 4).
- Prefunding balance is not debited for payments rejected with SCHEME_UNAVAILABLE.
**Depends on:** 15.8-T04

### 15.8-T23 — Verify SFTP batch retry behaviour: 10-minute retry for 2 hours on outage  _(50 min)_
**Context:** NFR-10 §4.3: ZeroPay SFTP unavailable causes batch job to retry every 10 minutes for 2 hours; if still unavailable, Ops alert is escalated. Test in accelerated time: configure SFTP stub to refuse connections for 120 minutes equivalent (12 retry cycles). Assert: 12 retry attempts are logged at approximately 10-minute intervals. After 2 hours of failure, assert that a P1/P2 Ops alert is raised (check alert log or metric). Then restore SFTP stub; assert the batch job succeeds on the next retry.
**Steps:** Configure SFTP stub to reject all connections.; Trigger the ZP0011 batch job; observe retry log entries.; Assert retry interval is approximately 600 seconds (10 minutes) between attempts (within 30-second tolerance).; Assert that after 12 failed retries (~120 minutes), an alert is emitted with severity P1 or P2 and job_name=ZP0011_delivery.; Restore SFTP stub; assert next retry attempt succeeds and file is delivered.
**Deliverable:** SFTP retry behaviour test script (sftp_retry_test.[ext]) and SFTP stub configuration instructions in perf-tests directory.
**Acceptance / logic checks:**
- 12 retry attempts are logged with approximately 600-second intervals.
- Alert with P1 or P2 severity is emitted after the 12th failed retry (within 2 hours).
- After SFTP restoration, the next retry attempt succeeds and ZP0011 file is delivered.
- No data corruption: the file content generated on the 13th attempt is identical to the first attempt (idempotent batch generation per NFR-10 §5.3).
- Test evidence (retry log with timestamps) is captured for the performance report.
**Depends on:** 15.8-T13

### 15.8-T24 — Implement same-currency short-circuit performance test  _(45 min)_
**Context:** Rate engine canonical brief: if collection = settle_A = settle_B = payout (same currency), skip the USD pool and compute: collection_amount = target_payout + service_charge. For GME Remit domestic payments, payout ccy = KRW, settle_A = KRW, settle_B = KRW. The short-circuit path must be at least as fast as the USD-pool path. Verify p95 latency for GET /v1/rates same-currency (domestic GME Remit) is within the 500ms p95 target. Also verify the calculation: target_payout=50000 KRW, service_charge=500 KRW -> collection_amount=50500 KRW; collection_usd, payout_usd_cost, margins should all be 0 or omitted.
**Steps:** Run GET /v1/rates with params: partner_id=GMEREMIT, scheme=ZEROPAY, direction=Domestic, target_payout=50000, currency=KRW at 200 TPS for 5 minutes using load_rates_domestic.js.; Assert p95 < 500ms and p99 < 1000ms.; Validate response for a single domestic request: collection_amount=50500, send_amount=50000 (or omitted), collection_usd absent or 0.; Assert that m_a+m_b constraint (>= 2% for cross-border) is NOT applied to domestic same-currency rules (domestic rules allow 0% margin).; Run 1000 domestic rate engine invocations and assert collection_amount = target_payout + 500 for all of them.
**Deliverable:** Domestic rate load test script (load_rates_domestic.[ext]) and 1000-vector same-currency validation script (same_currency_check.[ext]) in perf-tests directory.
**Acceptance / logic checks:**
- p95 latency at 200 TPS for same-currency domestic quotes is < 500ms.
- collection_amount = target_payout + 500 for all 1000 test vectors.
- USD-pool fields (collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd) are absent or zero in domestic responses.
- Same-currency rule with m_a=0, m_b=0 is accepted without error (no min-margin rejection for domestic).
- Test result is included in the performance report as domestic-path validation.
**Depends on:** 15.8-T03, 15.8-T17

### 15.8-T25 — Implement Partner B quote deviation and unavailability latency tests  _(45 min)_
**Context:** Rate engine canonical brief: Partner B authoritative quote deviation beyond 1.0% tolerance triggers PARTNER_B_QUOTE_DEVIATION (do not commit); Partner B unreachable triggers PARTNER_B_QUOTE_UNAVAILABLE (no fallback). Both error paths must be tested under load to ensure they fail-fast without hanging. Target: PARTNER_B_QUOTE_UNAVAILABLE must return within the POST /v1/payments p99 = 3000ms SLA (the timeout must be < 3000ms). PARTNER_B_QUOTE_DEVIATION must return within p95 = 1500ms (computation completes; only the comparison fails). Configure a mock Partner B endpoint with configurable response: (a) slow response (3s timeout), (b) deviation response (rate 1.5% off).
**Steps:** Configure mock Partner B stub: scenario A = no response (timeout after 2900ms); scenario B = returns a rate 1.5% away from the GMEPay+ computed rate.; Run 100 POST /v1/payments requests against scenario A; assert all return PARTNER_B_QUOTE_UNAVAILABLE within 3000ms and none hang indefinitely.; Run 100 POST /v1/payments requests against scenario B; assert all return PARTNER_B_QUOTE_DEVIATION within 1500ms.; Assert that in both error cases prefunding deduction is NOT committed (balance unchanged after all 100 requests).; Assert that deviation is computed as |partner_b_rate - gme_rate| / gme_rate; 1.5% > 1.0% threshold triggers error; 0.9% does NOT trigger error.
**Deliverable:** Partner B error path load test script (partner_b_error_test.[ext]) in perf-tests directory with configurable stub for timeout and deviation scenarios.
**Acceptance / logic checks:**
- All 100 PARTNER_B_QUOTE_UNAVAILABLE responses return within 3000ms (p99 SLA).
- All 100 PARTNER_B_QUOTE_DEVIATION responses return within 1500ms (p95 SLA).
- Prefunding balance is identical before and after all 200 error requests.
- Deviation of 1.5% triggers PARTNER_B_QUOTE_DEVIATION; deviation of 0.9% does not.
- No request hangs beyond 3100ms (hard timeout enforced at application level).
**Depends on:** 15.8-T04

### 15.8-T26 — Verify rate quote TTL expiry under load: RATE_QUOTE_EXPIRED rejection  _(45 min)_
**Context:** Rate engine canonical brief: quote TTL default = 60 seconds (aggregator-bound) or 300 seconds otherwise; configurable 60-1800 seconds. validUntil = quote_issued_at + ttl. On commit, if current_time > validUntil, reject with RATE_QUOTE_EXPIRED. Test: set TTL=60s; obtain a quote; wait 61 seconds; attempt POST /v1/payments using that quote_id; assert RATE_QUOTE_EXPIRED (HTTP 422). Also verify that a commit at t=59s (within TTL) succeeds. Run 500 expired-quote attempts concurrently to verify the check holds under load and no expired quote ever commits.
**Steps:** Set quote TTL to 60 seconds in the rule config for SendMN.; Obtain 500 quotes via GET /v1/rates; record each quote_id and issued_at timestamp.; Wait 61 seconds; then fire all 500 POST /v1/payments concurrently using the expired quotes.; Assert all 500 return RATE_QUOTE_EXPIRED error; prefunding balance unchanged.; Separately: obtain 1 quote, immediately (within 5 seconds) fire POST /v1/payments; assert success.
**Deliverable:** Quote TTL expiry test script (quote_ttl_test.[ext]) in perf-tests directory.
**Acceptance / logic checks:**
- All 500 expired-quote payment attempts return RATE_QUOTE_EXPIRED.
- Prefunding balance is unchanged after all 500 expired-quote attempts.
- A payment committed within 59 seconds of quote issuance succeeds.
- No expired quote ever reaches the scheme (ZeroPay stub receives 0 calls for expired-quote attempts).
- Test is deterministic: uses fixed TTL=60s and a controlled 61-second wait.
**Depends on:** 15.8-T04

### 15.8-T27 — Idempotency key cache performance: verify duplicate detection at 200 TPS  _(45 min)_
**Context:** NFR-10 §4.5 and API-05: all payment-mutating operations are idempotent via Idempotency-Key. Redis stores idempotency keys with TTL. Under load (200 TPS sustained for 5 min), repeat the same 100 unique Idempotency-Keys in a round-robin (each key repeated 600 times total). Assert: each unique key produces exactly one payment commit; subsequent calls return the cached response (HTTP 200 with same payment_id); Redis cache hit rate >= 99% for repeated keys.
**Steps:** Pre-generate 100 unique Idempotency-Keys (UUIDs).; Create load test script load_idempotency.js that cycles through the 100 keys at 200 TPS for 5 minutes.; Assert that each of the 100 keys results in exactly 1 payment record in the DB (SELECT COUNT(*) GROUP BY idempotency_key must return 1 for each).; Assert all 200-TPS calls return HTTP 200 (no 500 errors).; Check Redis cache metrics: key lookups should show >= 99% hit rate for repeated keys (each key is reused ~600 times).
**Deliverable:** Idempotency load test script (load_idempotency.[ext]) in perf-tests directory.
**Acceptance / logic checks:**
- Exactly 100 distinct payment records exist in DB after the test (not 60000).
- All repeated-key calls return HTTP 200 with the same payment_id as the original.
- Redis hit rate for the 100 keys >= 99% across the 5-minute run.
- No DUPLICATE_IDEMPOTENCY_KEY (HTTP 409) error is returned for idempotent replays (those should return 200, not 409).
- p95 latency for cached (repeated key) requests is <= the normal p95 of 1500ms.
**Depends on:** 15.8-T04

### 15.8-T28 — Generate performance test results report: compile all NFR acceptance evidence  _(60 min)_
**Context:** QA-12 §12.1 deliverable: performance test scripts and results report before UAT. NFR-10 §12 NFR acceptance criteria table (N-01 through N-20) must be verified. The report must map each NFR ID to the test ticket that covers it and the pass/fail result with raw metric values. Required sections: latency summary (all endpoints p50/p95/p99), throughput (peak TPS achieved), batch windows (job completion times), atomicity (prefunding over-deduction count=0), pool identity (violation count=0), soak test summary (heap and pool stability), failover RTO, replication lag. Format: Markdown or PDF.
**Steps:** Collect result artefacts from all prior test tickets (T03-T27): CSV outputs, metric scrapes, pass/fail status.; Fill in the NFR acceptance criteria table: for each N-01 through N-20 record Target, Measured Value, and Pass/Fail.; Include a latency percentile table for all endpoints (GET /v1/rates, POST /v1/payments, POST /v1/payments/cpm/generate, POST /v1/payments/cpm/commit, GET /v1/payments/{id}).; Include a section on findings and tuning actions taken (e.g. connection pool adjustments per NFR-10 §3.3 note).; Attach the pool_identity_stress_results (10000 vectors, 0 violations), soak_metrics.csv summary, and replica_lag.csv summary as appendices.
**Deliverable:** Performance test results report (perf_report_15.8.md or perf_report_15.8.pdf) in perf-tests/reports/ directory, containing filled NFR acceptance table and all supporting metric data.
**Acceptance / logic checks:**
- Report covers all 20 NFR acceptance criteria (N-01 through N-20) with measured values.
- Every latency p95 target from NFR-10 §2.1 is represented with a measured value and Pass/Fail verdict.
- Pool identity violation count is stated as 0 across all 10000 test vectors.
- Prefunding over-deduction count is stated as 0 from the T09 concurrent test.
- Report is signed off by the QA lead before UAT entry (per QA-12 §11.2 gate: all NFR-10 thresholds met).
**Depends on:** 15.8-T03, 15.8-T04, 15.8-T05, 15.8-T06, 15.8-T07, 15.8-T08, 15.8-T09, 15.8-T10, 15.8-T11, 15.8-T12, 15.8-T13, 15.8-T14, 15.8-T15, 15.8-T16, 15.8-T17, 15.8-T18, 15.8-T19, 15.8-T20, 15.8-T21, 15.8-T22, 15.8-T23, 15.8-T24, 15.8-T25, 15.8-T26, 15.8-T27


## WBS 15.9 — UAT support & business sign-off
### 15.9-T01 — Define UAT entry-gate checklist data model and DB migration  _(30 min)_
**Context:** WBS 15.9 UAT support. Before UAT can start, QA-12 §10.3 requires: System/E2E tests green in staging; test data seeded; Ops team trained; UAT scripts reviewed and approved. A structured entry-gate record in the DB allows the QA lead to tick off each criterion and block UAT execution until all are met. Table: uat_entry_gate (id BIGSERIAL PK, gate_item VARCHAR(120) NOT NULL, satisfied BOOLEAN DEFAULT FALSE, satisfied_by VARCHAR(120), satisfied_at TIMESTAMPTZ, notes TEXT, created_at TIMESTAMPTZ DEFAULT NOW()).
**Steps:** Create Flyway migration V2025_01__create_uat_entry_gate.sql with the schema above; Seed 6 rows for the mandatory items: SYSTEM_E2E_GREEN, STAGING_DATA_SEEDED, OPS_TEAM_TRAINED, UAT_SCRIPTS_APPROVED, NO_P1_P2_DEFECTS_OPEN, DR_DRILL_COMPLETE; Add NOT NULL constraint on gate_item; add unique index on gate_item; Write a DB-level CHECK to prevent satisfied=TRUE with a NULL satisfied_by; Verify migration runs cleanly against the test DB (port 5433)
**Deliverable:** Flyway migration file V2025_01__create_uat_entry_gate.sql with 6 seed rows
**Acceptance / logic checks:**
- Migration applies without error on a clean schema; rollback script also compiles
- All 6 mandatory gate_item values present after seed
- Attempt to set satisfied=TRUE with satisfied_by=NULL is rejected by DB constraint
- Unique index prevents duplicate gate_item values
- Table visible in test DB at localhost:5433 via psql \d uat_entry_gate

### 15.9-T02 — Build UAT entry-gate service: read and mark-satisfied endpoints  _(45 min)_
**Context:** WBS 15.9 UAT support. The QA lead must be able to mark each entry-gate item as satisfied and query overall gate status. QA-12 §10.3: entry requires all items green before UAT execution can start. Service method: markSatisfied(gateItem, actor) sets satisfied=TRUE, satisfied_by=actor, satisfied_at=now(). Method: isEntryGatePassed() returns true only when ALL rows have satisfied=TRUE.
**Steps:** Create UatEntryGateRepository (Spring Data JPA or JDBC) against uat_entry_gate table; Implement UatEntryGateService.markSatisfied(String gateItem, String actor): update row, throw if gateItem not found; Implement UatEntryGateService.isEntryGatePassed(): SELECT COUNT(*) WHERE satisfied=FALSE; return count==0; Expose GET /internal/uat/entry-gate (list all rows with status) and PUT /internal/uat/entry-gate/{item}/satisfy (body: {actor}) — admin-only endpoint secured by ADMIN role; Return 409 if UAT execution is attempted and isEntryGatePassed()==false
**Deliverable:** UatEntryGateService + REST endpoints GET /internal/uat/entry-gate and PUT /internal/uat/entry-gate/{item}/satisfy
**Acceptance / logic checks:**
- PUT with valid gateItem returns 200 and row shows satisfied=TRUE in subsequent GET
- PUT with unknown gateItem returns 404
- GET before all items satisfied returns overall_passed=false; after all satisfied returns overall_passed=true
- Endpoint requires ADMIN role; 401 returned without valid token
- isEntryGatePassed() returns false when even one row has satisfied=FALSE
**Depends on:** 15.9-T01

### 15.9-T03 — Author UAT test script template: UAT-001 set up new OVERSEAS partner  _(40 min)_
**Context:** WBS 15.9 UAT support. QA-12 §10.2 UAT-001: GME Ops Config sets up a new OVERSEAS partner end-to-end in Admin. Acceptance criterion: completed in < 30 min; partner can make a test payment. A structured, step-by-step script with timed checkpoints and a pass/fail cell is required (QA-12 §12.1: UAT scripts due two weeks before UAT start). OVERSEAS partner has prefunded USD balance; partner type must be set to OVERSEAS; a rule (partner x scheme x direction=Inbound) must be created with m_a+m_b >= 2.0%.
**Steps:** Create docs/uat/UAT-001-setup-overseas-partner.md with scenario header: UAT-001, Role: GME Ops Config, Preconditions, Steps, Expected Result, Timer checkpoint, Pass/Fail cell; List steps: navigate to Admin > Partners > New; set type=OVERSEAS, name=TestPartnerOVERSEAS, settlement_currency=USD; set webhook URL; click Save; Add step: create Rule for TestPartnerOVERSEAS x ZeroPay x Inbound; set m_a=1.5%, m_b=1.0% (combined=2.5%>=2.0%); click Save; Add step: fund prefunding balance USD 1000 via Admin > Prefunding > Add; Add step: make test MPM payment (target_payout=100 KRW, see UAT-004 script for rate-engine values); confirm payment.approved webhook received; Include timer field: start_time, end_time; must be < 30 min; include sign-off row for GME Ops Config actor
**Deliverable:** docs/uat/UAT-001-setup-overseas-partner.md — structured UAT script with pass/fail cells and timer checkpoint
**Acceptance / logic checks:**
- Script includes explicit start/stop timer instruction; pass requires elapsed < 30 min
- Rule creation step specifies m_a+m_b >= 2.0% with numeric example (1.5%+1.0%=2.5%)
- Script includes webhook receipt verification step (check webhook log in Admin)
- Sign-off row has fields: Tester name, Date, Result (Pass/Fail), Defects raised
- Script can be executed independently with zero prior project knowledge

### 15.9-T04 — Author UAT test script: UAT-002 add ZeroPay scheme to existing partner  _(30 min)_
**Context:** WBS 15.9 UAT support. QA-12 §10.2 UAT-002: GME Ops Config adds ZeroPay scheme to an existing partner. Acceptance criterion: scheme visible in partner portal without any deployment (config-without-code constraint from BRD-01: new scheme/partner requires only config, no code change and no deployment). Precondition: partner already exists (created in UAT-001).
**Steps:** Create docs/uat/UAT-002-add-zeropay-scheme.md; Precondition: TestPartnerOVERSEAS exists from UAT-001; Steps: Admin > Partners > TestPartnerOVERSEAS > Schemes > Add; select ZeroPay; set payout_currency=KRW; click Save; Step: log into Partner Portal as TestPartnerOVERSEAS; navigate to Schemes; confirm ZeroPay appears with status Active; Step: confirm no git merge or deployment occurred — check CI/CD pipeline; no new build triggered; Include sign-off row and pass/fail cell; note: absence of deployment is verified by checking OPS-13 deploy log
**Deliverable:** docs/uat/UAT-002-add-zeropay-scheme.md — structured UAT script
**Acceptance / logic checks:**
- Script explicitly instructs tester to check CI/CD deploy log shows no new deployment
- Scheme Active status verified in partner portal (not just admin)
- Script references config-without-code constraint: adding scheme must not require a merge or deploy
- Pass/fail cell and sign-off row present
- Precondition links to UAT-001 completion
**Depends on:** 15.9-T03

### 15.9-T05 — Author UAT test script: UAT-003 MPM domestic payment GME Remit  _(35 min)_
**Context:** WBS 15.9 UAT support. QA-12 §10.2 UAT-003: GME Remit team executes MPM domestic payment. Acceptance criterion: payment.approved webhook received; collection_amount = payout_amount + KRW 500. GME Remit is a LOCAL partner (no prefunding required). Same-currency short-circuit applies (collection=settle_A=settle_B=payout=KRW): collection_amount = target_payout + service_charge. service_charge = KRW 500 flat. KRW has 0 decimal places.
**Steps:** Create docs/uat/UAT-003-mpm-domestic-gmereremit.md; Precondition: GME Remit partner with type=LOCAL, ZeroPay scheme, Domestic rule configured; Test vector: target_payout=10000 KRW; expected collection_amount=10500 KRW (same-currency short-circuit: 10000+500); Steps: call POST /v1/payments with payload {partner_id, scheme=ZeroPay, direction=Domestic, target_payout=10000, payout_ccy=KRW, qr_code=ZPQR_TEST_001}; verify HTTP 200; Step: check webhook log in Admin for payment.approved event; confirm collection_amount=10500 KRW in response; Step: verify no prefunding deduction occurred (LOCAL partner exempt); include sign-off row
**Deliverable:** docs/uat/UAT-003-mpm-domestic-gmereremit.md — structured UAT script with numeric test vector
**Acceptance / logic checks:**
- collection_amount=10500 KRW verified in webhook payload (target_payout=10000 + service_charge=500)
- No prefunding deduction record exists for this transaction (LOCAL partner)
- payment.approved event appears in Admin webhook log within 30 s
- KRW amounts stored with 0 decimal places (10500 not 10500.00)
- Sign-off row completed by GME Remit team representative
**Depends on:** 15.9-T03

### 15.9-T06 — Author UAT test script: UAT-004 MPM inbound payment OVERSEAS partner  _(45 min)_
**Context:** WBS 15.9 UAT support. QA-12 §10.2 UAT-004: GME Ops Monitoring executes MPM Inbound payment for OVERSEAS partner. Acceptance criterion: prefunding deducted; webhook received; rate-engine fields match. OVERSEAS partner uses prefunded USD balance. Rate engine RECEIVE mode 5 steps: payout_usd_cost=target_payout/cost_rate_pay; collection_usd=payout_usd_cost/(1-m_a-m_b); send_amount=collection_usd*cost_rate_coll; collection_amount=send_amount+service_charge. Pool identity: collection_usd-collection_margin_usd-payout_margin_usd==payout_usd_cost (tol 0.01 USD). Test rates: treasury.usd_KRW=1350 (cost_rate_pay), cost_rate_coll=1.0 (Settle A=USD), m_a=1.5%, m_b=1.0%, service_charge=0 USD, target_payout=13500 KRW.
**Steps:** Create docs/uat/UAT-004-mpm-inbound-overseas.md; Compute expected values: payout_usd_cost=13500/1350=10.00 USD; collection_usd=10.00/(1-0.015-0.010)=10.2564 USD; send_amount=10.2564*1.0=10.2564 USD; collection_amount=10.2564+0=10.2564 USD (2dp); collection_margin_usd=10.2564*0.015=0.1538 USD; payout_margin_usd=10.2564*0.010=0.1026 USD; pool check: 10.2564-0.1538-0.1026=10.0000 (pass); Steps: note prefunding balance before; call POST /v1/payments for TestPartnerOVERSEAS; verify HTTP 200; Step: confirm prefunding_balance decreased by 10.2564 USD (the collection_usd, deducted atomically at request time for Fixed MPM); Step: check webhook for payment.approved; confirm rate-engine fields: payout_usd_cost=10.00, collection_usd=10.2564, send_amount=10.2564, collection_margin_usd=0.1538, payout_margin_usd=0.1026; Include sign-off row; note: internal fields m_a, m_b, cost_rate_pay must NOT appear in partner-facing webhook
**Deliverable:** docs/uat/UAT-004-mpm-inbound-overseas.md — structured UAT script with full numeric rate-engine vector
**Acceptance / logic checks:**
- Prefunding balance deducted by exactly 10.2564 USD before scheme call
- Webhook payload shows collection_usd=10.2564, send_amount=10.2564, payout_usd_cost=10.00
- Pool identity holds: 10.2564-0.1538-0.1026=10.0000 within 0.01 USD
- m_a, m_b, cost_rate_pay absent from partner-facing webhook response
- Payment rejected (no scheme call) if prefunding balance < 10.2564 USD before deduction
**Depends on:** 15.9-T03

### 15.9-T07 — Author UAT test script: UAT-005 CPM inbound payment OVERSEAS partner  _(40 min)_
**Context:** WBS 15.9 UAT support. QA-12 §10.2 UAT-005: GME Ops Monitoring executes CPM Inbound payment for OVERSEAS partner. Acceptance criterion: prefunding deducted at QR generate step; payment approved. CPM flow: prefunding deducted when QR is generated (POST /v1/payments/cpm/generate), not at payment commit. OVERSEAS partner, same rate setup as UAT-004 (treasury.usd_KRW=1350, m_a=1.5%, m_b=1.0%, target_payout=13500 KRW). Deduction is ATOMIC (SELECT FOR UPDATE on prefunding_balance).
**Steps:** Create docs/uat/UAT-005-cpm-inbound-overseas.md; Precondition: TestPartnerOVERSEAS with prefunding_balance >= 10.2564 USD; Step: call POST /v1/payments/cpm/generate with {partner_id, scheme=ZeroPay, direction=Inbound, target_payout=13500, payout_ccy=KRW}; verify HTTP 200 returns qr_payload and quote_id; Step: confirm prefunding deduction of 10.2564 USD occurred immediately after generate (check balance in Admin); Step: simulate merchant scan using ZeroPay test QR value; call POST /v1/payments/cpm/confirm with {quote_id}; verify payment.approved webhook; Step: verify no second deduction occurred at confirm step; include sign-off row
**Deliverable:** docs/uat/UAT-005-cpm-inbound-overseas.md — structured UAT script with CPM-specific deduction timing verification
**Acceptance / logic checks:**
- Prefunding deducted at /cpm/generate, not at /cpm/confirm — balance checked after each step
- Duplicate generate calls with same idempotency key do not cause double deduction
- Payment rejected if prefunding_balance < 10.2564 USD at generate time
- payment.approved webhook received after confirm step with matching quote_id
- QR payload returned by generate step is a valid ZeroPay CPM QR string
**Depends on:** 15.9-T06

### 15.9-T08 — Author UAT test script: UAT-006 low-balance alert  _(35 min)_
**Context:** WBS 15.9 UAT support. QA-12 §10.2 UAT-006: GME Ops Settlement verifies low-balance alert. Acceptance criterion: email received when balance drops below configured threshold. Per TICKET_BRIEF: low-balance alert per partner is a named feature. Alert triggers when prefunding_balance < low_balance_threshold for an OVERSEAS partner; alert sent to configured email addresses (confirmed with partner contacts per go-live checklist item 8).
**Steps:** Create docs/uat/UAT-006-low-balance-alert.md; Precondition: TestPartnerOVERSEAS configured with low_balance_threshold=50.00 USD; alert email=ops-test@gmeremit.com; Step: set prefunding_balance to 60.00 USD via Admin prefunding adjustment; Step: make a payment that deducts 15.00 USD (target_payout such that collection_usd=15.00); balance becomes 45.00 USD < threshold; Step: wait up to 2 minutes; check ops-test@gmeremit.com inbox for low-balance alert email; confirm email shows partner name, current balance (45.00 USD), threshold (50.00 USD); Include sign-off row; note alert must not fire again on the next payment unless balance recovers above threshold and drops below again
**Deliverable:** docs/uat/UAT-006-low-balance-alert.md — structured UAT script with threshold and email verification steps
**Acceptance / logic checks:**
- Alert email received at ops-test@gmeremit.com within 2 min of balance dropping below 50.00 USD
- Email body contains: partner name, current balance=45.00 USD, threshold=50.00 USD
- No duplicate alert sent for same below-threshold state (idempotent alerting)
- Alert NOT sent when balance=55.00 USD (above threshold)
- Sign-off row completed by GME Ops Settlement role
**Depends on:** 15.9-T05

### 15.9-T09 — Author UAT test script: UAT-007 ZeroPay morning settlement cycle  _(45 min)_
**Context:** WBS 15.9 UAT support. QA-12 §10.2 UAT-007: GME Ops Settlement executes ZeroPay morning settlement cycle. Acceptance criterion: ZP0061 transmitted by 05:00 KST; ZP0062 received and reconciled. Per SCH-06: ZP0061 is the outbound settlement request file (daily net settlement); ZP0062 is the inbound settlement confirmation file from KFTC. Files transmitted via SFTP to 한결원 (KFTC). Batch job scheduled daily; Ops can trigger manually in UAT. Use KFTC pre-production SFTP endpoint.
**Steps:** Create docs/uat/UAT-007-zeropay-settlement-cycle.md; Precondition: at least one approved MPM payment from UAT-004; KFTC pre-production SFTP credentials loaded; batch jobs scheduled per OPS-13; Step: trigger ZP0061 generation manually via Admin > Batch Jobs > ZP0061 Generate; record trigger time; Step: verify ZP0061 file appears in SFTP outbound directory with correct filename format and transmission timestamp <= 05:00 in full-run or within 2 min of manual trigger; Step: wait for ZP0062 file on SFTP inbound directory (KFTC test env responds); confirm file downloaded and parsed; check reconciliation_status=MATCHED for all transactions in UAT-004; Include sign-off row; note: ZP0061 settlement amount = sum of KRW payout amounts (gross) for all approved transactions
**Deliverable:** docs/uat/UAT-007-zeropay-settlement-cycle.md — structured UAT script with file-transmission and reconciliation verification steps
**Acceptance / logic checks:**
- ZP0061 transmitted to KFTC SFTP before 05:00 KST (or within SLA window in manual trigger mode)
- ZP0062 received, parsed, and transactions marked reconciliation_status=MATCHED in DB
- ZP0061 settlement total equals sum of target_payout amounts for all included approved transactions
- Audit log records ZP0061 file hash at generation time
- Sign-off row completed by GME Ops Settlement role
**Depends on:** 15.9-T06

### 15.9-T10 — Author UAT test script: UAT-008 exception resolution and audit trail  _(40 min)_
**Context:** WBS 15.9 UAT support. QA-12 §10.2 UAT-008: GME Ops Settlement resolves a flagged discrepancy. Acceptance criterion: Ops can resolve flagged discrepancy; audit trail complete. A discrepancy arises when ZP0062 amount differs from ZP0061 amount (reconciliation_status=DISCREPANCY). Ops must be able to flag the exception, add a resolution note, and close it. Every action must appear in the transaction event trail (8-step audit per TICKET_BRIEF).
**Steps:** Create docs/uat/UAT-008-exception-resolution.md; Precondition: inject a synthetic DISCREPANCY record (reconciliation_status=DISCREPANCY, expected_amount=10500 KRW, received_amount=10000 KRW, delta=-500 KRW) via Admin test utility; Step: navigate to Admin > Settlement > Exceptions; verify discrepancy appears with delta=-500 KRW; Step: click Resolve; enter resolution note = Test discrepancy - KRW 500 rounding adjustment from KFTC; click Submit; Step: verify exception status=RESOLVED; verify audit log entry shows actor, timestamp, previous status (DISCREPANCY), new status (RESOLVED), and resolution note; Step: verify the transaction event trail contains the EXCEPTION_FLAGGED and EXCEPTION_RESOLVED events with timestamps
**Deliverable:** docs/uat/UAT-008-exception-resolution.md — structured UAT script with audit trail verification
**Acceptance / logic checks:**
- Exception record appears in Admin with correct delta=-500 KRW before resolution
- After resolution, status=RESOLVED and resolution note stored verbatim
- Audit log entry has: actor (Ops user name), timestamp, previous_value=DISCREPANCY, new_value=RESOLVED
- Transaction event trail contains both EXCEPTION_FLAGGED and EXCEPTION_RESOLVED events in order
- Attempt to resolve already-resolved exception returns 409 or appropriate error
**Depends on:** 15.9-T09

### 15.9-T11 — Author UAT test script: UAT-009 transaction search and detail, internal fields hidden  _(40 min)_
**Context:** WBS 15.9 UAT support. QA-12 §10.2 UAT-009: GME Ops Monitoring verifies transaction search returns correct results; all fields present; internal fields hidden from partner portal. Internal fields per TICKET_BRIEF: m_a, m_b, cost rates must never be exposed via partner-facing API or portal. Ops Admin portal can see all fields. Partner portal must not show m_a, m_b, cost_rate_pay, cost_rate_coll.
**Steps:** Create docs/uat/UAT-009-transaction-search.md; Step 1 (Admin portal): search by date range covering UAT-004 transaction; verify result includes transaction_id, partner_id, payment_mode=MPM, direction=Inbound, collection_amount, payout_amount, status=APPROVED, rate-engine fields including m_a, m_b; Step 2 (Admin portal): open transaction detail; verify 8-event trail present: PAYMENT_INITIATED, RATE_QUOTED, PREFUNDING_DEDUCTED, SCHEME_SUBMITTED, SCHEME_APPROVED, WEBHOOK_SENT, plus any others; Step 3 (Partner portal): log in as TestPartnerOVERSEAS; search same transaction; verify transaction appears with collection_amount, payout_amount, status; Step 4 (Partner portal): confirm m_a, m_b, cost_rate_pay, cost_rate_coll are NOT present in partner portal response (inspect network response or page source); Include sign-off row; note: search by partner_id must only return that partner's transactions (IDOR check)
**Deliverable:** docs/uat/UAT-009-transaction-search.md — structured UAT script with IDOR and field-visibility checks
**Acceptance / logic checks:**
- Admin portal shows all rate-engine fields including m_a=0.015, m_b=0.010 for UAT-004 transaction
- Partner portal response for same transaction does NOT contain fields m_a, m_b, cost_rate_pay, cost_rate_coll
- Searching with TestPartnerOVERSEAS credentials does not return transactions belonging to GME Remit (IDOR boundary)
- 8-event audit trail visible in Admin with timestamps in chronological order
- Sign-off row completed by GME Ops Monitoring role
**Depends on:** 15.9-T06

### 15.9-T12 — Author UAT test script: UAT-010 partner portal balance inquiry and CSV export  _(35 min)_
**Context:** WBS 15.9 UAT support. QA-12 §10.2 UAT-010: GME Remit team verifies partner portal shows real-time balance and CSV export is correct. GME Remit is LOCAL (no prefunding), but the portal must show transaction history. For OVERSEAS partners (SendMN) the portal shows prefunding_balance. In UAT, use TestPartnerOVERSEAS for balance display (balance=45.00 USD from UAT-006). CSV export must include: transaction_id, date, payment_mode, direction, collection_amount, payout_amount, status.
**Steps:** Create docs/uat/UAT-010-partner-portal-balance.md; Step 1: log into Partner Portal as TestPartnerOVERSEAS; navigate to Balance; confirm displayed balance = current prefunding_balance from DB (e.g. 45.00 USD from UAT-006 state); Step 2: navigate to Transactions; click Export CSV; download file; Step 3: open CSV; verify columns present: transaction_id, date, payment_mode, direction, collection_amount, payout_amount, status; Step 4: verify row for UAT-004 transaction: collection_amount=10.2564 USD (or KRW equivalent), payout_amount=13500 KRW, status=APPROVED; Step 5: verify m_a, m_b, cost_rate columns are absent from CSV; include sign-off row
**Deliverable:** docs/uat/UAT-010-partner-portal-balance.md — structured UAT script with CSV column and data-accuracy verification
**Acceptance / logic checks:**
- Displayed balance matches DB prefunding_balance to 2 decimal places in USD
- CSV contains mandatory columns: transaction_id, date, payment_mode, direction, collection_amount, payout_amount, status
- UAT-004 transaction row values match DB record
- m_a, m_b, cost_rate_pay absent from CSV export
- Sign-off row completed by GME Remit team representative
**Depends on:** 15.9-T08

### 15.9-T13 — Author UAT test script: UAT-011 refund via Admin Portal and ZP0021 inclusion  _(40 min)_
**Context:** WBS 15.9 UAT support. QA-12 §10.2 UAT-011: GME Ops Settlement creates refund via Admin Portal. Acceptance criterion: refund created; appears in ZP0021 next batch. Per A-07 (QA-12 §13): Phase 1 refunds are Admin-portal-only (no partner API refund endpoint). ZP0021 is the ZeroPay refund batch file. Refund must reference original transaction_id; refund amount <= original collection_amount.
**Steps:** Create docs/uat/UAT-011-refund-admin-portal.md; Precondition: UAT-004 transaction in APPROVED status with collection_amount=10.2564 USD; Step: Admin > Payments > [UAT-004 transaction_id] > Refund; enter refund_amount=10.2564 USD (full refund); click Submit; verify refund record created with status=PENDING_BATCH; Step: trigger ZP0021 batch generation manually (Admin > Batch Jobs > ZP0021 Generate); Step: verify ZP0021 file contains the refund record with original_transaction_id, refund_amount=10.2564 USD; Step: verify audit log shows Ops actor, timestamp, refund_amount, and original transaction reference; include sign-off row
**Deliverable:** docs/uat/UAT-011-refund-admin-portal.md — structured UAT script with ZP0021 inclusion and audit trail checks
**Acceptance / logic checks:**
- Refund record created with status=PENDING_BATCH immediately after submission
- ZP0021 file generated contains refund row with correct original_transaction_id and refund_amount=10.2564 USD
- Attempt to refund more than original collection_amount is rejected with error
- Audit log entry records: actor, timestamp, refund_amount, original_transaction_id
- After ZP0021 generation, refund record status=BATCHED
**Depends on:** 15.9-T09

### 15.9-T14 — Author UAT test script: UAT-012 rate change affects new transactions only (rate-lock)  _(45 min)_
**Context:** WBS 15.9 UAT support. QA-12 §10.2 UAT-012: GME Ops Config changes a rate; in-flight quote is unaffected; new rate applies to next quote. Per TICKET_BRIEF: at commit, all USD-pool values and derived rates are permanently recorded (rate-lock); later treasury/margin changes never affect committed transactions. A pending quote (validUntil not yet expired) must continue to use its locked rate. New quotes after the config change use the new rate.
**Steps:** Create docs/uat/UAT-012-rate-change-rate-lock.md; Precondition: treasury.usd_KRW=1350; get a fresh rate quote for target_payout=13500 KRW; note quote_id and locked collection_usd=10.2564 USD; do NOT commit yet; Step: Admin > Config > Treasury Rates > ZeroPay KRW > update treasury.usd_KRW to 1400; click Save; verify audit log records previous value=1350, new value=1400, actor, timestamp; Step: commit the original quote (POST /v1/payments with quote_id from step 1); verify payment uses collection_usd=10.2564 (old rate, rate-locked), NOT recalculated with 1400; Step: request a new quote for same target_payout=13500 KRW; expected new values: payout_usd_cost=13500/1400=9.6429 USD; collection_usd=9.6429/(0.975)=9.8906 USD; verify new quote reflects 1400 rate; Include sign-off row; note: config audit log must show actor and previous value
**Deliverable:** docs/uat/UAT-012-rate-change-rate-lock.md — structured UAT script with pre-change quote, config update, commit, and new quote verification
**Acceptance / logic checks:**
- Committed payment uses collection_usd=10.2564 USD (1350 rate), not recalculated value
- New quote after config change uses treasury.usd_KRW=1400; collection_usd approx 9.8906 USD
- Audit log for rate change records: actor, timestamp, previous_value=1350, new_value=1400
- Rate change has no effect on already-committed transactions (immutable rate-lock)
- Sign-off row completed by GME Ops Config role
**Depends on:** 15.9-T06

### 15.9-T15 — Author UAT test script: UAT-013 config-without-code verification (no deployment during UAT-001)  _(30 min)_
**Context:** WBS 15.9 UAT support. QA-12 §10.2 UAT-013: GME Product Team verifies no merge or deployment occurred during UAT-001 partner setup. This validates the hard constraint (BRD-01, TICKET_BRIEF): adding a new scheme or partner requires only configuration, no code change and no deployment. Method: check CI/CD pipeline deploy log and git log during UAT-001 time window.
**Steps:** Create docs/uat/UAT-013-config-without-code.md; Precondition: record UAT-001 start_time and end_time; Step: navigate to CI/CD dashboard (OPS-13 pipeline); filter deployments by time window [UAT-001 start_time, end_time]; verify zero deployment runs triggered; Step: run git log --after=UAT-001-start --before=UAT-001-end on main/production branch; verify zero commits merged during window; Step: verify partner TestPartnerOVERSEAS is fully functional (can make payments) using only config records in DB; Include sign-off row; note: any deployment observed during UAT-001 is an automatic P1 defect
**Deliverable:** docs/uat/UAT-013-config-without-code.md — structured UAT script with CI/CD and git log verification
**Acceptance / logic checks:**
- CI/CD deploy log shows zero deployments during UAT-001 time window
- Git log shows zero merges to production branch during UAT-001 time window
- TestPartnerOVERSEAS can complete a payment using config only (no code)
- Any deployment observed is classified as P1 defect per script instructions
- Sign-off row completed by GME Product Team (product owner)
**Depends on:** 15.9-T03

### 15.9-T16 — Build UAT scenario execution tracker: DB schema and migration  _(30 min)_
**Context:** WBS 15.9 UAT support. A scenario execution tracker records each UAT run: which scenario (UAT-001 through UAT-013), who ran it, when, result (PASS/FAIL/BLOCKED), defect IDs raised, and sign-off actor. This is the machine-readable record needed for the exit gate check (all P1 scenarios signed off). Table: uat_scenario_run (id BIGSERIAL PK, scenario_id VARCHAR(20) NOT NULL, run_by VARCHAR(120), run_at TIMESTAMPTZ, result VARCHAR(10) CHECK(result IN (PASS,FAIL,BLOCKED)), defect_ids TEXT, signed_off_by VARCHAR(120), signed_off_at TIMESTAMPTZ, notes TEXT).
**Steps:** Create Flyway migration V2025_02__create_uat_scenario_run.sql with schema above; Add index on scenario_id for fast lookup; Seed scenario_id values UAT-001 through UAT-013 as reference data in a uat_scenario_definition table (id VARCHAR(20) PK, description TEXT, sign_off_role VARCHAR(80), priority VARCHAR(5) CHECK(priority IN (P1,P2))); Mark UAT-001 through UAT-013 as P1 per QA-12 §10.3 exit criteria; Add FK from uat_scenario_run.scenario_id to uat_scenario_definition.id
**Deliverable:** Flyway migration V2025_02__create_uat_scenario_run.sql + uat_scenario_definition seed with 13 scenarios all marked P1
**Acceptance / logic checks:**
- Migration applies cleanly; uat_scenario_run and uat_scenario_definition tables exist
- All 13 scenario IDs present in uat_scenario_definition with priority=P1
- result column rejects values outside PASS/FAIL/BLOCKED
- FK constraint prevents run records for unknown scenario IDs
- Index on scenario_id present (confirmed via \d uat_scenario_run)
**Depends on:** 15.9-T01

### 15.9-T17 — Build UAT scenario run service: record result and sign-off endpoints  _(45 min)_
**Context:** WBS 15.9 UAT support. The QA lead records each UAT scenario result via an internal API. Endpoints: POST /internal/uat/runs (body: {scenario_id, run_by, result, defect_ids, notes}) creates a run record. PUT /internal/uat/runs/{id}/sign-off (body: {signed_off_by}) sets signed_off_by and signed_off_at. GET /internal/uat/runs/summary returns for each scenario: latest result, signed_off_by, and whether all P1 scenarios are signed off (drives exit gate).
**Steps:** Create UatScenarioRunRepository and UatScenarioRunService; POST /internal/uat/runs: validate scenario_id exists in uat_scenario_definition; reject unknown scenario_id with 422; insert run record; PUT /internal/uat/runs/{id}/sign-off: update signed_off_by and signed_off_at; validate signed_off_by not blank; GET /internal/uat/runs/summary: return list of {scenario_id, priority, latest_result, signed_off_by, signed_off_at}; include top-level field all_p1_signed_off=true only when all P1 scenarios have a PASS result AND signed_off_by is not null; Secure all endpoints with ADMIN role; return 401 if unauthenticated
**Deliverable:** UatScenarioRunService + REST endpoints POST /internal/uat/runs, PUT /internal/uat/runs/{id}/sign-off, GET /internal/uat/runs/summary
**Acceptance / logic checks:**
- POST with valid scenario_id=UAT-004 and result=PASS returns 201 and persists record
- POST with unknown scenario_id returns 422
- all_p1_signed_off=false when even one P1 scenario missing PASS + signed_off
- all_p1_signed_off=true only when all 13 P1 scenarios have result=PASS and signed_off_by set
- PUT sign-off with blank signed_off_by returns 422
**Depends on:** 15.9-T16

### 15.9-T18 — Implement UAT exit-gate check: block go-live if not all P1 scenarios signed off  _(50 min)_
**Context:** WBS 15.9 UAT support. QA-12 §10.3 exit criteria: all P1 UAT scenarios signed off; no P1/P2 defects open; setup-time target confirmed (UAT-013). A go-live gate check endpoint (GET /internal/uat/exit-gate) must return overall_passed=true only when: (1) all 13 P1 scenarios in uat_scenario_run have result=PASS and signed_off_by set, (2) zero open P1/P2 defects in defect_registry table, (3) UAT-013 scenario signed off. This endpoint is called by the release manager before production deploy.
**Steps:** Add defect_registry table query (or integrate with existing defect tracking): count open defects where severity IN (P1,P2) and status != CLOSED; Implement UatExitGateService.check(): calls UatScenarioRunService.allP1SignedOff(), checks open P1/P2 defect count=0, and verifies UAT-013 has result=PASS + signed_off_by set; Expose GET /internal/uat/exit-gate returning {overall_passed, reasons:[...]} where reasons lists each failing criterion; Return HTTP 200 with overall_passed=false (not 4xx) so callers can parse the reason list; Add integration to CI/CD pre-production-deploy step: pipeline calls this endpoint and fails if overall_passed=false
**Deliverable:** UatExitGateService + GET /internal/uat/exit-gate endpoint + CI/CD pre-deploy integration
**Acceptance / logic checks:**
- Returns overall_passed=false with reason UAT_013_NOT_SIGNED_OFF when UAT-013 missing sign-off
- Returns overall_passed=false with reason OPEN_P1_P2_DEFECTS when open high-severity defects exist
- Returns overall_passed=true only when all 3 criteria simultaneously satisfied
- CI/CD pipeline fails pre-production-deploy step when overall_passed=false
- HTTP response is always 200 (overall_passed is in body, not HTTP status)
**Depends on:** 15.9-T17

### 15.9-T19 — Build defect registry: DB schema and migration for UAT defect tracking  _(30 min)_
**Context:** WBS 15.9 UAT support. QA-12 §11.1-11.2: defects have severity P1-P4; P1 requires fix within same sprint; P2 must be fixed before UAT entry; daily triage by QA lead + tech lead + product owner. A defect_registry table stores UAT-raised defects. Columns: id BIGSERIAL PK, defect_code VARCHAR(20) UNIQUE NOT NULL, title VARCHAR(255), severity VARCHAR(2) CHECK(severity IN (P1,P2,P3,P4)), status VARCHAR(20) CHECK(status IN (OPEN,IN_PROGRESS,RESOLVED,CLOSED)), affected_component VARCHAR(120), raised_by VARCHAR(120), raised_at TIMESTAMPTZ, resolved_at TIMESTAMPTZ, uat_scenario_id VARCHAR(20) FK to uat_scenario_definition, steps_to_reproduce TEXT, notes TEXT.
**Steps:** Create Flyway migration V2025_03__create_defect_registry.sql with schema above; Add index on severity, status for fast open-defect queries; Add index on uat_scenario_id; Verify FK to uat_scenario_definition compiles (or use soft reference if uat_scenario_definition created in separate migration); Test: insert a P1 defect with status=OPEN; query SELECT COUNT(*) WHERE severity IN (P1,P2) AND status != CLOSED; expect count=1
**Deliverable:** Flyway migration V2025_03__create_defect_registry.sql
**Acceptance / logic checks:**
- Migration applies; defect_registry table with all columns exists
- severity CHECK rejects value P5 with constraint violation
- status CHECK rejects value CANCELLED
- Index on (severity, status) present
- Composite query: count open P1/P2 defects returns 1 after inserting one P1 OPEN record
**Depends on:** 15.9-T16

### 15.9-T20 — Build defect registry service: raise, triage, and close defect endpoints  _(45 min)_
**Context:** WBS 15.9 UAT support. QA-12 §11.2 defect triage process: developer raises defect; daily triage by QA lead; P1 immediate fix; P2 fix before UAT entry. Endpoints: POST /internal/uat/defects (raise); PATCH /internal/uat/defects/{id}/status (transition status); GET /internal/uat/defects?severity=P1&status=OPEN (filter). Auto-generate defect_code as DEF-NNN (e.g. DEF-001) using sequence. P1 defect creation must trigger a notification (log + alert at minimum).
**Steps:** Create DefectRegistryService and DefectRegistryRepository; POST /internal/uat/defects: validate severity in P1-P4; auto-assign defect_code DEF-NNN; if severity=P1 log CRITICAL alert to monitoring channel (log.error with [UAT-P1-DEFECT] prefix); PATCH /internal/uat/defects/{id}/status: accept {new_status, notes}; validate transition is allowed (OPEN->IN_PROGRESS, IN_PROGRESS->RESOLVED, RESOLVED->CLOSED); reject invalid transitions with 422; GET /internal/uat/defects: support filter params severity and status; return list ordered by raised_at DESC; Secure all endpoints with ADMIN role
**Deliverable:** DefectRegistryService + REST endpoints POST /internal/uat/defects, PATCH /internal/uat/defects/{id}/status, GET /internal/uat/defects
**Acceptance / logic checks:**
- POST returns 201 with auto-assigned defect_code DEF-001 for first defect
- P1 defect creation triggers log entry containing [UAT-P1-DEFECT] prefix
- Invalid status transition OPEN->CLOSED returns 422
- GET ?severity=P1&status=OPEN returns only P1 OPEN defects
- GET /internal/uat/exit-gate (15.9-T18) returns overall_passed=false after a P1 defect is raised
**Depends on:** 15.9-T19, 15.9-T18

### 15.9-T21 — Build UAT progress report generator: daily JSON summary for QA lead  _(45 min)_
**Context:** WBS 15.9 UAT support. QA-12 §12.3: daily UAT progress report during UAT; includes scenario status and defect count by severity. A scheduled job (or on-demand endpoint) produces a JSON report: {report_date, scenarios_total:13, scenarios_passed, scenarios_failed, scenarios_blocked, scenarios_pending, defects_by_severity:{P1,P2,P3,P4}, all_p1_signed_off, entry_gate_passed}. Report is logged and optionally emailed.
**Steps:** Create UatProgressReportService.generateReport(): aggregate from uat_scenario_run (latest run per scenario_id), uat_entry_gate, defect_registry; Compute: scenarios_passed=count(result=PASS and signed_off), scenarios_failed=count(result=FAIL), scenarios_blocked=count(result=BLOCKED), scenarios_pending=13-passed-failed-blocked; Expose GET /internal/uat/progress-report returning the JSON structure above; Schedule the report job to run daily at 08:00 KST (Spring @Scheduled cron: 0 0 23 * * ? for 08:00 KST = UTC-9, or 0 0 23 for UTC equivalent of 08:00 KST+9=23:00 UTC); Log full JSON report at INFO level; send email if email_alerts_enabled=true in config
**Deliverable:** UatProgressReportService + GET /internal/uat/progress-report + daily scheduled job at 23:00 UTC
**Acceptance / logic checks:**
- Report shows scenarios_total=13 always
- After all 13 signed off: scenarios_passed=13, all_p1_signed_off=true
- After 2 FAIL runs: scenarios_failed=2
- defects_by_severity.P1 equals count of P1 defects in defect_registry regardless of status
- Scheduled job fires at 23:00 UTC and produces log line containing [UAT-DAILY-REPORT]
**Depends on:** 15.9-T17, 15.9-T20

### 15.9-T22 — Seed UAT staging environment: test data load script for UAT entry  _(40 min)_
**Context:** WBS 15.9 UAT support. QA-12 §10.3 entry criterion: staging environment test data seeded. Required seed data: (1) GME Remit partner (type=LOCAL, scheme=ZeroPay, Domestic rule, service_charge=500 KRW); (2) TestPartnerOVERSEAS (type=OVERSEAS, scheme=ZeroPay, Inbound rule, m_a=0.015, m_b=0.010, prefunding_balance=1000 USD, low_balance_threshold=50 USD); (3) treasury rate treasury.usd_KRW=1350; (4) ZeroPay scheme record with SFTP credentials pointing to KFTC pre-production. Script must be idempotent (safe to run multiple times).
**Steps:** Create db/seeds/uat_seed_data.sql with INSERT ... ON CONFLICT DO NOTHING for all records; Insert ZeroPay scheme record: id=zeropay, operator=KFTC, status=ACTIVE, sftp_host=sftp-preproduction.kftc.or.kr; Insert GME Remit partner: id=gme-remit, type=LOCAL; rule: partner_id=gme-remit, scheme_id=zeropay, direction=DOMESTIC, service_charge_amount=500, service_charge_ccy=KRW; Insert TestPartnerOVERSEAS: id=test-partner-overseas, type=OVERSEAS; rule: m_a=0.015, m_b=0.010, direction=INBOUND; prefunding_balance=1000.00 USD; low_balance_threshold=50.00 USD; Insert treasury rate: key=usd_KRW, value=1350.0000, updated_by=UAT_SEED, updated_at=now(); Test: run script twice; verify no duplicate records and record counts match expected
**Deliverable:** db/seeds/uat_seed_data.sql — idempotent UAT seed script with 5 entity types
**Acceptance / logic checks:**
- Script runs twice without error; record counts unchanged on second run
- GME Remit rule has service_charge_amount=500 and service_charge_ccy=KRW
- TestPartnerOVERSEAS prefunding_balance=1000.00 USD after seed
- treasury.usd_KRW=1350.0000 in treasury_rate table
- Combined margin for TestPartnerOVERSEAS rule: m_a(0.015)+m_b(0.010)=0.025 >= 0.02 constraint satisfied
**Depends on:** 15.9-T01

### 15.9-T23 — Create UAT sign-off certificate template (Word/structured format)  _(35 min)_
**Context:** WBS 15.9 UAT support. QA-12 §12.1: UAT sign-off certificate is issued by GME Product Owner after UAT exit. Must be produced after exit gate passes. Fields: project name (GMEPay+ Global QR Payment Hub), UAT period (start date - end date), environment (staging), scenarios executed (13), scenarios passed (N), open defects at sign-off (P1=0, P2=0, P3=N, P4=N), setup-time target met (Yes/No), DR drill completed (Yes/No), GME Product Owner name, signature, date. Certificate is the human-readable final artefact.
**Steps:** Create docs/uat/UAT-SIGNOFF-CERTIFICATE-TEMPLATE.md with all fields listed above; Include a section for each UAT participant role (GME Ops Settlement, GME Ops Config, GME Ops Monitoring, GME Remit team, GME Product Team) with Name, Date, Result (Pass/Fail) columns; Include a Defect Summary table: severity, total raised, total resolved, open at sign-off; Include a Conditions section: open P1/P2 defects must be 0 before Product Owner signs; open P3/P4 acceptable with documented risk acceptance; Add instructions: certificate must be signed after /internal/uat/exit-gate returns overall_passed=true
**Deliverable:** docs/uat/UAT-SIGNOFF-CERTIFICATE-TEMPLATE.md — structured sign-off certificate template
**Acceptance / logic checks:**
- Template includes all 14 go-live readiness checklist items from QA-12 §12.2 as checkboxes
- Defect Summary table has columns for P1, P2, P3, P4 counts
- Conditions section states: certificate MUST NOT be signed if open P1 or P2 defects > 0
- Each UAT participant role has an individual sign-off row
- Instructions reference GET /internal/uat/exit-gate overall_passed=true as pre-condition
**Depends on:** 15.9-T15

### 15.9-T24 — Implement rate-engine pool-identity assertion alert for UAT and go-live readiness  _(50 min)_
**Context:** WBS 15.9 UAT support. Go-live readiness checklist item 12 (QA-12 §12.2): pool-identity assertion alert wired to OPS alerting channel. Pool identity invariant (TICKET_BRIEF): collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost (tolerance 0.01 USD). If any committed transaction violates this, an alert must fire. This must be verifiable in UAT before sign-off.
**Steps:** Add pool identity assertion check in payment commit logic: after computing all rate-engine fields, assert abs((collection_usd - collection_margin_usd - payout_margin_usd) - payout_usd_cost) <= 0.01; If assertion fails: reject the transaction with error POOL_IDENTITY_VIOLATION; log ERROR with [POOL-IDENTITY-VIOLATION] prefix and all rate-engine field values; Add a monitoring alert rule: any log line containing [POOL-IDENTITY-VIOLATION] triggers an alert to the OPS channel (email or Slack per OPS-13 config); Expose GET /internal/uat/pool-identity-check that runs the assertion against the last 100 committed transactions and returns {violations:[], checked:100}; Add UAT test step to UAT-004 script: after payment, confirm GET /internal/uat/pool-identity-check returns violations=[]
**Deliverable:** Pool-identity assertion in payment commit path + monitoring alert + GET /internal/uat/pool-identity-check endpoint
**Acceptance / logic checks:**
- Transaction with engineered pool-identity deviation > 0.01 USD is rejected with POOL_IDENTITY_VIOLATION and NOT committed
- Log line [POOL-IDENTITY-VIOLATION] is emitted for any violation attempt
- GET /internal/uat/pool-identity-check returns violations=[] for all UAT-004 committed transactions
- Monitoring alert fires when [POOL-IDENTITY-VIOLATION] log line appears (manual test via log injection in staging)
- Normal UAT-004 transaction (pool identity holds: 10.2564-0.1538-0.1026=10.0000, within 0.01) passes assertion without error
**Depends on:** 15.9-T06

### 15.9-T25 — Author unit tests for UAT entry-gate and exit-gate service logic  _(45 min)_
**Context:** WBS 15.9 UAT support. Test ticket: covers the boolean gate logic in UatEntryGateService.isEntryGatePassed() and UatExitGateService.check() introduced in 15.9-T02 and 15.9-T18. These are logic-bearing services with multiple branch conditions. Edge cases: partial satisfaction, zero scenarios, all satisfied, open defects, UAT-013 missing sign-off.
**Steps:** Create UatEntryGateServiceTest with: (a) all items satisfied -> isEntryGatePassed()=true; (b) one item not satisfied -> false; (c) zero items in table -> false (defensive); Create UatExitGateServiceTest with: (a) all P1 signed off + 0 open P1/P2 defects + UAT-013 signed off -> overall_passed=true; (b) 1 open P2 defect -> false, reason=OPEN_P1_P2_DEFECTS; (c) UAT-013 not signed off -> false, reason=UAT_013_NOT_SIGNED_OFF; (d) one P1 scenario result=FAIL -> false, reason=P1_SCENARIOS_NOT_ALL_PASSED; Use in-memory H2 or Mockito mocks for repositories; Assert exact reason strings returned by check()
**Deliverable:** UatEntryGateServiceTest.java and UatExitGateServiceTest.java — unit test classes covering 7 scenarios
**Acceptance / logic checks:**
- isEntryGatePassed() returns true only when all 6 gate items have satisfied=TRUE
- isEntryGatePassed() returns false when zero rows in table
- check() returns overall_passed=true only when all 3 conditions simultaneously met
- check() reason list contains exactly the failing criterion ID when one condition fails
- All 7 test scenarios green in CI
**Depends on:** 15.9-T02, 15.9-T18

### 15.9-T26 — Author unit tests for UAT progress report generator  _(35 min)_
**Context:** WBS 15.9 UAT support. Test ticket: UatProgressReportService.generateReport() introduced in 15.9-T21. Verify aggregation logic for scenario counts and defect breakdowns. Edge cases: no runs yet (all pending), mixed results, defects across all severities.
**Steps:** Create UatProgressReportServiceTest; Scenario A: no runs yet; expect scenarios_total=13, scenarios_passed=0, scenarios_failed=0, scenarios_blocked=0, scenarios_pending=13, all_p1_signed_off=false; Scenario B: 10 PASS+signed, 2 FAIL, 1 BLOCKED; expect passed=10, failed=2, blocked=1, pending=0, all_p1_signed_off=false; Scenario C: all 13 PASS+signed; defects: P1=0, P2=0, P3=2, P4=1; expect all_p1_signed_off=true, defects_by_severity.P3=2; Scenario D: 1 open P1 defect; expect defects_by_severity.P1=1; all_p1_signed_off may still be false (relies on exit gate, not report); Use Mockito mocks for repositories
**Deliverable:** UatProgressReportServiceTest.java — unit test class with 4 scenarios
**Acceptance / logic checks:**
- Scenario A: scenarios_pending=13, all_p1_signed_off=false
- Scenario B: scenarios_passed=10, scenarios_failed=2, scenarios_blocked=1
- Scenario C: all_p1_signed_off=true, defects_by_severity.P3=2, defects_by_severity.P4=1
- Scenario D: defects_by_severity.P1=1
- All 4 test scenarios green in CI
**Depends on:** 15.9-T21

### 15.9-T27 — Author integration test: UAT scenario execution API end-to-end  _(50 min)_
**Context:** WBS 15.9 UAT support. Integration test ticket: covers the full scenario lifecycle via REST APIs introduced in 15.9-T17 and 15.9-T20. Tests use the real test DB (localhost:5433 or H2 embedded for CI). Lifecycle: raise a defect -> record FAIL run -> transition defect to CLOSED -> record PASS run -> sign off -> check exit gate.
**Steps:** Create UatScenarioIntegrationTest using Spring Boot test slice with test DB; Step 1: POST /internal/uat/defects {scenario_id=UAT-004, severity=P2, title=Rate engine returns wrong collection_usd}; assert defect_code=DEF-001; Step 2: POST /internal/uat/runs {scenario_id=UAT-004, result=FAIL, defect_ids=[DEF-001]}; assert 201; Step 3: PATCH /internal/uat/defects/1/status {new_status=RESOLVED}; then {new_status=CLOSED}; assert status=CLOSED; Step 4: POST /internal/uat/runs {scenario_id=UAT-004, result=PASS}; PUT /internal/uat/runs/{id}/sign-off {signed_off_by=ops-user}; Step 5: record PASS+sign-off for all remaining 12 scenarios; GET /internal/uat/exit-gate; with P2 defect CLOSED expect overall_passed=true
**Deliverable:** UatScenarioIntegrationTest.java — integration test covering full defect + run + sign-off + exit-gate lifecycle
**Acceptance / logic checks:**
- FAIL run for UAT-004 causes all_p1_signed_off=false in progress report
- After defect CLOSED and PASS run with sign-off, scenario contributes to all_p1_signed_off count
- After all 13 scenarios PASS+signed and zero open P1/P2 defects, exit-gate returns overall_passed=true
- Invalid status transition in step 3 (OPEN->CLOSED direct) returns 422
- Integration test is green in CI using test DB profile
**Depends on:** 15.9-T20, 15.9-T18

### 15.9-T28 — Wire UAT exit-gate check into CI/CD pre-production-deploy pipeline step  _(40 min)_
**Context:** WBS 15.9 UAT support. QA-12 §12.2 checklist item 1: all P1 UAT scenarios signed off. The CI/CD pre-production-deploy stage must call GET /internal/uat/exit-gate on the staging environment; if overall_passed=false, the pipeline fails with a clear message listing the reasons. Per OPS-13, the pre-production-deploy step runs after all tests pass and before the production deploy step. Environment variable UAT_GATE_URL must point to the staging internal API.
**Steps:** Add a new pipeline step uat-exit-gate-check in the production deploy workflow (GitHub Actions or equivalent); Step calls curl -f $UAT_GATE_URL/internal/uat/exit-gate -H Authorization: Bearer $INTERNAL_API_TOKEN; Parse response JSON: if overall_passed=false, echo each item in reasons[] and exit 1; If overall_passed=true, echo UAT exit gate PASSED and continue to production deploy; Add UAT_GATE_URL and INTERNAL_API_TOKEN to environment secrets documentation in OPS-13 runbook section; Ensure step runs BEFORE the production Docker image push step
**Deliverable:** CI/CD pipeline step uat-exit-gate-check in production deploy workflow file; OPS-13 runbook entry for UAT_GATE_URL secret
**Acceptance / logic checks:**
- Pipeline step fails (exit 1) when /internal/uat/exit-gate returns overall_passed=false
- Failure output includes each reason string from the reasons[] array
- Pipeline step succeeds (exit 0) and continues to production deploy when overall_passed=true
- Pipeline step position is before production image push in workflow order
- UAT_GATE_URL secret documented in OPS-13 runbook with example value format
**Depends on:** 15.9-T18

### 15.9-T29 — Verify go-live readiness checklist items 3 and 13: rate-engine vectors and rate-lock in production-equivalent env  _(50 min)_
**Context:** WBS 15.9 UAT support. QA-12 §12.2 items 3 and 13: (3) all rate-engine vectors RV-01 to RV-10 pass in production-equivalent environment; (13) rate-lock behavior verified in production environment. A test runner script executes all RV-xx vectors (defined in QA-12 §4.2) against the staging API and asserts expected outputs. For rate-lock: commit a payment, change treasury rate, confirm committed payment fields unchanged. These must be recorded as UAT pre-requisites.
**Steps:** Create scripts/uat/run_rate_engine_vectors.sh (or .py): loop through RV-01 to RV-10 test vectors; for each, call POST /v1/quotes with the vector inputs; compare collection_usd, send_amount, collection_margin_usd, payout_margin_usd to expected values; fail on any delta > 0.01 USD; Add rate-lock smoke test: POST /v1/payments (MPM, target_payout=13500 KRW, treasury.usd_KRW=1350); record collection_usd; update treasury.usd_KRW=1400 via Admin API; GET /v1/payments/{id}; assert collection_usd unchanged; Record test run output as UAT artefact (rate_engine_vector_results.txt) in docs/uat/; Script exits non-zero if any vector fails or rate-lock test fails; Add step in UAT-entry checklist (15.9-T01 gate item RATE_ENGINE_VECTORS_PASSED) to require this script to pass
**Deliverable:** scripts/uat/run_rate_engine_vectors.sh + docs/uat/rate_engine_vector_results.txt template; updated uat_entry_gate seed adding RATE_ENGINE_VECTORS_PASSED item
**Acceptance / logic checks:**
- Script runs against staging and produces results file with PASS/FAIL per vector
- Rate-lock test confirms collection_usd unchanged after treasury rate update post-commit
- Any deviation > 0.01 USD in any vector causes script exit code 1
- Results file included as artefact in UAT entry gate checklist
- Updated seed adds RATE_ENGINE_VECTORS_PASSED gate item to uat_entry_gate table
**Depends on:** 15.9-T22, 15.9-T14

### 15.9-T30 — Document UAT support runbook: Ops team training guide for UAT scenarios  _(40 min)_
**Context:** WBS 15.9 UAT support. QA-12 §10.3 entry criterion: Ops team trained before UAT entry. A concise runbook page covers: how to access the staging Admin portal and Partner portal, how to use /internal/uat/ APIs (entry gate, scenario runs, defect raising), how to trigger batch jobs manually (ZP0061, ZP0062, ZP0021), how to check prefunding balance and make adjustments, and escalation contacts. Must stand alone for Ops users with no developer context.
**Steps:** Create docs/uat/UAT-OPS-RUNBOOK.md; Section 1: environments and access — staging Admin portal URL, staging Partner portal URL, INTERNAL_API_TOKEN instructions; Section 2: running UAT scenarios — checklist of scripts in docs/uat/, how to record PASS/FAIL via POST /internal/uat/runs, how to raise defects via POST /internal/uat/defects; Section 3: batch job manual triggers — Admin > Batch Jobs panel; ZP0061 (settlement request), ZP0062 (confirmation poll), ZP0021 (refund batch); include expected completion times; Section 4: prefunding balance — Admin > Prefunding > {partner_id}; how to add balance, check low_balance_threshold; numeric example: add 100 USD to TestPartnerOVERSEAS; Section 5: escalation — QA lead contact, tech lead contact, product owner contact for P1 defects
**Deliverable:** docs/uat/UAT-OPS-RUNBOOK.md — Ops training guide covering 5 sections
**Acceptance / logic checks:**
- Section 3 lists all three batch jobs (ZP0061, ZP0062, ZP0021) with manual trigger instructions
- Section 4 includes numeric example (add 100 USD; new balance = old_balance + 100 USD)
- Section 2 includes exact curl examples for POST /internal/uat/runs and POST /internal/uat/defects
- Document can be understood by a non-developer Ops user with no prior GMEPay+ context
- Escalation section lists a contact for each of QA lead, tech lead, product owner roles
**Depends on:** 15.9-T23


## WBS 15.10 — Regression & defect management
### 15.10-T01 — Define regression-pack manifest schema (YAML/JSON config)  _(30 min)_
**Context:** GMEPay+ QA-12 §2.2.8: regression runs full automated suite (unit+integration+contract) on every PR against main/release, and full E2E nightly. A regression-pack manifest file must declare which test suites belong to which pack (PR-pack vs nightly-pack), their execution order, and timeout budgets. No such schema exists yet.
**Steps:** Create file ci/regression-packs.yaml with top-level keys: pr_pack (list of suite refs) and nightly_pack (list of suite refs).; Each suite entry must have fields: name (string), type (enum: unit|integration|contract|e2e), timeout_minutes (int), required (bool).; Add a JSON Schema file ci/regression-packs.schema.json that validates the YAML structure.; Document field constraints inline as YAML comments.
**Deliverable:** ci/regression-packs.yaml + ci/regression-packs.schema.json with pr_pack and nightly_pack defined
**Acceptance / logic checks:**
- pr_pack contains at least unit, integration, and contract suite entries; nightly_pack additionally contains e2e suite entries.
- JSON Schema validation passes on the sample YAML with no errors.
- A suite entry missing required field name or type fails schema validation with a descriptive error.
- timeout_minutes must be a positive integer; schema rejects zero or negative values.

### 15.10-T02 — Seed PR regression pack with unit + integration + contract suite refs  _(25 min)_
**Context:** QA-12 §2.2.8: PR regression pack scope = unit tests + integration tests + contract (API) tests. The regression-pack manifest (15.10-T01) must list exactly these three suite types for PR runs. Suite names must match the test runner module names already defined in the project.
**Steps:** Open ci/regression-packs.yaml created in 15.10-T01.; Under pr_pack add entries referencing: hub-core-unit (type: unit, required: true), hub-core-integration (type: integration, required: true), partner-api-contract (type: contract, required: true).; Set timeout_minutes for each entry (unit: 15, integration: 20, contract: 15).; Validate the updated file against the JSON Schema from 15.10-T01.
**Deliverable:** ci/regression-packs.yaml updated with three pr_pack suite entries
**Acceptance / logic checks:**
- pr_pack contains exactly three entries with types unit, integration, and contract respectively.
- All three entries have required: true.
- JSON Schema validation passes on the updated file.
- timeout_minutes values are positive integers within the 60-minute CI budget.
**Depends on:** 15.10-T01

### 15.10-T03 — Seed nightly regression pack with full E2E suite refs  _(25 min)_
**Context:** QA-12 §2.2.8: nightly regression runs the full E2E suite in addition to unit+integration+contract. E2E scenarios are defined in QA-12 §5 and cover Hub Core (HC-001 to HC-015), Admin (AD-001 to AD-015), Partner Portal (PP-001 to PP-007), and ZeroPay batch (ZP-001 to ZP-014). The nightly pack must include these as additional entries beyond the PR pack.
**Steps:** Open ci/regression-packs.yaml.; Under nightly_pack copy all three pr_pack suite entries then add: hub-core-e2e (type: e2e, timeout_minutes: 30), admin-e2e (type: e2e, timeout_minutes: 20), partner-portal-e2e (type: e2e, timeout_minutes: 15), zeropay-batch-e2e (type: e2e, timeout_minutes: 30).; Mark all e2e entries as required: true.; Validate against JSON Schema.
**Deliverable:** ci/regression-packs.yaml updated with nightly_pack containing 7 suite entries
**Acceptance / logic checks:**
- nightly_pack contains unit, integration, contract, and four e2e suite entries (7 total).
- All e2e entries have type: e2e and required: true.
- Total timeout budget for nightly pack does not exceed 150 minutes (sufficient for nightly CI window).
- JSON Schema validation passes.
**Depends on:** 15.10-T02

### 15.10-T04 — Implement regression-pack loader and suite discovery utility  _(45 min)_
**Context:** The regression-pack manifest (ci/regression-packs.yaml) needs a programmatic loader that reads the YAML, resolves suite references to actual test runner commands, and returns an ordered execution plan. Language follows project stack (Java). Suite type-to-command mapping: unit -> mvn test -pl {name}, integration -> mvn verify -pl {name} -P integration, contract -> mvn verify -pl {name} -P contract, e2e -> mvn verify -pl {name} -P e2e.
**Steps:** Create class qa/regression/RegressionPackLoader.java with method loadPack(String packName) returning List<SuiteExecution>.; SuiteExecution is a record with fields: suiteName, command, timeoutMinutes, required.; Validate that all suite types in the YAML map to known command templates; throw IllegalArgumentException for unknown types.; Write unit test RegressionPackLoaderTest verifying loadPack(pr_pack) returns 3 entries in declaration order.
**Deliverable:** RegressionPackLoader.java + RegressionPackLoaderTest.java
**Acceptance / logic checks:**
- loadPack(pr_pack) returns exactly 3 SuiteExecution entries in manifest order.
- loadPack(nightly_pack) returns exactly 7 SuiteExecution entries.
- An unknown suite type in the YAML causes IllegalArgumentException with a message naming the invalid type.
- Each SuiteExecution command string contains the suite name from the manifest.
**Depends on:** 15.10-T03

### 15.10-T05 — Implement CI pipeline step: PR regression pack trigger  _(40 min)_
**Context:** QA-12 §2.2.8 entry criterion: regression runs on every PR raised against main/release branch. The CI pipeline (e.g., GitHub Actions or equivalent) must have a workflow that triggers on pull_request events targeting main or release/*, runs the PR regression pack (unit+integration+contract), and fails the PR if any required suite fails.
**Steps:** Create or update .github/workflows/regression-pr.yml (or equivalent CI config).; Set trigger: on: [pull_request] with branches: [main, release/*].; Add steps: checkout, setup-java, then invoke RegressionPackLoader pr_pack and execute each suite sequentially.; Fail the workflow (exit non-zero) if any suite with required: true returns a non-zero exit code.; Upload test reports as artifacts on failure.
**Deliverable:** .github/workflows/regression-pr.yml configured for PR regression pack
**Acceptance / logic checks:**
- Workflow triggers on pull_request targeting main; does NOT trigger on push to feature branches.
- All three suite types (unit, integration, contract) execute in declared order.
- If hub-core-unit fails (required: true), workflow exits with failure code and subsequent suites are skipped.
- Test report artifacts are uploaded even on failure so results are inspectable.
**Depends on:** 15.10-T04

### 15.10-T06 — Implement CI pipeline step: nightly regression pack trigger  _(40 min)_
**Context:** QA-12 §2.2.8: full E2E suite runs nightly. The nightly pipeline must schedule the full regression pack (unit+integration+contract+e2e), run against the sandbox/integration environment with ZeroPay SFTP stub active, and email/notify on failure. Nightly window: after 02:00 KST batch window, before business hours.
**Steps:** Create .github/workflows/regression-nightly.yml triggered on schedule cron: 0 20 * * * (UTC 20:00 = KST 05:00, after ZP batch window).; Configure environment to point to sandbox environment variables.; Invoke RegressionPackLoader nightly_pack and execute all 7 suites.; On any failure, post a notification (Slack webhook or email) with suite name and failure count.; Archive full test reports as CI artifacts with 30-day retention.
**Deliverable:** .github/workflows/regression-nightly.yml configured for nightly E2E regression
**Acceptance / logic checks:**
- Workflow triggers on cron schedule 0 20 * * * (UTC) and not on push events.
- All 7 suite entries execute; e2e suites run against sandbox env vars (SANDBOX_API_URL, etc.).
- Failure notification fires and names the failing suite(s); does not fire on success.
- Test artifacts retained for 30 days per artifact retention setting.
**Depends on:** 15.10-T04

### 15.10-T07 — Define severity classification enum and defect record schema  _(35 min)_
**Context:** QA-12 §11.1 defines four severity levels: P1-Critical (production blocker, data loss, security breach, payment not processing; examples: pool identity violated, prefunding double-spend, ZP file not generated), P2-High (core feature broken, no workaround, settlement delayed), P3-Medium (feature impaired, workaround available, no data risk), P4-Low (cosmetic, docs). A defect record must capture severity, affected component, steps to reproduce, and status.
**Steps:** Create enum qa/defect/Severity.java with values P1_CRITICAL, P2_HIGH, P3_MEDIUM, P4_LOW and a description field.; Create record qa/defect/DefectRecord.java with fields: id (String), title (String), severity (Severity), component (String), stepsToReproduce (String), status (enum: OPEN, IN_PROGRESS, FIXED, VERIFIED, CLOSED), createdAt (Instant), resolvedAt (Instant, nullable).; Add factory method DefectRecord.create(id, title, severity, component, steps) that sets status=OPEN and createdAt=now().; Write unit test DefectRecordTest asserting that create() sets status OPEN and resolvedAt null.
**Deliverable:** Severity.java enum + DefectRecord.java record + DefectRecordTest.java
**Acceptance / logic checks:**
- Severity enum has exactly 4 values matching QA-12 definitions; each has a non-empty description.
- DefectRecord.create() returns status=OPEN and resolvedAt=null.
- DefectRecord is immutable (record type); no setters.
- A defect with severity P1_CRITICAL and component=HUB_CORE serialises to JSON with all required fields present (verify with Jackson ObjectMapper in test).

### 15.10-T08 — Implement defect triage workflow: daily P1/P2 review logic  _(45 min)_
**Context:** QA-12 §11.2: daily triage meeting reviews all new P1/P2 defects. P1 defects require immediate fix and re-test within same sprint. P2 defects require fix before UAT entry. P3/P4 are scheduled per sprint priority. The triage service must query open P1/P2 defects, age-flag P1s older than 1 day, and generate a daily triage report.
**Steps:** Create class qa/defect/TriageService.java with method getDailyTriageItems(LocalDate forDate) returning List<TriageItem>.; TriageItem has fields: defect (DefectRecord), isOverdue (boolean), triageNote (String).; isOverdue = true if defect severity is P1_CRITICAL and (forDate - createdAt.toLocalDate()) > 1 day, OR if severity is P2_HIGH and UAT entry date is imminent (configurable threshold, default 3 days).; Add method generateTriageReport(List<TriageItem>) returning a plain-text report string listing defect id, severity, title, age in days, and isOverdue flag.; Write unit test TriageServiceTest with scenarios: P1 raised today (not overdue), P1 raised 2 days ago (overdue), P2 normal.
**Deliverable:** TriageService.java + TriageServiceTest.java
**Acceptance / logic checks:**
- P1 defect with createdAt = today is not flagged overdue; P1 with createdAt = 2 days ago is flagged overdue.
- P2 defect with 5 days to UAT entry date is not overdue; P2 with 2 days to UAT entry is overdue (using 3-day threshold).
- generateTriageReport lists each item with id, severity label, title, age-days, and overdue flag.
- getDailyTriageItems returns only OPEN and IN_PROGRESS defects; FIXED/VERIFIED/CLOSED defects excluded.
**Depends on:** 15.10-T07

### 15.10-T09 — Implement P1 defect exit-gate check: same-sprint fix enforcement  _(45 min)_
**Context:** QA-12 §11.2 and §11.3: P1 defects must be fixed and re-tested within the same sprint; the Unit exit gate is 0 P1/P2 failures; Integration exit gate is 0 P1/P2; System/E2E gate is 0 P1 open. An exit-gate checker must count open P1 (and P2) defects for a given test level and return pass/fail with a reason.
**Steps:** Create class qa/defect/ExitGateChecker.java with method checkGate(TestLevel level, List<DefectRecord> allDefects) returning GateResult.; GateResult has fields: passed (boolean), reason (String), openP1Count (int), openP2Count (int).; TestLevel enum: UNIT, INTEGRATION, CONTRACT, SYSTEM_E2E, PERFORMANCE, SECURITY, UAT.; Gate logic per QA-12 §11.3: UNIT -> pass if openP1==0 && openP2==0; INTEGRATION -> same; CONTRACT -> pass if 0 schema mismatches (use openP1==0 as proxy); SYSTEM_E2E -> pass if openP1==0 (P2 <= 2 allowed if documented); UAT -> pass if openP1==0 && openP2==0.; Write ExitGateCheckerTest with vectors: UNIT gate with 1 P1 open (fails), SYSTEM_E2E with 1 P2 open (passes), UAT with 1 P2 open (fails).
**Deliverable:** ExitGateChecker.java + ExitGateCheckerTest.java
**Acceptance / logic checks:**
- UNIT gate fails if any OPEN P1 or P2 defect exists; passes if all are FIXED or higher.
- SYSTEM_E2E gate fails if openP1 > 0; passes if openP1 == 0 even with openP2 == 2.
- SYSTEM_E2E gate fails if openP2 > 2.
- UAT gate fails if openP1 > 0 OR openP2 > 0.
- GateResult.reason string names the blocking defect IDs when gate fails.
**Depends on:** 15.10-T07

### 15.10-T10 — Unit tests: ExitGateChecker edge cases  _(35 min)_
**Context:** Exit-gate logic (15.10-T09) has edge cases that must be explicitly tested: boundary values (exactly 2 P2s at SYSTEM_E2E is pass; 3 P2s is fail), resolved defects not counted (status FIXED/VERIFIED/CLOSED must be ignored), mixed severities, and empty defect list.
**Steps:** In ExitGateCheckerTest add test: SYSTEM_E2E with exactly 2 OPEN P2s and 0 P1s -> passes.; Add test: SYSTEM_E2E with 3 OPEN P2s -> fails with reason listing P2 count.; Add test: UNIT with 1 FIXED P1 and 0 OPEN defects -> passes (resolved defects not counted).; Add test: empty defect list for all gate levels -> all pass.; Add test: PERFORMANCE gate with 0 P1/P2 -> passes; SECURITY gate with 1 P2 open -> passes (security gate only blocks on CRITICAL/HIGH findings, mapped to P1 in this context; P2 is HIGH so security gate with openP2>0 fails).
**Deliverable:** ExitGateCheckerTest.java extended with 5 additional edge-case tests (all green)
**Acceptance / logic checks:**
- SYSTEM_E2E with exactly 2 open P2s returns passed=true.
- SYSTEM_E2E with 3 open P2s returns passed=false and openP2Count=3 in GateResult.
- UNIT with only FIXED P1 defects returns passed=true.
- Empty defect list returns passed=true for every TestLevel.
- SECURITY gate with 1 OPEN P2_HIGH returns passed=false (P2_HIGH maps to HIGH security finding per §11.3).
**Depends on:** 15.10-T09

### 15.10-T11 — Implement coverage-decrease detection guard for PR regression  _(40 min)_
**Context:** QA-12 §2.2.8 exit criterion: coverage must not decrease between the base branch and the PR. The guard reads the base-branch coverage report (from CI artifact) and the PR coverage report, computes the delta, and fails the PR if line coverage decreased.
**Steps:** Create script ci/coverage-guard.sh (or Java utility) that accepts two args: base_coverage_pct and pr_coverage_pct (floats).; If pr_coverage_pct < base_coverage_pct, print an error and exit 1.; Also enforce absolute minimum 85% line coverage per QA-12 §2.2.1; exit 1 if pr_coverage_pct < 85.0.; Add to .github/workflows/regression-pr.yml a step that extracts coverage from JaCoCo XML report and invokes coverage-guard.sh.; Write a unit test (bash bats or inline shell test) with vectors: base=87, pr=86 (fail), base=85, pr=85 (pass), base=84, pr=85 (pass but absolute check still fires since 84<85 would have already failed on base — test that base=85, pr=84 fails).
**Deliverable:** ci/coverage-guard.sh integrated into regression-pr.yml + coverage guard tests
**Acceptance / logic checks:**
- base=87.0, pr=86.0 exits with code 1 and prints a coverage-decrease message.
- base=85.0, pr=85.0 exits with code 0 (no decrease, at minimum threshold).
- base=90.0, pr=84.9 exits with code 1 citing both decrease and below-85% threshold.
- Coverage guard step appears in regression-pr.yml after the test-execution step.
**Depends on:** 15.10-T05

### 15.10-T12 — Define defect report document schema (JSON) for the parent deliverable  _(30 min)_
**Context:** WBS 15.10 parent deliverable is a Defect/Exit Report. This report must be machine-readable (for CI gate consumption) and human-readable. It captures: run metadata, exit gate results per level, open defect summary by severity, coverage summary, and pack execution results. Define the JSON schema before implementing the generator.
**Steps:** Create file qa/reports/defect-exit-report.schema.json as a JSON Schema draft-07 document.; Top-level fields: reportId (string), generatedAt (ISO-8601 string), packName (string, enum: pr_pack|nightly_pack), gateResults (array of GateResultEntry), openDefects (array of DefectSummary), coveragePct (number), overallPassed (boolean).; GateResultEntry fields: level (string), passed (boolean), openP1Count (int), openP2Count (int), reason (string).; DefectSummary fields: id (string), severity (string), title (string), agedays (int).; Validate schema against at least one valid and one invalid example document using a JSON Schema validator.
**Deliverable:** qa/reports/defect-exit-report.schema.json
**Acceptance / logic checks:**
- Schema validates a complete report JSON with 2 gate results and 1 open defect without error.
- Schema rejects a report missing the required overallPassed field.
- Schema rejects a GateResultEntry with a non-integer openP1Count.
- Schema enum on packName rejects value daily_pack; only pr_pack and nightly_pack accepted.
**Depends on:** 15.10-T09

### 15.10-T13 — Implement defect/exit report generator service  _(45 min)_
**Context:** Using the schema from 15.10-T12, implement a Java service that assembles the DefectExitReport from: list of DefectRecord objects, ExitGateChecker results, coverage percentage, and pack execution results. The generator must compute overallPassed as true only if ALL required gate levels pass AND coverage >= 85.0%.
**Steps:** Create class qa/reports/DefectExitReportGenerator.java with method generate(ReportInput input) returning DefectExitReport POJO.; ReportInput holds: packName, List<DefectRecord> allDefects, double coveragePct, Map<TestLevel, GateResult> gateResults.; overallPassed = all GateResult.passed == true AND coveragePct >= 85.0.; Serialize the report to JSON using Jackson; include generatedAt as current UTC ISO-8601.; Write DefectExitReportGeneratorTest with a happy-path scenario: 0 open defects, all gates passed, coverage 88.0 -> overallPassed=true.
**Deliverable:** DefectExitReportGenerator.java + DefectExitReportGeneratorTest.java
**Acceptance / logic checks:**
- Report with all gates passed and coverage 88.0 has overallPassed=true.
- Report with one SYSTEM_E2E gate failed has overallPassed=false even if coverage=100.
- Report with all gates passed but coverage=84.9 has overallPassed=false.
- Generated JSON conforms to the schema defined in 15.10-T12 (validate with schema validator in test).
- generatedAt field is present and parseable as ISO-8601 UTC.
**Depends on:** 15.10-T12, 15.10-T09

### 15.10-T14 — Unit tests: DefectExitReport generator edge cases  _(30 min)_
**Context:** The report generator (15.10-T13) must handle edge cases: no defects at all, all defects resolved, coverage exactly at boundary, mixed gate outcomes. These scenarios correspond to real regression run states the CI pipeline will encounter.
**Steps:** Add test: empty defect list + all gates passed + coverage=85.0 exactly -> overallPassed=true (boundary).; Add test: all defects status=CLOSED + UNIT gate passed + coverage=90 -> openDefects array in report is empty; overallPassed=true.; Add test: P1 open defect exists, UAT gate fails -> openDefects contains the P1 entry; overallPassed=false.; Add test: coverage=85.0 exactly (boundary) -> passes coverage check.; Add test: coverage=84.99 (just below boundary) -> overallPassed=false.
**Deliverable:** DefectExitReportGeneratorTest.java extended with 5 boundary/edge-case tests (all green)
**Acceptance / logic checks:**
- coverage=85.0 exactly yields overallPassed=true when all gates pass.
- coverage=84.99 yields overallPassed=false.
- Empty defect list produces openDefects:[] in JSON output.
- All-CLOSED defects produce openDefects:[] (closed defects not included in report summary).
- P1 OPEN defect appears in openDefects with severity=P1_CRITICAL.
**Depends on:** 15.10-T13

### 15.10-T15 — Wire exit-gate check into PR regression workflow  _(40 min)_
**Context:** The CI PR workflow (15.10-T05) must invoke ExitGateChecker after test execution and call DefectExitReportGenerator to produce a report artifact. If any required gate fails, the workflow must fail with a non-zero exit and attach the report. Defect data is read from a JSON file ci/open-defects.json maintained by the QA team.
**Steps:** Add ci/open-defects.json as a tracked file in the repo (initially empty array []).; Add a workflow step in regression-pr.yml that reads open-defects.json, loads coverage from JaCoCo report, and invokes the Java gate-check main class.; On gate failure, print the full GateResult reason and fail the workflow.; Upload the generated defect-exit-report.json as a workflow artifact regardless of outcome.; Write a test that simulates the workflow step with a seeded open-defects.json containing one P1 OPEN defect and asserts the step exits non-zero.
**Deliverable:** regression-pr.yml updated with gate-check step + ci/open-defects.json template
**Acceptance / logic checks:**
- Workflow step fails (exit 1) when open-defects.json contains one OPEN P1_CRITICAL defect at UNIT level.
- Workflow step passes (exit 0) when open-defects.json is empty and coverage >= 85.
- defect-exit-report.json artifact is uploaded in both pass and fail cases.
- ci/open-defects.json is valid JSON; initially empty array [].
**Depends on:** 15.10-T13, 15.10-T11

### 15.10-T16 — Wire exit-gate check into nightly regression workflow  _(40 min)_
**Context:** The nightly regression workflow (15.10-T06) must also run the exit-gate check, but additionally assess the SYSTEM_E2E gate (allowing up to 2 open P2s with documented mitigations per QA-12 §11.3). The nightly report is the primary Defect/Exit Report deliverable; it must be emailed to the QA lead and product owner on completion.
**Steps:** Update .github/workflows/regression-nightly.yml to include the gate-check step from 15.10-T15 after all suites complete.; Pass packName=nightly_pack to the report generator.; Add SYSTEM_E2E gate check specifically; allow up to 2 open P2s (pass if openP1==0 and openP2<=2).; After report generation, email the JSON report (or a HTML summary) to QA_LEAD_EMAIL and PRODUCT_OWNER_EMAIL environment variables using the configured notification action.; Archive the nightly defect-exit-report.json with 90-day retention.
**Deliverable:** regression-nightly.yml updated with gate-check + email notification step
**Acceptance / logic checks:**
- Nightly workflow generates defect-exit-report.json with packName=nightly_pack.
- SYSTEM_E2E gate in the nightly report passes with openP1=0 and openP2=2.
- SYSTEM_E2E gate fails with openP2=3 and workflow exits non-zero.
- Email notification step fires on both pass and fail; uses QA_LEAD_EMAIL env var.
- Report artifact retention is set to 90 days.
**Depends on:** 15.10-T15, 15.10-T06

### 15.10-T17 — Implement pool-identity assertion runtime check wired to regression suite  _(45 min)_
**Context:** QA-12 §4.3 and HC-015: the pool-identity assertion must be embedded in production code for every committed cross-border transaction. The regression suite must include a test that triggers the assertion path and verifies it fires (and raises a CRITICAL alert) when violated. Assertion: abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) <= 0.01 USD; violation = CRITICAL alert.
**Steps:** Confirm class RateEngine.java contains method assertPoolIdentity(BigDecimal collection_usd, BigDecimal collection_margin_usd, BigDecimal payout_margin_usd, BigDecimal payout_usd_cost) that throws PoolIdentityViolationException when delta > 0.01.; In PoolIdentityAssertionTest (regression suite), add test: collection_usd=10.2564, collection_margin=0.1538, payout_margin=0.1026, payout_usd_cost=10.0000 -> delta=0.0000, no exception (RV-01 data).; Add test: delta=0.0099 -> no exception (within tolerance).; Add test: delta=0.0101 -> PoolIdentityViolationException thrown.; Add test: verify that PoolIdentityViolationException triggers an CRITICAL alert log entry (check log output contains CRITICAL and pool_identity_violation).
**Deliverable:** PoolIdentityAssertionTest.java with 4 test cases covering tolerance boundary
**Acceptance / logic checks:**
- RV-01 values (collection_usd=10.2564, margins=0.1538+0.1026, payout=10.0000) pass assertion (delta=0.0000 within 0.01 tolerance).
- delta=0.0099 passes; delta=0.0101 throws PoolIdentityViolationException.
- On exception, CRITICAL-level log entry is emitted containing the string pool_identity_violation.
- Assertion method is called within the production commit code path (verify via integration test that HC-015 scenario passes).

### 15.10-T18 — Implement rate-engine regression test harness: RV-01 to RV-05 vectors  _(55 min)_
**Context:** QA-12 §4.2 defines 10 rate-engine test vectors (RV-01 to RV-10). All must pass in the regression suite. Vectors RV-01 to RV-05 are: RV-01 cross-border MNT->KRW (target=13500 KRW, cost_rate_coll=3500, cost_rate_pay=1350, m_a=0.015, m_b=0.010, svc=500 MNT -> collection_usd=10.2564, send_amount=35897.44 MNT, collection_amount=36397.44 MNT); RV-02 identity leg A (send_amount=collection_usd); RV-03 both legs identity; RV-04 same-currency short-circuit (KRW->KRW, collection=14000 KRW); RV-05 partner B within 0.8% tolerance -> commits.
**Steps:** Create RateEngineRegressionTest.java in the regression suite module.; Implement testRV01() using test fixture rates from QA-12 §3.3: assert collection_usd=10.2564 (+/- 0.0001), send_amount=35897.44 (+/- 0.01 MNT), collection_amount=36397.44 MNT, pool-identity delta <= 0.01.; Implement testRV02(): send_amount==collection_usd (within 0.0001), pool identity holds.; Implement testRV03(): both legs identity, collection_amount=103.0641 USD.; Implement testRV04(): collection_amount=14000 KRW, no USD pool fields (null/absent), no margin deducted.; Implement testRV05(): partner B quote 1360.80 (0.80% deviation <= 1.0% tolerance) -> transaction commits, recorded cost_rate_pay=1360.80.
**Deliverable:** RateEngineRegressionTest.java with RV-01 through RV-05 test methods (all green)
**Acceptance / logic checks:**
- RV-01: collection_amount=36397.44 MNT within 0.01 MNT tolerance; pool-identity delta<=0.01 USD.
- RV-02: send_amount equals collection_usd within 0.0001; collection_amount=10.7564 USD.
- RV-03: collection_amount=103.0641 USD within 0.01.
- RV-04: collection_amount=14000 KRW; collection_usd is null; no margin fields.
- RV-05: transaction status is COMMITTED and cost_rate_pay recorded as 1360.80.

### 15.10-T19 — Implement rate-engine regression test harness: RV-06 to RV-10 vectors  _(55 min)_
**Context:** QA-12 §4.2 vectors RV-06 to RV-10: RV-06 partner B quote 1.2% deviation (1366.20 vs 1350.00) -> PARTNER_B_QUOTE_DEVIATION, no commit; RV-07 min-margin exactly 2% (m_a=0.010, m_b=0.010, collection_usd=10.2041) -> commits; RV-08 below-minimum 1.9% (m_a=0.010, m_b=0.009) -> rejected at config time; RV-09 rounding edge case (target=10001 KRW, payout_usd_cost=7.40815, send_amount=26593.33 MNT) -> no overflow, pool identity; RV-10 large service charge 5000 MNT (send_amount=35897.44, collection_amount=40897.44) -> pool identity not affected by svc charge.
**Steps:** Add testRV06() to RateEngineRegressionTest.java: assert error code PARTNER_B_QUOTE_DEVIATION returned; transaction not committed; no prefunding deduction.; Add testRV07(): assert rule with m_a+m_b=0.020 accepted; collection_usd=10.2041 within 0.0001; transaction commits.; Add testRV08(): assert Admin rule-save with m_a=0.010, m_b=0.009 (sum=0.019) throws/returns validation error referencing 2.0% minimum constraint.; Add testRV09(): target=10001 KRW; assert no overflow; pool-identity delta<=0.01 USD; intermediate values stored to at least 4 decimal places.; Add testRV10(): service_charge=5000 MNT; assert send_amount==35897.44 (unchanged vs RV-01); collection_amount=40897.44 MNT; pool identity check uses send_amount not collection_amount.
**Deliverable:** RateEngineRegressionTest.java extended with RV-06 through RV-10 test methods (all green)
**Acceptance / logic checks:**
- RV-06: method returns error PARTNER_B_QUOTE_DEVIATION and transaction object has status NOT_COMMITTED.
- RV-07: rule accepted; collection_usd=10.2041 within 0.0001; combined margin=0.020 recorded.
- RV-08: rule-save method throws or returns error containing 2.0% or 0.02 minimum constraint message.
- RV-09: all intermediate BigDecimal values have scale >= 4; pool-identity delta<=0.01 USD.
- RV-10: send_amount=35897.44 (same as RV-01); collection_amount=40897.44; pool-identity computed without service_charge.
**Depends on:** 15.10-T18

### 15.10-T20 — Implement regression test: prefunding atomic deduction race condition (PF-003)  _(55 min)_
**Context:** QA-12 §5.6 PF-003 and QA-12 §2.2.2: the prefunding SELECT FOR UPDATE race-condition test must pass as an integration test exit criterion. Two concurrent requests from the same OVERSEAS partner (P-TEST-002, balance=50000 USD, each requesting collection_usd=30000 USD) must result in exactly one successful deduction and one INSUFFICIENT_PREFUNDING rejection; balance after=20000 USD.
**Steps:** Create PrefundingRaceConditionTest.java in the integration test module.; Seed P-TEST-002 with balance=50000.00 USD in a test-transaction-rolled-back DB context.; Submit two concurrent POST /v1/payments requests (via two threads) both requesting collection_usd=30000 USD.; Assert exactly one request succeeds (HTTP 200 / status=APPROVED) and exactly one fails with error INSUFFICIENT_PREFUNDING.; Assert final balance in DB = 20000.00 USD (first deduction only).
**Deliverable:** PrefundingRaceConditionTest.java integration test (green against real test DB with SELECT FOR UPDATE)
**Acceptance / logic checks:**
- Exactly 1 of the 2 concurrent requests returns status APPROVED; the other returns INSUFFICIENT_PREFUNDING.
- Final prefunding balance = 50000.00 - 30000.00 = 20000.00 USD (no double-deduction).
- The test DB confirms SELECT FOR UPDATE lock was acquired (verify via EXPLAIN or lock-wait log).
- Test is deterministic across 10 repeated runs with no intermittent double-deduction failures.

### 15.10-T21 — Implement regression test: rate-lock at commit (HC-008 / AD-012)  _(45 min)_
**Context:** QA-12 HC-008: after a transaction is committed, changing the treasury rate in Admin must not alter the committed transaction values. QA-12 AD-012: rate change applies to new transactions only. Rate-lock means all USD-pool values and derived rates are permanently recorded at commit time. Test must change treasury.usd_krw from 1350.00 to 1400.00 after committing an RV-01 payment and verify the committed record still shows cost_rate_pay=1350.00.
**Steps:** Create RateLockRegressionTest.java in the integration test module.; Commit an RV-01 payment (target=13500 KRW, treasury.usd_krw=1350.00) and record the transaction ID.; Via Admin API, update treasury.usd_krw to 1400.00.; Retrieve the committed transaction by ID and assert recorded cost_rate_pay=1350.00 (not 1400.00).; Issue a new GET /v1/rates request and assert new quote uses cost_rate_pay=1400.00.
**Deliverable:** RateLockRegressionTest.java integration test (green)
**Acceptance / logic checks:**
- Committed transaction has cost_rate_pay=1350.00 after treasury update to 1400.00.
- New GET /v1/rates response returns cost_rate_pay=1400.00 (new rate applies to new quotes).
- Committed send_amount and collection_amount are unchanged after treasury update.
- Rate-lock test passes without flakiness across 5 repeated runs.

### 15.10-T22 — Implement regression test: rate-quote TTL expiry (HC-005 / PA-008)  _(40 min)_
**Context:** QA-12 HC-005 and PA-008: if a partner obtains a rate quote then waits longer than the TTL before committing, the system must return RATE_QUOTE_EXPIRED with no prefunding deduction. Default TTL = 60s (aggregator-bound) or 300s otherwise; configurable 60-1800s. Test must freeze time or use a short TTL to avoid real waiting.
**Steps:** Create RateQuoteTTLTest.java in the integration test module.; Configure test rule with TTL=2s (minimum accepted) via Admin API.; Obtain a GET /v1/rates quote; record validUntil.; Advance the test clock (or Thread.sleep(3000)) past validUntil.; Attempt POST /v1/payments with the expired quote token; assert error code RATE_QUOTE_EXPIRED and HTTP 422.; Assert prefunding balance unchanged (no deduction on expired quote).
**Deliverable:** RateQuoteTTLTest.java integration test (green, uses 2s TTL or clock injection)
**Acceptance / logic checks:**
- POST /v1/payments with expired quote returns HTTP 422 and error code RATE_QUOTE_EXPIRED.
- Prefunding balance is unchanged after the rejected expired-quote attempt.
- A quote committed within its TTL succeeds (control case in same test class).
- TTL of 59s is rejected at configuration time (below minimum 60s); error names the 60s constraint.

### 15.10-T23 — Implement regression test: same-currency short-circuit (RV-04 / HC-001 / HC-002)  _(35 min)_
**Context:** QA-12 RV-04 and HC-001/HC-002: GME Remit (P-TEST-001, LOCAL, KRW) uses the same-currency short-circuit where collection=settle_A=settle_B=payout=KRW. USD pool is entirely skipped; collection_amount = target_payout + service_charge. For target=13500 KRW + svc=500 KRW = 14000 KRW. No margin applied; no prefunding deducted.
**Steps:** Create SameCurrencyShortCircuitTest.java in the regression test module.; Submit GET /v1/rates for P-TEST-001 (LOCAL, KRW), scheme=ZeroPay, target=13500 KRW, svc=500 KRW.; Assert response: collection_amount=14000 KRW, collection_usd=null/absent, collection_margin_usd=null/absent, payout_margin_usd=null/absent, offer_rate_coll=null/absent.; Submit POST /v1/payments and assert status=APPROVED and no prefunding deduction (LOCAL partner).; Assert audit record shows both rate slots recorded as IDENTITY.
**Deliverable:** SameCurrencyShortCircuitTest.java regression test (green)
**Acceptance / logic checks:**
- GET /v1/rates response has collection_amount=14000 KRW and no USD pool fields in JSON body.
- POST /v1/payments returns status=APPROVED with collection_amount=14000 KRW.
- Prefunding balance query for P-TEST-001 returns no balance (LOCAL partners have no prefunding).
- Audit record for the transaction has rate_source=IDENTITY for both collection and payout rate slots.

### 15.10-T24 — Implement regression test: config-without-code (new scheme + partner in < 30 min)  _(45 min)_
**Context:** QA-12 UAT-013 and AD-013: a new scheme and partner must be operable in under 30 minutes with zero code deployments or merges. The regression suite must include an automated timed walkthrough that creates a new synthetic scheme (SCH-TEST-99) and partner (P-TEST-999, OVERSEAS) via Admin API only, then executes a successful test payment to confirm no deployment was required.
**Steps:** Create ConfigWithoutCodeTest.java in the regression test module.; Record start time; create ZeroPay scheme entry SCH-TEST-99 via POST /admin/v1/schemes.; Create partner P-TEST-999 (OVERSEAS, USD) via POST /admin/v1/partners; configure mapping to SCH-TEST-99 with m_a=0.015, m_b=0.010.; Fund P-TEST-999 with 10000 USD prefunding; execute a GET /v1/rates and POST /v1/payments from P-TEST-999.; Assert end time - start time < 30 minutes; assert payment approved; assert no deployment event logged.
**Deliverable:** ConfigWithoutCodeTest.java automated timed walkthrough regression test (green)
**Acceptance / logic checks:**
- Full create-scheme + create-partner + fund + pay flow completes in under 30 minutes wall-clock.
- Payment from newly created P-TEST-999 is APPROVED with correct collection_amount.
- No application restart or code deployment occurred between scheme creation and payment approval.
- Removing P-TEST-999 and SCH-TEST-99 (test teardown) leaves no orphan data in DB.

### 15.10-T25 — Implement regression test: partner data isolation and internal field hiding (PP-005 / PP-007)  _(40 min)_
**Context:** QA-12 PP-005: m_a, m_b, cost rates, and GME margin must NOT appear in any Partner API or portal response. QA-12 PP-007: Partner A must not be able to query Partner B transactions. These are regression-critical security properties.
**Steps:** Create PartnerDataIsolationTest.java in the regression test module.; Authenticate as P-TEST-001 and call GET /v1/transactions; assert response body does not contain fields m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd, or payout_usd_cost.; Using P-TEST-001 credentials, attempt GET /v1/payments/{txn_id_of_P-TEST-002}; assert HTTP 403 (IDOR rejected).; Using P-TEST-001 credentials, attempt GET /v1/balance; assert appropriate response (LOCAL partner, no prefunding balance shown or 404).; Using P-TEST-002 credentials, call GET /v1/transactions and assert only P-TEST-002 transactions in results.
**Deliverable:** PartnerDataIsolationTest.java regression test (green)
**Acceptance / logic checks:**
- GET /v1/transactions for P-TEST-001 response JSON contains no field named m_a, m_b, cost_rate_coll, or cost_rate_pay.
- GET /v1/payments/{P-TEST-002-txn-id} with P-TEST-001 auth returns HTTP 403.
- GET /v1/transactions for P-TEST-002 returns only transactions belonging to P-TEST-002.
- Field-hiding check uses JSON deep-traversal to catch nested occurrences of internal fields.

### 15.10-T26 — Implement regression test: webhook delivery and retry (PA-006 / §7.3)  _(50 min)_
**Context:** QA-12 §7.3: if partner webhook returns non-2xx, system retries with exponential backoff. Test must confirm: mock webhook returning HTTP 500 for first 3 attempts, successful on 4th; payload identical on retry; idempotency preserved (no duplicate payment.approved events after retry success).
**Steps:** Create WebhookRetryRegressionTest.java in the regression test module.; Start a mock HTTP server on localhost that returns 500 for the first 3 POST requests then 200 on the 4th.; Configure P-TEST-002 webhook URL to point to the mock server.; Trigger a successful MPM payment and record the txn_id.; Assert mock server received exactly 4 POST requests all with identical payload (same txn_id, same collection_amount).; Assert payment.approved event recorded exactly once in DB (no duplicates).
**Deliverable:** WebhookRetryRegressionTest.java integration test (green)
**Acceptance / logic checks:**
- Mock server receives exactly 4 webhook calls before success.
- All 4 webhook payloads are byte-for-byte identical (same txn_id, status, collection_amount).
- payment.approved event count in DB = 1 (idempotent delivery).
- If all 4 retries fail (mock always returns 500), a payment.failed webhook is eventually fired and the event trail records WEBHOOK_DELIVERY_FAILED.

### 15.10-T27 — Implement regression test: ZeroPay batch timing windows (ZP-001 / §9.2)  _(50 min)_
**Context:** QA-12 §9.2 and ZP-001: ZP0011 (payment result, GME->ZP) must be transmitted to SFTP by 02:00 KST. In test, run batch at simulated 01:55 KST and confirm SFTP put completes before 02:00. Test uses a stub SFTP server (not the real 한결원 endpoint per environment table). Verify file format against SCH-06 (field count, trailer checksum).
**Steps:** Create ZeroPayBatchTimingTest.java in the regression test module.; Seed 5 payment transactions in the test DB.; Set system clock offset to 01:55:00 KST (or use test clock injection).; Trigger ZP0011 batch job; record wall-clock time at SFTP put completion.; Assert SFTP put completed within 5 minutes (simulating the 02:00 deadline with 5-minute budget).; Parse generated ZP0011 file from stub SFTP; assert record count = 5, trailer checksum matches header count.
**Deliverable:** ZeroPayBatchTimingTest.java integration test (green against stub SFTP)
**Acceptance / logic checks:**
- SFTP put for ZP0011 completes within the 5-minute test budget after trigger.
- Generated file contains exactly 5 data records matching the seeded transactions.
- File trailer record count field equals 5.
- File checksum/trailer in ZP0011 matches the format expected by SCH-06 (at minimum: trailer row count consistent with data rows).

### 15.10-T28 — Implement regression reconciliation test: batch discrepancy auto-flag (ZP-013 / §9.3)  _(50 min)_
**Context:** QA-12 §9.3 ZP-013: if ZP0062 settlement result totals differ from GME totals, the discrepancy must be auto-flagged, an ops alert fired, and the batch routed to exception management. Test uses a synthetic ZP0062 file with one record having a different KRW amount than GME DB shows.
**Steps:** Create SettlementReconciliationTest.java in the regression test module.; Seed 3 settlement transactions in DB (totaling 40500 KRW).; Construct a synthetic ZP0062 inbound file where one record shows 500 KRW less than GME value (total in ZP0062 = 40000 KRW).; Inject the synthetic file into the stub SFTP as if received from ZeroPay.; Trigger the reconciliation processor; assert discrepancy record is created with amount_diff = 500 KRW.; Assert an ops alert event is logged with severity CRITICAL and alert_type=RECONCILIATION_DISCREPANCY.
**Deliverable:** SettlementReconciliationTest.java integration test (green)
**Acceptance / logic checks:**
- Reconciliation processor creates a discrepancy record with expected_amount=40500 and received_amount=40000 (diff=500 KRW).
- Ops alert event contains alert_type=RECONCILIATION_DISCREPANCY and severity=CRITICAL.
- Discrepancy record status is set to OPEN and routed to exception management queue.
- Perfect-match case (ZP0062 totals = GME totals) produces no alert and batch status = RECONCILED.

### 15.10-T29 — Create regression pack execution report template (HTML/text)  _(40 min)_
**Context:** QA-12 §12.3: CI must produce a sprint test summary on every PR and nightly run including pass count, fail count, and coverage delta. The DefectExitReport JSON (15.10-T13) must be rendered into a human-readable HTML summary for PR comments and nightly email delivery. Template must show: run date, pack name, gate results table, open defect count by severity, coverage percentage, and overall pass/fail status.
**Steps:** Create template file qa/reports/defect-exit-report.html.jinja2 (or equivalent template engine).; Template must render: report header (reportId, generatedAt, packName), gate results as an HTML table (level, passed status, openP1, openP2, reason), open defect summary table (id, severity, title, ageDays), coverage pct with green/red color coding (green >= 85, red < 85), and overall PASSED/FAILED banner.; Write a unit test ReportTemplateTest that renders a sample DefectExitReport and asserts: PASSED banner present, gate table has correct row count, red color applied for coverage 84.9%.; Ensure report renders valid HTML (no unclosed tags) as asserted by a simple regex or HTML parser check.
**Deliverable:** defect-exit-report.html.jinja2 template + ReportTemplateTest.java (green)
**Acceptance / logic checks:**
- Sample report with overallPassed=true renders a green PASSED banner and no FAILED text.
- Sample report with coveragePct=84.9 renders the coverage cell with a red indicator.
- Gate results table contains one row per gate level from the input data.
- HTML output passes a well-formedness check (no unclosed tags found by parser).
**Depends on:** 15.10-T13

### 15.10-T30 — Create defect management runbook documentation section  _(30 min)_
**Context:** QA-12 §11 requires a documented defect management process covering severity definitions, triage steps, and exit gates. The runbook section must be a single Markdown file usable by QA lead and developers without reading the full spec. It must include the severity table, triage cadence, P1 same-sprint fix rule, P2 pre-UAT fix rule, and exit-gate table from QA-12 §11.3.
**Steps:** Create file docs/qa/defect-management-runbook.md.; Section 1: Severity Definitions - reproduce QA-12 §11.1 table (P1-P4 with definition and GMEPay+ examples: pool identity violated, prefunding double-spend, ZP file not generated for P1; Admin cannot save rule for P2).; Section 2: Triage Process - daily P1/P2 meeting, P1 same-sprint fix, P2 fix before UAT entry, P3/P4 scheduled.; Section 3: Exit Gates - reproduce QA-12 §11.3 table for all 7 levels with exact pass criteria (e.g., SYSTEM_E2E: 0 P1 open, P2 <= 2 with documented mitigations).; Section 4: How to raise a defect - link to issue tracker, required fields (severity, component, steps to reproduce).
**Deliverable:** docs/qa/defect-management-runbook.md
**Acceptance / logic checks:**
- Severity table has exactly 4 rows matching QA-12 §11.1 (P1 through P4) with definitions and GMEPay+ examples.
- Exit gates table has 7 rows matching QA-12 §11.3 level names and gate criteria verbatim.
- P1 same-sprint fix rule and P2 pre-UAT-entry fix rule are stated explicitly.
- Runbook contains no reference to external spec documents (e.g., see QA-12) -- all relevant content is reproduced inline.

### 15.10-T31 — Create regression pack operator guide documentation  _(30 min)_
**Context:** The nightly regression pack and PR regression pack (15.10-T05, 15.10-T06) need an operator guide so QA engineers and developers can understand what runs, when, how to interpret results, and how to investigate failures. Guide must cover: pack contents, trigger conditions, how to read the defect-exit-report.json, how to update open-defects.json, and how to override the exit gate in an emergency.
**Steps:** Create file docs/qa/regression-pack-guide.md.; Section 1: Pack Contents - list PR pack (unit, integration, contract) and nightly pack (all + e2e) suites with timeout budgets.; Section 2: Trigger Conditions - PR against main/release triggers PR pack; cron 0 20 * * * UTC triggers nightly.; Section 3: Reading the Report - explain defect-exit-report.json fields (gateResults, openDefects, coveragePct, overallPassed); include an example snippet.; Section 4: Maintaining open-defects.json - how to add/close a defect entry; field descriptions; warn that P1 entries block all PRs.; Section 5: Emergency gate override - procedure (requires QA lead + tech lead sign-off documented in PR comment; sets bypass label).
**Deliverable:** docs/qa/regression-pack-guide.md
**Acceptance / logic checks:**
- Guide names all 3 PR-pack suite types and all 7 nightly-pack suite types with timeout budgets matching ci/regression-packs.yaml.
- Cron schedule is stated as 0 20 * * * UTC (= 05:00 KST) with explanation.
- open-defects.json field descriptions match the DefectRecord schema from 15.10-T07.
- Emergency override procedure names required approvers (QA lead + tech lead) and states the bypass label name.
**Depends on:** 15.10-T30
