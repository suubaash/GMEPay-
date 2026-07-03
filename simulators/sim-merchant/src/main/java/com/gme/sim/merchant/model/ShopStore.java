package com.gme.sim.merchant.model;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory store for registered shops, backed by an append-only
 * JSONL file so shops survive process restarts.
 *
 * Persistence: each save appends one JSON line to shops.jsonl. On startup the
 * file is replayed into memory, keeping the LAST line per merchantId (so later
 * updates win). File IO failures are logged but never break the sim — memory
 * stays authoritative.
 */
@Component
public class ShopStore {

    private static final Logger log = LoggerFactory.getLogger(ShopStore.class);

    private final Map<String, ShopRecord> shops = new ConcurrentHashMap<>();

    private final ObjectMapper mapper;
    private final Path dataDir;
    private final Path shopsFile;

    public ShopStore(ObjectMapper mapper,
                     @Value("${gmepay.sim.merchant.data-dir:data/sim-merchant}") String dataDir) {
        this.mapper = mapper;
        this.dataDir = Path.of(dataDir);
        this.shopsFile = this.dataDir.resolve("shops.jsonl");
    }

    @PostConstruct
    void load() {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            log.warn("Could not create data dir {} — running memory-only: {}", dataDir, e.toString());
            return;
        }
        if (Files.exists(shopsFile)) {
            try {
                for (String line : Files.readAllLines(shopsFile, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    ShopRecord s = mapper.readValue(line, ShopRecord.class);
                    shops.put(s.merchantId(), s);  // last line per merchantId wins
                }
            } catch (IOException e) {
                log.warn("Could not read {}: {}", shopsFile, e.toString());
            }
        }
        log.info("Loaded {} shops from {}", shops.size(), dataDir);
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

    public void save(ShopRecord shop) {
        shops.put(shop.merchantId(), shop);
        append(shopsFile, shop);  // append; load() keeps last line per merchantId
    }

    public Optional<ShopRecord> find(String merchantId) {
        return Optional.ofNullable(shops.get(merchantId));
    }

    public List<ShopRecord> findAll() {
        return new ArrayList<>(shops.values());
    }
}
