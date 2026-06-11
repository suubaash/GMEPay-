# rate-fx  (backend)

**Scope:** Rate & FX engine: 3-ccy USD-pivot calc, quote/TTL/lock, partner-B quote

**Owned WBS work-packages:** 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 8.3  ·  **Tickets:** 118  ·  **Est:** 65.7h

## Service contract (MSA: own DB, API-only communication)

- **Datastore (owned by this service):** Redis (quote TTL); no owned RDBMS
- **APIs / events I EXPOSE:** POST /v1/rates (quote); event rate.quoted
- **APIs / events I CONSUME:** config-registry (rule margins + treasury rates, sync) — or values passed in request
- **Integration rule:** never read another service's database or import its private entities — call its API or consume its event; stub consumed services with WireMock in tests.

> Self-contained backlog for this service. Build it as its own repo/module with its own DB + Flyway migrations, against the `shared-libs` contracts (lib-money / lib-errors / lib-events / lib-api-contracts only). Each ticket has a deliverable + acceptance checks.


## WBS 4.1 — Treasury rate sourcing model
### 4.1-T01 — Define RateSource enum with four source modes  _(20 min)_
**Context:** The rate engine resolves each leg's cost rate from one of four source modes: IDENTITY (settle ccy = USD, hardcoded 1.0), LIVE (fetch from treasury table at quote time), MANUAL (operator override stored per rule), PARTNER (per-transaction quote from partner B API). These modes appear on rule config fields rate_coll_source and rate_pay_source. Both DB columns use CHECK IN ('IDENTITY','LIVE','MANUAL','PARTNER').
**Steps:** Create enum RateSource { IDENTITY, LIVE, MANUAL, PARTNER } in package com.gmepayplus.rateengine.model; Add Javadoc on each constant describing exactly when it applies (IDENTITY: leg ccy is USD; LIVE: default for non-identity cross-border; MANUAL: operator override; PARTNER: scheme-level Partner B quote); Ensure the enum is serialisable to/from its uppercase string name (Jackson @JsonValue / @JsonCreator or equivalent); Add a helper isIdentity() convenience method returning true when value == IDENTITY
**Deliverable:** RateSource.java enum in com.gmepayplus.rateengine.model
**Acceptance / logic checks:**
- RateSource.values() returns exactly four constants: IDENTITY, LIVE, MANUAL, PARTNER
- RateSource.valueOf('MANUAL') == RateSource.MANUAL and RateSource.IDENTITY.isIdentity() == true
- RateSource.LIVE.isIdentity() == false
- JSON serialisation round-trip: MANUAL serialises to string 'MANUAL' and deserialises back to RateSource.MANUAL

### 4.1-T02 — Define RateResolutionContext value object  _(25 min)_
**Context:** The resolver needs to know: which leg (COLL or PAY), the settlement currency for that leg (settle_a_ccy for COLL, settle_b_ccy for PAY), the configured source mode from the Rule, and any MANUAL override value stored on the rule (treasury_override_coll / treasury_override_pay). This context is built by the caller before dispatching to a resolver. All monetary/rate values use BigDecimal (never double/float). Currency codes are ISO 4217 strings.
**Steps:** Create immutable value object RateResolutionContext with fields: Leg leg (enum COLL/PAY), String settleCcy (ISO 4217), RateSource configuredSource, BigDecimal manualOverrideValue (nullable), String schemeId (for PARTNER lookup); Add a static factory method forCollLeg(String settleCcy, RateSource src, BigDecimal override) and forPayLeg(...) mirroring the two rate slots; Add validation in constructor: settleCcy not blank, configuredSource not null; if configuredSource == MANUAL then manualOverrideValue must be non-null and > 0
**Deliverable:** RateResolutionContext.java value object in com.gmepayplus.rateengine.model
**Acceptance / logic checks:**
- forCollLeg('USD', IDENTITY, null) builds successfully with settleCcy='USD' and configuredSource=IDENTITY
- forPayLeg('KRW', MANUAL, null) throws IllegalArgumentException because MANUAL requires a non-null, positive override value
- forPayLeg('KRW', MANUAL, BigDecimal.ZERO) throws IllegalArgumentException (zero not allowed)
- forCollLeg('USD', LIVE, null) builds successfully; manualOverrideValue is null
**Depends on:** 4.1-T01

### 4.1-T03 — Define CostRateResult value object  _(25 min)_
**Context:** Each resolver returns a result carrying: the resolved BigDecimal rate value, the effective RateSource that was used (same as configured except PARTNER may note the actual source), and an optional quoteReference string for PARTNER-source audit. Rate precision is 8 decimal places per RATE-04 §10.2. All values are immutable.
**Steps:** Create immutable CostRateResult with fields: BigDecimal rate (8 dp), RateSource resolvedSource, String quoteReference (nullable); Add static factory methods: ofIdentity() returning rate=1.0 source=IDENTITY; ofLive(BigDecimal rate); ofManual(BigDecimal rate); ofPartner(BigDecimal rate, String quoteRef); Enforce in constructor: rate > 0, rate scale <= 8; throw RateResolutionException('RATE_UNAVAILABLE') if rate is null or <= 0
**Deliverable:** CostRateResult.java value object in com.gmepayplus.rateengine.model
**Acceptance / logic checks:**
- CostRateResult.ofIdentity().rate.compareTo(BigDecimal.ONE) == 0 and resolvedSource == IDENTITY
- CostRateResult.ofManual(new BigDecimal('1380.00')).rate scale is <= 8
- CostRateResult.ofLive(BigDecimal.ZERO) throws RateResolutionException with code RATE_UNAVAILABLE
- CostRateResult.ofPartner(new BigDecimal('1380.5'), 'Q-REF-001').quoteReference == 'Q-REF-001'
**Depends on:** 4.1-T01

### 4.1-T04 — Define CostRateResolver interface and RateResolutionException  _(25 min)_
**Context:** The rate sourcing service dispatches to per-source resolver implementations. The interface accepts a RateResolutionContext and returns CostRateResult. Errors are typed: RATE_UNAVAILABLE (LIVE/MANUAL source, rate not found or <= 0), PARTNER_B_QUOTE_UNAVAILABLE (PARTNER source, API unreachable), PARTNER_B_QUOTE_DEVIATION (PARTNER source, commit-time deviation > tolerance). The interface is used by the RateSourceResolver orchestrator (4.1-T09).
**Steps:** Create interface CostRateResolver with single method: CostRateResult resolve(RateResolutionContext ctx) throws RateResolutionException; Create RateResolutionException(String errorCode, String message) extending RuntimeException; errorCode must be one of RATE_UNAVAILABLE, PARTNER_B_QUOTE_UNAVAILABLE, PARTNER_B_QUOTE_DEVIATION; Add convenience static factories on the exception: RateResolutionException.rateUnavailable(String detail), partnerQuoteUnavailable(), partnerQuoteDeviation(BigDecimal quoteTime, BigDecimal commitTime, BigDecimal tolerance); Add annotation @FunctionalInterface to the resolver interface
**Deliverable:** CostRateResolver.java interface and RateResolutionException.java in com.gmepayplus.rateengine.resolver
**Acceptance / logic checks:**
- Interface has exactly one abstract method resolve(RateResolutionContext) matching the signature
- RateResolutionException.rateUnavailable('no row').getErrorCode() == 'RATE_UNAVAILABLE'
- RateResolutionException.partnerQuoteDeviation(bd('1380'), bd('1395'), bd('0.01')).getErrorCode() == 'PARTNER_B_QUOTE_DEVIATION'
- Attempting to create RateResolutionException with errorCode 'BOGUS_CODE' throws IllegalArgumentException (validate in constructor)
**Depends on:** 4.1-T02, 4.1-T03

### 4.1-T05 — Implement IdentityRateResolver — USD leg short-circuit to 1.0  _(30 min)_
**Context:** RATE-04 §3.2: when settle_a_ccy = 'USD' the collection leg resolves as IDENTITY with cost_rate_coll = 1.0 (no treasury lookup); same for settle_b_ccy = 'USD' on the pay leg. The IDENTITY resolver must: assert the configured source is IDENTITY, assert settleCcy is 'USD', and return CostRateResult.ofIdentity(). It must never perform a DB or HTTP call. Any non-USD call with source=IDENTITY is a programming error and should throw an IllegalStateException.
**Steps:** Create IdentityRateResolver implements CostRateResolver; In resolve(): if ctx.configuredSource != IDENTITY throw IllegalStateException; if !ctx.settleCcy.equals('USD') throw IllegalStateException with message 'IDENTITY source requires USD currency, got: {ccy}'; Return CostRateResult.ofIdentity(); Annotate as @Component('identityRateResolver') for Spring wiring
**Deliverable:** IdentityRateResolver.java in com.gmepayplus.rateengine.resolver.impl
**Acceptance / logic checks:**
- resolve(forCollLeg('USD', IDENTITY, null)) returns CostRateResult with rate == 1.0 and resolvedSource == IDENTITY
- resolve(forPayLeg('USD', IDENTITY, null)) returns rate == 1.0
- resolve(forCollLeg('KRW', IDENTITY, null)) throws IllegalStateException (KRW is not USD)
- resolve(forCollLeg('USD', LIVE, null)) throws IllegalStateException (wrong source)
- No DB or HTTP calls are made (verify with mock injection — no repositories wired)
**Depends on:** 4.1-T04

### 4.1-T06 — Implement LiveRateResolver — fetch effective rate from treasury table  _(40 min)_
**Context:** RATE-04 §3.2: LIVE source fetches treasury.usd_{ccy} at quote-issuance time. The treasury_rate table stores effective_date, currency_code, rate (NUMERIC), and source='LIVE'|'MANUAL'. For LIVE resolution use the row where currency_code = settleCcy AND source = 'LIVE' with the most recent effective_date <= now(). If no such row exists, throw RateResolutionException.rateUnavailable(). Per RATE-04 Assumption A3: Phase 1 uses MANUAL-entered rates; LIVE source is modelled now. Rate must be > 0 else RATE_UNAVAILABLE.
**Steps:** Create LiveRateResolver implements CostRateResolver, injecting TreasuryRateRepository; In resolve(): assert ctx.configuredSource == LIVE; query repository for most-recent row: currency_code = ctx.settleCcy, source = 'LIVE', effective_date <= LocalDate.now(), order by effective_date DESC, limit 1; If no row found, throw RateResolutionException.rateUnavailable('No LIVE rate for ' + settleCcy); If row.rate <= 0, throw RateResolutionException.rateUnavailable('Non-positive LIVE rate for ' + settleCcy); Return CostRateResult.ofLive(row.rate)
**Deliverable:** LiveRateResolver.java in com.gmepayplus.rateengine.resolver.impl
**Acceptance / logic checks:**
- Given treasury row {ccy='KRW', source='LIVE', rate=1380.00, effective_date=today}, resolve returns CostRateResult.ofLive(1380.00)
- Given no matching row, resolve throws RateResolutionException with errorCode RATE_UNAVAILABLE
- Given row with future effective_date (tomorrow), resolve throws RATE_UNAVAILABLE (date filter excludes future rows)
- Given row with rate=0.00, resolve throws RATE_UNAVAILABLE
- resolve with source=MANUAL throws IllegalStateException (wrong resolver called)
**Depends on:** 4.1-T04

### 4.1-T07 — Implement ManualRateResolver — return operator override value  _(25 min)_
**Context:** RATE-04 §3.2: MANUAL source uses the operator-entered rate stored on the Rule record (treasury_override_coll for COLL leg, treasury_override_pay for PAY leg). This value is passed into RateResolutionContext.manualOverrideValue at the time the resolver is invoked. The resolver does NOT query the treasury table. RATE-04 §11.3: MANUAL rate must be > 0; 0 is not allowed. If manualOverrideValue is null or <= 0, throw RATE_UNAVAILABLE. MANUAL overrides LIVE — meaning the Rule config sets source=MANUAL instead of LIVE; no fallback occurs.
**Steps:** Create ManualRateResolver implements CostRateResolver; In resolve(): assert ctx.configuredSource == MANUAL; If ctx.manualOverrideValue is null, throw RateResolutionException.rateUnavailable('MANUAL override not set for rule'); If ctx.manualOverrideValue.compareTo(BigDecimal.ZERO) <= 0, throw RateResolutionException.rateUnavailable('MANUAL override must be positive'); Return CostRateResult.ofManual(ctx.manualOverrideValue); Annotate as @Component('manualRateResolver')
**Deliverable:** ManualRateResolver.java in com.gmepayplus.rateengine.resolver.impl
**Acceptance / logic checks:**
- resolve with manualOverrideValue=1380.00 returns CostRateResult with rate=1380.00 and resolvedSource=MANUAL
- resolve with manualOverrideValue=null throws RATE_UNAVAILABLE
- resolve with manualOverrideValue=0 throws RATE_UNAVAILABLE
- resolve with manualOverrideValue=-5 throws RATE_UNAVAILABLE
- No repository or HTTP call is made during resolution (pure in-memory)
**Depends on:** 4.1-T04

### 4.1-T08 — Implement PartnerBQuoteResolver — fetch per-transaction quote from Partner B API  _(35 min)_
**Context:** RATE-04 §7.3: PARTNER source calls partner B's quote API at both /rates time and POST /payments commit time. The resolver calls PartnerBQuoteClient.fetchQuote(schemeId, transactionContext) which returns a PartnerBQuote{rate: BigDecimal, quoteReference: String, validUntil: Instant}. If the client throws or returns null, throw RateResolutionException.partnerQuoteUnavailable() (no fallback to treasury). The commit-time deviation check (|commit - rates| / rates > tolerance => PARTNER_B_QUOTE_DEVIATION) is the responsibility of the RateSourceResolver orchestrator (4.1-T09), not this resolver. This resolver only fetches and validates the raw quote.
**Steps:** Create PartnerBQuoteResolver implements CostRateResolver, injecting PartnerBQuoteClient; In resolve(): assert ctx.configuredSource == PARTNER; Call partnerBQuoteClient.fetchQuote(ctx.schemeId) wrapped in try-catch; on any exception throw RateResolutionException.partnerQuoteUnavailable(); If returned quote is null or quote.rate <= 0, throw RateResolutionException.partnerQuoteUnavailable(); Return CostRateResult.ofPartner(quote.rate, quote.quoteReference); Annotate as @Component('partnerBQuoteResolver')
**Deliverable:** PartnerBQuoteResolver.java in com.gmepayplus.rateengine.resolver.impl
**Acceptance / logic checks:**
- When PartnerBQuoteClient returns rate=1395.00 and ref='Q-001', resolve returns CostRateResult with rate=1395.00, resolvedSource=PARTNER, quoteReference='Q-001'
- When PartnerBQuoteClient throws IOException, resolve throws RateResolutionException with code PARTNER_B_QUOTE_UNAVAILABLE
- When PartnerBQuoteClient returns null, resolve throws PARTNER_B_QUOTE_UNAVAILABLE
- resolve with configuredSource=LIVE throws IllegalStateException
- No treasury table query occurs (verify no TreasuryRateRepository injection)
**Depends on:** 4.1-T04

### 4.1-T09 — Implement RateSourceResolver — orchestrate per-source dispatch for both legs  _(45 min)_
**Context:** The RateSourceResolver is the main service wiring the four resolvers to a Rule's configured source modes. It builds a RateResolutionContext for each leg from the Rule (settle_a_ccy, settle_b_ccy, rate_coll_source, rate_pay_source, treasury_override_coll, treasury_override_pay, schemeId) and dispatches to the matching resolver. It returns a RatePair{CostRateResult collResult, CostRateResult payResult}. It also performs the PARTNER commit-time deviation check for the pay leg if pay source = PARTNER: deviation = |commitRate - quotedRate| / quotedRate; if > tolerance (default 1.0%, per-partner configurable) throw PARTNER_B_QUOTE_DEVIATION. Both legs are resolved independently.
**Steps:** Create RateSourceResolver @Service injecting Map<RateSource, CostRateResolver> (Spring will auto-populate by qualifier); Add method RatePair resolveRates(Rule rule, String transactionContext) that builds contexts and calls the correct resolver for each leg; Add method CostRateResult resolveForCommit(Rule rule, CostRateResult quotedPayResult) for commit-time PARTNER deviation check: re-resolves pay leg, computes deviation = |new - quoted| / quoted, if > tolerance throw PARTNER_B_QUOTE_DEVIATION; Expose tolerance as @Value('${rate.partnerB.deviation.tolerance:0.01}') BigDecimal defaultTolerance; Wire in the four resolver beans by name: identityRateResolver, liveRateResolver, manualRateResolver, partnerBQuoteResolver
**Deliverable:** RateSourceResolver.java @Service in com.gmepayplus.rateengine.resolver
**Acceptance / logic checks:**
- Rule with settle_a_ccy='USD', rate_coll_source=IDENTITY dispatches to IdentityRateResolver and collResult.rate == 1.0
- Rule with settle_b_ccy='KRW', rate_pay_source=LIVE dispatches to LiveRateResolver
- Rule with rate_pay_source=MANUAL and treasury_override_pay=1380 dispatches to ManualRateResolver and returns 1380
- resolveForCommit with quotedRate=1380 and commitRate=1394.8 (deviation=1.07% > 1%) throws PARTNER_B_QUOTE_DEVIATION
- resolveForCommit with quotedRate=1380 and commitRate=1390 (deviation=0.72% < 1%) succeeds and returns new rate
**Depends on:** 4.1-T05, 4.1-T06, 4.1-T07, 4.1-T08

### 4.1-T10 — Implement TreasuryRateRepository — effective-dated rate lookup  _(45 min)_
**Context:** The LiveRateResolver (4.1-T06) requires a TreasuryRateRepository that can find the most recent treasury_rate row for a given currency and source with effective_date <= today. The treasury_rates table (DAT-03) has columns: id, currency_code VARCHAR(3), rate NUMERIC(20,8), source VARCHAR(10) CHECK IN ('LIVE','MANUAL'), effective_date DATE, created_at TIMESTAMP. Index on (currency_code, source, effective_date DESC) must exist for performance. Phase 1 only uses source='MANUAL' rows; LIVE rows are stored for Phase 2.
**Steps:** Create TreasuryRateRepository extending JpaRepository<TreasuryRate, Long> (or use Spring Data JDBC); Add query method: Optional<TreasuryRate> findLatestEffective(String currencyCode, String source, LocalDate asOf) using @Query with ORDER BY effective_date DESC LIMIT 1 WHERE currency_code=:ccy AND source=:source AND effective_date <= :asOf; Create TreasuryRate @Entity with fields matching the DB schema above (BigDecimal rate with @Column(precision=20,scale=8)); Add a DB migration (Flyway/Liquibase) creating the treasury_rates table with the composite index on (currency_code, source, effective_date DESC) if not already present in prior migrations
**Deliverable:** TreasuryRateRepository.java and TreasuryRate.java entity in com.gmepayplus.treasury; DB migration file
**Acceptance / logic checks:**
- findLatestEffective('KRW','LIVE', today) returns the row with the highest effective_date <= today among all LIVE KRW rows
- findLatestEffective('KRW','LIVE', today) when only future-dated rows exist returns Optional.empty()
- findLatestEffective('USD','LIVE', today) returns Optional.empty() (USD has no LIVE row; identity is handled before reaching this)
- Composite index (currency_code, source, effective_date DESC) exists per EXPLAIN plan (verify in test with H2 or testcontainers)
**Depends on:** 4.1-T04

### 4.1-T11 — Unit tests — IdentityRateResolver and ManualRateResolver  _(30 min)_
**Context:** Verify both pure in-memory resolvers with exact input/output vectors per RATE-04 §3.2. IDENTITY must always return exactly 1.0 for USD and throw for any other currency. MANUAL must return the exact override value stored on the rule and reject zero/null. Tests use JUnit 5 + AssertJ; no Spring context needed (plain unit tests).
**Steps:** Create IdentityRateResolverTest: test USD/IDENTITY returns 1.0; test KRW/IDENTITY throws IllegalStateException; test USD/LIVE source throws IllegalStateException; Create ManualRateResolverTest: test KRW/MANUAL/1380.00 returns 1380.00 with resolvedSource=MANUAL; test null override throws RATE_UNAVAILABLE; test zero override throws RATE_UNAVAILABLE; test negative override throws RATE_UNAVAILABLE; Assert CostRateResult.rate.compareTo(BigDecimal.ONE) == 0 (not equals(), which checks scale) for identity case; Verify no mocks are needed (resolvers are stateless)
**Deliverable:** IdentityRateResolverTest.java and ManualRateResolverTest.java in src/test/.../resolver/impl
**Acceptance / logic checks:**
- All 7 test cases pass with zero failures
- IdentityRateResolver test for USD returns rate==1.0 and resolvedSource==IDENTITY
- ManualRateResolver test for KRW with override 1380.00 returns rate==1380.00 and resolvedSource==MANUAL
- All three error cases (null, zero, negative) each produce RateResolutionException with errorCode RATE_UNAVAILABLE
- Tests run in < 100 ms total (no I/O)
**Depends on:** 4.1-T05, 4.1-T07

### 4.1-T12 — Unit tests — LiveRateResolver with mocked TreasuryRateRepository  _(35 min)_
**Context:** Verify the LIVE resolver fetches the correct treasury row. Test vectors: KRW LIVE 1380.00 (today); KRW LIVE with future date only (no row, RATE_UNAVAILABLE); no row at all (RATE_UNAVAILABLE); row with rate=0 (RATE_UNAVAILABLE). Mocks via Mockito. Per RATE-04 §3.2 the rate is fetched at quote-issuance time from treasury.usd_{ccy}.
**Steps:** Create LiveRateResolverTest using @ExtendWith(MockitoExtension.class); Mock TreasuryRateRepository; stub findLatestEffective('KRW','LIVE',today) to return Optional of row with rate=1380.00000000; Test happy path: returns CostRateResult with rate=1380.00000000, resolvedSource=LIVE; Test no row: stub returns Optional.empty(); assert throws RATE_UNAVAILABLE; Test rate=0 row: assert throws RATE_UNAVAILABLE; Test wrong source (ctx.configuredSource=MANUAL): assert throws IllegalStateException
**Deliverable:** LiveRateResolverTest.java in src/test/.../resolver/impl
**Acceptance / logic checks:**
- Happy path: rate==1380.00000000 and resolvedSource==LIVE
- Empty repository: RateResolutionException errorCode == RATE_UNAVAILABLE
- Zero-rate row: RateResolutionException errorCode == RATE_UNAVAILABLE
- Wrong-source call: IllegalStateException thrown (not RateResolutionException)
- Mockito verifies findLatestEffective called exactly once in each test
**Depends on:** 4.1-T06, 4.1-T10

### 4.1-T13 — Unit tests — PartnerBQuoteResolver with mocked PartnerBQuoteClient  _(30 min)_
**Context:** Verify the PARTNER resolver delegates to the client and handles errors. Test vectors: client returns {rate=1395.00, ref='Q-001'} -> resolves correctly; client throws IOException -> PARTNER_B_QUOTE_UNAVAILABLE; client returns null -> PARTNER_B_QUOTE_UNAVAILABLE; client returns rate<=0 -> PARTNER_B_QUOTE_UNAVAILABLE. Deviation check is NOT in this resolver (tested in 4.1-T14).
**Steps:** Create PartnerBQuoteResolverTest using @ExtendWith(MockitoExtension.class); Mock PartnerBQuoteClient; Test happy path: stub fetchQuote to return {rate=1395.00, ref='Q-001'}; assert rate==1395.00 and quoteReference=='Q-001'; Test IOException: stub throws IOException; assert PARTNER_B_QUOTE_UNAVAILABLE; Test null return: stub returns null; assert PARTNER_B_QUOTE_UNAVAILABLE; Test zero-rate quote: stub returns {rate=0.00}; assert PARTNER_B_QUOTE_UNAVAILABLE
**Deliverable:** PartnerBQuoteResolverTest.java in src/test/.../resolver/impl
**Acceptance / logic checks:**
- Happy path: resolvedSource==PARTNER, rate==1395.00, quoteReference=='Q-001'
- IOException produces RateResolutionException with code PARTNER_B_QUOTE_UNAVAILABLE
- Null return produces PARTNER_B_QUOTE_UNAVAILABLE
- Zero-rate produces PARTNER_B_QUOTE_UNAVAILABLE
- Mockito verifies fetchQuote called exactly once per test
**Depends on:** 4.1-T08

### 4.1-T14 — Unit tests — RateSourceResolver orchestration and PARTNER deviation check  _(45 min)_
**Context:** Verify the orchestrator dispatches to the correct sub-resolver per leg and that the commit-time PARTNER deviation check fires correctly. Deviation formula (RATE-04 §7.3): deviation = |commitRate - quotedRate| / quotedRate; if deviation > tolerance (default 0.01) throw PARTNER_B_QUOTE_DEVIATION. Test cases: IDENTITY coll + LIVE pay; MANUAL override pay; PARTNER pay within tolerance (0.72% < 1%); PARTNER pay over tolerance (1.07% > 1%).
**Steps:** Create RateSourceResolverTest using @ExtendWith(MockitoExtension.class); inject mock resolvers; Build a Rule stub with settle_a_ccy='USD', rate_coll_source=IDENTITY, settle_b_ccy='KRW', rate_pay_source=LIVE; Test dispatch: verifyCollResult.rate==1.0 IDENTITY, payResult from mock LiveResolver with 1380; Build Rule with rate_pay_source=MANUAL, treasury_override_pay=1380; test payResult==1380 resolvedSource==MANUAL; Test deviation check: quotedRate=1380, commitRate=1394.8, tolerance=0.01 => |14.8|/1380=0.01072 > 0.01 => PARTNER_B_QUOTE_DEVIATION; Test no deviation: quotedRate=1380, commitRate=1390, tolerance=0.01 => 10/1380=0.00724 < 0.01 => success
**Deliverable:** RateSourceResolverTest.java in src/test/.../resolver
**Acceptance / logic checks:**
- IDENTITY coll + LIVE pay dispatch verified with correct mocks called
- MANUAL pay returns treasury_override_pay value with resolvedSource=MANUAL
- commitRate=1394.8 on quotedRate=1380 with tolerance=1% throws PARTNER_B_QUOTE_DEVIATION
- commitRate=1390 on quotedRate=1380 with tolerance=1% does not throw
- Both legs resolve independently (mock for each leg called exactly once)
**Depends on:** 4.1-T09, 4.1-T11, 4.1-T12, 4.1-T13

### 4.1-T15 — Integration test — dual-IDENTITY legs (HUB direction, settle_a=USD, settle_b=USD)  _(40 min)_
**Context:** RATE-04 §7.1: both legs may simultaneously be IDENTITY when both settle_a_ccy and settle_b_ccy are USD (HUB direction). In this case cost_rate_coll = 1.0 and cost_rate_pay = 1.0. The rule still applies margins m_a and m_b. Test with: target_payout=100.00 USD, cost_rate_coll=1.0 (IDENTITY), cost_rate_pay=1.0 (IDENTITY), m_a=0.01, m_b=0.01, service_charge=0.50 USD. Expected (from RATE-04 §7.1): payout_usd_cost=100.00, collection_usd=100/(0.98)=102.0408, send_amount=102.0408, collection_amount=102.5408.
**Steps:** Create a Spring Boot integration test class RateDualIdentityIntegrationTest using @SpringBootTest with an H2 in-memory DB; Configure a Rule with settle_a_ccy='USD', rate_coll_source=IDENTITY, settle_b_ccy='USD', rate_pay_source=IDENTITY, m_a=0.01, m_b=0.01, service_charge=0.50; Call RateSourceResolver.resolveRates() and assert both legs return rate=1.0; Feed the resolved rates into the rate engine and assert the 5 computed fields match the RATE-04 §7.1 example within tolerance
**Deliverable:** RateDualIdentityIntegrationTest.java in src/test/.../integration
**Acceptance / logic checks:**
- collResult.rate == 1.0 exactly (BigDecimal compareTo == 0)
- payResult.rate == 1.0 exactly
- collection_usd within 0.01 of 102.0408
- send_amount within 0.01 of 102.0408 (cost_rate_coll=1.0 so send_amount=collection_usd)
- collection_amount within 0.01 of 102.5408
**Depends on:** 4.1-T09, 4.1-T10

### 4.1-T16 — Integration test — IDENTITY coll leg + LIVE pay leg (SendMN cross-border vector)  _(40 min)_
**Context:** RATE-04 §4.3 worked example: settle_a_ccy=USD (IDENTITY, cost_rate_coll=1.0), settle_b_ccy=KRW (LIVE, cost_rate_pay=1380.00). target_payout=50000 KRW, m_a=0.01, m_b=0.01, service_charge=0.36 USD. Expected outputs: payout_usd_cost=36.2319 USD, collection_usd=36.9714 USD, send_amount=36.9714 USD, collection_amount=37.3314 USD, pool identity holds within 0.01 USD. Pre-load treasury_rates row: currency_code='KRW', source='LIVE', rate=1380.00000000, effective_date=today.
**Steps:** Insert treasury_rate row {ccy='KRW',source='LIVE',rate=1380.00000000,effective_date=today} into H2 test DB; Build Rule with settle_a_ccy='USD'/IDENTITY and settle_b_ccy='KRW'/LIVE; Call RateSourceResolver.resolveRates(); assert collResult.rate==1.0 (IDENTITY) and payResult.rate==1380.00 (LIVE); Feed into rate engine; assert payout_usd_cost within 0.01 of 36.2319, collection_usd within 0.01 of 36.9714, collection_amount within 0.01 of 37.3314; Assert pool identity: |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| < 0.01
**Deliverable:** RateLiveCrossIntegrationTest.java in src/test/.../integration
**Acceptance / logic checks:**
- collResult.rate == 1.0 (IDENTITY source, no DB hit needed for coll leg)
- payResult.rate == 1380.00 (LIVE, from treasury_rates table)
- payout_usd_cost within 0.01 of 36.2319
- collection_usd within 0.01 of 36.9714
- Pool identity assertion passes (deviation < 0.01 USD)
**Depends on:** 4.1-T09, 4.1-T10

