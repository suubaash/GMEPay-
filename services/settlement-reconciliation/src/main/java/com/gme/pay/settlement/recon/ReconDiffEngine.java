package com.gme.pay.settlement.recon;

import com.gme.pay.settlement.batch.SettlementBatchStatus;
import com.gme.pay.settlement.model.TransactionRecord;
import com.gme.pay.settlement.parser.ZeroPayResultRecord;
import com.gme.pay.settlement.persistence.ReconExceptionEntity;
import com.gme.pay.settlement.persistence.ReconExceptionRepository;
import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.persistence.SettlementBatchRepository;
import com.gme.pay.settlement.persistence.SettlementLineEntity;
import com.gme.pay.settlement.persistence.SettlementLineRepository;
import com.gme.pay.settlement.port.TransactionQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reconciliation diff engine.
 *
 * <p>Workflow:
 * <ol>
 *   <li>Aggregate internal GME settlement amounts per merchant from {@link TransactionQueryPort}</li>
 *   <li>Build scheme-side map from parsed ZeroPay result file records</li>
 *   <li>Run {@link LineMatcher} to produce MATCHED / DISCREPANCY / MISSING_* lines</li>
 *   <li>Persist DISCREPANCY and MISSING lines as {@link ReconExceptionEntity} rows</li>
 * </ol>
 *
 * <p>The batchId is the caller's stable identifier for the settlement window (e.g.
 * {@code "ZP0062-20260615-001"}). The caller is responsible for idempotency checks
 * before invoking this method.
 */
@Component
public class ReconDiffEngine {

    private static final Logger log = LoggerFactory.getLogger(ReconDiffEngine.class);

    private final TransactionQueryPort transactionQueryPort;
    private final LineMatcher lineMatcher;
    private final ReconExceptionRepository reconExceptionRepository;
    private final SettlementBatchRepository batchRepository;
    private final SettlementLineRepository lineRepository;

    public ReconDiffEngine(
            TransactionQueryPort transactionQueryPort,
            LineMatcher lineMatcher,
            ReconExceptionRepository reconExceptionRepository,
            SettlementBatchRepository batchRepository,
            SettlementLineRepository lineRepository) {
        this.transactionQueryPort = transactionQueryPort;
        this.lineMatcher = lineMatcher;
        this.reconExceptionRepository = reconExceptionRepository;
        this.batchRepository = batchRepository;
        this.lineRepository = lineRepository;
    }

    /**
     * Run the diff for a given settlement date and set of ZeroPay parsed records.
     *
     * @param batchId          unique identifier for this recon run (stored on each exception row)
     * @param settlementDate   the business date being reconciled
     * @param schemeRecords    parsed ZeroPay result-file records (DATA lines only are used)
     * @return the full list of {@link ReconLine} including MATCHED lines (for the caller's log)
     */
    @Transactional
    public List<ReconLine> runDiff(
            String batchId,
            LocalDate settlementDate,
            List<ZeroPayResultRecord> schemeRecords) {

        // 1. Build GME side: sum netSettlementAmount per merchant from approved unbatched txns
        List<TransactionRecord> txns = transactionQueryPort.findUnbatchedApproved(settlementDate);

        Map<String, BigDecimal> gmeMap = txns.stream()
                .filter(t -> t.merchantId() != null)
                .collect(Collectors.groupingBy(
                        TransactionRecord::merchantId,
                        Collectors.reducing(BigDecimal.ZERO,
                                TransactionRecord::targetPayoutKrw,
                                BigDecimal::add)));

        // 2. Build scheme side: sum amounts per merchantId from DATA lines
        Map<String, BigDecimal> schemeMap = schemeRecords.stream()
                .filter(ZeroPayResultRecord::isSettlementData)
                .collect(Collectors.groupingBy(
                        r -> r.merchantId() != null ? r.merchantId() : "",
                        Collectors.reducing(BigDecimal.ZERO,
                                r -> r.amount() != null ? r.amount() : BigDecimal.ZERO,
                                BigDecimal::add)));

        // 3. Diff
        List<ReconLine> allLines = lineMatcher.match(gmeMap, schemeMap);

        // 4. Persist exceptions (DISCREPANCY + MISSING)
        long exceptionCount = persistExceptions(batchId, allLines);

        log.info("ReconDiff batchId={} date={} totalLines={} exceptions={}",
                batchId, settlementDate, allLines.size(), exceptionCount);

        return allLines;
    }

