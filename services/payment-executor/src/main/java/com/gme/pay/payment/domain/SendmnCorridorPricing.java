package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.client.PartnerConfigClient;
import com.gme.pay.payment.domain.client.RateClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * The commercial terms the SENDMN (KRW→MNT) corridor is priced with — gap <b>CFO#11</b>.
 *
 * <h2>What CFO#11 actually was</h2>
 * SENDMN's price lived in Java: {@code SendmnPaymentService.FEE_KRW = 500} and an FX margin whose
 * "configuration" was a {@code @Value} default of {@code 0.02} that {@code application.properties} then
 * re-stated. Meanwhile {@code config-registry} owns commercial terms for every partner
 * ({@code partner_fx_config} V019, {@code partner_fee_schedule} V018, editable in the step-6 commercial
 * panel). So the corridor's pricing existed in two places and the code silently won: an owner could set
 * a margin in the registry, see it saved, and change nothing about what customers were charged.
 *
 * <h2>What this class does — and deliberately does NOT do</h2>
 * It routes SENDMN through the same {@link CorridorPricing} resolver T4-1 built for Nepal, so the
 * registry is now consulted and, once terms are entered there, they take effect. It is a
 * <b>behaviour-preserving refactor, not a pricing change</b>:
 * <ul>
 *   <li>{@link #DEFAULT_FX_MARGIN} (2%) and {@link #DEFAULT_FEE_KRW} (₩500) are declared as the
 *       corridor's <b>code defaults</b>. Nothing is configured today, so those are what the corridor
 *       charges — bit for bit what it charged before this change.</li>
 *   <li>Unlike Nepal, SENDMN therefore <b>never refuses a payment for want of pricing</b>. Nepal fails
 *       closed because it had no price at all; SENDMN has a working price and live corridor traffic, and
 *       refusing would be an outage rather than a fix.</li>
 *   <li>The values are not silent any more: the first use of either logs a WARN naming the exact place
 *       an owner must enter the real term, and {@link CorridorPricing#provenance()} exposes the state
 *       for reading. The drift is now visible instead of invisible.</li>
 * </ul>
 * Neither constant is a price chosen here. Each is a record of a price GME is <em>already</em> charging,
 * held visibly until an owner confirms it in config-registry — at which point these defaults stop being
 * reached and can be deleted.
 *
 * <h2>The USD/KRW fallback stays, and why that is a different question</h2>
 * {@code strictUsdBasis} is {@code false} by default, so an unavailable live USD/KRW still falls back to
 * {@link UsdAmountBasis#KRW_PER_USD_FALLBACK} (1350) exactly as it always has. That constant is not a
 * price: it converts the KRW charge into the USD figure the prefunding float is debited by and the T4-2
 * regulatory cap is evaluated on — a control basis, whose conservative direction is why T4-2 sanctioned
 * it in the first place. Failing closed on it (as Nepal does) would take a working corridor offline
 * during a rate-provider blip, which is the outage this task exists to avoid, and the wrongness it can
 * cause is already <em>detected</em> rather than ignored: settlement-reconciliation's T2-2 tie-out
 * back-derives every deduction and counts the ones priced off 1350 in
 * {@code corridor_recon_summary.fallback_rate_basis_count}, and {@code UsdAmountBasis} WARNs on each use.
 *
 * <p>It is nonetheless a real exposure, so it is now a <b>decision an owner can take</b> rather than a
 * property of the code: {@code gmepay.payment.sendmn.usd-basis-strict=true} makes SENDMN refuse
 * (503 {@code CORRIDOR_RATE_UNAVAILABLE}, retryable, nothing moved) instead of falling back. Default
 * {@code false} = today's behaviour, so this branch changes nothing until someone chooses otherwise.
 * The fallback is never used to compute the FX offer rate on any corridor.
 */
@Component
public class SendmnCorridorPricing extends CorridorPricing {

    /** Human-readable corridor label used in every refusal message and WARN. */
    public static final String CORRIDOR = "SENDMN corridor (KRW→MNT)";

    /** Scheme code the fee schedule is keyed on ({@code partner_fee_schedule.scheme_id}). */
    public static final String SCHEME_CODE = "SENDMN";

    /** Corridor direction the fee schedule is keyed on (V018 roster). */
    public static final String DIRECTION = "OVERSEAS";

    /** Payout currency. MNT is a 0-decimal currency in practice on this scheme. */
    public static final String PAYOUT_CURRENCY = "MNT";

    /** Collection currency — the wallet is KRW-funded and GME collects in KRW. */
    public static final String COLLECTION_CURRENCY = "KRW";

    /**
     * The FX margin the corridor has been charging (2%), kept as the code default so externalising the
     * terms does not re-price live traffic. <b>Not a value chosen here</b> — see the class javadoc.
     */
    public static final BigDecimal DEFAULT_FX_MARGIN = new BigDecimal("0.02");

    /**
     * The flat service fee the corridor has been charging (₩500), kept as the code default for the same
     * reason. Formerly {@code SendmnPaymentService.FEE_KRW}.
     */
    public static final BigDecimal DEFAULT_FEE_KRW = new BigDecimal("500");

    /**
     * Working precision of the offer-rate multiplication. <b>10, not Nepal's 12</b>: this is the value
     * {@code SendmnPaymentService} has always used, and a refactor must not shift a live corridor's
     * arithmetic by a digit.
     */
    private static final MathContext RATE_MC = new MathContext(10, RoundingMode.HALF_UP);

    @Autowired
    public SendmnCorridorPricing(
            @Nullable RateClient rateClient,
            @Nullable PartnerConfigClient partnerConfigClient,
            @Value("${gmepay.payment.sendmn.fx-margin:}") String fxMarginOverride,
            @Value("${gmepay.payment.sendmn.service-fee-krw:}") String serviceFeeKrwOverride,
            @Value("${gmepay.payment.sendmn.usd-basis-strict:false}") boolean usdBasisStrict) {
        super(spec(usdBasisStrict), rateClient, partnerConfigClient,
                fxMarginOverride, serviceFeeKrwOverride);
    }

    /**
     * Test/explicit-wiring constructor: config-registry (when a client is given) then the code
     * defaults — i.e. the production default shape, with no module-config override in play.
     */
    public SendmnCorridorPricing(@Nullable RateClient rateClient,
                                 @Nullable PartnerConfigClient partnerConfigClient) {
        this(rateClient, partnerConfigClient, "", "", false);
    }

    /** Test/explicit-wiring constructor with the module-config overrides supplied directly. */
    public SendmnCorridorPricing(@Nullable RateClient rateClient,
                                 @Nullable PartnerConfigClient partnerConfigClient,
                                 String fxMarginOverride,
                                 String serviceFeeKrwOverride) {
        this(rateClient, partnerConfigClient, fxMarginOverride, serviceFeeKrwOverride, false);
    }

    private static Spec spec(boolean usdBasisStrict) {
        return new Spec(CORRIDOR, SCHEME_CODE, DIRECTION, COLLECTION_CURRENCY, PAYOUT_CURRENCY,
                "gmepay.payment.sendmn.fx-margin", "gmepay.payment.sendmn.service-fee-krw",
                DEFAULT_FX_MARGIN, DEFAULT_FEE_KRW, RATE_MC, usdBasisStrict);
    }
}