### 4.1-T17 — Integration test — MANUAL override on pay leg overrides LIVE source  _(35 min)_
**Context:** RATE-04 §3.2: MANUAL source means the operator has overridden LIVE for a contract-locked partner. The rule stores treasury_override_pay on the rule record. Even if a LIVE treasury row exists, the ManualRateResolver uses the override value, not the table. Test: treasury has KRW LIVE=1380.00; rule is configured source=MANUAL with treasury_override_pay=1350.00. Expected: payResult.rate=1350.00 (MANUAL wins), not 1380.00 (LIVE ignored).
**Steps:** Insert treasury_rate row KRW/LIVE/1380.00/today into H2 test DB; Build Rule with rate_pay_source=MANUAL, treasury_override_pay=1350.00; Call RateSourceResolver.resolveRates(); capture payResult; Assert payResult.rate == 1350.00 and payResult.resolvedSource == MANUAL; Assert LiveRateResolver was NOT invoked for this leg (verify via Spring ApplicationContext or Mockito spy)
**Deliverable:** RateManualOverrideIntegrationTest.java in src/test/.../integration
**Acceptance / logic checks:**
- payResult.rate == 1350.00 (not 1380.00 from LIVE table)
- payResult.resolvedSource == MANUAL
- LiveRateResolver.resolve() is NOT called for the pay leg
- Changing treasury_override_pay to 1400.00 returns 1400.00 (override is the authoritative value)
- Setting treasury_override_pay to null and source=MANUAL throws RATE_UNAVAILABLE at resolve time
**Depends on:** 4.1-T09, 4.1-T10

### 4.1-T18 — Integration test — missing rate returns RATE_UNAVAILABLE (no fallback)  _(35 min)_
**Context:** RATE-04 §11.4: if cost_rate_coll or cost_rate_pay cannot be resolved for LIVE/MANUAL source, return RATE_UNAVAILABLE. No fallback, no stale rate use. Test cases: (a) LIVE source but no treasury row for currency -> RATE_UNAVAILABLE; (b) MANUAL source but treasury_override_pay is null on rule -> RATE_UNAVAILABLE. The rate engine must not proceed and no transaction record is created.
**Steps:** Test case A: configure Rule with rate_pay_source=LIVE for currency 'MNT'; ensure no MNT row in treasury_rates; call resolveRates(); assert RateResolutionException with code RATE_UNAVAILABLE; Test case B: configure Rule with rate_pay_source=MANUAL; set treasury_override_pay=null; call resolveRates(); assert RateResolutionException with code RATE_UNAVAILABLE; Test case C: LIVE row exists but rate=0.00 in treasury_rates; assert RATE_UNAVAILABLE; Verify no transaction record is committed to the payment table in any of the three cases
**Deliverable:** RateMissingRateIntegrationTest.java in src/test/.../integration
**Acceptance / logic checks:**
- No MNT LIVE row -> RATE_UNAVAILABLE thrown before any rate-engine step executes
- MANUAL source with null override -> RATE_UNAVAILABLE
- Zero-value LIVE row -> RATE_UNAVAILABLE
- No payment record persisted in DB for any of the three failure cases
- Error code string == 'RATE_UNAVAILABLE' in all three cases
**Depends on:** 4.1-T09, 4.1-T10


## WBS 4.2 — 5-step RECEIVE-mode calculation engine
### 4.2-T01 — Define RateEngineInput DTO with all required fields and types  _(30 min)_
**Context:** The 5-step RECEIVE-mode engine takes these inputs: target_payout (Decimal, payout ccy amount fixed by QR), cost_rate_pay (Decimal, treasury.usd_{settle_b_ccy}, units of Settle B per 1 USD), cost_rate_coll (Decimal, treasury.usd_{settle_a_ccy}, units of Settle A per 1 USD), m_a (Decimal fraction e.g. 0.01 = 1%), m_b (Decimal fraction), service_charge (Decimal flat fee in Settle A ccy; 0 if none), settle_a_ccy (String ISO-4217), settle_b_ccy (String ISO-4217), collect_ccy (String ISO-4217), payout_ccy (String ISO-4217), is_same_ccy_shortcircuit (boolean). All Decimal fields must use java.math.BigDecimal (Java) or Python decimal.Decimal — never float/double. Create in package com.gmepay.rateengine.dto (or equivalent module).
**Steps:** Create RateEngineInput class/record with all fields listed in context using BigDecimal (or language Decimal) for every monetary and rate field; Annotate or document each field with its source (LIVE/MANUAL/IDENTITY/PARTNER) and unit convention (e.g. cost_rate_pay: units of Settle B per 1 USD); Add a static factory / builder that sets is_same_ccy_shortcircuit = true when collect_ccy == settle_a_ccy == settle_b_ccy == payout_ccy; Write a unit test asserting the factory sets is_same_ccy_shortcircuit correctly for both the same-ccy case and the cross-border case
**Deliverable:** RateEngineInput DTO class/record (RateEngineInput.java or equivalent) with all 11 fields, correct Decimal types, and a same-ccy factory helper; passing unit test
**Acceptance / logic checks:**
- All monetary and rate fields are BigDecimal (or Decimal), not float/double — verified by code review or reflection test
- is_same_ccy_shortcircuit is true when collect_ccy=KRW, settle_a_ccy=KRW, settle_b_ccy=KRW, payout_ccy=KRW
- is_same_ccy_shortcircuit is false when settle_a_ccy=USD, payout_ccy=KRW
- service_charge defaults to BigDecimal.ZERO (not null) when not supplied
- Class is immutable (all fields final or record components)

### 4.2-T02 — Define RateEngineResult DTO with all output fields and types  _(25 min)_
**Context:** The engine returns these fields: payout_usd_cost (Decimal, Step 1; NULL for same-ccy), collection_usd (Decimal, Step 2; NULL for same-ccy), collection_margin_usd (Decimal, Step 3a; 0 for same-ccy), payout_margin_usd (Decimal, Step 3b; 0 for same-ccy), send_amount (Decimal, Step 4, in settle_a_ccy; NULL for same-ccy), send_amount_ccy (String), collection_amount (Decimal, Step 5, in settle_a_ccy), collection_amount_ccy (String), service_charge (Decimal, echoed from input), service_charge_ccy (String), cost_rate_coll (Decimal echoed), cost_rate_pay (Decimal echoed), offer_rate_coll (Decimal derived; NULL for same-ccy), cross_rate (Decimal derived; NULL for same-ccy). Precision rules: Steps 1-4 intermediates stored to DECIMAL(20,8); collection_amount rounded to currency-native scale (KRW = 0 dp, USD = 4 dp). Create in same package as 4.2-T01.
**Steps:** Create RateEngineResult class/record with all 14 fields using BigDecimal for all monetary and rate fields; Document precision expectation per field in Javadoc/docstring (DECIMAL(20,8) for intermediates; currency-scale for collection_amount); Mark payout_usd_cost, collection_usd, send_amount, offer_rate_coll, cross_rate as @Nullable (or Optional) since they are NULL for same-ccy; Write a unit test that constructs a sample result and asserts no float fields exist
**Deliverable:** RateEngineResult DTO class/record (RateEngineResult.java or equivalent) with all 14 fields, correct Decimal/nullable types, and passing structural test
**Acceptance / logic checks:**
- payout_usd_cost, collection_usd, send_amount, offer_rate_coll, cross_rate are nullable/Optional; collection_margin_usd and payout_margin_usd are non-null (default ZERO)
- No field uses float, double, Float, or Double type
- collection_amount_ccy and send_amount_ccy are separate String fields, not inferred
- Result is immutable (final fields or record)
- Javadoc/docstring states DECIMAL(20,8) precision for intermediate fields
**Depends on:** 4.2-T01

### 4.2-T03 — Implement Step 1 pure function: payout_usd_cost = target_payout / cost_rate_pay  _(20 min)_
**Context:** Step 1 of the 5-step RECEIVE-mode engine: payout_usd_cost = target_payout / cost_rate_pay. target_payout is fixed by the merchant QR (e.g. 50000 KRW). cost_rate_pay = treasury.usd_{settle_b_ccy} (e.g. 1380.00 for KRW). Division must use BigDecimal with MathContext of at least 10 significant figures (use DECIMAL128 or equivalent). The result is an intermediate; do NOT round here. Worked example: target_payout=50000, cost_rate_pay=1380.00 => payout_usd_cost = 36.23188405... USD. For IDENTITY leg (Settle B = USD), cost_rate_pay = 1.0 so payout_usd_cost = target_payout exactly.
**Steps:** Create a package-private pure static function stepOnePayoutUsdCost(BigDecimal targetPayout, BigDecimal costRatePay): BigDecimal; Use BigDecimal.divide with MathContext.DECIMAL128 (34 sig figs) to avoid ArithmeticException on repeating decimals; Return result unrounded (precision kept at DECIMAL128 scale); Write unit test with numeric example: targetPayout=50000, costRatePay=1380.00, expect result approximately 36.2319 (within 0.0001); Add edge-case test: costRatePay=1.0 (IDENTITY), expect result == targetPayout exactly
**Deliverable:** Pure static function stepOnePayoutUsdCost in RateEngineSteps class (or equivalent), with 2 passing unit tests
**Acceptance / logic checks:**
- stepOnePayoutUsdCost(50000, 1380.00) returns value in range [36.2318, 36.2320]
- stepOnePayoutUsdCost(100.00, 1.0) returns exactly 100.00 (IDENTITY leg)
- Function signature accepts BigDecimal, returns BigDecimal — no float
- Division uses MathContext.DECIMAL128 or equivalent, not plain divide() which would throw on repeating decimal
- No rounding applied inside this function
**Depends on:** 4.2-T01, 4.2-T02

### 4.2-T04 — Implement margin guard: reject when m_a + m_b < 0.02 for cross-border rules  _(25 min)_
**Context:** Hard business rule: for cross-border rules (is_same_ccy_shortcircuit = false), m_a + m_b must be >= 0.02 (2%). If m_a + m_b < 0.02 the engine must throw a checked exception RateEngineException with code INVALID_MARGIN_COMBINATION before any computation begins. Same-currency rules (is_same_ccy_shortcircuit = true) may have m_a = m_b = 0. Also guard: m_a >= 0 and m_b >= 0 individually (no negative margins). Additionally guard that (1 - m_a - m_b) > 0, i.e. m_a + m_b < 1.0 (otherwise Step 2 divides by a non-positive number). These guards run before Step 1.
**Steps:** Create RateEngineException (checked, with String errorCode field) if not already present; Implement validateMargins(RateEngineInput input) static method that applies all three guards; Guard 1: if !is_same_ccy and (m_a + m_b) < 0.02, throw INVALID_MARGIN_COMBINATION; Guard 2: if m_a < 0 or m_b < 0, throw NEGATIVE_MARGIN; Guard 3: if (m_a + m_b) >= 1.0, throw MARGIN_EXCEEDS_UNITY; Write 5 unit tests covering each guard path and a valid pass-through
**Deliverable:** validateMargins() method in RateEngineSteps (or MarginValidator), RateEngineException class, 5 passing unit tests
**Acceptance / logic checks:**
- m_a=0.009, m_b=0.009, cross-border => throws INVALID_MARGIN_COMBINATION
- m_a=0.01, m_b=0.01, cross-border => no exception (exactly 2%)
- m_a=0.0, m_b=0.0, same-ccy => no exception
- m_a=-0.01, m_b=0.03 => throws NEGATIVE_MARGIN
- m_a=0.60, m_b=0.50 => throws MARGIN_EXCEEDS_UNITY
**Depends on:** 4.2-T01

### 4.2-T05 — Implement Step 2 pure function: collection_usd = payout_usd_cost / (1 - m_a - m_b)  _(20 min)_
**Context:** Step 2 of the 5-step engine: collection_usd = payout_usd_cost / (1 - m_a - m_b). This is the gross-up to the pre-margin USD pool. Divisor = (1 - m_a - m_b); guaranteed > 0 by 4.2-T04 guard. Use DECIMAL128 precision; do NOT round result. Worked example: payout_usd_cost=36.2319 USD, m_a=0.01, m_b=0.01 => divisor=0.98, collection_usd=36.2319/0.98=36.9713... USD. For IDENTITY legs both sides: payout_usd_cost=100.00, m_a=0.01, m_b=0.01 => collection_usd=100.00/0.98=102.0408... USD.
**Steps:** Create pure static function stepTwoCollectionUsd(BigDecimal payoutUsdCost, BigDecimal mA, BigDecimal mB): BigDecimal; Compute divisor = ONE.subtract(mA).subtract(mB) using BigDecimal arithmetic; Divide payoutUsdCost by divisor with MathContext.DECIMAL128; Return unrounded result; Write unit test: payoutUsdCost=36.2319, mA=0.01, mB=0.01, expect result approx 36.9713 (within 0.0001); Write identity-legs test: payoutUsdCost=100.00, mA=0.01, mB=0.01, expect approx 102.0408
**Deliverable:** Pure static function stepTwoCollectionUsd in RateEngineSteps, with 2 passing unit tests
**Acceptance / logic checks:**
- stepTwoCollectionUsd(36.2319, 0.01, 0.01) returns value in [36.9712, 36.9715]
- stepTwoCollectionUsd(100.00, 0.01, 0.01) returns value in [102.0407, 102.0410]
- Uses BigDecimal arithmetic only, no float
- No rounding inside function
- Function does not call validateMargins (guard is caller responsibility)
**Depends on:** 4.2-T03, 4.2-T04

### 4.2-T06 — Implement Step 3 pure functions: collection_margin_usd and payout_margin_usd  _(20 min)_
**Context:** Step 3a: collection_margin_usd = collection_usd * m_a. Step 3b: payout_margin_usd = collection_usd * m_b. Both are literal USD dollar amounts deducted from the pool -- not rate adjustments. Use DECIMAL128 precision, no rounding. Worked example: collection_usd=36.9714, m_a=0.01, m_b=0.01 => collection_margin_usd=0.3697 USD, payout_margin_usd=0.3697 USD. Second example (identity legs): collection_usd=102.0408, m_a=0.01, m_b=0.01 => collection_margin_usd=1.0204, payout_margin_usd=1.0204.
**Steps:** Create pure static function stepThreeMargins(BigDecimal collectionUsd, BigDecimal mA, BigDecimal mB): MarginPair (a small value object with collectionMarginUsd and payoutMarginUsd fields); Compute collectionMarginUsd = collectionUsd.multiply(mA) with DECIMAL128; Compute payoutMarginUsd = collectionUsd.multiply(mB) with DECIMAL128; Return MarginPair with both values unrounded; Write unit test for cross-border case: collectionUsd=36.9714, mA=0.01, mB=0.01 => both margins approx 0.3697; Write unit test for same-ccy case: mA=0, mB=0 => both margins exactly 0
**Deliverable:** stepThreeMargins function, MarginPair value object, 2 passing unit tests
**Acceptance / logic checks:**
- stepThreeMargins(36.9714, 0.01, 0.01) gives collectionMarginUsd in [0.3696, 0.3698] and payoutMarginUsd in [0.3696, 0.3698]
- stepThreeMargins(102.0408, 0.01, 0.01) gives both margins in [1.0203, 1.0205]
- stepThreeMargins(any, 0, 0) gives exactly ZERO for both margins
- Both fields in MarginPair are BigDecimal, not float
- MarginPair is immutable
**Depends on:** 4.2-T05

### 4.2-T07 — Implement Step 4 pure function: send_amount = collection_usd * cost_rate_coll  _(20 min)_
**Context:** Step 4: send_amount = collection_usd * cost_rate_coll. cost_rate_coll = treasury.usd_{settle_a_ccy} (units of Settle A per 1 USD). Result is in Settle A currency. For IDENTITY leg (Settle A = USD), cost_rate_coll = 1.0 so send_amount = collection_usd exactly. Do NOT round at this step; store to DECIMAL(20,8) precision. Worked example: collection_usd=36.9714, cost_rate_coll=1.0 (IDENTITY) => send_amount=36.9714 USD. Non-identity example: collection_usd=36.9714, cost_rate_coll=1380.00 (KRW) => send_amount=51,020.532 KRW.
**Steps:** Create pure static function stepFourSendAmount(BigDecimal collectionUsd, BigDecimal costRateColl): BigDecimal; Compute collectionUsd.multiply(costRateColl) with MathContext.DECIMAL128; Return unrounded result; Write unit test for IDENTITY leg: collectionUsd=36.9714, costRateColl=1.0, expect 36.9714; Write unit test for non-identity: collectionUsd=36.9714, costRateColl=1380.00, expect approx 51020.532 (within 0.01)
**Deliverable:** Pure static function stepFourSendAmount in RateEngineSteps, with 2 passing unit tests
**Acceptance / logic checks:**
- stepFourSendAmount(36.9714, 1.0) returns exactly 36.9714 (IDENTITY)
- stepFourSendAmount(36.9714, 1380.00) returns value in [51020.52, 51020.55]
- stepFourSendAmount(102.0408, 1.0) returns 102.0408 (identity-legs example)
- Uses BigDecimal multiply, not float
- No rounding inside the function
**Depends on:** 4.2-T06

### 4.2-T08 — Implement Step 5 pure function: collection_amount = send_amount + service_charge (with currency-scale rounding)  _(25 min)_
**Context:** Step 5: collection_amount = send_amount + service_charge. service_charge is a flat per-transaction fee in Settle A currency; it NEVER enters the USD pool. This is the final partner debit amount. Rounding rule: collection_amount is rounded to the currency-native scale at this step only (KRW = 0 decimal places, USD = 4 decimal places; pass in an int currencyScale). Worked example: send_amount=36.9714 USD, service_charge=0.36 USD, scale=4 => collection_amount=37.3314 USD. KRW example: send_amount=51020.532 KRW, service_charge=500 KRW, scale=0 => collection_amount=51521 KRW (rounded to nearest KRW). Same-ccy example: send_amount not used; formula is target_payout + service_charge (caller handles this path; this function still works: pass send_amount = target_payout).
**Steps:** Create pure static function stepFiveCollectionAmount(BigDecimal sendAmount, BigDecimal serviceCharge, int currencyScale): BigDecimal; Sum sendAmount.add(serviceCharge); Apply setScale(currencyScale, RoundingMode.HALF_UP) to the sum; Return rounded result; Write unit test: sendAmount=36.9714, serviceCharge=0.36, scale=4 => 37.3314; Write unit test: sendAmount=51020.532, serviceCharge=500, scale=0 => 51521; Write unit test: serviceCharge=0, scale=4 => collection_amount equals sendAmount rounded
**Deliverable:** Pure static function stepFiveCollectionAmount in RateEngineSteps, with 3 passing unit tests
**Acceptance / logic checks:**
- stepFiveCollectionAmount(36.9714, 0.36, 4) returns 37.3314
- stepFiveCollectionAmount(51020.532, 500, 0) returns 51521 (zero decimals)
- stepFiveCollectionAmount(102.0408, 0.50, 4) returns 102.5408 (identity-legs example)
- Rounding uses HALF_UP, not truncation or FLOOR
- serviceCharge=0 path: result equals sendAmount rounded to scale
**Depends on:** 4.2-T07

### 4.2-T09 — Assemble the 5-step RECEIVE-mode pipeline in RateEngineService.compute()  _(40 min)_
**Context:** Wire the five step functions (4.2-T03 through 4.2-T08) and the margin guard (4.2-T04) into a single RateEngineService.compute(RateEngineInput) -> RateEngineResult method. Logic: (1) call validateMargins; (2) if is_same_ccy_shortcircuit, return same-ccy result: collection_amount = target_payout + service_charge (rounded to currency scale), all USD pool fields null/zero, offer_rate_coll = 1.0, cross_rate = 1.0; (3) else execute Steps 1-5 in order; (4) build and return RateEngineResult with all fields populated. The pool identity assertion (collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost, tolerance 0.01 USD) is added in 4.2-T10 and called from here. Derived outputs offer_rate_coll and cross_rate are computed in 4.2-T11 and called from here.
**Steps:** Create RateEngineService class with public RateEngineResult compute(RateEngineInput input) throws RateEngineException; Call validateMargins(input) first; propagate exception on failure; Branch on input.is_same_ccy_shortcircuit: if true, compute same-ccy short-circuit result and return; Otherwise call stepOne -> stepTwo -> stepThree -> stepFour -> stepFive in order; Call poolIdentityCheck (stub from 4.2-T10: leave as TODO comment for now); Call computeDerivedRates (stub from 4.2-T11: leave as TODO comment for now); Build RateEngineResult from all computed values and return
**Deliverable:** RateEngineService.compute() method wiring all steps; same-ccy branch fully functional; cross-border branch functional pending 4.2-T10 and 4.2-T11 stubs
**Acceptance / logic checks:**
- Same-ccy input (collect_ccy=KRW, payout_ccy=KRW, service_charge=500) returns collection_amount=target_payout+500, all USD pool fields null or zero
- Cross-border input reaches stepFiveCollectionAmount and returns non-null collection_amount
- Invalid margin input throws RateEngineException before any step executes
- Method signature is compute(RateEngineInput) throws RateEngineException; no float in/out
- Calling compute twice with same input returns identical result (pure/deterministic)
**Depends on:** 4.2-T03, 4.2-T04, 4.2-T05, 4.2-T06, 4.2-T07, 4.2-T08

### 4.2-T10 — Implement pool identity assertion inside the pipeline  _(25 min)_
**Context:** After Step 3 is computed, verify: collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost within tolerance 0.01 USD. This is an algebraic consequence of the formulas (not a business guard), but any floating-point or implementation error that breaks it must be caught before the result is returned. If the absolute difference > 0.01, throw RateEngineException with code POOL_IDENTITY_BREACH and include the actual deviation in the message. The check runs only for cross-border paths (skip for same-ccy short-circuit). Integration point: call from RateEngineService.compute() after stepThree, before stepFour. From worked example: collection_usd=36.9714, collection_margin_usd=0.3697, payout_margin_usd=0.3697, payout_usd_cost=36.2319 => deviation=0.0001 USD, passes.
**Steps:** Create static function assertPoolIdentity(BigDecimal collectionUsd, BigDecimal collectionMarginUsd, BigDecimal payoutMarginUsd, BigDecimal payoutUsdCost) throws RateEngineException; Compute residual = collectionUsd - collectionMarginUsd - payoutMarginUsd - payoutUsdCost; If abs(residual) > 0.01, throw RateEngineException(POOL_IDENTITY_BREACH, deviation=residual); Wire call into RateEngineService.compute() replacing the TODO stub from 4.2-T09; Write passing test: worked example values => no exception; Write failing test: artificially inject deviation of 0.02 => expects POOL_IDENTITY_BREACH
**Deliverable:** assertPoolIdentity() function wired into RateEngineService.compute(), 2 passing unit tests
**Acceptance / logic checks:**
- collection_usd=36.9714, collection_margin_usd=0.3697, payout_margin_usd=0.3697, payout_usd_cost=36.2319 => no exception (deviation 0.0001 < 0.01)
- identity-legs case: collection_usd=102.0408, margins=1.0204 each, payout_usd_cost=100.00 => no exception (deviation 0 < 0.01)
- Injected residual of 0.02 => throws POOL_IDENTITY_BREACH
- Same-ccy path bypasses assertion entirely (no exception for null USD fields)
- Exception message includes the actual deviation value for diagnostics
**Depends on:** 4.2-T09

### 4.2-T11 — Implement derived rate computations: offer_rate_coll and cross_rate  _(25 min)_
**Context:** After the 5 steps, compute two derived (never configured) BOK outputs. offer_rate_coll = send_amount / (collection_usd - collection_margin_usd); maps to BOK FX1015 #14. cross_rate = target_payout / send_amount. Both are NULL for same-ccy short-circuit. Worked example: send_amount=36.9714, collection_usd=36.9714, collection_margin_usd=0.3697 => offer_rate_coll = 36.9714 / 36.6017 = 1.01010. target_payout=50000 KRW, send_amount=36.9714 USD => cross_rate = 50000 / 36.9714 = 1352.24 KRW/USD. Both computed using DECIMAL128; no rounding at this stage. Wire into RateEngineService.compute() replacing the TODO stub from 4.2-T09.
**Steps:** Create static function computeDerivedRates(BigDecimal sendAmount, BigDecimal collectionUsd, BigDecimal collectionMarginUsd, BigDecimal targetPayout): DerivedRates (small value object with offerRateColl and crossRate BigDecimal fields); Compute pool_net = collectionUsd.subtract(collectionMarginUsd); Compute offerRateColl = sendAmount.divide(poolNet, MathContext.DECIMAL128); Compute crossRate = targetPayout.divide(sendAmount, MathContext.DECIMAL128); Return DerivedRates with both values; Wire into RateEngineService.compute(); set null for same-ccy path; Write 2 unit tests: cross-border example and identity-legs example from spec
**Deliverable:** computeDerivedRates() function and DerivedRates value object, wired into RateEngineService.compute(), 2 passing unit tests
**Acceptance / logic checks:**
- computeDerivedRates(36.9714, 36.9714, 0.3697, 50000) gives offerRateColl in [1.01009, 1.01011]
- computeDerivedRates(36.9714, 36.9714, 0.3697, 50000) gives crossRate in [1352.23, 1352.25]
- Identity-legs: computeDerivedRates(102.0408, 102.0408, 1.0204, 100.00) gives offerRateColl in [1.01009, 1.01011] and crossRate in [0.9799, 0.9801]
- Same-ccy path returns null for both derived fields in RateEngineResult
- Uses BigDecimal DECIMAL128 division, not float
**Depends on:** 4.2-T10

### 4.2-T12 — Full integration unit test: fully worked cross-border example (SendMN on ZeroPay)  _(35 min)_
**Context:** End-to-end test of the assembled RateEngineService.compute() using the canonical worked example from RATE-04 spec section 4.3: target_payout=50000 KRW, cost_rate_pay=1380.00 (treasury.usd_krw), cost_rate_coll=1.0 (IDENTITY: Settle A=USD), m_a=0.01, m_b=0.01, service_charge=0.36 USD (Settle A ccy), settle_a_ccy=USD, settle_b_ccy=KRW, collect_ccy=USD, payout_ccy=KRW, is_same_ccy_shortcircuit=false. Expected outputs per spec: payout_usd_cost=36.2319, collection_usd=36.9714, collection_margin_usd=0.3697, payout_margin_usd=0.3697, send_amount=36.9714 USD, collection_amount=37.3314 USD, offer_rate_coll~1.01010, cross_rate~1352.24. Pool identity deviation < 0.01 USD. This test constitutes the primary acceptance vector for the engine.
**Steps:** Build RateEngineInput using the exact values from context; Call RateEngineService.compute(input); Assert each output field against the expected value with tolerances as specified; Assert pool identity deviation |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| < 0.0002; Assert no exception is thrown; Assert collection_amount is rounded to 4 decimal places (USD scale)
**Deliverable:** Integration unit test class RateEngineIntegrationTest with method testSendMnZeroPayCanonicalExample(), passing
**Acceptance / logic checks:**
- payout_usd_cost in [36.2318, 36.2320]
- collection_usd in [36.9713, 36.9715]
- collection_margin_usd in [36.9713*0.01 - 0.0001, 36.9713*0.01 + 0.0001] i.e. approx 0.3697
- send_amount in [36.9713, 36.9715] (IDENTITY leg so equals collection_usd)
- collection_amount in [37.3313, 37.3315]
- offer_rate_coll in [1.01009, 1.01011]
- cross_rate in [1352.23, 1352.25]
- pool identity deviation < 0.0002 USD
**Depends on:** 4.2-T11

### 4.2-T13 — Full integration unit test: identity-legs (USD-to-USD both legs) example  _(25 min)_
**Context:** End-to-end test using the second canonical worked example from RATE-04 spec section 7.1: target_payout=100.00 USD, cost_rate_coll=1.0 (IDENTITY), cost_rate_pay=1.0 (IDENTITY), m_a=0.01, m_b=0.01, service_charge=0.50 USD, settle_a_ccy=USD, settle_b_ccy=USD, collect_ccy=USD, payout_ccy=USD, is_same_ccy_shortcircuit=false. Expected: payout_usd_cost=100.00, collection_usd=102.0408, collection_margin_usd=1.0204, payout_margin_usd=1.0204, send_amount=102.0408, collection_amount=102.5408, offer_rate_coll~1.01010, cross_rate~0.98000. Pool identity: 102.0408 - 1.0204 - 1.0204 = 100.0000 exactly.
**Steps:** Build RateEngineInput with both identity legs (cost_rate_coll=1.0, cost_rate_pay=1.0, payout_ccy=USD, settle_b_ccy=USD); Call RateEngineService.compute(input); Assert all output fields against expected values with tight tolerances; Assert pool identity exactly equals 100.00 (deviation < 0.0001); Assert is_same_ccy_shortcircuit was false (cross-border path taken, margins applied); Assert offer_rate_coll and cross_rate are non-null
**Deliverable:** Integration unit test testIdentityLegsUsdUsd() in RateEngineIntegrationTest, passing
**Acceptance / logic checks:**
- payout_usd_cost is exactly 100.00
- collection_usd in [102.0407, 102.0410]
- collection_margin_usd and payout_margin_usd each in [1.0203, 1.0205]
- collection_amount in [102.5407, 102.5410]
- cross_rate in [0.9799, 0.9801]
- pool identity deviation < 0.0001
**Depends on:** 4.2-T12

