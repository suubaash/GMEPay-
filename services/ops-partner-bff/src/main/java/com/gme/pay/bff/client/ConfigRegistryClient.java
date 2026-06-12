package com.gme.pay.bff.client;

import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerView;

import java.math.RoundingMode;
import java.util.List;

/**
 * Read-only view of config-registry's partner registry.
 *
 * <p>This BFF NEVER reads config-registry's database (MSA rule). The production
 * implementation calls {@code GET /v1/partners/{id}} via Spring 6 RestClient; the
 * Phase-1 default implementation is an in-memory stub so the BFF can be exercised
 * without booting config-registry.
 *
 * <p>Slice 1 collapsed the 5 drifting Partner DTOs to one canonical
 * {@link PartnerView} (read) + {@link PartnerCommand} (write) in
 * {@code lib-api-contracts} — see {@code docs/PARTNER_SETUP_PLAN.md} §"Cross-cutting
 * bug fixes". The {@link PartnerSummary} and {@link PartnerCreateRequest} records
 * below are retained as <b>@Deprecated</b> Expand-phase aliases (ADR-013): they
 * still carry the BFF's wire shape (the Admin UI binds to {@code partnerId}) and
 * delegate to / wrap the canonical types so adding a Slice-N field only requires
 * editing {@link PartnerView}, never these aliases. A future Contract migration
 * removes them once every consumer has switched to {@link PartnerView} directly.
 */
public interface ConfigRegistryClient {

    /**
     * Loads a single partner summary by id. Returns {@code null} if the partner
     * is not known to config-registry.
     */
    PartnerSummary getPartner(String partnerId);

    /** Lists all known partners (currently used to populate Admin UI tables). */
    List<PartnerSummary> listPartners();

    /**
     * Creates a new partner from the Admin UI partner form. Production
     * implementation POSTs to {@code /v1/partners}; Phase-1 stub appends to an
     * in-memory map.
     */
    PartnerSummary createPartner(PartnerCreateRequest request);

    /**
     * Updates only the per-partner settlement rounding mode (the Admin UI partner
     * settings form). Production calls {@code PUT /v1/partners/{id}/rounding-mode}.
     * Returns the updated summary; returns {@code null} if the partner is unknown.
     */
    PartnerSummary updateRoundingMode(String partnerId, String mode);

    /** Lists all schemes known to config-registry. Populates the Admin UI scheme table. */
    List<SchemeSummary> listSchemes();

    // -------- Slice 1 (1C.2) draft endpoints (ADR-012) -----------------------
    //
    // Default implementations return null / empty list so existing anonymous
    // test fakes (PartnerPortalControllerTest / AdminDashboardControllerTest)
    // keep compiling without forcing every test to add the four new methods.
    // The Slice 2+ DTOs migrate everyone off the deprecated PartnerSummary/
    // PartnerCreateRequest aliases at the same time as those test fakes get
    // updated.

    /**
     * Create a new partner draft. Routes to config-registry's
     * {@code POST /v1/partners/draft}; the resulting row sits in
     * {@code status=ONBOARDING} with a paired change_request in
     * {@code state=DRAFT}. Returns the canonical {@link PartnerView} the BFF
     * passes through to the Admin UI wizard unchanged.
     */
    default PartnerView createDraft(PartnerCommand.CreateDraft request) {
        throw new UnsupportedOperationException(
                "createDraft is not implemented by " + getClass().getName());
    }

    /**
     * Save Step-1 Identity edits onto an existing draft. Routes to
     * {@code PATCH /v1/partners/draft/{partnerCode}/step-1}. Returns the
     * updated {@link PartnerView} including the bitemporal stamps of the
     * fresh current row.
     */
    default PartnerView patchDraftStep1(String partnerCode, PartnerCommand.UpdateStep1 request) {
        throw new UnsupportedOperationException(
                "patchDraftStep1 is not implemented by " + getClass().getName());
    }

    /**
     * Read the current draft for {@code partnerCode}. Routes to
     * {@code GET /v1/partners/draft/{partnerCode}}. Returns {@code null} when
     * no current row matches (404 from config-registry).
     */
    default PartnerView getDraft(String partnerCode) {
        return null;
    }

