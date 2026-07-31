package com.gme.pay.payment.alert;

import com.gme.pay.contracts.events.OpsAlertPayload;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.payment.persistence.OpsAlertArchive;
import com.gme.pay.payment.persistence.OpsAlertEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * The single fan-out for every ops alert raised inside payment-executor (gap <b>T3-3</b>).
 *
 * <p>Three independent legs, in this order and each individually guarded so no leg can take down
 * another (or the payment that triggered the alert):
 *
 * <ol>
 *   <li><b>Persist</b> — {@link OpsAlertArchive#record} writes the alert to {@code ops_alerts}
 *       <em>first</em>, so the evidence exists before anything that can fail over the network is
 *       attempted, and survives a restart of this service, the broker and the BFF.</li>
 *   <li><b>Publish</b> — onto the existing {@link EventPublisher} seam ({@code ops.alert} →
 *       {@code gmepay.ops.alert}) so the platform's aggregate consumer (ops-partner-bff's control
 *       tower) still sees it wherever a broker IS wired.</li>
 *   <li><b>Notify</b> — {@link AlertSink#deliver}, the direct path to a human; the outcome is stamped
 *       back onto the persisted row so "did anyone find out?" is answerable in SQL.</li>
 * </ol>
 *
 * <p><b>Why the sink is not just the publisher.</b> payment-executor's {@code EventPublisher} is
 * {@code LogEventPublisher} — this service has no {@code lib-events-kafka} on its classpath — so leg 2
 * currently terminates in a log line, and adding a Kafka publisher here would also start emitting
 * {@code payment.approved} from a second producer (a money-path change, register item T2-5). Leg 3 is
 * therefore the only end-to-end path from a decline spike to an on-call human today.
 *
 * <p><b>Two constructors.</b> The Spring one takes the archive + sink; the {@code EventPublisher}-only
 * one is for unit tests and keeps the publish-only behaviour the monitors were originally written
 * against. Per the platform convention for multi-constructor {@code @Component}s, the production
 * constructor carries {@code @Autowired} explicitly.
 */
@Component
public class OpsAlertPipeline {

    private static final Logger log = LoggerFactory.getLogger(OpsAlertPipeline.class);

    private final EventPublisher eventPublisher;
    @Nullable private final OpsAlertArchive archive;
    private final AlertSink sink;

    /** Production wiring: persist → publish → notify. */
    @org.springframework.beans.factory.annotation.Autowired
    public OpsAlertPipeline(EventPublisher eventPublisher, OpsAlertArchive archive, AlertSink sink) {
        this.eventPublisher = eventPublisher;
        this.archive = archive;
        this.sink = sink;
    }

    /**
     * Publish-only pipeline (no durable archive, log-only sink) — used by unit tests and by any caller
     * that only has an {@link EventPublisher}.
     */
    public OpsAlertPipeline(EventPublisher eventPublisher) {
        this(eventPublisher, null, new LogAlertSink());
    }

    /**
     * Run one alert through all three legs. Never throws: this is called from inside the pay path.
     */
    public void emit(OpsAlertPayload alert) {
        Long rowId = archive == null ? null : archive.record(alert);
        publish(alert);
        notifySink(alert, rowId);
    }

    private void publish(OpsAlertPayload alert) {
        try {
            eventPublisher.publish(new OpsAlertEvent(alert));
            log.warn("published {} ops alert: severity={} subject={} {}",
                    alert.alertType(), alert.severity(), alert.subjectRef(), alert.detail());
        } catch (RuntimeException e) {
            // Alerting must never break the pay path; a broker publisher may throw.
            log.error("failed to publish {} ops alert ({}): {}",
                    alert.alertType(), alert.detail(), e.getMessage(), e);
        }
    }

    private void notifySink(OpsAlertPayload alert, @Nullable Long rowId) {
        AlertDelivery outcome;
        try {
            outcome = sink.deliver(alert);
            if (outcome == null) {
                outcome = AlertDelivery.failed("unknown", "sink returned null");
            }
        } catch (RuntimeException e) {
            // The port contract says never throw, but a third-party sink is still a third party.
            log.error("ops alert notification sink threw for {} subject={}: {}",
                    alert.alertType(), alert.subjectRef(), e.toString());
            outcome = AlertDelivery.failed("unknown", e.toString());
        }
        if (archive != null) {
            archive.recordNotification(rowId, statusColumn(outcome), outcome.channel(),
                    outcome.error());
        }
    }

    private static String statusColumn(AlertDelivery outcome) {
        return switch (outcome.status()) {
            case DELIVERED -> OpsAlertEntity.NOTIFY_DELIVERED;
            case FAILED -> OpsAlertEntity.NOTIFY_FAILED;
            case SKIPPED -> OpsAlertEntity.NOTIFY_SKIPPED;
        };
    }
}
