package com.gme.pay.registry.web;

import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.WebhookSubscriptionView;
import com.gme.pay.registry.webhook.WebhookProvisioningService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Slice 8 Lane D — webhook-subscription endpoints on the partner resource
 * (wizard step 8). Kept in its own controller so each slice's surface stays
 * reviewable in isolation (the Lane C regulatory surface lives in its own
 * controller); all mount under {@code /v1/partners} and share the
 * partner-code-on-the-URL-line contract.
 *
 * <p>{@code {partnerCode}} / {@code {id}} is always the human-facing business
 * code (e.g. {@code "GMEREMIT"}), never the BIGINT surrogate — same URL
 * contract as every other partner endpoint.
 *
 * <p>NOTE: there is deliberately NO provisioning endpoint here — activation
 * provisioning ({@code WebhookProvisioningService.provisionOnActivation}) is
 * invoked by Lane A's lifecycle service inside the activation transaction,
 * and the one-time signing secret rides the activation response, never a
 * standalone GET.
 */
@RestController
@RequestMapping("/v1/partners")
public class PartnerWebhookSubscriptionController {

    private final WebhookProvisioningService webhookService;

    public PartnerWebhookSubscriptionController(WebhookProvisioningService webhookService) {
        this.webhookService = webhookService;
    }

    /**
     * Save the step-8 webhook subscription draft for one environment onto an
     * existing draft partner — in-place upsert of the (partner, environment)
     * {@code partner_webhook_subscription} row (V030) with one audit row
     * (ADR-007). The draft is what activation provisions when the partner
     * transitions into the environment.
     *
     * <p>Returns 200 with the fresh {@link WebhookSubscriptionView}; 404
     * unknown draft; 409 when the partner has left ONBOARDING or the row was
     * already provisioned; 400 on validation failure (non-HTTPS / over-long
     * url, unknown environment, malformed event types).
     */
    @PatchMapping("/draft/{partnerCode}/step-8/webhook-subscription")
    public WebhookSubscriptionView patchDraftStep8WebhookSubscription(
            @PathVariable String partnerCode,
            @RequestBody PartnerCommand.UpdateStep8WebhookSubscription req,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        return webhookService.saveDraftSubscription(partnerCode, req, actor);
    }

    /**
     * The partner's webhook subscriptions across environments — powers the
     * wizard's step-8 rehydrate and the partner detail page's webhook tile.
     * 404 when the code is unknown; an empty list when step 8 has not been
     * saved yet. Secret material (even hashes) never appears here.
     */
    @GetMapping("/{id}/webhook-subscriptions")
    public List<WebhookSubscriptionView> getWebhookSubscriptions(@PathVariable String id) {
        return webhookService.currentSubscriptions(id);
    }
}