    /**
     * List every in-flight draft. Routes to {@code GET /v1/partners/drafts}.
     * Powers the Admin UI "Drafts in progress" section.
     */
    default List<PartnerView> listDrafts() {
        return List.of();
    }

    // -------- Slice 2 (2A.1) contact endpoints (PARTNER_SETUP_PLAN §Slice 2) --

    /**
     * Bulk-replace the contact set on a draft (wizard step-2 "Next"). Routes to
     * {@code PATCH /v1/partners/draft/{partnerCode}/step-2}; config-registry
     * supersedes every current {@code partner_contact} row and inserts the new
     * set in one transaction (SCD-6, ADR-010). Returns the freshly-inserted
     * current set as canonical {@link ContactView}s.
     *
     * <p>Default throws — like {@link #createDraft} — so existing anonymous
     * test fakes keep compiling; both real implementations override.
     */
    default List<com.gme.pay.contracts.ContactView> patchDraftStep2(
            String partnerCode, PartnerCommand.UpdateStep2 request) {
        throw new UnsupportedOperationException(
                "patchDraftStep2 is not implemented by " + getClass().getName());
    }

    /**
     * The CURRENT contact set for a partner. Routes to
     * {@code GET /v1/partners/{partnerCode}/contacts}. A partner with no
     * contacts yields an empty list; an unknown partner surfaces upstream's
     * 404 as a {@code ResponseStatusException} from the rest/stub
     * implementations.
     */
    default List<com.gme.pay.contracts.ContactView> listContacts(String partnerCode) {
        return List.of();
    }

    // -------- Slice 3 (3B.1) KYB endpoints (PARTNER_SETUP_PLAN §Slice 3) ------
    //
    // Defaults throw — like createDraft — so existing anonymous test fakes keep
    // compiling; both real implementations override.

    /**
     * Save wizard step-3 (KYB) onto a draft — full-state replace of the
     * operator-editable fields. Routes to
     * {@code PATCH /v1/partners/draft/{partnerCode}/step-3}; config-registry
     * supersedes the current {@code partner_kyb} row and inserts a fresh one
     * in one transaction (SCD-6, ADR-010), carrying the screening verdict
     * forward server-side. Returns the fresh {@link com.gme.pay.contracts.KybView}.
     */
    default com.gme.pay.contracts.KybView patchDraftStep3(
            String partnerCode, com.gme.pay.contracts.KybCommand.UpdateStep3 request) {
        throw new UnsupportedOperationException(
                "patchDraftStep3 is not implemented by " + getClass().getName());
    }

    /**
     * The CURRENT KYB view for a partner. Routes to
     * {@code GET /v1/partners/{partnerCode}/kyb}. Upstream 404 (unknown code
     * OR no KYB row yet) surfaces as a {@code ResponseStatusException} from
     * the rest/stub implementations.
     */
    default com.gme.pay.contracts.KybView getKyb(String partnerCode) {
        throw new UnsupportedOperationException(
                "getKyb is not implemented by " + getClass().getName());
    }

    /**
     * Run sanctions screening for a partner (the step-3 "Run screening"
     * button / the detail page's rescreen button). Routes to
     * {@code POST /v1/partners/{partnerCode}/kyb/screen} (no body — the
     * server assembles the screening subject from the stored aggregate,
     * ADR-009). Returns the updated {@link com.gme.pay.contracts.KybView}
     * carrying the verdict.
     */
    default com.gme.pay.contracts.KybView runKybScreening(String partnerCode) {
        throw new UnsupportedOperationException(
                "runKybScreening is not implemented by " + getClass().getName());
    }

    // -------- Slice 3 (3A.1) document vault endpoints (ADR-006) ---------------
    //
    // Defaults follow the established convention: list degrades to empty,
    // upload/download throw so a missing override is loud. Both real
    // implementations override all three.

