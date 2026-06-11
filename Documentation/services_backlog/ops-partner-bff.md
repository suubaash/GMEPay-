# ops-partner-bff — service backlog bundle

Backend-for-frontend serving admin-ui and partner-portal-ui (19 endpoints, built ahead of WBS v2 — legitimized by WBS v3). Module `services/ops-partner-bff`. Speaks REST to 10 upstream services; stubs remain only under the `demo` profile.

<!-- wbs-v3-gap-closure -->

---

## WBS v3 gap-closure tickets (re-baseline, 2026-06-10)

These tickets convert this service's PARTIAL audit findings into DONE and add work discovered during the build. Statuses live on the `Backlog` sheet of `GMEPay+_Task_Backlog.xlsx`; phase sequencing on the `Completion Plan v3` sheet of `GMEPay+_WBS.xlsx`.

### 17.5-G02 — Flip BFF's 10 clients from Stub to REST
*Completion phase:* **R2** · *Est:* 240 min · *Role:* Backend · *Deps:* 17.5-G01

**Context.** BFF ships Stub*Client @Component beans; Rest* impls exist for some. Implement the missing Rest* clients and verify all 10 against live services.

**Steps.**
- Implement remaining Rest*Client beans (RestTemplate/RestClient)
- Profile 'live' activates REST, 'demo' keeps stubs
- Verify all 19 endpoints return live data

**Deliverable.** BFF live against real services

**Acceptance.**
- 19/19 endpoints 200 with live data on compose stack
- Stubs only under demo profile

### 18.1-G01 — Document BFF surface as INTER_SERVICE contract
*Completion phase:* **R2** · *Est:* 90 min · *Role:* Backend

**Context.** 19 endpoints exist but aren't in docs/INTER_SERVICE_CONTRACTS.md; UI agents had to read code. Freeze the BFF wire contract.

**Steps.**
- Add BFF section: 19 endpoints, DTO field tables
- Mark BigDecimal-as-string + ISO dates explicitly
- Link from UI bundles

**Deliverable.** Frozen BFF contract doc

**Acceptance.**
- Doc matches springdoc output; UI contract tests reference it

### 18.1-G02 — BFF auth pass-through + partner scoping
*Completion phase:* **R2** · *Est:* 140 min · *Role:* Backend · *Deps:* 18.4-G02

**Context.** Portal endpoints take partnerId from path with no authz: any caller can read any partner. Enforce JWT partner claim = path partnerId.

**Steps.**
- Security filter validating JWT (18.4)
- 403 on partner mismatch; admin role bypass
- Tests for scoping matrix

**Deliverable.** Partner isolation enforced

**Acceptance.**
- Cross-partner read returns 403 in tests

### 18.4-G02 — BFF validates JWT + role gates
*Completion phase:* **R3** · *Est:* 180 min · *Role:* Backend · *Deps:* 18.4-G01

**Context.** BFF AuthController currently fakes login. Delegate to auth-identity; spring-security resource server validates; method security on admin vs portal routes.

**Steps.**
- POST /v1/auth/login proxies to auth-identity
- Resource-server JWKS validation
- @PreAuthorize role checks per endpoint group

**Deliverable.** BFF secured by real JWT

**Acceptance.**
- Expired/forged token → 401; wrong role → 403; tests cover matrix

---

<!-- ws-21-partner-setup-rebaseline -->

## Partner Setup re-baseline tickets (WS 21)

These tickets close Partner Setup audit gaps under the 8-slice vertical plan in `docs/PARTNER_SETUP_PLAN.md` (approved 2026-06-11). Each ticket id `21.{slice}-Pxx` maps to a wizard slice; ADR references point at `docs/adr/`. Tickets owned by **ops-partner-bff** live here; cross-service contributions are listed at the bottom for awareness.

> Note: legacy WP 10.3 entries on the WBS spreadsheet remain in place but are flagged *superseded by WS 21 — see docs/PARTNER_SETUP_PLAN.md*.

### Slice 1 tickets owned by this service

### 21.1-P10 — Draft endpoint POST /v1/admin/partners/draft (create empty draft)
*Slice:* **1** · *Est:* 45 min · *Role:* Backend · *Owner:* ops-partner-bff · *ADR refs:* ADR-012

**Context.** Per ADR-012 the wizard persists drafts server-side. Slice 1 endpoint creates an empty draft tied to the current operator's JWT subject, returning the draft id used to PATCH later steps. Module: services/ops-partner-bff. Owner is BFF; under the hood it calls config-registry POST /internal/v1/config/partners/draft.

