package com.gme.pay.payment.alert;

/**
 * Outcome of one {@link AlertSink#deliver} attempt, recorded on the durable {@code ops_alerts} row so
 * "was anyone actually notified?" is answerable from the same place as the alert itself (gap T3-3).
 *
 * <p>Mirrors ops-partner-bff's {@code PageOutcome}: a channel label plus a terminal status, no
 * exceptions.
 *
 * @param status  terminal delivery status
 * @param channel the sink that handled it ({@code log} / {@code webhook})
 * @param error   failure description when {@link Status#FAILED}, otherwise {@code null}
 */
public record AlertDelivery(AlertDelivery.Status status, String channel, String error) {

    /** Terminal statuses; the string values are exactly the {@code ops_alerts.notify_status} domain. */
    public enum Status {
        /** The sink accepted the alert. */
        DELIVERED,
        /** The sink was reached but refused/errored (after its own retries). */
        FAILED,
        /** No sink ran (e.g. the durable write failed first, so there is nothing to notify about). */
        SKIPPED
    }

    public static AlertDelivery delivered(String channel) {
        return new AlertDelivery(Status.DELIVERED, channel, null);
    }

    public static AlertDelivery failed(String channel, String error) {
        return new AlertDelivery(Status.FAILED, channel, error);
    }

    public static AlertDelivery skipped(String reason) {
        return new AlertDelivery(Status.SKIPPED, null, reason);
    }
}