    /**
     * Upload one KYB document onto a partner. Routes to config-registry's
     * multipart {@code POST /v1/partners/{partnerCode}/documents}; the bytes go
     * to the ADR-006 vault, the metadata row to {@code partner_document} (V010),
     * and a {@code partner_document} audit event is chained (ADR-007). Returns
     * the fresh current {@link com.gme.pay.contracts.DocumentView};
     * re-uploading a doc type supersedes the prior row and bumps
     * {@code version} (object-lock: nothing is ever overwritten or deleted).
     *
     * @param expiryDate ISO-8601 {@code yyyy-MM-dd} or {@code null}.
     */
    default com.gme.pay.contracts.DocumentView uploadDocument(
            String partnerCode, String docType, String expiryDate,
            String filename, String contentType, byte[] content) {
        throw new UnsupportedOperationException(
                "uploadDocument is not implemented by " + getClass().getName());
    }

    /**
     * The CURRENT document set for a partner (at most one row per doc type).
     * Routes to {@code GET /v1/partners/{partnerCode}/documents}. A partner
     * with no documents yields an empty list; an unknown partner surfaces
     * upstream's 404.
     */
    default List<com.gme.pay.contracts.DocumentView> listDocuments(String partnerCode) {
        return List.of();
    }

    /**
     * Download passthrough for one stored document (current or superseded id —
     * the document viewer's version history). Routes to
     * {@code GET /v1/partners/{partnerCode}/documents/{docId}/content}.
     */
    default DocumentContent downloadDocument(String partnerCode, Long docId) {
        throw new UnsupportedOperationException(
                "downloadDocument is not implemented by " + getClass().getName());
    }

    /**
     * One downloaded document as relayed through the BFF: original filename +
     * MIME type + the bytes. Buffered rather than streamed — KYB documents are
     * scans/PDFs (tens of MB at worst) and the BFF is a relay, not a CDN.
     */
    record DocumentContent(String filename, String contentType, byte[] content) {}

    // -------- Slice 4 (4A.1) bank-account endpoints (PARTNER_SETUP_PLAN §Slice 4)
    //
    // Defaults follow the established convention: the write + verify throw so a
    // missing override is loud, the list degrades to empty. Both real
    // implementations override all three.

    /**
     * Bulk-replace the bank-account set on a draft (wizard step-4 "Next").
     * Routes to {@code PATCH /v1/partners/draft/{partnerCode}/step-4};
     * config-registry supersedes every current {@code partner_bank_account}
     * row and inserts the new set in one transaction (SCD-6, ADR-010),
     * carrying verification verdicts forward where the (currency, account
     * number) pair is unchanged. Returns the freshly-inserted current set as
     * canonical {@link com.gme.pay.contracts.BankAccountView}s.
     */
    default List<com.gme.pay.contracts.BankAccountView> patchDraftStep4(
            String partnerCode, PartnerCommand.UpdateStep4 request) {
        throw new UnsupportedOperationException(
                "patchDraftStep4 is not implemented by " + getClass().getName());
    }

    /**
     * The CURRENT bank-account set for a partner. Routes to
     * {@code GET /v1/partners/{partnerCode}/bank-accounts}. A partner with no
     * accounts yields an empty list; an unknown partner surfaces upstream's
     * 404 as a {@code ResponseStatusException} from the rest/stub
     * implementations.
     */
    default List<com.gme.pay.contracts.BankAccountView> listBankAccounts(String partnerCode) {
        return List.of();
    }

    /**
     * Run account verification for one CURRENT bank-account row (the step-4
     * "Verify" button). Routes to
     * {@code POST /v1/partners/{partnerCode}/bank-accounts/{accountId}/verify}
     * (no body — the server assembles the subject from the stored row and runs
     * its {@code AccountVerificationProvider} port: KFTC for KR rails in
     * production, the deterministic stub by default). Returns the FRESH SCD-6
     * row carrying the verdict (note: a new row id).
     */
    default com.gme.pay.contracts.BankAccountView verifyBankAccount(
            String partnerCode, Long accountId) {
        throw new UnsupportedOperationException(
                "verifyBankAccount is not implemented by " + getClass().getName());
    }

    // -------- Slice 4 (4B.1) settlement-config endpoints (PARTNER_SETUP_PLAN §Slice 4)
    //
    // Defaults throw — like createDraft — so existing anonymous test fakes keep
    // compiling; both real implementations override.

