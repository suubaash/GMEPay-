package com.gme.pay.audit;

import java.util.HexFormat;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link AuditPublisher} that emits each event at {@code INFO} level. Used
 * everywhere Kafka is not configured: local dev, unit slices, the entire Slice 1
 * deployment (Kafka turns on for audit in Slice 8 with the cold MinIO sink).
 *
 * <p>Hash bytes are rendered in lower-case hex (32 bytes → 64 hex chars) so logs are
 * grep-able for chain verification spot-checks (e.g.{@code grep aggregateId=42
 * audit.log | awk '{print $rowHash}'}).
 *
 * <p>Kept deliberately free of Spring annotations — {@code lib-audit} is a contracts
 * library, so wiring this as a {@code @Bean} is the consuming service's call (typically
 * guarded by {@code @ConditionalOnMissingBean(AuditPublisher.class)} so {@link
 * KafkaAuditPublisher} can take over when present).
 */
public final class LogAuditPublisher implements AuditPublisher {

    private static final Logger log = LoggerFactory.getLogger(LogAuditPublisher.class);
    private static final HexFormat HEX = HexFormat.of();

    @Override
    public void publish(AuditEvent event) {
        Objects.requireNonNull(event, "event");
        log.info(
                "audit: aggregateType={} aggregateId={} eventType={} actorId={} actorIp={} "
                        + "recordedAt={} prevHash={} rowHash={}",
                event.aggregateType(),
                event.aggregateId(),
                event.eventType(),
                event.actorId(),
                event.actorIp(),
                event.recordedAt(),
                event.prevHash() == null ? "(genesis)" : HEX.formatHex(event.prevHash()),
                event.rowHash() == null ? "(unsealed)" : HEX.formatHex(event.rowHash()));
    }
}
