package com.gme.pay.reporting.kofiu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Builds the KoFIU daily feed file from a {@link KofiuReportBatch} and writes
 * it to a configurable output directory.
 *
 * <h2>File layout (TODO placeholders)</h2>
 * <p>The official KoFIU electronic-submission format specification has not been
 * confirmed at the time of writing (OI-03 pending). The layout below follows
 * a plausible fixed-width / pipe-delimited convention based on KoFIU's
 * published draft — all TODO markers must be replaced once the spec is confirmed.
 *
 * <pre>
 * H|KOFIU_FEED|{reportDate}|{entityId}|{totalCtr}|{totalStr}
 * CTR|{endUserId}|{partnerId}|{reportDate}|{totalAmountKrw}|{txnCount}|{txnIds}
 * ...
 * STR|{txnId}|{txnRef}|{endUserId}|{partnerId}|{reportDate}|{amountKrw}|{srcCcy}|{dstCcy}
 * ...
 * T|{totalRecords}
 * </pre>
 *
 * <p>Money fields are formatted as plain decimal strings (no scientific notation,
 * scale 2) per MONEY_CONVENTION.md.
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li>{@code gmepay.reporting.kofiu.output-dir} — directory to write feed files;
 *       default {@code /tmp/kofiu-feeds}</li>
 *   <li>{@code gmepay.reporting.kofiu.entity-id} — fallback entity id when
 *       regulatory config does not supply one; default {@code KOFIU_ENTITY_UNKNOWN}</li>
 * </ul>
 *
 * <p>Spring 6 note: this component has two constructors (the @Value one and the
 * no-arg for subclassing in tests). {@code @Autowired} is placed on the
 * {@code @Value} constructor so Spring selects it unambiguously (required when
 * a component has 2+ constructors).
 */
@Component
public class KofiuFeedFileBuilder {

    private static final Logger log = LoggerFactory.getLogger(KofiuFeedFileBuilder.class);

    static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final String LINE_SEP = "\n";

    private final String outputDir;
    private final String fallbackEntityId;

    @Autowired
    public KofiuFeedFileBuilder(
            @Value("${gmepay.reporting.kofiu.output-dir:/tmp/kofiu-feeds}") String outputDir,
            @Value("${gmepay.reporting.kofiu.entity-id:KOFIU_ENTITY_UNKNOWN}") String fallbackEntityId) {
        this.outputDir = outputDir;
        this.fallbackEntityId = fallbackEntityId;
    }

    /**
     * Builds the feed file content for {@code batch} and writes it to
     * {@link #outputDir}/{@code KOFIU_YYYYMMDD.dat}.
     *
     * @param batch the daily report batch; must not be null
     * @return the {@link Path} of the written file
     * @throws UncheckedIOException if the file cannot be written
     */
    public Path buildAndWrite(KofiuReportBatch batch) {
        String content = buildContent(batch);
        Path dir = Paths.get(outputDir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create KoFIU output directory: " + dir, e);
        }
        String filename = "KOFIU_" + batch.getReportDate().format(DATE_FMT) + ".dat";
        Path file = dir.resolve(filename);
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
            log.info("KoFIU feed file written: {} ({} CTR, {} STR)",
                    file, batch.getCtrReports().size(), batch.getStrReports().size());
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write KoFIU feed file: " + file, e);
        }
    }

    /**
     * Builds the in-memory feed file string. Exposed package-private for tests.
     */
    String buildContent(KofiuReportBatch batch) {
        LocalDate date = batch.getReportDate();
        String dateStr = date.format(DATE_FMT);
        // TODO(OI-03): use per-partner entityId from RegulatoryConfigPort once
        // the batch carries partner-level metadata; for now use fallback.
        String entityId = fallbackEntityId;

        StringBuilder sb = new StringBuilder(512);

        // Header line
        // TODO(OI-03): confirm header field order and length specs with KoFIU
        sb.append("H|KOFIU_FEED")
                .append("|").append(dateStr)
                .append("|").append(entityId)
                .append("|CTR_COUNT=").append(batch.getCtrReports().size())
                .append("|STR_COUNT=").append(batch.getStrReports().size())
                .append(LINE_SEP);

        // CTR records
        for (CtrReport ctr : batch.getCtrReports()) {
            // TODO(OI-03): confirm CTR field positions 1-n per KoFIU spec
            sb.append("CTR")
                    .append("|").append(ctr.getEndUserId())
                    .append("|").append(ctr.getPartnerId())
                    .append("|").append(ctr.getReportDate().format(DATE_FMT))
                    .append("|").append(ctr.getTotalAmountKrw().toPlainString())
                    .append("|").append(ctr.getTransactionCount())
                    .append("|").append(String.join(",",
                            ctr.getContributingTxnIds().stream()
                                    .map(String::valueOf)
                                    .toList()))
                    .append(LINE_SEP);
        }

        // STR records
        for (StrReport str : batch.getStrReports()) {
            // TODO(OI-03): confirm STR field positions 1-n per KoFIU spec
            sb.append("STR")
                    .append("|").append(str.getTxnId())
                    .append("|").append(str.getTxnRef())
                    .append("|").append(str.getEndUserId())
                    .append("|").append(str.getPartnerId())
                    .append("|").append(str.getReportDate().format(DATE_FMT))
                    .append("|").append(str.getAmountKrw().toPlainString())
                    .append("|").append(str.getSrcCcy())
                    .append("|").append(str.getDstCcy())
                    .append(LINE_SEP);
        }

        // Trailer
        // TODO(OI-03): confirm trailer format per KoFIU spec
        sb.append("T|").append(batch.totalReports()).append(LINE_SEP);

        return sb.toString();
    }
}