    /**
     * Save the step-4 settlement panel onto a draft — full-state replace of
     * the settlement parameters. Routes to
     * {@code PATCH /v1/partners/draft/{partnerCode}/step-4-settlement};
     * config-registry supersedes the current {@code partner_settlement_config}
     * row and inserts a fresh one in one transaction (SCD-6, ADR-010). Returns
     * the fresh {@link com.gme.pay.contracts.SettlementConfigView}.
     */
    default com.gme.pay.contracts.SettlementConfigView patchDraftStep4Settlement(
            String partnerCode, PartnerCommand.UpdateStep4Settlement request) {
        throw new UnsupportedOperationException(
                "patchDraftStep4Settlement is not implemented by " + getClass().getName());
    }

    /**
     * The CURRENT settlement config for a partner. Routes to
     * {@code GET /v1/partners/{partnerCode}/settlement-config}. Upstream 404
     * (unknown code OR no config yet) surfaces as a
     * {@code ResponseStatusException} from the rest/stub implementations.
     */
    default com.gme.pay.contracts.SettlementConfigView getSettlementConfig(String partnerCode) {
        throw new UnsupportedOperationException(
                "getSettlementConfig is not implemented by " + getClass().getName());
    }

    /**
     * Project a transaction instant onto a payout date through the partner's
     * settlement config and the KR + bank-country business-day calendars (the
     * wizard's settlement preview panel). Routes to
     * {@code GET /v1/partners/{partnerCode}/settlement-preview?txnInstant=ISO}
     * (+ optional {@code bankCountry} alpha-2 override).
     *
     * @param txnInstant ISO-8601 instant string, passed through verbatim so
     *                   config-registry owns the parse + its 400 message.
     */
    default com.gme.pay.contracts.SettlementPreview getSettlementPreview(
            String partnerCode, String txnInstant, String bankCountry) {
        throw new UnsupportedOperationException(
                "getSettlementPreview is not implemented by " + getClass().getName());
    }

    // -------- Slice 5 (5A.1) prefunding-config endpoints (PARTNER_SETUP_PLAN §Slice 5)
    //
    // Defaults throw — like createDraft — so existing anonymous test fakes keep
    // compiling; both real implementations override.

    /**
     * Save the step-5 prefunding panel onto a draft — full-state replace of
     * the prefunding parameters. Routes to
     * {@code PATCH /v1/partners/draft/{partnerCode}/step-5}; config-registry
     * supersedes the current {@code partner_prefunding_config} row and inserts
     * a fresh one in one transaction (SCD-6, ADR-010). Returns the fresh
     * {@link com.gme.pay.contracts.PrefundingConfigView}. Money fields ride as
     * decimal STRINGS per {@code docs/MONEY_CONVENTION.md}.
     */
    default com.gme.pay.contracts.PrefundingConfigView patchDraftStep5(
            String partnerCode, PartnerCommand.UpdateStep5 request) {
        throw new UnsupportedOperationException(
                "patchDraftStep5 is not implemented by " + getClass().getName());
    }

    /**
     * The CURRENT prefunding config for a partner. Routes to
     * {@code GET /v1/partners/{partnerCode}/prefunding-config}. Upstream 404
     * (unknown code OR no config yet) surfaces as a
     * {@code ResponseStatusException} from the rest/stub implementations.
     */
    default com.gme.pay.contracts.PrefundingConfigView getPrefundingConfig(String partnerCode) {
        throw new UnsupportedOperationException(
                "getPrefundingConfig is not implemented by " + getClass().getName());
    }

    // -------- Slice 6 (6A.1) pricing-rule endpoints (PARTNER_SETUP_PLAN §Slice 6)
    //
    // Defaults follow the established convention: the write throws so a missing
    // override is loud, the list degrades to empty. Both real implementations
    // override both.

    /**
     * Bulk-replace the pricing-rule set on a draft (wizard step-6 "Next").
     * Routes to {@code PATCH /v1/partners/draft/{partnerCode}/step-6-rules};
     * config-registry supersedes every current {@code partner_rule} row (V017)
     * and inserts the new set in one transaction (SCD-6, ADR-010), enforcing
     * the lib-domain margin invariant (cross-border {@code mA + mB >= 2%},
     * same-currency zero margin) against the partner's V016 collection/settle
     * currency split. Returns the freshly-inserted current set as canonical
     * {@link com.gme.pay.contracts.RuleView}s. Margins and money ride as
     * decimal STRINGS per {@code docs/MONEY_CONVENTION.md}.
     */
    default List<com.gme.pay.contracts.RuleView> patchDraftStep6Rules(
            String partnerCode, PartnerCommand.UpdateStep6Rules request) {
        throw new UnsupportedOperationException(
                "patchDraftStep6Rules is not implemented by " + getClass().getName());
    }

