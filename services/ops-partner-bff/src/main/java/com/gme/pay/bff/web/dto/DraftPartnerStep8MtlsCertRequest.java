package com.gme.pay.bff.web.dto;

import com.gme.pay.contracts.PartnerCommand;

/**
 * BFF wire shape for
 * {@code PATCH /v1/admin/partners/draft/{partnerCode}/step-8/mtls-cert}
 * (Slice 8 Lane B — mTLS certificate upload). Carries one environment + PEM
 * pair; the PEM is parsed, fingerprinted and stored by config-registry.
 */
public record DraftPartnerStep8MtlsCertRequest(String environment, String certPem) {

    /** Adapt to the canonical write surface {@code lib-api-contracts} exposes. */
    public PartnerCommand.UploadMtlsCert toUploadMtlsCert() {
        return new PartnerCommand.UploadMtlsCert(environment, certPem);
    }
}
