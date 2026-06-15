package com.gme.pay.reporting.bok;

import com.gme.pay.reporting.domain.BokFxRecord;
import com.gme.pay.reporting.domain.BokReportType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds BOK (Bank of Korea) FX1014 and FX1015 report files from a list of
 * {@link BokFxRecord} objects produced by {@link com.gme.pay.reporting.domain.BokFxMapper}.
 *
 * <h2>File format</h2>
 * Each record is written as a pipe-delimited (|) CSV line. Fixed-width padding
 * rules follow the publicly-documented BOK foreign-exchange electronic reporting
 * specification (외국환거래 전자신고 서식). Column widths noted below are the
 * maximum byte widths from that spec; values are left-padded with spaces for
 * numeric fields and right-padded with spaces for alphanumeric fields.
 *
 * <h2>FX1014 — Outbound foreign exchange (GME customer paying overseas)</h2>
 * Columns (1-based, pipe-delimited):
 * <pre>
 *  Col  Name                       Max   Type  Notes
 *  ---  -------------------------  ----  ----  ------------------------------------------
 *   1   record_type                4     AN    "1014" (form identifier)
 *   2   report_date                8     N     YYYYMMDD (KST)
 *   3   reporting_entity_id        10    AN    GME entity code (gmepay.reporting.bok.entity-id)
 *   4   partner_id                 20    AN    Internal partner identifier
 *   5   txn_ref                    30    AN    Transaction reference
 *   6   txn_id                     20    N     Internal numeric transaction ID
 *   7   remittance_ccy             3     AN    ISO 4217 — collection currency (KRW source)
 *   8   remittance_amount          18.2  N     Amount remitted (collectionAmount, 2dp)
 *   9   payout_ccy                 3     AN    ISO 4217 — destination currency
 *  10   payout_amount              18.2  N     Amount paid out (payoutAmount, 2dp)
 *  11   exchange_rate              18.8  N     crossRate (target_payout / send_amount), 8dp
 *  12   usd_equivalent             18.2  N     USD equivalent of the transaction (usdAmount, 2dp)
 *  13   offer_rate_coll            18.8  N     offerRateColl — send/collect-net rate, 8dp
 *  14   submission_status          10    AN    PENDING / SUBMITTED / REJECTED
 *  15   bok_txn_code               10    AN    TODO(OI-03): BOK transaction type code — awaiting
 *                                              official mapping table from BOK API specification.
 *                                              Currently hard-coded as "TODO_OI03".
 *  16   bok_fx_reporting_category  10    AN    TODO(OI-03): BOK FX reporting category code —
 *                                              awaiting official category mapping from spec.
 *                                              Currently hard-coded as "TODO_OI03".
 * </pre>
 *
 * <h2>FX1015 — Inbound foreign exchange (payment to Korean merchant)</h2>
 * Columns (1-based, pipe-delimited):
 * <pre>
 *  Col  Name                       Max   Type  Notes
 *  ---  -------------------------  ----  ----  ------------------------------------------
 *   1   record_type                4     AN    "1015" (form identifier)
 *   2   report_date                8     N     YYYYMMDD (KST)
 *   3   reporting_entity_id        10    AN    GME entity code
 *   4   partner_id                 20    AN    Internal partner identifier
 *   5   txn_ref                    30    AN    Transaction reference
 *   6   txn_id                     20    N     Internal numeric transaction ID
 *   7   collection_ccy             3     AN    ISO 4217 — foreign source currency
 *   8   collection_amount          18.2  N     Amount collected from sender (2dp)
 *   9   payout_ccy                 3     AN    ISO 4217 — KRW destination currency
 *  10   payout_amount              18.2  N     Amount paid to Korean merchant (2dp)
 *  11   exchange_rate              18.8  N     crossRate, 8dp
 *  12   usd_equivalent             18.2  N     USD equivalent (2dp)
 *  13   offer_rate_coll            18.8  N     BOK FX1015 field #14:
 *                                              send_amount / (collection_usd - collection_margin_usd)
 *                                              8dp — locked at CommitTransaction time.
 *  14   submission_status          10    AN    PENDING / SUBMITTED / REJECTED
 *  15   bok_txn_code               10    AN    TODO(OI-03): BOK transaction type code
 *  16   bok_fx_reporting_category  10    AN    TODO(OI-03): BOK FX reporting category code
 * </pre>
 *
 * <h2>File naming</h2>
 * Files are written to {@code gmepay.reporting.bok.outbound-dir} (default:
 * {@code ./build/bok-out}) with names:
 * <ul>
 *   <li>FX1014: {@code BOK_FX1014_YYYYMMDD.csv}</li>
 *   <li>FX1015: {@code BOK_FX1015_YYYYMMDD.csv}</li>
 * </ul>
 *
 * <h2>SFTP submission</h2>
 * Real SFTP submission is out of scope (no credentials; calendar-bound OI-03).
 * After writing the file, {@link #submitStub(Path, BokReportType, LocalDate)} is
 * called which logs the would-be submission and returns immediately.
 */
@Component
public class BokFxFileBuilder {

    private static final Logger log = LoggerFactory.getLogger(BokFxFileBuilder.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String DELIMITER = "|";
    private static final String TODO_OI03 = "TODO_OI03";

    private final String outboundDir;
    private final String entityId;

    /**
     * Primary constructor. {@code @Autowired} required — Spring 6 does not
     * auto-select the @Value constructor when multiple constructors are present.
     */
    @Autowired
    public BokFxFileBuilder(
            @Value("${gmepay.reporting.bok.outbound-dir:./build/bok-out}") String outboundDir,
            @Value("${gmepay.reporting.bok.entity-id:GME_KR}") String entityId) {
        this.outboundDir = outboundDir;
        this.entityId = entityId;
    }

    /**
     * Builds one FX1014 file and one FX1015 file for the given report date.
     * Records are split by {@link BokReportType} before writing.
     * Files with zero matching records are still created (empty body, header-only).
     *
     * @param records    mapped BOK FX records for the report date
     * @param reportDate the KST date for which the report is generated
     * @return a {@link BokFileResult} describing the two files written
     * @throws IOException if the output directory cannot be created or files cannot be written
     */
    public BokFileResult buildFiles(List<BokFxRecord> records, LocalDate reportDate)
            throws IOException {
        Path dir = Paths.get(outboundDir);
        Files.createDirectories(dir);

        List<BokFxRecord> fx1014 = records.stream()
                .filter(r -> r.getReportType() == BokReportType.FX1014)
                .collect(Collectors.toList());
        List<BokFxRecord> fx1015 = records.stream()
                .filter(r -> r.getReportType() == BokReportType.FX1015)
                .collect(Collectors.toList());

        Path fx1014Path = dir.resolve("BOK_FX1014_" + reportDate.format(DATE_FMT) + ".csv");
        Path fx1015Path = dir.resolve("BOK_FX1015_" + reportDate.format(DATE_FMT) + ".csv");

        writeFile(fx1014Path, fx1014, BokReportType.FX1014, reportDate);
        writeFile(fx1015Path, fx1015, BokReportType.FX1015, reportDate);

        submitStub(fx1014Path, BokReportType.FX1014, reportDate);
        submitStub(fx1015Path, BokReportType.FX1015, reportDate);

        log.info("BOK FX files written for {}: FX1014={} ({} records), FX1015={} ({} records)",
                reportDate, fx1014Path.getFileName(), fx1014.size(),
                fx1015Path.getFileName(), fx1015.size());

        return new BokFileResult(fx1014Path, fx1014.size(), fx1015Path, fx1015.size());
    }

    // -------------------------------------------------------------------------
    // Private: file writer
    // -------------------------------------------------------------------------

    private void writeFile(Path path, List<BokFxRecord> records,
                           BokReportType type, LocalDate reportDate) throws IOException {
        try (PrintWriter pw = new PrintWriter(
                Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {

            // Header comment line describes the layout
            pw.println("# BOK " + type.name() + " | reportDate=" + reportDate
                    + " | entity=" + entityId
                    + " | records=" + records.size());
            pw.println("# Col: record_type|report_date|entity_id|partner_id|txn_ref|txn_id"
                    + "|ccy_src|amount_src|ccy_dst|amount_dst|exchange_rate|usd_equivalent"
                    + "|offer_rate_coll|submission_status|bok_txn_code|bok_fx_reporting_category");

            for (BokFxRecord r : records) {
                pw.println(buildLine(r, type, reportDate));
            }
        }
    }

    /**
     * Builds a single pipe-delimited data line for one {@link BokFxRecord}.
     * The column layout is identical for FX1014 and FX1015 — only col 1
     * (record_type) differs.
     * <p>Exposed as {@code public} to allow direct golden-row assertions in unit tests.
     */
    public String buildLine(BokFxRecord r, BokReportType type, LocalDate reportDate) {
        return String.join(DELIMITER,
                // Col 1: record_type — "1014" or "1015"
                type == BokReportType.FX1014 ? "1014" : "1015",
                // Col 2: report_date YYYYMMDD
                reportDate.format(DATE_FMT),
                // Col 3: reporting entity ID
                pad(entityId, 10),
                // Col 4: partner_id
                String.valueOf(r.getPartnerId()),
                // Col 5: txn_ref
                pad(r.getTxnRef(), 30),
                // Col 6: txn_id
                String.valueOf(r.getTxnId()),
                // Col 7: source currency (collection for FX1015; same field for FX1014)
                pad(r.getCollectionCcy(), 3),
                // Col 8: source amount — 2 decimal places
                formatAmount(r.getCollectionAmount(), 2),
                // Col 9: destination currency
                pad(r.getPayoutCcy(), 3),
                // Col 10: payout amount — 2 decimal places
                formatAmount(r.getPayoutAmount(), 2),
                // Col 11: exchange rate (crossRate) — 8 decimal places
                formatAmount(r.getCrossRate(), 8),
                // Col 12: USD equivalent — 2 decimal places
                formatAmount(r.getUsdAmount(), 2),
                // Col 13: offer_rate_coll (BOK FX1015 field #14) — 8 decimal places
                formatAmount(r.getOfferRateColl(), 8),
                // Col 14: submission status
                pad(r.getSubmissionStatus(), 10),
                // Col 15: TODO(OI-03) BOK transaction type code — awaiting BOK spec mapping
                TODO_OI03,
                // Col 16: TODO(OI-03) BOK FX reporting category code — awaiting BOK spec mapping
                TODO_OI03
        );
    }

    // -------------------------------------------------------------------------
    // Private: SFTP submission stub
    // -------------------------------------------------------------------------

    /**
     * Stub for SFTP submission to BOK. Real credentials/SFTP are out of scope
     * (calendar-bound OI-03). Logs the would-be submission and returns.
     *
     * <p>TODO(OI-03): replace with real SFTP push when BOK SFTP endpoint and
     * credentials are available.
     */
    private void submitStub(Path filePath, BokReportType type, LocalDate reportDate) {
        log.info("[STUB] Would SFTP-submit {} to BOK {} endpoint for {} — OI-03 pending",
                filePath.getFileName(), type.name(), reportDate);
    }

    // -------------------------------------------------------------------------
    // Private: formatting helpers
    // -------------------------------------------------------------------------

    private static String formatAmount(BigDecimal value, int scale) {
        if (value == null) return "0." + "0".repeat(scale);
        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    /** Right-pads (or truncates) a string field to exactly {@code width} chars. */
    private static String pad(String value, int width) {
        if (value == null) value = "";
        if (value.length() >= width) return value.substring(0, width);
        return value + " ".repeat(width - value.length());
    }

    // -------------------------------------------------------------------------
    // Result record
    // -------------------------------------------------------------------------

    /**
     * Describes the two files produced by {@link #buildFiles}.
     */
    public static final class BokFileResult {
        private final Path fx1014Path;
        private final int fx1014Count;
        private final Path fx1015Path;
        private final int fx1015Count;

        public BokFileResult(Path fx1014Path, int fx1014Count,
                             Path fx1015Path, int fx1015Count) {
            this.fx1014Path = fx1014Path;
            this.fx1014Count = fx1014Count;
            this.fx1015Path = fx1015Path;
            this.fx1015Count = fx1015Count;
        }

        public Path getFx1014Path() { return fx1014Path; }
        public int getFx1014Count() { return fx1014Count; }
        public Path getFx1015Path() { return fx1015Path; }
        public int getFx1015Count() { return fx1015Count; }
    }
}