### 4.2-T14 — Full integration unit test: same-currency short-circuit (GME Remit on ZeroPay)  _(20 min)_
**Context:** End-to-end test using the same-ccy short-circuit case from RATE-04 spec section 7.2: target_payout=15000 KRW, service_charge=500 KRW, collect_ccy=KRW, settle_a_ccy=KRW, settle_b_ccy=KRW, payout_ccy=KRW, m_a=0, m_b=0, is_same_ccy_shortcircuit=true. Expected: collection_amount=15500 KRW (integer, 0 decimals), payout_usd_cost=null, collection_usd=null, collection_margin_usd=0, payout_margin_usd=0, cost_rate_coll=1.0, cost_rate_pay=1.0, offer_rate_coll=1.0, cross_rate=1.0. No USD pool computed. No pool identity check applied.
**Steps:** Build RateEngineInput with all four ccys = KRW, m_a=0, m_b=0; Call RateEngineService.compute(input); Assert collection_amount = 15500, rounded to 0 decimal places (KRW scale); Assert payout_usd_cost is null; Assert collection_usd is null; Assert collection_margin_usd = 0 and payout_margin_usd = 0; Assert offer_rate_coll = 1.0 and cross_rate = 1.0
**Deliverable:** Integration unit test testSameCcyShortCircuitKrw() in RateEngineIntegrationTest, passing
**Acceptance / logic checks:**
- collection_amount equals exactly 15500 with scale 0
- payout_usd_cost is null
- collection_usd is null
- collection_margin_usd is ZERO and payout_margin_usd is ZERO
- offer_rate_coll is 1.0 and cross_rate is 1.0
- No POOL_IDENTITY_BREACH exception raised (same-ccy bypasses that check)
**Depends on:** 4.2-T12

### 4.2-T15 — Unit tests for margin guard edge cases and boundary values  _(25 min)_
**Context:** Targeted tests for the margin validation guard implemented in 4.2-T04. Business rules: cross-border m_a + m_b >= 0.02 (2% floor), m_a >= 0, m_b >= 0, m_a + m_b < 1.0. Same-currency rules may have m_a=m_b=0. Boundary values to cover: exactly 2% (pass), 1.99% (fail), one margin negative, sum >= 1.0 (divisor non-positive), and same-ccy exempt.
**Steps:** Write test: m_a=0.01, m_b=0.01, cross-border => no exception (exactly 2% floor); Write test: m_a=0.0099, m_b=0.0100, cross-border => INVALID_MARGIN_COMBINATION; Write test: m_a=0.015, m_b=0.0049, cross-border => INVALID_MARGIN_COMBINATION (1.99%); Write test: m_a=-0.005, m_b=0.03, cross-border => NEGATIVE_MARGIN; Write test: m_a=0.50, m_b=0.51, cross-border => MARGIN_EXCEEDS_UNITY (sum=1.01); Write test: m_a=0, m_b=0, is_same_ccy=true => no exception
**Deliverable:** Test class MarginGuardTest with 6 passing tests covering all guard branches
**Acceptance / logic checks:**
- m_a=0.01+m_b=0.01 cross-border passes
- m_a=0.0099+m_b=0.0100 cross-border throws INVALID_MARGIN_COMBINATION
- m_a=-0.005 throws NEGATIVE_MARGIN regardless of sum
- m_a+m_b=1.01 throws MARGIN_EXCEEDS_UNITY
- same-ccy with m_a=0,m_b=0 passes without exception
- All 6 tests pass independently with no shared mutable state
**Depends on:** 4.2-T04

### 4.2-T16 — Unit tests for decimal precision: no float contamination and DECIMAL128 scale  _(30 min)_
**Context:** These tests guard against accidental float/double usage and verify that intermediate values are held at sufficient precision. Key risk: using double for cost_rate_pay=1380 and target_payout=50000 introduces binary floating-point error that can violate the pool identity. Verify that each step function and the assembled pipeline hold precision >= 8 decimal places on intermediates, and that the pool identity deviation for the canonical example is < 0.0001 USD (well within 0.01 tolerance). Also verify collection_amount is the ONLY field rounded.
**Steps:** Write test asserting all intermediate BigDecimal fields in RateEngineResult have scale >= 8 for the cross-border case; Write test confirming pool identity deviation for canonical example (target_payout=50000 KRW) is < 0.0001 (not merely < 0.01); Write test: replace any BigDecimal constant with explicit scale-10 construction and confirm result is unchanged; Write test confirming collection_amount.scale() equals the supplied currencyScale (4 for USD, 0 for KRW); Write test confirming service_charge=0 path: collection_amount equals send_amount rounded to scale, no extra precision loss
**Deliverable:** Test class DecimalPrecisionTest with 5 passing tests
**Acceptance / logic checks:**
- payout_usd_cost.scale() >= 8 for canonical cross-border example
- pool identity deviation for canonical example < 0.0001
- collection_usd.scale() >= 8
- collection_amount.scale() == 4 for USD currency
- collection_amount.scale() == 0 for KRW currency
**Depends on:** 4.2-T12


## WBS 4.3 — USD pool identity assertion
### 4.3-T01 — Define PoolIdentityError exception and tolerance constant  _(20 min)_
**Context:** WBS 4.3 USD pool identity assertion. The spec (RATE-04 §11.2) requires that after Steps 1-3 of the 5-step RECEIVE-mode calculation, the check |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| < 0.01 USD must hold; failure is a hard programming error named POOL_IDENTITY_FAILURE. This ticket creates the exception class and the configurable tolerance constant so all other tickets can import them.
**Steps:** Create module rate_engine/errors.py (or equivalent package path); add PoolIdentityError(RuntimeError) with fields: collection_usd, collection_margin_usd, payout_margin_usd, payout_usd_cost, delta (all Decimal).; Add a settings constant POOL_IDENTITY_TOLERANCE_USD defaulting to Decimal('0.01') loaded from application config; document that it must remain positive.; Add a __str__ that renders all five fields and the word POOL_IDENTITY_FAILURE so log scanners can grep for it.; Write docstring citing RATE-04 §11.2 and stating this is a programming error, not a user error.
**Deliverable:** rate_engine/errors.py with PoolIdentityError class and POOL_IDENTITY_TOLERANCE_USD constant (default Decimal('0.01'))
**Acceptance / logic checks:**
- PoolIdentityError is importable from rate_engine.errors.
- Instantiating with delta=Decimal('0.015') and four USD field values succeeds and str() includes the word POOL_IDENTITY_FAILURE.
- POOL_IDENTITY_TOLERANCE_USD defaults to Decimal('0.01') and is of type Decimal, not float.
- Overriding POOL_IDENTITY_TOLERANCE_USD to Decimal('0.005') in test config is reflected when the constant is re-imported.
**Depends on:** 4.2

### 4.3-T02 — Implement assert_pool_identity() pure assertion function  _(25 min)_
**Context:** WBS 4.3. RATE-04 §11.2 formula: |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| < POOL_IDENTITY_TOLERANCE_USD (default 0.01 USD). All four inputs are Decimal. service_charge is NOT part of this check; it enters only at Step 5 (collection_amount = send_amount + service_charge) and must never be passed in. This function is the single source of truth for the assertion, tested in isolation before being wired into the engine.
**Steps:** In rate_engine/assertions.py create function assert_pool_identity(collection_usd, collection_margin_usd, payout_margin_usd, payout_usd_cost, tolerance=POOL_IDENTITY_TOLERANCE_USD) -> None.; Compute delta = abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost).; If delta >= tolerance raise PoolIdentityError(collection_usd, collection_margin_usd, payout_margin_usd, payout_usd_cost, delta).; If delta < tolerance return None silently.; Add a module docstring noting service_charge is intentionally excluded per RATE-04 §5.3.
**Deliverable:** rate_engine/assertions.py with assert_pool_identity() function
**Acceptance / logic checks:**
- Passing case: collection_usd=Decimal('36.9714'), collection_margin_usd=Decimal('0.3697'), payout_margin_usd=Decimal('0.3697'), payout_usd_cost=Decimal('36.2320') yields delta=0.0000 which is < 0.01 - function returns None.
- Failing case: artificially set collection_usd=Decimal('37.00') with the same margin and cost values (delta > 0.01) - function raises PoolIdentityError.
- Boundary exact: delta=Decimal('0.0099') < 0.01 passes; delta=Decimal('0.0100') >= 0.01 raises.
- service_charge is NOT a parameter; function signature has exactly 5 parameters (4 pool values + tolerance).
- Passing tolerance=Decimal('0.005') makes delta=Decimal('0.0070') raise where the default 0.01 would not.
**Depends on:** 4.3-T01

### 4.3-T03 — Unit tests for assert_pool_identity() - passing, failing, and service_charge exclusion cases  _(30 min)_
**Context:** WBS 4.3. Tests for the assert_pool_identity function in rate_engine/assertions.py. RATE-04 §4.3 worked example: treasury.usd_krw=1380.00, target_payout=50000 KRW, m_a=m_b=0.01. Results: payout_usd_cost=36.2319, collection_usd=36.9714, collection_margin_usd=0.3697, payout_margin_usd=0.3697 (all Decimal). Pool identity delta ~ 0.0001 USD, well within 0.01. service_charge is separate (e.g. 0.36 USD) and must NOT affect pool identity. Pool identity covers only Steps 1-3 values.
**Steps:** Create tests/rate_engine/test_pool_identity_assertion.py.; Test PASS_NOMINAL: use RATE-04 §4.3 worked values (payout_usd_cost=Decimal('36.2319'), collection_usd=Decimal('36.9714'), margins both Decimal('0.3697')) - assert no exception raised.; Test FAIL_ENGINEERED: set collection_usd=Decimal('40.0000'), others unchanged - assert PoolIdentityError raised and error.delta > Decimal('0.01').; Test SERVICE_CHARGE_IRRELEVANCE: call assert_pool_identity with the passing RATE-04 values then repeat with service_charge=Decimal('100.00') stored alongside but NOT passed to the function - both calls return None, confirming service_charge cannot affect the check.; Test BOUNDARY: delta=Decimal('0.0099') passes; delta=Decimal('0.0100') raises.; Test CUSTOM_TOLERANCE: tolerance=Decimal('0.005') causes delta=Decimal('0.0070') to raise PoolIdentityError.
**Deliverable:** tests/rate_engine/test_pool_identity_assertion.py with at least 5 parameterised test cases all passing
**Acceptance / logic checks:**
- All 5+ test cases pass with no tolerance warnings.
- SERVICE_CHARGE_IRRELEVANCE test explicitly demonstrates service_charge is not a parameter.
- FAIL_ENGINEERED test asserts error.delta attribute > Decimal('0.01').
- Boundary test checks both sides of the 0.01 threshold (< passes, >= raises).
- No float literals in any test - all monetary values use Decimal constructor.
**Depends on:** 4.3-T02

### 4.3-T04 — Integrate assert_pool_identity() into rate engine post-Steps-1-3  _(35 min)_
**Context:** WBS 4.3. RATE-04 §11.2 and the pseudocode in §11 show the assertion must be called immediately after Step 3b (payout_margin_usd = collection_usd * m_b) and before Step 4 (send_amount = collection_usd * cost_rate_coll). The same-currency short-circuit path (collection_ccy = settle_a_ccy = settle_b_ccy = payout_ccy) must SKIP the assertion entirely because the USD pool is never computed; instead collection_amount = target_payout + service_charge directly. service_charge is added at Step 5 and must not be passed to the assertion.
**Steps:** Open the Rate Engine calculate() method (rate_engine/engine.py or equivalent).; Locate the same-currency short-circuit branch; confirm assert_pool_identity is NOT called there.; In the cross-border branch, immediately after payout_margin_usd = collection_usd * m_b, add assert_pool_identity(collection_usd, collection_margin_usd, payout_margin_usd, payout_usd_cost).; Confirm Step 4 (send_amount) and Step 5 (collection_amount = send_amount + service_charge) follow unchanged after the assertion call.; Let PoolIdentityError propagate uncaught at this layer (the orchestrator catches it).
**Deliverable:** Updated rate_engine/engine.py with assert_pool_identity() call inserted at the correct point in the cross-border branch
**Acceptance / logic checks:**
- On a normal RATE-04 §4.3 cross-border call (m_a=m_b=0.01, target_payout=50000 KRW, usd_krw=1380) the engine completes and returns a RateQuote with no exception.
- Mutating collection_usd to an absurd value before assertion (test-only monkey-patch) causes the engine to raise PoolIdentityError before reaching Step 4.
- A same-currency call (e.g. KRW to KRW domestic) completes without any PoolIdentityError even though pool fields are null/zero.
- service_charge changing from 0.36 to 5.00 USD on a cross-border call does NOT raise PoolIdentityError (Step 5 comes after assertion).
- assert_pool_identity is called exactly once per cross-border calculation, not per same-currency call.
**Depends on:** 4.3-T02, 4.3-T03

### 4.3-T05 — Add critical-level structured logging on PoolIdentityError  _(25 min)_
**Context:** WBS 4.3. RATE-04 §11.2 states: 'It must be logged as a critical internal error for investigation.' A PoolIdentityError is a programming bug, not a user error. The log record must include all four pool values and the delta so production on-call engineers can diagnose without querying the DB. Log at CRITICAL level; the orchestrator should then re-raise or translate to a safe API error response (POOL_IDENTITY_FAILURE) - this ticket covers only logging, not the API response translation.
**Steps:** In the orchestrator layer (payment_orchestrator.py or rate_orchestrator.py) add a try/except PoolIdentityError block around the Rate Engine calculate() call.; In the except block log at CRITICAL level with structured fields: event=POOL_IDENTITY_FAILURE, collection_usd, collection_margin_usd, payout_margin_usd, payout_usd_cost, delta, quote_ref (if available).; After logging, re-raise the exception (do not swallow it; upstream caller will translate to API error).; Use the application logger (not print); ensure the message contains the literal string POOL_IDENTITY_FAILURE for log aggregator alerting.
**Deliverable:** Orchestrator try/except block in payment_orchestrator.py that logs PoolIdentityError at CRITICAL with all five numeric fields before re-raising
**Acceptance / logic checks:**
- Triggering a synthetic PoolIdentityError (mock engine) produces a CRITICAL log record containing the string POOL_IDENTITY_FAILURE.
- Log record contains collection_usd, collection_margin_usd, payout_margin_usd, payout_usd_cost, and delta as separate structured fields (not embedded in a prose string).
- Exception is re-raised after logging - calling code still receives PoolIdentityError.
- No CRITICAL log fires on a normal cross-border call that passes the assertion.
- Same-currency (short-circuit) calls produce no CRITICAL log.
**Depends on:** 4.3-T04

### 4.3-T06 — Translate PoolIdentityError to POOL_IDENTITY_FAILURE API error response  _(25 min)_
**Context:** WBS 4.3. The Northbound API (API-05) must return a structured error when a POOL_IDENTITY_FAILURE occurs. This is a 500 Internal Server Error (not a 4xx - the partner did nothing wrong). Response body must include error_code: POOL_IDENTITY_FAILURE and a safe message without internal field values (pool values are sensitive; they go to logs only). The orchestrator re-raises PoolIdentityError after logging (4.3-T05); this ticket adds the exception handler in the API layer.
**Steps:** In the API gateway/handler layer (e.g. api/routes/rates.py or api/routes/payments.py) add an exception handler for PoolIdentityError.; Map PoolIdentityError -> HTTP 500 response body {error_code: POOL_IDENTITY_FAILURE, message: Internal rate calculation error. Please contact support.} with no pool field values in the response.; Ensure the handler does NOT log again (logging is done in the orchestrator layer in 4.3-T05).; Add the same handler to both POST /v1/rates and POST /v1/payments routes.
**Deliverable:** API exception handler in api/routes/ mapping PoolIdentityError to HTTP 500 with error_code POOL_IDENTITY_FAILURE, added to both /v1/rates and /v1/payments handlers
**Acceptance / logic checks:**
- When the rate engine raises PoolIdentityError, the API returns HTTP 500 with body containing error_code=POOL_IDENTITY_FAILURE.
- Response body does NOT contain collection_usd, payout_usd_cost, or any internal pool values.
- HTTP status code is 500 (not 400 or 422).
- Normal cross-border and same-currency calls still return HTTP 200 with valid RateQuote.
- Unit test confirms the error_code field value is exactly the string POOL_IDENTITY_FAILURE.
**Depends on:** 4.3-T05

### 4.3-T07 — Integration test: same-currency short-circuit trivially satisfies identity (no assertion called)  _(30 min)_
**Context:** WBS 4.3. RATE-04 §7.2 and DAT-03 §7.3: when collection_ccy = settle_a_ccy = settle_b_ccy = payout_ccy (e.g. GME Remit KRW-to-KRW on ZeroPay), the USD pool is bypassed entirely. Fields collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd are all NULL/zero; collection_amount = target_payout + service_charge. The pool identity assertion must NOT be invoked on this path - it is not that it passes trivially, it is that the assertion function is never called, since there is no pool to check.
**Steps:** Create tests/rate_engine/test_same_currency_short_circuit.py.; Call the rate engine with a same-currency rule: collection_ccy=KRW, settle_a_ccy=KRW, settle_b_ccy=KRW, payout_ccy=KRW, target_payout=Decimal('50000'), service_charge=Decimal('500').; Assert result.collection_amount = Decimal('50500') (= 50000 + 500).; Assert result.collection_usd is None or Decimal('0') (pool bypassed).; Using unittest.mock.patch assert_pool_identity is called 0 times.; Assert no PoolIdentityError is raised.
**Deliverable:** tests/rate_engine/test_same_currency_short_circuit.py demonstrating the short-circuit path bypasses the pool identity assertion entirely
**Acceptance / logic checks:**
- collection_amount == Decimal('50500') for target_payout=50000 KRW + service_charge=500 KRW.
- assert_pool_identity is confirmed called 0 times via mock patch.
- No PoolIdentityError is raised on the short-circuit path.
- Changing service_charge from 500 to 1000 KRW yields collection_amount=51000, still no assertion call.
- USD pool fields (collection_usd, payout_usd_cost) are null/zero in the returned result.
**Depends on:** 4.3-T04

### 4.3-T08 — Integration test: end-to-end cross-border call with RATE-04 worked example values  _(35 min)_
**Context:** WBS 4.3. Full end-to-end test using the canonical worked example from RATE-04 §4.3: treasury.usd_krw=1380.00, target_payout=50000 KRW, m_a=0.01 (collection side), m_b=0.01 (payout side), service_charge=0.36 USD, cost_rate_coll=1.0 (identity leg, Settle A=USD), cost_rate_pay=1380.00. Expected: payout_usd_cost=36.2319, collection_usd=36.9714, collection_margin_usd=0.3697, payout_margin_usd=0.3697, send_amount=36.9714, collection_amount=37.3314. Pool identity delta=0.0001 < 0.01 USD (passes). service_charge change must not affect pool identity.
**Steps:** Create tests/rate_engine/test_rate04_worked_example.py.; Set up engine with exact RATE-04 §4.3 parameters and call calculate().; Assert each intermediate value matches RATE-04 §4.3 within Decimal('0.0001') precision.; Assert no PoolIdentityError is raised (pool identity passes).; Re-run with service_charge changed to Decimal('5.00') USD and assert pool identity still passes (collection_amount changes but pool check values are unchanged).; Assert that collection_amount = send_amount + service_charge (Step 5 correctness).
**Deliverable:** tests/rate_engine/test_rate04_worked_example.py validating the full 5-step RATE-04 §4.3 worked example including pool identity pass and service_charge isolation
**Acceptance / logic checks:**
- payout_usd_cost = Decimal('36.2319') +/- Decimal('0.0001').
- collection_usd = Decimal('36.9714') +/- Decimal('0.0001').
- collection_margin_usd = payout_margin_usd = Decimal('0.3697') +/- Decimal('0.0001').
- No PoolIdentityError raised on the nominal run.
- Changing service_charge from 0.36 to 5.00 USD does not raise PoolIdentityError and does not alter payout_usd_cost, collection_usd, collection_margin_usd, or payout_margin_usd.
**Depends on:** 4.3-T04

### 4.3-T09 — Unit test: verify tolerance is configurable and drives assertion boundary  _(25 min)_
**Context:** WBS 4.3. The tolerance default is Decimal('0.01') but must be configurable (POOL_IDENTITY_TOLERANCE_USD in application settings). This ticket tests that overriding the tolerance at the engine or function level changes the pass/fail boundary and that no float literals leak into the comparison. This is a regression guard ensuring future ops changes to tolerance do not silently break the check.
**Steps:** Create tests/rate_engine/test_pool_identity_tolerance.py.; Construct pool values with a known delta of Decimal('0.007'): collection_usd=Decimal('100.007'), collection_margin_usd=Decimal('1.00'), payout_margin_usd=Decimal('1.00'), payout_usd_cost=Decimal('98.000').; Call assert_pool_identity with default tolerance Decimal('0.01') -> expect no exception (0.007 < 0.01).; Call assert_pool_identity with tolerance=Decimal('0.005') -> expect PoolIdentityError (0.007 >= 0.005).; Call assert_pool_identity with tolerance=Decimal('0.008') -> expect PoolIdentityError (0.007 < 0.008 is false, 0.007 < 0.008 is true) - confirm boundary direction is strictly less-than.; Assert POOL_IDENTITY_TOLERANCE_USD constant type is Decimal not float.
**Deliverable:** tests/rate_engine/test_pool_identity_tolerance.py covering tolerance configurability and boundary behavior
**Acceptance / logic checks:**
- delta=Decimal('0.007') passes with tolerance=Decimal('0.01').
- delta=Decimal('0.007') raises PoolIdentityError with tolerance=Decimal('0.005').
- delta=Decimal('0.007') passes with tolerance=Decimal('0.008') (strictly less-than, 0.007 < 0.008).
- POOL_IDENTITY_TOLERANCE_USD is of type Decimal (isinstance check).
- No float literal appears in the tolerance comparison path (inspect source or assert Decimal arithmetic).
**Depends on:** 4.3-T02


## WBS 4.4 — Derived BOK outputs
### 4.4-T01 — Add offer_rate_coll and cross_rate fields to RateQuote result DTO  _(25 min)_
**Context:** WBS 4.4 Derived BOK outputs. RateQuote is the engine result object returned by compute_rate_quote() and stored in Redis (rate_quote table). It currently holds pool fields (collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, send_amount, service_charge, collection_amount). Two derived fields must be added: offer_rate_coll (DECIMAL(20,8), nullable - NULL for same-currency) and cross_rate (DECIMAL(20,8), nullable - NULL for same-currency). These are BOK-required outputs: offer_rate_coll = BOK FX1015 field #14. Depends on WBS 4.2 (rate engine core).
**Steps:** Open the RateQuote DTO/record class (result object of compute_rate_quote).; Add field offer_rate_coll: BigDecimal (nullable) with Javadoc: BOK FX1015 field #14; derived as send_amount / (collection_usd - collection_margin_usd); NULL for same-currency flows.; Add field cross_rate: BigDecimal (nullable) with Javadoc: target_payout / send_amount; NULL for same-currency flows.; Add the two fields to the rate_quote DB table migration as DECIMAL(20,8) NULL columns (offer_rate_coll, cross_rate).; Verify all existing RateQuote constructors/builders compile with the new fields (set null or 0 as appropriate for existing callers).
**Deliverable:** Updated RateQuote DTO with offer_rate_coll and cross_rate fields plus DB migration script adding those two columns to rate_quote table
**Acceptance / logic checks:**
- RateQuote object can be instantiated with offer_rate_coll = null and cross_rate = null without NPE.
- DB migration runs cleanly on an empty rate_quote table: both columns appear as DECIMAL(20,8) NULL.
- All existing unit tests for RateQuote still pass after adding the two nullable fields.
- Javadoc on offer_rate_coll explicitly states: BOK FX1015 field #14; formula send_amount / (collection_usd - collection_margin_usd).
**Depends on:** 4.2

### 4.4-T02 — Implement offer_rate_coll computation in rate engine (cross-border path)  _(30 min)_
**Context:** WBS 4.4 Derived BOK outputs. After completing steps 1-5 of the RECEIVE-mode rate engine (payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd, send_amount), compute: collection_net_usd = collection_usd - collection_margin_usd; offer_rate_coll = send_amount / collection_net_usd. This is the effective all-in rate from Settle A to Settle B after collection-side margin, and is BOK FX1015 field #14. Must use Decimal arithmetic (BigDecimal in Java). Numeric example: collection_usd=36.9714 USD, collection_margin_usd=0.3697 USD, send_amount=36.9714 USD (identity leg: cost_rate_coll=1.0) -> collection_net_usd=36.6017 -> offer_rate_coll=36.9714/36.6017=1.01010. Second example (USD/USD): collection_usd=102.0408, collection_margin_usd=1.0204, send_amount=102.0408 -> offer_rate_coll=102.0408/101.0204=1.01010. Depends on 4.4-T01.
**Steps:** In the cross-border branch of compute_rate_quote(), after step 4 (send_amount = collection_usd * cost_rate_coll), compute: collection_net_usd = collection_usd.subtract(collection_margin_usd).; Compute offer_rate_coll = send_amount.divide(collection_net_usd, 8, HALF_UP). Use BigDecimal.divide with explicit scale 8 and rounding mode.; Assign offer_rate_coll to the RateQuote builder/constructor.; Add a single-line comment above the assignment: // BOK FX1015 field #14: effective Settle-A to Settle-B rate after collection-side margin.; Confirm same-currency path still sets offer_rate_coll = BigDecimal.ONE (unity rate, no margin).
**Deliverable:** compute_rate_quote() method populating offer_rate_coll in the returned RateQuote for cross-border calls
**Acceptance / logic checks:**
- Input: collection_usd=36.9714, collection_margin_usd=0.3697, send_amount=36.9714 -> offer_rate_coll == 1.01010 (within 0.000005 tolerance).
- Input: collection_usd=102.0408, collection_margin_usd=1.0204, send_amount=102.0408 -> offer_rate_coll == 1.01010 (within 0.000005).
- Source code contains comment referencing BOK FX1015 field #14 adjacent to the formula.
- Same-currency call returns offer_rate_coll = 1.0 exactly.
- offer_rate_coll field in the returned RateQuote is non-null for any cross-border call.
**Depends on:** 4.4-T01

### 4.4-T03 — Implement cross_rate computation in rate engine (cross-border path)  _(25 min)_
**Context:** WBS 4.4 Derived BOK outputs. After computing send_amount in step 4 of the RECEIVE-mode engine, compute: cross_rate = target_payout / send_amount. This is the direct exchange rate between payout currency and Settle A currency; a reconciliation/display reference figure. Must use BigDecimal arithmetic, scale 8, HALF_UP. Numeric examples: target_payout=50000 KRW, send_amount=36.9714 USD -> cross_rate=50000/36.9714=1352.24 KRW/USD. USD/USD example: target_payout=100.00, send_amount=102.0408 -> cross_rate=100.00/102.0408=0.98000. Same-currency: cross_rate=1.0. Depends on 4.4-T01.
**Steps:** In the cross-border branch of compute_rate_quote(), after step 4 (send_amount computed), compute cross_rate = target_payout.divide(send_amount, 8, HALF_UP).; Assign cross_rate to the RateQuote builder/constructor.; Add comment: // cross_rate: direct payout-ccy / Settle-A-ccy reference rate for reconciliation.; Confirm same-currency path sets cross_rate = BigDecimal.ONE.; Confirm cross_rate is non-null in the returned RateQuote for all cross-border calls.
**Deliverable:** compute_rate_quote() method populating cross_rate in the returned RateQuote for cross-border calls
**Acceptance / logic checks:**
- Input: target_payout=50000, send_amount=36.9714 -> cross_rate == 1352.24 (within 0.005 tolerance).
- Input: target_payout=100.00, send_amount=102.0408 -> cross_rate == 0.98000 (within 0.000005).
- Same-currency call returns cross_rate = 1.0 exactly.
- cross_rate is non-null for any cross-border call in returned RateQuote.
- No floating-point (double/float) used anywhere in the computation path; BigDecimal only.
**Depends on:** 4.4-T01