**Steps.** Add controller method `PartnerDraftController.createDraft()` returning `{ draftId: Long, createdAt: Instant, ownerSubject: String }`; under the hood BFF posts to config-registry which inserts a row in `partner_draft(draft_id BIGSERIAL, owner_subject, created_at, payload JSONB DEFAULT '{}', current_step INT DEFAULT 1)` — add this table via V214; @PreAuthorize("hasAnyRole('OPS_ADMIN','SUPER_ADMIN')"); rate-limit: max 10 drafts per operator per hour.

**Deliverable.** `services/ops-partner-bff/src/main/java/com/gme/pay/bff/partner/PartnerDraftController.java; services/config-registry/src/main/resources/db/migration/V214__partner_draft.sql`

**Acceptance.**
- POST /v1/admin/partners/draft returns 201 with body containing draftId
- Same operator hitting the endpoint 11 times in an hour gets 429 on the 11th call
- OPS_VIEWER role gets 403
- Draft row exists in DB after the response

### 21.1-P11 — Draft endpoint PATCH /v1/admin/partners/draft/{id}/step-1 (Identity)
*Slice:* **1** · *Est:* 60 min · *Role:* Backend · *Owner:* ops-partner-bff · *ADR refs:* ADR-012

**Context.** Slice 1 step-1 patch endpoint. Validates the identity fields (P09 validators), upserts the draft payload jsonb under key `step1`, increments `current_step` if it was 1, returns the merged draft view.

**Steps.** Add controller method `PartnerDraftController.patchStep1(Long draftId, @Valid PartnerStep1Command body)`; body has legal_name_local, legal_name_romanized, tax_id, tax_id_type, country_of_incorporation, legal_form, registered_address (nested), operating_address (nested), lei; invoke TaxIdValidator; persist into partner_draft.payload->'step1'; ETag/If-Match for optimistic concurrency on the draft.

**Deliverable.** `services/ops-partner-bff/src/main/java/com/gme/pay/bff/partner/PartnerDraftController.java`

**Acceptance.**
- Valid step-1 body returns 200 with merged draft view
- Invalid KR_BRN returns 422 with `unmet:[{field:taxId,code:TAX_ID_KR_BRN_CHECKSUM}]`
- Stale If-Match returns 412 Precondition Failed
- Patch by a different operator than the draft owner returns 403

### 21.1-P12 — Draft endpoint GET /v1/admin/partners/draft/{id} + GET /v1/admin/partners/drafts (list)
*Slice:* **1** · *Est:* 45 min · *Role:* Backend · *Owner:* ops-partner-bff · *ADR refs:* ADR-012

**Context.** Read endpoints for resume-mid-edit and the draft list page on the admin UI.

**Steps.** GET /v1/admin/partners/draft/{id} returns full draft + a `nextStep` hint; GET /v1/admin/partners/drafts?ownerSubject=me returns paginated list (default 25, max 100); filter drafts older than 30 days as auto-purgeable.

**Deliverable.** `services/ops-partner-bff/src/main/java/com/gme/pay/bff/partner/PartnerDraftController.java`

**Acceptance.**
- GET /v1/admin/partners/drafts returns own drafts by default; SUPER_ADMIN can list all
- GET on someone else's draft returns 403 for non-admins
- Pagination link headers (RFC 5988) present when results exceed page size
- Draft older than 30 days carries `expiresAt` field indicating cleanup window

### 21.1-P13 — RestConfigRegistryClient adapts to the new PartnerView shape
*Slice:* **1** · *Est:* 60 min · *Role:* Backend · *Owner:* ops-partner-bff · *ADR refs:* —

**Context.** BFF -> config-registry HTTP client must speak PartnerView (P04). Update the existing RestConfigRegistryClient to deserialize into PartnerView, including the new identity fields. Mark old DTO methods @Deprecated.

**Steps.** Edit `services/ops-partner-bff/src/main/java/com/gme/pay/bff/clients/RestConfigRegistryClient.java`: change return types to PartnerView; map nested registered_address/operating_address; preserve BigDecimal scale; add @Retry annotation on transient 5xx; @Deprecated old methods retained one release.

**Deliverable.** `services/ops-partner-bff/src/main/java/com/gme/pay/bff/clients/RestConfigRegistryClient.java`

**Acceptance.**
- Deserialization of a PartnerView with all 21 identity fields populated round-trips through Jackson with no field loss
- BigDecimal LEI is unaffected (LEI is alphanumeric)
- Transient 503 from config-registry retried up to 3 times with 200ms backoff
- Existing BFF endpoints continue to return PartnerView-shaped JSON

### Slice 2 tickets owned by this service

