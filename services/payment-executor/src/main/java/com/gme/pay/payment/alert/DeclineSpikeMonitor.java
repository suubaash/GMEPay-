package com.gme.pay.payment.alert;

import com.gme.pay.contracts.events.OpsAlertPayload;
import com.gme.pay.events.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ops-wave <b>DECLINE_SPIKE</b> monitor (defect #5). A lightweight in-memory rolling counter that
 * records the outcome (approved / declined) of each NEW authorization per subject (partner and/or
 * scheme ref) and, when the decline RATE over a short window rises above a configurable threshold,
 * emits a {@code DECLINE_SPIKE} {@link OpsAlertPayload} onto the {@link EventPublisher} seam
 * (topic {@code gmepay.ops.alert}). With no broker wired, the seam's {@code LogEventPublisher}
 * fallback logs the alert instead.
 *
 * <p><b>Default off.</b> Enable with {@code gmepay.decline-spike.enabled=true}. When the bean is
 * absent, callers see {@code null} and skip recording (they null-guard the collaborator), so this is
 * purely additive and off by default.
 *
 * <p><b>Window + guards.</b> Outcomes older than {@code window-seconds} (default 60s) are evicted per
 * subject. An alert fires only when the subject has at least {@code min-samples} (default 20)
 * outcomes in-window AND the decline rate strictly exceeds {@code threshold-rate} (default 0.5).
 * The min-samples guard stops a single early decline from tripping a 100% rate. After firing, a
 * per-subject cooldown ({@code cooldown-seconds}, default 300s) suppresses repeats so one spike does
 * not spam the topic. Alerting never throws into the pay path.
 */
@Component
@ConditionalOnProperty(name = "gmepay.decline-spike.enabled", havingValue = "true")
public class DeclineSpikeMonitor {

    private static final Logger log = LoggerFactory.getLogger(DeclineSpikeMonitor.class);

    public static final String ALERT_TYPE = "DECLINE_SPIKE";

    static final String SEV_WARN = "WARN";
    static final String SEV_CRITICAL = "CRITICAL";

    private final EventPublisher eventPublisher;
    private final Clock clock;
    private final Duration window;
    private final int minSamples;
    private final double thresholdRate;
    private final Duration cooldown;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public DeclineSpikeMonitor(EventPublisher eventPublisher,
                               Clock clock,
                               @Value("${gmepay.decline-spike.window-seconds:60}") long windowSeconds,
                               @Value("${gmepay.decline-spike.min-samples:20}") int minSamples,
                               @Value("${gmepay.decline-spike.threshold-rate:0.5}") double thresholdRate,
                               @Value("${gmepay.decline-spike.cooldown-seconds:300}") long cooldownSeconds) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.clock = Objects.requireNonNull(clock);
        this.window = Duration.ofSeconds(windowSeconds > 0 ? windowSeconds : 60L);
        this.minSamples = minSamples > 0 ? minSamples : 20;
        this.thresholdRate = (thresholdRate > 0 && thresholdRate <= 1) ? thresholdRate : 0.5;
        this.cooldown = Duration.ofSeconds(cooldownSeconds >= 0 ? cooldownSeconds : 300L);
    }

    /**
     * Record the outcome of one NEW authorization for a subject (a partner code and/or scheme id).
     * {@code null}/blank refs are skipped. Each non-null ref is tracked independently so a spike is
     * attributed to whichever dimension is failing.
     */
    public void record(String partnerRef, String schemeRef, boolean approved) {
        recordSubject(partnerRef, approved);
        recordSubject(schemeRef, approved);
    }

    private void recordSubject(String subjectRef, boolean approved) {
        if (subjectRef == null || subjectRef.isBlank()) {
            return;
        }
        Instant now = Instant.now(clock);
        Window w = windows.computeIfAbsent(subjectRef, k -> new Window());
        OpsAlertPayload alert;
        synchronized (w) {
            w.add(approved, now, window);
            alert = w.evaluate(subjectRef, now);
        }
        if (alert != null) {
            publish(alert);
        }
    }

    private void publish(OpsAlertPayload alert) {
        try {
            eventPublisher.publish(new OpsAlertEvent(alert));
            log.warn("published DECLINE_SPIKE ops alert: severity={} subject={} {}",
                    alert.severity(), alert.subjectRef(), alert.detail());
        } catch (RuntimeException e) {
            // Alerting must never break the pay path; the broker publisher may throw.
            log.error("failed to publish DECLINE_SPIKE ops alert ({}): {}", alert.detail(), e.getMessage(), e);
        }
    }

    /** Per-subject rolling window of recent outcomes + last-alert timestamp for cooldown. */
    private final class Window {
        private final Deque<Sample> samples = new ArrayDeque<>();
        private long declines;
        private Instant lastAlertAt;

        void add(boolean approved, Instant now, Duration windowLen) {
            samples.addLast(new Sample(now, approved));
            if (!approved) {
                declines++;
            }
            Instant cutoff = now.minus(windowLen);
            while (!samples.isEmpty() && samples.peekFirst().at().isBefore(cutoff)) {
                Sample old = samples.removeFirst();
                if (!old.approved()) {
                    declines--;
                }
            }
        }

        OpsAlertPayload evaluate(String subjectRef, Instant now) {
            int total = samples.size();
            if (total < minSamples) {
                return null;
            }
            double rate = (double) declines / total;
            if (rate <= thresholdRate) {
                return null;
            }
            if (lastAlertAt != null && lastAlertAt.plus(cooldown).isAfter(now)) {
                return null; // still in cooldown
            }
            lastAlertAt = now;
            String severity = rate >= 0.8 ? SEV_CRITICAL : SEV_WARN;
            String detail = String.format(
                    "declineRate=%.2f (%d/%d) over %ds > threshold=%.2f",
                    rate, declines, total, window.toSeconds(), thresholdRate);
            return new OpsAlertPayload(
                    OpsAlertPayload.EVENT_TYPE,
                    ALERT_TYPE,
                    severity,
                    subjectRef,
                    detail,
                    DateTimeFormatter.ISO_INSTANT.format(now));
        }
    }

    private record Sample(Instant at, boolean approved) {
    }
}
