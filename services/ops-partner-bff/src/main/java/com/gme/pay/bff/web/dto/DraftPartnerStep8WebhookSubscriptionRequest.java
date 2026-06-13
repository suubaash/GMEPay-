package com.gme.pay.bff.web.dto;

import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.WebhookSubscriptionCommand;

/**
 * BFF wire shape for
 * {@code PATCH /v1/admin/partners/draft/{partnerCode}/step-8/webhook-subscription}
 * (Slice 8 Lane D — webhook subscription draft). In-place upsert of the
 * (partner, environment) {@code partner_webhook_subscription} row.
 */
public record DraftPartnerStep8WebhookSubscriptionRequest(WebhookSubscriptionCommand subscription) {

    /** Adapt to the canonical write surface {@code lib-api-contracts} exposes. */
    public PartnerCommand.UpdateStep8WebhookSubscription toUpdateStep8WebhookSubscription() {
        return new PartnerCommand.UpdateStep8WebhookSubscription(subscription);
    }
}
