package com.gme.pay.bff.web.dto;

import com.gme.pay.contracts.PartnerCommand;

/**
 * BFF wire shape for
 * {@code PATCH /v1/admin/partners/draft/{partnerCode}/step-6-currency-split}
 * (Slice 6 / Settlement-flow Step 5 — the per-partner GME ↔ partner
 * settlement-currency pair, see {@code Documentation/SETTLEMENT_FLOW_SPEC.md}
 * §6.1). The URL identifies the partner being mutated; the body carries the
 * collect/settle currency split applied by config-registry's
 * {@link PartnerCommand.UpdateStep6CurrencySplit} write — the ONLY path that
 * can ORIGINATE a real split (the four-field create/step-1 path carries no
 * split fields).
 *
 * <ul>
 *   <li>{@code collectionCcy} — currency GME collects from the partner's
 *       prefund float.</li>
 *   <li>{@code settleACcy} — currency GME books the partner-liability leg
 *       in.</li>
 * </ul>
 *
 * <p>Both are ISO-4217 alpha-3 codes; config-registry validates the shape and
 * rejects a change once the partner is live (409). Adapter
 * {@link #toUpdateStep6CurrencySplit()} converts to the canonical write payload
 * before the BFF calls config-registry — the same seam discipline as
 * {@link DraftPartnerStep6CommercialRequest}.
 */
public record DraftPartnerStep6CurrencySplitRequest(
        String collectionCcy,
        String settleACcy) {

    /** Adapt to the canonical write surface {@code lib-api-contracts} exposes. */
    public PartnerCommand.UpdateStep6CurrencySplit toUpdateStep6CurrencySplit() {
        return new PartnerCommand.UpdateStep6CurrencySplit(collectionCcy, settleACcy);
    }
}
