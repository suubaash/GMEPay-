package com.gme.pay.settlement.rerun;

import com.gme.pay.settlement.parser.ZP0062Parser;
import com.gme.pay.settlement.parser.ZP0064Parser;
import com.gme.pay.settlement.parser.ZeroPayResultRecord;
import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.persistence.SettlementBatchRepository;
import com.gme.pay.settlement.recon.BatchNotFoundException;
import com.gme.pay.settlement.recon.MatchStatus;
import com.gme.pay.settlement.recon.ReconDiffEngine;
import com.gme.pay.settlement.recon.ReconLine;
import com.gme.pay.settlement.scheduler.ReconFileSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Operator recon re-run (UC ops). Re-runs reconciliation for a single batch ({@code batchId}) or an entire
 * business date ({@code settlementDate}), reusing the idempotent {@link ReconDiffEngine#runDiffForBatch}
 * (delete-then-reinsert on {@code batchId}) so a re-run never double-posts or duplicates exception lines.
 *
 * <p>The scheme confirmation file for each batch is re-read from the same inbox {@link ReconFileSource} the
 * scheduler uses, then re-diffed against the batch's persisted settlement lines. Any resulting break emits
 * the {@code RECON_BREAK} ops alert via the diff engine. Who/why is recorded in the audit log.
 */
@Service
public class ReconRerunService {

    private static final Logger log = LoggerFactory.getLogger(ReconRerunService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final SettlementBatchRepository batchRepository;
    private final ReconFileSource fileSource;
    private final ZP0062Parser zp0062Parser;
    private final ZP0064Parser zp0064Parser;
    private final ReconDiffEngine diffEngine;

    public ReconRerunService(SettlementBatchRepository batchRepository,
                             ReconFileSource fileSource,
                             ZP0062Parser zp0062Parser,
                             ZP0064Parser zp0064Parser,
                             ReconDiffEngine diffEngine) {
        this.batchRepository = batchRepository;
        this.fileSource = fileSource;
        this.zp0062Parser = zp0062Parser;
        this.zp0064Parser = zp0064Parser;
        this.diffEngine = diffEngine;
    }

    /**
     * Re-run reconciliation for the requested scope.
     *
     * @throws IllegalArgumentException if neither/both of batchId and settlementDate are supplied
     * @throws BatchNotFoundException   if no matching batch exists
     */
    public ReconRerunResponse rerun(ReconRerunRequest request) {
        boolean hasBatchId = request.batchId() != null && !request.batchId().isBlank();
        boolean hasDate = request.settlementDate() != null;
        if (hasBatchId == hasDate) {
            throw new IllegalArgumentException("exactly one of {batchId, settlementDate} must be supplied");
        }

        List<SettlementBatchEntity> batches = resolveBatches(request);
        if (batches.isEmpty()) {
            throw new BatchNotFoundException("no settlement batch found for recon re-run: "
                    + (hasBatchId ? "batchId=" + request.batchId() : "settlementDate=" + request.settlementDate()));
        }

        log.info("operator recon re-run requested: operator={} scope={} reason={} batches={}",
                request.operatorId(),
                hasBatchId ? "batchId=" + request.batchId() : "settlementDate=" + request.settlementDate(),
                request.reason(), batches.size());

        List<ReconRerunResponse.BatchReconSummary> summaries = new ArrayList<>();
        int totalMatched = 0;
        int totalExceptions = 0;

        for (SettlementBatchEntity batch : batches) {
            List<ZeroPayResultRecord> records = readSchemeRecords(batch);
            List<ReconLine> lines = diffEngine.runDiffForBatch(batch, records);
            long exceptions = lines.stream().filter(ReconLine::requiresAttention).count();
            long matched = lines.stream().filter(l -> l.matchStatus() == MatchStatus.MATCHED).count();
            totalMatched += (int) matched;
            totalExceptions += (int) exceptions;
            summaries.add(new ReconRerunResponse.BatchReconSummary(
                    batch.getBatchId(), batch.getStatus(), (int) matched, (int) exceptions));
            log.info("operator recon re-run: batchId={} operator={} matched={} exceptions={} status={}",
                    batch.getBatchId(), request.operatorId(), matched, exceptions, batch.getStatus());
        }

        return new ReconRerunResponse(
                request.operatorId(), summaries.size(), totalMatched, totalExceptions, summaries);
    }

    private List<SettlementBatchEntity> resolveBatches(ReconRerunRequest request) {
        if (request.batchId() != null && !request.batchId().isBlank()) {
            return batchRepository.findById(request.batchId()).map(List::of).orElseGet(List::of);
        }
        // settlementDate: every REQUEST batch (ZP0061/ZP0063) generated for that business date.
        return batchRepository.findByBusinessDate(request.settlementDate()).stream()
                .filter(b -> "ZP0061".equals(b.getFileType()) || "ZP0063".equals(b.getFileType()))
                .toList();
    }

    /**
     * Re-read and parse the scheme confirmation file for a request batch (ZP0061→ZP0062, ZP0063→ZP0064).
     * A missing file yields an empty record list — the diff still runs (every merchant surfaces as
     * MISSING_SCHEME), which correctly re-raises the break rather than silently passing.
     */
    private List<ZeroPayResultRecord> readSchemeRecords(SettlementBatchEntity batch) {
        String fileType = batch.getFileType();
        String resultType;
        if ("ZP0063".equals(fileType)) {
            resultType = "ZP0064";
        } else {
            resultType = "ZP0062";   // ZP0061 (and default) confirmed by ZP0062
        }
        String filename = resultType + "_" + batch.getBusinessDate().format(DATE_FMT) + ".txt";
        List<String> raw = fileSource.readInboxFile(filename);
        if (raw == null) {
            log.warn("operator recon re-run: {} not found in inbox for batch {} — re-diffing against empty scheme file",
                    filename, batch.getBatchId());
            return List.of();
        }
        return "ZP0064".equals(resultType) ? zp0064Parser.parse(raw) : zp0062Parser.parse(raw);
    }
}
