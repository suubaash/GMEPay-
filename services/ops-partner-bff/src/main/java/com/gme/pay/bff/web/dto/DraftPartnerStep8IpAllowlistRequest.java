package com.gme.pay.bff.web.dto;

import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerIpAllowlistCommand;

import java.util.List;

/**
 * BFF wire shape for
 * {@code PATCH /v1/admin/partners/draft/{partnerCode}/step-8/ip-allowlist}
 * (Slice 8 Lane B — IP allowlist editor). The URL identifies the partner; the
 * body carries the FULL desired allowlist set (bulk-replace semantics — an
 * empty list clears all entries). Elements bind directly to the canonical
 * {@link PartnerIpAllowlistCommand}.
 */
public record DraftPartnerStep8IpAllowlistRequest(List<PartnerIpAllowlistCommand> ipAllowlist) {

    /** Adapt to the canonical write surface {@code lib-api-contracts} exposes. */
    public PartnerCommand.UpdateStep8Credentials toUpdateStep8Credentials() {
        return new PartnerCommand.UpdateStep8Credentials(ipAllowlist);
    }
}
