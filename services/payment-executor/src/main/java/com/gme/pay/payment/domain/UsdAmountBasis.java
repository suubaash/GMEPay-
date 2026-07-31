package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.client.RateClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The ONE place the wallet path turns a corridor amount into the USD figure the regulatory limits
 * (V020 {@code partner_limits}) are denominated in.
 *
 * <p><b>Why a single source (T4-2).</b> The limit basis MUST equal the money basis: if the cap is
 * evaluated at one rate and the prefunding float is debited at another, a partner can be over its
 * statutory 소액해외송금업 ceiling in USD terms while every local check passes. So this helper
 * re-uses exactly the rate each corridor already uses for its prefunding deduction — the live
 * {@code USD/KRW} mid rate from the rate provider, with the same conservative
 * {@link #KRW_PER_USD_FALLBACK} constant the SENDMN deduction has always fallen back to. No second
 * rate source is introduced.
 *
 * <p><b>Fail-closed by omission.</b> {@link #toUsd} returns {@code null} when it cannot establish a
 * basis (a non-KRW corridor currency whose live USD rate is unavailable). {@code null} is NOT
 * "unlimited" — {@link WalletLimitGate} turns it into a {@link LimitCheckUnavailableException}
 * decline whenever the partner actually has a cap configured. KRW never returns null (the fallback
 * constant is the sanctioned basis for the KRW corridors).
 *
 * <p>Deliberately static, like {@link TransactionLimitPolicy}: no state, no bean, callable from the
 * wallet services and the gate alike.
 */
public final class UsdAmountBasis {

    private static final Logger log = LoggerFactory.getLogger(UsdAmountBasis.class);

    /**
     * Conservative KRW/USD fallback used when the live rate is unavailable. Historically
     * {@code SendmnPaymentService.KRW_PER_USD} — moved here so the prefunding deduction and the
     * limit check cannot drift onto different constants.
     */
    public static final BigDecimal KRW_PER_USD_FALLBACK = new BigDecimal("1350");

    /** Scale of the USD basis; matches the SENDMN prefunding deduction ({@code chargedUsd}). */
    private static final int USD_SCALE = 8;

    private UsdAmountBasis() {
    }

    /**
     * Live {@code USD/KRW} (KRW per 1 USD) from the rate provider, falling back to
     * {@link #KRW_PER_USD_FALLBACK} when the provider is unreachable or answers a non-positive rate.
     * This is the rate the SENDMN prefunding deduction uses, so the limit check and the float debit
     * share one basis.
     */
    public static BigDecimal krwPerUsd(@Nullable RateClient rateClient) {
        if (rateClient == null) {
            return KRW_PER_USD_FALLBACK;
        }
        try {
            RateClient.LiveRate r = rateClient.fetchLiveRate("USD", "KRW");
            if (r != null && r.rate() != null && r.rate().signum() > 0) {
                return r.rate();
            }
            log.warn("live USD/KRW rate empty — falling back to {} KRW/USD", KRW_PER_USD_FALLBACK);
        } catch (RuntimeException ex) {
            log.warn("live USD/KRW rate unavailable ({}) — falling back to {} KRW/USD",
                    ex.getMessage(), KRW_PER_USD_FALLBACK);
        }
        return KRW_PER_USD_FALLBACK;
    }

    /**
     * USD equivalent of {@code amount} denominated in {@code currency}.
     *
     * @return the USD figure, or {@code null} when no basis can be established (caller must then
     *         fail CLOSED if a cap is configured — never treat null as unconstrained)
     */
    @Nullable
    public static BigDecimal toUsd(@Nullable RateClient rateClient,
                                   @Nullable BigDecimal amount,
                                   @Nullable String currency) {
        if (amount == null) {
            return null;
        }
        String ccy = (currency == null || currency.isBlank()) ? "KRW" : currency.trim().toUpperCase(java.util.Locale.ROOT);
        if ("USD".equals(ccy)) {
            return amount;
        }
        if ("KRW".equals(ccy)) {
            return amount.divide(krwPerUsd(rateClient), USD_SCALE, RoundingMode.HALF_UP);
        }
        // Any other corridor currency (NPR / MNT / …): the live USD/<ccy> rate is the ONLY sanctioned
        // basis — there is no blessed fallback constant, so an outage yields null (fail closed).
        if (rateClient == null) {
            return null;
        }
        try {
            RateClient.LiveRate r = rateClient.fetchLiveRate("USD", ccy);
            if (r != null && r.rate() != null && r.rate().signum() > 0) {
                return amount.divide(r.rate(), USD_SCALE, RoundingMode.HALF_UP);
            }
            log.warn("live USD/{} rate empty — no USD limit basis available", ccy);
        } catch (RuntimeException ex) {
            log.warn("live USD/{} rate unavailable ({}) — no USD limit basis available", ccy, ex.getMessage());
        }
        return null;
    }
}
