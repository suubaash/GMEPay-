package com.gme.pay.notify.api;

import com.gme.pay.notify.domain.WebhookConfigEntry;
import com.gme.pay.notify.domain.WebhookConfigStore;
import com.gme.pay.notify.domain.WebhookUrlNotHttpsException;
import com.gme.pay.notify.dto.WebhookConfigListResponse;
import com.gme.pay.notify.dto.WebhookConfigRequest;
import com.gme.pay.notify.dto.WebhookConfigResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST endpoints for managing per-partner webhook configurations (API-05 §6.1).
 *
 * <pre>
 * POST   /v1/webhook-configs          — register a new webhook endpoint
 * GET    /v1/webhook-configs?partnerId — list active configs for a partner
 * GET    /v1/webhook-configs/{id}      — fetch a single config
 * DELETE /v1/webhook-configs/{id}      — deactivate a config
 * </pre>
 */
@RestController
@RequestMapping("/v1/webhook-configs")
public class WebhookConfigController {

    private final WebhookConfigStore store;

    public WebhookConfigController(WebhookConfigStore store) {
        this.store = store;
    }

    /**
     * Registers a new webhook endpoint for a partner.
     *
     * @return 201 Created with the saved configuration (signing secret is never echoed back)
     */
    @PostMapping
    public ResponseEntity<WebhookConfigResponse> register(@RequestBody WebhookConfigRequest request) {
        WebhookConfigEntry entry = new WebhookConfigEntry(
                request.partnerId(),
                request.webhookUrl(),
                request.eventTypes(),
                request.signingSecret()
        );
        WebhookConfigResponse saved = store.save(entry);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * Lists all active webhook configurations for the specified partner.
     */
    @GetMapping
    public WebhookConfigListResponse listByPartner(@RequestParam Long partnerId) {
        List<WebhookConfigResponse> configs = store.findActiveByPartnerId(partnerId);
        return new WebhookConfigListResponse(configs, configs.size());
    }

    /**
     * Fetches a single webhook configuration by id.
     */
    @GetMapping("/{id}")
    public ResponseEntity<WebhookConfigResponse> getById(@PathVariable Long id) {
        return store.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Deactivates (soft-deletes) a webhook configuration.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        if (store.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        store.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Exception handlers
    // -------------------------------------------------------------------------

    @ExceptionHandler(WebhookUrlNotHttpsException.class)
    public ResponseEntity<String> handleNonHttps(WebhookUrlNotHttpsException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
