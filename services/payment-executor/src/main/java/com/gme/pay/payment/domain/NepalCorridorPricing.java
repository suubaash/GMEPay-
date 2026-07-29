package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.client.PartnerConfigClient;
import com.gme.pay.payment.domain.client.RateClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Resolves the commercial terms the Nepal (KRW→NPR) corridor must be priced with — gap T4-1.
 *
 * <h2>The rule this class exists to enforce</h2>
 * The reference rate, the FX margin and the service fee are <b>business decisions owned by GME</b>,
 * not implementation details. This resolver reads all three from configuration and <b>refuses</b>
 * ({@link CorridorPricingUnavailableException}) when any of them is missing. It contains no default
 * margin, no default fee and no fallback rate constant.
 *
 * <p>That last point is deliberate. {@link UsdAmountBasis#KRW_PER_USD_FALLBACK} (1350) exists so the
 * regulatory LIMIT check can still run during a rate outage — a conservative direction for a control.
 * Using it to PRICE a payment is the opposite: it silently sells FX at a stale rate. So this resolver
 * fetches USD/KRW itself and fails closed, rather than calling {@code UsdAmountBasis.krwPerUsd}.
 *
 * <h2>Where the terms come from</h2>
 * <ol>
 *   <li><b>config-registry commercial terms (preferred)</b> — the Slice 6 surface already models
 *       exactly these fields, so no new pricing store is invented:
 *       <ul>
 *         <li>margin ← {@code GET /v1/partners/{code}/fx-config} → {@code marginBps}
 *             ({@code partner_fx_config}, V019)</li>
 *         <li>fee ← {@code GET /v1/partners/{code}/fee-schedules/effective?schemeId=NEPAL
 *             &direction=OVERSEAS&amountUsd=…} → {@code serviceFeeUsd}
 *             ({@code partner_fee_schedule}, V018 — fixed + bps + volume tiers)</li>
 *       </ul>
 *   </li>
 *   <li><b>Module config override</b> — {@code gmepay.payment.nepal.fx-margin} (decimal fraction) and
 *       {@code gmepay.payment.nepal.service-fee-krw}. Both are <b>unset by default</b>, so an
 *       unconfigured deployment fails closed; they exist so a local/sim or pre-onboarding environment
 *       can be made to transact by CONFIGURATION rather than by a hardcoded default.</li>
 * </ol>
 *
 * <p>The live rates always come from the rate provider ({@link RateClient#fetchLiveRate}); only the
 * margin/fee/source labels are configuration. {@code referenceRateSource} is carried for audit — it
 * records which quote source the terms were struck against and never alters arithmetic.
 *
 * <h2>Margin convention</h2>
 * Identical to SENDMN so the two cross-border corridors read the same way:
 * {@code offerRate = midRate × (1 − margin)} — the payer's KRW buys fewer NPR than mid, and the
 * difference is GME's payout-leg margin. {@code margin} must be in {@code [0, 1)}.
 */
@Component
public class NepalCorridorPricing {

    private static final Logger log = LoggerFactory.getLogger(NepalCorridorPricing.class);

    /** Human-readable corridor label used in every refusal message. */
    public static final String CORRIDOR = "NEPAL corridor (KRW→NPR)";

    /** Scheme code the fee schedule is keyed on. */
    public static final String SCHEME_CODE = "NEPAL";

    /** Corridor direction the fee schedule is keyed on (V018 roster). */
    public static final String DIRECTION = "OVERSEAS";

    /** Payout currency. NPR is a 2-decimal currency (paisa). */
    public static final String PAYOUT_CURRENCY = "NPR";

    /** Collection currency — the wallet is KRW-funded and GME collects in KRW. */
    public static final String COLLECTION_CURRENCY = "KRW";

    /** Working precision for the offer-rate multiplication (matches SENDMN). */
    private static final MathContext RATE_MC = new MathContext(12, RoundingMode.HALF_UP);

    /** Scale of the USD figures the prefunding float and the limit caps are denominated in. */
    static final int USD_SCALE = 8;

    @Nullable private final RateClient rateClient;
    @Nullable private final PartnerConfigClient partnerConfigClient;
    /** Optional module-config margin override (decimal fraction). Blank = not configured. */
    private final String fxMarginOverride;
    /** Optional module-config service-fee override in KRW. Blank = not configured. */
    private final String serviceFeeKrwOverride;

    @Autowired
    public NepalCorridorPricing(
            @Nullable RateClient rateClient,
            @Nullable PartnerConfigClient partnerConfigClient,
            @Value("${gmepay.payment.nepal.fx-margin:}") String fxMarginOverride,
            @Value("${gmepay.payment.nepal.service-fee-krw:}") String serviceFeeKrwOverride) {
        this.rateClient = rateClient;
        this.partnerConfigClient = partnerConfigClient;
        this.fxMarginOverride = fxMarginOverride == null ? "" : fxMarginOverride.trim();
        this.serviceFeeKrwOverride = serviceFeeKrwOverride == null ? "" : serviceFeeKrwOverride.trim();
    }

    /** Test/explicit-wiring constructor — terms supplied directly, no Spring property binding. */
    public NepalCorridorPricing(@Nullable RateClient rateClient,
                                @Nullable PartnerConfigClient partnerConfigClient) {
        this(rateClient, partnerConfigClient, "", "");
    }

    /**
     * The rate side of the corridor's pricing: the live KRW→NPR mid rate, the configured margin, the
     * resulting offer rate, and the live USD/KRW rate the USD float leg is measured in.
     *
     * @param partnerCode the partner whose commercial terms apply (the wallet issuer)
     * @throws CorridorPricingUnavailableException when a rate or the margin cannot be established
     */
    public Rates resolveRates(@Nullable String partnerCode) {
        BigDecimal midRate = liveRate(COLLECTION_CURRENCY, PAYOUT_CURRENCY);
        BigDecimal krwPerUsd = liveRate("USD", COLLECTION_CURRENCY);

        MarginTerm margin = resolveMargin(partnerCode);
        BigDecimal offerRate = midRate.multiply(BigDecimal.ONE.subtract(margin.fraction()), RATE_MC);
        if (offerRate.signum() <= 0) {
            throw CorridorPricingUnavailableException.notConfigured(CORRIDOR,
                    "the configured FX margin (" + margin.fraction() + ") produces a non-positive"
                            + " offer rate", margin.source());
        }
        return new Rates(midRate, margin.fraction(), offerRate, krwPerUsd,
                margin.rateSource(), margin.source());
    }

    /**
     * The fee side: the service fee GME charges on this transaction, in USD.
     *
     * @param partnerCode the partner whose fee schedule applies
     * @param amountUsd   the USD volume the tiered/bps fee component is charged on
     * @param krwPerUsd   the SAME rate {@link #resolveRates} returned, so the KRW fee shown to the
     *                    payer and the USD fee debited from float are on one basis
     * @throws CorridorPricingUnavailableException when no fee is configured
     */
    public Fee resolveFee(@Nullable String partnerCode, BigDecimal amountUsd, BigDecimal krwPerUsd) {
        // (1) Module-config override, in KRW — the local/sim escape hatch, unset by default.
        if (!serviceFeeKrwOverride.isBlank()) {
            BigDecimal feeKrw = parsePositiveOrZero(serviceFeeKrwOverride,
                    "gmepay.payment.nepal.service-fee-krw");
            return new Fee(feeKrw, feeKrw.divide(krwPerUsd, 4, RoundingMode.HALF_UP),
                    "gmepay.payment.nepal.service-fee-krw");
        }
        // (2) config-registry partner_fee_schedule (V018), resolved for this scheme/direction/volume.
        if (partnerConfigClient != null && partnerCode != null && !partnerCode.isBlank()) {
            BigDecimal feeUsd = partnerConfigClient
                    .resolveServiceFeeUsd(partnerCode, SCHEME_CODE, DIRECTION, amountUsd)
                    .orElse(null);
            if (feeUsd != null && feeUsd.signum() >= 0) {
                return new Fee(feeUsd.multiply(krwPerUsd).setScale(0, RoundingMode.HALF_UP),
                        feeUsd.setScale(4, RoundingMode.HALF_UP),
                        "config-registry partner_fee_schedule");
            }
        }
        throw CorridorPricingUnavailableException.notConfigured(CORRIDOR, "the service fee",
                "config-registry GET /v1/partners/" + partnerCode + "/fee-schedules/effective"
                        + "?schemeId=" + SCHEME_CODE + "&direction=" + DIRECTION
                        + ", or gmepay.payment.nepal.service-fee-krw");
    }

    // ---------------------------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------------------------

    /** Live rate or refusal. No fallback constant — see the class javadoc. */
    private BigDecimal liveRate(String base, String quote) {
        String pair = base + "/" + quote;
        if (rateClient == null) {
            throw CorridorPricingUnavailableException.rateUnavailable(CORRIDOR, pair, null);
        }
        try {
            RateClient.LiveRate r = rateClient.fetchLiveRate(base, quote);
            if (r == null || r.rate() == null || r.rate().signum() <= 0) {
                throw CorridorPricingUnavailableException.rateUnavailable(CORRIDOR, pair, null);
            }
            return r.rate();
        } catch (CorridorPricingUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw CorridorPricingUnavailableException.rateUnavailable(CORRIDOR, pair, ex);
        }
    }

    private MarginTerm resolveMargin(@Nullable String partnerCode) {
        // (1) Module-config override — unset by default.
        if (!fxMarginOverride.isBlank()) {
            return new MarginTerm(parseMarginFraction(fxMarginOverride,
                    "gmepay.payment.nepal.fx-margin"),
                    "MODULE_CONFIG", "gmepay.payment.nepal.fx-margin");
        }
        // (2) config-registry partner_fx_config (V019).
        if (partnerConfigClient != null && partnerCode != null && !partnerCode.isBlank()) {
            PartnerConfigClient.FxTerms terms =
                    partnerConfigClient.resolveFxConfig(partnerCode).orElse(null);
            if (terms != null && terms.marginFraction() != null) {
                BigDecimal fraction = terms.marginFraction();
                if (fraction.signum() < 0 || fraction.compareTo(BigDecimal.ONE) >= 0) {
                    throw CorridorPricingUnavailableException.notConfigured(CORRIDOR,
                            "the FX margin (" + terms.marginBps() + " bps is outside [0, 10000))",
                            "config-registry partner_fx_config");
                }
                String source = terms.referenceRateSource() == null
                        ? "UNSPECIFIED" : terms.referenceRateSource();
                log.debug("{} priced with margin {} from config-registry (rate source {})",
                        CORRIDOR, fraction, source);
                return new MarginTerm(fraction, source, "config-registry partner_fx_config");
            }
        }
        throw CorridorPricingUnavailableException.notConfigured(CORRIDOR, "the FX margin",
                "config-registry GET /v1/partners/" + partnerCode + "/fx-config,"
                        + " or gmepay.payment.nepal.fx-margin");
    }

    private static BigDecimal parseMarginFraction(String raw, String key) {
        BigDecimal v;
        try {
            v = new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            throw CorridorPricingUnavailableException.notConfigured(CORRIDOR,
                    "the FX margin ('" + raw + "' is not a decimal)", key);
        }
        if (v.signum() < 0 || v.compareTo(BigDecimal.ONE) >= 0) {
            throw CorridorPricingUnavailableException.notConfigured(CORRIDOR,
                    "the FX margin (" + v + " is outside [0, 1))", key);
        }
        return v;
    }

    private static BigDecimal parsePositiveOrZero(String raw, String key) {
        BigDecimal v;
        try {
            v = new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            throw CorridorPricingUnavailableException.notConfigured(CORRIDOR,
                    "the service fee ('" + raw + "' is not a decimal)", key);
        }
        if (v.signum() < 0) {
            throw CorridorPricingUnavailableException.notConfigured(CORRIDOR,
                    "the service fee (" + v + " is negative)", key);
        }
        return v;
    }

    /**
     * The resolved rate side of the corridor's pricing.
     *
     * @param midRateNprPerKrw   live mid rate, NPR per 1 KRW
     * @param marginFraction     configured FX margin as a decimal fraction
     * @param offerRateNprPerKrw {@code mid × (1 − margin)} — what the payer actually gets
     * @param krwPerUsd          live USD/KRW, the basis for the USD float + cap figures
     * @param referenceRateSource audit label of the quote source the terms were struck against
     * @param marginSource       where the margin came from (for logs / the ops report)
     */
    public record Rates(BigDecimal midRateNprPerKrw,
                        BigDecimal marginFraction,
                        BigDecimal offerRateNprPerKrw,
                        BigDecimal krwPerUsd,
                        String referenceRateSource,
                        String marginSource) {
    }

    /** The resolved service fee, in both the payer's currency (KRW) and the float currency (USD). */
    public record Fee(BigDecimal feeKrw, BigDecimal feeUsd, String source) {
    }

    /** Internal carrier for a resolved margin + its provenance. */
    private record MarginTerm(BigDecimal fraction, String rateSource, String source) {
    }
}
