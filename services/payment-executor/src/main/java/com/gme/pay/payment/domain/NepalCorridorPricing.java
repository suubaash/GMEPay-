package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.client.PartnerConfigClient;
import com.gme.pay.payment.domain.client.RateClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.MathContext;
import java.math.RoundingMode;

/**
 * The commercial terms the Nepal (KRW→NPR) corridor must be priced with — gap T4-1.
 *
 * <p>All resolution logic lives in {@link CorridorPricing}; this class is nothing but Nepal's
 * descriptor. Two entries in that descriptor are the whole point of T4-1:
 * <ul>
 *   <li><b>No code default for the margin or the fee.</b> Nepal had no pricing at all before T4-1 —
 *       the wallet's KRW was handed to the scheme as if it were NPR — so there is no working price to
 *       preserve, and a guessed margin or a copied SENDMN fee would be the same class of defect as the
 *       pass-through it replaced. Missing terms therefore <b>refuse</b> the payment
 *       ({@link CorridorPricingUnavailableException}, 503, no float moved, no scheme call). A corridor
 *       in that state is not sellable, and saying so on the wire is the honest answer.</li>
 *   <li><b>{@code strictUsdBasis = true}.</b> {@link UsdAmountBasis#KRW_PER_USD_FALLBACK} (1350) is a
 *       control basis, not a price; Nepal fetches USD/KRW live and refuses instead of leaning on it.</li>
 * </ul>
 *
 * <p>Terms come from config-registry's Slice 6 surface ({@code partner_fx_config} V019 /
 * {@code partner_fee_schedule} V018) or, for a local/sim environment, from
 * {@code gmepay.payment.nepal.fx-margin} / {@code gmepay.payment.nepal.service-fee-krw} — both unset
 * by default. See {@link CorridorPricing} for the full order and the margin convention.
 */
@Component
public class NepalCorridorPricing extends CorridorPricing {

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

    /** Working precision for the offer-rate multiplication (T4-1's original value — do not change). */
    private static final MathContext RATE_MC = new MathContext(12, RoundingMode.HALF_UP);

    private static final Spec SPEC = new Spec(
            CORRIDOR, SCHEME_CODE, DIRECTION, COLLECTION_CURRENCY, PAYOUT_CURRENCY,
            "gmepay.payment.nepal.fx-margin", "gmepay.payment.nepal.service-fee-krw",
            /* codeDefaultMarginFraction */ null,   // fail closed — T4-1 invents no price
            /* codeDefaultFeeKrw */ null,           // fail closed — T4-1 invents no price
            RATE_MC,
            /* strictUsdBasis */ true);             // 1350 is a control basis, never a price

    @Autowired
    public NepalCorridorPricing(
            @Nullable RateClient rateClient,
            @Nullable PartnerConfigClient partnerConfigClient,
            @Value("${gmepay.payment.nepal.fx-margin:}") String fxMarginOverride,
            @Value("${gmepay.payment.nepal.service-fee-krw:}") String serviceFeeKrwOverride) {
        super(SPEC, rateClient, partnerConfigClient, fxMarginOverride, serviceFeeKrwOverride);
    }

    /** Test/explicit-wiring constructor — no Spring property binding, so nothing is configured. */
    public NepalCorridorPricing(@Nullable RateClient rateClient,
                                @Nullable PartnerConfigClient partnerConfigClient) {
        this(rateClient, partnerConfigClient, "", "");
    }
}
