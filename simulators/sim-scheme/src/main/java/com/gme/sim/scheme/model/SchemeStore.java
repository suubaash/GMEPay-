package com.gme.sim.scheme.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.sim.scheme.config.SchemeConfig;
import com.gme.sim.scheme.config.SchemeProfile;
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
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory store for merchants, CPM tokens, and payments.
 * Seeds two demo merchants on startup.
 * <p>
 * Merchants and payments are backed by append-only JSONL files so runtime-created
 * records survive a process restart:
 *   - merchants.jsonl : one line per {@link #saveMerchant} (last line per merchantId wins)
 *   - payments.jsonl  : one line per {@link #savePayment} (last line per authId wins)
 * CPM tokens are ephemeral auth tokens and are intentionally NOT persisted.
 * <p>
 * Seed vs runtime: {@link #seedDemoMerchants} uses {@link #putMerchant} (map only,
 * no disk write) so the file never accumulates duplicate demo merchants across boots.
 * After seeding, {@link #load()} replays persisted data so it overrides seed defaults.
 * File IO failures are logged but never break the sim — memory stays authoritative.
 */
@Component
public class SchemeStore {

    private static final Logger log = LoggerFactory.getLogger(SchemeStore.class);

    private final SchemeConfig schemeConfig;

    private final ConcurrentHashMap<String, MerchantRecord>  merchants = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CpmTokenRecord>  cpmTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PaymentRecord>   payments  = new ConcurrentHashMap<>();

    private final ObjectMapper mapper;
    private final Path dataDir;
    private final Path merchantsFile;
    private final Path paymentsFile;

    public SchemeStore(SchemeConfig schemeConfig,
                       ObjectMapper mapper,
                       @Value("${gmepay.sim.scheme.data-dir:data/sim-scheme}") String dataDir) {
        this.schemeConfig = schemeConfig;
        this.mapper = mapper;
        this.dataDir = Path.of(dataDir);
        this.merchantsFile = this.dataDir.resolve("merchants.jsonl");
        this.paymentsFile = this.dataDir.resolve("payments.jsonl");
    }

    /**
     * Seed demo merchants, THEN replay persisted data. Both run inside one
     * @PostConstruct because @PostConstruct ordering across methods is not
     * guaranteed — load() must run after seeding so persisted records win.
     */
    @PostConstruct
    void init() {
        seedDemoMerchants();
        load();
    }

    void seedDemoMerchants() {
        SchemeProfile p = schemeConfig.getProfile();
        if (p == SchemeProfile.KHQR) {
            putMerchant(new MerchantRecord("KHQR-M001", "Angkor Coffee",    "Siem Reap",     "5812"));
            putMerchant(new MerchantRecord("KHQR-M002", "Phnom Penh Mart",  "Phnom Penh",    "5411"));
        } else if (p == SchemeProfile.ZEROPAY) {
            putMerchant(new MerchantRecord("ZP-M001", "Seoul Noodle House", "Seoul",         "5812"));
            putMerchant(new MerchantRecord("ZP-M002", "Busan Fish Market",  "Busan",         "5411"));
        }
    }

    // --- Persistence ---

    private void load() {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            log.warn("Could not create data dir {} — running memory-only: {}", dataDir, e.toString());
            return;
        }
        if (Files.exists(merchantsFile)) {
            try {
                for (String line : Files.readAllLines(merchantsFile, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    MerchantRecord m = mapper.readValue(line, MerchantRecord.class);
                    merchants.put(m.merchantId(), m);  // last line per merchantId wins, overrides seed
                }
            } catch (IOException e) {
                log.warn("Could not read {}: {}", merchantsFile, e.toString());
            }
        }
        if (Files.exists(paymentsFile)) {
            try {
                for (String line : Files.readAllLines(paymentsFile, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    PaymentRecord pay = mapper.readValue(line, PaymentRecord.class);
                    payments.put(pay.getAuthId(), pay);  // last line per authId wins
                }
            } catch (IOException e) {
                log.warn("Could not read {}: {}", paymentsFile, e.toString());
            }
        }
        log.info("Loaded {} merchants and {} payments from {}", merchants.size(), payments.size(), dataDir);
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

    // --- Merchants ---

    /** Map-only put (no disk write). Used for seeding so the file never accumulates demo merchants. */
    private void putMerchant(MerchantRecord merchant) {
        merchants.put(merchant.merchantId(), merchant);
    }

    /** Runtime save: map put + append to merchants.jsonl. */
    public void saveMerchant(MerchantRecord merchant) {
        merchants.put(merchant.merchantId(), merchant);
        append(merchantsFile, merchant);  // append; load() keeps last line per merchantId
    }

    public Optional<MerchantRecord> findMerchant(String merchantId) {
        return Optional.ofNullable(merchants.get(merchantId));
    }

    public Collection<MerchantRecord> allMerchants() {
        return merchants.values();
    }

    // --- CPM Tokens (ephemeral — not persisted) ---

    public void saveCpmToken(CpmTokenRecord token) {
        cpmTokens.put(token.token(), token);
    }

    public Optional<CpmTokenRecord> findCpmToken(String token) {
        return Optional.ofNullable(cpmTokens.get(token));
    }

    // --- Payments ---

    public void savePayment(PaymentRecord payment) {
        payments.put(payment.getAuthId(), payment);
        append(paymentsFile, payment);  // append; load() keeps last line per authId
    }

    public Optional<PaymentRecord> findPayment(String authId) {
        return Optional.ofNullable(payments.get(authId));
    }
}
