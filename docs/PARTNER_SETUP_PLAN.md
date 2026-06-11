# GMEPay+ — Partner Setup Re-baseline Plan (v1)

**Status:** Approved 2026-06-11 (user mandate: "this runs 20 years, no cutting corners").
**Supersedes:** the 4-field `/partners/new` aspiration (partnerId/type/settlementCurrency/settlementRoundingMode) — that form now reads as a demo proof, not the production aggregate.
**Drives:** WBS workstream **WS 21 — Partner Setup re-baseline**, work packages **21.1 → 21.8**, ticket prefix **`-Pxx`**.
**Architecture:** locked by ADR-006 through ADR-014 (`docs/adr/`).

## Why re-baseline

The 22-agent audit (`docs/WBS_STATUS.md`) showed broad scaffolding but no acceptance-check coverage. A second targeted audit (2026-06-11) of the Partner Setup feature alone found **70+ gap items** across 9 themes against a real cross-border QR-payment partner onboarding:

| Theme | BLOCKER count for first prod partner |
|---|---|
| Identity & legal | 6 (legal name local+romanized, tax ID, country of incorporation, legal form, structured address, LEI) |
| KYB & compliance | 5 (license + expiry, UBO + PEP, sanctions screening + decision, risk rating, Wolfsberg CBDDQ) |
| Contacts | 3 (primary email, low-balance alert recipients, role-based contacts) |
| Banking & payouts | 4 (settlement bank account + verification, T+N cycle, settlement method, business-day calendar) |
| Prefunding | 5 (balance row wiring, threshold, funding model, credit limit, float top-up) |
| Commercial terms | 5 (ccy split, fee schedule, FX margin, caps, contract dates) |
| Technical credentials | 3 (API key/HMAC, Principal, webhook) — wire-up debt, data exists |
| Schemes & corridors | 5 (enablement matrix, ZeroPay merchantId, role, corridor matrix, institution code) |
| Lifecycle & audit | 5 (status enum, activation gate, suspension reasons, audit_log, 4-eyes) |
| Regulatory reporting | 3 (BOK 외환거래보고, Hometax e-tax, KoFIU STR/CTR) |

Plus **two cross-cutting bugs** that block every fix:
1. **Partner ID type schism** — `Partner.partnerId` is `String`, but `PrincipalEntity`, `WebhookEndpointEntity`, settlement-reconciliation, and `PartnerCredentialPort` use `Long`. Every wire-up join hits this wall.
2. **Five Partner DTO shapes drift** — `Partner` (lib-domain), `PartnerEntity`, `PartnerProfile`, `PartnerSummary`, `PartnerCreateRequest`. Every new field has to be added to all five.

Both fixed in Slice 1 before any new field lands.

## Approach — 8 vertical slices, backend + UI together

Per user preference: no hidden foundation phase. Each slice ships a wizard step plus the foundation primitive it needs underneath. Operators see progress every iteration. Foundation primitives are introduced exactly when first needed.

```
Slice 1: Identity            ─ bitemporal, change_request, audit_log, Keycloak, ID/DTO fixes
   │
Slice 2: Contacts            ─ 4-eyes surfaced in UI; child-entity pattern hardened
   │
Slice 3: KYB                 ─ MinIO vault + Octa Solution adapter + document_version
   │
Slice 4: Banking & Settlement ─ multi-row bank accounts + 계좌실명조회 + business-day calendars
   │
Slice 5: Prefunding (OVERSEAS) ─ wire existing PartnerBalanceEntity into partner-create txn
   │
Slice 6: Commercial Terms    ─ ccy split + rule persistence + fee + FX + caps + contract
   │
Slice 7: Schemes & Corridors  ─ partner×scheme×corridor join + data-driven SchemeRouter
   │
Slice 8: Credentials + Lifecycle + Reporting ─ API key/HMAC/webhook one-time modal, full FSM, BOK + Hometax + KoFIU
```

Each slice ends with an **exit gate** signed by the user. Don't start N+1 until N's gate is signed.

## Slice 1 — Identity + Foundation

