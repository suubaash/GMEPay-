package com.gme.pay.settlement.runlog;

import com.gme.pay.contracts.events.OpsAlertPayload;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.settlement.alert.ReconAlertEvent;
import com.gme.pay.settlement.alert.ReconBreakAlerter;
import com.gme.pay.settlement.calendar.BusinessCalendar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raises the two batch-operations ops alerts (gap <b>T3-4</b>) through the pipeline that already exists —
 * it does <b>not</b> add a second notification path.
 *
 * <h2>Which pipeline, and why this one</h2>
 * <p>This service already publishes {@code RECON_BREAK} through the {@code opsAlertPublisher} seam
 * ({@link ReconBreakAlerter#ALERT_PUBLISHER_BEAN}, wired in
 * {@link com.gme.pay.settlement.alert.ReconAlertConfig}): the {@code KafkaEventPublisher} on topic
 * {@code gmepay.ops.alert} when a broker is configured, the log-only fallback otherwise. Downstream, that
 * topic is consumed into ops-partner-bff's alert store and out through its {@code PagingPort} /
 * {@code WebhookPagingAdapter} — the same idiom as payment-executor's {@code AlertSink} /
 * {@code WebhookAlertSink} pair added for T3-3. So a batch failure now lands wherever the operator already
 * pointed their pager, with no new URL to configure and no second payload shape to template against.
 *
 * <p>Consequently there is deliberately no {@code AlertSink} interface in this service: introducing one
 * here would be the second notification path the brief warns against. What T3-3 established is reused as
 * the transport; this class only decides <em>when</em> and <em>with what severity</em>.
 *
 * <h2>The two alert types</h2>
 * <table border="1">
 *   <caption>batch-ops alertTypes on {@code gmepay.ops.alert}</caption>
 *   <tr><th>alertType</th><th>severity</th><th>meaning</th></tr>
 *   <tr><td>{@code BATCH_RUN_FAILED}</td><td>CRITICAL</td>
 *       <td>A settlement-generation or recon window threw. The run's transaction rolled back, so the
 *           {@code batch_runs} row (written separately) is the only evidence — the alert carries its id.</td></tr>
 *   <tr><td>{@code BATCH_CALENDAR_UNVERIFIED}</td><td>WARN</td>
 *       <td>A window ran on a date no configured business-day calendar covers. Not a failure — a statement
 *           that the holiday check was vacuous. Raised at most ONCE per business date (see below).</td></tr>
 * </table>
 *
 * <h2>Why the calendar alert is de-duplicated</h2>
 * <p>Eight windows a day would otherwise raise eight identical WARNs every day forever until somebody
 * populates the calendar, and an alert stream that always contains the same eight lines is an alert stream
 * nobody reads — which would defeat the purpose. One per business date is enough to be undeniable. The
 * durable record is complete regardless: EVERY {@code batch_runs} row carries its own
 * {@code calendar_verdict}, so per-run evidence is never lost to the de-duplication.
 *
 * <p><b>Never throws.</b> Alerting is the reporting path for a failure that has already happened; an
 * exception here must not replace the original diagnosis. Failures are returned as a
 * {@link AlertOutcome#failed(String)} so the caller can stamp {@code alert_status=FAILED} on the run row —
 * making "the run failed AND nobody was told" a queryable state rather than a silence.
 */
@Component
public class BatchRunAlerter {

    /** A settlement-generation or recon window threw. */
    public static final String ALERT_BATCH_RUN_FAILED = "BATCH_RUN_FAILED";

    /** A window ran on a date the configured business-day calendar does not cover. */
    public static final String ALERT_CALENDAR_UNVERIFIED = "BATCH_CALENDAR_UNVERIFIED";

    private static final Logger log = LoggerFactory.getLogger(BatchRunAlerter.class);

    private final EventPublisher publisher;
    private final BusinessCalendar calendar;

    /**
     * Business dates for which the UNVERIFIED warning has already been raised. Keyed by date and pruned
     * whenever a newer date appears, so this holds at most a couple of entries — a plain unbounded set here
     * would be a slow leak in a process that runs for months.
     */
    private final Map<LocalDate, Boolean> calendarWarned = new ConcurrentHashMap<>();

    public BatchRunAlerter(@Qualifier(ReconBreakAlerter.ALERT_PUBLISHER_BEAN) EventPublisher publisher,
                           BusinessCalendar calendar) {
        this.publisher = publisher;
        this.calendar = calendar;
    }

    /** The outcome of trying to raise one alert. Never an exception. */
    public record AlertOutcome(String status, String error) {

        public static final String RAISED = "RAISED";
        public static final String FAILED = "FAILED";
        public static final String NOT_APPLICABLE = "NOT_APPLICABLE";

        public static AlertOutcome raised() {
            return new AlertOutcome(RAISED, null);
        }

        public static AlertOutcome failed(String error) {
            return new AlertOutcome(FAILED, error);
        }

        public static AlertOutcome notApplicable() {
            return new AlertOutcome(NOT_APPLICABLE, null);
        }
    }

    /**
     * Raise {@code BATCH_RUN_FAILED} for a window that threw.
     *
     * @param runId    the {@code batch_runs.id} the alert refers to, or null if even the ledger write failed
     * @param fileType e.g. {@code ZP0061}
     * @param window   e.g. {@code MORNING}
     * @param date     the business date the run was for
     * @param cause    the exception that ended the run
     */
    public AlertOutcome alertRunFailed(Long runId, String fileType, String window, LocalDate date,
                                       Throwable cause) {
        String subject = subjectRef(fileType, window, date);
        String detail = String.format(
                "settlement batch run FAILED: %s %s for business date %s threw %s: %s. "
                        + "The run's transaction rolled back, so no settlement batch/lines exist for it; "
                        + "batch_runs.id=%s holds the diagnosis. Re-run with "
                        + "POST /v1/settlements/batch/rerun {fileType, settlementWindow, businessDate, operatorId, reason}.",
                fileType, window, date,
                cause == null ? "an unknown error" : cause.getClass().getSimpleName(),
                cause == null ? "(no message)" : String.valueOf(cause.getMessage()),
                runId == null ? "UNWRITTEN" : runId.toString());
        return publish(ALERT_BATCH_RUN_FAILED, "CRITICAL", subject, detail);
    }

    /**
     * Raise {@code BATCH_CALENDAR_UNVERIFIED} at most once per business date.
     *
     * @return {@link AlertOutcome#notApplicable()} when the date has already been warned about
     */
    public AlertOutcome alertCalendarUnverified(String fileType, String window, LocalDate date) {
        if (date == null) {
            return AlertOutcome.notApplicable();
        }
        // Prune older dates first: at most one live entry in steady state.
        calendarWarned.keySet().removeIf(d -> d.isBefore(date));
        if (calendarWarned.putIfAbsent(date, Boolean.TRUE) != null) {
            return AlertOutcome.notApplicable();   // already warned for this business date
        }
        String detail = String.format(
                "settlement batch ran on an UNVERIFIED business day: %s is not covered by any configured "
                        + "business-day calendar (%s), so the KRW banking-holiday check was vacuous — "
                        + "%s/%s and every other window for this date proceeded on the assumption that today "
                        + "is a banking day. Populating gmepay.calendar.non-business-dates (and "
                        + "verified-through) is an OPERATOR/BUSINESS input; see Documentation/RUNBOOK_BATCH_OPS.md.",
                date, calendar.describe(), fileType, window);
        return publish(ALERT_CALENDAR_UNVERIFIED, "WARN", subjectRef(fileType, window, date), detail);
    }

    /** {@code subjectRef} must be non-blank or the Kafka transport rejects the event. */
    private static String subjectRef(String fileType, String window, LocalDate date) {
        String s = (fileType == null ? "BATCH" : fileType) + "/" + (window == null ? "-" : window)
                + "/" + (date == null ? "-" : date.toString());
        return s.isBlank() ? "BATCH" : s;
    }

    private AlertOutcome publish(String alertType, String severity, String subjectRef, String detail) {
        try {
            publisher.publish(new ReconAlertEvent(new OpsAlertPayload(
                    OpsAlertPayload.EVENT_TYPE, alertType, severity, subjectRef, detail,
                    Instant.now().toString())));
            log.warn("{} alert emitted: severity={} subject={} detail={}",
                    alertType, severity, subjectRef, detail);
            return AlertOutcome.raised();
        } catch (Exception e) {
            // Never rethrow: this IS the reporting path. Log loudly and let the caller record that the
            // notification failed, so "failed and nobody was told" is queryable.
            log.error("{} alert could NOT be published for subject={} — the failure is recorded in "
                            + "batch_runs but no notification left this service: {}",
                    alertType, subjectRef, e.getMessage(), e);
            return AlertOutcome.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
