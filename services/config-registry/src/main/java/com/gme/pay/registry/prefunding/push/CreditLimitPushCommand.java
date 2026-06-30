package com.gme.pay.registry.prefunding.push;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

/**
 * Wire body of the Wave-3 credit-limit push to prefunding
 * ({@code PUT /internal/v1/prefunding/{partnerId}/credit-limit}).
 *
 * <p>config-registry owns a partner's credit line ({@code credit_limit_usd},
 * V015 prefunding config) and AML velocity/volume caps (daily/monthly/annual +
 * daily transaction count, V020/V034 limits). prefunding needs them at balance
 * time to gate POSTPAID/HYBRID drawdown and AML breach — historically they
 * arrived per-request; this push lets config-registry update them once, on the
 * write that sets them (IR-pf-2 in the fleet status).
 *
 * <p>Local record (not a lib-api-contracts type) because lib-api-contracts is
 * FROZEN for Wave-3; prefunding binds the same field names on its side. Money
 * fields are major-USD-units {@link BigDecimal}, decimal STRINGs on the wire
 * (MONEY_CONVENTION); {@code dailyTxnCountLimit} is a plain integer count. Any
 * field may be {@code null} (the cap is unconstrained); nulls are omitted from
 * the JSON ({@code NON_NULL}) so an absent field reads as "no change to that
 * cap" rather than "clear it".
 *
 * @param creditLimitUsd     POSTPAID/HYBRID credit line; {@code null} = none.
 * @param dailyCapUsd        rolling daily volume cap; {@code null} = unconstrained.
 * @param monthlyCapUsd      rolling monthly volume cap; {@code null} = unconstrained.
 * @param annualCapUsd       rolling annual volume cap; {@code null} = unconstrained.
 * @param dailyTxnCountLimit per-day transaction-count cap; {@code null} = unconstrained.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreditLimitPushCommand(
        BigDecimal creditLimitUsd,
        BigDecimal dailyCapUsd,
        BigDecimal monthlyCapUsd,
        BigDecimal annualCapUsd,
        Integer dailyTxnCountLimit) {

    /** True when every cap is absent — nothing worth pushing. */
    public boolean isEmpty() {
        return creditLimitUsd == null
                && dailyCapUsd == null
                && monthlyCapUsd == null
                && annualCapUsd == null
                && dailyTxnCountLimit == null;
    }
}
