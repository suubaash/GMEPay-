package com.gme.pay.ratefx.issue;

import java.math.BigDecimal;

/**
 * Request to ISSUE a partner-priced quote (the quote-issuer epic). The caller (gateway/partner)
 * supplies only the transaction facts; rate-fx resolves the partner's currencies, margins, service
 * fee, and the treasury cost rates itself, so the partner cannot influence the priced cascade.
 *
 * @param partnerCode    GME partner business code (e.g. "GMEREMIT") — keys the config lookup
 * @param schemeId       QR scheme code (e.g. "zeropay") — selects the pricing rule
 * @param direction      corridor direction (INBOUND/OUTBOUND/BOTH); may be null to match a wildcard rule
 * @param targetPayout   amount the customer receives, in {@code payoutCurrency} (fixed by the QR)
 * @param payoutCurrency the payout / payout-settlement currency (e.g. "KRW" for ZeroPay)
 */
public record PartnerQuoteRequest(
        String partnerCode,
        String schemeId,
        String direction,
        BigDecimal targetPayout,
        String payoutCurrency) {
}
