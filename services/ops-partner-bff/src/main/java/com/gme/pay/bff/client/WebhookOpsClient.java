package com.gme.pay.bff.client;

/**
 * Ops view of the notification-webhook delivery pipeline: the backlog gauge for the
 * control-tower (PENDING + DLQ counts) and the operator replay action. Production calls
 * notification-webhook; the Phase-1 default
 * {@link com.gme.pay.bff.client.stub.StubWebhookOpsClient} is an in-memory stub.
 *
 * <p>Endpoint mapping (notification-webhook):
 * <ul>
 *   <li>{@code GET  /v1/webhooks/deliveries/backlog} -> {@link #backlog()}</li>
 *   <li>{@code POST /v1/webhooks/deliveries/{id}/replay} -> {@link #replay(String, String)}</li>
 * </ul>
 */
public interface WebhookOpsClient {

    /**
     * The current delivery backlog. Never throws — degrades to {@link WebhookBacklog#UNKNOWN}
     * (both counts null) when notification-webhook is unreachable, so the control-tower
     * shows the section as "unknown" rather than 500ing.
     */
    WebhookBacklog backlog();

    /**
     * Replay one webhook delivery by id. Returns the outcome; propagates upstream 4xx
     * (unknown delivery id) as a {@code ResponseStatusException} from the rest impl.
     */
    ReplayResult replay(String deliveryId, String actor);

    /**
     * Webhook delivery backlog gauge. {@code pending} = queued-but-undelivered,
     * {@code dlq} = dead-letter (exhausted retries). Null counts mean "unknown"
     * (upstream unavailable). {@link #total()} sums the two (null-safe).
     */
    record WebhookBacklog(Integer pending, Integer dlq) {
        /** All-unknown backlog used when the upstream cannot be reached. */
        public static final WebhookBacklog UNKNOWN = new WebhookBacklog(null, null);

        /** True when neither count is known. */
        public boolean unknown() {
            return pending == null && dlq == null;
        }

        /** Null-safe sum of pending + dlq; null when both are unknown. */
        public Integer total() {
            if (pending == null && dlq == null) {
                return null;
            }
            return (pending == null ? 0 : pending) + (dlq == null ? 0 : dlq);
        }
    }

    /** Outcome of a single delivery replay. */
    record ReplayResult(String deliveryId, String status, String detail) {}
}
