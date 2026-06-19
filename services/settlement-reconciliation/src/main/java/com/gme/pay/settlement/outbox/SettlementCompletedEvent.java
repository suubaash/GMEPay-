package com.gme.pay.settlement.outbox;

import com.gme.pay.events.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Emitted (via the outbox) when an outbound settlement batch reaches GENERATED — the signal that GME
 * has produced a ZeroPay settlement-instruction file for a window. Topic {@code gmepay.settlement.completed}
 * (KafkaEventPublisher derives it from {@link #eventType()}). {@code aggregateId} = batchId (must be
 * non-blank or KafkaEventPublisher rejects it). Downstream consumers (reporting, ops) read the event
 * fields from {@code payload.*} (the outbox drain nests them under a top-level {@code payload}).
 */
public record SettlementCompletedEvent(
        String batchId,
        String fileType,
        String settlementWindow,
        LocalDate businessDate,
        Character settlementType,
        BigDecimal netSettlementAmount,
        String totalCurrency,
        int transactionCount,
        String fileChecksum,
        Instant occurredAt) implements DomainEvent {

    /** Convenience constructor stamping {@code occurredAt} = now. */
    public SettlementCompletedEvent(String batchId, String fileType, String settlementWindow,
                                    LocalDate businessDate, Character settlementType,
                                    BigDecimal netSettlementAmount, String totalCurrency,
                                    int transactionCount, String fileChecksum) {
        this(batchId, fileType, settlementWindow, businessDate, settlementType, netSettlementAmount,
                totalCurrency, transactionCount, fileChecksum, Instant.now());
    }

    @Override
    public String eventType() {
        return "settlement.completed";
    }

    @Override
    public String aggregateId() {
        return batchId;
    }

    @Override
    public Instant occurredAt() {
        return occurredAt;
    }
}
