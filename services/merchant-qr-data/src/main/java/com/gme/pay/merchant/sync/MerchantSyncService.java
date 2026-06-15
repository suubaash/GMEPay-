package com.gme.pay.merchant.sync;

import com.gme.pay.merchant.domain.InMemoryMerchantRepository;
import com.gme.pay.merchant.domain.Merchant;
import com.gme.pay.merchant.domain.MerchantRepository;
import com.gme.pay.merchant.persistence.MongoBackedMerchantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Domain service that orchestrates ZeroPay merchant/QR file ingest.
 *
 * <p>Reads from a local directory (no real SFTP — see {@link MerchantSyncScheduler}).
 * Delegates parsing to {@link ZeroPayMerchantFileParser} / {@link ZeroPayQrFileParser}
 * and persists results via the repository. Supports both the
 * {@link MongoBackedMerchantRepository} (when MongoDB is wired) and
 * {@link InMemoryMerchantRepository} (unit tests / local dev without Mongo).
 *
 * <p>UC-07-01: incremental files ZP0041/0043/0045/0047 processed as deltas.
 * UC-07-02: full-list files ZP0051/0053 used for reconciliation upserts.
 */
@Service
public class MerchantSyncService {

    private static final Logger log = LoggerFactory.getLogger(MerchantSyncService.class);

    /** Maximum error messages retained per file in the SyncResult (rest are counted only). */
    private static final int MAX_ERROR_DETAILS = 20;

    private final ZeroPayMerchantFileParser merchantParser;
    private final ZeroPayQrFileParser qrParser;
    private final MerchantRepository merchantRepository;

    public MerchantSyncService(ZeroPayMerchantFileParser merchantParser,
                                ZeroPayQrFileParser qrParser,
                                MerchantRepository merchantRepository) {
        this.merchantParser = merchantParser;
        this.qrParser = qrParser;
        this.merchantRepository = merchantRepository;
    }

    /**
     * Processes a single ZeroPay inbound file.
     *
     * <p>Detects the file type from the name, selects the appropriate parser,
     * and upserts or deactivates records via the repository.
     *
     * @param file path to the file to process
     * @return sync result with counts and any error details
     */
    public SyncResult processFile(Path file) {
        String filename = file.getFileName().toString();
        ZeroPayFileType fileType = ZeroPayFileType.fromFilename(filename);

        if (fileType == null) {
            String reason = "Unrecognised file prefix; expected ZP0041/43/45/47/51/53: " + filename;
            log.warn(reason);
            return SyncResult.fatal(filename, null, reason);
        }

        log.info("Processing {} file: {}", fileType, filename);

        try {
            return switch (fileType.domain) {
                case MERCHANT -> processMerchantFile(file, filename, fileType);
                case QR -> processQrFile(file, filename, fileType);
            };
        } catch (IOException e) {
            String reason = "IO error reading file " + filename + ": " + e.getMessage();
            log.error(reason, e);
            return SyncResult.fatal(filename, fileType, reason);
        }
    }

    // ------------------------------------------------------------------
    // Merchant-domain file processing (ZP0041, ZP0045, ZP0051)
    // ------------------------------------------------------------------

