package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.web.dto.DraftPartnerStep8WebhookSubscriptionRequest;
import com.gme.pay.contracts.WebhookSubscriptionView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 8 Lane D — webhook-subscription pass-throughs for the Admin UI wizard's
 * step-8 webhook panel and the partner detail page.
 *
 * <p>Pure pass-throughs: each call delegates to {@link ConfigRegistryClient},
 * which adapts to config-registry's
 * {@code PATCH /v1/partners/draft/{code}/step-8/webhook-subscription} and
 * {@code GET /v1/partners/{id}/webhook-subscriptions} endpoints.
 * Upstream 400/404/409 pass through with their messages preserved.
 *
 * <p>NOTE: provisioning (minting the signing secret) is NOT exposed here —
 * activation provisioning is invoked by Lane A's lifecycle service inside the
 * activation transaction; the one-time signing secret rides the activation
 * response, never a standalone GET.
 */
@RestController
@RequestMapping("/v1/admin")
public class PartnerWebhookSubscriptionController {

    private final ConfigRegistryClient configRegistry;

    public PartnerWebhookSubscriptionController(ConfigRegistryClient configRegistry) {
        this.configRegistry = configRegistry;
    }

    /**
     * Save the step-8 webhook subscription draft — in-place upsert of the
     * (partner, environment) row (V030). Returns 200 with the fresh
     * {@link WebhookSubscriptionView}; 404 unknown draft; 409 when the
     * partner has left ONBOARDING or the row was already provisioned; 400 on
     * validation failure.
     */
    @PatchMapping("/partners/draft/{partnerCode}/step-8/webhook-subscription")
    public WebhookSubscriptionView patchDraftStep8WebhookSubscription(
            @PathVariable String partnerCode,
            @RequestBody DraftPartnerStep8WebhookSubscriptionRequest body,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        if (body == null || body.subscription() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "request body with a 'subscription' object required");
        }
        return configRegistry.patchDraftStep8WebhookSubscription(
                partnerCode, body.toUpdateStep8WebhookSubscription(), actor);
    }

    /**
     * The partner's webhook subscriptions across environments — powers the
     * wizard's step-8 rehydrate and the partner detail page's webhook tile.
     * 404 when the code is unknown; empty list when step 8 not yet saved.
     */
    @GetMapping("/partners/{partnerCode}/webhook-subscription")
    public List<WebhookSubscriptionView> getWebhookSubscriptions(
            @PathVariable String partnerCode) {
        return configRegistry.getWebhookSubscriptions(partnerCode);
    }
}
