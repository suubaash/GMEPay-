package com.gme.pay.merchant.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

/**
 * Local-filesystem {@link MerchantFileSource}: scans a configured inbound directory
 * for ZeroPay batch files and (optionally) archives them after successful processing.
 *
 * <p>This is the default / test transport — no real SFTP credentials are required.
 * Drop fixture files (e.g. {@code ZP0041_20260615.dat}) into
 * {@code gmepay.merchant-sync.inbound-dir} for local testing. A real SFTP-backed
 * source can replace this bean (see {@link MerchantFileSource}); this implementation
 * is {@link ConditionalOnMissingBean} so a custom source wins automatically.
 *
 * <p>{@link #markProcessed(Path)} moves the file into
 * {@code {archive-dir}/{YYYYMMDD}/} when an archive directory is configured (non-blank),
 * otherwise it is a no-op (files remain and the scheduler relies on idempotent upserts).
 */
@Component
@ConditionalOnMissingBean(MerchantFileSource.class)
public class LocalDirectoryFileSource implements MerchantFileSource {

    private static final Logger log = LoggerFactory.getLogger(LocalDirectoryFileSource.class);

    /** File extensions accepted as ZeroPay inbound batch files. */
    private static final List<String> ACCEPTED_EXTENSIONS = List.of(".dat", ".txt", ".csv");

    private final String inboundDir;
    private final String archiveDir;

    /**
     * @param inboundDir directory scanned for inbound files
     * @param archiveDir directory files are moved into after processing; blank = disable archiving
     */
    @Autowired
    public LocalDirectoryFileSource(
            @Value("${gmepay.merchant-sync.inbound-dir:./data/zeropay-inbound}") String inboundDir,
            @Value("${gmepay.merchant-sync.archive-dir:}") String archiveDir) {
        this.inboundDir = inboundDir;
        this.archiveDir = archiveDir;
    }

    @Override
    public List<Path> listAvailableFiles() throws IOException {
        Path dir = Paths.get(inboundDir);
        if (!Files.isDirectory(dir)) {
            log.warn("LocalDirectoryFileSource: inbound-dir does not exist or is not a directory: {}",
                    dir.toAbsolutePath());
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> isAcceptedExtension(p.getFileName().toString()))
                    .filter(p -> ZeroPayFileType.fromFilename(p.getFileName().toString()) != null)
                    .sorted()
                    .toList();
        }
    }

    @Override
    public void markProcessed(Path file) throws IOException {
        if (archiveDir == null || archiveDir.isBlank()) {
            return; // archiving disabled — idempotent upserts make re-ingest safe
        }
        if (file == null || !Files.exists(file)) {
            return; // already moved/removed — idempotent
        }
        Path target = Paths.get(archiveDir, LocalDate.now().toString());
        Files.createDirectories(target);
        Path dest = target.resolve(file.getFileName());
        Files.move(file, dest, StandardCopyOption.REPLACE_EXISTING);
        log.info("LocalDirectoryFileSource: archived {} -> {}", file.getFileName(), dest.toAbsolutePath());
    }

    @Override
    public String describe() {
        return "local-dir[inbound=" + Paths.get(inboundDir).toAbsolutePath()
                + (archiveDir == null || archiveDir.isBlank() ? ", archive=disabled" : ", archive=" + archiveDir)
                + "]";
    }

    private static boolean isAcceptedExtension(String filename) {
        String lower = filename.toLowerCase();
        return ACCEPTED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}
