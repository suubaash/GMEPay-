package com.gme.pay.reporting.hometax;

import com.gme.pay.contracts.PartnerRegulatoryConfigView;

/**
 * Port for reading partner regulatory configuration from the config-registry
 * service (V029 {@code partner_regulatory_config}).
 *
 * <p>This service NEVER reads config-registry's database (MSA rule). The
 * production implementation calls
 * {@code GET /v1/partners/{partnerCode}/regulatory} via RestClient.
 * In tests and Phase-1 this is stubbed.
 *
 * <p>Config key consumed by the production implementation:
 * {@code gmepay.config-registry.base-url}
 */
public interface RegulatoryConfigClient {

    /**
     * Fetches the current regulatory config for a partner.
     *
     * @param partnerCode the human-facing business code, e.g. {@code "GMEREMIT"}
     * @return the current regulatory config view, or {@code null} if not found
     */
    PartnerRegulatoryConfigView getRegulatory(String partnerCode);
}
