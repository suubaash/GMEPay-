package com.gme.pay.contracts.events;

import java.util.List;
import java.util.Set;

/**
 * The authoritative catalogue of every domain event on the GMEPay+ bus (CTO gate, checklist §4/§7):
 * event type → topic, schema version, producing services, consuming services. Adding a producer
 * without declaring its consumers here — or declaring a MONEY-TERMINAL event with no consumer —
 * fails {@code EventCatalogTest}, so "an event nothing receives" can no longer ship silently
 * (the failure mode that left settlement.completed dark until 2026-07-05).
 *
 * <p>Topic naming: {@code gmepay.<eventType>} (lib-events-kafka's {@code TOPIC_PREFIX}). The wire
 * envelope carries {@code schemaVersion} (stamped by {@code KafkaEventPublisher}); bump an entry's
 * version when a field changes meaning or is removed — additive fields do not need a bump.
 */
public final class EventCatalog {

    private EventCatalog() {}

    /**
     * @param moneyTerminal a terminal money movement (approval/reversal/settlement) — MUST have
     *                      at least one consumer or the contract test fails the build
     */
    public record EventEntry(
            String eventType,
            int schemaVersion,
            boolean moneyTerminal,
            List<String> producers,
            List<String> consumers) {

        public String topic() {
            return "gmepay." + eventType;
        }
    }

    public static final List<EventEntry> EVENTS = List.of(
            new EventEntry("payment.approved", 1, true,
                    List.of("transaction-mgmt"),
                    List.of("revenue-ledger", "notification-webhook")),
            new EventEntry("payment.reversed", 1, true,
                    List.of("transaction-mgmt"),
                    List.of("revenue-ledger", "prefunding")),
            new EventEntry("settlement.completed", 1, true,
                    List.of("settlement-reconciliation"),
                    List.of("ops-partner-bff")),
            new EventEntry("ops.alert", 1, false,
                    List.of("payment-executor", "prefunding", "transaction-mgmt",
                            "settlement-reconciliation", "notification-webhook"),
                    List.of("ops-partner-bff")),
            new EventEntry("ops.alert.ack", 1, false,
                    List.of("ops-partner-bff"),
                    // Acknowledgement audit trail: recorded, intentionally no live consumer yet.
                    List.of()),
            new EventEntry("prefunding.alert", 1, false,
                    List.of("prefunding"),
                    // Low-balance alert; superseded by ops.alert FLOAT_LOW — consumer intentionally
                    // pending until the legacy emitter is retired (tracked in CHECKLIST.md §2).
                    List.of()));

    /** Event types every producer in the fleet is allowed to emit. */
    public static Set<String> knownEventTypes() {
        return Set.copyOf(EVENTS.stream().map(EventEntry::eventType).toList());
    }
}
