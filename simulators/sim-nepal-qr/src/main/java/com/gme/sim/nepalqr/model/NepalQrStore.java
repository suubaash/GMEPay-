package com.gme.sim.nepalqr.model;

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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory store, backed by append-only JSONL files so records
 * survive process restarts.
 *   - records   : every inbound request/response (append order preserved)
 *   - txns      : transactions created by /pay/, keyed by unique reference
 *
 * This is the whole point of the mock: whenever GMEPay+ calls to create a txn,
 * the request + response are stored and can be inspected.
 *
 * Persistence: each save appends one JSON line to records.jsonl / txns.jsonl.
 * On startup the files are replayed into memory. txns replay keeps the LAST line
 * per reference (so state changes like REVERSED win). File IO failures are logged
 * but never break the sim — memory stays authoritative.
 */
@Component
public class NepalQrStore {

    private static final Logger log = LoggerFactory.getLogger(NepalQrStore.class);

    private final CopyOnWriteArrayList<SimRecord> records = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, TxnRecord> txnsByReference = new ConcurrentHashMap<>();

    private final ObjectMapper mapper;
    private final Path dataDir;
    private final Path recordsFile;
    private final Path txnsFile;

    public NepalQrStore(ObjectMapper mapper,
                        @Value("${gmepay.sim.nepalqr.data-dir:data/sim-nepal-qr}") String dataDir) {
        this.mapper = mapper;
        this.dataDir = Path.of(dataDir);
        this.recordsFile = this.dataDir.resolve("records.jsonl");
        this.txnsFile = this.dataDir.resolve("txns.jsonl");
    }

    @PostConstruct
    void load() {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            log.warn("Could not create data dir {} — running memory-only: {}", dataDir, e.toString());
            return;
        }
        if (Files.exists(recordsFile)) {
            try {
                for (String line : Files.readAllLines(recordsFile, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) records.add(mapper.readValue(line, SimRecord.class));
                }
            } catch (IOException e) {
                log.warn("Could not read {}: {}", recordsFile, e.toString());
            }
        }
        if (Files.exists(txnsFile)) {
            try {
                for (String line : Files.readAllLines(txnsFile, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    TxnRecord t = mapper.readValue(line, TxnRecord.class);
                    txnsByReference.put(t.reference, t);  // last line per reference wins
                }
            } catch (IOException e) {
                log.warn("Could not read {}: {}", txnsFile, e.toString());
            }
        }
        log.info("Loaded {} records and {} txns from {}", records.size(), txnsByReference.size(), dataDir);
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

    // --- Records ---

    public void save(SimRecord r) {
        records.add(r);
        append(recordsFile, r);
    }

    /** Newest-first, optionally filtered by reference. */
    public List<SimRecord> records(String referenceFilter) {
        return records(referenceFilter, null);
    }

    /** Newest-first, optionally filtered by reference and/or endpoint. */
    public List<SimRecord> records(String referenceFilter, String endpointFilter) {
        List<SimRecord> out = new ArrayList<>(records);
        java.util.Collections.reverse(out);
        if (referenceFilter != null && !referenceFilter.isBlank()) {
            out.removeIf(r -> !referenceFilter.equals(r.reference));
        }
        if (endpointFilter != null && !endpointFilter.isBlank()) {
            out.removeIf(r -> !endpointFilter.equals(r.endpoint));
        }
        return out;
    }

    public Optional<SimRecord> findRecord(String id) {
        return records.stream().filter(r -> id.equals(r.id)).findFirst();
    }

    // --- Transactions (dedup on reference) ---

    /** @return true if the reference has already been used to create a txn. */
    public boolean referenceExists(String reference) {
        return txnsByReference.containsKey(reference);
    }

    public void saveTxn(TxnRecord txn) {
        txnsByReference.put(txn.reference, txn);
        append(txnsFile, txn);  // append; load() keeps last line per reference
    }

    public Optional<TxnRecord> findTxn(String reference) {
        return Optional.ofNullable(txnsByReference.get(reference));
    }
}
