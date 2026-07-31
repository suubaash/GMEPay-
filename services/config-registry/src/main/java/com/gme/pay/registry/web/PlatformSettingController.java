package com.gme.pay.registry.web;

import com.gme.pay.registry.actor.AuditActorHeader;
import com.gme.pay.registry.settings.PlatformSettingService;
import com.gme.pay.registry.settings.PlatformSettingView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator-facing surface of the generic platform-settings store (owner Goal #3 —
 * change platform tunables from the admin UI without a redeploy).
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /v1/admin/settings}        → all settings, key-sorted</li>
 *   <li>{@code GET /v1/admin/settings/{key}}  → one (404 if absent)</li>
 *   <li>{@code PUT /v1/admin/settings/{key}}  body {@code {value, updatedBy?}} → upsert
 *       (validates NUMBER), writes an audit row, returns the updated row</li>
 * </ul>
 *
 * <h2>Security note</h2>
 *
 * <p>Like {@link OpsControlController} / {@link AuditLogController}, this endpoint is
 * reached only through the BFF, which adds the Keycloak OIDC ops-role gate at the edge
 * (config-registry itself carries no in-process RBAC). The operator identity arrives as
 * {@code X-Actor} (falling back to the request body {@code updatedBy}) and is recorded on
 * every audit row; {@code X-Forwarded-For} carries the client IP for the same row.
 */
@RestController
@RequestMapping("/v1/admin/settings")
public class PlatformSettingController {

    private final PlatformSettingService service;

    public PlatformSettingController(PlatformSettingService service) {
        this.service = service;
    }

    @GetMapping
    public List<PlatformSettingView> list() {
        return service.list();
    }

    @GetMapping("/{key}")
    public PlatformSettingView get(@PathVariable String key) {
        return service.get(key);
    }

    @PutMapping("/{key}")
    public PlatformSettingView put(
            @PathVariable String key,
            @RequestBody UpdateRequest body,
            @AuditActorHeader String actor,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip) {
        String value = body == null ? null : body.value();
        // X-Actor (from the BFF's authenticated principal) wins; body.updatedBy is a fallback.
        String updatedBy = actor != null && !actor.isBlank()
                ? actor
                : (body == null ? null : body.updatedBy());
        return service.upsert(key, value, updatedBy, ip);
    }

    /** {@code {"value": "...", "updatedBy": "..."}} — updatedBy optional. */
    public record UpdateRequest(String value, String updatedBy) {
    }
}
