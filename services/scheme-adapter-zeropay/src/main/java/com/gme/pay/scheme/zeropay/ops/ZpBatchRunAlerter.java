package com.gme.pay.scheme.zeropay.ops;

import com.gme.pay.contracts.events.OpsAlertPayload;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.scheme.zeropay.ops.calendar.BusinessCalendar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raises the two batch-operations ops alerts for the ZeroPay adapter (gap <b>T3-4</b>) through the platform's
 * existing {@code gmepay.ops.alert} pipeline — the same alertTypes, severities and payload shape
 * settlement-reconciliation uses, so an operator templates against one format.
 *
 * <p>Before this existed the adapter had no {@link EventPublisher} on its classpath at all, so a failed
 * 02:00 ZP0011 could not reach the alert pipeline even in principle — the same first-hop break T3-3 found in
 * payment-executor. {@link ZpOpsAlertConfig} supplies the transport; this class decides when and how loudly.
 *
 * <p><b>Never throws.</b> Alerting is the reporting path for a failure that already happened; an exception
 * here would replace the diagnosis with a bookkeeping error. Failures come back as
 * {@link Outcome#failed(String)} so the caller can stamp {@code alert_status=FAILED} on the run row, making
 * "the run failed AND nobody was told" queryable instead of silent.
 */
@Component
public class ZpBatchRunAlerter {

    /** A ZeroPay batch window threw. */
    public static final String ALERT_BATCH_RUN_FAILED = "BATCH_RUN_FAILED";

    /** A window ran on a date the configured business-day calendar does not cover. */
    public static final String ALERT_CALENDAR_UNVERIFIED = "BATCH_CALENDAR_UNVERIFIED";

    private static final Logger log = LoggerFactory.getLogger(ZpBatchRunAlerter.class);

    private final EventPublisher publisher;
    private final BusinessCalendar calendar;

    /** Business dates already warned about, pruned as dates advance so this holds ~one entry. */
    private final Map<LocalDate, Boolean> calendarWarned = new ConcurrentHashMap<>();

    public ZpBatchRunAlerter(@Qualifier(ZpOpsAlertConfig.ALERT_PUBLISHER_BEAN) EventPublisher publisher,
                             BusinessCalendar calendar) {
        this.publisher = publisher;
        this.calendar = calendar;
    }

    /** The outcome of trying to raise one alert. Never an exception. */
    public record Outcome(String status, String error) {

        public static final String RAISED = "RAISED";
        public static final String FAILED = "FAILED";
        public static final String NOT_APPLICABLE = "NOT_APPLICABLE";

        public static Outcome raised() {
            return new Outcome(RAISED, null);
        }

        public static Outcome failed(String error) {
            return new Outcome(FAILED, error);
        }

        public static Outcome notApplicable() {
            return new Outcome(NOT_APPLICABLE, null);
        }
    }

    /** Raise {@code BATCH_RUN_FAILED} (CRITICAL) for a window that threw. */
    public Outcome alertRunFailed(Long runId, String batchType, LocalDate date, Throwable cause) {
        String detail = String.format(
                "ZeroPay batch run FAILED: %s for business date %s threw %s: %s. zp_batch_runs.id=%s holds "
                        + "the diagnosis. Re-run with POST /internal/scheme/zeropay/batch/rerun "
                        + "{batchType, businessDate, operatorId, reason}. NOTE: a failed ZP0011/ZP0012 also "
                        + "BLOCKS that date's ZP0061/ZP0063 settlement request via the spec §8.2 "
                        + "prerequisite gate, so this is not an isolated file.",
                batchType, date,
                cause == null ? "an unknown error" : cause.getClass().getSimpleName(),
                cause == null ? "(no message)" : String.valueOf(cause.getMessage()),
                runId == null ? "UNWRITTEN" : runId.toString());
        return publish(ALERT_BATCH_RUN_FAILED, "CRITICAL", subjectRef(batchType, date), detail);
    }

    /** Raise {@code BATCH_CALENDAR_UNVERIFIED} (WARN) at most once per business date. */
    public Outcome alertCalendarUnverified(String batchType, LocalDate date) {
        if (date == null) {
            return Outcome.notApplicable();
        }
        calendarWarned.keySet().removeIf(d -> d.isBefore(date));
        if (calendarWarned.putIfAbsent(date, Boolean.TRUE) != null) {
            return Outcome.notApplicable();
        }
        String detail = String.format(
                "ZeroPay batch ran on an UNVERIFIED business day: %s is not covered by any configured "
                        + "business-day calendar (%s), so the KRW banking-holiday check was vacuous — %s and "
                        + "every other window for this date proceeded assuming today is a banking day, which "
                        + "risks handing KFTC a file it rejects or double-counts. Populating "
                        + "gmepay.calendar.non-business-dates is an OPERATOR/BUSINESS input; see "
                        + "Documentation/RUNBOOK_BATCH_OPS.md.",
                date, calendar.describe(), batchType);
        return publish(ALERT_CALENDAR_UNVERIFIED, "WARN", subjectRef(batchType, date), detail);
    }

    /** {@code subjectRef} must be non-blank or the Kafka transport rejects the event. */
    private static String subjectRef(String batchType, LocalDate date) {
        String s = (batchType == null ? "ZPBATCH" : batchType) + "/" + (date == null ? "-" : date.toString());
        return s.isBlank() ? "ZPBATCH" : s;
    }

    private Outcome publish(String alertType, String severity, String subjectRef, String detail) {
        try {
            publisher.publish(new ZpOpsAlertEvent(new OpsAlertPayload(
                    OpsAlertPayload.EVENT_TYPE, alertType, severity, subjectRef, detail,
                    Instant.now().toString())));
            log.warn("{} alert emitted: severity={} subject={} detail={}",
                    alertType, severity, subjectRef, detail);
            return Outcome.raised();
        } catch (Exception e) {
            log.error("{} alert could NOT be published for subject={} — the failure is recorded in "
                            + "zp_batch_runs but no notification left this service: {}",
                    alertType, subjectRef, e.getMessage(), e);
            return Outcome.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