    /**
     * Reconcile a <b>persisted outbound settlement batch</b> against the scheme's confirmation file.
     *
     * <p>This is the authoritative recon path: the GME side is the net amount GMEPay+ actually
     * <em>booked and sent</em> for each merchant — re-summed from the batch's {@code settlement_lines}
     * (Σ signed amount per merchant = payments − clawed-back refunds = the net we requested in
     * ZP0061/ZP0063) — <b>not</b> a re-fetch of live transaction gross. ZeroPay's ZP0062/ZP0064
     * confirms that net figure, so a correctly-booked NET batch ties out exactly (no false discrepancy
     * from comparing gross against net).
     *
     * <p>The run is idempotent on {@code batch.batchId}: prior exception rows for the batch are deleted
     * before re-inserting, so re-processing the same result file never duplicates rows
     * (backlog 7.1-T17 / 7.1-T18 acceptance). On completion the batch lifecycle advances:
     * all-MATCHED → {@code RECONCILED} (with the matched lines flagged), any mismatch → {@code RECEIVED}
     * (received but holding open exceptions for ops). The status only ever moves forward through legal
     * transitions; a batch already at/after RECONCILED is left untouched.
     *
     * @param batch          the persisted ZP0061/ZP0063 batch whose lines are the GME-side truth
     * @param schemeRecords  parsed ZP0062/ZP0064 result-file records (DATA lines used)
     * @return the full list of {@link ReconLine} (including MATCHED) for the caller's audit log
     */
    @Transactional
    public List<ReconLine> runDiffForBatch(SettlementBatchEntity batch, List<ZeroPayResultRecord> schemeRecords) {
        if (batch == null) {
            throw new BatchNotFoundException("recon requested for a null settlement batch");
        }
        String batchId = batch.getBatchId();

        // 1. GME side: net booked amount per merchant, re-summed from the persisted settlement lines.
        //    Σ signed amount per merchant (payments positive, claw-back refunds negative) = the net
        //    GMEPay+ requested ZeroPay credit for that merchant.
        List<SettlementLineEntity> lines = lineRepository.findByBatchId(batchId);
        Map<String, BigDecimal> gmeMap = lines.stream()
                .filter(l -> l.getMerchantId() != null)
                .collect(Collectors.groupingBy(
                        SettlementLineEntity::getMerchantId, LinkedHashMap::new,
                        Collectors.reducing(BigDecimal.ZERO,
                                l -> l.getAmount() != null ? l.getAmount() : BigDecimal.ZERO,
                                BigDecimal::add)));

        // 2. Scheme side: net credited amount per merchant from the confirmation file's DATA lines.
        Map<String, BigDecimal> schemeMap = schemeRecords.stream()
                .filter(ZeroPayResultRecord::isSettlementData)
                .collect(Collectors.groupingBy(
                        r -> r.merchantId() != null ? r.merchantId() : "",
                        LinkedHashMap::new,
                        Collectors.reducing(BigDecimal.ZERO,
                                r -> r.amount() != null ? r.amount() : BigDecimal.ZERO,
                                BigDecimal::add)));

        // 3. Diff.
        List<ReconLine> allLines = lineMatcher.match(gmeMap, schemeMap);

        // 4. Idempotent persist: clear prior exceptions for this batch, then re-insert the open ones.
        long exceptionCount = persistExceptions(batchId, allLines);

        // 5. Flag the matched lines and advance the batch lifecycle.
        markMatchedLines(lines, allLines);
        advanceBatchStatus(batch, exceptionCount);

        log.info("ReconDiffForBatch batchId={} merchants={} matched={} exceptions={} -> status={}",
                batchId, allLines.size(), allLines.size() - exceptionCount, exceptionCount, batch.getStatus());

        return allLines;
    }

    /**
     * Idempotently persist the open (non-MATCHED) recon lines for a batch: delete any prior rows for
     * the batch, then insert one row per line requiring attention. Returns the number persisted.
     */
    private long persistExceptions(String batchId, List<ReconLine> allLines) {
        reconExceptionRepository.deleteByBatchId(batchId);
        Instant now = Instant.now();
        long count = 0;
        for (ReconLine line : allLines) {
            if (line.requiresAttention()) {
                reconExceptionRepository.save(ReconExceptionEntity.fromReconLine(batchId, line, now));
                count++;
            }
        }
        return count;
    }

    /** Set {@code matched=true} on each settlement line whose merchant reconciled cleanly (MATCHED). */
    private void markMatchedLines(List<SettlementLineEntity> lines, List<ReconLine> reconLines) {
        Map<String, Boolean> matchedByMerchant = reconLines.stream()
                .collect(Collectors.toMap(ReconLine::merchantId,
                        l -> l.matchStatus() == MatchStatus.MATCHED, (a, b) -> a && b));
        for (SettlementLineEntity line : lines) {
            if (Boolean.TRUE.equals(matchedByMerchant.get(line.getMerchantId())) && !line.isMatched()) {
                line.setMatched(true);
                lineRepository.save(line);
            }
        }
    }

    /**
     * Move the batch forward after a recon run: all-clean → RECONCILED, any open exception → RECEIVED
     * (the file was received but holds discrepancies for ops). Transition is via the legal-state machine
     * — a batch still at GENERATED is first taken to RECEIVED. A batch already at/after RECONCILED is a
     * no-op (recon already closed). The status is never moved backwards or through an illegal edge.
     */
    private void advanceBatchStatus(SettlementBatchEntity batch, long exceptionCount) {
        SettlementBatchStatus current;
        try {
            current = SettlementBatchStatus.valueOf(batch.getStatus());
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("batch {} has no/unknown status '{}' — leaving as-is", batch.getBatchId(), batch.getStatus());
            return;
        }
        if (current == SettlementBatchStatus.RECONCILED) {
            return;   // recon already closed; idempotent no-op
        }
        // Walk forward to RECEIVED if not there yet (GENERATED -> TRANSMITTED -> RECEIVED).
        moveForward(batch, SettlementBatchStatus.TRANSMITTED);
        moveForward(batch, SettlementBatchStatus.RECEIVED);
        if (exceptionCount == 0) {
            moveForward(batch, SettlementBatchStatus.RECONCILED);
        }
        batchRepository.save(batch);
    }

    /** Advance one legal step toward {@code to} if the current status permits it; else leave unchanged. */
    private static void moveForward(SettlementBatchEntity batch, SettlementBatchStatus to) {
        SettlementBatchStatus from = SettlementBatchStatus.valueOf(batch.getStatus());
        if (from == to) {
            return;
        }
        if (from.canMoveTo(to)) {
            batch.setStatus(to.name());
        }
    }
}
