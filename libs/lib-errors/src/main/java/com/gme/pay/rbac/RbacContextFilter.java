package com.gme.pay.rbac;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reconstructs the caller's {@link PermissionContext} from the {@link RbacHeaders} the edge stamped,
 * and binds it to the thread for the duration of the request (cleared in finally). Runs early so the
 * {@link RbacPermissionInterceptor} sees the context at handler dispatch. No DB / network access —
 * the headers already carry the resolved permissions.
 *
 * <h2>Provenance (anti-spoof)</h2>
 * The {@code X-Gme-*} headers are trusted across a network boundary, so a request that reaches a
 * service directly (bypassing the gateway) could otherwise forge them. When a verification secret is
 * configured ({@code gmepay.rbac.verify.secret}) this filter runs in STRICT mode: stamped claims are
 * trusted only with a fresh, valid {@link RbacHeaders#SIGNATURE} the gateway produced with the shared
 * secret (see {@link RbacClaimSigner}). The signature binds <b>every</b> authorization-bearing header —
 * the authority claims AND the constraint-context attributes (country/region/office/approval-granted)
 * — so appending or altering any of them yields {@link PermissionContext#ANONYMOUS} and
 * {@code @RequiresPermission} denies. With a blank secret the filter stays in LEGACY trust mode (local
 * dev / single-service tests); {@code RbacAutoConfiguration} fails a service closed if it enforces
 * without a secret, so legacy mode cannot be reached by misconfiguration in production.
 */
public class RbacContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RbacContextFilter.class);

    private final String verifySecret;   // blank => legacy trust (no signature required)
    private final long clockSkewMs;

    /** Legacy / test constructor: trust stamped headers without signature verification. */
    public RbacContextFilter() {
        this("", 300_000L);
    }

    public RbacContextFilter(String verifySecret, long clockSkewMs) {
        this.verifySecret = verifySecret == null ? "" : verifySecret;
        this.clockSkewMs = clockSkewMs;
    }

    /** The full set of edge-stamped, signature-bound claim values for one request. */
    record StampedClaims(String principalId, String tenantId, String permissions, String roles,
                         String constraints, String country, String region, String office,
                         String approvalGranted) {}

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            RbacContextHolder.set(resolveContext(request));
            filterChain.doFilter(request, response);
        } finally {
            RbacContextHolder.clear();
        }
    }

    private PermissionContext resolveContext(HttpServletRequest request) {
        StampedClaims c = new StampedClaims(
                request.getHeader(RbacHeaders.PRINCIPAL_ID),
                request.getHeader(RbacHeaders.TENANT_ID),
                request.getHeader(RbacHeaders.PERMISSIONS),
                request.getHeader(RbacHeaders.ROLES),
                request.getHeader(RbacHeaders.CONSTRAINTS),
                request.getHeader(RbacHeaders.COUNTRY),
                request.getHeader(RbacHeaders.REGION),
                request.getHeader(RbacHeaders.OFFICE),
                request.getHeader(RbacHeaders.APPROVAL_GRANTED));

        if (!verifySecret.isBlank()) {
            boolean claimsPresent = notBlank(c.principalId()) || notBlank(c.tenantId())
                    || notBlank(c.permissions()) || notBlank(c.roles()) || notBlank(c.constraints())
                    || notBlank(c.country()) || notBlank(c.region()) || notBlank(c.office())
                    || notBlank(c.approvalGranted());
            if (!claimsPresent) {
                return PermissionContext.ANONYMOUS;   // nothing stamped — anonymous, no warning
            }
            if (!verifySignature(c, request.getHeader(RbacHeaders.SIGNATURE),
                    request.getHeader(RbacHeaders.SIGNATURE_TS),
                    System.currentTimeMillis(), request.getRequestURI())) {
                return PermissionContext.ANONYMOUS;   // present but unverifiable — refuse to trust
            }
        }
        return new PermissionContext(trimToNull(c.principalId()), trimToNull(c.tenantId()),
                csv(c.permissions()), csv(c.roles()));
    }

    /**
     * STRICT-mode signature check over the exact header values received. Package-private for tests.
     * Logs a security warning on any failure (claims were present, so a failure is a spoof signal).
     * {@code nowMs} is injected so freshness can be tested deterministically.
     */
    boolean verifySignature(StampedClaims c, String sig, String tsRaw, long nowMs, String path) {
        if (sig == null || sig.isBlank() || tsRaw == null || tsRaw.isBlank()) {
            log.warn("RBAC claims present without signature — refusing to trust (possible spoof). path={}", path);
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(tsRaw.trim());
        } catch (NumberFormatException e) {
            log.warn("RBAC signature timestamp malformed ('{}') — refusing. path={}", tsRaw, path);
            return false;
        }
        long age = Math.abs(nowMs - ts);
        if (age > clockSkewMs) {
            log.warn("RBAC signature stale ({}ms > {}ms skew) — refusing. path={}", age, clockSkewMs, path);
            return false;
        }
        String canonical = RbacClaimSigner.canonical(ts, c.principalId(), c.tenantId(), c.permissions(),
                c.roles(), c.constraints(), c.country(), c.region(), c.office(), c.approvalGranted());
        if (!RbacClaimSigner.verify(sig, canonical, verifySecret)) {
            log.warn("RBAC signature invalid — refusing to trust stamped claims (possible spoof). path={}", path);
            return false;
        }
        return true;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trimToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static Set<String> csv(String header) {
        if (header == null || header.isBlank()) return Set.of();
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
