package com.gme.pay.registry.webhook;

/**
 * Lifecycle of one {@code partner_webhook_subscription} row (V030 CHECK
 * roster) — Slice 8 Lane D.
 */
public enum WebhookSubscriptionStatus {
    /** Saved by the step-8 wizard; no endpoint registered, no secret minted. */
    DRAFT,
    /** Endpoint registered with notification-webhook; signing secret issued. */
    PROVISIONED,
    /** Kill switch: wiring kept, deliveries stopped. */
    DISABLED
}