    /**
     * The CURRENT pricing-rule set for a partner. Routes to
     * {@code GET /v1/partners/{partnerCode}/rules}. A partner with no rules
     * yields an empty list; an unknown partner surfaces upstream's 404 as a
     * {@code ResponseStatusException} from the rest/stub implementations.
     */
    default List<com.gme.pay.contracts.RuleView> listRules(String partnerCode) {
        return List.of();
    }

    // -------- Slice 6 (6B.1) commercial-terms endpoints (PARTNER_SETUP_PLAN §Slice 6)
    //
    // Defaults follow the established convention: the write throws so a missing
    // override is loud; the fee list degrades to empty, the single-row reads
    // throw like the write (their 404 carries wizard-rehydrate semantics).
    // Both real implementations override all five.

    /**
     * Save the step-6 commercial composite (fees + FX + limits + contract)
     * onto a draft. Routes to
     * {@code PATCH /v1/partners/draft/{partnerCode}/step-6-commercial};
     * config-registry applies each non-null section ATOMICALLY (one
     * transaction — SCD-6 paired writes per ADR-010, one audit row per
     * section per ADR-007) and leaves null sections untouched. The
     * 소액해외송금업 caps ({@code perTxnMax <= 5000},
     * {@code annualCap <= 50000} for {@code licenseType=SOAEK_HAEOEMONG}) are
     * enforced server-side. Returns the fresh
     * {@link com.gme.pay.contracts.CommercialTermsView} (untouched sections
     * null). Money and bps ride as decimal STRINGS per
     * {@code docs/MONEY_CONVENTION.md}.
     */
    default com.gme.pay.contracts.CommercialTermsView patchDraftStep6Commercial(
            String partnerCode, PartnerCommand.UpdateStep6Commercial request) {
        throw new UnsupportedOperationException(
                "patchDraftStep6Commercial is not implemented by " + getClass().getName());
    }

    /**
     * The CURRENT fee-schedule set for a partner. Routes to
     * {@code GET /v1/partners/{partnerCode}/fee-schedules}. A partner with no
     * fee rows yields an empty list; an unknown partner surfaces upstream's
     * 404 as a {@code ResponseStatusException} from the rest/stub
     * implementations.
     */
    default List<com.gme.pay.contracts.FeeScheduleView> getFeeSchedules(String partnerCode) {
        return List.of();
    }

    /**
     * The CURRENT FX config for a partner. Routes to
     * {@code GET /v1/partners/{partnerCode}/fx-config}. Upstream 404 (unknown
     * code OR no config yet) surfaces as a {@code ResponseStatusException}
     * from the rest/stub implementations.
     */
    default com.gme.pay.contracts.FxConfigView getFxConfig(String partnerCode) {
        throw new UnsupportedOperationException(
                "getFxConfig is not implemented by " + getClass().getName());
    }

    /**
     * The CURRENT limits for a partner. Routes to
     * {@code GET /v1/partners/{partnerCode}/limits}. Upstream 404 semantics as
     * {@link #getFxConfig(String)}.
     */
    default com.gme.pay.contracts.LimitsView getLimits(String partnerCode) {
        throw new UnsupportedOperationException(
                "getLimits is not implemented by " + getClass().getName());
    }

    /**
     * The CURRENT contract for a partner. Routes to
     * {@code GET /v1/partners/{partnerCode}/contract}. Upstream 404 semantics
     * as {@link #getFxConfig(String)}.
     */
    default com.gme.pay.contracts.ContractView getContract(String partnerCode) {
        throw new UnsupportedOperationException(
                "getContract is not implemented by " + getClass().getName());
    }