### 4.4-T04 — Guard divide-by-zero: validate collection_net_usd > 0 before offer_rate_coll  _(30 min)_
**Context:** WBS 4.4 Derived BOK outputs. offer_rate_coll = send_amount / (collection_usd - collection_margin_usd). Denominator collection_net_usd = collection_usd - collection_margin_usd must be > 0 before division. Under normal engine flow collection_usd > collection_margin_usd always (m_a < 1.0 is already guarded), but a defensive explicit check prevents any future code path from producing ArithmeticException or returning infinity. Error code: INVALID_MARGIN (or a dedicated RATE_COMPUTATION_ERROR). Also: send_amount > 0 must be checked before cross_rate = target_payout / send_amount (send_amount = collection_usd * cost_rate_coll; if cost_rate_coll > 0 and collection_usd > 0 this holds, but guard explicitly). Depends on 4.4-T02 and 4.4-T03.
**Steps:** Before computing offer_rate_coll, add: if collection_net_usd.compareTo(BigDecimal.ZERO) <= 0 throw new RateEngineException(RATE_COMPUTATION_ERROR, 'collection_net_usd must be > 0').; Before computing cross_rate, add: if send_amount.compareTo(BigDecimal.ZERO) <= 0 throw new RateEngineException(RATE_COMPUTATION_ERROR, 'send_amount must be > 0').; Ensure exception messages are logged at ERROR level with the offending values.; Verify that the existing guard (m_a + m_b < 1.0) comes before these derived-rate guards in execution order so the primary guard fires first on bad margin input.
**Deliverable:** Two explicit divide-by-zero guards in compute_rate_quote() with RateEngineException throws before offer_rate_coll and cross_rate divisions
**Acceptance / logic checks:**
- Calling compute_rate_quote() with collection_usd=1.0, collection_margin_usd=1.0 (collection_net_usd=0) throws RateEngineException with code RATE_COMPUTATION_ERROR, not ArithmeticException.
- Calling with collection_usd=0.0001, collection_margin_usd=0.0001 (net=0) also throws (boundary).
- Calling with send_amount=0 (artificially injected) throws RateEngineException before cross_rate division.
- Normal cross-border call with collection_net_usd=36.6017 does NOT throw; proceeds to return offer_rate_coll=1.01010.
- The m_a+m_b >= 0.02 guard fires before the derived-rate guards when m_a+m_b < 0.02 is the root cause.
**Depends on:** 4.4-T02, 4.4-T03

### 4.4-T05 — Unit tests: offer_rate_coll and cross_rate happy-path numeric vectors  _(40 min)_
**Context:** WBS 4.4 Derived BOK outputs. Test the computed values of offer_rate_coll and cross_rate against spec-provided numeric examples. Engine RECEIVE mode, cross-border. Vector 1 (KRW/USD identity-coll leg): target_payout=50000 KRW, cost_rate_pay=1350.0 (KRW/USD), cost_rate_coll=1.0 (USD identity), m_a=0.01, m_b=0.01, service_charge=0 -> expected: payout_usd_cost=37.0370, collection_usd=37.8948 (approx), collection_margin_usd=0.3789, send_amount=37.8948, offer_rate_coll=37.8948/(37.8948-0.3789)=1.01010, cross_rate=50000/37.8948=1319.4. Vector 2 (USD/USD): target_payout=100 USD, cost_rate_pay=1.0, cost_rate_coll=1.0, m_a=0.01, m_b=0.01, service_charge=0 -> collection_usd=102.0408, collection_margin_usd=1.0204, send_amount=102.0408, offer_rate_coll=1.01010, cross_rate=0.98000. Depends on 4.4-T02, 4.4-T03, 4.4-T04.
**Steps:** Create test class RateEngineDerivedRatesTest (JUnit 5).; Implement testOfferRateCollKrwUsd(): use Vector 1 inputs, assert offer_rate_coll within delta 0.00005, cross_rate within delta 0.01.; Implement testOfferRateCollUsdUsd(): use Vector 2 inputs, assert offer_rate_coll=1.01010 within 0.00005, cross_rate=0.98000 within 0.00005.; Implement testSameCurrencyDerivedRates(): same-currency call, assert offer_rate_coll=1.0 exactly, cross_rate=1.0 exactly.; Use BigDecimal assertions throughout; no assertEquals(double, double) - use compareTo or assertThat with precision.
**Deliverable:** RateEngineDerivedRatesTest.java with at least 3 passing unit tests covering happy-path numeric vectors for offer_rate_coll and cross_rate
**Acceptance / logic checks:**
- testOfferRateCollUsdUsd passes: offer_rate_coll=1.01010 +/- 0.00005 for USD/USD inputs.
- testSameCurrencyDerivedRates passes: offer_rate_coll=1.0, cross_rate=1.0 for same-ccy call.
- testOfferRateCollKrwUsd passes: cross_rate is in expected KRW/USD range (1300-1400) for target_payout=50000 KRW at rate ~1350.
- All tests use BigDecimal inputs (no float literals).
- Test class runs with mvn test -pl rate-engine with zero failures.
**Depends on:** 4.4-T02, 4.4-T03, 4.4-T04

### 4.4-T06 — Unit tests: divide-by-zero guards for offer_rate_coll and cross_rate  _(30 min)_
**Context:** WBS 4.4 Derived BOK outputs. Guard tests verify that the engine throws RateEngineException (not ArithmeticException) when collection_net_usd <= 0 or send_amount <= 0. These are defensive guards added in 4.4-T04. Test via a test-only constructor or package-private method that bypasses the earlier m_a/m_b validation and directly injects a zero denominator, OR by verifying the exception fires correctly when m_a is crafted to make collection_net_usd approach zero (m_a close to 1.0 but blocked by the earlier m_a+m_b<1.0 guard first). Also test that a normal call with valid denominators (collection_net_usd=36.6017, send_amount=36.9714) does NOT throw. Depends on 4.4-T04.
**Steps:** In RateEngineDerivedRatesTest, add testDivideByZeroCollectionNetUsd(): inject collection_net_usd=0 via the package-private guard method or a test subclass; assert RateEngineException is thrown with code RATE_COMPUTATION_ERROR.; Add testDivideByZeroSendAmount(): inject send_amount=0; assert RateEngineException thrown.; Add testNoExceptionForValidDenominators(): call with collection_net_usd=36.6017, send_amount=36.9714; assert no exception and offer_rate_coll is populated.; Verify exception message contains the field name (collection_net_usd or send_amount) for debuggability.
**Deliverable:** Divide-by-zero guard tests added to RateEngineDerivedRatesTest.java (3 additional test methods)
**Acceptance / logic checks:**
- testDivideByZeroCollectionNetUsd throws RateEngineException (not ArithmeticException) when collection_net_usd=0.
- testDivideByZeroSendAmount throws RateEngineException when send_amount=0.
- Exception errorCode is RATE_COMPUTATION_ERROR in both cases.
- testNoExceptionForValidDenominators completes without exception and returns non-null offer_rate_coll.
- All 3 tests pass with mvn test.
**Depends on:** 4.4-T04, 4.4-T05

### 4.4-T07 — Persist offer_rate_coll and cross_rate into rate_quote table on quote creation  _(35 min)_
**Context:** WBS 4.4 Derived BOK outputs. When the rate engine creates a quote (compute_rate_quote()), the returned RateQuote (containing offer_rate_coll and cross_rate) must be persisted to the rate_quote table. The rate_quote table already holds pool values; the two new DECIMAL(20,8) NULL columns (offer_rate_coll, cross_rate) were added in 4.4-T01. The repository/DAO layer that INSERTs into rate_quote must now include those two fields. Precision: DECIMAL(20,8). NULL only for same-currency flows. Depends on 4.4-T01.
**Steps:** In the RateQuoteRepository (or JPA entity for rate_quote), map offer_rate_coll and cross_rate fields to the DB columns added in 4.4-T01.; In the INSERT/save path for a new quote, write rateQuote.getOfferRateColl() and rateQuote.getCrossRate() to the DB (null for same-currency).; Add a repository integration test (or @DataJpaTest) that saves a cross-border RateQuote and reads it back, asserting offer_rate_coll and cross_rate are preserved to 8 decimal places.; Add a second case for same-currency: assert both columns read back as NULL.
**Deliverable:** Updated RateQuoteRepository persisting offer_rate_coll and cross_rate, plus integration test verifying round-trip DB persistence
**Acceptance / logic checks:**
- Cross-border RateQuote saved with offer_rate_coll=1.01010000 reads back as 1.01010000 (no truncation).
- Same-currency RateQuote saved with offer_rate_coll=null reads back as null.
- No existing tests broken by the repository change.
- DB query SELECT offer_rate_coll, cross_rate FROM rate_quote WHERE id=? returns the expected values.
**Depends on:** 4.4-T01, 4.4-T02, 4.4-T03

