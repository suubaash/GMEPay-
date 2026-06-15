package com.gme.pay.settlement.recon;

import com.gme.pay.settlement.model.TransactionRecord;
import com.gme.pay.settlement.parser.ZeroPayResultRecord;
import com.gme.pay.settlement.persistence.ReconExceptionEntity;
import com.gme.pay.settlement.persistence.ReconExceptionRepository;
import com.gme.pay.settlement.port.TransactionQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

    public ReconDiffEngine(
            TransactionQueryPort transactionQueryPort,
            LineMatcher lineMatcher,
            ReconExceptionRepository reconExceptionRepository) {
        this.transactionQueryPort = transactionQueryPort;
        this.lineMatcher = lineMatcher;
        this.reconExceptionRepository = reconExceptionRepository;
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
        Instant now = Instant.now();
        long exceptionCount = 0;
        for (ReconLine line : allLines) {
            if (line.requiresAttention()) {
                ReconExceptionEntity entity = ReconExceptionEntity.fromReconLine(batchId, line, now);
                reconExceptionRepository.save(entity);
                exceptionCount++;
            }
        }

        log.info("ReconDiff batchId={} date={} totalLines={} exceptions={}",
                batchId, settlementDate, allLines.size(), exceptionCount);

        return allLines;
    }
}