### 21.2-P02 — BFF endpoint PATCH /v1/admin/partners/draft/{id}/step-2 (bulk replace contacts)
*Slice:* **2** · *Est:* 45 min · *Role:* Backend · *Owner:* ops-partner-bff · *ADR refs:* —

**Context.** Step-2 patch endpoint. Accepts a full contact list and replaces the draft's step2 payload. Validation: ≥1 contact per required role (OPS_24X7, FINANCE, COMPLIANCE_MLRO, TECH) — soft nudge, enforced as gate in Slice 8.

**Steps.** Edit PartnerDraftController to add patchStep2(Long draftId, @Valid PartnerStep2Command body) where body.contacts is a list; reject duplicate (role,email) within the list with 422; persist into partner_draft.payload->'step2'.

**Deliverable.** `services/ops-partner-bff/src/main/java/com/gme/pay/bff/partner/PartnerDraftController.java`

**Acceptance.**
- Bulk replace overwrites previous step-2 payload
- List with 2 entries same role+email returns 422
- List missing FINANCE role returns 200 but with `warnings:[{code:MISSING_ROLE_FINANCE}]`
- Phone in invalid format flagged at validation

### 21.2-P03 — BFF endpoint POST /v1/admin/partners/{id}/contacts (proposing a change_request)
*Slice:* **2** · *Est:* 60 min · *Role:* Backend · *Owner:* ops-partner-bff · *ADR refs:* ADR-008

**Context.** Post-activation contact change goes through change_request per ADR-008. POST returns the created change_request id in PROPOSED state; checker approves via 21.2-P04.

**Steps.** Add ContactChangeController.proposeContactChange(Long partnerId, ContactChangeCommand cmd); body identifies the contact (or null for create) plus the diff; inserts change_request with aggregate_type='partner_contact', state=PROPOSED, payload=JSON of cmd; emits Kafka event `gmepay.changerequest.proposed` for the approval queue worker.

**Deliverable.** `services/ops-partner-bff/src/main/java/com/gme/pay/bff/partner/ContactChangeController.java`

**Acceptance.**
- POST returns 201 with change_request id and state=PROPOSED
- Same operator approving their own change is rejected by the CHECK on change_request (P06)
- Kafka event `gmepay.changerequest.proposed` produced with payload
- Listing a partner's pending change requests returns this one

### 21.2-P04 — BFF endpoint POST /v1/admin/partners/change-requests/{id}/approve
*Slice:* **2** · *Est:* 45 min · *Role:* Backend · *Owner:* ops-partner-bff · *ADR refs:* ADR-008

**Context.** Approval endpoint — the checker side of 4-eyes. Calls Spring State Machine to transition PROPOSED -> APPROVED then APPLIED; applies the payload to the aggregate atomically.

**Steps.** Add ChangeRequestApprovalController.approve(Long crId, ApproveCommand body); body has approver_subject (taken from JWT, not body, to prevent spoofing) and optional reason; @Transactional: state -> APPROVED, set approved_at and approver_id, then apply the payload by deferring to the aggregate's service (PartnerAdminService.applyContactChange etc.), then state -> APPLIED; rollback on apply failure leaves the CR in APPROVED state for retry.

**Deliverable.** `services/ops-partner-bff/src/main/java/com/gme/pay/bff/partner/ChangeRequestApprovalController.java; services/config-registry/.../ChangeRequestApplier.java`

**Acceptance.**
- Approving a change_request where approver_subject == proposer_subject returns 403 with code SELF_APPROVAL_FORBIDDEN
- Successful approve transitions PROPOSED -> APPROVED -> APPLIED
- If apply step throws, CR remains in APPROVED and the transaction rolls back the aggregate
- Audit log row written for each transition

### 21.2-P05 — BFF endpoint POST /v1/admin/partners/change-requests/{id}/reject (with reason)
*Slice:* **2** · *Est:* 30 min · *Role:* Backend · *Owner:* ops-partner-bff · *ADR refs:* ADR-008

**Context.** Rejection terminal transition. Reason required.

**Steps.** Add ChangeRequestApprovalController.reject(Long crId, RejectCommand body); body.reason mandatory (≥10 chars); state transitions PROPOSED -> REJECTED (terminal); audit log row.

**Deliverable.** `services/ops-partner-bff/src/main/java/com/gme/pay/bff/partner/ChangeRequestApprovalController.java`

**Acceptance.**
- Empty reason returns 422
- Successful reject transitions to REJECTED
- Subsequent approve/reject on a REJECTED CR returns 409 Conflict
- Audit log row written

### Slice 3 tickets owned by this service

