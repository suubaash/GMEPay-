package com.gme.sim.wallet.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe receipt store, backed by an append-only JSONL file so payment
 * receipts survive process restarts.
 *
 * Persistence: each save appends one JSON line to receipts.jsonl. On startup
 * the file is replayed into memory (last line per receipt id wins). File IO
 * failures are logged but never break the sim — memory stays authoritative.
 */
@Component
public class ReceiptStore {

    private static final Logger log = LoggerFactory.getLogger(ReceiptStore.class);

    private final ConcurrentHashMap<String, Receipt> receipts = new ConcurrentHashMap<>();

    private final ObjectMapper mapper;
    private final Path dataDir;
    private final Path receiptsFile;

    public ReceiptStore(ObjectMapper mapper,
                        @Value("${gmepay.sim.wallet.data-dir:data/sim-wallet}") String dataDir) {
        this.mapper = mapper;
        this.dataDir = Path.of(dataDir);
        this.receiptsFile = this.dataDir.resolve("receipts.jsonl");
    }

    @PostConstruct
    void load() {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            log.warn("Could not create data dir {} — running memory-only: {}", dataDir, e.toString());
            return;
        }
        if (Files.exists(receiptsFile)) {
            try {
                for (String line : Files.readAllLines(receiptsFile, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    Receipt r = mapper.readValue(line, Receipt.class);
                    receipts.put(r.id(), r);  // last line per id wins
                }
            } catch (IOException e) {
                log.warn("Could not read {}: {}", receiptsFile, e.toString());
            }
        }
        log.info("Loaded {} receipts from {}", receipts.size(), dataDir);
    }

    private void append(Path file, Object value) {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(mapper.writeValueAsString(value));
            w.newLine();
        } catch (IOException e) {
            log.warn("Could not persist to {}: {}", file, e.toString());
        }
    }

    public void save(Receipt receipt) {
        receipts.put(receipt.id(), receipt);
        append(receiptsFile, receipt);
    }

    public Receipt get(String id) {
        return receipts.get(id);
    }
}
