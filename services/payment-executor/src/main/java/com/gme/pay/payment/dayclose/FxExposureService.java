package com.gme.pay.payment.dayclose;

import com.gme.pay.payment.domain.client.TransactionClient;
import com.gme.pay.payment.opsrun.LedgerOpsJob;
import com.gme.pay.payment.opsrun.LedgerOpsRunExecutor;
import com.gme.pay.payment.opsrun.LedgerOpsRunRecorder;
import com.gme.pay.payment.opsrun.LedgerOpsRunTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives the net open FX position per currency from transactions already recorded (gap <b>T2-5</b> / CFO#9).
 *
 * <p>See {@link FxExposureReport} for what this measurement is and — importantly — the four things it
 * deliberately is not (no hedging advice, no target position, no revaluation, no cross-currency netting).
 *
 * <h2>Where the numbers come from</h2>
 * <p>One source: {@code TransactionClient.findApprovedForDate}, i.e. transaction-mgmt's own APPROVED rows. Each
 * transaction contributes to exactly three things —
 * <ul>
 *   <li>{@code +collectionAmount} to its collection currency's position;</li>
 *   <li>{@code −payoutAmount} to its payout currency's position;</li>
 *   <li>{@code prefundDeductedUsd} to the settled-USD figure of BOTH currencies it touched, because that single
 *       USD movement is the leg that settled both sides of that transaction.</li>
 * </ul>
 * Nothing is inferred and nothing is filled in: a transaction with no recorded USD deduction is counted in
 * {@code missingBasisCount} rather than being assigned an assumed rate.
 *
 * <h2>The rate basis, and the fallback constant</h2>
 * <p>{@code impliedUsdBasis = collected / settledUsd} is the units-per-USD rate the settlement actually used,
 * back-derived rather than looked up — so it reports what happened, not what the rate provider would say now.
 * When that basis matches the hub's hardcoded USD fallback constant within tolerance, the transaction is counted
 * in {@code fallbackBasisSuspected}: it was probably priced off an unverified compile-time constant during a
 * rate-provider outage, which is the second half of CFO#9. The same back-derivation T2-2's corridor tie-out uses,
 * for the same reason. Detecting it is all this does — the constant itself still needs treasury sign-off, which
 * is not a code change and is not made here.
 */
@Service
public class FxExposureService {

    private static final Logger log = LoggerFactory.getLogger(FxExposureService.class);

    /** Scale for a back-derived units-per-USD basis. Matches T2-2's {@code RATE_SCALE}. */
    private static final int BASIS_SCALE = 4;

    private final TransactionClient transactions;
    private final LedgerOpsRunExecutor executor;
    private final Clock clock;
    private final int windowDays;
    private final String fallbackBasisCurrency;
    private final BigDecimal fallbackBasisRate;
    private final BigDecimal fallbackBasisTolerance;

    public FxExposureService(
            TransactionClient transactions,
            LedgerOpsRunExecutor executor,
            Clock clock,
            @Value("${gmepay.fx-exposure.window-days:7}") int windowDays,
            @Value("${gmepay.fx-exposure.fallback-basis-currency:KRW}") String fallbackBasisCurrency,
            @Value("${gmepay.fx-exposure.fallback-basis-rate:1350}") BigDecimal fallbackBasisRate,
            @Value("${gmepay.fx-exposure.fallback-basis-tolerance:1}") BigDecimal fallbackBasisTolerance) {
        this.transactions = transactions;
        this.executor = executor;
        this.clock = clock;
        this.windowDays = windowDays > 0 ? windowDays : 7;
        this.fallbackBasisCurrency = fallbackBasisCurrency;
        this.fallbackBasisRate = fallbackBasisRate;
        this.fallbackBasisTolerance = fallbackBasisTolerance == null
                ? BigDecimal.ONE : fallbackBasisTolerance.abs();
    }

    /** The configured rolling window, in days, the scheduled run measures. */
    public int windowDays() {
        return windowDays;
    }

    /**
     * Run the measurement wrapped in the durable run ledger + failure alerting.
     *
     * @param start   inclusive first date
     * @param end     inclusive last date
     * @param trigger scheduler or operator
     */
    public LedgerOpsRunExecutor.RunResult<FxExposureReport> run(LocalDate start, LocalDate end,
                                                              LedgerOpsRunTrigger trigger,
                                                              @Nullable String operatorId) {
        LedgerOpsRunRecorder.RunKey key = trigger == LedgerOpsRunTrigger.OPERATOR
                ? LedgerOpsRunRecorder.RunKey.operator(LedgerOpsJob.FX_EXPOSURE, end, operatorId)
                : LedgerOpsRunRecorder.RunKey.scheduled(LedgerOpsJob.FX_EXPOSURE, end);
        return executor.execute(key, () -> measure(start, end),
                r -> LedgerOpsRunExecutor.RunSummary.of(
                        "window=[" + r.startDate() + ".." + r.endDate() + "] available=" + r.available()
                                + " currencies=" + r.positions().size() + " txns=" + r.transactionCount(),
                        r.transactionCount()));
    }

    /**
     * Measure the position over {@code [start, end]} inclusive.
     *
     * <p>Does not throw for an unreadable transaction leg: it returns an UNAVAILABLE report with an empty
     * position list. "We could not look" and "there is no exposure" must never render identically, and a
     * measurement that silently reported zero would be worse than no measurement at all.
     */
    public FxExposureReport measure(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("start and end required");
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("start must be <= end, got start=" + start + " end=" + end);
        }
        Instant now = Instant.now(clock);

        List<TransactionClient.DailyTransaction> txns = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            try {
                txns.addAll(transactions.findApprovedForDate(date));
            } catch (RuntimeException e) {
                log.error("FX exposure: transaction leg unreadable for {} — the whole window is reported "
                        + "UNAVAILABLE rather than measured from a partial read: {}", date, e.toString());
                return FxExposureReport.unavailable(start, end, now,
                        "transaction-mgmt could not be read for " + date + ": " + e);
            }
        }

        Map<String, Accumulator> byCurrency = new LinkedHashMap<>();
        for (TransactionClient.DailyTransaction t : txns) {
            BigDecimal usd = t.prefundDeductedUsd();
            boolean hasUsd = usd != null && usd.signum() != 0;

            if (t.collectionCcy() != null) {
                Accumulator acc = byCurrency.computeIfAbsent(t.collectionCcy(), Accumulator::new);
                acc.collected = acc.collected.add(orZero(t.collectionAmount()));
                acc.refunded = acc.refunded.add(t.refunded());
                acc.collectedTxnCount++;
                if (hasUsd) {
                    acc.settledUsd = acc.settledUsd.add(usd);
                    acc.collectionSettledUsd = acc.collectionSettledUsd.add(usd);
                    acc.basisTxnCount++;
                    if (isFallbackBasis(t.collectionCcy(), orZero(t.collectionAmount()), usd)) {
                        acc.fallbackBasisSuspected++;
                    }
                } else {
                    // No USD figure => no derivable rate basis for this transaction. Counted, never assumed.
                    acc.missingBasisCount++;
                }
            }

            if (t.payoutCcy() != null) {
                Accumulator acc = byCurrency.computeIfAbsent(t.payoutCcy(), Accumulator::new);
                acc.paidOut = acc.paidOut.add(orZero(t.payoutAmount()));
                acc.paidOutTxnCount++;
                if (hasUsd && !t.payoutCcy().equals(t.collectionCcy())) {
                    // The same USD movement settled this leg too; add it once per transaction per currency.
                    acc.settledUsd = acc.settledUsd.add(usd);
                }
            }
        }

        List<FxExposureReport.CurrencyPosition> positions = new ArrayList<>(byCurrency.size());
        for (Accumulator acc : byCurrency.values()) {
            positions.add(acc.toPosition());
        }
        return new FxExposureReport(start, end, now, true, null, txns.size(), List.copyOf(positions));
    }

    /**
     * True when the back-derived units-per-USD basis for one collection matches the configured hardcoded
     * fallback constant within tolerance. Only ever true for the one configured currency — applying a
     * KRW-shaped constant to an MNT amount would manufacture findings.
     */
    private boolean isFallbackBasis(String currency, BigDecimal collected, BigDecimal usd) {
        if (fallbackBasisRate == null || !fallbackBasisCurrency.equalsIgnoreCase(currency)) {
            return false;
        }
        if (collected.signum() == 0 || usd.signum() == 0) {
            return false;
        }
        BigDecimal implied = collected.divide(usd, BASIS_SCALE, RoundingMode.HALF_UP);
        return implied.subtract(fallbackBasisRate).abs().compareTo(fallbackBasisTolerance) <= 0;
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Mutable per-currency accumulator; converted to the immutable report record at the end. */
    private static final class Accumulator {
        private final String currency;
        private BigDecimal collected = BigDecimal.ZERO;
        private BigDecimal refunded = BigDecimal.ZERO;
        private BigDecimal paidOut = BigDecimal.ZERO;
        private BigDecimal settledUsd = BigDecimal.ZERO;
        /** USD settled against COLLECTIONS only — the denominator of the implied basis. */
        private BigDecimal collectionSettledUsd = BigDecimal.ZERO;
        private int collectedTxnCount;
        private int paidOutTxnCount;
        private int basisTxnCount;
        private int missingBasisCount;
        private int fallbackBasisSuspected;

        private Accumulator(String currency) {
            this.currency = currency;
        }

        private FxExposureReport.CurrencyPosition toPosition() {
            BigDecimal net = collected.subtract(refunded).subtract(paidOut);
            BigDecimal basis = collectionSettledUsd.signum() == 0
                    // No USD settled against collections in this currency: there is no basis to derive, and a
                    // fabricated one would be indistinguishable from a measured one.
                    ? null
                    : collected.divide(collectionSettledUsd, BASIS_SCALE, RoundingMode.HALF_UP);
            return new FxExposureReport.CurrencyPosition(currency, collected, collectedTxnCount, refunded,
                    paidOut, paidOutTxnCount, net, settledUsd, basis, basisTxnCount, missingBasisCount,
                    fallbackBasisSuspected);
        }
    }
}
