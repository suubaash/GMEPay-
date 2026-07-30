package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.client.PartnerConfigClient;
import com.gme.pay.payment.domain.client.RateClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The ONE resolver a cross-border wallet corridor prices itself with — the shared mechanism behind
 * {@link NepalCorridorPricing} (gap T4-1) and {@link SendmnCorridorPricing} (gap CFO#11).
 *
 * <h2>The rule this class exists to enforce</h2>
 * The FX margin and the service fee are <b>business decisions owned by GME</b>, not implementation
 * details. {@code config-registry} already models both ({@code partner_fx_config} V019,
 * {@code partner_fee_schedule} V018 — the Slice 6 commercial-terms surface an owner edits in the
 * step-6 panel), so a corridor must read them from there and never from a constant in Java. CFO#11
 * is exactly the drift that happens otherwise: the terms exist in two places and the code silently
 * wins, so nobody can tell what price the platform is actually charging by reading the configuration.
 *
 * <h2>Resolution order (identical for every corridor)</h2>
 * <ol>
 *   <li><b>Module config override</b> — {@code <prefix>.fx-margin} (decimal fraction) /
 *       {@code <prefix>.service-fee-krw}. Unset by default. This is the local/sim/pre-onboarding
 *       escape hatch: an environment can be made to transact by CONFIGURATION, never by a default.</li>
 *   <li><b>config-registry commercial terms (the intended production source)</b> —
 *       margin ← {@code GET /v1/partners/{code}/fx-config} → {@code marginBps};
 *       fee ← {@code GET /v1/partners/{code}/fee-schedules/effective?schemeId=…&direction=…&amountUsd=…}
 *       → {@code serviceFeeUsd} (fixed + bps + volume tiers, most-specific match).</li>
 *   <li><b>Corridor code default, only if the corridor declares one</b> — see below.</li>
 *   <li>Otherwise <b>refuse</b> ({@link CorridorPricingUnavailableException}).</li>
 * </ol>
 *
 * <h2>Why two corridors behave differently at step 3, deliberately</h2>
 * <ul>
 *   <li><b>Nepal declares NO code default and fails closed.</b> It had no pricing at all before T4-1
 *       (KRW was handed to the scheme as NPR), so there is no "today's behaviour" worth preserving and
 *       a guessed margin would be the same class of defect as the pass-through it replaced.</li>
 *   <li><b>SENDMN declares its historical values as code defaults and keeps charging them.</b> It has
 *       working pricing and live corridor traffic. Making it refuse, or quietly re-pricing it, would be
 *       an outage or a unilateral pricing change — neither is a refactor's business. So the constants
 *       survive as an explicitly-labelled last resort: the money does not move, but the state stops
 *       being invisible ({@link #provenance()} + a one-shot WARN naming where the owner must enter the
 *       real terms).</li>
 * </ul>
 * A code default is therefore never a price GME chose here; it is a record of a price GME is already
 * charging, held visibly until an owner confirms it in config-registry.
 *
 * <h2>The USD basis is a control, not a price</h2>
 * {@link UsdAmountBasis#KRW_PER_USD_FALLBACK} (1350) exists so the regulatory LIMIT check and the USD
 * float debit can still be evaluated during a rate outage — a conservative direction for a control.
 * Whether a corridor may lean on it is {@link Spec#strictUsdBasis()}:
 * <ul>
 *   <li>{@code strictUsdBasis=true} (Nepal) — no fallback; a missing live USD/KRW refuses the payment.</li>
 *   <li>{@code strictUsdBasis=false} (SENDMN's default) — the historical graceful fallback, so a
 *       rate-provider blip does not take a live corridor down. Every use is WARNed by
 *       {@code UsdAmountBasis}, surfaced on {@link Rates#usdBasisFallbackUsed()}, and independently
 *       detected downstream by settlement-reconciliation's {@code fallback_rate_basis_count} (T2-2).</li>
 * </ul>
 * The fallback is never used to compute an FX <em>offer rate</em> on any corridor — that rate always
 * comes live from the rate provider or the payment is refused.
 *
 * <h2>Margin convention</h2>
 * {@code offerRate = midRate × (1 − margin)} — the payer's KRW buys less than mid, and the difference
 * is GME's payout-leg margin. {@code margin} must be in {@code [0, 1)}.
 */
public abstract class CorridorPricing {

    /** Scale of the USD figures the prefunding float and the limit caps are denominated in. */
    static final int USD_SCALE = 8;

    private static final BigDecimal BPS_PER_UNIT = new BigDecimal("10000");

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final Spec spec;
    @Nullable private final RateClient rateClient;
    @Nullable private final PartnerConfigClient partnerConfigClient;
    /** Module-config margin override (decimal fraction). Blank = not configured. */
    private final String fxMarginOverride;
    /** Module-config service-fee override in KRW. Blank = not configured. */
    private final String serviceFeeKrwOverride;

    /** One WARN per JVM per term — an ops signal, not a per-payment log flood. */
    private final AtomicBoolean marginDefaultWarned = new AtomicBoolean();
    private final AtomicBoolean feeDefaultWarned = new AtomicBoolean();

    // Last-resolved provenance, so the state is READABLE and not only loggable (see provenance()).
    private final AtomicReference<String> lastMarginSource = new AtomicReference<>(UNRESOLVED);
    private final AtomicReference<String> lastFeeSource = new AtomicReference<>(UNRESOLVED);
    private final AtomicBoolean marginOnCodeDefault = new AtomicBoolean();
    private final AtomicBoolean feeOnCodeDefault = new AtomicBoolean();
    private final AtomicBoolean usdBasisFallbackSeen = new AtomicBoolean();

    /** Provenance label before the corridor has priced anything. */
    public static final String UNRESOLVED = "UNRESOLVED";

    /** Provenance label for a term taken from the corridor's historical hardcoded value. */
    public static final String CODE_DEFAULT = "CODE_DEFAULT";

    protected CorridorPricing(Spec spec,
                              @Nullable RateClient rateClient,
                              @Nullable PartnerConfigClient partnerConfigClient,
                              @Nullable String fxMarginOverride,
                              @Nullable String serviceFeeKrwOverride) {
        this.spec = spec;
        this.rateClient = rateClient;
        this.partnerConfigClient = partnerConfigClient;
        this.fxMarginOverride = fxMarginOverride == null ? "" : fxMarginOverride.trim();
        this.serviceFeeKrwOverride = serviceFeeKrwOverride == null ? "" : serviceFeeKrwOverride.trim();
    }

    /**
     * The rate side of the corridor's pricing: the live mid rate, the resolved margin, the resulting
     * offer rate, and the USD/KRW rate the USD float leg is measured in.
     *
     * @param partnerCode the partner whose commercial terms apply (the wallet issuer)
     * @throws CorridorPricingUnavailableException when a rate or the margin cannot be established
     */
    public Rates resolveRates(@Nullable String partnerCode) {
        BigDecimal midRate = liveRate(spec.collectionCurrency(), spec.payoutCurrency());
        UsdBasis basis = usdBasis();

        MarginTerm margin = resolveMargin(partnerCode);
        BigDecimal offerRate =
                midRate.multiply(BigDecimal.ONE.subtract(margin.fraction()), spec.rateMathContext());
        if (offerRate.signum() <= 0) {
            throw CorridorPricingUnavailableException.notConfigured(spec.corridor(),
                    "the configured FX margin (" + margin.fraction() + ") produces a non-positive"
                            + " offer rate", margin.source());
        }
        return new Rates(midRate, margin.fraction(), offerRate, basis.krwPerUsd(),
                margin.rateSource(), margin.source(), margin.fromCodeDefault(), basis.fallbackUsed());
    }

    /**
     * The fee side: the service fee GME charges on this transaction.
     *
     * @param partnerCode the partner whose fee schedule applies
     * @param amountUsd   the USD volume the tiered/bps fee component is charged on
     * @param krwPerUsd   the SAME rate {@link #resolveRates} returned, so the KRW fee shown to the
     *                    payer and the USD fee debited from float are on one basis
     * @throws CorridorPricingUnavailableException when no fee is configured and the corridor declares
     *                                            no code default
     */
    public Fee resolveFee(@Nullable String partnerCode, BigDecimal amountUsd, BigDecimal krwPerUsd) {
        // (1) Module-config override, in KRW — the local/sim escape hatch, unset by default.
        if (!serviceFeeKrwOverride.isBlank()) {
            BigDecimal feeKrw = parsePositiveOrZero(serviceFeeKrwOverride, spec.feePropertyKey());
            return recordFee(new Fee(feeKrw, feeKrw.divide(krwPerUsd, 4, RoundingMode.HALF_UP),
                    spec.feePropertyKey(), false));
        }
        // (2) config-registry partner_fee_schedule (V018), resolved for this scheme/direction/volume.
        if (partnerConfigClient != null && partnerCode != null && !partnerCode.isBlank()) {
            BigDecimal feeUsd = partnerConfigClient
                    .resolveServiceFeeUsd(partnerCode, spec.schemeCode(), spec.direction(), amountUsd)
                    .orElse(null);
            if (feeUsd != null && feeUsd.signum() >= 0) {
                return recordFee(new Fee(feeUsd.multiply(krwPerUsd).setScale(0, RoundingMode.HALF_UP),
                        feeUsd.setScale(4, RoundingMode.HALF_UP),
                        "config-registry partner_fee_schedule", false));
            }
        }
        // (3) The corridor's historical hardcoded fee, if it declares one. Behaviour-preserving and
        //     LOUD — the value is not a price chosen here, it is the price already being charged.
        BigDecimal codeDefaultFeeKrw = spec.codeDefaultFeeKrw();
        if (codeDefaultFeeKrw != null) {
            if (feeDefaultWarned.compareAndSet(false, true)) {
                log.warn("{} is charging a CODE-DEFAULT service fee of {} {} — this value is hardcoded"
                                + " in payment-executor, NOT owner-configured. Enter the real fee in"
                                + " config-registry ({}) or, for a local/sim environment, set {}."
                                + " Today's effective pricing is preserved until then (CFO#11).",
                        spec.corridor(), codeDefaultFeeKrw, spec.collectionCurrency(),
                        feeConfigLocation(partnerCode), spec.feePropertyKey());
            }
            return recordFee(new Fee(codeDefaultFeeKrw,
                    codeDefaultFeeKrw.divide(krwPerUsd, 4, RoundingMode.HALF_UP),
                    CODE_DEFAULT + " (" + spec.feePropertyKey() + " unset, no partner_fee_schedule row)",
                    true));
        }
        throw CorridorPricingUnavailableException.notConfigured(spec.corridor(), "the service fee",
                feeConfigLocation(partnerCode) + ", or " + spec.feePropertyKey());
    }

    /**
     * The corridor's CURRENT pricing provenance — where the margin and the fee last came from, and
     * whether either is still riding a code default. This is what makes CFO#11's "running on code
     * defaults" state observable rather than a comment in a Java file: an ops surface, a support
     * question or a test can read it without parsing logs.
     */
    public Provenance provenance() {
        return new Provenance(spec.corridor(),
                lastMarginSource.get(), marginOnCodeDefault.get(),
                lastFeeSource.get(), feeOnCodeDefault.get(),
                usdBasisFallbackSeen.get(),
                marginOnCodeDefault.get() || feeOnCodeDefault.get());
    }

    /** The corridor descriptor this resolver was built with (label, scheme, currencies). */
    protected Spec spec() {
        return spec;
    }

    // ---------------------------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------------------------

    /** Live rate or refusal. No fallback constant is ever used for an FX offer rate. */
    private BigDecimal liveRate(String base, String quote) {
        String pair = base + "/" + quote;
        if (rateClient == null) {
            throw CorridorPricingUnavailableException.rateUnavailable(spec.corridor(), pair, null);
        }
        try {
            RateClient.LiveRate r = rateClient.fetchLiveRate(base, quote);
            if (r == null || r.rate() == null || r.rate().signum() <= 0) {
                throw CorridorPricingUnavailableException.rateUnavailable(spec.corridor(), pair, null);
            }
            return r.rate();
        } catch (CorridorPricingUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw CorridorPricingUnavailableException.rateUnavailable(spec.corridor(), pair, ex);
        }
    }

    /**
     * The USD/KRW rate the float debit and the regulatory cap are measured in.
     *
     * <p>{@code strictUsdBasis} corridors refuse when it is unavailable. The others use
     * {@link UsdAmountBasis#krwPerUsd} — the single platform helper, so the limit check and the float
     * debit can never end up on different constants — and report that the fallback was taken.
     */
    private UsdBasis usdBasis() {
        if (spec.strictUsdBasis() || !"KRW".equals(spec.collectionCurrency())) {
            return new UsdBasis(liveRate("USD", spec.collectionCurrency()), false);
        }
        BigDecimal krwPerUsd = UsdAmountBasis.krwPerUsd(rateClient);
        boolean fallback = krwPerUsd.compareTo(UsdAmountBasis.KRW_PER_USD_FALLBACK) == 0;
        if (fallback) {
            usdBasisFallbackSeen.set(true);
        }
        return new UsdBasis(krwPerUsd, fallback);
    }

    private MarginTerm resolveMargin(@Nullable String partnerCode) {
        // (1) Module-config override — unset by default.
        if (!fxMarginOverride.isBlank()) {
            return recordMargin(new MarginTerm(
                    parseMarginFraction(fxMarginOverride, spec.marginPropertyKey()),
                    "MODULE_CONFIG", spec.marginPropertyKey(), false));
        }
        // (2) config-registry partner_fx_config (V019).
        if (partnerConfigClient != null && partnerCode != null && !partnerCode.isBlank()) {
            PartnerConfigClient.FxTerms terms =
                    partnerConfigClient.resolveFxConfig(partnerCode).orElse(null);
            if (terms != null && terms.marginFraction() != null) {
                BigDecimal fraction = terms.marginFraction();
                if (fraction.signum() < 0 || fraction.compareTo(BigDecimal.ONE) >= 0) {
                    throw CorridorPricingUnavailableException.notConfigured(spec.corridor(),
                            "the FX margin (" + terms.marginBps() + " bps is outside [0, "
                                    + BPS_PER_UNIT.toPlainString() + "))",
                            "config-registry partner_fx_config");
                }
                String source = terms.referenceRateSource() == null
                        ? "UNSPECIFIED" : terms.referenceRateSource();
                log.debug("{} priced with margin {} from config-registry (rate source {})",
                        spec.corridor(), fraction, source);
                return recordMargin(new MarginTerm(fraction, source,
                        "config-registry partner_fx_config", false));
            }
        }
        // (3) The corridor's historical hardcoded margin, if it declares one.
        BigDecimal codeDefaultMargin = spec.codeDefaultMarginFraction();
        if (codeDefaultMargin != null) {
            if (marginDefaultWarned.compareAndSet(false, true)) {
                log.warn("{} is pricing on a CODE-DEFAULT FX margin of {} — this value is hardcoded in"
                                + " payment-executor, NOT owner-configured. Enter the real margin in"
                                + " config-registry ({}) or, for a local/sim environment, set {}."
                                + " Today's effective pricing is preserved until then (CFO#11).",
                        spec.corridor(), codeDefaultMargin, fxConfigLocation(partnerCode),
                        spec.marginPropertyKey());
            }
            return recordMargin(new MarginTerm(codeDefaultMargin, CODE_DEFAULT,
                    CODE_DEFAULT + " (" + spec.marginPropertyKey()
                            + " unset, no partner_fx_config row)", true));
        }
        throw CorridorPricingUnavailableException.notConfigured(spec.corridor(), "the FX margin",
                fxConfigLocation(partnerCode) + ", or " + spec.marginPropertyKey());
    }

    private String fxConfigLocation(@Nullable String partnerCode) {
        return "config-registry GET /v1/partners/" + partnerCode + "/fx-config,";
    }

    private String feeConfigLocation(@Nullable String partnerCode) {
        return "config-registry GET /v1/partners/" + partnerCode + "/fee-schedules/effective"
                + "?schemeId=" + spec.schemeCode() + "&direction=" + spec.direction();
    }

    private MarginTerm recordMargin(MarginTerm term) {
        lastMarginSource.set(term.source());
        marginOnCodeDefault.set(term.fromCodeDefault());
        return term;
    }

    private Fee recordFee(Fee fee) {
        lastFeeSource.set(fee.source());
        feeOnCodeDefault.set(fee.fromCodeDefault());
        return fee;
    }

    private BigDecimal parseMarginFraction(String raw, String key) {
        BigDecimal v;
        try {
            v = new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            throw CorridorPricingUnavailableException.notConfigured(spec.corridor(),
                    "the FX margin ('" + raw + "' is not a decimal)", key);
        }
        if (v.signum() < 0 || v.compareTo(BigDecimal.ONE) >= 0) {
            throw CorridorPricingUnavailableException.notConfigured(spec.corridor(),
                    "the FX margin (" + v + " is outside [0, 1))", key);
        }
        return v;
    }

    private BigDecimal parsePositiveOrZero(String raw, String key) {
        BigDecimal v;
        try {
            v = new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            throw CorridorPricingUnavailableException.notConfigured(spec.corridor(),
                    "the service fee ('" + raw + "' is not a decimal)", key);
        }
        if (v.signum() < 0) {
            throw CorridorPricingUnavailableException.notConfigured(spec.corridor(),
                    "the service fee (" + v + " is negative)", key);
        }
        return v;
    }

    // ---------------------------------------------------------------------------------------
    // value types
    // ---------------------------------------------------------------------------------------

    /**
     * Everything corridor-specific about pricing, in one place, so the resolution logic itself is
     * shared verbatim between corridors.
     *
     * @param corridor                  human-readable label used in every refusal + WARN
     * @param schemeCode                scheme the fee schedule is keyed on
     * @param direction                 corridor direction the fee schedule is keyed on (V018 roster)
     * @param collectionCurrency        what GME collects from the payer (KRW on both corridors today)
     * @param payoutCurrency            what the merchant is paid in
     * @param marginPropertyKey         module-config key for the margin escape hatch
     * @param feePropertyKey            module-config key for the fee escape hatch
     * @param codeDefaultMarginFraction the corridor's historical hardcoded margin, or {@code null} to
     *                                  fail closed when nothing is configured
     * @param codeDefaultFeeKrw         the corridor's historical hardcoded fee in the collection
     *                                  currency, or {@code null} to fail closed
     * @param rateMathContext           precision of the offer-rate multiplication (per corridor, so
     *                                  externalising the terms cannot shift an existing corridor's
     *                                  arithmetic by a digit)
     * @param strictUsdBasis            {@code true} = refuse when live USD/KRW is unavailable;
     *                                  {@code false} = use the sanctioned limit-check fallback
     */
    protected record Spec(String corridor,
                          String schemeCode,
                          String direction,
                          String collectionCurrency,
                          String payoutCurrency,
                          String marginPropertyKey,
                          String feePropertyKey,
                          @Nullable BigDecimal codeDefaultMarginFraction,
                          @Nullable BigDecimal codeDefaultFeeKrw,
                          MathContext rateMathContext,
                          boolean strictUsdBasis) {
    }

    /**
     * The resolved rate side of the corridor's pricing.
     *
     * @param midRate               live mid rate, payout currency per 1 unit of collection currency
     * @param marginFraction        resolved FX margin as a decimal fraction
     * @param offerRate             {@code mid × (1 − margin)} — what the payer actually gets
     * @param krwPerUsd             USD/KRW, the basis for the USD float + cap figures
     * @param referenceRateSource   audit label of the quote source the terms were struck against
     * @param marginSource          where the margin came from (logs / ops report / tests)
     * @param marginFromCodeDefault true when the margin is the corridor's hardcoded historical
     *                              value because nothing is configured (CFO#11 drift, visible)
     * @param usdBasisFallbackUsed  true when {@code krwPerUsd} is the 1350 limit-check fallback
     *                              rather than a live quote (T2-2 rate-basis variance)
     */
    public record Rates(BigDecimal midRate,
                        BigDecimal marginFraction,
                        BigDecimal offerRate,
                        BigDecimal krwPerUsd,
                        String referenceRateSource,
                        String marginSource,
                        boolean marginFromCodeDefault,
                        boolean usdBasisFallbackUsed) {
    }

    /**
     * The resolved service fee, in both the payer's currency and the float currency (USD).
     *
     * @param fromCodeDefault true when the fee is the corridor's hardcoded historical value
     */
    public record Fee(BigDecimal feeKrw, BigDecimal feeUsd, String source, boolean fromCodeDefault) {
    }

    /**
     * A readable snapshot of where this corridor's commercial terms are coming from.
     *
     * @param ownerActionRequired true when EITHER term is still on a code default — i.e. an owner must
     *                            confirm the real value in config-registry
     */
    public record Provenance(String corridor,
                             String marginSource,
                             boolean marginOnCodeDefault,
                             String feeSource,
                             boolean feeOnCodeDefault,
                             boolean usdBasisFallbackSeen,
                             boolean ownerActionRequired) {
    }

    /** Internal carrier for a resolved margin + its provenance. */
    private record MarginTerm(BigDecimal fraction, String rateSource, String source,
                              boolean fromCodeDefault) {
    }

    /** Internal carrier for the USD basis + whether it came from the fallback constant. */
    private record UsdBasis(BigDecimal krwPerUsd, boolean fallbackUsed) {
    }
}
