package com.gme.pay.settlement.corridor;

import com.gme.pay.settlement.alert.ReconBreakAlerter;
import com.gme.pay.settlement.persistence.CorridorReconSummaryEntity;
import com.gme.pay.settlement.persistence.CorridorReconSummaryRepository;
import com.gme.pay.settlement.persistence.ReconExceptionEntity;
import com.gme.pay.settlement.persistence.ReconExceptionRepository;
import com.gme.pay.settlement.port.PrefundingMovementPort;
import com.gme.pay.settlement.port.SchemeReconFeedParser;
import com.gme.pay.settlement.port.SchemeSettlementPort;
import com.gme.pay.settlement.port.SchemeTransactionPort;
import com.gme.pay.settlement.recon.MatchStatus;
import com.gme.pay.settlement.recon.ReconLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-border <b>three-way</b> reconciliation for one corridor and one settlement date (GAP T2-2).
 *
 * <p>The gap it closes: the scheme settles in USD against the rate IT registered with us, while the
 * hub deducted USD from the partner float on a completely different basis (live USD/KRW, or a
 * hardcoded fallback). Nobody compared those two numbers, so a systematic rate-basis loss could
 * accrue invisibly. Both sides are GME-owned, so this needs <b>no partner file</b> — and none is
 * assumed (SendMN's recon file format is external gate O4; see {@link SchemeReconFeedParser}).
 *
 * <p>The three legs, joined on the hub partner reference:
 * <ol>
 *   <li>{@link SchemeTransactionPort} — our transaction records (KRW charged, local paid, USD deducted);</li>
 *   <li>{@link PrefundingMovementPort} — our prefunding USD movements;</li>
 *   <li>{@link SchemeSettlementPort} — the adapter's record of what the scheme confirmed, with the
 *       registered rate and the USD owed.</li>
 * </ol>
 *
 * <p>Classification per transaction, in precedence order (a missing leg is never also reported as a
 * variance — there is nothing to subtract):
 * <ul>
 *   <li>{@link MatchStatus#MISSING_INTERNAL} — a scheme confirmation and/or float movement with no
 *       transaction of ours;</li>
 *   <li>{@link MatchStatus#MISSING_SCHEME} — our transaction, no scheme confirmation;</li>
 *   <li>{@link MatchStatus#MISSING_PREFUNDING} — our transaction and the scheme's confirmation, but
 *       no float movement;</li>
 *   <li>{@link MatchStatus#DISCREPANCY} — like-for-like amounts disagree (txn USD vs prefunding USD,
 *       or txn local payout vs the local amount the scheme confirmed);</li>
 *   <li>{@link MatchStatus#RATE_BASIS_VARIANCE} — everything agrees, but the USD collected is short
 *       of the USD owed by more than {@link CorridorSpec#rateBasisToleranceUsd()};</li>
 *   <li>{@link MatchStatus#MATCHED} — otherwise.</li>
 * </ul>
 *
 * <p>Reuses the ZeroPay lane's seams rather than forking a parallel subsystem: breaks land in
 * {@code recon_exceptions} through {@link ReconExceptionRepository} (same ops resolve/re-run
 * workflow) and alert through {@link ReconBreakAlerter} as {@code RECON_BREAK}. Idempotent on the
 * run's {@code batchId}: prior rows for the date are deleted before re-insert and the day's summary
 * row is overwritten, so re-running a date never duplicates anything.
 */
public class CorridorThreeWayReconciler {

    private static final Logger log = LoggerFactory.getLogger(CorridorThreeWayReconciler.class);

    /** Scale for USD money in this engine (matches prefunding's 8-dp USD ledger). */
    private static final int USD_SCALE = 8;

    /** Scale for the back-derived KRW-per-USD basis. */
    private static final int RATE_SCALE = 4;

    private final CorridorSpec spec;
    private final SchemeTransactionPort transactionPort;
    private final PrefundingMovementPort prefundingPort;
    private final SchemeSettlementPort schemePort;
    private final SchemeReconFeedParser feedParser;
    private final ReconExceptionRepository exceptionRepository;
    private final CorridorReconSummaryRepository summaryRepository;
    private final ReconBreakAlerter breakAlerter;

    public CorridorThreeWayReconciler(
            CorridorSpec spec,
            SchemeTransactionPort transactionPort,
            PrefundingMovementPort prefundingPort,
            SchemeSettlementPort schemePort,
            SchemeReconFeedParser feedParser,
            ReconExceptionRepository exceptionRepository,
            CorridorReconSummaryRepository summaryRepository,
            ReconBreakAlerter breakAlerter) {
        this.spec = spec;
        this.transactionPort = transactionPort;
        this.prefundingPort = prefundingPort;
        this.schemePort = schemePort;
        this.feedParser = feedParser;
        this.exceptionRepository = exceptionRepository;
        this.summaryRepository = summaryRepository;
        this.breakAlerter = breakAlerter;
    }

    /** The corridor this reconciler speaks for. */
    public CorridorSpec spec() {
        return spec;
    }

    /** Upper-case scheme code, for selecting among several reconcilers. */
    public String scheme() {
        return spec.scheme();
    }

    /**
     * Run the tie-out for {@code settlementDate}: classify every transaction, persist the breaks to
     * the ops exception queue, alert if any remain open, and write the day's finance summary
     * (including the cumulative signed rate-basis variance).
     *
     * <p>Safe to re-run for the same date — see the class note on idempotency.
     *
     * @return the full result including MATCHED lines, for the caller's audit log / API response
     */
    @Transactional
    public CorridorReconResult reconcile(LocalDate settlementDate) {
        String batchId = spec.batchId(settlementDate);

        List<SchemeTransactionRecord> txns =
                transactionPort.findApprovedByScheme(spec.schemeId(), settlementDate);
        List<PrefundingMovement> movements =
                prefundingPort.deductionsOn(spec.partnerCode(), settlementDate);
        List<SchemeSettlementRecord> schemeRecords = schemePort.confirmedOn(settlementDate);

        Map<String, SchemeTransactionRecord> txnByRef = new LinkedHashMap<>();
        for (SchemeTransactionRecord t : txns) {
            if (t.joinKey() != null) {
                txnByRef.put(t.joinKey(), t);
            }
        }
        // Multiple movements / scheme rows for one reference are SUMMED, never collapsed: a duplicate
        // deduct or a double-confirmed payment must show up as an amount discrepancy, not be hidden.
        Map<String, BigDecimal> prefundingByRef = new LinkedHashMap<>();
        for (PrefundingMovement m : movements) {
            if (m.reference() == null || m.amountUsd() == null) {
                continue;
            }
            prefundingByRef.merge(m.reference(), m.amountUsd(), BigDecimal::add);
        }
        Map<String, List<SchemeSettlementRecord>> schemeByRef = new LinkedHashMap<>();
        for (SchemeSettlementRecord s : schemeRecords) {
            if (s.reference() == null) {
                continue;
            }
            schemeByRef.computeIfAbsent(s.reference(), k -> new ArrayList<>()).add(s);
        }

        Set<String> references = new LinkedHashSet<>();
        references.addAll(txnByRef.keySet());
        references.addAll(prefundingByRef.keySet());
        references.addAll(schemeByRef.keySet());

        List<ThreeWayLine> lines = new ArrayList<>(references.size());
        for (String reference : references) {
            lines.add(classify(reference, txnByRef.get(reference),
                    prefundingByRef.get(reference), schemeByRef.get(reference)));
        }

        long persisted = persistExceptions(batchId, lines);
        // Reuse the ops break alert; the corridor settles in USD, so the amounts render as dollars.
        breakAlerter.alertOnBreak(batchId, lines.stream().map(ThreeWayLine::toReconLine).toList(), "$");

        CorridorReconSummaryEntity summary = upsertSummary(batchId, settlementDate, lines);

        log.info("Corridor 3-way recon scheme={} date={} txns={} prefundingRefs={} schemeRefs={} "
                        + "lines={} breaks={} variance={} cumulative={} feedAvailable={}",
                spec.scheme(), settlementDate, txns.size(), prefundingByRef.size(), schemeByRef.size(),
                lines.size(), persisted,
                summary.getRateBasisVarianceUsd().toPlainString(),
                summary.getCumulativeVarianceUsd().toPlainString(),
                summary.isSchemeFeedAvailable());

        return new CorridorReconResult(batchId, settlementDate, spec.scheme(), lines, summary);
    }

    // ---------------------------------------------------------------------------------
    // Classification
    // ---------------------------------------------------------------------------------

    private ThreeWayLine classify(String reference,
                                  SchemeTransactionRecord txn,
                                  BigDecimal prefundingUsd,
                                  List<SchemeSettlementRecord> schemeRows) {

        BigDecimal localConfirmed = sum(schemeRows, SchemeSettlementRecord::localAmount);
        BigDecimal usdOwed = sum(schemeRows, SchemeSettlementRecord::settlementUsd);
        BigDecimal registeredRate = schemeRows == null || schemeRows.isEmpty()
                ? null : schemeRows.get(0).registeredRate();
        String schemeMerchant = schemeRows == null || schemeRows.isEmpty()
                ? null : schemeRows.get(0).merchantId();

        // Leg (a) absent: the float moved and/or the scheme confirmed, but we have no transaction.
        if (txn == null) {
            String note = schemeRows != null && !schemeRows.isEmpty()
                    ? "scheme confirmed a payment we have no APPROVED transaction for"
                    : "prefunding USD moved for a reference we have no APPROVED transaction for";
            return new ThreeWayLine(reference, null, schemeMerchant, null, spec.localCcy(),
                    null, localConfirmed, null, prefundingUsd, usdOwed, registeredRate,
                    null, false, null, MatchStatus.MISSING_INTERNAL, note);
        }

        BigDecimal chargedKrw = chargedKrw(txn);
        BigDecimal usdDeducted = txn.prefundingDeductedUsd();
        BigDecimal impliedKrwPerUsd = impliedKrwPerUsd(chargedKrw, usdDeducted);
        boolean fallbackBasis = isFallbackBasis(impliedKrwPerUsd);

        // Leg (c) absent: we recorded an APPROVED payment the scheme never confirmed to us.
        if (schemeRows == null || schemeRows.isEmpty()) {
            return new ThreeWayLine(reference, txn.txnRef(), txn.merchantId(), chargedKrw,
                    spec.localCcy(), txn.targetPayout(), null, usdDeducted, prefundingUsd, null,
                    null, impliedKrwPerUsd, fallbackBasis, null, MatchStatus.MISSING_SCHEME,
                    "APPROVED transaction with no " + spec.scheme() + " confirmation on this date");
        }

        // Leg (b) absent: the payment happened but the partner float was never charged for it.
        if (prefundingUsd == null) {
            return new ThreeWayLine(reference, txn.txnRef(), txn.merchantId(), chargedKrw,
                    spec.localCcy(), txn.targetPayout(), localConfirmed, usdDeducted, null, usdOwed,
                    registeredRate, impliedKrwPerUsd, fallbackBasis, null,
                    MatchStatus.MISSING_PREFUNDING,
                    "scheme confirmed the payment but no prefunding USD movement was found");
        }

        // Like-for-like amount checks before any rate-basis judgement.
        if (usdDeducted == null) {
            return new ThreeWayLine(reference, txn.txnRef(), txn.merchantId(), chargedKrw,
                    spec.localCcy(), txn.targetPayout(), localConfirmed, null, prefundingUsd, usdOwed,
                    registeredRate, null, false, null, MatchStatus.DISCREPANCY,
                    "transaction carries no prefundingDeductedUsd while prefunding moved $"
                            + prefundingUsd.toPlainString());
        }
        if (usdDeducted.compareTo(prefundingUsd) != 0) {
            return new ThreeWayLine(reference, txn.txnRef(), txn.merchantId(), chargedKrw,
                    spec.localCcy(), txn.targetPayout(), localConfirmed, usdDeducted, prefundingUsd,
                    usdOwed, registeredRate, impliedKrwPerUsd, fallbackBasis, null,
                    MatchStatus.DISCREPANCY,
                    "USD on the transaction ($" + usdDeducted.toPlainString()
                            + ") differs from the prefunding movement ($" + prefundingUsd.toPlainString() + ")");
        }
        if (txn.targetPayout() != null && localConfirmed != null
                && txn.targetPayout().compareTo(localConfirmed) != 0) {
            return new ThreeWayLine(reference, txn.txnRef(), txn.merchantId(), chargedKrw,
                    spec.localCcy(), txn.targetPayout(), localConfirmed, usdDeducted, prefundingUsd,
                    usdOwed, registeredRate, impliedKrwPerUsd, fallbackBasis, null,
                    MatchStatus.DISCREPANCY,
                    spec.localCcy() + " paid per our transaction (" + txn.targetPayout().toPlainString()
                            + ") differs from what the scheme confirmed (" + localConfirmed.toPlainString() + ")");
        }

        // All three legs agree on the like-for-like figures. The remaining question is the one nobody
        // was asking: do the two USD BASES agree?
        BigDecimal variance = usdOwed == null ? null
                : usdDeducted.subtract(usdOwed).setScale(USD_SCALE, RoundingMode.HALF_UP);

        MatchStatus status = MatchStatus.MATCHED;
        String note = null;
        if (variance != null && variance.compareTo(spec.rateBasisToleranceUsd().negate()) < 0) {
            status = MatchStatus.RATE_BASIS_VARIANCE;
            note = "USD collected from the float ($" + usdDeducted.toPlainString()
                    + ") is short of the USD owed " + spec.scheme() + " at its registered rate "
                    + (registeredRate != null ? registeredRate.toPlainString() : "?")
                    + " ($" + usdOwed.toPlainString() + ") by $" + variance.abs().toPlainString()
                    + (fallbackBasis ? " — deduction was priced off the hardcoded USD/KRW fallback" : "");
        } else if (fallbackBasis) {
            note = "deduction was priced off the hardcoded USD/KRW fallback ("
                    + spec.fallbackKrwPerUsd().toPlainString() + "), not a live rate";
        }

        return new ThreeWayLine(reference, txn.txnRef(), txn.merchantId(), chargedKrw, spec.localCcy(),
                txn.targetPayout(), localConfirmed, usdDeducted, prefundingUsd, usdOwed, registeredRate,
                impliedKrwPerUsd, fallbackBasis, variance, status, note);
    }

    /**
     * KRW the wallet was actually charged: transaction-mgmt stores the wallet amount, the hub adds the
     * fixed service fee on top before converting to USD. Null when the send leg is not KRW (defensive —
     * that would be a different corridor).
     */
    private BigDecimal chargedKrw(SchemeTransactionRecord txn) {
        if (txn.sendAmount() == null || txn.sendCcy() == null || !"KRW".equalsIgnoreCase(txn.sendCcy())) {
            return null;
        }
        return txn.sendAmount().add(spec.serviceFeeKrw());
    }

    /**
     * Back-derive the KRW/USD basis the hub priced the deduction at: {@code chargedKrw / usdDeducted}.
     * This is how a stale or fallback rate becomes provable after the fact — the rate itself is not
     * persisted on the transaction.
     */
    private static BigDecimal impliedKrwPerUsd(BigDecimal chargedKrw, BigDecimal usdDeducted) {
        if (chargedKrw == null || usdDeducted == null || usdDeducted.signum() == 0) {
            return null;
        }
        return chargedKrw.divide(usdDeducted, RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** True when the implied basis sits within tolerance of the hub's hardcoded fallback constant. */
    private boolean isFallbackBasis(BigDecimal impliedKrwPerUsd) {
        if (impliedKrwPerUsd == null || spec.fallbackKrwPerUsd() == null) {
            return false;
        }
        return impliedKrwPerUsd.subtract(spec.fallbackKrwPerUsd()).abs()
                .compareTo(spec.fallbackMatchToleranceKrw()) <= 0;
    }

    private static BigDecimal sum(List<SchemeSettlementRecord> rows,
                                  java.util.function.Function<SchemeSettlementRecord, BigDecimal> field) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        boolean any = false;
        for (SchemeSettlementRecord r : rows) {
            BigDecimal v = field.apply(r);
            if (v != null) {
                total = total.add(v);
                any = true;
            }
        }
        return any ? total : null;
    }

    // ---------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------

    /**
     * Idempotently persist the open lines into the SAME {@code recon_exceptions} queue ops already
     * works, stamped with the scheme + transaction (V010). Prior rows for the run are deleted first,
     * so re-running a date replaces rather than duplicates.
     */
    private long persistExceptions(String batchId, List<ThreeWayLine> lines) {
        exceptionRepository.deleteByBatchId(batchId);
        Instant now = Instant.now();
        long count = 0;
        for (ThreeWayLine line : lines) {
            if (!line.requiresAttention()) {
                continue;
            }
            ReconLine reconLine = line.toReconLine();
            ReconExceptionEntity entity = ReconExceptionEntity.fromCorridorLine(
                    batchId, spec.scheme(), line.txnRef() != null ? line.txnRef() : line.reference(),
                    reconLine, now);
            entity.setResolutionNote(line.note());
            exceptionRepository.save(entity);
            count++;
        }
        return count;
    }

    /**
     * Write (or overwrite) the day's finance summary, then recompute the SIGNED cumulative
     * rate-basis variance for this scheme from its earliest summarised day forward — so a re-run or a
     * back-dated run leaves every later day's cumulative correct rather than stale.
     */
    private CorridorReconSummaryEntity upsertSummary(String batchId, LocalDate date,
                                                     List<ThreeWayLine> lines) {
        CorridorReconSummaryEntity e = summaryRepository
                .findBySettlementDateAndScheme(date, spec.scheme())
                .orElseGet(CorridorReconSummaryEntity::new);

        BigDecimal chargedKrw = BigDecimal.ZERO;
        BigDecimal localPaid = BigDecimal.ZERO;
        BigDecimal usdDeducted = BigDecimal.ZERO;
        BigDecimal usdOwed = BigDecimal.ZERO;
        BigDecimal variance = BigDecimal.ZERO;
        BigDecimal breakValue = BigDecimal.ZERO;
        int fallbackCount = 0;
        int breakCount = 0;

        for (ThreeWayLine line : lines) {
            chargedKrw = chargedKrw.add(nz(line.chargedKrw()));
            localPaid = localPaid.add(nz(line.localPaid()));
            usdDeducted = usdDeducted.add(nz(line.usdDeducted()));
            usdOwed = usdOwed.add(nz(line.usdOwedScheme()));
            // The variance is summed wherever it is computable — including on a line that broke for
            // another reason — so the day's signed drift is complete, not filtered by break type.
            variance = variance.add(nz(line.rateBasisVarianceUsd()));
            if (line.fallbackRateBasis()) {
                fallbackCount++;
            }
            if (line.requiresAttention()) {
                breakCount++;
                breakValue = breakValue.add(line.discrepancyUsd());
            }
        }

        e.setSettlementDate(date);
        e.setScheme(spec.scheme());
        e.setCorridor(spec.corridor());
        e.setBatchId(batchId);
        e.setTxnCount(lines.size());
        e.setChargedKrw(chargedKrw);
        e.setLocalPaid(localPaid);
        e.setLocalCurrency(spec.localCcy());
        e.setUsdDeducted(usdDeducted);
        e.setUsdOwedScheme(usdOwed);
        e.setRateBasisVarianceUsd(variance);
        e.setFallbackRateBasisCount(fallbackCount);
        e.setBreakCount(breakCount);
        e.setBreakValueUsd(breakValue);
        // FALSE until a real partner settlement file is parsed — SendMN's format is external gate O4.
        e.setSchemeFeedAvailable(feedParser != null && feedParser.available());
        e.setGeneratedAt(Instant.now());
        e.setCumulativeVarianceUsd(variance);   // provisional; fixed by the recompute below
        summaryRepository.save(e);

        recomputeCumulative();
        return e;
    }

    /** Walk the scheme's summaries oldest-first, restating each day's signed running total. */
    private void recomputeCumulative() {
        BigDecimal running = BigDecimal.ZERO;
        for (CorridorReconSummaryEntity row :
                summaryRepository.findBySchemeOrderBySettlementDateAsc(spec.scheme())) {
            running = running.add(nz(row.getRateBasisVarianceUsd()));
            if (row.getCumulativeVarianceUsd() == null
                    || row.getCumulativeVarianceUsd().compareTo(running) != 0) {
                row.setCumulativeVarianceUsd(running);
                summaryRepository.save(row);
            }
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