### 4.4-T08 — Copy offer_rate_coll and cross_rate to transaction record at rate-lock (commit)  _(40 min)_
**Context:** WBS 4.4 Derived BOK outputs. On CommitTransaction (rate-lock step), ALL rate_quote pool values and derived rates are copied to the transaction record as immutable columns (rate_locked_at is set). The transaction table must have offer_rate_coll and cross_rate columns (DECIMAL(20,8) NULL, labelled in column comment: BOK FX1015 field #14 for offer_rate_coll). The on_commit() function copies: transaction.offer_rate_coll = quote.offer_rate_coll; transaction.cross_rate = quote.cross_rate. After lock, no subsequent treasury or margin change may alter these values. Depends on 4.4-T07.
**Steps:** Add migration: ALTER TABLE transaction ADD COLUMN offer_rate_coll DECIMAL(20,8) NULL COMMENT 'Derived rate: send_amount/(collection_usd-collection_margin_usd); BOK FX1015 field #14; locked at commit'; ADD COLUMN cross_rate DECIMAL(20,8) NULL COMMENT 'Derived rate: target_payout/send_amount; locked at commit'.; In the on_commit() / CommitTransaction service method, add assignment: transaction.setOfferRateColl(quote.getOfferRateColl()); transaction.setCrossRate(quote.getCrossRate()).; Verify rate_locked_at is also set in the same atomic write as the derived fields.; Add an integration test: commit a transaction, then change treasury rates in DB, reload the transaction record, assert offer_rate_coll and cross_rate are unchanged.
**Deliverable:** DB migration adding offer_rate_coll and cross_rate to transaction table plus on_commit() assignments with rate-lock immutability test
**Acceptance / logic checks:**
- After CommitTransaction, transaction.offer_rate_coll equals the value from the locked RateQuote (1.01010 for standard cross-border).
- After CommitTransaction, updating treasury rate in DB and reloading the transaction record does NOT change offer_rate_coll or cross_rate.
- Same-currency transaction committed with offer_rate_coll=null persists null in transaction table.
- rate_locked_at and offer_rate_coll are written in the same DB transaction (atomically).
- Migration runs without error; DESCRIBE transaction shows both new columns.
**Depends on:** 4.4-T07

### 4.4-T09 — Expose offer_rate_coll (as offer_rate) and cross_rate in /v1/rates API response  _(35 min)_
**Context:** WBS 4.4 Derived BOK outputs. The northbound API (GET /v1/rates) response must include offer_rate (mapped from offer_rate_coll) and cross_rate. Per spec API-05, field names in the response are: offer_rate (= offer_rate_coll), cross_rate, plus existing pool fields (collection_usd, send_amount, service_charge, collection_amount, validUntil). The RateQuoteResponse DTO returned by the /rates endpoint must include these two fields. Same-currency: offer_rate=1.0, cross_rate=1.0 (do not expose null to partners; convert null to 1.0 in the API layer). Depends on 4.4-T02, 4.4-T03.
**Steps:** In RateQuoteResponse (API response DTO), add fields: offer_rate (BigDecimal) and cross_rate (BigDecimal).; In the mapping from RateQuote -> RateQuoteResponse, set offer_rate = (quote.getOfferRateColl() != null ? quote.getOfferRateColl() : BigDecimal.ONE); similarly for cross_rate.; Add @Schema annotation (OpenAPI): offer_rate description = 'Effective Settle-A to Settle-B rate after collection-side margin (BOK FX1015 #14). Use this rate to compute the customer-facing collection amount.'; cross_rate = 'Direct payout-ccy / send-amount reference rate for reconciliation.'; Write a controller/MockMvc test: mock a cross-border rate computation, call GET /v1/rates, assert JSON response contains offer_rate=1.01010 and cross_rate in expected range.
**Deliverable:** RateQuoteResponse DTO with offer_rate and cross_rate fields, mapping logic, OpenAPI annotations, and controller test
**Acceptance / logic checks:**
- GET /v1/rates for cross-border call returns JSON with offer_rate=1.01010 (within 0.00005) and cross_rate populated.
- GET /v1/rates for same-currency call returns offer_rate=1.0 and cross_rate=1.0 (not null, not absent).
- OpenAPI spec (generated) contains offer_rate with description mentioning BOK FX1015 #14.
- Field name in JSON is offer_rate (not offer_rate_coll) per API-05.
- Controller test passes with mvn test.
**Depends on:** 4.4-T02, 4.4-T03, 4.4-T07

### 4.4-T10 — Document BOK FX1015 field #14 mapping in code comments and architecture note  _(20 min)_
**Context:** WBS 4.4 Derived BOK outputs. The spec requires the BOK FX1015 field #14 mapping to be documented in code so that any developer or compliance reviewer can trace offer_rate_coll back to the regulatory requirement without referencing external documents. This is a pure documentation ticket: no logic changes. Key facts to embed: offer_rate_coll = send_amount / (collection_usd - collection_margin_usd); maps to BOK FX1015 (payout-to-Korean-merchant report) field #14; cross_rate = target_payout / send_amount (reconciliation reference); both are derived and NEVER operator-configured; BOK-reported rates equal executed rates by construction (rate-lock). FX1014 covers collection side, FX1015 covers payout side. Depends on 4.4-T08.
**Steps:** In compute_rate_quote(), add a block comment above the derived-rates section: // === Derived BOK Outputs (never configured) === // offer_rate_coll: BOK FX1015 (payout-side) field #14. Formula: send_amount / (collection_usd - collection_margin_usd). // cross_rate: target_payout / send_amount. Reconciliation reference rate only. // Both values are locked immutably at CommitTransaction. See RATE-04 in spec.; In the RateQuote DTO Javadoc class-level comment, add: 'offer_rate_coll maps to Bank of Korea FX1015 (payout leg) field #14. FX1014 covers the collection leg. Both rates equal executed rates by construction; no separate reporting computation needed.'; In the transaction entity class, add a Javadoc on the offer_rate_coll field: '@see bok_report_record for FX1015 submission; this field is the authoritative source for FX1015 #14.'; Verify no TODO or placeholder comments remain in the derived-rates code block.
**Deliverable:** In-code documentation: block comment in compute_rate_quote(), class-level Javadoc in RateQuote DTO, and field Javadoc in transaction entity - all referencing BOK FX1015 field #14
**Acceptance / logic checks:**
- grep -r 'FX1015' src/ returns at least 3 hits: compute_rate_quote(), RateQuote DTO, transaction entity.
- The comment in compute_rate_quote() states both the formula and the BOK FX1015 field #14 reference.
- The transaction entity Javadoc on offer_rate_coll references bok_report_record as the downstream consumer.
- No floating-point types appear anywhere in the documented code paths.
- Code review: a new developer can identify the BOK regulatory purpose of offer_rate_coll from code alone, without reading the spec.
**Depends on:** 4.4-T08


## WBS 4.5 — Identity legs & same-currency short-circuit
### 4.5-T01 — Add is_same_currency flag and identity-leg fields to RuleConfig domain model  _(30 min)_
**Context:** WBS 4.5 implements identity legs and same-currency short-circuit. A Rule links a Partner x Scheme x Direction and carries cost_rate_coll / cost_rate_pay plus margin percentages m_a and m_b. The rate engine needs two new derived boolean flags: (1) is_identity_coll = (settle_a_ccy == USD); (2) is_identity_pay = (settle_b_ccy == USD); (3) is_same_currency = (collection_ccy == settle_a_ccy == settle_b_ccy == payout_ccy). These flags are computed, not stored — they derive from the currencies already on the Rule record. Adding them to the RuleConfig value object makes them available to the engine without passing raw currency strings.
**Steps:** Open the RuleConfig value object / record class (or equivalent DTO) used as the rate engine input.; Add three computed / derived boolean fields: is_identity_coll, is_identity_pay, is_same_currency — all package-private or internal.; Implement each as a pure currency-equality check: is_identity_coll = settle_a_ccy.equals(USD); is_identity_pay = settle_b_ccy.equals(USD); is_same_currency = collection_ccy == settle_a_ccy == settle_b_ccy == payout_ccy (all four equal).; Add a Javadoc / inline comment citing spec section 7.1 and 7.2 for each flag.; Add a unit test class RuleConfigFlagsTest with at least 4 cases: (a) all KRW -> is_same_currency=true, both identity flags=false; (b) settle_a=USD -> is_identity_coll=true; (c) settle_b=USD -> is_identity_pay=true; (d) both settle legs USD -> both identity flags true, is_same_currency=false (ccy differ from collection/payout).
**Deliverable:** Updated RuleConfig value object with three computed boolean flags plus RuleConfigFlagsTest unit test class.
**Acceptance / logic checks:**
- is_same_currency returns true iff all four of collection_ccy, settle_a_ccy, settle_b_ccy, payout_ccy are identical strings.
- is_identity_coll returns true iff settle_a_ccy == USD; is_identity_pay returns true iff settle_b_ccy == USD.
- is_same_currency = true does NOT imply either identity flag is true (e.g. all KRW => is_same_currency=true, is_identity_coll=false).
- All four RuleConfigFlagsTest cases pass.
- Flags are derived/computed — no new DB columns required.
**Depends on:** 4.2

### 4.5-T02 — Implement identity-leg rate resolution in RateEngine.resolveRates()  _(35 min)_
**Context:** WBS 4.5 identity leg rule (spec §7.1): when settle_a_ccy = USD, set cost_rate_coll = 1.0 with source = IDENTITY; when settle_b_ccy = USD, set cost_rate_pay = 1.0 with source = IDENTITY. Both legs may simultaneously be IDENTITY (HUB direction, USD-in / USD-out). Margins m_a and m_b still apply normally in cross-border identity-leg cases; the identity only means no treasury lookup for that leg. The rate source enum must include IDENTITY alongside LIVE, MANUAL, PARTNER. This ticket adds identity resolution BEFORE the normal treasury/partner lookup so identity legs never cause RATE_UNAVAILABLE errors.
**Steps:** Ensure the RateSource enum (or equivalent) contains an IDENTITY value (add it if missing).; In RateEngine.resolveRates() (or the method that sets cost_rate_coll and cost_rate_pay), add an early-return path for each leg: if rule.is_identity_coll == true, set cost_rate_coll = Decimal(1.0) and source_coll = IDENTITY without querying the treasury.; Apply the same pattern for the pay leg: if rule.is_identity_pay == true, set cost_rate_pay = Decimal(1.0) and source_pay = IDENTITY.; Record both source flags on the RateQuote / intermediate result so they are persisted in the rate-lock step.; Ensure the resolved rates and sources are passed to the 5-step engine unmodified.
**Deliverable:** Updated resolveRates() method in RateEngine with IDENTITY early-return for both legs.
**Acceptance / logic checks:**
- When settle_a_ccy = USD: cost_rate_coll = 1.0 exactly (Decimal), source_coll = IDENTITY; no treasury call made for that leg.
- When settle_b_ccy = USD: cost_rate_pay = 1.0 exactly, source_pay = IDENTITY; no treasury call for that leg.
- When neither leg is identity: resolution falls through to existing LIVE/MANUAL/PARTNER logic unchanged.
- Both legs IDENTITY simultaneously: both rates = 1.0, both sources = IDENTITY; 5-step engine receives valid non-zero rates.
- IDENTITY resolution does not suppress margin application — m_a and m_b still flow into the 5-step engine.
**Depends on:** 4.5-T01

### 4.5-T03 — Add identity-leg worked example unit tests to RateEngineIdentityTest  _(40 min)_
**Context:** WBS 4.5: verify that the 5-step RECEIVE-mode engine produces correct outputs when one or both legs are IDENTITY. Spec §7.1 worked example: target_payout=100.00 USD, cost_rate_coll=1.0 (IDENTITY), cost_rate_pay=1.0 (IDENTITY), m_a=0.01, m_b=0.01, service_charge=0.50 USD. Expected: payout_usd_cost=100.00, collection_usd=102.0408, collection_margin_usd=1.0204, payout_margin_usd=1.0204, send_amount=102.0408, collection_amount=102.5408, offer_rate_coll=1.01010, cross_rate=0.98000. Pool identity: 102.0408 - 1.0204 - 1.0204 = 100.0000. All arithmetic must use Decimal types; tolerance for derived rates = 0.00001.
**Steps:** Create test class RateEngineIdentityTest.; Add test case test_both_legs_identity_usd: inputs as per spec §7.1 worked example; assert each output field within stated tolerance.; Add test case test_identity_coll_only: settle_a=USD (cost_rate_coll=1.0), settle_b=KRW (cost_rate_pay=1350.00), m_a=0.015, m_b=0.015, target_payout=50000 KRW, service_charge=0 USD; verify step-by-step outputs and pool identity holds.; Add test case test_identity_pay_only: settle_a=EUR (cost_rate_coll=0.92), settle_b=USD (cost_rate_pay=1.0), m_a=0.01, m_b=0.01, target_payout=100.00 USD, service_charge=1.00 EUR; verify outputs.; Assert pool identity |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| < 0.01 for all three cases.
**Deliverable:** RateEngineIdentityTest class with 3 test cases covering single and dual identity-leg scenarios.
**Acceptance / logic checks:**
- Both-legs-identity test: collection_amount = 102.5408 USD within 0.0001 tolerance.
- Both-legs-identity test: offer_rate_coll = 1.01010 and cross_rate = 0.98000 within 0.00001.
- Pool identity assertion passes for all three test cases (tolerance 0.01 USD).
- source_coll and source_pay are recorded as IDENTITY in the returned RateQuote for identity legs.
- Non-identity legs in mixed tests resolve rates from treasury (not hardcoded to 1.0).
**Depends on:** 4.5-T02

### 4.5-T04 — Implement same-currency short-circuit branch in RateEngine.compute()  _(35 min)_
**Context:** WBS 4.5 same-currency short-circuit (spec §7.2): when is_same_currency = true (all four currencies equal), skip the entire 5-step USD pool. Instead: collection_amount = target_payout + service_charge; offer_rate_coll = 1.0; cross_rate = 1.0; collection_usd = NULL; payout_usd_cost = NULL; collection_margin_usd = 0; payout_margin_usd = 0; send_amount = target_payout. The guard must also assert m_a == 0 AND m_b == 0 and raise INVALID_MARGIN if not. service_charge is a flat Decimal in the collection currency (e.g. KRW 500); it is added directly, no USD conversion. KRW scale = 0 decimals.
**Steps:** In RateEngine.compute(), immediately after the guard checks, add: if rule.is_same_currency { /* short-circuit */ }.; Inside the short-circuit block: assert m_a == 0 and m_b == 0 (raise INVALID_MARGIN if violated); set collection_amount = target_payout + service_charge; set offer_rate_coll = Decimal(1.0); set cross_rate = Decimal(1.0); set send_amount = target_payout; set collection_usd = null; set payout_usd_cost = null; set collection_margin_usd = Decimal(0); set payout_margin_usd = Decimal(0).; Round collection_amount to currency scale (KRW = 0 decimals) using the existing round_to_currency_scale helper.; Build and return RateQuote from these values, bypassing all 5-step logic.; Ensure the normal 5-step path is unchanged (else branch) and that is_same_currency=false does not enter the short-circuit block.
**Deliverable:** Short-circuit branch in RateEngine.compute() that sets collection_amount = target_payout + service_charge and nulls all USD pool fields when is_same_currency = true.
**Acceptance / logic checks:**
- GME Remit KRW->KRW on ZeroPay: target_payout=10000 KRW, service_charge=500 KRW, m_a=0, m_b=0 => collection_amount=10500 KRW, collection_usd=null, payout_usd_cost=null.
- GME Remit worked example from spec: target_payout=15000 KRW, service_charge=500 KRW => collection_amount=15500 KRW.
- If m_a or m_b != 0 with is_same_currency=true, engine raises INVALID_MARGIN before computing.
- offer_rate_coll = 1.0 and cross_rate = 1.0 for all same-currency transactions.
- Short-circuit does not call treasury or Partner B quote client.
**Depends on:** 4.5-T01

### 4.5-T05 — Add same-currency short-circuit unit tests to RateEngineSameCurrencyTest  _(35 min)_
**Context:** WBS 4.5: verify the same-currency short-circuit produces the correct collection_amount and that all USD pool fields are null or zero. Key test vector (spec §7.2): GME Remit on ZeroPay KRW->KRW: target_payout=15000 KRW, service_charge=500 KRW, m_a=0, m_b=0 => collection_amount=15500 KRW. Additional vector: target_payout=10000 KRW, service_charge=500 KRW => collection_amount=10500 KRW. Edge case: service_charge=0 => collection_amount=target_payout. Error case: m_a=0.01 with is_same_currency=true => INVALID_MARGIN. KRW scale is 0 decimals; result must be a whole number.
**Steps:** Create test class RateEngineSameCurrencyTest.; Add test_short_circuit_gmе_remit_zeropay_15000: inputs target_payout=15000, service_charge=500, m_a=0, m_b=0, all currencies KRW; assert collection_amount=15500, collection_usd=null, payout_usd_cost=null, offer_rate_coll=1.0, cross_rate=1.0.; Add test_short_circuit_10000: same setup with target_payout=10000, service_charge=500; assert collection_amount=10500.; Add test_short_circuit_zero_service_charge: target_payout=20000, service_charge=0; assert collection_amount=20000.; Add test_short_circuit_rejects_nonzero_margin: target_payout=10000, service_charge=500, m_a=0.01, m_b=0; assert engine raises INVALID_MARGIN (not MARGIN_BELOW_MINIMUM).
**Deliverable:** RateEngineSameCurrencyTest class with 4 parameterised test cases for same-currency short-circuit.
**Acceptance / logic checks:**
- test_short_circuit_gmе_remit_zeropay_15000 passes: collection_amount=15500 KRW exactly.
- test_short_circuit_10000 passes: collection_amount=10500 KRW exactly.
- Zero service charge test: collection_amount equals target_payout exactly.
- Non-zero margin with is_same_currency=true raises INVALID_MARGIN, not any other error code.
- collection_margin_usd and payout_margin_usd are Decimal(0) (not null) in the returned RateQuote for same-currency transactions.
**Depends on:** 4.5-T04

### 4.5-T06 — Enforce m_a = m_b = 0 guard for same-currency rules in RateEngine guard block  _(25 min)_
**Context:** WBS 4.5: the pseudocode (spec §14) places the same-currency margin guard inside the short-circuit block: if is_same_currency AND (m_a != 0 OR m_b != 0) raise INVALID_MARGIN. This must be a runtime engine guard (not solely an admin-portal config-time guard), so that any misconfigured rule attempting to run with non-zero margins on a same-currency leg is caught before any computation. This ticket focuses only on the guard logic — the full short-circuit arithmetic is covered in 4.5-T04. The guard must fire BEFORE the cross-border margin floor check (m_a + m_b >= 0.02) so the error code is unambiguous.
**Steps:** In the engine guard block (before both the short-circuit and 5-step paths), add: if is_same_currency AND (m_a != 0 OR m_b != 0) raise INVALID_MARGIN with message SAME_CURRENCY_NONZERO_MARGIN.; Ensure this guard runs BEFORE the cross-border m_a+m_b >= 0.02 check to prevent a same-currency rule from inadvertently hitting MARGIN_BELOW_MINIMUM instead.; Add a guard test in the existing RateEngineSameCurrencyTest (or a new class): rule is_same_currency=true, m_a=0.005, m_b=0 => INVALID_MARGIN; also m_a=0, m_b=0.01 => INVALID_MARGIN.; Verify cross-border rules (is_same_currency=false) are NOT affected by this guard and still require m_a+m_b>=0.02.; Document the guard order in a brief inline comment.
**Deliverable:** Guard clause in RateEngine ensuring is_same_currency=true with any non-zero margin raises INVALID_MARGIN before cross-border floor check.
**Acceptance / logic checks:**
- is_same_currency=true, m_a=0.005, m_b=0 => raises INVALID_MARGIN.
- is_same_currency=true, m_a=0, m_b=0.01 => raises INVALID_MARGIN.
- is_same_currency=false, m_a=0.01, m_b=0.01 (cross-border) => does NOT raise INVALID_MARGIN here; proceeds to margin floor check.
- is_same_currency=false, m_a=0.005, m_b=0.005 (sum<0.02) => raises MARGIN_BELOW_MINIMUM (not INVALID_MARGIN).
- Guard order: SAME_CURRENCY_NONZERO_MARGIN check executes before MARGIN_BELOW_MINIMUM check in the method body.
**Depends on:** 4.5-T04

### 4.5-T07 — Verify pool identity assertion is bypassed for same-currency transactions  _(30 min)_
**Context:** WBS 4.5: the pool identity assertion (|collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| < 0.01 USD) is defined only for cross-border paths (spec §11.2). For same-currency short-circuit, collection_usd and payout_usd_cost are NULL — applying the assertion would cause a NullPointerException or false POOL_IDENTITY_FAILURE. This ticket ensures the assertion is scoped to the cross-border (else) branch only, and that a same-currency transaction never hits POOL_IDENTITY_FAILURE. Additionally verify that when cost_rate_coll = 1.0 (identity leg, cross-border), the assertion still holds because send_amount = collection_usd * 1.0 = collection_usd.
**Steps:** Locate the pool identity assertion in RateEngine.compute() (the check: |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| < 0.01).; Confirm it is inside the else (cross-border) branch only; if it is at the top level or in the shared path, move it inside the cross-border branch.; Add a test in RateEngineSameCurrencyTest: verify that compute() with is_same_currency=true does NOT raise POOL_IDENTITY_FAILURE even though collection_usd is null.; Add a test in RateEngineIdentityTest: cross-border with both legs IDENTITY (cost_rate_coll=1.0, cost_rate_pay=1.0) — pool identity assertion must pass (collection_usd - 2*margin = payout_usd_cost within 0.01 USD).; Document in a comment that pool identity is cross-border only.
**Deliverable:** Pool identity assertion scoped strictly to the cross-border branch, with tests confirming no false POOL_IDENTITY_FAILURE for same-currency transactions.
**Acceptance / logic checks:**
- Same-currency transaction with is_same_currency=true completes without POOL_IDENTITY_FAILURE.
- Cross-border identity-leg transaction (both rates=1.0, m_a=m_b=0.01): pool identity holds and assertion passes.
- Cross-border non-identity transaction: pool identity assertion still executes and catches a deliberately broken pool (inject bad value => POOL_IDENTITY_FAILURE).
- Pool identity assertion is not reachable from the short-circuit code path (static analysis / test coverage confirms it).
**Depends on:** 4.5-T04, 4.5-T03

### 4.5-T08 — Persist IDENTITY rate source in transaction rate-lock record  _(40 min)_
**Context:** WBS 4.5: at CommitTransaction (rate-lock), all USD pool values and derived rates are permanently written to the transaction record. For identity-leg transactions (spec §7.1), cost_rate_coll_source and cost_rate_pay_source must be stored as IDENTITY. For same-currency short-circuit (spec §7.2), both source fields are recorded as IDENTITY and USD pool fields (collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd) are NULL per the DB schema (DECIMAL(20,8) NULL). The offer_rate_coll and cross_rate fields are also NULL for same-currency (schema: Derived; NULL for same-ccy). This ticket wires the short-circuit and identity-source values from RateQuote into the rate-lock write path.
**Steps:** Locate the rate-lock write method (e.g. TransactionRepository.lockRates() or equivalent) that copies RateQuote fields to the transaction DB record.; Ensure the write includes cost_rate_coll_source and cost_rate_pay_source columns (enum string: IDENTITY, MANUAL, LIVE, PARTNER).; For same-currency transactions: write collection_usd=NULL, payout_usd_cost=NULL, collection_margin_usd=NULL, payout_margin_usd=NULL, offer_rate_coll=NULL, cross_rate=NULL per the DB schema spec.; For cross-border identity-leg transactions: write cost_rate_coll=1.0 or cost_rate_pay=1.0 with source=IDENTITY; USD pool fields are NOT null (they have computed values).; Add an integration or repository unit test: commit a same-currency transaction and assert all NULL columns are actually NULL in the stored record; commit a dual-identity-leg cross-border transaction and assert cost_rate_coll=1.0 and source=IDENTITY.
**Deliverable:** Updated rate-lock write path that persists IDENTITY source flags and correct NULL values for same-currency transactions.
**Acceptance / logic checks:**
- After committing a same-currency transaction: collection_usd IS NULL, payout_usd_cost IS NULL, offer_rate_coll IS NULL, cross_rate IS NULL in DB.
- After committing a same-currency transaction: cost_rate_coll_source = IDENTITY and cost_rate_pay_source = IDENTITY in DB.
- After committing a cross-border dual-identity transaction: collection_usd is NOT NULL (has computed value); cost_rate_coll_source = IDENTITY.
- After committing a cross-border non-identity transaction: source columns reflect MANUAL or LIVE (not IDENTITY).
- Rate-lock is immutable: re-reading the transaction after a subsequent treasury rate change still returns the original locked rates.
**Depends on:** 4.5-T04, 4.5-T02

### 4.5-T09 — Validate same-currency margin constraint at rule configuration save time  _(45 min)_
**Context:** WBS 4.5 / spec §11.3: at rule-save time in the Admin Service backend (not rate-engine runtime), enforce: (a) cross-border rules: m_a + m_b >= 2.0% (0.02); (b) same-currency rules: m_a = 0 AND m_b = 0. The same-currency check is determined by whether collection_ccy == settle_a_ccy == settle_b_ccy == payout_ccy on the rule being saved. Auto-enforce (not just validate) for same-currency rules: if the rule resolves as same-currency, forcibly set m_a=0 and m_b=0 before saving, regardless of operator input. Also set cost_rate_coll_source = IDENTITY and cost_rate_pay_source = IDENTITY automatically. Return a 422 with error code MARGIN_BELOW_MINIMUM if a cross-border rule has m_a+m_b < 0.02.
**Steps:** In the RuleSaveService (or equivalent backend handler for POST/PUT /admin/rules), after currency fields are resolved, compute is_same_currency = (collection_ccy == settle_a_ccy == settle_b_ccy == payout_ccy).; If is_same_currency = true: override m_a = 0, m_b = 0; set cost_rate_coll_source = IDENTITY and cost_rate_pay_source = IDENTITY; do not raise an error (auto-enforce).; If is_same_currency = false (cross-border): validate m_a + m_b >= 0.02; if not, return HTTP 422 with error code MARGIN_BELOW_MINIMUM.; Persist the (possibly overridden) rule and write an audit log entry recording the actor, timestamp, old values, and new values.; Add unit tests: (a) save KRW/KRW same-currency rule with m_a=0.01 => saved with m_a=0, m_b=0, sources=IDENTITY; (b) save cross-border rule with m_a=0.005, m_b=0.005 => 422 MARGIN_BELOW_MINIMUM; (c) save cross-border with m_a=0.01, m_b=0.01 => saves successfully.
**Deliverable:** RuleSaveService validation and auto-enforcement of margin constraints with unit tests and audit log.
**Acceptance / logic checks:**
- Saving a KRW/KRW same-currency rule with any non-zero m_a or m_b auto-corrects both to 0 and saves (no error).
- Saving a cross-border rule with m_a+m_b = 0.019 returns 422 MARGIN_BELOW_MINIMUM.
- Saving a cross-border rule with m_a+m_b = 0.02 succeeds.
- Audit log entry is created with old and new margin values when auto-enforcement overrides operator input.
- cost_rate_coll_source and cost_rate_pay_source are set to IDENTITY automatically for same-currency rules.
**Depends on:** 4.5-T01

### 4.5-T10 — Integration test: end-to-end same-currency short-circuit for GME Remit on ZeroPay  _(50 min)_
**Context:** WBS 4.5: full end-to-end integration test from GET /v1/rates through POST /v1/payments for a same-currency (Domestic KRW->KRW) transaction. GME Remit is a LOCAL partner (no prefunding deduction). Rule: collection_ccy=KRW, settle_a_ccy=KRW, settle_b_ccy=KRW, payout_ccy=KRW, m_a=0, m_b=0, service_charge=500 KRW. Test vector: target_payout=10000 KRW => collection_amount=10500 KRW. The /v1/rates response must omit USD pool fields (or return them as null/absent). POST /v1/payments must lock rates with IDENTITY sources and null USD pool columns. No prefunding ledger deduction must occur (LOCAL partner).
**Steps:** Set up an integration test with in-memory or test-container DB; seed GME Remit partner (type=LOCAL) and the KRW/KRW/KRW/KRW rule with service_charge=500.; Call GET /v1/rates with partner_id=gme_remit, scheme=zeropay, direction=DOMESTIC, target_payout=10000, payout_ccy=KRW.; Assert response: collection_amount=10500, collection_usd=absent/null, payout_usd_cost=absent/null, offer_rate_coll=1.0 or absent, cross_rate=1.0 or absent, validUntil present.; Call POST /v1/payments with the returned txn_ref and a valid ZeroPay CPM/MPM payload; assert HTTP 200 and transaction state = COMPLETED (or SCHEME_CALLED per state machine).; Assert DB: collection_usd IS NULL, payout_usd_cost IS NULL, cost_rate_coll_source = IDENTITY, cost_rate_pay_source = IDENTITY, collection_amount = 10500, prefunding_deducted = false / no ledger row for this partner.
**Deliverable:** Integration test class RateSameCurrencyE2ETest covering full GET /rates + POST /payments flow for the domestic KRW short-circuit case.
**Acceptance / logic checks:**
- GET /v1/rates returns collection_amount=10500 KRW for target_payout=10000, service_charge=500.
- GET /v1/rates response does not include collection_usd or payout_usd_cost fields (or returns them as null/absent per API contract).
- POST /v1/payments completes without POOL_IDENTITY_FAILURE or INVALID_MARGIN errors.
- DB record after commit: collection_usd IS NULL, cost_rate_coll_source = IDENTITY.
- No prefunding ledger deduction is recorded for GME Remit (LOCAL partner type).
**Depends on:** 4.5-T04, 4.5-T07, 4.5-T08, 4.5-T09

### 4.5-T11 — Integration test: end-to-end dual-identity-leg cross-border transaction (HUB USD/USD)  _(50 min)_
**Context:** WBS 4.5: verify that a HUB-direction USD-in / USD-out transaction (both identity legs) runs the full 5-step engine with cost_rate_coll=1.0, cost_rate_pay=1.0 (IDENTITY), non-zero margins, and USD pool fields populated. This is distinct from same-currency short-circuit — the USD pool IS computed, and m_a/m_b apply normally. Test vector from spec §7.1: OVERSEAS partner, target_payout=100.00 USD, m_a=0.01, m_b=0.01, service_charge=0.50 USD; expected collection_amount=102.5408 USD, collection_usd=102.0408, offer_rate_coll=1.01010, cross_rate=0.98000. Prefunding deduction must occur (OVERSEAS partner).
**Steps:** Seed an OVERSEAS partner with USD settlement on both legs and a HUB rule: settle_a_ccy=USD, settle_b_ccy=USD, m_a=0.01, m_b=0.01, service_charge=0.50 USD, cost_rate_coll_source=IDENTITY, cost_rate_pay_source=IDENTITY.; Fund the partner prefunding balance with 200.00 USD.; Call GET /v1/rates with target_payout=100.00, payout_ccy=USD; assert collection_amount=102.5408, collection_usd=102.0408, offer_rate_coll=1.01010 (tolerance 0.00001), cross_rate=0.98000.; Call POST /v1/payments; assert transaction completes and prefunding balance is debited by 102.5408 USD.; Assert DB: collection_usd=102.0408, cost_rate_coll_source=IDENTITY, cost_rate_pay_source=IDENTITY; pool identity |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| < 0.01.
**Deliverable:** Integration test class RateIdentityLegE2ETest covering GET /rates + POST /payments for a dual-IDENTITY-leg HUB cross-border case.
**Acceptance / logic checks:**
- GET /v1/rates returns collection_amount=102.5408 USD (tolerance 0.0001) and collection_usd=102.0408.
- offer_rate_coll=1.01010 and cross_rate=0.98000 within 0.00001 tolerance.
- Prefunding balance decremented by 102.5408 USD (not 100 USD).
- DB: cost_rate_coll_source=IDENTITY, cost_rate_pay_source=IDENTITY, collection_usd IS NOT NULL.
- Pool identity check in DB: |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| < 0.01.
**Depends on:** 4.5-T02, 4.5-T03, 4.5-T08


## WBS 4.6 — Partner B authoritative quote & deviation
### 4.6-T01 — Define PartnerBQuoteClient interface and PartnerBQuoteResult value type  _(30 min)_
**Context:** WBS 4.6: Partner B authoritative quote. When a scheme rule has rate_source_pay = PARTNER, the Rate Engine must call Partner B's quote API synchronously at both /rates time and /payments (commit) time. The interface must be mockable for unit tests. The response carries the quoted cost_rate_pay value (units of Settle B per 1 USD, e.g. 1350.00 KRW/USD) plus an optional quote_valid_until timestamp returned by Partner B. Errors are typed: PARTNER_B_QUOTE_UNAVAILABLE for any network/timeout failure, never a fallback.
**Steps:** Create interface PartnerBQuoteClient with method: fetchQuote(schemeId: String, payoutAmount: BigDecimal, payoutCurrency: String): PartnerBQuoteResult; Define sealed result type PartnerBQuoteResult with two subtypes: PartnerBQuoteSuccess(costRatePay: BigDecimal, quoteValidUntil: Instant?) and PartnerBQuoteUnavailable(reason: String); Place interface in package com.gmepay.rateengine.partnerbquote; Add Javadoc: fetchQuote is synchronous; callers must treat any exception as UNAVAILABLE; never returns null; Write a trivial stub implementation (StubPartnerBQuoteClient) that returns a configurable PartnerBQuoteSuccess for use in unit tests
**Deliverable:** Interface PartnerBQuoteClient + sealed result types PartnerBQuoteResult, PartnerBQuoteSuccess, PartnerBQuoteUnavailable + StubPartnerBQuoteClient in com.gmepay.rateengine.partnerbquote
**Acceptance / logic checks:**
- fetchQuote signature accepts schemeId (String), payoutAmount (BigDecimal), payoutCurrency (String) and returns PartnerBQuoteResult
- PartnerBQuoteSuccess.costRatePay is BigDecimal (never double/float)
- PartnerBQuoteUnavailable carries a non-null reason string
- StubPartnerBQuoteClient can be configured to return either subtype; compiles and passes a trivial instantiation test
- No dependency on HTTP client libraries in the interface itself (only in the impl)
**Depends on:** 4.1, 4.2

### 4.6-T02 — Add partner_b_quote_enabled and partner_b_quote_deviation_pct to scheme config entity  _(40 min)_
**Context:** WBS 4.6: Scheme-level config controls whether Partner B quote is active. Two fields already defined in DAT-03 schema: partner_b_quote_enabled BOOLEAN (default false) and partner_b_quote_deviation_pct DECIMAL(6,4) (default 0.0100 representing 1.0%). These fields live on the Scheme entity/table (same table that holds api_endpoint, api_credential_ref, sftp_host, etc.). The deviation_pct field stores the fractional tolerance: 0.0100 = 1.0%, 0.0050 = 0.5%. Config changes must log actor, timestamp, and previous value per audit rules.
**Steps:** Add columns partner_b_quote_enabled BOOLEAN NOT NULL DEFAULT FALSE and partner_b_quote_deviation_pct DECIMAL(6,4) NOT NULL DEFAULT 0.0100 to the schemes table via a Flyway migration file V4_6_001__scheme_partner_b_quote_config.sql; Add the two fields to the Scheme JPA entity/domain object with appropriate types (boolean, BigDecimal); Add validation: partner_b_quote_deviation_pct must be in range (0, 0.1000] (0% exclusive, 10% inclusive); reject at service layer with IllegalArgumentException if out of range; Ensure the Scheme config audit log captures old and new values for both fields when updated; Write a unit test: default values are 0.0100 and false; out-of-range 0.0000 and 0.1100 are rejected; 0.0050 is accepted
**Deliverable:** Flyway migration V4_6_001__scheme_partner_b_quote_config.sql + updated Scheme entity + validation + unit test SchemePartnerBQuoteConfigTest
**Acceptance / logic checks:**
- Migration applies cleanly; both columns present with correct defaults on a fresh schema
- Scheme entity fields partner_b_quote_enabled and partner_b_quote_deviation_pct are correctly typed
- Deviation 0.0100 (1.0%) is accepted; 0.0000 is rejected; 0.1100 is rejected; 0.0050 is accepted
- Audit log records previous and new values of both fields when a Scheme is updated
**Depends on:** 4.6-T01

### 4.6-T03 — Implement HTTP PartnerBQuoteClient with configurable timeout  _(50 min)_
**Context:** WBS 4.6: The HTTP implementation calls Partner B's quote API. The scheme record has api_endpoint (e.g. https://partnerbscheme.example/quote) and api_credential_ref (vault reference). The call is synchronous (short-timeout per spec §7.5). Spec does not define a standard HTTP timeout value for PBQ; use a configurable property partner_b_quote_http_timeout_ms (default 3000 ms). Request: GET {api_endpoint}/rates?payout_amount={amount}&payout_currency={currency}. Response: JSON with field cost_rate_pay (numeric string) and optional quote_valid_until (ISO-8601 UTC). Any non-2xx, connection error, or elapsed timeout returns PartnerBQuoteUnavailable (no retry, no fallback).
**Steps:** Create HttpPartnerBQuoteClient implementing PartnerBQuoteClient; inject scheme api_endpoint + resolved credential + timeout config; Configure HTTP client with connect-timeout = partner_b_quote_http_timeout_ms and read-timeout = partner_b_quote_http_timeout_ms (property, default 3000 ms); Parse JSON response: extract cost_rate_pay as BigDecimal; parse optional quote_valid_until as Instant; return PartnerBQuoteSuccess; On any IOException, SocketTimeoutException, non-2xx status, or JSON parse error: log WARN with scheme_id and return PartnerBQuoteUnavailable(reason); Add Spring @ConfigurationProperties class PartnerBQuoteProperties with field httpTimeoutMs (int, default 3000)
**Deliverable:** HttpPartnerBQuoteClient + PartnerBQuoteProperties in com.gmepay.rateengine.partnerbquote.http
**Acceptance / logic checks:**
- HttpTimeoutMs defaults to 3000; can be overridden via application.properties partner_b_quote.http_timeout_ms
- cost_rate_pay parsed as BigDecimal (not double); value 1350.00 from JSON produces BigDecimal(1350.00)
- SocketTimeoutException produces PartnerBQuoteUnavailable, not a thrown exception
- Non-2xx HTTP 503 produces PartnerBQuoteUnavailable with non-empty reason string
- quote_valid_until absent in JSON produces PartnerBQuoteSuccess with quoteValidUntil = null (no NPE)
**Depends on:** 4.6-T01, 4.6-T02

### 4.6-T04 — Integrate PartnerBQuoteClient into Rate Engine quote-time path  _(45 min)_
**Context:** WBS 4.6: At /rates request time, if the rule has rate_source_pay = PARTNER (and scheme.partner_b_quote_enabled = true), the Rate Engine must call PartnerBQuoteClient.fetchQuote(schemeId, targetPayout, payoutCurrency) instead of reading treasury.usd_{settle_b_ccy} for cost_rate_pay. The returned costRatePay value is used in Step 1 of the 5-step USD pool: payout_usd_cost = target_payout / cost_rate_pay. The RateQuote snapshot must record cost_rate_pay_source = PARTNER and the raw quote value. If PartnerBQuoteUnavailable is returned, the Rate Engine immediately returns PARTNER_B_QUOTE_UNAVAILABLE to the caller (no treasury fallback, no partial quote).
**Steps:** In RateEngineService.computeRateQuote, after resolving the Rule, check if rate_source_pay == PARTNER; If PARTNER: call partnerBQuoteClient.fetchQuote(schemeId, targetPayout, payoutCurrency); if result is PartnerBQuoteUnavailable return error PARTNER_B_QUOTE_UNAVAILABLE immediately; If PartnerBQuoteSuccess: use costRatePay as cost_rate_pay for Step 1; record cost_rate_pay_source = PARTNER in the RateQuote object; Also record partner_b_quote_value (the raw quote) and partner_b_quote_valid_until on the RateQuote for later deviation check; Ensure RateQuote now carries fields: cost_rate_pay_source (enum IDENTITY/LIVE/MANUAL/PARTNER), partner_b_quote_value (BigDecimal nullable), partner_b_quote_valid_until (Instant nullable)
**Deliverable:** Updated RateEngineService.computeRateQuote integration branch for PARTNER source + extended RateQuote value object
**Acceptance / logic checks:**
- When rate_source_pay = PARTNER and client returns PartnerBQuoteSuccess(1350.00), RateQuote.cost_rate_pay = 1350.00 and cost_rate_pay_source = PARTNER
- When rate_source_pay = PARTNER and client returns PartnerBQuoteUnavailable, method returns error PARTNER_B_QUOTE_UNAVAILABLE without proceeding to USD pool steps
- When rate_source_pay != PARTNER, partnerBQuoteClient.fetchQuote is never called
- RateQuote.partner_b_quote_value = 1350.00 is persisted in cache for later deviation check at commit time
- No treasury lookup for cost_rate_pay occurs when rate_source_pay = PARTNER
**Depends on:** 4.6-T01, 4.6-T02, 4.6-T03

### 4.6-T05 — Implement Partner B quote deviation check at commit time  _(50 min)_
**Context:** WBS 4.6: At POST /v1/payments (commit) time, if RateQuote.cost_rate_pay_source == PARTNER, the Rate Engine calls PartnerBQuoteClient.fetchQuote again to get the current quote. Deviation formula: deviation = |commit_quote - rates_quote| / rates_quote. If deviation > partner_b_quote_deviation_pct (default 0.0100 = 1.0%), raise PARTNER_B_QUOTE_DEVIATION and do NOT commit. If client returns PartnerBQuoteUnavailable, raise PARTNER_B_QUOTE_UNAVAILABLE and do NOT commit. No fallback in either case. RV-05 (spec): rates_quote=1350.00, commit_quote=1360.80 => deviation=0.008=0.80% < 1.0% => COMMIT. RV-06: rates_quote=1350.00, commit_quote=1366.20 => deviation=0.012=1.2% > 1.0% => DEVIATION error.
**Steps:** In TransactionOrchestrator.commitTransaction (or RateEngineService.onCommit), after expiry check, check if rateQuote.cost_rate_pay_source == PARTNER; Call partnerBQuoteClient.fetchQuote(schemeId, targetPayout, payoutCurrency); if PartnerBQuoteUnavailable, throw RateEngineException(PARTNER_B_QUOTE_UNAVAILABLE) without touching DB; If PartnerBQuoteSuccess: compute deviation = abs(commitQuote - ratesQuote) / ratesQuote using BigDecimal arithmetic; Load tolerance = scheme.partner_b_quote_deviation_pct; if deviation > tolerance throw RateEngineException(PARTNER_B_QUOTE_DEVIATION) without touching DB; If deviation <= tolerance: update transaction.cost_rate_pay = commitQuote, cost_rate_pay_source = PARTNER, and proceed to rate-lock commit
**Deliverable:** Deviation check logic in TransactionOrchestrator.commitTransaction + RateEngineException with typed error codes
**Acceptance / logic checks:**
- RV-05: rates_quote=1350.00, commit_quote=1360.80, tolerance=0.0100 => deviation=0.008 => transaction commits; recorded cost_rate_pay=1360.80
- RV-06: rates_quote=1350.00, commit_quote=1366.20, tolerance=0.0100 => deviation=0.012 => throws PARTNER_B_QUOTE_DEVIATION; no DB write occurs
- Commit-time PartnerBQuoteUnavailable => throws PARTNER_B_QUOTE_UNAVAILABLE; no DB write occurs
- Boundary: deviation exactly = tolerance (e.g. exactly 0.0100 with rates_quote=1350.00, commit_quote=1363.50) => commits (deviation not strictly greater than tolerance)
- When cost_rate_pay_source != PARTNER, no second fetchQuote call is made at commit time
**Depends on:** 4.6-T04

### 4.6-T06 — Enforce no-fallback invariant: block any treasury cost_rate_pay path when source = PARTNER  _(35 min)_
**Context:** WBS 4.6: Spec is explicit: if Partner B API is unreachable, there is NO fallback to treasury rates. The code must have no code path that substitutes treasury.usd_{settle_b_ccy} when rate_source_pay = PARTNER and the PBQ client fails. This ticket adds a guard assertion in RateEngineService to make the no-fallback invariant explicit and testable. Also verifies that treasury.getCostRate(settle_b_ccy) is never called when rate_source_pay = PARTNER.
**Steps:** In the PARTNER branch of RateEngineService.computeRateQuote, add an explicit guard: after the UNAVAILABLE early return, assert that cost_rate_pay was NOT sourced from treasury (i.e., do not invoke treasury lookup for settle_b at all in this branch); Add a test with a mock TreasuryService that throws AssertionError if getCostRate is called for settle_b_ccy when rate_source_pay = PARTNER; Verify the same invariant at commit time: if rate_source_pay = PARTNER and PBQ unavailable, cost_rate_pay from the stored quote is never used as fallback; Add a log.error entry at CRITICAL level when PARTNER_B_QUOTE_UNAVAILABLE is returned, including scheme_id and timestamp (for ops monitoring); Document the no-fallback rule in a brief code comment co-located with the PARTNER branch
**Deliverable:** No-fallback guard assertion + test NoFallbackInvariantTest verifying treasury is never consulted for pay-leg when source = PARTNER
**Acceptance / logic checks:**
- Test NoFallbackInvariantTest: TreasuryService.getCostRate(settle_b_ccy) is never called when rate_source_pay = PARTNER (mock verifies zero interactions on that method)
- PARTNER_B_QUOTE_UNAVAILABLE at /rates time: no treasury substitution; error returned immediately
- PARTNER_B_QUOTE_UNAVAILABLE at commit time: no treasury substitution; no DB write; error returned immediately
- CRITICAL log entry emitted on UNAVAILABLE with scheme_id present in log fields
**Depends on:** 4.6-T05

### 4.6-T07 — Bound RateQuote validUntil by Partner B quote_valid_until timestamp  _(30 min)_
**Context:** WBS 4.6: Spec §9.1 states: for transactions with a Partner B authoritative quote, validUntil is also bounded by Partner B's quote validity timestamp (whichever is earlier). RateQuote.valid_until = min(quote_issued_at + rate_quote_ttl_seconds, partnerBQuote.quoteValidUntil). If partnerBQuote.quoteValidUntil is null (Partner B did not return an expiry), use only the GMEPay+ TTL. The RateQuoteResponse returned to the partner must reflect the tighter bound so the partner knows the true commit deadline.
**Steps:** In RateEngineService after computing valid_until = quote_issued_at + ttl_seconds, check if rateQuote.partner_b_quote_valid_until is non-null; If non-null: valid_until = min(computed_valid_until, partner_b_quote_valid_until) using Instant comparison; Set rateQuote.valid_until to the result and surface it in RateQuoteResponse.validUntil (ISO-8601 UTC string); Write unit test: TTL-based expiry = T+300s, partnerB expiry = T+120s => validUntil = T+120s; Write unit test: TTL-based expiry = T+300s, partnerB expiry = null => validUntil = T+300s
**Deliverable:** Updated valid_until assignment in RateEngineService + unit tests PartnerBValidUntilBoundTest
**Acceptance / logic checks:**
- When partnerB quoteValidUntil = T+120s and TTL expiry = T+300s, RateQuoteResponse.validUntil = T+120s
- When partnerB quoteValidUntil = T+600s and TTL expiry = T+300s, RateQuoteResponse.validUntil = T+300s
- When partnerB quoteValidUntil = null, validUntil = quote_issued_at + rate_quote_ttl_seconds unchanged
- validUntil in API response is ISO-8601 UTC (ends with Z); no local-time zone applied
**Depends on:** 4.6-T04

### 4.6-T08 — Persist cost_rate_pay_source = PARTNER on committed transaction record  _(40 min)_
**Context:** WBS 4.6: Rate-lock spec (§9 and pseudocode) requires all USD-pool values and derived rates to be permanently recorded on commit. For Partner B transactions the recorded cost_rate_pay = the commit-time Partner B quote value (not the /rates-time quote), and cost_rate_pay_source = PARTNER must be stored so the audit trail and BOK reporting can identify the source. Depends on 4.6-T05 which already updates cost_rate_pay at commit time. This ticket ensures the source enum column exists in the transactions table and is written correctly.
**Steps:** Add Flyway migration V4_6_002__txn_cost_rate_pay_source.sql: add column cost_rate_pay_source VARCHAR(10) NULL to transactions table (values: IDENTITY, LIVE, MANUAL, PARTNER); Add field to Transaction entity/domain object; populate from RateQuote.cost_rate_pay_source at commit time; Ensure existing IDENTITY-path transactions set cost_rate_pay_source = IDENTITY on commit (backward-compatible default); Write integration test: commit a PARTNER-source transaction; assert transactions.cost_rate_pay_source = PARTNER and cost_rate_pay = commit-time quote value; Confirm the column is excluded from partner-facing API response fields (internal field per spec §8.3)
**Deliverable:** Migration V4_6_002__txn_cost_rate_pay_source.sql + updated Transaction entity + integration test CostRatePaySourcePersistenceTest
**Acceptance / logic checks:**
- After PARTNER-source commit with commit_quote=1360.80: transactions.cost_rate_pay=1360.80 and transactions.cost_rate_pay_source=PARTNER
- After IDENTITY-source commit: transactions.cost_rate_pay_source=IDENTITY
- cost_rate_pay_source column not present in any partner-facing /payments or /rates response payload
- Migration is idempotent (re-run safe via Flyway checksum)
**Depends on:** 4.6-T05

### 4.6-T09 — Unit tests: PartnerBQuoteClient mock — within-tolerance commit succeeds (RV-05)  _(35 min)_
**Context:** WBS 4.6 test ticket. Spec test vector RV-05: target_payout=13500 KRW, cost_rate_coll=3500.00 MNT/USD, rates_time cost_rate_pay=1350.00 KRW/USD (PARTNER), m_a=0.015, m_b=0.010, service_charge=500 MNT. At commit, Partner B returns 1360.80 (deviation=|1360.80-1350.00|/1350.00=0.008=0.8%, within 1.0% tolerance). Expected: transaction commits; recorded cost_rate_pay=1360.80; cost_rate_pay_source=PARTNER. Rate engine intermediate values: payout_usd_cost=13500/1360.80=9.9206 USD; collection_usd=9.9206/(1-0.025)=10.1750 USD; collection_margin_usd=10.1750*0.015=0.1526 USD; payout_margin_usd=10.1750*0.010=0.1018 USD; pool identity: 10.1750-0.1526-0.1018=9.9206 (within 0.01 USD).
**Steps:** Create test class PartnerBQuoteRV05Test using StubPartnerBQuoteClient; Configure stub: /rates call returns PartnerBQuoteSuccess(1350.00); commit call returns PartnerBQuoteSuccess(1360.80); Invoke RateEngineService.computeRateQuote with the RV-05 inputs; assert all intermediate USD-pool values; Invoke TransactionOrchestrator.commitTransaction; assert no exception thrown; Assert transaction.cost_rate_pay=1360.80, cost_rate_pay_source=PARTNER, transaction is in COMMITTED state
**Deliverable:** Test class PartnerBQuoteRV05Test (unit, mocked client)
**Acceptance / logic checks:**
- computeRateQuote succeeds with rates_quote=1350.00; rateQuote.partner_b_quote_value=1350.00
- commitTransaction succeeds with commit_quote=1360.80; no DEVIATION exception
- Recorded cost_rate_pay=1360.80 (BigDecimal.compareTo == 0)
- Pool identity assertion: |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| <= 0.01 USD
- cost_rate_pay_source field = PARTNER on committed record
**Depends on:** 4.6-T05, 4.6-T08

### 4.6-T10 — Unit tests: PartnerBQuoteClient mock — over-tolerance commit raises DEVIATION, no commit (RV-06)  _(35 min)_
**Context:** WBS 4.6 test ticket. Spec test vector RV-06: same inputs as RV-05 except commit-time Partner B quote=1366.20. deviation=|1366.20-1350.00|/1350.00=0.012=1.2% which exceeds 1.0% tolerance. Expected: PARTNER_B_QUOTE_DEVIATION returned; transaction NOT committed; no prefunding deduction; no DB write. The test must verify that the transaction state remains unchanged (e.g. PENDING, not COMMITTED).
**Steps:** Create test class PartnerBQuoteRV06Test using StubPartnerBQuoteClient; Configure stub: /rates call returns PartnerBQuoteSuccess(1350.00); commit call returns PartnerBQuoteSuccess(1366.20); Invoke computeRateQuote (should succeed); capture rateQuote; Invoke commitTransaction; assert RateEngineException with code PARTNER_B_QUOTE_DEVIATION is thrown; Assert: no DB write to transactions table; prefunding balance unchanged; transaction state != COMMITTED
**Deliverable:** Test class PartnerBQuoteRV06Test (unit, mocked client)
**Acceptance / logic checks:**
- commitTransaction throws RateEngineException with errorCode = PARTNER_B_QUOTE_DEVIATION
- Transaction record is NOT written / state != COMMITTED
- Prefunding deduction NOT triggered (verify mock PrefundingService.deduct never called)
- Deviation math: |1366.20 - 1350.00| / 1350.00 = 0.012 asserted in test comments
- Boundary case: commit_quote=1363.50 => deviation=0.01000 exactly => commits (not strictly greater than tolerance)
**Depends on:** 4.6-T05, 4.6-T08

### 4.6-T11 — Unit tests: PartnerBQuoteClient mock — timeout at /rates time raises UNAVAILABLE, no quote issued  _(30 min)_
**Context:** WBS 4.6 test ticket. Spec: if Partner B quote API is unreachable at /rates time, return PARTNER_B_QUOTE_UNAVAILABLE; no fallback; no RateQuote issued. Test: stub configured to return PartnerBQuoteUnavailable at /rates time. Expected: computeRateQuote returns PARTNER_B_QUOTE_UNAVAILABLE immediately; no USD pool computation performed; TreasuryService.getCostRate(settle_b_ccy) never called; no quote persisted in cache.
**Steps:** Create test class PartnerBQuoteUnavailableAtRatesTimeTest using StubPartnerBQuoteClient; Configure stub: fetchQuote always returns PartnerBQuoteUnavailable('connection timeout'); Invoke computeRateQuote; assert RateEngineException with code PARTNER_B_QUOTE_UNAVAILABLE; Verify mock TreasuryService.getCostRate was NOT called for settle_b_ccy (Mockito verify zero interactions); Verify no RateQuote object was stored in Redis cache (mock CacheService.store never called)
**Deliverable:** Test class PartnerBQuoteUnavailableAtRatesTimeTest (unit, mocked client)
**Acceptance / logic checks:**
- computeRateQuote throws RateEngineException with code PARTNER_B_QUOTE_UNAVAILABLE
- TreasuryService.getCostRate not called for settle_b_ccy (no fallback)
- No quote stored in cache
- CRITICAL log message emitted with scheme_id
- Exception propagates to API gateway which maps it to HTTP 503 with error code PARTNER_B_QUOTE_UNAVAILABLE
**Depends on:** 4.6-T06

### 4.6-T12 — Unit tests: PartnerBQuoteClient mock — timeout at commit time raises UNAVAILABLE, no commit  _(30 min)_
**Context:** WBS 4.6 test ticket. Spec: if Partner B quote API is unreachable at commit time (/payments), return PARTNER_B_QUOTE_UNAVAILABLE; no commit; no prefunding deduction. Test: /rates succeeds with PartnerBQuoteSuccess(1350.00); commit-time call returns PartnerBQuoteUnavailable. Expected: commitTransaction raises PARTNER_B_QUOTE_UNAVAILABLE; transaction not written; prefunding balance unchanged.
**Steps:** Create test class PartnerBQuoteUnavailableAtCommitTimeTest using StubPartnerBQuoteClient; Configure stub: first fetchQuote call (rates time) returns PartnerBQuoteSuccess(1350.00); second call (commit time) returns PartnerBQuoteUnavailable('read timeout'); Invoke computeRateQuote (succeeds); invoke commitTransaction; Assert RateEngineException with code PARTNER_B_QUOTE_UNAVAILABLE from commitTransaction; Assert: no DB write; PrefundingService.deduct never called; transaction state != COMMITTED
**Deliverable:** Test class PartnerBQuoteUnavailableAtCommitTimeTest (unit, mocked client)
**Acceptance / logic checks:**
- commitTransaction throws RateEngineException with errorCode = PARTNER_B_QUOTE_UNAVAILABLE
- No prefunding deduction (mock PrefundingService.deduct called zero times)
- Transaction NOT written to DB
- rates-time quote itself was valid; only commit-time call failed
- StubPartnerBQuoteClient returns different values for sequential calls (rates vs commit)
**Depends on:** 4.6-T05, 4.6-T06

### 4.6-T13 — Map PARTNER_B_QUOTE_DEVIATION and PARTNER_B_QUOTE_UNAVAILABLE to API error response codes  _(35 min)_
**Context:** WBS 4.6 / API-05: Both errors must be surfaced to Partner A via the standard error response envelope. Per spec error catalog: PARTNER_B_QUOTE_DEVIATION = HTTP 422 (commit-time quote deviated beyond tolerance, retry from POST /v1/rates); PARTNER_B_QUOTE_UNAVAILABLE = HTTP 503 (Partner B API unreachable, retry after delay). The error response body must include: error_code (string), message (human-readable), and must NOT include internal fields (m_a, m_b, cost_rate_coll, cost_rate_pay, partner_b_quote_value). These codes appear in responses to both POST /v1/rates and POST /v1/payments.
**Steps:** In the API gateway error-mapping layer, add cases for RateEngineException(PARTNER_B_QUOTE_DEVIATION) => HTTP 422 body {error_code: PARTNER_B_QUOTE_DEVIATION, message: 'Partner B commit-time quote deviated beyond configured tolerance. Re-quote and retry.'}; Add case for RateEngineException(PARTNER_B_QUOTE_UNAVAILABLE) => HTTP 503 body {error_code: PARTNER_B_QUOTE_UNAVAILABLE, message: 'Partner B quote API unreachable. Retry after delay.'}; Ensure no internal fields (cost_rate_pay, partner_b_quote_value, m_a, m_b) leak into the error response body; Write a controller-layer test asserting the HTTP status codes and error_code strings; Confirm the two codes appear in the OpenAPI spec / error catalog for API-05
**Deliverable:** Error mapping entries in API gateway layer + controller tests ApiErrorMappingPartnerBTest
**Acceptance / logic checks:**
- PARTNER_B_QUOTE_DEVIATION => HTTP 422; response body contains error_code = PARTNER_B_QUOTE_DEVIATION
- PARTNER_B_QUOTE_UNAVAILABLE => HTTP 503; response body contains error_code = PARTNER_B_QUOTE_UNAVAILABLE
- Error response body does NOT contain any of: cost_rate_pay, partner_b_quote_value, m_a, m_b
- Both errors can occur on both POST /v1/rates and POST /v1/payments endpoints
- Error message is human-readable and references retry action
**Depends on:** 4.6-T05, 4.6-T06

### 4.6-T14 — Wire PartnerBQuoteClient bean via Spring config, guarded by partner_b_quote_enabled flag  _(40 min)_
**Context:** WBS 4.6: HttpPartnerBQuoteClient must be registered as a Spring bean and injected into RateEngineService. The client should only be invoked when scheme.partner_b_quote_enabled = true AND rule.rate_source_pay = PARTNER. If partner_b_quote_enabled = false on the scheme, the PARTNER rate_source_pay config is invalid and should be rejected at rule-save time (configuration guard, not runtime). A NoOpPartnerBQuoteClient that always returns UNAVAILABLE should be registered as a fallback bean for safety in environments where the HTTP impl is disabled.
**Steps:** Create Spring @Configuration class PartnerBQuoteClientConfig that registers HttpPartnerBQuoteClient as primary bean with properties injected from PartnerBQuoteProperties; Register NoOpPartnerBQuoteClient (always returns UNAVAILABLE) as @ConditionalOnMissingBean fallback; In RuleValidationService, add check: if rate_source_pay = PARTNER and scheme.partner_b_quote_enabled = false, reject with validation error 'Partner B quote not enabled for this scheme'; Write test: saving a rule with rate_source_pay=PARTNER on a scheme with partner_b_quote_enabled=false is rejected at config time; Write test: saving a rule with rate_source_pay=PARTNER on a scheme with partner_b_quote_enabled=true is accepted
**Deliverable:** PartnerBQuoteClientConfig Spring config + NoOpPartnerBQuoteClient + RuleValidationService guard + tests PartnerBQuoteWiringTest
**Acceptance / logic checks:**
- HttpPartnerBQuoteClient bean is registered and injectable in Spring context
- Rule creation with rate_source_pay=PARTNER and partner_b_quote_enabled=false fails with validation error at save time
- Rule creation with rate_source_pay=PARTNER and partner_b_quote_enabled=true succeeds
- NoOpPartnerBQuoteClient is never reached in normal runtime (only if HttpPartnerBQuoteClient bean missing)
- PartnerBQuoteProperties.httpTimeoutMs is read from application.properties correctly
**Depends on:** 4.6-T03, 4.6-T04

### 4.6-T15 — Integration smoke test: end-to-end PARTNER-source rate quote and commit with mocked Partner B HTTP server  _(55 min)_
**Context:** WBS 4.6 final integration test. Brings together all sub-components: scheme config (partner_b_quote_enabled=true, deviation_pct=0.0100), rule (rate_source_pay=PARTNER), HttpPartnerBQuoteClient pointing at a WireMock server, Rate Engine, and TransactionOrchestrator. Test must cover: (a) successful commit with within-tolerance drift; (b) DEVIATION rejection; (c) UNAVAILABLE at commit. Uses the RV-05/RV-06 vectors. Verifies the complete path: POST /v1/rates => PBQ call => RateQuote stored => POST /v1/payments => second PBQ call => deviation check => commit or error.
**Steps:** Set up WireMock server stubbing GET /rates for Partner B; configure three stub scenarios: success-1350, success-1360.80, success-1366.20, and connection-refused; Configure test scheme + rule with partner_b_quote_enabled=true, rate_source_pay=PARTNER, deviation_pct=0.0100; Scenario A: rates stub=1350.00, commit stub=1360.80 => assert HTTP 200 on /payments, transaction COMMITTED, cost_rate_pay=1360.80; Scenario B: rates stub=1350.00, commit stub=1366.20 => assert HTTP 422 PARTNER_B_QUOTE_DEVIATION on /payments, no DB row; Scenario C: rates stub=1350.00, commit stub=connection-refused => assert HTTP 503 PARTNER_B_QUOTE_UNAVAILABLE on /payments, no DB row
**Deliverable:** Integration test class PartnerBQuoteEndToEndIT (WireMock-backed, Spring Boot test slice)
**Acceptance / logic checks:**
- Scenario A: transaction committed, HTTP 200, cost_rate_pay=1360.80 in DB
- Scenario B: HTTP 422, error_code=PARTNER_B_QUOTE_DEVIATION, no transaction row in DB
- Scenario C: HTTP 503, error_code=PARTNER_B_QUOTE_UNAVAILABLE, no transaction row in DB
- WireMock verifies exactly 2 calls to Partner B quote endpoint per commit attempt (1 at /rates, 1 at /payments)
- Pool identity assertion passes for Scenario A (|collection_usd - margins - payout_usd_cost| <= 0.01 USD)
**Depends on:** 4.6-T09, 4.6-T10, 4.6-T11, 4.6-T12, 4.6-T13, 4.6-T14


## WBS 4.7 — Rate quote lifecycle, TTL & rate-lock
### 4.7-T01 — DB migration: create rate_quote table with all pool and TTL columns  _(30 min)_
**Context:** WBS 4.7 — Rate quote lifecycle. The rate_quote table captures every output of the rate engine at GET /v1/rates or CPM generate time. Columns required: id BIGINT PK, quote_ref VARCHAR(64) UNIQUE, rule_id FK->rule, partner_id FK->partner, scheme_id FK->qr_scheme, direction VARCHAR(10), payment_mode VARCHAR(5), target_payout DECIMAL(20,4), payout_ccy CHAR(3), payout_usd_cost DECIMAL(20,8) NULL, collection_usd DECIMAL(20,8) NULL, collection_margin_usd DECIMAL(20,8) NULL, payout_margin_usd DECIMAL(20,8) NULL, send_amount DECIMAL(20,4), send_amount_ccy CHAR(3), service_charge DECIMAL(20,4), service_charge_ccy CHAR(3), collection_amount DECIMAL(20,4), collection_ccy CHAR(3), offer_rate_coll DECIMAL(20,8) NULL, cross_rate DECIMAL(20,8) NULL, cost_rate_coll DECIMAL(20,8), cost_rate_pay DECIMAL(20,8), treasury_rate_id_coll BIGINT FK->treasury_rate, treasury_rate_id_pay BIGINT FK->treasury_rate, ttl_seconds INT NOT NULL, quote_issued_at TIMESTAMPTZ NOT NULL, valid_until TIMESTAMPTZ NOT NULL, is_used BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ. USD-pool columns are NULL for same-currency short-circuit transactions. is_used is set TRUE when a transaction commits using this quote.
**Steps:** Create a Flyway/Liquibase migration file V4_7_001__create_rate_quote.sql; Add all columns per the schema above with correct types and NULL constraints; Add FK constraints to rule, partner, qr_scheme, treasury_rate tables; Add index on quote_ref (unique), composite index on (partner_id, quote_issued_at), index on valid_until for TTL expiry sweeps; Add CHECK constraint: valid_until = quote_issued_at + ttl_seconds * INTERVAL 1 second is implied by app logic; add CHECK ttl_seconds BETWEEN 60 AND 1800
**Deliverable:** Migration file V4_7_001__create_rate_quote.sql that applies cleanly on a fresh schema after 4.2 and 4.4 migrations
**Acceptance / logic checks:**
- Migration applies without error on a clean DB; all columns exist with correct types
- UNIQUE constraint on quote_ref rejects duplicate inserts
- FK to rule, partner, qr_scheme, treasury_rate enforced
- ttl_seconds column has CHECK constraint: ttl_seconds >= 60 AND ttl_seconds <= 1800
- valid_until index exists for efficient expiry sweeps (EXPLAIN shows index scan on WHERE valid_until < NOW())
**Depends on:** 4.2, 4.4

### 4.7-T02 — DB migration: add locked rate columns to transaction table  _(35 min)_
**Context:** WBS 4.7 — Rate lock. At CommitTransaction all USD-pool values and derived rates must be copied from rate_quote onto the transaction record and become immutable. The transaction table already exists (from WBS 4.4). Add columns: rate_quote_id BIGINT FK->rate_quote, payout_usd_cost DECIMAL(20,8) NULL, collection_usd DECIMAL(20,8) NULL, collection_margin_usd DECIMAL(20,8) NULL, payout_margin_usd DECIMAL(20,8) NULL, send_amount DECIMAL(20,4), service_charge DECIMAL(20,4), collection_amount DECIMAL(20,4), offer_rate_coll DECIMAL(20,8) NULL, cross_rate DECIMAL(20,8) NULL, cost_rate_coll DECIMAL(20,8), cost_rate_pay DECIMAL(20,8), quote_issued_at TIMESTAMPTZ, valid_until TIMESTAMPTZ, committed_at TIMESTAMPTZ, is_same_ccy_shortcircuit BOOLEAN. These columns must be immutable after commit — enforced at application layer (no UPDATE on locked columns post-commit) and by DB trigger in this ticket.
**Steps:** Create migration file V4_7_002__add_rate_lock_columns_to_transaction.sql; Add all locked-rate columns with NULL allowed (pre-commit rows do not have them set yet); Add FK rate_quote_id -> rate_quote; Create a PostgreSQL trigger function that raises EXCEPTION if an UPDATE attempts to change any of the 15 locked columns when committed_at IS NOT NULL; Attach trigger BEFORE UPDATE ON transaction FOR EACH ROW
**Deliverable:** Migration file V4_7_002__add_rate_lock_columns_to_transaction.sql including trigger definition
**Acceptance / logic checks:**
- All 15 locked columns are present after migration
- FK rate_quote_id -> rate_quote enforced
- UPDATE of cost_rate_coll on a row where committed_at IS NOT NULL raises an exception
- UPDATE of collection_amount on a pre-commit row (committed_at IS NULL) succeeds
- EXPLAIN shows FK index on rate_quote_id
**Depends on:** 4.7-T01

### 4.7-T03 — Implement TTL resolution service: resolve ttl_seconds from rule/partner config  _(30 min)_
**Context:** WBS 4.7 — TTL resolution. The TTL to apply to a rate quote is resolved as follows: if the rule is aggregator-bound (partner.is_aggregator_bound = TRUE), default is 60s; otherwise default is 300s. The resolved default is then overridden by partner.rate_quote_ttl_seconds if that field is non-null, and finally clamped to [60, 1800]. Partner.rate_quote_ttl_seconds is stored in the partner table (INT, default 300, range 60-1800). The partner table does not have is_aggregator_bound yet — add it in this ticket (BOOLEAN NOT NULL DEFAULT FALSE). Formula: base = (partner.is_aggregator_bound ? 60 : 300); ttl = clamp(partner.rate_quote_ttl_seconds ?? base, 60, 1800).
**Steps:** Add column is_aggregator_bound BOOLEAN NOT NULL DEFAULT FALSE to partner table in migration V4_7_003__add_aggregator_flag.sql; Implement TtlResolutionService.resolve(Partner partner) in the rate engine module; Logic: base = partner.isAggregatorBound() ? 60 : 300; configured = partner.getRateQuoteTtlSeconds() != null ? partner.getRateQuoteTtlSeconds() : base; return Math.max(60, Math.min(1800, configured)); Add unit tests covering: aggregator-bound partner gets 60s default; non-aggregator gets 300s default; explicit 120s config returns 120; value below 60 clamps to 60; value above 1800 clamps to 1800
**Deliverable:** TtlResolutionService class + unit tests + migration V4_7_003__add_aggregator_flag.sql
**Acceptance / logic checks:**
- aggregator-bound partner with null rate_quote_ttl_seconds returns ttl=60
- non-aggregator partner with null rate_quote_ttl_seconds returns ttl=300
- partner with rate_quote_ttl_seconds=120 returns ttl=120 regardless of aggregator flag
- partner with rate_quote_ttl_seconds=30 returns ttl=60 (clamped to minimum)
- partner with rate_quote_ttl_seconds=3600 returns ttl=1800 (clamped to maximum)
**Depends on:** 4.7-T01

### 4.7-T04 — Implement RateQuote domain entity and RateQuoteRepository  _(45 min)_
**Context:** WBS 4.7 — Quote entity. Define the RateQuote Java entity mapping to the rate_quote table (WBS 4.7-T01). Fields mirror the DB columns exactly. Key computed invariant: validUntil = quoteIssuedAt + ttlSeconds seconds. The entity must expose isExpired(Instant now) = now.isAfter(validUntil). Repository must support: save(RateQuote), findByQuoteRef(String quoteRef), markUsed(Long id). All DECIMAL fields must use java.math.BigDecimal. Timestamps use java.time.Instant stored as TIMESTAMPTZ.
**Steps:** Create RateQuote.java JPA entity with all fields mapped to rate_quote columns; Ensure validUntil is set in the entity factory method: validUntil = quoteIssuedAt.plusSeconds(ttlSeconds); Implement isExpired(Instant now) returning now.isAfter(validUntil); Create RateQuoteRepository (Spring Data JPA or JOOQ): save, findByQuoteRef, markUsed (SET is_used=TRUE); Add unit tests: entity construction sets validUntil correctly; isExpired returns false at quoteIssuedAt+ttl-1s; isExpired returns true at quoteIssuedAt+ttl+1s
**Deliverable:** RateQuote.java entity, RateQuoteRepository.java interface, unit tests for isExpired boundary
**Acceptance / logic checks:**
- RateQuote created with quoteIssuedAt=T and ttlSeconds=60 has validUntil=T+60s
- isExpired(T+59s) returns false
- isExpired(T+60s) returns true (expired AT the boundary)
- isExpired(T+61s) returns true
- findByQuoteRef returns empty Optional for unknown ref
- markUsed sets is_used=TRUE in DB
**Depends on:** 4.7-T01

### 4.7-T05 — Implement issueQuote function: persist rate quote with computed validUntil  _(45 min)_
**Context:** WBS 4.7 — Issue quote. The issueQuote function accepts the output of the rate engine (RateEngineResult containing all 5-step pool values, derived rates, cost rates, treasury rate IDs, rule_id, partner_id, scheme_id, direction, payment_mode, target_payout, payout_ccy) plus the resolved ttl_seconds (from TtlResolutionService 4.7-T03). It must: generate a quote_ref (UUID), set quote_issued_at = clock.now() (UTC), compute valid_until = quote_issued_at + ttl_seconds, build a RateQuote entity, persist it, and return RateQuoteResponse containing quote_ref, offer_rate_coll, send_amount, service_charge, collection_amount, collection_usd, payout_usd_cost, validUntil (ISO-8601 UTC string). For same-currency short-circuit transactions, USD pool fields are NULL. Store in Redis with key rate_quote:{quote_ref} TTL=ttl_seconds.
**Steps:** Implement QuoteService.issueQuote(RateEngineResult result, int ttlSeconds) in the rate engine module; Generate UUID for quote_ref; Set quote_issued_at = Instant.now(clock), valid_until = quote_issued_at.plusSeconds(ttlSeconds); Persist RateQuote via RateQuoteRepository.save(); Write quote to Redis: key=rate_quote:{quote_ref}, value=serialized RateQuote JSON, TTL=ttlSeconds; Return RateQuoteResponse with validUntil formatted as ISO-8601 UTC
**Deliverable:** QuoteService.issueQuote() method with Redis cache write + unit tests
**Acceptance / logic checks:**
- Returned validUntil equals quote_issued_at + ttl_seconds to the second
- Redis key rate_quote:{quote_ref} exists and expires after ttl_seconds
- Same-currency transaction: payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd are NULL in persisted quote
- Cross-currency transaction: all five USD pool values are non-null and positive
- quote_ref is a valid UUID; no two calls return the same ref
**Depends on:** 4.7-T03, 4.7-T04

### 4.7-T06 — Implement expiry check at commit: return RATE_QUOTE_EXPIRED for stale quotes  _(40 min)_
**Context:** WBS 4.7 — Expiry check. The CommitTransaction flow calls lockRateQuote(quoteRef) before proceeding. This function must: load the rate_quote by quote_ref (from Redis first, fallback to DB), check isExpired(Instant.now()), and if expired throw RateQuoteExpiredException which the Orchestrator converts to error code RATE_QUOTE_EXPIRED returned to the partner (HTTP 422). Also check is_used=TRUE to reject replay (return RATE_QUOTE_ALREADY_USED). The expiry check must use the stored valid_until from the quote — not a re-computed value. Clock is injectable for testing.
**Steps:** Implement QuoteService.lockRateQuote(String quoteRef, Instant now) method; Load quote from Redis cache first (rate_quote:{quoteRef}); on cache miss load from DB via RateQuoteRepository.findByQuoteRef(); If quote not found throw QuoteNotFoundException (-> HTTP 404); If quote.isUsed() throw RateQuoteAlreadyUsedException (-> HTTP 409); If quote.isExpired(now) throw RateQuoteExpiredException (-> HTTP 422, error_code=RATE_QUOTE_EXPIRED); Return the valid RateQuote for rate-lock step; do NOT mark as used here (that happens in 4.7-T07)
**Deliverable:** QuoteService.lockRateQuote() method + RateQuoteExpiredException + unit tests
**Acceptance / logic checks:**
- Quote with valid_until=T checked at T-1s: no exception thrown
- Quote with valid_until=T checked at T: RateQuoteExpiredException thrown (expired at boundary)
- Quote with valid_until=T checked at T+1s: RateQuoteExpiredException thrown
- Quote with is_used=TRUE throws RateQuoteAlreadyUsedException regardless of expiry
- Unknown quoteRef throws QuoteNotFoundException
- Cache miss falls through to DB lookup correctly
**Depends on:** 4.7-T04, 4.7-T05

### 4.7-T07 — Implement rate-lock: copy pool and derived rate values onto transaction at commit  _(50 min)_
**Context:** WBS 4.7 — Rate lock. After lockRateQuote() confirms the quote is valid, CommitTransaction must copy all USD pool values and derived rates from the RateQuote onto the transaction record atomically. Fields to copy: payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd, send_amount, service_charge, collection_amount, offer_rate_coll, cross_rate, cost_rate_coll, cost_rate_pay, quote_issued_at, valid_until. Also set: rate_quote_id, committed_at = Instant.now(), is_same_ccy_shortcircuit from quote. Then mark rate_quote.is_used = TRUE. Both the transaction UPDATE and the rate_quote.is_used UPDATE must occur in the same DB transaction (atomic). After commit, the trigger (4.7-T02) prevents further changes to these columns.
**Steps:** Implement RateLockService.applyRateLock(Transaction txn, RateQuote quote, Instant committedAt) method; In a single @Transactional block: update txn with all 15 locked fields + rate_quote_id + committed_at; call RateQuoteRepository.markUsed(quote.getId()); Ensure the same DB transaction covers both writes (no partial commit); Add unit test: after applyRateLock, all 15 fields on the transaction match the source quote exactly; Add integration test: simulate a treasury rate change after commit; verify the stored txn values are unchanged
**Deliverable:** RateLockService.applyRateLock() method + unit + integration tests
**Acceptance / logic checks:**
- All 15 rate fields on txn equal the corresponding fields on the source RateQuote after lock
- committed_at is set to the instant applyRateLock was called
- rate_quote.is_used is TRUE after lock
- Both writes fail or both succeed — partial commit is impossible (test: inject DB error on markUsed, verify txn fields not updated)
- After a later treasury rate change, reading the committed txn returns original locked values unchanged
**Depends on:** 4.7-T02, 4.7-T06

### 4.7-T08 — Unit tests: validUntil computation and TTL boundary correctness  _(30 min)_
**Context:** WBS 4.7 — Test vectors for validUntil and TTL. This ticket is dedicated test coverage for the validUntil formula and expiry boundary. Formula: validUntil = quote_issued_at + ttl_seconds. Boundary rule: a quote is expired when Instant.now() >= validUntil (i.e. expired AT the boundary instant, not only after). Use a fixed-clock (TestClock) so all time is deterministic. Key vectors: (1) ttl=60, check at +59s -> valid; (2) ttl=60, check at exactly +60s -> expired; (3) ttl=300, check at +299s -> valid; (4) ttl=1800, check at +1800s -> expired; (5) ttl=60, validUntil stored as 2026-10-10T12:01:00Z, check at 2026-10-10T12:01:00Z -> expired.
**Steps:** Create RateQuoteTtlTest.java in the rate-engine test module; Inject a fixed TestClock set to a known base time T; For each vector: construct a RateQuote with given ttl_seconds and quote_issued_at=T; call isExpired(T + offset); Assert expected expired/valid result for each vector; Add one additional vector: ttl=60 but quote_issued_at is 1 second in the past relative to T, check at T+58s -> valid (59s elapsed)
**Deliverable:** RateQuoteTtlTest.java with at least 6 parameterized test cases
**Acceptance / logic checks:**
- ttl=60, checked at +59s: isExpired=false
- ttl=60, checked at +60s: isExpired=true (boundary is exclusive for validity)
- ttl=300, checked at +299s: isExpired=false
- ttl=1800, checked at +1800s: isExpired=true
- Stored validUntil matches quote_issued_at.plusSeconds(ttl_seconds) exactly
- All 6 tests pass with zero tolerance — no rounding or off-by-one
**Depends on:** 4.7-T04

### 4.7-T09 — Unit tests: TTL clamping to [60, 1800] range — parameterized boundary vectors  _(25 min)_
**Context:** WBS 4.7 — Clamping test. TtlResolutionService.resolve() must clamp the configured ttl_seconds to [60, 1800]. Verify every boundary: below minimum, at minimum, middle values, at maximum, above maximum. Also verify the aggregator-bound flag selects the correct default (60 vs 300) before any configured override. All tests use pure unit mocks — no DB or Redis.
**Steps:** Create TtlResolutionServiceTest.java with parameterized test method; Test vectors: input=-1 -> 60; input=0 -> 60; input=59 -> 60; input=60 -> 60; input=61 -> 61; input=300 -> 300; input=1800 -> 1800; input=1801 -> 1800; input=9999 -> 1800; Add tests for default selection: aggregator-bound partner with null config -> 60; non-aggregator partner with null config -> 300; Verify that an explicit config value overrides the aggregator default before clamping
**Deliverable:** TtlResolutionServiceTest.java with at least 12 parameterized cases
**Acceptance / logic checks:**
- rate_quote_ttl_seconds=59 resolves to 60
- rate_quote_ttl_seconds=60 resolves to 60
- rate_quote_ttl_seconds=1800 resolves to 1800
- rate_quote_ttl_seconds=1801 resolves to 1800
- aggregator-bound + null config -> 60
- non-aggregator + null config -> 300
- aggregator-bound + explicit 120 config -> 120 (config wins, then clamp)
**Depends on:** 4.7-T03

### 4.7-T10 — Unit tests: rate-lock immutability — locked values unchanged after treasury update  _(50 min)_
**Context:** WBS 4.7 — Immutability test. After CommitTransaction, changing treasury rates or rule margins must not alter the locked values on the transaction. This test verifies the rate-lock guarantee end-to-end at the service layer. Use an in-memory H2 DB or Testcontainers PostgreSQL. Test flow: (1) create a rule with cost_rate_coll=1300.0 (USD/KRW); (2) issue a quote; (3) commit the transaction (applyRateLock); (4) update treasury rate to 1400.0; (5) reload the transaction from DB; (6) assert txn.cost_rate_coll == 1300.0 (original). Also test: updating rule m_a does not affect committed_at transaction.
**Steps:** Create RateLockImmutabilityTest.java as a @SpringBootTest slice or Testcontainers test; Set up rule with cost_rate_coll=1300.0 (KRW per USD), m_a=0.01, m_b=0.01; Issue and commit a test transaction; record all locked values; Update treasury_rate table: set new rate 1400.0 for usd_krw; Reload transaction from DB and assert all 15 locked columns equal original values; Update rule.m_a to 0.02 and verify committed transaction still shows original m_a snapshot (note: m_a is on rule not txn, but locked values like collection_margin_usd must not change)
**Deliverable:** RateLockImmutabilityTest.java with at least 4 test cases
**Acceptance / logic checks:**
- txn.cost_rate_coll equals 1300.0 after treasury rate changed to 1400.0
- txn.collection_usd equals the value computed at quote time, not recomputed with 1400.0
- DB trigger prevents direct UPDATE of cost_rate_coll on committed row (attempt raises exception)
- txn.committed_at is set and not null after commit
- txn.offer_rate_coll equals value derived at quote time, not re-derived from new rate
**Depends on:** 4.7-T07

### 4.7-T11 — Unit tests: RATE_QUOTE_EXPIRED error at commit — expiry boundary integration test  _(40 min)_
**Context:** WBS 4.7 — Expiry integration test. Verify that CommitTransaction returns error code RATE_QUOTE_EXPIRED when the quote TTL has elapsed. Use injectable TestClock. Test flow: (1) issue a quote at time T with ttl=60; (2) advance clock to T+61s; (3) call CommitTransaction with the quote_ref; (4) verify response is HTTP 422 with error_code=RATE_QUOTE_EXPIRED; (5) verify transaction is set to FAILED status; (6) verify no rate-lock columns were written. Also test the exact-boundary case: advance to T+60s and expect EXPIRED.
**Steps:** Create RateQuoteExpiryCommitTest.java as a service-layer unit test with mocked clock and mocked repositories; Issue a quote at T=0 with ttl=60 (validUntil=T+60); Set clock to T+60 (boundary); call CommitTransaction; assert RateQuoteExpiredException is thrown; Set clock to T+61; call CommitTransaction; assert RateQuoteExpiredException is thrown; Verify that neither attempt wrote any rate-lock columns to the transaction; Verify is_used remains FALSE on the quote (commit was rejected, quote should remain unused)
**Deliverable:** RateQuoteExpiryCommitTest.java with 4 test cases covering expired and boundary scenarios
**Acceptance / logic checks:**
- Clock at T+60: CommitTransaction throws RATE_QUOTE_EXPIRED
- Clock at T+59: CommitTransaction succeeds (not expired)
- After failed commit due to expiry: txn.committed_at is NULL
- After failed commit due to expiry: rate_quote.is_used is FALSE
- Error response body contains error_code=RATE_QUOTE_EXPIRED
**Depends on:** 4.7-T06, 4.7-T07

### 4.7-T12 — Wire issueQuote into GET /v1/rates handler — return validUntil in response  _(40 min)_
**Context:** WBS 4.7 — API wiring. The GET /v1/rates endpoint (rate quote API) is partially implemented from WBS 4.4. In this ticket: after the rate engine computes the quote, call QuoteService.issueQuote() (4.7-T05) to persist it and obtain the quote_ref and validUntil. Update the RateQuoteResponse DTO to include fields: quote_ref (String), validUntil (String, ISO-8601 UTC). The handler must pass the resolved ttl_seconds (from TtlResolutionService, 4.7-T03) to issueQuote. The txn state is set to QUOTED after persisting the quote. Redis store happens inside issueQuote.
**Steps:** Update RateQuoteResponse DTO: add quote_ref and validUntil fields; In GET /v1/rates handler: call TtlResolutionService.resolve(partner) to get ttl; pass to QuoteService.issueQuote(); Map the returned RateQuoteResponse (quote_ref, validUntil included) to the HTTP response body; Ensure validUntil is formatted as ISO-8601 UTC (e.g. 2026-10-10T12:01:00Z); Add integration test: call GET /v1/rates; assert response contains quote_ref (UUID format) and validUntil (validUntil = now + ttl within 2s tolerance)
**Deliverable:** Updated GET /v1/rates handler with quote_ref + validUntil in response + integration test
**Acceptance / logic checks:**
- GET /v1/rates response contains quote_ref as a UUID string
- validUntil in response matches quote_issued_at + ttl_seconds within 1-second tolerance
- rate_quote row is persisted in DB with correct values after the call
- Redis key rate_quote:{quote_ref} is set with TTL=ttl_seconds
- Aggregator-bound partner gets validUntil = now+60s; standard partner gets now+300s (when no override set)
**Depends on:** 4.7-T05, 4.7-T09

### 4.7-T13 — Wire lockRateQuote and applyRateLock into CommitTransaction handler  _(50 min)_
**Context:** WBS 4.7 — Commit wiring. The CommitTransaction endpoint (POST /v1/payments) calls the Orchestrator which must: (1) call QuoteService.lockRateQuote(quoteRef, Instant.now()) — throws RATE_QUOTE_EXPIRED (HTTP 422) or RATE_QUOTE_ALREADY_USED (HTTP 409) if invalid; (2) on valid quote, call RateLockService.applyRateLock(txn, quote, Instant.now()) to copy all pool values atomically and mark quote used; (3) continue with prefunding deduction and scheme call. Error response for RATE_QUOTE_EXPIRED must have body: {error_code: RATE_QUOTE_EXPIRED, message: Rate quote has expired. Please request a new quote.}
**Steps:** In TransactionOrchestrator.commitTransaction(): insert lockRateQuote() call after quote_ref validation, before prefunding deduction; Map RateQuoteExpiredException to HTTP 422 with error_code=RATE_QUOTE_EXPIRED in the global exception handler; Map RateQuoteAlreadyUsedException to HTTP 409 with error_code=RATE_QUOTE_ALREADY_USED; Call RateLockService.applyRateLock() immediately after lockRateQuote() returns the valid quote; Add integration test: full POST /v1/payments flow with valid quote succeeds and returns committed txn with locked rate values in response
**Deliverable:** Updated CommitTransaction handler with expiry guard + rate-lock + integration test
**Acceptance / logic checks:**
- POST /v1/payments with expired quote_ref returns HTTP 422 and error_code=RATE_QUOTE_EXPIRED
- POST /v1/payments with already-used quote_ref returns HTTP 409 and error_code=RATE_QUOTE_ALREADY_USED
- POST /v1/payments with valid quote: txn.cost_rate_coll matches quote.cost_rate_coll in DB after commit
- Re-using the same quote_ref a second time after a successful commit returns HTTP 409
- Transaction status transitions from QUOTED to PENDING_DEBIT (OVERSEAS) or DEBITED (LOCAL) after successful rate lock
**Depends on:** 4.7-T06, 4.7-T07, 4.7-T12

### 4.7-T14 — Configure rate_quote_ttl_seconds in partner Admin UI and validate range [60, 1800]  _(45 min)_
**Context:** WBS 4.7 — Config. The Admin System partner edit form must expose rate_quote_ttl_seconds (INT) and is_aggregator_bound (BOOLEAN). Validation rules: rate_quote_ttl_seconds must be in [60, 1800] inclusive; if outside range, reject with error message Allowed range: 60 to 1800 seconds. Changes to rate_quote_ttl_seconds apply only to new quotes issued after the change — in-flight quotes retain their original TTL. Config change must be audit-logged (actor, old value, new value, timestamp) per the platform audit requirement.
**Steps:** Add rate_quote_ttl_seconds integer input and is_aggregator_bound checkbox to partner edit form in the Admin API; Add server-side validation: reject if rate_quote_ttl_seconds < 60 OR > 1800 with HTTP 400 and descriptive error; Add audit log entry on change: record field name, previous value, new value, actor, changed_at; Update the partner cache invalidation to bust Redis hot cache on partner save; Add unit test: PUT /admin/partners/{id} with ttl=59 returns HTTP 400; ttl=60 returns 200; ttl=1800 returns 200; ttl=1801 returns 400
**Deliverable:** Admin API partner update endpoint with ttl validation + audit logging + unit tests
**Acceptance / logic checks:**
- rate_quote_ttl_seconds=59 returns HTTP 400 with range error message
- rate_quote_ttl_seconds=1800 returns HTTP 200 and persists value
- rate_quote_ttl_seconds=1801 returns HTTP 400
- Audit log contains previous and new value of rate_quote_ttl_seconds with actor and timestamp
- Quotes issued before the TTL change retain their original validUntil (no retroactive change)
**Depends on:** 4.7-T03, 4.7-T05

### 4.7-T15 — Redis TTL alignment test: verify Redis key expiry matches validUntil  _(40 min)_
**Context:** WBS 4.7 — Cache TTL alignment. The Redis key rate_quote:{quote_ref} must expire at the same time as the quote's validUntil. This means the Redis SET command must use EX=ttl_seconds (not EX=validUntil-now, which would diverge if there is clock skew between the app and Redis). Verify that: (1) the Redis TTL set at issue time equals ttl_seconds exactly; (2) after the Redis key expires, lockRateQuote() falls back to DB and still returns RATE_QUOTE_EXPIRED for an expired quote; (3) if Redis evicts the key early (simulated), DB fallback works correctly.
**Steps:** Add test RedisAlignmentTest.java using an embedded Redis (Testcontainers or EmbeddedRedis); Issue a quote with ttl=60; immediately call Redis TTL command on rate_quote:{quote_ref}; assert TTL is between 58 and 60 (within 2s of expected); Simulate Redis eviction by deleting the key manually; call lockRateQuote() at T+61; assert RATE_QUOTE_EXPIRED from DB fallback; Issue another quote; advance to T+59 (within TTL); delete Redis key; call lockRateQuote(); assert quote returned from DB (not expired); Verify Redis value contains all required fields: quote_ref, validUntil, all pool values
**Deliverable:** RedisAlignmentTest.java with 4 test cases for TTL alignment and cache-miss fallback
**Acceptance / logic checks:**
- Redis TTL for rate_quote:{quote_ref} is within 2s of ttl_seconds immediately after issue
- Cache miss at T+59 falls through to DB and returns valid quote
- Cache miss at T+61 falls through to DB and returns RATE_QUOTE_EXPIRED
- Redis serialized value contains valid_until field matching DB row
- Two concurrent lockRateQuote() calls for same quote_ref: exactly one succeeds, other gets RATE_QUOTE_ALREADY_USED
**Depends on:** 4.7-T06, 4.7-T05


## WBS 4.8 — Rounding & precision rules
### 4.8-T01 — Define currency_scale table and CurrencyScale entity  _(35 min)_
**Context:** GMEPay+ must apply rounding only at defined output points using per-currency decimal scale. Spec RATE-04 §10.3 defines the scale table: KRW=0 (half-up to whole won), USD=2 (half-up to cent), MNT=0, EUR=2, VND=0, THB=2. For currencies not listed, scale comes from ISO 4217 decimal places populated by GME Ops. A currency_scale table is needed so the rate engine can look up scale without hardcoding. Depends on 4.2 (entity/schema foundation) and 4.3 (DB migrations).
**Steps:** Create DB migration: table currency_scale(ccy CHAR(3) PK, decimal_places SMALLINT NOT NULL CHECK(decimal_places>=0), rounding_mode VARCHAR(20) NOT NULL DEFAULT 'HALF_UP', notes VARCHAR(200)).; Seed initial rows for KRW(0), USD(2), MNT(0), EUR(2), VND(0), THB(2) with rounding_mode=HALF_UP for all.; Create Java entity CurrencyScale with fields ccy, decimalPlaces, roundingMode(enum: HALF_UP).; Create CurrencyScaleRepository with findByCcy(String ccy) and a cache-friendly findAll().; Add a NOT NULL constraint and a check that decimal_places <= 8 to prevent misconfiguration.
**Deliverable:** DB migration file V4_8_001__currency_scale.sql + CurrencyScale entity + CurrencyScaleRepository
**Acceptance / logic checks:**
- Migration runs cleanly; SELECT * FROM currency_scale returns exactly 6 seed rows with correct decimal_places values.
- findByCcy('KRW') returns decimalPlaces=0, findByCcy('USD') returns decimalPlaces=2.
- findByCcy('XXX') returns empty Optional (unknown currency, no row).
- INSERT of decimal_places=-1 or decimal_places=9 is rejected by DB constraint.
- All seed rows have rounding_mode=HALF_UP.
**Depends on:** 4.2, 4.3

### 4.8-T02 — Create CurrencyScaleLookupService with fallback to ISO 4217  _(30 min)_
**Context:** The rate engine needs to resolve decimal scale at runtime for any ISO 4217 currency, including ones not yet seeded. Spec RATE-04 §10.3 Assumption A2: for unlisted currencies use the ISO 4217 decimal places maintained by GME Ops. The service must: (1) look up currency_scale table (4.8-T01); (2) if not found, throw CurrencyScaleNotFoundException with message 'Currency scale not configured for {ccy} - GME Ops must populate currency_scale table before activating this currency'. No silent fallback to 0 or 2.
**Steps:** Create CurrencyScaleLookupService that autowires CurrencyScaleRepository.; Implement getScale(String ccy): return CurrencyScale if found, else throw CurrencyScaleNotFoundException.; Add a getDecimalPlaces(String ccy) convenience method returning int.; Add a getRoundingMode(String ccy) convenience method returning RoundingMode enum.; Write unit tests with mocked repository: found case (KRW->0, USD->2), not-found case throws exception.
**Deliverable:** CurrencyScaleLookupService.java + CurrencyScaleNotFoundException.java + unit test CurrencyScaleLookupServiceTest.java
**Acceptance / logic checks:**
- getDecimalPlaces('KRW') returns 0.
- getDecimalPlaces('USD') returns 2.
- getDecimalPlaces('MNT') returns 0.
- getDecimalPlaces('UNKNOWN') throws CurrencyScaleNotFoundException with informative message containing the ccy code.
- getRoundingMode('KRW') returns RoundingMode.HALF_UP.
**Depends on:** 4.8-T01

### 4.8-T03 — Implement roundToCurrencyScale utility function  _(30 min)_
**Context:** The rate engine must round collection_amount (Settle-A ccy) and the parsed target_payout (Settle-B/payout ccy) using half-up rounding to the currency's decimal scale. All other intermediates (payout_usd_cost, collection_usd, margin amounts, send_amount) must NOT be rounded - they carry 8 decimal places of precision (DECIMAL(20,8)). Spec RATE-04 §10.4: rounding applied only at Step 5 (collection_amount) and target_payout input parsing. Rounding mode is HALF_UP per spec §10.3 table.
**Steps:** Create MoneyRoundingUtil with static method roundToCurrencyScale(BigDecimal amount, String ccy, CurrencyScaleLookupService lookup): returns amount.setScale(decimalPlaces, RoundingMode.HALF_UP).; Ensure the method rejects null amount or null ccy with IllegalArgumentException.; Add overload roundToCurrencyScale(BigDecimal amount, CurrencyScale scale) for pre-resolved scale.; Write unit tests covering KRW rounding (1234.5 -> 1235, 1234.4 -> 1234), USD rounding (10.555 -> 10.56, 10.554 -> 10.55), and MNT rounding (99.9 -> 100).; Verify that a BigDecimal with 8 decimal places passed through returns 8 decimal places unchanged (no rounding on intermediates path - intermediates are NOT passed to this function).
**Deliverable:** MoneyRoundingUtil.java + MoneyRoundingUtilTest.java
**Acceptance / logic checks:**
- roundToCurrencyScale(BigDecimal('1234.5'), 'KRW', ...) returns BigDecimal('1235') (scale 0).
- roundToCurrencyScale(BigDecimal('10.555'), 'USD', ...) returns BigDecimal('10.56') (scale 2, half-up).
- roundToCurrencyScale(BigDecimal('10.554'), 'USD', ...) returns BigDecimal('10.55').
- roundToCurrencyScale(BigDecimal('99.9'), 'MNT', ...) returns BigDecimal('100') (scale 0).
- Passing null amount throws IllegalArgumentException; unknown ccy propagates CurrencyScaleNotFoundException.
**Depends on:** 4.8-T02

### 4.8-T04 — Apply rounding at collection_amount output point in rate engine (Step 5)  _(40 min)_
**Context:** Spec RATE-04 §10.4 and pseudocode line: collection_amount = round_to_currency_scale(collection_amount, settle_a_ccy). This is the ONLY rounding point in the 5-step calculation. Steps 1-4 intermediates (payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd, send_amount) must all be computed and stored as DECIMAL(20,8) BigDecimal WITHOUT rounding. Step 5: collection_amount = send_amount + service_charge, then rounded to settle_a_ccy scale. Depends on rate engine core (4.3) and MoneyRoundingUtil (4.8-T03).
**Steps:** Locate the rate engine Step 5 implementation in the existing rate calculation service (from 4.3).; After computing collection_amount = send_amount.add(service_charge), call MoneyRoundingUtil.roundToCurrencyScale(collection_amount, settleACcy, lookup) and replace collection_amount with the result.; Confirm Steps 1-4 computations use BigDecimal arithmetic at scale 8 (setScale not called on intermediates).; Add assertion that payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd, send_amount all have scale >= 8 after computation (developer guard).; Write unit test: for settleACcy=KRW, verify collection_amount in result has scale 0; for settleACcy=USD, scale 2.
**Deliverable:** Updated RateCalculationService.java (Step 5 rounding wired) + RateCalculationServiceRoundingTest.java
**Acceptance / logic checks:**
- For settle_a_ccy=KRW: computed collection_amount (e.g. 50123.7) is rounded to 50124 (integer, scale 0).
- For settle_a_ccy=USD: computed collection_amount (e.g. 36.4559) is rounded to 36.46 (scale 2).
- send_amount in the returned quote retains 8 decimal places (e.g. 49623.72000000), not rounded.
- collection_usd in returned quote retains 8 decimal places.
- When settle_a_ccy is not in currency_scale table, CurrencyScaleNotFoundException is thrown before any value is returned.
**Depends on:** 4.8-T03, 4.3

### 4.8-T05 — Apply rounding at target_payout input parsing point  _(30 min)_
**Context:** Spec RATE-04 §10.4: rounding is applied once at target_payout input parsing (the final output point for the payout side). The input target_payout arrives as a numeric value in payout_ccy (e.g. KRW integer from partner). It must be parsed and rounded to payout_ccy scale before entering Step 1 (payout_usd_cost = target_payout / cost_rate_pay). For KRW this means the value must already be an integer; for USD it must have at most 2 decimal places. This prevents sub-unit fractional inputs from propagating into pool math.
**Steps:** In the rate engine input validation/parsing layer, after reading target_payout from the request, call MoneyRoundingUtil.roundToCurrencyScale(target_payout, payoutCcy, lookup).; Store the rounded value as the authoritative target_payout for all subsequent steps.; Add unit test: target_payout=50000.7 with payoutCcy=KRW rounds to 50001 before Step 1 runs.; Add unit test: target_payout=100.001 with payoutCcy=USD rounds to 100.00 before Step 1 runs.; Log a WARN if rounding changes the input value (input was not on currency boundary), to surface misconfigured partner integrations.
**Deliverable:** Updated RateEngineInputParser.java (or equivalent input handler) + RateEngineInputParserTest.java
**Acceptance / logic checks:**
- target_payout=50000.7, payoutCcy=KRW -> parsed value is 50001 (BigDecimal scale 0).
- target_payout=100.001, payoutCcy=USD -> parsed value is 100.00 (BigDecimal scale 2).
- target_payout=50000 (already integer), payoutCcy=KRW -> parsed value is 50000 (no change, no WARN logged).
- target_payout=0 rejected before rounding by existing INVALID_PAYOUT_AMOUNT guard.
- target_payout with payoutCcy not in currency_scale -> CurrencyScaleNotFoundException before any calculation.
**Depends on:** 4.8-T03, 4.3

### 4.8-T06 — Verify intermediates are stored at DECIMAL(20,8) precision in DB columns  _(35 min)_
**Context:** Spec DAT-03 §2.1 and RATE-04 §10.2: USD intermediary values (collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd) and send_amount are stored as DECIMAL(20,8) in the rate_quote and transaction tables. collection_amount is stored as DECIMAL(20,4) (currency-native rounded value). This ticket verifies DB schema matches spec and adds a DB-level constraint audit. Do NOT introduce rounding on insert - the application layer must already carry 8dp BigDecimal into the ORM mapper.
**Steps:** Read the existing rate_quote and transaction table DDL (from 4.2/4.3 migrations).; Confirm columns: collection_usd DECIMAL(20,8), payout_usd_cost DECIMAL(20,8), collection_margin_usd DECIMAL(20,8), payout_margin_usd DECIMAL(20,8), send_amount DECIMAL(20,8), collection_amount DECIMAL(20,4).; If any column has wrong precision, add a migration ALTER TABLE to correct it.; Write an integration test that inserts a RateQuote with send_amount=49623.72345678 and reads it back - assert no precision loss (value equals original to 8dp).; Add a comment block in the migration explaining WHY each column uses its specific scale (intermediate vs output).
**Deliverable:** Migration V4_8_002__fix_rate_quote_precision.sql (if correction needed, else documented as N/A) + IntermediatePrecisionIntegrationTest.java
**Acceptance / logic checks:**
- DESCRIBE rate_quote shows collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, send_amount all as DECIMAL(20,8).
- DESCRIBE rate_quote/transaction shows collection_amount as DECIMAL(20,4).
- Insert send_amount=49623.72345678, read back: value equals 49623.72345678 (no truncation).
- Insert collection_amount=50124.0000 (KRW), read back: value equals 50124.0000 (correct scale).
- Java ORM mapping: RateQuote.sendAmount is BigDecimal; no inadvertent setScale or doubleValue() call anywhere in the mapping.
**Depends on:** 4.8-T04, 4.2, 4.3

### 4.8-T07 — Implement pool identity check post-rounding and enforce hard error  _(35 min)_
**Context:** Spec RATE-04 §10.5 and §11.3: pool identity (collection_usd - collection_margin_usd - payout_margin_usd = payout_usd_cost) must hold within 0.01 USD tolerance. This check runs AFTER Steps 1-4, using unrounded 8dp intermediates. A breach is a hard programming error - transaction must fail with POOL_IDENTITY_FAILURE (not silently commit). The check must also run in the on_commit path to guard against any future code path that bypasses the quote calculation. Intermediates use 8dp BigDecimal so the tolerance of 0.01 USD is generous (rounding cannot cause breach if implemented correctly).
**Steps:** Create PoolIdentityChecker.assertIdentity(BigDecimal collectionUsd, BigDecimal collectionMarginUsd, BigDecimal payoutMarginUsd, BigDecimal payoutUsdCost): compute diff = abs(collectionUsd - collectionMarginUsd - payoutMarginUsd - payoutUsdCost); if diff > 0.01 throw PoolIdentityException with details.; Wire assertIdentity() into RateCalculationService after Step 3 (before Step 4) and also in the on_commit path.; Write unit test: valid input -> no exception; tampered collectionUsd (off by 0.02) -> PoolIdentityException thrown.; Write unit test: diff exactly 0.01 -> no exception (boundary); diff 0.010001 -> exception (exclusive upper bound).; Confirm exception message includes all four values and the computed diff for debuggability.
**Deliverable:** PoolIdentityChecker.java + PoolIdentityException.java + PoolIdentityCheckerTest.java + wiring in RateCalculationService
**Acceptance / logic checks:**
- collectionUsd=36.2319, collectionMarginUsd=0.7246, payoutMarginUsd=0.3623, payoutUsdCost=35.145 -> diff=abs(36.2319-0.7246-0.3623-35.145)=0.0000 -> no exception.
- If collectionUsd is inflated by 0.02 in above example -> PoolIdentityException.
- diff=0.01 (exactly at tolerance) -> no exception.
- diff=0.0101 -> PoolIdentityException.
- Exception message contains all four BigDecimal input values and the computed diff value.
**Depends on:** 4.8-T04

### 4.8-T08 — Implement payout rounding at payout output point  _(35 min)_
**Context:** Spec RATE-04 §10.4 and the three-currency model: the payout amount delivered to the merchant (target_payout in payout_ccy) is the Settle-B/payout side. For ZeroPay/KRW this is always an integer. The rate engine receives target_payout as input (already rounded per 4.8-T05). However the payout leg also surfaces in settlement batch files (ZP0061 etc per SCH-06) and webhook payloads. This ticket ensures payout amount serialisation (webhook, API response) is rounded to payout_ccy scale before output, and that no fractional KRW ever appears in an outbound payload.
**Steps:** Create PayoutAmountSerializer that applies MoneyRoundingUtil.roundToCurrencyScale to target_payout before including it in any API response or webhook payload.; Locate POST /v1/payments response builder and the payment.pending_debit webhook payload builder; wrap target_payout serialization through PayoutAmountSerializer.; Write unit test: target_payout=50000 KRW serialised -> '50000' (no decimal point in JSON).; Write unit test: target_payout=100.50 USD serialised -> '100.50' (2dp).; Add a guard: if target_payout after rounding != original target_payout (i.e. input already rounded per T05), throw AssertionError (should never happen in production after T05 is wired).
**Deliverable:** PayoutAmountSerializer.java + updated response/webhook builders + PayoutAmountSerializerTest.java
**Acceptance / logic checks:**
- KRW target_payout in API response JSON is an integer (no decimal point), e.g. 50000 not 50000.00.
- USD target_payout in API response JSON has exactly 2 decimal places, e.g. 100.50.
- AssertionError is never thrown in integration tests (confirming T05 rounding precedes T08 serialisation).
- Webhook payload payment.pending_debit.target_payout for KRW transaction = integer value.
- No floating-point conversion (doubleValue, floatValue) appears in the serialization path.
**Depends on:** 4.8-T05

### 4.8-T09 — Unit tests: per-currency scale lookup and rounding correctness (all seeded currencies)  _(30 min)_
**Context:** Spec RATE-04 §10.3 defines six currencies with explicit scale and rounding direction. This ticket writes a comprehensive parameterised unit test suite covering all seeded currencies plus the unknown-currency exception path. Tests must use exact numeric examples from the spec table to confirm the rounding module matches the spec. These tests serve as the living specification for future currency additions.
**Steps:** Create CurrencyScaleRoundingParameterisedTest using JUnit 5 @ParameterizedTest + @CsvSource.; Cover all 6 seeded currencies: KRW(1234.5->1235, 1234.4->1234), USD(10.555->10.56, 10.554->10.55), MNT(5.5->6, 5.4->5), EUR(0.555->0.56), VND(999.6->1000), THB(5.555->5.56).; Add boundary test: value=0.0 rounds to 0 for all currencies.; Add large-value test: KRW 50000000 (50 million) rounds to 50000000 (no change, already integer).; Add unknown-currency test: getDecimalPlaces('XYZ') throws CurrencyScaleNotFoundException.
**Deliverable:** CurrencyScaleRoundingParameterisedTest.java
**Acceptance / logic checks:**
- All 6 currency round-trip vectors pass (exact BigDecimal equality after rounding).
- KRW: 1234.5 -> 1235 (HALF_UP, scale 0).
- USD: 10.555 -> 10.56 (HALF_UP, scale 2); 10.554 -> 10.55.
- MNT: 5.5 -> 6 (HALF_UP, scale 0).
- Unknown currency 'XYZ' throws CurrencyScaleNotFoundException.
**Depends on:** 4.8-T03, 4.8-T02

### 4.8-T10 — Unit tests: pool identity holds after rounding outputs (no-broken-identity test)  _(40 min)_
**Context:** Spec RATE-04 §10.5: pool identity (collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost, tolerance 0.01 USD) must hold even after collection_amount is rounded to currency scale. The rounding of collection_amount affects only the Settle-A currency output; the USD intermediates remain at 8dp. This test proves that rounding collection_amount to KRW scale or USD scale does NOT break the USD pool identity invariant. Test uses worked numeric examples from the canonical rate engine.
**Steps:** Set up test fixture using the 5-step calculation for a KRW payout (usd_krw=1380, target_payout=50000 KRW, m_a=0.015, m_b=0.010, service_charge=500 KRW): compute all intermediates and collection_amount with KRW rounding.; Assert pool identity: abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) <= 0.01.; Repeat for USD payout leg (usd rate=1.0, target_payout=100 USD, m_a=0.015, m_b=0.010, service_charge=1.00 USD): collection_amount rounded to 2dp USD.; Assert pool identity holds for USD case also.; Add a negative test: artificially set collection_usd += 0.02 BigDecimal and assert PoolIdentityException is thrown.
**Deliverable:** PoolIdentityAfterRoundingTest.java
**Acceptance / logic checks:**
- KRW example: pool identity diff <= 0.01 USD after collection_amount rounded to integer KRW.
- USD example: pool identity diff <= 0.01 USD after collection_amount rounded to 2dp USD.
- Intermediate values (collection_usd, payout_usd_cost, send_amount) have scale >= 8 in both test cases.
- Negative test: perturbed collection_usd (+0.02) causes PoolIdentityException with diff in message.
- collection_amount for KRW case is a whole number (BigDecimal.scale() == 0 or value has no fractional part).
**Depends on:** 4.8-T07, 4.8-T04

