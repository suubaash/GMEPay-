package com.gme.pay.rbac;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for cross-service RBAC enforcement. Disabled by default — a service opts
 * in with {@code gmepay.rbac.enabled=true}, and chooses {@code gmepay.rbac.mode=audit}
 * (log-only rollout) or {@code enforce} (block on missing permission).
 */
@ConfigurationProperties(prefix = "gmepay.rbac")
public class RbacProperties {

    /** Master switch for the {@code @RequiresPermission} interceptor + context filter. */
    private boolean enabled = false;

    /** AUDIT (log decisions, never block) or ENFORCE (403 on missing permission). */
    private RbacMode mode = RbacMode.ENFORCE;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public RbacMode getMode() { return mode; }
    public void setMode(RbacMode mode) { this.mode = mode; }

    /** Claim-provenance verification (anti-spoof). */
    private final Verify verify = new Verify();
    public Verify getVerify() { return verify; }

    /**
     * Verification of the gateway's HMAC signature over the stamped {@code X-Gme-*} claims.
     * A blank {@code secret} keeps the legacy "trust the headers" behaviour (local dev / tests);
     * setting it (to match {@code gmepay.rbac.stamp.secret} on the gateway) switches the
     * {@link RbacContextFilter} into STRICT mode, where unsigned / forged / stale claims are refused.
     */
    public static class Verify {

        /** Shared secret the gateway signs claims with; blank = no signature required (legacy trust). */
        private String secret = "";

        /** Max allowed clock skew between the gateway's sign time and service receipt (ms). */
        private long clockSkewMs = 300_000;

        /**
         * Fail-closed guard. When false (default), a service in {@code mode=ENFORCE} with a blank
         * {@code secret} FAILS to start — otherwise it would silently trust forged headers. Set true
         * only to deliberately accept unsigned claims (local single-service dev).
         */
        private boolean allowUnsigned = false;

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }

        public long getClockSkewMs() { return clockSkewMs; }
        public void setClockSkewMs(long clockSkewMs) { this.clockSkewMs = clockSkewMs; }

        public boolean isAllowUnsigned() { return allowUnsigned; }
        public void setAllowUnsigned(boolean allowUnsigned) { this.allowUnsigned = allowUnsigned; }
    }
}
