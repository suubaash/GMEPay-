package com.gme.pay.settlement.alert;

import com.gme.pay.contracts.events.OpsAlertPayload;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.settlement.recon.ReconLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Emits a {@link OpsAlertPayload} of alertType {@code RECON_BREAK} whenever a reconciliation run
 * (scheduled or operator-triggered) leaves open exceptions — the ops "reconciliation-break" alert.
 *
 * <p>Publishing goes through the {@link EventPublisher} seam ({@link #ALERT_PUBLISHER_BEAN}): the Kafka
 * transport (topic {@code gmepay.ops.alert}, derived from {@code eventType="ops.alert"}) when a broker is
 * configured, otherwise the log-only fallback — so local/test boots need no broker. When the run is clean
 * (no exceptions) nothing is emitted.
 *
 * <p><b>Severity</b> is derived from the break's blast radius:
 * <ul>
 *   <li>{@code CRITICAL} — any MISSING line (a whole merchant absent from one side) or total break amount
 *       ≥ {@link #CRITICAL_AMOUNT_KRW}</li>
 *   <li>{@code WARN} — ≥ {@link #WARN_COUNT} discrepancy lines or total break amount ≥ {@link #WARN_AMOUNT_KRW}</li>
 *   <li>{@code INFO} — otherwise (a small handful of low-value discrepancies)</li>
 * </ul>
 * Money context rides in {@code detail} as decimal strings per {@code docs/MONEY_CONVENTION.md}.
 */
@Component
public class ReconBreakAlerter {

    /** alertType for the reconciliation-break condition (per {@link OpsAlertPayload} contract). */
    public static final String ALERT_TYPE = "RECON_BREAK";

    /** Bean name for the alert transport (Kafka-or-logging), wired in {@link ReconAlertConfig}. */
    public static final String ALERT_PUBLISHER_BEAN = "opsAlertPublisher";

    static final int WARN_COUNT = 5;
    static final BigDecimal WARN_AMOUNT_KRW = new BigDecimal("1000000");     // ₩1,000,000
    static final BigDecimal CRITICAL_AMOUNT_KRW = new BigDecimal("10000000"); // ₩10,000,000

    private static final Logger log = LoggerFactory.getLogger(ReconBreakAlerter.class);

    private final EventPublisher publisher;

    public ReconBreakAlerter(@Qualifier(ALERT_PUBLISHER_BEAN) EventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Raise a {@code RECON_BREAK} alert for the given batch if the recon result holds any exception line.
     * A clean run (all MATCHED) emits nothing.
     *
     * @param batchId  the settlement batch id the alert concerns (becomes {@code subjectRef})
     * @param allLines the full recon result (MATCHED + exception lines) from the diff engine
     */
    public void alertOnBreak(String batchId, List<ReconLine> allLines) {
        List<ReconLine> breaks = allLines.stream().filter(ReconLine::requiresAttention).toList();
        if (breaks.isEmpty()) {
            return;   // clean batch — no alert
        }

        long missing = breaks.stream().filter(l -> l.matchStatus().name().startsWith("MISSING")).count();
        BigDecimal totalBreak = breaks.stream()
                .map(l -> l.discrepancyAmount() != null ? l.discrepancyAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String severity = severityFor(breaks.size(), missing, totalBreak);
        String detail = String.format(
                "reconciliation break on batch %s: %d exception line(s) (%d missing), total break ₩%s",
                batchId, breaks.size(), missing, totalBreak.toPlainString());

        OpsAlertPayload payload = new OpsAlertPayload(
                OpsAlertPayload.EVENT_TYPE,
                ALERT_TYPE,
                severity,
                batchId,
                detail,
                Instant.now().toString());

        publisher.publish(new ReconAlertEvent(payload));
        log.info("RECON_BREAK alert emitted: batchId={} severity={} breaks={} total=₩{}",
                batchId, severity, breaks.size(), totalBreak.toPlainString());
    }

    private static String severityFor(int breakCount, long missing, BigDecimal totalBreak) {
        if (missing > 0 || totalBreak.compareTo(CRITICAL_AMOUNT_KRW) >= 0) {
            return "CRITICAL";
        }
        if (breakCount >= WARN_COUNT || totalBreak.compareTo(WARN_AMOUNT_KRW) >= 0) {
            return "WARN";
        }
        return "INFO";
    }
}
