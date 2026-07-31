package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.client.PartnerConfigClient;
import com.gme.pay.payment.domain.client.PrefundingClient;
import com.gme.pay.payment.domain.client.RateClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Regulatory limit gate for the WALLET payment path ({@code POST /v1/pay}) — gap T4-2.
 *
 * <p><b>The hole this closes.</b> Limit enforcement existed only on {@code POST /v1/payments/authorize}
 * ({@code PaymentOrchestrator} Step 1c + Step 4). Every wallet corridor — GMEREMIT domestic, SENDMN
 * KRW→MNT, and the Nepal/cross-border pass-through through {@code FailoverPaymentRouter} — went
 * straight to the scheme, so the DB-hard-capped 소액해외송금업 ceilings (5,000 USD per transaction /
 * 50,000 USD annual, V020) and the per-partner velocity cap (V034, the column named `aml_velocity_*` --
 * a transaction-count ceiling, NOT AML screening; see T5-3) were unenforced on the busiest entry point.
 *
 * <p><b>What it enforces</b>, in one place so every corridor gets the same rule:
 * <ol>
 *   <li><b>Per-transaction</b> min/max USD — the same {@link TransactionLimitPolicy} the authorize
 *       path uses, on the same {@code partner_limits} row. Pure, no side effect.</li>
 *   <li><b>Cumulative</b> daily / monthly / annual USD <i>and</i> the daily transaction-count
 *       velocity cap — delegated to {@link PrefundingClient#chargeCumulative}, i.e. the SAME
 *       race-free path the authorize gate uses (prefunding takes a per-partner row lock, so two
 *       concurrent wallet payments cannot both slip past a cap). This replaces the bare
 *       {@code deduct} the wallet corridors used to call, which consults no caps at all.</li>
 * </ol>
 *
 * <p><b>Rate basis.</b> The cap is USD-denominated, the wallet amount is not. The conversion goes
 * through {@link UsdAmountBasis} — the very rate the corridor already uses for its prefunding
 * deduction — so the limit basis and the money basis are identical. Corridors that already hold a USD
 * figure (SENDMN's {@code chargedUsd}) pass it in directly via {@link #enforceUsd} and no conversion
 * happens at all.
 *
 * <p><b>Ordering.</b> Callers invoke the gate AFTER merchant validation and BEFORE any irreversible
 * step (no float moved, no scheme call), and call {@link #reverse} on any subsequent decline so a
 * payment that never completed does not permanently consume cap.
 *
 * <p><b>Fail-open vs fail-closed.</b> "No limits row" (or config-registry unreachable) remains
 * fail-OPEN — that is the documented {@code resolveLimits} contract and pre-existing risk window,
 * unchanged here. But once limits ARE resolved, an inability to evaluate them
 * ({@link LimitCheckUnavailableException}) fails CLOSED: a configured cap is never silently skipped.
 */
@Component
public class WalletLimitGate {

    private static final Logger log = LoggerFactory.getLogger(WalletLimitGate.class);

    @Nullable private final PartnerConfigClient partnerConfigClient;
    @Nullable private final PrefundingClient prefundingClient;
    @Nullable private final RateClient rateClient;

    @Autowired
    public WalletLimitGate(@Nullable PartnerConfigClient partnerConfigClient,
                           @Nullable PrefundingClient prefundingClient,
                           @Nullable RateClient rateClient) {
        this.partnerConfigClient = partnerConfigClient;
        this.prefundingClient = prefundingClient;
        this.rateClient = rateClient;
    }

    /**
     * A gate with no config source — every {@code enforce} is a no-op. For legacy/minimal wiring and
     * for unit tests of behaviour unrelated to limits; production always wires the real bean.
     */
    public static WalletLimitGate disabled() {
        return new WalletLimitGate(null, null, null);
    }

    /**
     * Enforce the partner's limits on {@code amount} denominated in {@code currency} (converted to the
     * USD basis via {@link UsdAmountBasis}).
     *
     * @param partner the limit subject (wallet issuer); {@link WalletPartnerRef#none()} = unconstrained
     * @param txnRef  the reference the cumulative charge is keyed on — MUST be the same reference the
     *                corridor later uses for its prefunding deduct/reverse, so a reverse nets out
     * @return the charge handle; pass it to {@link #reverse} if the payment then declines
     * @throws TransactionLimitExceededException per-transaction min/max breach (nothing charged)
     * @throws CumulativeLimitExceededException  daily/monthly/annual or velocity breach (nothing charged)
     * @throws LimitCheckUnavailableException     limits configured but not evaluable (fail closed)
     */
    public LimitCharge enforce(WalletPartnerRef partner, String txnRef,
                               @Nullable BigDecimal amount, @Nullable String currency) {
        PartnerConfigClient.TxnLimits limits = resolveLimits(partner);
        if (limits == null) {
            return LimitCharge.none(partner, txnRef);
        }
        BigDecimal amountUsd = UsdAmountBasis.toUsd(rateClient, amount, currency);
        if (amountUsd == null) {
            throw new LimitCheckUnavailableException(
                    "partner '" + partner.code() + "' has configured limits but no USD basis is available"
                            + " for " + amount + " " + currency + " — refusing rather than bypassing the cap");
        }
        return apply(partner, txnRef, amountUsd, limits);
    }

    /**
     * As {@link #enforce} but for a corridor that ALREADY holds the USD figure it is about to move
     * (SENDMN's {@code chargedUsd}). Preferred wherever available: zero conversion, so the cap basis
     * is bit-for-bit the money basis.
     */
    public LimitCharge enforceUsd(WalletPartnerRef partner, String txnRef, @Nullable BigDecimal amountUsd) {
        PartnerConfigClient.TxnLimits limits = resolveLimits(partner);
        if (limits == null) {
            return LimitCharge.none(partner, txnRef);
        }
        if (amountUsd == null) {
            throw new LimitCheckUnavailableException(
                    "partner '" + partner.code() + "' has configured limits but the payment carries no"
                            + " USD amount — refusing rather than bypassing the cap");
        }
        return apply(partner, txnRef, amountUsd, limits);
    }

    /** Per-txn rule, then the cumulative charge. Order matters: the pure check declines for free. */
    private LimitCharge apply(WalletPartnerRef partner, String txnRef, BigDecimal amountUsd,
                              PartnerConfigClient.TxnLimits limits) {
        // (1) Per-transaction min/max — the statutory per-txn ceiling among them. No side effect.
        TransactionLimitPolicy.enforcePerTransaction(partner.code(), amountUsd, limits);

        // (2) Cumulative amount caps + velocity count, charged race-free under prefunding's
        //     per-partner row lock. Only when a cap is actually configured.
        if (!hasCumulativeCap(limits)) {
            return LimitCharge.none(partner, txnRef);
        }
        if (prefundingClient == null) {
            throw new LimitCheckUnavailableException(
                    "partner '" + partner.code() + "' has a cumulative cap configured but the"
                            + " cumulative-usage ledger is not wired — refusing rather than bypassing the cap");
        }
        try {
            prefundingClient.chargeCumulative(partner.id(), txnRef, amountUsd,
                    limits.dailyCapUsd(), limits.monthlyCapUsd(), limits.annualCapUsd(),
                    limits.dailyTxnCountLimit());
        } catch (CumulativeLimitExceededException breach) {
            throw breach;
        } catch (RuntimeException ex) {
            // The cap exists but we could not evaluate it (prefunding down, no balance row, …).
            throw new LimitCheckUnavailableException(
                    "cumulative cap check failed for partner '" + partner.code() + "' txn " + txnRef
                            + " — refusing rather than bypassing the cap: " + ex.getMessage(), ex);
        }
        return LimitCharge.charged(partner, txnRef, amountUsd);
    }

    /**
     * Return the cap consumed by {@code charge} because the payment did not complete (scheme decline,
     * definitive reject, insufficient float). Best-effort + idempotent, and a no-op when nothing was
     * charged. Mirrors the {@code reverseCumulative} compensation the authorize/void path performs.
     */
    public void reverse(@Nullable LimitCharge charge) {
        if (charge == null || !charge.cumulativeCharged() || prefundingClient == null) {
            return;
        }
        try {
            prefundingClient.reverseCumulative(charge.partnerId(), charge.txnRef());
        } catch (RuntimeException ex) {
            // Fail-safe direction: an un-reversed charge leaves cap consumed (over-restrictive),
            // never over-permissive. Never mask the decline that triggered this.
            log.warn("cumulative reverse failed for partner {} txn {}: {}",
                    charge.partnerId(), charge.txnRef(), ex.getMessage());
        }
    }

    /**
     * The partner's limits, or {@code null} when none apply. Fail-soft exactly like the authorize
     * path: an unknown partner or a config-registry blip yields {@code null} (unconstrained), which is
     * logged so an unconstrained wallet payment is at least visible.
     */
    @Nullable
    private PartnerConfigClient.TxnLimits resolveLimits(WalletPartnerRef partner) {
        if (partnerConfigClient == null) {
            return null;
        }
        if (partner == null || !partner.isKnown()) {
            log.warn("wallet payment has no partner code — regulatory limits cannot be resolved,"
                    + " proceeding unconstrained");
            return null;
        }
        return partnerConfigClient.resolveLimits(partner.code()).orElse(null);
    }

    /** True when any cumulative amount cap OR the daily transaction-count velocity cap is configured. */
    private static boolean hasCumulativeCap(PartnerConfigClient.TxnLimits limits) {
        return limits != null
                && (limits.dailyCapUsd() != null || limits.monthlyCapUsd() != null
                || limits.annualCapUsd() != null || limits.dailyTxnCountLimit() != null);
    }

    /**
     * Handle for a cumulative charge the gate placed (or did not). Carries the partner + reference so
     * the caller can hand it straight back to {@link #reverse} on a decline.
     */
    public record LimitCharge(long partnerId, String txnRef, boolean cumulativeCharged,
                              @Nullable BigDecimal chargedUsd) {

        static LimitCharge none(WalletPartnerRef partner, String txnRef) {
            return new LimitCharge(partner == null ? 0L : partner.id(), txnRef, false, null);
        }

        static LimitCharge charged(WalletPartnerRef partner, String txnRef, BigDecimal amountUsd) {
            return new LimitCharge(partner.id(), txnRef, true, amountUsd);
        }
    }
}