### 21.3-P09 — BFF endpoint POST /v1/admin/partners/draft/{id}/step-3 (KYB details + run screening)
*Slice:* **3** · *Est:* 90 min · *Role:* Backend · *Owner:* ops-partner-bff · *ADR refs:* —

**Context.** Step-3 patch endpoint. Accepts license fields, UBO array, risk decision; optionally triggers a screening via kyb-adapter (`runScreening=true`).

**Steps.** Add PartnerDraftController.patchStep3(Long draftId, PartnerStep3Command body); validate ubo sum ≤ 100, license_expiry ≥ today; if body.runScreening=true POST to kyb-adapter /v1/kyb/screen; on result write partner_kyb row + publish event (P08); persist into partner_draft.payload->'step3'.

**Deliverable.** `services/ops-partner-bff/src/main/java/com/gme/pay/bff/partner/PartnerDraftController.java`

**Acceptance.**
- UBO sum > 100 returns 422
- License expiry in the past returns 422 with code LICENSE_EXPIRED
- runScreening=true triggers kyb-adapter call and the result is captured in the patch response
- kyb-adapter circuit-open returns 503 with code KYB_PROVIDER_UNAVAILABLE; step-3 patch otherwise succeeds without screening

### 21.3-P10 — BFF endpoint POST /v1/admin/partners/{id}/documents (upload via lib-vault)
*Slice:* **3** · *Est:* 60 min · *Role:* Backend · *Owner:* ops-partner-bff · *ADR refs:* ADR-006

**Context.** Multipart upload endpoint. BFF streams the file to lib-vault, records partner_document row.

**Steps.** Add DocumentController.upload(Long partnerId, @RequestParam doc_type, @RequestPart MultipartFile file); enforce max size 25MB; call VaultClient.put with computed key `partners/{partner_code}/{doc_type}/{ts}.{ext}`; insert partner_document row; emit audit log.

**Deliverable.** `services/ops-partner-bff/src/main/java/com/gme/pay/bff/partner/DocumentController.java`

**Acceptance.**
- 20MB PDF uploads, returns 201 with document id and vault_uri
- 26MB file returns 413 Payload Too Large
- Virus-scan returning 'infected' fails the upload with 422 code VIRUS_SCAN_FAILED
- Audit log row carries doc_type, vault_uri, uploader

### Slice 4 tickets owned by this service

### 21.4-P05 — BFF endpoint PATCH /v1/admin/partners/draft/{id}/step-4 + bank verification trigger
*Slice:* **4** · *Est:* 90 min · *Role:* Backend · *Owner:* ops-partner-bff · *ADR refs:* —

**Context.** Step-4 patch: bank account multi-row replace + settlement config; per-row verification button calls AccountVerificationProvider.

**Steps.** Add PartnerDraftController.patchStep4 with body containing accounts[] and settlementConfig; per account if `triggerVerification=true` invoke verifier; record verification_date + verification_status; settlement preview endpoint GET /v1/admin/partners/draft/{id}/settlement-preview rolling cutoff over business_day_calendar.

**Deliverable.** `services/ops-partner-bff/src/main/java/com/gme/pay/bff/partner/PartnerDraftController.java`

**Acceptance.**
- Settlement preview correctly rolls Friday 11:30 KST + T+1 + Chuseok onto Tuesday
- Verification button updates verification_status to KFTC_VERIFIED on success
- Settlement method BAKONG with currency=KRW returns 422 (Bakong is KHR only)
- Two primary PAYOUT accounts in same currency rejected at patch time

### Slice 5 tickets owned by this service

### 21.5-P05 — BFF endpoint PATCH /v1/admin/partners/draft/{id}/step-5 (OVERSEAS only, gated)
*Slice:* **5** · *Est:* 60 min · *Role:* Backend · *Owner:* ops-partner-bff · *ADR refs:* —

**Context.** Step-5 patch. Server enforces partner_type=OVERSEAS or returns 422 STEP_NOT_APPLICABLE.

**Steps.** Add PartnerDraftController.patchStep5 with PartnerStep5Command (funding_model, opening_balance, thresholds, credit_limit, float_top_up_bank_account_id, ref_pattern, collateral); reject if draft.partner_type != OVERSEAS; validate float_top_up_bank_account_id belongs to this draft and has purpose=FLOAT_TOPUP; preview top-up reference for next day.

**Deliverable.** `services/ops-partner-bff/src/main/java/com/gme/pay/bff/partner/PartnerDraftController.java`

