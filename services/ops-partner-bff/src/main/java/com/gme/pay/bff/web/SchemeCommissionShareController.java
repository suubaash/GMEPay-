package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.contracts.SchemeCommissionShareCommand;
import com.gme.pay.contracts.SchemeCommissionShareView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Scheme-side commission-share pass-throughs for the Admin UI QR-scheme setup —
 * the configurable GME↔scheme split of the net merchant fee (V031). Kept in its
 * own controller (rather than growing {@code AdminDashboardController}) so the
 * surface stays reviewable in isolation; mounts under the same {@code /v1/admin}
 * prefix.
 *
 * <p>Pure pass-throughs to {@link ConfigRegistryClient}, which adapts to
 * config-registry's {@code /v1/schemes/{schemeId}/commission-shares} endpoints.
 * Upstream 400 (validation) / 404 (unknown scheme) pass through with their
 * messages preserved. Shares and rates ride as decimal STRINGS per
 * {@code docs/MONEY_CONVENTION.md}.
 */
@RestController
@RequestMapping("/v1/admin")
public class SchemeCommissionShareController {

    private final ConfigRegistryClient configRegistry;

    public SchemeCommissionShareController(ConfigRegistryClient configRegistry) {
        this.configRegistry = configRegistry;
    }

    /**
     * The CURRENT commission-share rows for {@code schemeId}. Mirrors
     * {@code GET /v1/schemes/{schemeId}/commission-shares}. Empty list when none
     * configured; 404 for an unknown scheme code.
     */
    @GetMapping("/schemes/{schemeId}/commission-shares")
    public List<SchemeCommissionShareView> getCommissionShares(@PathVariable String schemeId) {
        return configRegistry.listSchemeCommissionShares(schemeId);
    }

    /**
     * Bulk-replace the scheme's commission-share set (one row per direction;
     * empty list clears). Mirrors
     * {@code PUT /v1/schemes/{schemeId}/commission-shares}; upstream 400/404
     * pass through with their messages preserved.
     */
    @PutMapping("/schemes/{schemeId}/commission-shares")
    public List<SchemeCommissionShareView> replaceCommissionShares(
            @PathVariable String schemeId,
            @RequestBody List<SchemeCommissionShareCommand> shares) {
        if (shares == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body required");
        }
        return configRegistry.replaceSchemeCommissionShares(schemeId, shares);
    }
}
