package com.gme.sim.scheme.model;

import org.springframework.stereotype.Component;

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
 */
@Component
public class MerchantFeedStore {

    /** per-merchant list of events, ordered by append time (and seq). */
    private final ConcurrentHashMap<String, List<PaymentFeedEvent>> feeds =
            new ConcurrentHashMap<>();

    /** per-merchant sequence counter. */
    private final ConcurrentHashMap<String, AtomicLong> seqCounters =
            new ConcurrentHashMap<>();

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
