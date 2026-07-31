package com.gme.pay.payment.alert;

import com.gme.pay.contracts.events.OpsAlertPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Safe default {@link AlertSink}: logs the alert at {@code WARN} and reports success.
 *
 * <p>Wired by {@link AlertSinkConfig} as {@code @ConditionalOnMissingBean(AlertSink.class)}, so it
 * wins whenever {@code gmepay.alert.sink.webhook-url} is unset. That keeps the alert path functional
 * and observable with zero configuration and adds no outbound network dependency to a local run —
 * mirroring ops-partner-bff's {@code LogPagingAdapter}.
 *
 * <p><b>Honest limitation</b>, and the reason a webhook URL should be configured before pilot
 * traffic: a log line only reaches a human if somebody is reading logs, and the platform has no log
 * aggregation (gap T3-2 residual). The durable {@code ops_alerts} row written by
 * {@link OpsAlertPipeline} is the queryable record; this sink is not a paging channel.
 */
public class LogAlertSink implements AlertSink {

    /** Value written to {@code ops_alerts.notify_channel}. */
    public static final String CHANNEL = "log";

    private static final Logger log = LoggerFactory.getLogger(LogAlertSink.class);

    @Override
    public AlertDelivery deliver(OpsAlertPayload alert) {
        log.warn("OPS ALERT (log-only; no gmepay.alert.sink.webhook-url configured): "
                        + "severity={} type={} subjectRef={} occurredAt={} detail={}",
                alert.severity(), alert.alertType(), alert.subjectRef(),
                alert.occurredAt(), alert.detail());
        return AlertDelivery.delivered(CHANNEL);
    }
}
