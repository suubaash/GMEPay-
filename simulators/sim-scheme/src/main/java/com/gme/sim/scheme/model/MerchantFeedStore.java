package com.gme.sim.scheme.model;

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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Thread-safe, append-only payment-notification feed keyed by merchantId.
 * <p>
 * Each merchant gets its own monotonic sequence counter starting at 1.
 * <p>
 * Backed by an append-only JSONL file (feed.jsonl) so the feed survives a
 * process restart. Each {@link #append} writes one {@link PersistedFeedEvent}
 * line (merchantId + event). On startup {@link #load()} replays events into
 * {@code feeds} and restores each merchant's seq counter to the max seq seen,
 * so newly-appended events never collide with persisted ones.
 * File IO failures are logged but never break the sim.
 */
@Component
public class MerchantFeedStore {

    private static final Logger log = LoggerFactory.getLogger(MerchantFeedStore.class);

    /** per-merchant list of events, ordered by append time (and seq). */
    private final ConcurrentHashMap<String, List<PaymentFeedEvent>> feeds =
            new ConcurrentHashMap<>();

    /** per-merchant sequence counter. */
    private final ConcurrentHashMap<String, AtomicLong> seqCounters =
            new ConcurrentHashMap<>();

    private final ObjectMapper mapper;
    private final Path dataDir;
    private final Path feedFile;

    /** JSONL wrapper: which merchant a persisted feed event belongs to. */
    public record PersistedFeedEvent(String merchantId, PaymentFeedEvent event) {}

    public MerchantFeedStore(ObjectMapper mapper,
                             @Value("${gmepay.sim.scheme.data-dir:data/sim-scheme}") String dataDir) {
        this.mapper = mapper;
        this.dataDir = Path.of(dataDir);
        this.feedFile = this.dataDir.resolve("feed.jsonl");
    }

    @PostConstruct
    void load() {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            log.warn("Could not create data dir {} — running memory-only: {}", dataDir, e.toString());
            return;
        }
        if (Files.exists(feedFile)) {
            try {
                for (String line : Files.readAllLines(feedFile, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    PersistedFeedEvent p = mapper.readValue(line, PersistedFeedEvent.class);
                    feeds.computeIfAbsent(p.merchantId(), k ->
                            Collections.synchronizedList(new ArrayList<>())).add(p.event());
                    // Restore seq counter to the max seq seen for this merchant.
                    AtomicLong counter = seqCounters.computeIfAbsent(
                            p.merchantId(), k -> new AtomicLong(0));
                    if (p.event().seq() > counter.get()) {
                        counter.set(p.event().seq());
                    }
                }
            } catch (IOException e) {
                log.warn("Could not read {}: {}", feedFile, e.toString());
            }
        }
        log.info("Loaded feed for {} merchants from {}", feeds.size(), dataDir);
    }

    private void append(String merchantId, PaymentFeedEvent event) {
        try (BufferedWriter w = Files.newBufferedWriter(feedFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(mapper.writeValueAsString(new PersistedFeedEvent(merchantId, event)));
            w.newLine();
        } catch (IOException e) {
            log.warn("Could not persist to {}: {}", feedFile, e.toString());
        }
    }

    /**
     * Append an event to the merchant's feed.
     *
     * @param merchantId  owner of the feed
     * @param authId      AUTH-... id
     * @param schemeTxnRef TXN-... or null
     * @param status      "APPROVED" | "CAPTURED" | "REFUNDED"
     * @param event       the fully-built event (seq is assigned here, not by caller)
     */
    public PaymentFeedEvent append(String merchantId,
                                   String authId,
                                   String schemeTxnRef,
                                   String status,
                                   java.math.BigDecimal amount,
                                   String currency,
                                   String payerRef,
                                   String at) {
        // Ensure structures exist
        seqCounters.computeIfAbsent(merchantId, k -> new AtomicLong(0));
        feeds.computeIfAbsent(merchantId, k ->
                Collections.synchronizedList(new ArrayList<>()));

        long seq = seqCounters.get(merchantId).incrementAndGet();
        PaymentFeedEvent event = new PaymentFeedEvent(
                seq, authId, schemeTxnRef, status, amount, currency, payerRef, at);
        feeds.get(merchantId).add(event);
        append(merchantId, event);  // persist after building
        return event;
    }

    /**
     * Return events with seq strictly greater than {@code since}, ascending.
     * Returns an empty list (not null) when no events exist.
     */
    public List<PaymentFeedEvent> since(String merchantId, long since) {
        List<PaymentFeedEvent> list = feeds.getOrDefault(merchantId,
                Collections.emptyList());
        return list.stream()
                .filter(e -> e.seq() > since)
                .collect(Collectors.toList());
    }

    /**
     * Latest seq for this merchant; 0 if no events yet.
     */
    public long latestSeq(String merchantId) {
        AtomicLong counter = seqCounters.get(merchantId);
        return counter == null ? 0L : counter.get();
    }
}