    private SyncResult processMerchantFile(Path file, String filename, ZeroPayFileType fileType)
            throws IOException {
        ParseResult<ParsedMerchantRow> parsed;
        try (Reader reader = new FileReader(file.toFile(), StandardCharsets.UTF_8)) {
            parsed = (fileType.syncMode == ZeroPayFileType.SyncMode.INCREMENTAL)
                    ? merchantParser.parseIncremental(reader, filename)
                    : merchantParser.parseFullList(reader, filename);
        }

        int upserted = 0;
        int deactivated = 0;
        List<String> persistErrors = new ArrayList<>();

        for (ParsedMerchantRow row : parsed.rows()) {
            try {
                if (row.isDelete()) {
                    // MD record — deactivate the merchant by QR lookup isn't available here;
                    // we deactivate by setting the status on any existing record via upsert.
                    // Since ZP0041 delete rows carry merchantId (not qrCode), we use the
                    // existing record's qrCode when found via MongoBackedMerchantRepository.
                    Optional<Merchant> existing = findByMerchantId(row.merchantId());
                    if (existing.isPresent()) {
                        Merchant m = existing.get();
                        upsertMerchant(new Merchant(m.merchantId(), m.qrCodeId(),
                                m.name(), m.merchantType(), m.feeType(),
                                "DEACTIVATED", false,
                                m.payoutCurrency(), m.schemeId(), m.city(), m.mcc()));
                        deactivated++;
                    } else {
                        log.debug("MD row for unknown merchant_id={} — no existing record to deactivate",
                                row.merchantId());
                    }
                } else {
                    // MN / MC / full-list row — upsert requires a qrCode as the document _id.
                    // ZP0041/0045/0051 rows carry merchantId but NOT qrCode; we must look up
                    // an existing document or synthesise a placeholder qrCode.
                    // TODO(spec): ZP0041 may carry qr_code in a future field; update when confirmed.
                    Optional<Merchant> existing = findByMerchantId(row.merchantId());
                    String qrCodeId = existing.map(Merchant::qrCodeId)
                            .orElse(syntheticQrCode(row.merchantId()));
                    Merchant m = new Merchant(
                            row.merchantId(),
                            qrCodeId,
                            row.name(),
                            row.merchantType(),
                            row.feeType(),
                            row.status(),
                            row.isActive(),
                            row.payoutCurrency(),
                            row.schemeId(),
                            row.city(),
                            row.mcc());
                    upsertMerchant(m);
                    upserted++;
                }
            } catch (Exception e) {
                String msg = "Failed to persist merchant row for merchant_id=" + row.merchantId()
                             + ": " + e.getMessage();
                log.warn(msg, e);
                if (persistErrors.size() < MAX_ERROR_DETAILS) {
                    persistErrors.add(msg);
                }
            }
        }

        List<String> allErrors = new ArrayList<>(parsed.errors());
        allErrors.addAll(persistErrors);
        int totalErrors = parsed.errors().size() + persistErrors.size();

        SyncResult result = new SyncResult(
                filename, fileType, upserted, deactivated, parsed.skipped(),
                totalErrors, allErrors.stream().limit(MAX_ERROR_DETAILS).toList(),
                Instant.now(), true);

        log.info("Completed {}: upserted={}, deactivated={}, skipped={}, errors={}",
                filename, upserted, deactivated, parsed.skipped(), totalErrors);
        return result;
    }

    // ------------------------------------------------------------------
    // QR-domain file processing (ZP0043, ZP0047, ZP0053)
    // ------------------------------------------------------------------