**Acceptance.**
- LOCAL partner attempting step-5 returns 422 STEP_NOT_APPLICABLE
- tier_85 < tier_70 returns 422 with code TIER_ORDER_INVALID
- float_top_up_bank_account_id with purpose=PAYOUT returns 422 with code WRONG_PURPOSE
- Reference preview for partner_code=ABC on 2026-09-30 returns 'GMP-ABC-20260930'

### Slice 6 tickets owned by this service

### 21.6-P10 — BFF endpoint PATCH /v1/admin/partners/draft/{id}/step-6 (commercial-terms bulk)
*Slice:* **6** · *Est:* 90 min · *Role:* Backend · *Owner:* ops-partner-bff · *ADR refs:* —

**Context.** Step-6 patch — biggest single step in the wizard. ccy split + rule rows + fee schedule + fx + limits + contract.

**Steps.** Add PartnerDraftController.patchStep6 with PartnerStep6Command (multi-section body); validate m_a+m_b >= 2% per rule; validate fee tier non-overlap; validate per_txn_max <= 5000 for SMALL_FX_TRANSFER; persist sections separately into partner_draft.payload->'step6' subkeys (rules, fees, fx, limits, contract).

**Deliverable.** `services/ops-partner-bff/src/main/java/com/gme/pay/bff/partner/PartnerDraftController.java`

**Acceptance.**
- Patch with cross-border rule m_a+m_b=1.9% returns 422 with code RULE_MARGIN_BELOW_2PCT
- Patch with per_txn_max=5001 for SMALL_FX_TRANSFER partner returns 422
- Contract effective_to in the past returns 422
- Rate-fx quote-preview embedded in response uses the new partner_fx_config

### Slice 7 tickets owned by this service

### 21.7-P05 — BFF endpoint PATCH /v1/admin/partners/draft/{id}/step-7 (scheme matrix + corridors)
*Slice:* **7** · *Est:* 75 min · *Role:* Backend · *Owner:* ops-partner-bff · *ADR refs:* —

**Context.** Step-7 patch: scheme enablement matrix + corridor matrix + per-scheme ZeroPay specifics.

**Steps.** Add PartnerDraftController.patchStep7 with PartnerStep7Command (schemes[] with per-scheme fields, corridors[]); persist into partner_draft.payload->'step7'; validate zeropay_merchant_id format when scheme=ZEROPAY; validate kftc_institution_code numeric 3 digits.

**Deliverable.** `services/ops-partner-bff/src/main/java/com/gme/pay/bff/partner/PartnerDraftController.java`

**Acceptance.**
- Patch with scheme=ZEROPAY missing zeropay_merchant_id returns 422
- kftc_institution_code='ABC' rejected
- Corridor src=dst rejected as same-corridor
- Operating-hours preview for ZeroPay (24x7 with KFTC 16:30 KST cutoff) returned in response

### Slice 8 tickets owned by this service

### 21.8-P17 — BFF endpoint PATCH /v1/admin/partners/draft/{id}/step-8 + activation orchestration
*Slice:* **8** · *Est:* 90 min · *Role:* Backend · *Owner:* ops-partner-bff · *ADR refs:* —

**Context.** Final step. Body has BOK/Hometax/KoFIU/PIPA/Travel Rule fields. After patch the operator clicks Activate which invokes the orchestrator.

**Steps.** Add PartnerDraftController.patchStep8; new endpoint POST /v1/admin/partners/draft/{id}/activate calling ActivationGate.evaluate; if ready, orchestrator drives FSM Draft -> ... -> Live transitions atomically; one-time credential issuance happens during Sandbox transition; activation response carries plaintext apiKey + hmacSecret + webhook signing secret (returned ONCE).

**Deliverable.** `services/ops-partner-bff/src/main/java/com/gme/pay/bff/partner/PartnerDraftController.java; services/ops-partner-bff/src/main/java/com/gme/pay/bff/partner/PartnerActivationOrchestrator.java`

**Acceptance.**
- Activate with unmet gates returns 422 with the gate result
- Activate happy-path returns 200 with credential plaintext block
- Subsequent GET /v1/credentials/{partnerId} returns hashed-only view
- Failure mid-orchestration rolls back FSM to last consistent state

### Cross-service contributions touching this service

Tickets owned elsewhere but with code or schema touchpoints in this service. Listed here so this bundle remains the single read for a service developer.

- **21.1-P04** (config-registry, Slice 1) — Collapse 5 Partner DTOs into PartnerView (read) + PartnerCommand (write)
- **21.1-P14** (auth-identity, Slice 1) — Keycloak OAuth2 resource-server config on api-gateway and config-registry (retire password=demo)
- **21.5-P02** (prefunding, Slice 5) — Wire PartnerBalanceEntity into partner-create transaction (BFF orchestration)

