package com.gme.pay.auth.rbac;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * RBAC resolution API. Called by the edge (api-gateway claim enrichment) at token mint /
 * refresh — NOT per request — to obtain a principal's effective permissions, which it then
 * stamps into the {@code X-Gme-*} headers for downstream {@code @RequiresPermission} checks.
 *
 * <ul>
 *   <li>{@code GET  /v1/rbac/principals/{id}/permissions} → {@link ResolvedAccess}</li>
 *   <li>{@code POST /v1/rbac/resolve}  {principalId | username} → {@link ResolvedAccess}</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/rbac")
public class RbacResolveController {

    private final RbacResolutionService resolution;

    public RbacResolveController(RbacResolutionService resolution) {
        this.resolution = resolution;
    }

    @GetMapping("/principals/{id}/permissions")
    public ResolvedAccess byPrincipal(@PathVariable("id") Long id) {
        return resolution.resolve(id);
    }

    @PostMapping("/resolve")
    public ResolvedAccess resolve(@RequestBody ResolveRequest req) {
        if (req == null || (req.principalId() == null && (req.username() == null || req.username().isBlank()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "principalId or username required");
        }
        return req.principalId() != null
                ? resolution.resolve(req.principalId())
                : resolution.resolveByUsername(req.username());
    }

    /** Resolve by surrogate id or username (exactly one required). */
    public record ResolveRequest(Long principalId, String username) {}
}