### 4.8-T11 — Document rounding decision points: inline code comments and JavaDoc  _(25 min)_
**Context:** Spec RATE-04 §10 requires that every developer working on the rate engine can identify exactly WHERE rounding is applied. Per the TICKET_BRIEF self-contained rule, this ticket adds formal JavaDoc and inline comments to the three rounding-application sites: (1) input parsing of target_payout (4.8-T05), (2) Step 5 collection_amount rounding (4.8-T04), and (3) pool identity check (4.8-T07). Also documents explicitly that Steps 1-4 intermediates are NOT rounded. This prevents future developers from accidentally adding rounding in the wrong place.
**Steps:** Add JavaDoc to MoneyRoundingUtil explaining: only two call sites are permitted - target_payout parsing and collection_amount Step 5. Any other call site is a defect.; Add inline comment block at each of the two rounding call sites citing RATE-04 §10.4 and the spec rule text verbatim.; Add inline comment at each Step 1-4 intermediate computation: // NO ROUNDING - intermediate must retain 8dp precision per RATE-04 §10.2.; Add JavaDoc to PoolIdentityChecker explaining the 0.01 USD tolerance and that breach is a hard programming error.; Create a short ADR (Architecture Decision Record) comment at the top of RateCalculationService explaining the rounding philosophy.
**Deliverable:** Updated JavaDoc and inline comments in MoneyRoundingUtil.java, RateCalculationService.java (Steps 1-5), PoolIdentityChecker.java
**Acceptance / logic checks:**
- MoneyRoundingUtil JavaDoc explicitly states 'only two permitted call sites'.
- Step 5 in RateCalculationService has comment citing RATE-04 §10.4.
- Each of Steps 1-4 has a '// NO ROUNDING' comment.
- PoolIdentityChecker JavaDoc states tolerance=0.01 USD and breach=hard error.
- Code review: no other call to roundToCurrencyScale exists outside the two permitted sites (grep confirms).
**Depends on:** 4.8-T04, 4.8-T05, 4.8-T07


