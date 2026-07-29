package com.gme.pay.bff.web;

import com.gme.pay.bff.client.PlatformSettingsClient;
import com.gme.pay.bff.web.dto.PlatformSettingView;
import com.gme.pay.rbac.RbacHeaders;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin platform-settings surface — a thin pass-through to config-registry's generic
 * settings store (owner Goal #3 — operators change platform tunables from the admin UI
 * without a redeploy). The admin UI reaches config-registry only through this BFF.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /v1/admin/settings}        → all settings (key-sorted)</li>
 *   <li>{@code GET /v1/admin/settings/{key}}  → one (404 propagated from config-registry)</li>
 *   <li>{@code PUT /v1/admin/settings/{key}}  body {@code {value, updatedBy?}} → upsert
 *       (400 NUMBER-validation propagated); the authenticated principal is forwarded as the
 *       operator recorded on config-registry's audit row.</li>
 * </ul>
 *
 * <h2>RBAC (fail closed)</h2>
 * <p>Reads require the support-read permission {@code txn.view} (an ops operator with
 * {@code ops:operate} passes too); the mutating PUT requires the {@code ops:operate}
 * permission. Delegated to {@link OpsRbacGuard}, which DENIES (403) when the
 * {@code X-Gme-Permissions} header is absent or lacks the required permission — matching the
 * neighbouring ops admin surfaces.
 */
@RestController
@RequestMapping("/v1/admin/settings")
public class PlatformSettingsController {

    private final PlatformSettingsClient client;
    private final OpsRbacGuard rbac;

    public PlatformSettingsController(PlatformSettingsClient client, OpsRbacGuard rbac) {
        this.client = client;
        this.rbac = rbac;
    }

    @GetMapping
    public List<PlatformSettingView> list() {
        rbac.requireTxnView();
        return client.list();
    }

    @GetMapping("/{key}")
    public PlatformSettingView get(
            @PathVariable String key) {
        rbac.requireTxnView();
        return client.get(key);
    }

    @PutMapping("/{key}")
    public PlatformSettingView put(
            @PathVariable String key,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = RbacHeaders.PRINCIPAL_ID, required = false) String principal) {
        rbac.requireOps();
        String value = body == null ? null : body.get("value");
        // Forward the token-verified subject as the operator on config-registry's audit row;
        // fall back to the legacy principal header / body updatedBy only when unauthenticated (dev).
        String updatedBy = rbac.actor(principal != null && !principal.isBlank()
                ? principal
                : (body == null ? null : body.get("updatedBy")));
        return client.update(key, value, updatedBy);
    }
}
