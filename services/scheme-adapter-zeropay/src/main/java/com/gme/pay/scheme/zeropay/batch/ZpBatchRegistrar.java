package com.gme.pay.scheme.zeropay.batch;

import com.gme.pay.scheme.zeropay.adapter.model.BatchFile;
import com.gme.pay.scheme.zeropay.adapter.model.BatchType;
import com.gme.pay.scheme.zeropay.persistence.ZpBatchFileEntity;
import com.gme.pay.scheme.zeropay.persistence.ZpBatchFileRepository;
import com.gme.pay.scheme.zeropay.persistence.ZpStagedRecordEntity;
import com.gme.pay.scheme.zeropay.persistence.ZpStagedRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persists the batch-file registry ({@code zp_batch_files}) and ZP0011 staged detail records
 * ({@code zp_staged_records}) for each generated/transferred outbound file.
 *
 * <p>Without this, the registry tables (V001/V002) are written by nothing on the live path —
 * so duplicate generation and out-of-window transfers cannot be detected, and reconciliation
 * has nothing to match inbound ZP0012 against. This registrar closes that gap: the scheduler
 * registers a {@code GENERATED} row before transfer and flips it to {@code TRANSMITTED} after.</p>
 *
 * <p>All methods are best-effort from the scheduler's perspective — callers wrap them so a
 * registry write never blocks the outbound transfer itself.</p>
 */
@Component
public class ZpBatchRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ZpBatchRegistrar.class);

    private final ZpBatchFileRepository batchFileRepository;
    private final ZpStagedRecordRepository stagedRecordRepository;

    public ZpBatchRegistrar(ZpBatchFileRepository batchFileRepository,
                            ZpStagedRecordRepository stagedRecordRepository) {
        this.batchFileRepository = batchFileRepository;
        this.stagedRecordRepository = stagedRecordRepository;
    }

    /**
     * Registers a freshly generated outbound file as {@code GENERATED}, staging ZP0011 detail
     * lines when supplied. Idempotent on the natural key {@code (fileType, businessDate, seq)}:
     * an already-registered file is returned as-is (no duplicate row, no re-staging).
     *
     * @param file       the generated batch file
     * @param fileName   the outbound file name (registry natural identifier)
     * @param windowOpens transmission window open instant
     * @param windowCloses transmission window close instant
     * @param zp0011Records optional ZP0011 detail records to stage (may be null/empty)
     * @return the registry row id, or {@code null} if persistence failed (non-fatal)
     */
    @Transactional
    public Long registerGenerated(BatchFile file, String fileName,
                                  Instant windowOpens, Instant windowCloses,
                                  List<Zp0011Record> zp0011Records) {
        try {
            Optional<ZpBatchFileEntity> existing = batchFileRepository
                    .findByFileTypeAndBusinessDateAndSequenceNo(
                            file.fileType().name(), file.businessDate(), file.sequenceNo());
            if (existing.isPresent()) {
                log.debug("Batch file already registered: {} {} seq {}",
                        file.fileType(), file.businessDate(), file.sequenceNo());
                return existing.get().getId();
            }

            ZpBatchFileEntity entity = ZpBatchFileEntity.outbound(
                    file.fileType().name(), file.businessDate(), file.sequenceNo(), fileName,
                    sha256Hex(file.contentBytes()), file.contentBytes().length, file.recordCount(),
                    file.controlSum(), windowOpens, windowCloses);
            Long id = batchFileRepository.save(entity).getId();

            if (file.fileType() == BatchType.ZP0011 && zp0011Records != null) {
                int line = 1;
                for (Zp0011Record r : zp0011Records) {
                    // Defensive truncation to the staging column widths (V002) so a single
                    // over-long field never aborts the whole registry write.
                    stagedRecordRepository.save(ZpStagedRecordEntity.zp0011Detail(
                            id, line++, trim(r.gmeTxnId(), 20), trim(r.zeroPayTxnRef(), 20),
                            trim(r.merchantId(), 10), trim(r.qrCodeId(), 20),
                            r.txnDate(), r.txnTime(), r.payoutAmountKrw(),
                            r.merchantFeeAmt(), r.vanFeeAmt(),
                            String.valueOf(r.partnerType()), trim(r.approvalCode(), 12),
                            String.valueOf(r.statusCode())));
                }
            }
            log.info("Registered outbound {} ({}), {} records staged",
                    file.fileType(), fileName, file.recordCount());
            return id;
        } catch (RuntimeException e) {
            log.warn("Batch registry write failed (non-fatal) for {}: {}",
                    file.fileType(), e.getMessage());
            return null;
        }
    }

    /** Flips a registered file to {@code TRANSMITTED}; no-op if the id is null/not found. */
    @Transactional
    public void markTransmitted(Long batchFileId, Instant sentAt) {
        if (batchFileId == null) {
            return;
        }
        try {
            batchFileRepository.findById(batchFileId).ifPresent(e -> {
                e.markTransmitted(sentAt);
                batchFileRepository.save(e);
            });
        } catch (RuntimeException e) {
            log.warn("Batch registry transmit-mark failed (non-fatal) id={}: {}",
                    batchFileId, e.getMessage());
        }
    }

    private static String trim(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "0".repeat(64);
        }
    }
}