    /**
     * @deprecated Slice 1 DTO collapse — bind to {@link PartnerView} from
     * {@code lib-api-contracts} instead. Retained as an Expand-phase alias
     * (ADR-013) so the Admin UI wire shape (which binds to {@code partnerId})
     * does not break; new fields land on {@link PartnerView}, this record stays
     * the four-field summary the UI already consumes. The Contract migration
     * deletes this record.
     */
    @Deprecated(forRemoval = true, since = "Slice 1 — see docs/PARTNER_SETUP_PLAN.md")
    record PartnerSummary(
            String partnerId,
            String type,
            String settlementCurrency,
            RoundingMode settlementRoundingMode
    ) {
        /**
         * Adapt a canonical {@link PartnerView} to the legacy four-field summary.
         * Carries the {@code partnerCode} into the wire-level {@code partnerId}
         * slot so the Admin UI's existing JSON binding keeps working. Returns
         * {@code null} when {@code view} is {@code null}.
         */
        public static PartnerSummary fromView(PartnerView view) {
            if (view == null) {
                return null;
            }
            return new PartnerSummary(
                    view.partnerCode(),
                    view.type() == null ? null : view.type().name(),
                    view.settlementCurrency(),
                    view.settlementRoundingMode());
        }
    }

    // -------- Slice 2 (2B.1) change-request approval endpoints (ADR-008) ------
    //
    // Default implementations throw UnsupportedOperationException so the stub
    // clients that only implement partner CRUD do not need to change. Once the
    // BFF's RestConfigRegistryClient wires these up the stubs for controller
    // tests also get the matching methods.

    /**
     * Paginated list of change requests, optionally filtered by state.
     * Routes to {@code GET /v1/change-requests?state=...&page=...&size=...} on
     * config-registry. The page envelope mirrors
     * {@link com.gme.pay.bff.web.dto.Page}.
     */
    default ChangeRequestPage listChangeRequests(String state, int page, int size) {
        throw new UnsupportedOperationException(
                "listChangeRequests is not implemented by " + getClass().getName());
    }

    /**
     * Fetch a single change request by surrogate id.
     * Routes to {@code GET /v1/change-requests/{id}}.
     */
    default com.gme.pay.contracts.ChangeRequestView getChangeRequest(Long id) {
        throw new UnsupportedOperationException(
                "getChangeRequest is not implemented by " + getClass().getName());
    }

    /**
     * Approve a PROPOSED change request and immediately apply it.
     * Routes to {@code POST /v1/change-requests/{id}/approve}.
     * Returns the view in state=APPLIED on success; re-throws upstream 4xx
     * (including 409 for self-approval).
     */
    default com.gme.pay.contracts.ChangeRequestView approveChangeRequest(
            Long id, String approvedBy) {
        throw new UnsupportedOperationException(
                "approveChangeRequest is not implemented by " + getClass().getName());
    }

    /**
     * Reject a change request with a mandatory reason.
     * Routes to {@code POST /v1/change-requests/{id}/reject}.
     * Returns the updated view in state=REJECTED.
     */
    default com.gme.pay.contracts.ChangeRequestView rejectChangeRequest(
            Long id, String rejectedBy, String reason) {
        throw new UnsupportedOperationException(
                "rejectChangeRequest is not implemented by " + getClass().getName());
    }

    /**
     * Paginated page of {@link com.gme.pay.contracts.ChangeRequestView} rows.
     * Mirrors the shape that config-registry's
     * {@code ChangeRequestController.ChangeRequestPageView} serialises so JSON
     * deserialisation in the BFF's REST client is straightforward.
     */
    record ChangeRequestPage(
            java.util.List<com.gme.pay.contracts.ChangeRequestView> content,
            int page,
            int size,
            long total) {}

    // -------- Slice 7 (7A/7B) scheme-enablement + corridor endpoints (PARTNER_SETUP_PLAN §Slice 7)
    //
    // Defaults follow the established convention: the writes throw so a missing
    // override is loud; the list reads degrade to empty. Both real implementations
    // override all five.

