package com.gme.pay.bff.web.dto;

import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerRegulatoryConfigCommand;

/**
 * BFF wire shape for
 * {@code PATCH /v1/admin/partners/draft/{partnerCode}/step-8/regulatory}
 * (Slice 8 Lane C — regulatory attributes: BOK 외환거래보고 + Hometax +
 * KoFIU CTR/STR + PIPA + Travel Rule). Full-state replace semantics.
 */
public record DraftPartnerStep8RegulatoryRequest(PartnerRegulatoryConfigCommand regulatory) {

    /** Adapt to the canonical write surface {@code lib-api-contracts} exposes. */
    public PartnerCommand.UpdateStep8Regulatory toUpdateStep8Regulatory() {
        return new PartnerCommand.UpdateStep8Regulatory(regulatory);
    }
}
