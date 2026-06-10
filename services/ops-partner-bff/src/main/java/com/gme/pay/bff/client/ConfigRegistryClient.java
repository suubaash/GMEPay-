package com.gme.pay.bff.client;

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
 * <p>Phase C2 adds write operations (partner create, rounding-mode update) and a
 * scheme list view to back the Admin UI partner form and scheme list.
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

    /**
     * Phase-1 DTO mirroring the fields the Admin UI partner form binds to. The
     * {@code settlementRoundingMode} is the per-partner liability rounding policy
     * (see {@code docs/MONEY_CONVENTION.md}).
     */
    record PartnerSummary(
            String partnerId,
            String type,
            String settlementCurrency,
            RoundingMode settlementRoundingMode
    ) {}

    /**
     * Input shape for {@link #createPartner(PartnerCreateRequest)}. Mirrors the
     * Admin UI partner-form fields; the rounding mode arrives as the textual
     * {@code RoundingMode} name (e.g. {@code "HALF_UP"}, {@code "DOWN"}).
     */
    record PartnerCreateRequest(
            String partnerId,
            String type,
            String settlementCurrency,
            String settlementRoundingMode
    ) {}

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
