package com.gme.sim.gmeremit.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory store seeded with 3 demo users (₩500,000 each), backed by an append-only
 * JSONL file so mutating balances + transaction history survive a process restart.
 *
 * <p>Persistence: each user mutation appends one {@link UserSnapshot} JSON line to
 * users.jsonl. On startup the 3 users are seeded, then the file is replayed — the LAST
 * snapshot per userId wins and overrides the seed (restoring balance + history). File IO
 * failures are logged but never break the sim; memory stays authoritative.
 */
@Component
public class WalletStore {

    private static final Logger log = LoggerFactory.getLogger(WalletStore.class);

    private final Map<String, WalletUser> users = new LinkedHashMap<>();

    private final ObjectMapper mapper;
    private final Path dataDir;
    private final Path usersFile;

    public WalletStore(ObjectMapper mapper,
                       @Value("${gmepay.sim.gmeremit.data-dir:data/sim-gmeremit}") String dataDir) {
        this.mapper = mapper;
        this.dataDir = Path.of(dataDir);
        this.usersFile = this.dataDir.resolve("users.jsonl");
    }

    @PostConstruct
    void load() {
        seedAll();
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            log.warn("Could not create data dir {} — running memory-only: {}", dataDir, e.toString());
            return;
        }
        if (Files.exists(usersFile)) {
            try {
                int replayed = 0;
                for (String line : Files.readAllLines(usersFile, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    UserSnapshot s = mapper.readValue(line, UserSnapshot.class);
                    users.put(s.userId(), fromSnapshot(s));  // last snapshot per userId wins
                    replayed++;
                }
                log.info("Replayed {} user snapshots from {}", replayed, usersFile);
            } catch (IOException e) {
                log.warn("Could not read {}: {}", usersFile, e.toString());
            }
        }
    }

    private void seedAll() {
        users.clear();
        seed("user-001", "Alice Kim",   "500000");
        seed("user-002", "Bob Lee",     "500000");
        seed("user-003", "Chloe Park",  "500000");
    }

    private void seed(String id, String name, String balance) {
        users.put(id, new WalletUser(id, name, new BigDecimal(balance)));
    }

    private WalletUser fromSnapshot(UserSnapshot s) {
        WalletUser u = new WalletUser(s.userId(), s.name(), new BigDecimal(s.balanceKrw()));
        u.restore(new BigDecimal(s.balanceKrw()), s.transactions());
        return u;
    }

    public List<WalletUser> allUsers() {
        return List.copyOf(users.values());
    }

    public Optional<WalletUser> findUser(String userId) {
        return Optional.ofNullable(users.get(userId));
    }

    /** Persist the current state of a user. Call right after a mutation (e.g. {@code user.debit(...)}). */
    public void persist(WalletUser u) {
        UserSnapshot snapshot = new UserSnapshot(
                u.getUserId(), u.getName(), u.getBalanceKrw().toPlainString(), u.getTransactions());
        append(usersFile, snapshot);
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

    /** Re-seeds the store to its initial state (in-memory + clears the JSONL). Used by tests only. */
    public synchronized void resetForTest() {
        seedAll();
        try {
            Files.deleteIfExists(usersFile);
        } catch (IOException e) {
            log.warn("Could not clear {}: {}", usersFile, e.toString());
        }
    }

    /** Snapshot of a user's full mutable state, serialized one-per-line to users.jsonl. */
    record UserSnapshot(String userId, String name, String balanceKrw, List<WalletTransaction> transactions) {}
}