## WBS 4.9 — Rate engine unit tests vs vectors
### 4.9-T01 — Create rate-engine test harness and shared fixtures for RV-01..RV-10  _(40 min)_
**Context:** WBS 4.9 needs a JUnit 5 (or equivalent Java) test module for the rate engine. The harness must use decimal arithmetic (BigDecimal with scale >= 10 for intermediates), provide reusable fixture builders for RateEngineInput (target_payout, coll_ccy, payout_ccy, cost_rate_coll, cost_rate_pay, m_a, m_b, service_charge) and RateEngineOutput (payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd, send_amount, collection_amount, offer_rate_coll, cross_rate), and expose a shared assertPoolIdentity(output, tolerance=0.01 USD) helper. Pool identity invariant: abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) <= 0.01 USD.
**Steps:** Create Maven/Gradle sub-module or test source set rate-engine-tests under the existing backend project.; Add RateEngineTestFixtures.java with static builder methods for each currency pair (MNT/KRW, USD/KRW, USD/USD, KRW/KRW).; Add RateEngineAssertions.java with assertPoolIdentity(output, BigDecimal tolerance) that throws AssertionError with a descriptive message on violation.; Add a smoke test that instantiates a fixture and calls assertPoolIdentity to confirm the helper itself works.; Verify CI picks up the new test module (add to build script if needed).
**Deliverable:** rate-engine-tests module with RateEngineTestFixtures.java, RateEngineAssertions.java, and a passing smoke test.
**Acceptance / logic checks:**
- Module compiles and smoke test passes in CI with zero failures.
- RateEngineTestFixtures provides builders for all four currency-pair combinations used across RV-01..RV-10.
- assertPoolIdentity passes when delta = 0.009 USD and fails when delta = 0.011 USD (boundary check).
- All intermediate values use BigDecimal with scale >= 10; no double/float arithmetic present.
- assertPoolIdentity error message includes actual delta and threshold values for diagnosis.
**Depends on:** 4.2, 4.3, 4.4, 4.5, 4.6, 4.8

