package com.gme.pay.rbac;

/**
 * The outcome of one permission check — the unit the audit trail records (every check,
 * allowed or denied, with a reason), per the FSS-inspectable audit requirement.
 *
 * @param principalId who was checked (may be null = anonymous)
 * @param tenantId    tenant scope of the caller
 * @param permission  the permission code required by the handler
 * @param allowed     whether the check passed
 * @param mode        AUDIT (logged only) or ENFORCE (blocked when denied)
 * @param reason      human-readable reason (esp. on deny)
 * @param method      HTTP method
 * @param path        request path
 */
public record RbacDecision(
        String principalId,
        String tenantId,
        String permission,
        boolean allowed,
        RbacMode mode,
        String reason,
        String method,
        String path) {}
