package com.gme.pay.payment.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the active {@link AlertSink} (gap T3-3): a generic webhook when a URL is configured, a
 * log-only fallback otherwise. Same intent as ops-partner-bff's {@code PagingConfig}, so the platform
 * has one paging idiom — but chosen inside <b>one</b> bean method rather than by
 * {@code @ConditionalOnProperty}, deliberately.
 *
 * <h2>Why not {@code @ConditionalOnProperty}</h2>
 * <p>{@code @ConditionalOnProperty} with no {@code havingValue} matches a property that is
 * <em>present but empty</em>. An operator (or a manifest) setting
 * {@code GMEPAY_ALERT_SINK_WEBHOOK_URL=} — the natural way to write "not configured" in a
 * docker-compose file or a Helm value, and exactly what {@code ${VAR:-}} expands to — would therefore
 * activate the webhook sink pointed at nothing and make <b>every page fail silently</b>. That is
 * strictly worse than the log fallback, and it is the same shape of accident as the default-OFF
 * monitors T3-3 exists to eliminate. A blank/whitespace URL is treated here as "not configured", so
 * the fallback wins and the choice is logged at startup.
 *
 * <h2>What an operator sets</h2>
 * <pre>
 *   GMEPAY_ALERT_SINK_WEBHOOK_URL=https://hooks.slack.com/services/...   # or PagerDuty/Opsgenie/Teams
 *   GMEPAY_ALERT_SINK_TIMEOUT_MS=3000     (optional)
 *   GMEPAY_ALERT_SINK_MAX_ATTEMPTS=3      (optional)
 * </pre>
 *
 * <p>No vendor code and no credential in the repo (ADR-015): the secret, when there is one, is the
 * opaque token inside the operator's URL. See {@code Documentation/RUNBOOK_MONITORING.md}.
 *
 * <p>{@code @ConditionalOnMissingBean} so a test (or a future in-house sink) can supply its own.
 */
@Configuration
public class AlertSinkConfig {

    private static final Logger log = LoggerFactory.getLogger(AlertSinkConfig.class);

    @Bean
    @ConditionalOnMissingBean(AlertSink.class)
    public AlertSink alertSink(
            @Value("${gmepay.alert.sink.webhook-url:}") String webhookUrl,
            @Value("${gmepay.alert.sink.timeout-ms:3000}") long timeoutMs,
            @Value("${gmepay.alert.sink.max-attempts:3}") int maxAttempts) {
        if (isConfigured(webhookUrl)) {
            log.info("ops alert notification sink = webhook (timeout={}ms, maxAttempts={}); "
                            + "DECLINE_SPIKE alerts will be POSTed to the configured on-call URL",
                    timeoutMs, maxAttempts);
            return new WebhookAlertSink(webhookUrl.trim(), timeoutMs, maxAttempts);
        }
        log.warn("ops alert notification sink = LOG ONLY. Alerts are persisted (ops_alerts) and "
                + "queryable at GET /internal/ops/alerts, but NOTHING WILL PAGE A HUMAN. Set "
                + "GMEPAY_ALERT_SINK_WEBHOOK_URL to your pager/Slack/Opsgenie webhook before pilot "
                + "traffic — see Documentation/RUNBOOK_MONITORING.md.");
        return new LogAlertSink();
    }

    /** Blank / whitespace-only counts as "not configured" — see class javadoc. */
    static boolean isConfigured(String webhookUrl) {
        return webhookUrl != null && !webhookUrl.isBlank();
    }
}
