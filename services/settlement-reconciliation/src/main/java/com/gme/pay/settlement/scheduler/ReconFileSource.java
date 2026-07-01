package com.gme.pay.settlement.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reads ZeroPay result files from the configured inbox directory
 * ({@code gmepay.settlement.recon.inbox-dir}). Shared by the scheduled {@link ReconScheduler} and the
 * operator recon re-run so both resolve scheme files the same way.
 *
 * <p>Phase 2b TODO: replace the file-system read with an SFTP pull from ZeroPay (externally blocked).
 */
@Component
public class ReconFileSource {

    private static final Logger log = LoggerFactory.getLogger(ReconFileSource.class);

    private final String inboxDir;

    public ReconFileSource(@Value("${gmepay.settlement.recon.inbox-dir:}") String inboxDir) {
        this.inboxDir = inboxDir;
    }

    /**
     * Read all lines of {@code <inboxDir>/<filename>}. Returns {@code null} when the inbox is not
     * configured or the file is absent/unreadable.
     */
    public List<String> readInboxFile(String filename) {
        if (inboxDir == null || inboxDir.isBlank()) {
            log.debug("ReconFileSource: inboxDir not configured; skipping file read for {}", filename);
            return null;
        }
        java.io.File file = new java.io.File(inboxDir, filename);
        if (!file.exists()) {
            return null;
        }
        try {
            return java.nio.file.Files.readAllLines(file.toPath());
        } catch (java.io.IOException e) {
            log.error("ReconFileSource: failed to read {}: {}", file.getAbsolutePath(), e.getMessage());
            return null;
        }
    }
}
