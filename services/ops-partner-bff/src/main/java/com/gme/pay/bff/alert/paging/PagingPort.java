package com.gme.pay.bff.alert.paging;

/**
 * Vendor-agnostic on-call paging port (ADR-015 — no cloud SDK). One adapter delivers a
 * {@link PageRequest} to whatever on-call channel is wired via config: an HTTP webhook
 * ({@link WebhookPagingAdapter} — Slack / PagerDuty / Opsgenie / MS Teams) in production,
 * or a log-only fallback ({@link LogPagingAdapter}) when no URL is configured.
 *
 * <p>Implementations MUST NOT throw: paging is a best-effort side-effect of consuming an
 * alert and must never wedge the Kafka consumer or fail the store. The outcome (delivered
 * / failed) is returned so the caller can record it on the stored alert.
 */
public interface PagingPort {

    /**
     * Attempt to page the on-call for one alert. Never throws — a transport failure is
     * captured in the returned {@link PageOutcome}.
     *
     * @param request the stable on-call wire shape
     * @return the delivery outcome (delivered / failed, with a short channel + detail)
     */
    PageOutcome page(PageRequest request);

    /** Result of one paging attempt; {@code delivered=false} means the channel failed. */
    record PageOutcome(boolean delivered, String channel, String detail) {

        public static PageOutcome delivered(String channel) {
            return new PageOutcome(true, channel, null);
        }

        public static PageOutcome failed(String channel, String detail) {
            return new PageOutcome(false, channel, detail);
        }
    }
}
