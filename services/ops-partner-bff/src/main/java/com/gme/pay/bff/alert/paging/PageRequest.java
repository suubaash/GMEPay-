package com.gme.pay.bff.alert.paging;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gme.pay.bff.alert.OpsAlertView;

/**
 * The small, stable, vendor-agnostic shape POSTed to the on-call webhook when an alert
 * pages a human (ADR-015 — no cloud SDK; one generic JSON webhook fans out to Slack
 * incoming webhooks / PagerDuty Events API / Opsgenie / MS Teams via config).
 *
 * <p>Deliberately a flat, minimal projection of {@link OpsAlertView}: the on-call
 * integration templates against these field names, so they are frozen here rather than
 * leaking the internal store shape (seq / delivery record / ack state).
 *
 * <p>{@code link} is an optional deep-link into the control tower for the alert; it is
 * left {@code null} unless a base link is configured.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PageRequest(
        String alertType,
        String severity,
        String subjectRef,
        String detail,
        String occurredAt,
        String link) {

    /** Project a stored alert view (+ optional deep link) to the on-call wire shape. */
    public static PageRequest from(OpsAlertView v, String link) {
        return new PageRequest(
                v.alertType(), v.severity(), v.subjectRef(), v.detail(), v.occurredAt(), link);
    }
}