    private SyncResult processQrFile(Path file, String filename, ZeroPayFileType fileType)
            throws IOException {
        ParseResult<ParsedQrRow> parsed;
        try (Reader reader = new FileReader(file.toFile(), StandardCharsets.UTF_8)) {
            parsed = (fileType.syncMode == ZeroPayFileType.SyncMode.INCREMENTAL)
                    ? qrParser.parseIncremental(reader, filename)
                    : qrParser.parseFullList(reader, filename);
        }

        int upserted = 0;
        int deactivated = 0;
        List<String> persistErrors = new ArrayList<>();

        for (ParsedQrRow row : parsed.rows()) {
            try {
                if (row.isDeactivation()) {
                    // Deactivate by upserting the existing merchant record with active=false.
                    Optional<Merchant> existing = findByQrCode(row.qrCode());
                    if (existing.isPresent()) {
                        Merchant m = existing.get();
                        upsertMerchant(new Merchant(m.merchantId(), m.qrCodeId(),
                                m.name(), m.merchantType(), m.feeType(),
                                "DEACTIVATED", false,
                                m.payoutCurrency(), m.schemeId(), m.city(), m.mcc()));
                        deactivated++;
                    } else {
                        // No existing record — insert a minimal deactivated shell so the
                        // lookup endpoint returns active=false if the QR is ever queried.
                        upsertMerchant(new Merchant(
                                row.merchantId(), row.qrCode(), "",
                                "", "", "DEACTIVATED", false,
                                null, "ZEROPAY", null, null));
                        deactivated++;
                    }
                } else {
                    // QR register (QR row) or full-list active entry — upsert existing or create.
                    Optional<Merchant> existing = findByQrCode(row.qrCode());
                    if (existing.isPresent()) {
                        Merchant m = existing.get();
                        upsertMerchant(new Merchant(m.merchantId(), m.qrCodeId(),
                                m.name(), m.merchantType(), m.feeType(),
                                "ACTIVE", true,
                                m.payoutCurrency(), m.schemeId(), m.city(), m.mcc()));
                    } else {
                        // New QR — create a minimal stub; merchant details arrive via ZP0041.
                        upsertMerchant(new Merchant(
                                row.merchantId(), row.qrCode(), "",
                                "", "", "ACTIVE", true,
                                null, "ZEROPAY", null, null));
                    }
                    upserted++;
                }
            } catch (Exception e) {
                String msg = "Failed to persist QR row for qr_code=" + row.qrCode()
                             + ": " + e.getMessage();
                log.warn(msg, e);
                if (persistErrors.size() < MAX_ERROR_DETAILS) {
                    persistErrors.add(msg);
                }
            }
        }

        List<String> allErrors = new ArrayList<>(parsed.errors());
        allErrors.addAll(persistErrors);
        int totalErrors = parsed.errors().size() + persistErrors.size();

        SyncResult result = new SyncResult(
                filename, fileType, upserted, deactivated, parsed.skipped(),
                totalErrors, allErrors.stream().limit(MAX_ERROR_DETAILS).toList(),
                Instant.now(), true);

        log.info("Completed {}: upserted={}, deactivated={}, skipped={}, errors={}",
                filename, upserted, deactivated, parsed.skipped(), totalErrors);
        return result;
    }

    // ------------------------------------------------------------------
    // Repository delegation helpers (work with both Mongo and in-memory)
    // ------------------------------------------------------------------

    private void upsertMerchant(Merchant m) {
        if (merchantRepository instanceof MongoBackedMerchantRepository mongo) {
            mongo.upsert(m);
        } else if (merchantRepository instanceof InMemoryMerchantRepository mem) {
            mem.put(m);
        } else {
            // Fallback: unknown repo type — log and skip (should not happen in practice).
            log.warn("Unknown repository type {}; upsert skipped for qrCode={}",
                    merchantRepository.getClass().getSimpleName(), m.qrCodeId());
        }
    }

    private Optional<Merchant> findByQrCode(String qrCode) {
        return merchantRepository.findByQrCodeId(qrCode);
    }

    private Optional<Merchant> findByMerchantId(String merchantId) {
        if (merchantRepository instanceof MongoBackedMerchantRepository mongo) {
            return mongo.findByMerchantId(merchantId);
        }
        // In-memory store has no merchantId index — not needed for unit tests.
        return Optional.empty();
    }

    /**
     * Derives a deterministic placeholder QR code from a merchant id for use
     * when a ZP0041 row arrives before the matching ZP0043 QR registration.
     *
     * <p>Format: {@code ZPMERCH-<merchantId>} — clearly synthetic, NOT a real
     * ZeroPay CHAR(20) QR identifier. The real QR registration (ZP0043) will
     * overwrite this with the true QR code when processed.
     *
     * <p>TODO(spec): Remove when ZP0041 layout is confirmed to include qr_code.
     */
    static String syntheticQrCode(String merchantId) {
        return "ZPMERCH-" + merchantId;
    }
}
