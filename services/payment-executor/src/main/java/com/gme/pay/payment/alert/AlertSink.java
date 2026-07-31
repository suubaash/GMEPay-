package com.gme.pay.payment.alert;

import com.gme.pay.contracts.events.OpsAlertPayload;

/**
 * Outbound notification port for ops alerts raised inside payment-executor — the "somebody actually
 * finds out" edge of gap <b>T3-3</b> (COO#4: "a 100% decline spike at 3am pages nobody").
 *
 * <h2>Why payment-executor needs its own sink</h2>
 * <p>The platform's aggregate paging port lives in ops-partner-bff
 * ({@code com.gme.pay.bff.alert.paging.PagingPort} + {@code LogPagingAdapter} /
 * {@code WebhookPagingAdapter}), reached over {@code gmepay.ops.alert}. That chain cannot deliver a
 * payment-executor alert today: this service has no {@code lib-events-kafka} on its classpath, so its
 * {@link com.gme.pay.events.EventPublisher} is still {@code LogEventPublisher} and nothing is
 * published to a broker at all. Wiring a real Kafka publisher here would also start emitting
 * {@code payment.approved} / {@code payment.failed} from a second producer, which is a money-path
 * change, not a monitoring change — so it is explicitly out of scope (register item T2-5).
 *
 * <p>This port is therefore the direct path from the two conditions payment-executor detects to a
 * human, and it is deliberately shaped like the BFF's {@code PagingPort} (same log-default /
 * URL-only-webhook pair, same never-throws contract, same "no vendor is hardcoded" rule, ADR-015) so
 * an operator points BOTH at the same webhook URL and templates against one payload shape.
 *
 * <h2>Contract</h2>
 * <p>Implementations <b>must never throw</b>. A failure is reported as
 * {@link AlertDelivery#failed(String, String)}; the caller ({@link OpsAlertPipeline}) records the
 * outcome on the durable alert row and carries on. Nothing on this path may affect a payment.
 */
public interface AlertSink {

    /**
     * Deliver one alert to the configured destination.
     *
     * @param alert the alert as it was persisted and published (never {@code null})
     * @return the delivery outcome; never {@code null}, never thrown
     */
    AlertDelivery deliver(OpsAlertPayload alert);
}
