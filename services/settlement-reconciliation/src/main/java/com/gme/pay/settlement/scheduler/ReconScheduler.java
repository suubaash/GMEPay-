package com.gme.pay.settlement.scheduler;

import com.gme.pay.settlement.parser.ZP0062Parser;
import com.gme.pay.settlement.parser.ZP0064Parser;
import com.gme.pay.settlement.parser.ZeroPayResultRecord;
import com.gme.pay.settlement.recon.ReconDiffEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Recon scheduler — triggers reconciliation in KST windows.
 *
 * <p>Per spec:
 * <ul>
 *   <li>~10:00 KST — process ZP0062 (morning settlement result)</li>
 *   <li>~19:00 KST — process ZP0064 (afternoon settlement result)</li>
 * </ul>
 *
 * <p>Both schedules are gated on {@code gmepay.settlement.recon.enabled} (default {@code false}).
 * Set to {@code true} in production environments only — not in test or dev.
 *
 * <p>In Phase 2a the scheduler reads files from the configured inbox directory
 * ({@code gmepay.settlement.recon.inbox-dir}). Phase 2b will replace the file-system
 * trigger with an SFTP pull from ZeroPay (out of scope here per spec).
 *
 * <p>Spring 6 note: {@link Component} with 2 constructors would need {@code @Autowired}
 * on the Spring-injected constructor. This class has exactly one constructor so the
 * annotation is omitted (Spring injects automatically).
 */
@EnableScheduling
@Component
public class ReconScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconScheduler.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final boolean enabled;
    private final String inboxDir;
    private final ZP0062Parser zp0062Parser;
    private final ZP0064Parser zp0064Parser;
    private final ReconDiffEngine diffEngine;

    public ReconScheduler(
            @Value("${gmepay.settlement.recon.enabled:false}") boolean enabled,
            @Value("${gmepay.settlement.recon.inbox-dir:}") String inboxDir,
            ZP0062Parser zp0062Parser,
            ZP0064Parser zp0064Parser,
            ReconDiffEngine diffEngine) {
        this.enabled = enabled;
        this.inboxDir = inboxDir;
        this.zp0062Parser = zp0062Parser;
        this.zp0064Parser = zp0064Parser;
        this.diffEngine = diffEngine;
    }

    /**
     * Morning recon — runs at 10:05 KST (01:05 UTC) every day.
     * Processes ZP0062 morning settlement result files found in the inbox directory.
     *
     * <p>Cron is expressed in UTC because Spring's {@code @Scheduled} uses the JVM default
     * timezone unless overridden. The UTC offset accounts for KST = UTC+9.
     */
    @Scheduled(cron = "${gmepay.settlement.recon.morning-cron:5 5 1 * * *}")
    public void morningRecon() {
        if (!enabled) {
            log.debug("ReconScheduler morningRecon: disabled (gmepay.settlement.recon.enabled=false)");
            return;
        }
        LocalDate today = LocalDate.now(KST);
        log.info("ReconScheduler: starting morning recon for date={}", today);
        processZP0062(today);
    }

    /**
     * Afternoon recon — runs at 19:05 KST (10:05 UTC) every day.
     * Processes ZP0064 afternoon settlement result files.
     */
    @Scheduled(cron = "${gmepay.settlement.recon.afternoon-cron:5 5 10 * * *}")
    public void afternoonRecon() {
        if (!enabled) {
            log.debug("ReconScheduler afternoonRecon: disabled (gmepay.settlement.recon.enabled=false)");
            return;
        }
        LocalDate today = LocalDate.now(KST);
        log.info("ReconScheduler: starting afternoon recon for date={}", today);
        processZP0064(today);
    }

    /**
     * Process a ZP0062 file for the given settlement date.
     *
     * <p>Reads the file from {@code <inboxDir>/ZP0062_<YYYYMMDD>.txt}, parses it,
     * and runs the diff engine. If no file is found, logs a warning and skips.
     *
     * <p>Phase 2b TODO: replace file-system read with SFTP pull from ZeroPay.
     */
    void processZP0062(LocalDate date) {
        String filename = "ZP0062_" + date.format(DATE_FMT) + ".txt";
        List<String> lines = readInboxFile(filename);
        if (lines == null) {
            log.warn("ReconScheduler: ZP0062 file not found in inbox: {}", filename);
            return;
        }
        try {
            List<ZeroPayResultRecord> records = zp0062Parser.parse(lines);
            String batchId = "ZP0062-" + date.format(DATE_FMT);
            diffEngine.runDiff(batchId, date, records);
        } catch (Exception e) {
            log.error("ReconScheduler: ZP0062 processing failed for date={}: {}", date, e.getMessage(), e);
        }
    }

    /**
     * Process a ZP0064 file for the given settlement date.
     */
    void processZP0064(LocalDate date) {
        String filename = "ZP0064_" + date.format(DATE_FMT) + ".txt";
        List<String> lines = readInboxFile(filename);
        if (lines == null) {
            log.warn("ReconScheduler: ZP0064 file not found in inbox: {}", filename);
            return;
        }
        try {
            List<ZeroPayResultRecord> records = zp0064Parser.parse(lines);
            String batchId = "ZP0064-" + date.format(DATE_FMT);
            diffEngine.runDiff(batchId, date, records);
        } catch (Exception e) {
            log.error("ReconScheduler: ZP0064 processing failed for date={}: {}", date, e.getMessage(), e);
        }
    }

    /**
     * Read lines from the inbox directory. Returns null if the file does not exist.
     *
     * <p>Phase 2b TODO: this will be replaced by an SFTP client call.
     */
    private List<String> readInboxFile(String filename) {
        if (inboxDir == null || inboxDir.isBlank()) {
            log.debug("ReconScheduler: inboxDir not configured; skipping file read for {}", filename);
            return null;
        }
        java.io.File file = new java.io.File(inboxDir, filename);
        if (!file.exists()) {
            return null;
        }
        try {
            return java.nio.file.Files.readAllLines(file.toPath());
        } catch (java.io.IOException e) {
            log.error("ReconScheduler: failed to read {}: {}", file.getAbsolutePath(), e.getMessage());
            return null;
        }
    }
}
