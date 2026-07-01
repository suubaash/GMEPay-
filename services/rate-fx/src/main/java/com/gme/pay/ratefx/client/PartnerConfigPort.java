package com.gme.pay.ratefx.client;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read port for the partner pricing config rate-fx needs to ISSUE a quote (the quote-issuer epic):
 * the per-partner currency split and the per-(scheme,direction) pricing rule. Owned by
 * config-registry; rate-fx consumes it only through this port (HTTP via
 * {@link RestConfigRegistryClient}; tests use a fake). rate-fx does not depend on lib-api-contracts,
 * so the JSON is mapped into these small local records — the same idiom as
 * {@code XeRateClient}/{@code XeMultiRateResponse}.
 *
 * <p>Unlike the payment-path config lookups (non-fatal), quote ISSUANCE requires this config: a
 * missing partner or no applicable rule is an error (the issuer cannot price), surfaced as a
 * {@link com.gme.pay.errors.ApiException}.
 */
public interface PartnerConfigPort {

    /** The partner's currency split (GET /v1/partners/{code}). */
    PartnerCurrencies getPartnerCurrencies(String partnerCode);

    /** The partner's current pricing rules (GET /v1/partners/{code}/rules); empty if none. */
    List<PartnerRule> getRules(String partnerCode);

    /**
     * Per-partner currency split. {@code collectionCcy} = what the partner collects from the
     * customer; {@code settleACcy} = the currency GME settles the collection leg with the partner in;
     * {@code settlementCurrency} = the legacy single currency (fallback when a side is null).
     */
    record PartnerCurrencies(String collectionCcy, String settleACcy, String settlementCurrency) {
        /** Collection currency, falling back to the legacy settlement currency. */
        public String collectionOrDefault() {
            return collectionCcy != null && !collectionCcy.isBlank() ? collectionCcy : settlementCurrency;
        }

        /** Settle-A currency, falling back to the legacy settlement currency. */
        public String settleAOrDefault() {
            return settleACcy != null && !settleACcy.isBlank() ? settleACcy : settlementCurrency;
        }
    }

    /**
     * One pricing rule (partner_rule V017) for a {@code (schemeId, direction)}: the partner margins
     * {@code mA} (collection-side) / {@code mB} (payout-side) as fractions, the flat
     * {@code serviceChargeUsd} per-transaction fee (USD), and the per-leg rate-source mode
     * ({@code rateCollSource} / {@code ratePaySource}, RATE-04 §3.2). {@code direction} is
     * INBOUND/OUTBOUND/BOTH (or null = all).
     *
     * <p>The source fields are nullable on the wire (config-registry may omit them); a null/blank
     * value means {@code LIVE} (resolved via {@link com.gme.pay.ratefx.issue.RateSource#fromNullable}).
     * Only {@code PARTNER} changes the resolution path — the pay leg is then quoted by Partner B
     * instead of the treasury snapshot store (WBS 4.6).
     */
    record PartnerRule(String schemeId, String direction,
                       BigDecimal mA, BigDecimal mB, BigDecimal serviceChargeUsd,
                       String rateCollSource, String ratePaySource) {

        /** Back-compat 5-arg form: both legs default to {@code LIVE} (null sources). */
        public PartnerRule(String schemeId, String direction,
                           BigDecimal mA, BigDecimal mB, BigDecimal serviceChargeUsd) {
            this(schemeId, direction, mA, mB, serviceChargeUsd, null, null);
        }
    }
}
