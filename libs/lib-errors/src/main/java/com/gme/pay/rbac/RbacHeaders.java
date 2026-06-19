package com.gme.pay.rbac;

/**
 * The HTTP header contract by which an upstream Policy stamps a caller's resolved
 * RBAC context onto a request, and downstream services read it. Mirrors the existing
 * GMEPay+ "gateway authenticates, stamps headers, services trust them" model
 * (e.g. {@code X-Partner-Id}): the api-gateway resolves the principal's effective
 * permissions (once, at the edge, from JWT claims) and stamps these; every service's
 * {@link com.gme.pay.rbac.RbacContextFilter} reconstructs the {@link PermissionContext}
 * with no per-request RBAC network hop (meets the &lt;50ms NFR).
 */
public final class RbacHeaders {

    private RbacHeaders() {}

    /** Principal (operator/partner) surrogate id. */
    public static final String PRINCIPAL_ID = "X-Gme-Principal-Id";
    /** Tenant (partner) surrogate id — empty/absent for platform-hub operators. */
    public static final String TENANT_ID = "X-Gme-Tenant-Id";
    /** Comma-separated effective permission codes (resource.action). */
    public static final String PERMISSIONS = "X-Gme-Permissions";
    /** Comma-separated role codes (coarse; permissions are the authority). */
    public static final String ROLES = "X-Gme-Roles";

    // ----- Dynamic-constraint context (P3): caller attributes the engine evaluates against.

    /** Caller country (ISO-3166 alpha-2) — LOCATION constraints. */
    public static final String COUNTRY = "X-Gme-Country";
    /** Caller region (JAPAN | KOREA | CIS …) — LOCATION constraints. */
    public static final String REGION = "X-Gme-Region";
    /** Caller office id — LOCATION constraints. */
    public static final String OFFICE = "X-Gme-Office";
    /** "true" when a prior approval has been granted for this request — APPROVAL constraints. */
    public static final String APPROVAL_GRANTED = "X-Gme-Approval-Granted";
    /**
     * Edge-stamped encoded constraints applying to the granted action. Wire format
     * (see {@code HeaderConstraintSource}): {@code TYPE:k=v;k=v|TYPE:k=v} — {@code |}
     * between constraints, {@code :} after the type, {@code ;} between config pairs,
     * {@code ,} within a set value.
     */
    public static final String CONSTRAINTS = "X-Gme-Constraints";

    // ----- Provenance (anti-spoof): proves the gateway — not a forger — stamped the claims above.

    /** Lowercase-hex HMAC-SHA256 over the stamped claim bundle (see {@link RbacClaimSigner}). */
    public static final String SIGNATURE = "X-Gme-Sig";
    /** Epoch-millis the signature was produced — gives a freshness / anti-replay window. */
    public static final String SIGNATURE_TS = "X-Gme-Sig-Ts";
}