### 4.9-T02 — Test RV-01: cross-border inbound MNT->KRW via USD (baseline 5-step vector)  _(30 min)_
**Context:** Rate engine 5-step RECEIVE-mode formula: (1) payout_usd_cost = target_payout / cost_rate_pay; (2) collection_usd = payout_usd_cost / (1 - m_a - m_b); (3) collection_margin_usd = collection_usd * m_a, payout_margin_usd = collection_usd * m_b; (4) send_amount = collection_usd * cost_rate_coll; (5) collection_amount = send_amount + service_charge. Derived: offer_rate_coll = send_amount / (collection_usd - collection_margin_usd); cross_rate = target_payout / send_amount. RV-01 inputs: target_payout=13500 KRW, coll_ccy=MNT, payout_ccy=KRW, cost_rate_coll=3500.00, cost_rate_pay=1350.00, m_a=0.015, m_b=0.010, service_charge=500 MNT.
**Steps:** In RateEngineRV01Test.java call the rate engine with the RV-01 fixture.; Assert payout_usd_cost = 10.0000 USD (tolerance 0.0001).; Assert collection_usd = 10.2564 USD (tolerance 0.0001).; Assert send_amount = 35897.44 MNT (tolerance 0.01), collection_amount = 36397.44 MNT (tolerance 0.01).; Assert offer_rate_coll = 3553.28 MNT/USD (tolerance 0.01) and cross_rate = 0.37609 KRW/MNT (tolerance 0.00001).; Call assertPoolIdentity(output) to confirm delta <= 0.01 USD.
**Deliverable:** RateEngineRV01Test.java with named test method testRV01_crossBorderInbound_MNT_KRW_via_USD.
**Acceptance / logic checks:**
- payout_usd_cost == 10.0000 USD within 0.0001.
- collection_usd == 10.2564 USD within 0.0001.
- send_amount == 35897.44 MNT within 0.01 and collection_amount == 36397.44 MNT within 0.01.
- offer_rate_coll == 3553.28 MNT/USD within 0.01 and cross_rate == 0.37609 KRW/MNT within 0.00001.
- Pool-identity assertion passes (delta <= 0.01 USD).
**Depends on:** 4.9-T01

### 4.9-T03 — Test RV-02: identity leg A (Settle A = USD, cost_rate_coll = 1.0)  _(25 min)_
**Context:** When Settle A currency = USD, cost_rate_coll is set to the IDENTITY flag (value 1.0); the USD pool math still applies but send_amount = collection_usd * 1.0 = collection_usd. RV-02 inputs: target_payout=13500 KRW, coll_ccy=USD, payout_ccy=KRW, cost_rate_coll=1.0 (IDENTITY), cost_rate_pay=1350.00, m_a=0.015, m_b=0.010, service_charge=0.50 USD. Expected: payout_usd_cost=10.0000 USD, collection_usd=10.2564 USD, send_amount=10.2564 USD, collection_amount=10.7564 USD.
**Steps:** Create RateEngineRV02Test.java with fixture: coll_ccy=USD, cost_rate_coll=1.0 (IDENTITY), cost_rate_pay=1350.00, service_charge=0.50 USD.; Assert send_amount == collection_usd (identity leg: no FX conversion).; Assert collection_amount == 10.7564 USD (tolerance 0.0001).; Verify the rate engine persists cost_rate_coll source field as IDENTITY (not a numeric treasury lookup).; Call assertPoolIdentity(output).
**Deliverable:** RateEngineRV02Test.java with test method testRV02_identityLegA_settleA_USD.
**Acceptance / logic checks:**
- send_amount == collection_usd (== 10.2564 USD) within 0.0001.
- collection_amount == 10.7564 USD within 0.0001.
- Recorded cost_rate_coll source = IDENTITY (not TREASURY).
- Pool-identity assertion passes.
- payout_usd_cost == 10.0000 USD within 0.0001.
**Depends on:** 4.9-T01

### 4.9-T04 — Test RV-03: both legs identity (USD->USD, cost_rate_coll=1.0, cost_rate_pay=1.0)  _(25 min)_
**Context:** When both Settle A and Settle B are USD, both cost rates = 1.0 (IDENTITY). Margins still apply to collection_usd. RV-03 inputs: target_payout=100.00 USD, coll_ccy=USD, payout_ccy=USD, cost_rate_coll=1.0, cost_rate_pay=1.0, m_a=0.015, m_b=0.010, service_charge=0.50 USD. Expected: payout_usd_cost=100.0000 USD, collection_usd=102.5641 USD, send_amount=102.5641 USD, collection_amount=103.0641 USD. Note: this is NOT the same-currency short-circuit (different ccys could still route through USD pool even when both legs are 1.0).
**Steps:** Create RateEngineRV03Test.java with fixture: coll_ccy=USD, payout_ccy=USD, both cost rates=1.0 IDENTITY, service_charge=0.50 USD, target_payout=100.00.; Assert payout_usd_cost = 100.0000 USD (tolerance 0.0001).; Assert collection_usd = 102.5641 USD (tolerance 0.0001).; Assert send_amount == collection_usd and collection_amount = 103.0641 USD (tolerance 0.0001).; Confirm both leg sources recorded as IDENTITY in output metadata.; Call assertPoolIdentity(output).
**Deliverable:** RateEngineRV03Test.java with test method testRV03_bothLegsIdentity_USD_USD.
**Acceptance / logic checks:**
- payout_usd_cost == 100.0000 USD within 0.0001.
- collection_usd == 102.5641 USD within 0.0001.
- send_amount == collection_usd (both IDENTITY legs) within 0.0001.
- collection_amount == 103.0641 USD within 0.0001.
- Pool-identity assertion passes.
**Depends on:** 4.9-T01

### 4.9-T05 — Test RV-04: same-currency short-circuit (KRW->KRW, USD pool skipped)  _(30 min)_
**Context:** Same-currency short-circuit rule: if collection_ccy == settle_A == settle_B == payout_ccy then skip the USD pool entirely. Formula: collection_amount = target_payout + service_charge. No payout_usd_cost, collection_usd, margins, offer_rate_coll, or cross_rate computed. m_a=0 and m_b=0 (required for same-currency rules; combined margin 0% allowed). RV-04 inputs: target_payout=13500 KRW, coll_ccy=KRW, payout_ccy=KRW, cost rates=IDENTITY (skipped), m_a=0.0, m_b=0.0, service_charge=500 KRW. Expected: collection_amount=14000 KRW; all USD pool fields null.
**Steps:** Create RateEngineRV04Test.java with same-currency fixture: coll_ccy=KRW, payout_ccy=KRW, m_a=0, m_b=0, service_charge=500 KRW.; Assert collection_amount = 14000 KRW (exact integer for KRW scale=0).; Assert payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd are null or absent in the output object.; Assert offer_rate_coll and cross_rate are null or absent.; Verify no prefunding deduction event is emitted (or deduction_usd = null).
**Deliverable:** RateEngineRV04Test.java with test method testRV04_sameCurrencyShortCircuit_KRW_KRW.
**Acceptance / logic checks:**
- collection_amount == 14000 KRW (exact, KRW scale 0 decimals).
- All USD pool fields (payout_usd_cost, collection_usd, margins) are null in the output.
- offer_rate_coll and cross_rate are null.
- No MARGIN_BELOW_MINIMUM error raised (m_a+m_b=0 allowed for same-currency).
- No prefunding deduction emitted.
**Depends on:** 4.9-T01

### 4.9-T06 — Test RV-05: partner B quote within tolerance (0.8% deviation, transaction commits)  _(35 min)_
**Context:** Partner B authoritative quote: at /rates time, treasury cost_rate_pay=1350.00 KRW/USD. At commit time, partner B live quote=1360.80 KRW/USD. Deviation = abs(1360.80-1350.00)/1350.00 = 0.8%, which is within the default 1.0% tolerance. Engine must recompute with commit-time rate 1360.80 and commit. Inputs otherwise identical to RV-01: target_payout=13500 KRW, coll_ccy=MNT, cost_rate_coll=3500.00, m_a=0.015, m_b=0.010, service_charge=500 MNT. Expected at commit: payout_usd_cost=13500/1360.80=9.9206 USD (illustrative); transaction committed; recorded cost_rate_pay=1360.80; rate_source=PARTNER.
**Steps:** Create RateEngineRV05Test.java; mock the partner B quote API to return 1360.80 at commit time.; Invoke the rate engine commit path; confirm no PARTNER_B_QUOTE_DEVIATION error thrown.; Assert recorded cost_rate_pay = 1360.80 in the output/persisted record.; Assert rate_source field = PARTNER (not TREASURY).; Assert payout_usd_cost = 9.9206 USD (tolerance 0.0001) computed with the commit-time rate.
**Deliverable:** RateEngineRV05Test.java with test method testRV05_partnerBQuote_withinTolerance_commits.
**Acceptance / logic checks:**
- No PARTNER_B_QUOTE_DEVIATION error thrown when deviation=0.8%.
- Committed record stores cost_rate_pay=1360.80 (not 1350.00).
- rate_source field in output = PARTNER.
- payout_usd_cost = 9.9206 USD within 0.0001 using commit-time rate.
- Pool-identity assertion passes with the commit-time recomputed values.
**Depends on:** 4.9-T01

### 4.9-T07 — Test RV-06: partner B quote over tolerance (1.2% deviation, returns PARTNER_B_QUOTE_DEVIATION)  _(35 min)_
**Context:** Partner B quote deviation > default 1.0% tolerance must abort the transaction. At /rates time, treasury cost_rate_pay=1350.00. At commit time, partner B quote=1366.20 KRW/USD. Deviation = abs(1366.20-1350.00)/1350.00 = 1.2%, exceeds 1.0%. Engine must return error code PARTNER_B_QUOTE_DEVIATION without committing or deducting prefunding. Inputs otherwise same as RV-01.
**Steps:** Create RateEngineRV06Test.java; mock partner B quote API to return 1366.20 at commit time.; Invoke the rate engine commit path.; Assert the result is an error with code PARTNER_B_QUOTE_DEVIATION.; Assert no transaction record is persisted (check DB or repository mock received no save call).; Assert no prefunding deduction event is emitted.
**Deliverable:** RateEngineRV06Test.java with test method testRV06_partnerBQuote_overTolerance_rejectsWithError.
**Acceptance / logic checks:**
- Error code PARTNER_B_QUOTE_DEVIATION returned when deviation=1.2%.
- No transaction committed to the database.
- Prefunding balance unchanged (no deduction event).
- Error code matches the API-05 error catalog constant (not a freeform string).
- Tolerance boundary: deviation exactly 1.0% does NOT trigger error (verify with a separate assertion or parameterized case).
**Depends on:** 4.9-T01

### 4.9-T08 — Test RV-07: min-margin boundary exactly 2% combined (rule accepted, transaction commits)  _(25 min)_
**Context:** Min combined margin rule for cross-border rules: m_a + m_b >= 2.0%. At exactly 2.0% the rule must be accepted at config time and transactions must commit. RV-07 inputs: target_payout=13500 KRW, coll_ccy=MNT, payout_ccy=KRW, cost_rate_coll=3500.00, cost_rate_pay=1350.00, m_a=0.010, m_b=0.010 (combined=0.020), service_charge=500 MNT. Expected: collection_usd = 10.0000 / 0.980 = 10.2041 USD (tolerance 0.0001); rule saved without error; transaction commits.
**Steps:** Create RateEngineRV07Test.java with m_a=0.010, m_b=0.010 (combined exactly 2.0%).; Invoke rule-config validation path; assert no MARGIN_BELOW_MINIMUM error.; Run rate engine with these margins; assert collection_usd = 10.2041 USD (tolerance 0.0001).; Assert combined margin recorded as 0.020 (2.0%) in the output.; Call assertPoolIdentity(output).
**Deliverable:** RateEngineRV07Test.java with test method testRV07_minMarginBoundary_exactly2pct_accepted.
**Acceptance / logic checks:**
- Rule config validation passes without error when m_a+m_b=0.020 (exactly 2.0%).
- collection_usd = 10.2041 USD within 0.0001.
- Combined margin 0.020 stored in output.
- Pool-identity assertion passes.
- Transaction commits successfully (no error thrown).
**Depends on:** 4.9-T01

### 4.9-T09 — Test RV-08: below-minimum margin 1.9% combined (rejected at config time)  _(30 min)_
**Context:** Min combined margin for cross-border rules is 2.0%. Any rule with m_a+m_b < 0.020 must be rejected at Admin System config-save time, before any transaction can be attempted. RV-08 inputs: target_payout=13500 KRW, coll_ccy=MNT, payout_ccy=KRW, cost_rate_coll=3500.00, cost_rate_pay=1350.00, m_a=0.010, m_b=0.009 (combined=1.9%), service_charge=500 MNT. Expected: rule save rejected with a validation error referencing the 2.0% minimum; no transaction possible under this rule.
**Steps:** Create RateEngineRV08Test.java; invoke the rule-config validation service with m_a=0.010, m_b=0.009.; Assert that a MARGIN_BELOW_MINIMUM (or equivalent) validation error is thrown/returned.; Assert the error message explicitly references the 2.0% minimum constraint.; Confirm no rate-engine computation is attempted (verify mock or spy was not called).; Attempt to call the rate engine directly with these margins (bypassing config) and verify it also rejects the input.
**Deliverable:** RateEngineRV08Test.java with test method testRV08_belowMinMargin_1pt9pct_rejectedAtConfig.
**Acceptance / logic checks:**
- Rule save raises MARGIN_BELOW_MINIMUM when m_a+m_b=0.019.
- Error message text references the 2.0% minimum constraint.
- No rate-engine computation invoked for the rejected rule.
- Direct rate-engine call with combined margin 0.019 also returns an error (double guard).
- m_a+m_b=0.020 does NOT raise the error (boundary test from RV-07 confirms the cut-off is exclusive below 0.020).
**Depends on:** 4.9-T01, 4.9-T08

### 4.9-T10 — Test RV-09: rounding edge case (non-divisible payout 10001 KRW)  _(30 min)_
**Context:** Rate engine must use decimal arithmetic throughout; rounding applied only at collection_amount layer (final output), not at intermediate steps. RV-09 inputs: target_payout=10001 KRW, coll_ccy=MNT, payout_ccy=KRW, cost_rate_coll=3500.00, cost_rate_pay=1350.00, m_a=0.015, m_b=0.010, service_charge=500 MNT. Expected intermediates: payout_usd_cost=10001/1350.00=7.40815 USD, collection_usd=7.40815/0.975=7.59810 USD, send_amount=7.59810*3500.00=26593.33 MNT. Pool-identity delta must be <= 0.01 USD; all stored values must carry at least 4 decimal places.
**Steps:** Create RateEngineRV09Test.java with target_payout=10001 KRW (odd, non-round value).; Assert payout_usd_cost = 7.40815 USD (tolerance 0.00001).; Assert collection_usd = 7.59810 USD (tolerance 0.00001).; Assert send_amount = 26593.33 MNT (tolerance 0.01); verify the value stored in DB/output has at least 4 decimal places.; Call assertPoolIdentity(output) and confirm delta <= 0.01 USD.
**Deliverable:** RateEngineRV09Test.java with test method testRV09_roundingEdgeCase_nonDivisiblePayout.
**Acceptance / logic checks:**
- payout_usd_cost = 7.40815 USD within 0.00001 (intermediate stored without premature rounding).
- collection_usd = 7.59810 USD within 0.00001.
- send_amount = 26593.33 MNT within 0.01.
- Pool-identity delta <= 0.01 USD.
- All intermediate BigDecimal values stored with scale >= 4 in output object (spot-check via reflection or accessor).
**Depends on:** 4.9-T01

### 4.9-T11 — Test RV-10: service-charge separation (large charge 5000 MNT, USD pool unchanged)  _(30 min)_
**Context:** service_charge is a flat fee in Settle A currency added AFTER the USD pool computation; it never enters the USD pool. collection_amount = send_amount + service_charge. RV-10 inputs: same as RV-01 (target_payout=13500 KRW, cost_rate_coll=3500.00, cost_rate_pay=1350.00, m_a=0.015, m_b=0.010) but service_charge=5000 MNT (vs 500 MNT in RV-01). Expected: collection_usd=10.2564 USD (identical to RV-01), send_amount=35897.44 MNT (identical to RV-01), collection_amount=35897.44+5000=40897.44 MNT. Pool-identity check uses only collection_usd/margins/payout_usd_cost (service_charge excluded).
**Steps:** Create RateEngineRV10Test.java; use RV-01 base fixture but set service_charge=5000 MNT.; Assert collection_usd = 10.2564 USD (tolerance 0.0001) — same as RV-01 confirming service_charge does not affect USD pool.; Assert send_amount = 35897.44 MNT (tolerance 0.01) — identical to RV-01.; Assert collection_amount = 40897.44 MNT (tolerance 0.01).; Verify service_charge is recorded separately in revenue ledger fields, not folded into send_amount.; Call assertPoolIdentity(output) confirming pool identity holds without service_charge in the formula.
**Deliverable:** RateEngineRV10Test.java with test method testRV10_serviceChargeSeparation_largeCharge.
**Acceptance / logic checks:**
- collection_usd == 10.2564 USD (identical to RV-01; service_charge has no effect on pool).
- send_amount == 35897.44 MNT (identical to RV-01).
- collection_amount == 40897.44 MNT within 0.01.
- service_charge (5000 MNT) present in revenue ledger field, absent from USD-pool fields.
- Pool-identity assertion passes (formula excludes service_charge).
**Depends on:** 4.9-T01, 4.9-T02

### 4.9-T12 — Wire RV-01..RV-10 test suite into CI and enforce zero-tolerance gate  _(45 min)_
**Context:** All ten rate-engine test vectors must run in CI on every pull request and must pass with zero failures as a non-negotiable gate (per PM-14: rate-engine test vectors are a non-negotiable acceptance criterion). Pool-identity tolerance is <= 0.01 USD; KRW and USD final amounts must match expected values exactly within floating-point tolerance. The CI job must fail the build if any vector fails.
**Steps:** Add rate-engine-tests module to the CI pipeline (GitHub Actions or equivalent) under a step named rate-engine-vectors.; Configure the step to run only RV-01..RV-10 test classes (tag or test suite class) so failures are surfaced distinctly from other unit tests.; Set the CI step to exit-code 1 on any test failure (fail-fast: do not continue to integration tests if vectors fail).; Add a build badge or test-report artifact that shows the 10 vector results per run.; Verify locally that a deliberate mutation to the rate engine formula (e.g., change divisor from 0.975 to 0.97) causes exactly the relevant vector(s) to fail and the CI step to report red.
**Deliverable:** CI pipeline configuration (e.g., .github/workflows/rate-engine-vectors.yml or equivalent step in existing pipeline) that runs the 10 vector tests as a mandatory gate.
**Acceptance / logic checks:**
- CI step rate-engine-vectors runs on every PR and push to main.
- All 10 vector tests (T02..T11) appear in the test report by name.
- Deliberate formula mutation causes CI gate to fail (mutation test confirmed locally).
- CI step is listed as a required status check protecting the main branch.
- Test report artifact is uploaded and accessible from the CI run summary.
**Depends on:** 4.9-T02, 4.9-T03, 4.9-T04, 4.9-T05, 4.9-T06, 4.9-T07, 4.9-T08, 4.9-T09, 4.9-T10, 4.9-T11


## WBS 8.3 — POST /rates (quote)
### 8.3-T01 — Define RateQuoteRequest and RateQuoteResponse DTOs in lib-api-contracts  _(30 min)_
**Context:** POST /v1/rates (API-05 sec4.2): request fields target_payout string decimal, payout_currency ISO-4217, scheme_id string, direction domestic|inbound|outbound|hub, optional merchant_qr, optional partner_ref. Response: quote_id, offer_rate, send_amount, service_charge, service_charge_currency, collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, cross_rate, valid_until ISO-8601 UTC, scheme_id, direction, partner_ref. USD pool fields omitted for domestic (same-ccy). m_a, m_b, cost_rate_* NEVER in response. Module: lib-api-contracts.
**Steps:** Create RateQuoteRequest.java in lib-api-contracts/src/main/java/com/gme/pay/api/rates/ with JSR-380 annotations: @NotBlank on target_payout, @NotBlank @Size(3) on payout_currency, @NotBlank on scheme_id, @Pattern(regexp=domestic|inbound|outbound|hub) on direction; Create RateQuoteResponse.java with all response fields as String; USD pool fields annotated @JsonInclude(NON_NULL); Add OpenAPI @Schema annotations per API-05 field descriptions; ./gradlew :lib-api-contracts:build to verify
**Deliverable:** lib-api-contracts/src/main/java/com/gme/pay/api/rates/RateQuoteRequest.java and RateQuoteResponse.java
**Acceptance / logic checks:**
- @Valid rejects null target_payout
- direction rejects values other than domestic|inbound|outbound|hub
- USD pool fields absent from JSON when null (NON_NULL)
- Decimal amounts serialize as strings not numbers
- m_a, m_b, cost_rate_* fields absent from RateQuoteResponse

<!-- wbs-v3-gap-closure -->

---

## WBS v3 gap-closure tickets (re-baseline, 2026-06-10)

These tickets convert this service's PARTIAL audit findings into DONE and add work discovered during the build. Statuses live on the `Backlog` sheet of `GMEPay+_Task_Backlog.xlsx`; phase sequencing on the `Completion Plan v3` sheet of `GMEPay+_WBS.xlsx`.

### 17.2-G02 — rate-fx: swap H2 for real PostgreSQL ITs
*Completion phase:* **R1** · *Est:* 120 min · *Role:* Backend · *Deps:* 17.1-G02

**Context.** Tests currently run on H2 in PostgreSQL mode. Acceptance requires real PG. Scope: rate snapshots + quote TTL tables.

**Steps.**
- Add Testcontainers postgres:16 to the service's ITs
- Run Flyway migrations against it; fix PG-only syntax drift
- Keep H2 only for pure unit slices

**Deliverable.** Repository/migration ITs green on PostgreSQL 16

**Acceptance.**
- ./gradlew :services:rate-fx:test green with Testcontainers
- Migration checksum stable; no H2-mode workarounds left

### 17.3-G01 — Redis-backed rate-quote TTL store
*Completion phase:* **R1** · *Est:* 100 min · *Role:* Backend · *Deps:* 17.1-G02

**Context.** Quote TTL/rate-lock is in-memory; restart loses locks. Move to Redis with TTL semantics.

**Steps.**
- spring-data-redis + lettuce
- Key rq:{quoteId} with EXPIRE = quote TTL
- IT via Testcontainers redis

**Deliverable.** Quotes expire via Redis TTL

**Acceptance.**
- Lock survives service restart within TTL
- Expired quote rejected with deterministic error code

---

<!-- ws-21-partner-setup-rebaseline -->

## Partner Setup re-baseline tickets (WS 21)

These tickets close Partner Setup audit gaps under the 8-slice vertical plan in `docs/PARTNER_SETUP_PLAN.md` (approved 2026-06-11). Each ticket id `21.{slice}-Pxx` maps to a wizard slice; ADR references point at `docs/adr/`. Tickets owned by **rate-fx** live here; cross-service contributions are listed at the bottom for awareness.

> Note: legacy WP 10.3 entries on the WBS spreadsheet remain in place but are flagged *superseded by WS 21 — see docs/PARTNER_SETUP_PLAN.md*.

### Slice 6 tickets owned by this service

### 21.6-P08 — rate-fx: read partner_fx_config + apply margin_bps + honor reference source
*Slice:* **6** · *Est:* 90 min · *Role:* Backend · *Owner:* rate-fx · *ADR refs:* —

**Context.** Wire rate-fx to pull margin from partner_fx_config instead of the static rule.m_a / m_b. Reference source switch routes to the configured rate feed.

**Steps.** Update services/rate-fx/src/main/java/com/gme/pay/rate/QuoteEngine.java to read partner_fx_config via config-registry REST; apply margin_bps on top of the reference rate; if reference_rate_source=PARTNER_PROVIDED call the partner-supplied rate endpoint (Slice 7+); honor quote_hold_seconds when caching the quote.

**Deliverable.** `services/rate-fx/src/main/java/com/gme/pay/rate/QuoteEngine.java`

**Acceptance.**
- Partner with margin_bps=150 (1.5%) on a mid-market USD/KRW=1380 gets quote 1399.7
- quote_hold_seconds=600 keeps the quote retrievable for 10 minutes
- PARTNER_PROVIDED source missing endpoint falls back to MID_MARKET with WARN log
- Existing rate-fx 5-step engine boundary tests still green

