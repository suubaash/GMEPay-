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