**Scope:** wizard scaffold + step 1 (Identity), all foundation primitives that every later slice depends on.

**Backend:**
- Resolve String↔Long Partner ID schism (cross-cutting bug #1). Pick **`partner_id BIGINT` surrogate** with **`partner_code VARCHAR(20)`** unique business key (matches DAT-03 §4.3 and the existing PrincipalEntity/WebhookEndpointEntity Long).
- Collapse 5 Partner DTOs (cross-cutting bug #2) to one canonical `PartnerView` DTO surface; introduce `PartnerCommand` (create/update payloads) separate from read DTO.
- Bitemporal `partner` table per ADR-010 (valid_from/to + recorded_at/superseded_at; SCD Type 6 with current-flag).
- `change_request` table + Spring State Machine per ADR-008 (DRAFT → PROPOSED → APPROVED → APPLIED, REJECTED terminal).
- `audit_log` infrastructure per ADR-007: dedicated `audit` Postgres DB with INSERT-only role + hash chain; Kafka topic `gmepay.audit.partner`; `lib-audit` library.
- Keycloak service per ADR-011 (docker-compose service, Rocky 9-based, Postgres-backed); api-gateway becomes OAuth2 resource server; ops-partner-bff `password=demo` flow retired.
- Identity fields on partner aggregate: `partner_code` (UNIQUE), `legal_name_local`, `legal_name_romanized`, `tax_id` (with type discriminator: KR-BRN / KH-VAT / VN-MST / SG-UEN / generic), `country_of_incorporation` (ISO-3166 alpha-2), `legal_form` (enum: CORP/LLC/MTO/EMI/BANK/OTHER), `registered_address` (structured: street1, street2, city, state, postcode, country), `operating_address` (same), `lei` (ISO 17442, optional but tracked).
- Draft endpoints: `POST /v1/admin/partners/draft` (create empty draft), `PATCH /v1/admin/partners/draft/{id}/step-1`, `GET /v1/admin/partners/draft/{id}`, `GET /v1/admin/partners/drafts` (list).
- Wire-up: `RestConfigRegistryClient` adapts to the new `PartnerView` shape; admin-ui `client.js` updated.

**UI:**
- Wizard scaffold (8 steps as tabs/stepper, server-side draft persistence per ADR-012).
- Step 1 (Identity) form with the fields above + Yup validation per regional format.
- Draft list on `/partners` (list partners + drafts side-by-side, "Resume" button).
- Keycloak login replacing `password=demo` (admin-ui auth slice: Keycloak OIDC redirect; logout; refresh).

**Exit gate for Slice 1:**
- Operator logs into admin-ui via Keycloak (no `password=demo` left).
- Operator creates a Draft Identity for a new partner; the row appears in `partner` with `status=ONBOARDING` and a `change_request` in `state=DRAFT`.
- Closing the browser and returning by URL resumes the draft mid-edit.
- A second operator can be a checker; self-approval is DB-rejected by the CHECK constraint.
- audit_log carries the BEFORE/AFTER rows for every write, with the hash chain verifying.
- Bitemporal queries work: `findCurrent(partner_code)` returns latest; `findAsOf(partner_code, validAt, recordedAt)` returns the historical view.
- `gradlew :services:config-registry:test :services:auth-identity:test :services:ops-partner-bff:test` all green.
- Admin-ui vitest + Next.js build green.

**Tickets:** `21.1-P01..P22` (estimate: 22 tickets — 14 backend, 8 UI).

## Slice 2 — Contacts

**Scope:** wizard step 2 + the 4-eyes machinery surfaced in UI.

**Backend:**
- `partner_contact` child table (bitemporal): `id BIGSERIAL`, `partner_id FK`, `role` enum (OPS_24X7, FINANCE, COMPLIANCE_MLRO, TECH, LEGAL, INCIDENT), `name`, `email`, `phone_e164`, `is_authorized_signatory BOOL`, `notes`.
- `change_request` already exists from Slice 1; reuse for contact mutations.
- BFF endpoints: `PATCH /v1/admin/partners/draft/{id}/step-2` (bulk replace contacts), `POST /v1/admin/partners/{id}/contacts` (proposing change), `POST /v1/admin/partners/change-requests/{id}/approve`.

**UI:**
- Step 2 form: multi-row contact editor with role dropdown + E.164 phone validation.
- Generic "Approval queue" page where checker operators see pending PROPOSED change_requests; click Approve / Reject (with reason).
- Audit log viewer (read-only, paginated) on partner detail page.

**Exit gate:**
- ≥4 role contacts required before activation pre-condition (enforced by gate, not Yup — Yup just nudges).
- Bank-account changes (later slices) require 2 authorized-signatory approvals; DB-enforced.
- Audit log shows old/new for every contact change.

**Tickets:** `21.2-P01..P15`.

## Slice 3 — KYB

**Scope:** wizard step 3 + document vault + Octa Solution adapter.

**Backend:**
- MinIO bucket `gmepay-partner-vault` with object-lock compliance mode per ADR-006; `lib-vault` library wrapping MinIO SDK with virus-scan hook + Vault-key encryption.
- `partner_document` (bitemporal): `id`, `partner_id FK`, `doc_type` enum (LICENSE, CERT_INCORPORATION, AOA, BOARD_RESOLUTION, UBO_DECLARATION, FINANCIALS, CBDDQ, OTHER), `vault_uri`, `version`, `expiry_date`, `verified_by`, `verified_at`.
- `partner_kyb` (bitemporal): risk_rating enum (LOW/MEDIUM/HIGH), risk_rationale, next_review_date, license_type, license_number, license_authority, license_expiry, ubo_set_jsonb (array of {name, ownership_pct, is_pep, country}), wolfsberg_cbddq_doc_id FK to partner_document.
- `KybProvider` port in `lib-kyb` per ADR-009.
- `StubKybAdapter` for Slice 3 internal dev; `OctaKybAdapter` lands later when Octa Solution sandbox creds are available.
- Kafka topic `gmepay.kyb.screening` for screening results; outbox-published in same txn.
- New service `services/kyb-adapter` (Spring Boot module owning the vendor integration).

**UI:**
- Step 3 form: license details, UBO entry table (name + ownership % + PEP toggle + country), document upload area with drag-drop, Wolfsberg CBDDQ upload, "Run screening" button.
- Document viewer modal (read-only, with version history).
- Screening result panel (latest result, decision history, rescreen button).

**Exit gate:**
- Operator uploads a license PDF → stored in MinIO with versioning + object-lock; row in `partner_document`.
- Operator runs screening via `StubKybAdapter`; result stored in `partner_kyb`; Kafka event published.
- Risk rating + rationale + next review date all captured.
- Tamper attempt: directly UPDATE `partner_kyb.risk_rating` via psql → audit_log hash chain detects the drift on next validate.

**Tickets:** `21.3-P01..P18`. **Calendar dep:** Octa Solution sandbox creds (not blocking Slice 3 — falls back to Stub).

## Slice 4 — Banking & Settlement

**Scope:** wizard step 4 — settlement bank accounts, settlement cycle, methods, calendars.

**Backend:**
- `partner_bank_account` (bitemporal): `id`, `partner_id FK`, `currency` (ISO-4217), `bank_name`, `bic_swift`, `iban_or_account_number`, `account_holder_name`, `bank_country` (ISO-3166), `intermediary_bic` (optional), `verification_status` enum (UNVERIFIED, KFTC_VERIFIED, BANK_LETTER, MICRO_DEPOSIT), `verification_evidence_doc_id` FK, `verification_date`, `is_primary` BOOL, `swift_charge_bearer` enum (OUR/BEN/SHA), `purpose` enum (PAYOUT, FLOAT_TOPUP, REFUND).
- `partner_settlement_config` (bitemporal): `partner_id PK`, `cycle_t_plus_n` INT, `cutoff_time` TIME, `cutoff_timezone` VARCHAR, `settlement_method` enum (SWIFT_MT103, KR_FIRM_BANKING, BAKONG, NAPAS_247, PROMPT_PAY, FAST_SG, OTHER).
- `business_day_calendar`: seed Korean + ASEAN holidays for go-live + 2 years (Chuseok, Tet, Songkran, Lunar New Year, etc.).
- KFTC 계좌실명조회 verification stub — port abstraction `AccountVerificationProvider` with `KftcVerificationAdapter` + `StubVerificationAdapter`.

**UI:**
- Step 4 form: bank account multi-row editor with per-row verification button, settlement config panel (cycle/cutoff/method/timezone picker), settlement preview ("with these settings, your Mon 11:30 KST txn pays out Wed 11:30 KST").

**Exit gate:**
- ≥1 verified bank account per `settle_a_ccy` configured.
- Settlement cycle preview correctly handles holiday roll (e.g. Friday cutoff with T+1 + Chuseok rolls to Tuesday).
- Bank account changes require 2 authorized-signatory approvals (cross-checks ADR-008 4-eyes).

**Tickets:** `21.4-P01..P20`.

## Slice 5 — Prefunding (OVERSEAS partners only)

**Scope:** wizard step 5 + wire-up of existing `PartnerBalanceEntity`.

**Backend:**
- Wire `services/prefunding/PartnerBalanceEntity` into the partner-create transaction via the BFF: when a partner is activated with `funding_model=PREFUNDED` or `HYBRID`, the prefunding service creates the balance row in the same change_request.
- New fields on `partner_prefunding_config` (bitemporal): `funding_model` enum (PREFUNDED, POSTPAID, HYBRID), `opening_balance_usd` NUMERIC, `low_balance_threshold_usd` (default 10000), `alert_tier_70_pct`, `alert_tier_85_pct`, `alert_tier_95_pct`, `credit_limit_usd` (NULL = no limit), `auto_suspend_on_breach` BOOL, `float_top_up_bank_account_id` FK to partner_bank_account (with `purpose=FLOAT_TOPUP`), `top_up_reference_pattern` VARCHAR (e.g. `GMP-{partner_code}-{yyyyMMdd}`), `collateral_amount_usd` (optional).
- Tier-alert generation: Kafka topic `gmepay.prefunding.alert` produced when balance crosses threshold; `notification-webhook` already consumes from kafka.
- Auto-suspend rule: when balance < 0 (or breach + `auto_suspend_on_breach=true`), publish a `change_request(aggregate=partner, payload=status:SUSPENDED)` with `proposed_by='system'`, requires operator approval to lift.

**UI:**
- Step 5 form (visible only for OVERSEAS): funding model radio, opening balance + currency, threshold inputs with live preview, credit limit, top-up bank picker (filtered to FLOAT_TOPUP purpose accounts), reference pattern preview.
- Prefunding dashboard tile on partner detail page: current balance, threshold gauge with tier coloring, recent top-ups, recent alerts.

**Exit gate:**
- OVERSEAS partner creates prefunding row + balance row in same txn.
- Push balance to 65% threshold → no alert; push to 75% → tier-70 alert; push to 88% → tier-85 alert; push to <0 → auto-suspend change_request proposed.
- Float top-up reference correctly auto-reconciles incoming wire to partner ledger.

**Tickets:** `21.5-P01..P17`.

## Slice 6 — Commercial Terms (ccy split + rules + fees + FX + limits + contract)

**Scope:** wizard step 6 — the financial-correctness slice.

**Backend:**
- Split `settlement_currency` into `collection_ccy` + `settle_a_ccy` (per DAT-03 §4.3 + PRD-07 §5.3.2). Migration follows Expand/Backfill/Contract (ADR-013) across 3 releases.
- Rule persistence in config-registry: today only `POST /v1/rules/validate` exists; add `POST /v1/rules` (create) + `GET /v1/rules` (list per partner) + `PUT /v1/rules/{id}` (update via change_request). Schema includes the existing lib-domain Rule.validate `m_a + m_b >= 2%` invariant.
- `partner_fee_schedule` (bitemporal): per (partner × scheme × direction): fixed_fee, bps_fee, tier table (volume bands → bps).
- `partner_fx_config` (bitemporal): margin_bps, reference_rate_source enum (SEOUL_FX_BROKER, PARTNER_PROVIDED, MID_MARKET), quote_hold_seconds (default 300, range 60-1800).
- `partner_limits` (bitemporal): per_txn_min_usd, per_txn_max_usd, daily_cap_usd, monthly_cap_usd, annual_cap_usd. 소액해외송금업 hard-enforced server-side: per_txn ≤ 5000 USD, annual ≤ 50000 USD for partners with that license type.
- `partner_contract` (bitemporal): effective_from, effective_to, auto_renewal BOOL, notice_period_days, refund_chargeback_policy enum (PARTNER_BEARS, MERCHANT_BEARS, SHARED), termination_reason.
- transaction-mgmt + rate-fx services updated to read the new collection_ccy / settle_a_ccy split.

**UI:**
- Step 6 form: split ccy picker (collect vs settle), rule editor (multi-row per scheme/direction with margin sliders), fee schedule (fixed + bps + optional tier table), FX margin slider + reference source picker + quote hold input, limits with 소액해외송금업 visual cap markers, contract dates + auto-renewal toggle.

**Exit gate:**
- Configure a complete corridor (collect KRW / settle USD, scheme=ZEROPAY direction=OUTBOUND, fees, FX margin, contract dates).
- Preview rate via rate-fx using the new rule rows; result matches the pricing formula.
- Attempt to POST a transaction at USD 5,001 → server refuses with `LIMIT_BREACH per_txn_max=5000 (소액해외송금업)`.
- Attempt to POST a transaction outside the contract effective dates → server refuses with `CONTRACT_NOT_ACTIVE`.

**Tickets:** `21.6-P01..P25`.

## Slice 7 — Schemes & Corridors

**Scope:** wizard step 7 — scheme enablement + corridor matrix + per-partner scheme credentials.

**Backend:**
- `partner_scheme` (bitemporal): `partner_id`, `scheme_id`, `direction` enum (INBOUND, OUTBOUND, BOTH), `role` enum (ACQUIRER, ISSUER, BOTH), `zeropay_merchant_id`, `zeropay_sub_merchant_id`, `kftc_institution_code`, `partner_type_char` enum (D, I) — Zp0011 derivation.
- `partner_corridor` (bitemporal): `partner_id`, `src_country`, `src_ccy`, `dst_country`, `dst_ccy`, `go_live_date`, `is_active`.
- SchemeRouter rewrite from hardcoded `KR → ZEROPAY` to data-driven (reads partner_scheme join).
- Per-partner scheme credentials stored in Vault (per ADR-006 pattern), referenced by `partner_scheme.vault_secret_id`.
- Approval method per (partner × scheme × payment_mode CPM/MPM): `approval_method` enum (CONFIRMATION, SILENT). Closes OI-01 / 1.2-T03.
- Operating hours per scheme (settlement cutoffs): seeded for ZeroPay (24×7 with KFTC 16:30 KST cutoff), Bakong (24×7 with NBC 09:00-15:00 ICT), etc.

**UI:**
- Step 7 form: scheme enablement matrix (rows = schemes, columns = direction × role, checkboxes), per-row drill-down for ZeroPay specifics (merchantId, sub-merchantId, institution code, D/I, approval method per CPM/MPM), corridor matrix builder (src country/ccy → dst country/ccy multi-select), operating hours preview.

**Exit gate:**
- Onboard a 2nd KR partner with different ZeroPay sub-merchant config — SchemeRouter routes correctly by data without code change.
- Toggle a corridor active/inactive; transactions on inactive corridor are rejected at the gateway.
- Zp0011 batch correctly derives D/I from per-partner config (not from a global flag).

**Tickets:** `21.7-P01..P22`.

## Slice 8 — Credentials + Lifecycle + Reporting

**Scope:** wizard step 8 — credential issuance + full FSM + regulatory reporting attributes.

**Backend:**
- Wire existing `auth-identity/ApiKeyEntity` + `PrincipalEntity` + `notification-webhook/WebhookEndpointEntity` into the partner-create transaction (the wire-up debt). Generate API key + HMAC secret on activation, return plaintext ONCE in the response, store hash.
- IP allowlist: `partner_ip_allowlist` table (up to 10 CIDRs per partner); api-gateway pre-signature 403 enforcement.
- mTLS cert exchange: `partner_mtls_cert` (bitemporal): cert_pem, fingerprint_sha256, expiry_date, status. mTLS verification at the Nginx layer (ADR-002).
- Sandbox vs production credential pairs: `partner_credential_environment` enum (SANDBOX, PRODUCTION); each partner gets both pairs; sandbox prefix `pk_test_` / `sk_test_`.
- Credential rotation: scheduled job that proposes rotation `change_request` at 11 months (12-month rotation per SEC-09); revoke endpoint with audit_log entry.
- Full FSM: `Draft → KYB-Pending → KYB-Approved → Contract-Signed → Sandbox → UAT → Live → Suspended → Terminated`. State transitions are change_request-gated. Go-live + Suspend require 4-eyes.
- Activation pre-condition gate: returns 422 + `unmet[]` until: legal_name set, KYB approved, ≥1 verified bank account per settle_ccy, contracts signed, prefunding row created if OVERSEAS, ≥4 role contacts, ≥1 scheme enabled, sanctions clear or operator-overridden.
- Post-activation immutability: partner_code, country_of_incorporation, partner_type, collection_ccy, settle_a_ccy locked after first `Live` transition; mutation attempts return 400 with `IMMUTABLE_AFTER_ACTIVATION`.
- Suspension reason codes enum: LIMIT_BREACH, SANCTIONS_HIT, CREDENTIAL_COMPROMISE, KYB_LAPSED, CONTRACT_EXPIRED, OPERATOR_INITIATED.
- BOK 외환거래보고 attributes on partner: `bok_txn_code`, `bok_fx_reporting_category` enum (INDIVIDUAL_AGGREGATE, INSTITUTIONAL), `bok_remitter_type` enum.
- Hometax e-tax-invoice settings: `hometax_issuer_cert_id` FK to vault doc, `vat_treatment` enum (ZERO_RATED_EXPORT, STANDARD, EXEMPT).
- KoFIU STR/CTR feed config: `kofiu_entity_id`, `ctr_threshold_krw` (default 10000000), per-corridor STR enable flag.
- PIPA cross-border PII transfer notice: `pipa_jurisdiction_allowlist` array, `legal_basis_code` enum.
- Travel Rule (TRP/Sygna/IVMS101): `travel_rule_protocol` enum + endpoint config; ≥KRW 1M transfers must include IVMS101 originator + beneficiary block.

**UI:**
- Step 8: Review tab (full partner summary with anchor jumps to earlier steps), Activate button (calls pre-condition gate; shows `unmet[]` if any), one-time credential modal (API key + HMAC + webhook signing secret displayed once with copy-to-clipboard, dismiss confirmation), confirmation that operator has stored secrets.
- Partner detail page (post-activation): status header with FSM transitions menu, credential rotation panel, audit log full view, BOK/Hometax/KoFIU reporting settings tab, status-action menu (Suspend/Reactivate/Terminate with reason).

**Exit gate:**
- A real first partner walks the entire FSM end-to-end: Draft → KYB-Pending (after Slice 3 screening passes) → KYB-Approved (after operator review with 4-eyes) → Contract-Signed (contract dates set) → Sandbox (test creds issued + verified) → UAT → Live (4-eyes on go-live).
- Partner hits API from an allow-listed IP with mTLS cert → request authenticated; same request from non-allow-listed IP → 403 pre-signature; expired cert → 403.
- Sandbox credentials work against sandbox endpoint; production credentials work against production endpoint; cross-environment use fails.
- Suspension flow: operator proposes suspend, 2nd operator approves; auth-identity revokes the active credentials within seconds; api-gateway rejects subsequent calls.
- BOK 외환거래보고 file generation correctly classifies the partner's txns by BOK code.
- Hometax e-tax-invoice issued for a settled fee.
- KoFIU CTR report triggered when daily aggregate crosses 10M KRW for a single end-user.

**Tickets:** `21.8-P01..P40`.

## Critical path & calendar

```
Slice 1 ───────────► Slices 2 (Contacts) + 3 (KYB) can run in parallel after 1.
   │                          │
   ▼                          ▼
Slice 2 ─────►  Slice 4 (Banking) ────► Slice 5 (Prefunding, OVERSEAS only)
                  │                          │
                  ▼                          ▼
Slice 6 (Commercial Terms) ─────► Slice 7 (Schemes & Corridors)
                                              │
                                              ▼
                                       Slice 8 (Credentials + Lifecycle + Reporting)
```

Sequential gates: 1 → (2 || 3) → 4 → 5 → 6 → 7 → 8. Slices 2 and 3 can be parallel-staffed after Slice 1's foundation lands. Slice 5 only runs for OVERSEAS partners but its config infrastructure must exist before activation gate.

**Effort estimate:**
- Per-slice single-dev: 1.5–2 weeks (Slice 1 is heaviest at ~2 weeks because of foundation; Slice 8 is heaviest at ~2.5 weeks because of FSM + regulatory).
- Per-slice with Workflow fan-out: ~3 days.
- **Total: 10–14 weeks single-dev, 6–8 weeks with fan-out**, calendar-bound by:
  - Octa Solution sandbox creds (Slice 3 deferred to stub until then) — calendar dep.
  - Vault deployment (R3 phase) — pulls in earlier if Slice 6 needs FX margin secret storage.
  - Keycloak ops setup (Slice 1) — straightforward but new infra component.

**External blockers (calendar-bound):**
- Octa Solution sandbox + production access (procurement track, user-driven).
- KFTC 계좌실명조회 API access for Slice 4 verification (real prod cert needed).
- BOK FX-reporting code mapping (OI-03 open item in WBS).
- Hometax e-tax-invoice issuer certificate (operational onboarding, not code).

## Cross-cutting bug fixes (Slice 1, prerequisite)

1. **Partner ID type schism** — adopt `partner_id BIGINT` surrogate + `partner_code VARCHAR(20)` unique business key. Migration via Expand/Backfill/Contract (ADR-013):
   - Release N: add `partner_id BIGSERIAL` to `partners`, populate from sequence; add `partner_code VARCHAR(20)` filled from current `partner_id String`.
   - Release N+1: PrincipalEntity / WebhookEndpointEntity / settlement-reconciliation / PartnerCredentialPort switch to read `partner_id` FK; old String references deprecated.
   - Release N+2: drop the old `partner_id String` column; rename `partner_code` to remain.
2. **Five Partner DTO shapes** — collapse to one canonical `PartnerView` (read DTO) + `PartnerCommand` (write DTO) in lib-api-contracts. Migration: keep old shape names as `@Deprecated` aliases for one release, then delete.

## What gets retired

The current `/partners/new` 4-field form is retained as a redirect to the new wizard's Slice 1 (Identity step) — operators landing on the old URL are bounced. The 4 fields the form collected (partnerId, type, settlementCurrency, settlementRoundingMode) become:
- `partnerId` → `partner_code` (renamed in Slice 1).
- `type` → still LOCAL/OVERSEAS but augmented by `legal_form` + `country_of_incorporation`.
- `settlementCurrency` → split into `collection_ccy` + `settle_a_ccy` in Slice 6.
- `settlementRoundingMode` → stays, moves to Slice 6 (commercial terms section).

## References

- ADR-006 through ADR-014 (`docs/adr/`) — architecture decisions.
- `docs/STACK.md` — full tech stack (now references this plan in §1 ADR section).
- `docs/INTER_SERVICE_CONTRACTS.md` — service boundaries.
- `docs/MONEY_CONVENTION.md` — BigDecimal money handling.
- `Documentation/GMEPay+_WBS.xlsx` — work breakdown (WS 21 added by this plan).
- `Documentation/GMEPay+_Task_Backlog.xlsx` — per-ticket detail (rows 21.x-Pxx added by this plan).
- `Documentation/services_backlog/ops-partner-bff.md`, `config-registry.md`, `auth-identity.md`, `notification-webhook.md`, `prefunding.md` — service bundles (updated per slice).
