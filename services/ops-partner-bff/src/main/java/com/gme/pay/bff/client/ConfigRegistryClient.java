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
}
