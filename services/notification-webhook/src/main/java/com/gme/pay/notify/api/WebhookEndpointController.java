package com.gme.pay.notify.api;

import com.gme.pay.contracts.WebhookEndpointRegistrationCommand;
import com.gme.pay.contracts.WebhookEndpointRegistrationView;
import com.gme.pay.notify.provisioning.WebhookEndpointProvisioningService;
import com.gme.pay.notify.provisioning.WebhookSecretRotationView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slice 8 Lane D — the thin partner-activation registration endpoint that
 * config-registry's {@code RestNotificationWebhookClient} calls when a partner
 * transitions to {@code SANDBOX} / {@code LIVE}:
 *
 * <pre>
 * POST /v1/webhooks/endpoints                            — register an endpoint + mint its signing secret
 * POST /v1/webhooks/endpoints/{id}/rotate-secret          — issue the next generation (T5-4)
 * </pre>
 *
 * <p>Returns 201 Created with the endpoint id and the ONE-TIME plaintext
 * signing secret when a new registration was made; 200 OK with the existing
 * endpoint id and a {@code null} secret on the idempotent replay (an active
 * endpoint for the partner + environment already exists — activation retries
 * never mint a second secret).
 *
 * <p>The operator-facing CRUD surface stays on {@link WebhookConfigController}
 * ({@code /v1/webhook-configs}); THIS endpoint is the machine-to-machine
 * provisioning seam and the only place secrets are generated.
 */
@RestController
@RequestMapping("/v1/webhooks/endpoints")
public class WebhookEndpointController {

    private final WebhookEndpointProvisioningService provisioningService;

    public WebhookEndpointController(WebhookEndpointProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
    }

    @PostMapping
    public ResponseEntity<WebhookEndpointRegistrationView> register(
            @RequestBody WebhookEndpointRegistrationCommand request) {
        WebhookEndpointRegistrationView view = provisioningService.register(request);
        return ResponseEntity
                .status(view.newlyRegistered() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(view);
    }

    /**
     * Rotates one endpoint's signing secret (gap T5-4) and reveals the new plaintext
     * ONCE. The retired secret stays valid for {@code overlapMinutes} — during that
     * window every delivery carries both signatures, so the partner can redeploy
     * whenever they like without dropping events. {@code overlapMinutes=0} cuts over
     * immediately.
     *
     * <p>500 (not 400) when no derivation root key is configured: that is a platform
     * misconfiguration, not a bad request — and we refuse to hand out a secret the
     * dispatcher could not reproduce.
     */
    @PostMapping("/{endpointId}/rotate-secret")
    public ResponseEntity<WebhookSecretRotationView> rotateSecret(
            @PathVariable Long endpointId,
            @RequestParam(name = "overlapMinutes", required = false) Long overlapMinutes) {
        return ResponseEntity.ok(provisioningService.rotateSecret(endpointId, overlapMinutes));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }

    /** Missing/unusable derivation root key — a server-side configuration fault. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> handleMisconfiguration(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ex.getMessage());
    }
}