    /**
     * Bulk-replace the scheme-enablement set on a draft (wizard step-7 "Next").
     * Routes to {@code PATCH /v1/partners/draft/{partnerCode}/step-7/schemes};
     * config-registry supersedes every current {@code partner_scheme} row (V022)
     * and inserts the new set in one transaction (SCD-6, ADR-010), enforcing the
     * enabled-ZEROPAY wiring invariant server-side. Returns the freshly-inserted
     * current set as canonical {@link com.gme.pay.contracts.PartnerSchemeView}s.
     */
    default List<com.gme.pay.contracts.PartnerSchemeView> patchDraftStep7Schemes(
            String partnerCode, PartnerCommand.UpdateStep7Schemes request) {
        throw new UnsupportedOperationException(
                "patchDraftStep7Schemes is not implemented by " + getClass().getName());
    }

    /**
     * Bulk-replace the corridor set on a draft (wizard step-7 "Next").
     * Routes to {@code PATCH /v1/partners/draft/{partnerCode}/step-7/corridors};
     * config-registry supersedes every current {@code partner_corridor} row (V023)
     * and inserts the new set in one transaction (SCD-6, ADR-010). Returns the
     * freshly-inserted current set as canonical
     * {@link com.gme.pay.contracts.PartnerCorridorView}s.
     */
    default List<com.gme.pay.contracts.PartnerCorridorView> patchDraftStep7Corridors(
            String partnerCode, PartnerCommand.UpdateStep7Corridors request) {
        throw new UnsupportedOperationException(
                "patchDraftStep7Corridors is not implemented by " + getClass().getName());
    }

    /**
     * The CURRENT scheme-enablement set for a partner. Routes to
     * {@code GET /v1/partners/{partnerCode}/schemes}. A partner with no schemes
     * yields an empty list; an unknown partner surfaces upstream's 404 as a
     * {@code ResponseStatusException} from the rest/stub implementations.
     */
    default List<com.gme.pay.contracts.PartnerSchemeView> listSchemeEnablements(
            String partnerCode) {
        return List.of();
    }

    /**
     * The CURRENT corridor set for a partner. Routes to
     * {@code GET /v1/partners/{partnerCode}/corridors}. A partner with no
     * corridors yields an empty list; an unknown partner surfaces upstream's 404
     * as a {@code ResponseStatusException} from the rest/stub implementations.
     */
    default List<com.gme.pay.contracts.PartnerCorridorView> listCorridors(
            String partnerCode) {
        return List.of();
    }

    /**
     * The operating-hours schedule for a scheme. Routes to
     * {@code GET /v1/schemes/{schemeId}/operating-hours}. An unknown
     * {@code schemeId} yields an empty list (no 404 — reference data is
     * migration-seeded, unknown ids are unsupported schemes rather than errors).
     */
    default List<com.gme.pay.contracts.SchemeOperatingHoursView> getSchemeOperatingHours(
            String schemeId) {
        return List.of();
    }

    /**
     * @deprecated Slice 1 DTO collapse — build a {@link PartnerCommand.CreateDraft}
     * directly. Retained so call-sites (Admin UI request mapping) compile during
     * the Expand phase; {@link #toCreateDraft()} adapts it to the canonical
     * write payload.
     */
    @Deprecated(forRemoval = true, since = "Slice 1 — see docs/PARTNER_SETUP_PLAN.md")
    record PartnerCreateRequest(
            String partnerId,
            String type,
            String settlementCurrency,
            String settlementRoundingMode
    ) {
        /**
         * Adapt this legacy request to a canonical {@link PartnerCommand.CreateDraft}.
         * Identity-step fields (legal names, tax id, addresses, etc.) are all
         * {@code null} — this alias only carries the four-field aggregate the
         * pre-Slice-1 Admin UI form sends. Type and rounding-mode strings are
         * parsed at this seam so the BFF never propagates raw strings into the
         * canonical typed surface.
         */
        public PartnerCommand.CreateDraft toCreateDraft() {
            return new PartnerCommand.CreateDraft(
                    partnerId,
                    type == null || type.isBlank() ? null : com.gme.pay.domain.PartnerType.valueOf(type),
                    settlementCurrency,
                    settlementRoundingMode == null || settlementRoundingMode.isBlank()
                            ? null
                            : RoundingMode.valueOf(settlementRoundingMode),
                    null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * Scheme-list row for the Admin UI. Schemes are payment-network configurations
     * (e.g. ZeroPay-KR) owned by config-registry.
     */
    record SchemeSummary(
            String schemeId,
            String name,
            String country,
            String currency,
            String mode,
            String status
    ) {}
}
