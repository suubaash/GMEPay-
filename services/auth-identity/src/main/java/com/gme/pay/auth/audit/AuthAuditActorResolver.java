package com.gme.pay.auth.audit;

import com.gme.pay.audit.AuditActors;
import com.gme.pay.internalauth.InternalAuthHeaders;
import com.gme.pay.rbac.RbacHeaders;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Decides what an incoming request is allowed to claim about <b>who is acting</b>, and answers
 * in the {@link AuditActors} vocabulary. Half of gap T5-1 is that audit rows had no actor at
 * all in this service; the other half — the half that makes a trail worse than useless rather
 * than merely absent — is recording an unauthenticated header as though it were an identity.
 *
 * <h2>What auth-identity can actually verify about a caller</h2>
 *
 * <p>Being exact about this bounds what may be claimed, so it is worth stating plainly.
 *
 * <p>auth-identity does <b>not authenticate human operators</b>. Per ADR-011 that is Keycloak's
 * job; this service issues and verifies <i>machine</i> credentials. It has no resource server,
 * no JWKS, no session. It cannot look at a request and know that Alice is behind it.
 *
 * <p>What it can verify is the <b>caller service</b>: the platform's shared internal-auth
 * secret ({@link InternalAuthHeaders#INTERNAL_TOKEN}), which is already mandatory on every
 * route this service exposes ({@code InternalAuthEnforcedConfig} refuses to boot otherwise).
 * The trust chain is therefore delegated: <i>the secret proves the caller is the api-gateway or
 * the ops BFF → that caller verified the human against Keycloak → the operator id it forwards
 * is attested</i>. That is exactly as strong as the shared secret and no stronger, which is why
 * the resulting actor is recorded as an attested principal only when the secret checked out.
 *
 * <h2>The rule</h2>
 *
 * <table border="1">
 *   <caption>Actor resolution</caption>
 *   <tr><th>Caller proved itself?</th><th>Operator claim present?</th><th>Recorded as</th></tr>
 *   <tr><td>yes (valid internal token)</td><td>yes</td>
 *       <td>{@code alice@gme.com} — attested, via {@link AuditActors#attested}</td></tr>
 *   <tr><td>yes</td><td>no</td>
 *       <td>{@code svc:internal-caller} — the service is attested, the human is genuinely
 *           unknown, and the row says which of the two is known</td></tr>
 *   <tr><td>no</td><td>yes</td>
 *       <td>{@code unverified:alice@gme.com} — the claim is kept for forensics but is
 *           structurally impossible to mistake for, or join against, a real principal</td></tr>
 *   <tr><td>no</td><td>no</td>
 *       <td>{@code unattributed}</td></tr>
 * </table>
 *
 * <p>The operator claim is read from {@link RbacHeaders#PRINCIPAL_ID} first — the header the
 * ops BFF already stamps and {@code ApprovalController} already reads — and from the
 * platform's {@code X-Actor} header second, so the RBAC admin surface (which today forwards
 * neither reliably) can be attributed the moment its caller starts stamping one.
 *
 * <h2>Why an unproven claim is recorded rather than rejected</h2>
 *
 * <p>Rejecting the write would be the stronger control, and {@code gmepay.audit.actor
 * .require-attestation=true} does exactly that. It is <b>not</b> the default, deliberately:
 * the internal-auth gate proves the caller, but no caller of {@code /v1/rbac/**} stamps an
 * operator id yet, so failing closed today would break every RBAC write in the platform.
 * This therefore lands as fail-visible rather than fail-closed — nothing is attributed that
 * was not proven, and the unattributed writes are <i>countable</i>
 * ({@code SELECT count(*) FROM audit_log WHERE actor_id LIKE 'unverified:%' OR actor_id =
 * 'unattributed'}), so the residual gap is a number in a report instead of a claim.
 *
 * <h2>Authentication failures are the point, not an edge case</h2>
 *
 * <p>A rejected login has, by construction, no verified subject — that is what "rejected"
 * means. Such a row is attributed {@code unverified:<claim>} or {@code unattributed}, and the
 * failure detail (which api key prefix, which error, from which IP) lives in the payload. An
 * implementation that "helpfully" attributed a failed attempt to the principal it was
 * impersonating would be forging evidence against that principal.
 */
@Component
public class AuthAuditActorResolver {

    private static final Logger log = LoggerFactory.getLogger(AuthAuditActorResolver.class);

    /** The platform's claimed-operator header (same wire name config-registry uses). */
    public static final String ACTOR_HEADER = "X-Actor";

    /**
     * Recorded when a trusted service authenticated but forwarded no human principal. Named for
     * the ROLE, not for a specific service: the shared secret does not distinguish which
     * service presented it, so naming the BFF here would be a claim the token cannot support.
     */
    public static final String CALLER_SERVICE = "internal-caller";

    /** Per-request memo so one request can never resolve to two different actors. */
    static final String REQUEST_ATTRIBUTE = AuthAuditActorResolver.class.getName() + ".actor";

    /** {@code audit_log.actor_ip} is {@code VARCHAR(45)} (IPv6 textual length). */
    private static final int MAX_IP_LEN = 45;

    private final String internalSecret;
    private final boolean requireAttestation;
    private final boolean trustForwardedIp;

    public AuthAuditActorResolver(
            @Value("${gmepay.internal-auth.secret:}") String internalSecret,
            @Value("${gmepay.audit.actor.require-attestation:false}") boolean requireAttestation,
            @Value("${gmepay.audit.actor.trust-forwarded-ip:false}") boolean trustForwardedIp) {
        this.internalSecret = internalSecret == null ? "" : internalSecret.trim();
        this.requireAttestation = requireAttestation;
        this.trustForwardedIp = trustForwardedIp;
        if (this.internalSecret.isEmpty()) {
            // Unreachable in a booted service (InternalAuthEnforcedConfig refuses to start on a
            // blank secret) but reachable in a slice test, and worth saying out loud rather than
            // silently degrading every row to "unattributed".
            log.warn("audit-actor: gmepay.internal-auth.secret is not configured — no caller can "
                            + "be attested, so every audited action will be recorded as '{}…' or "
                            + "'{}'. Honest, but useless for attribution (gap T5-1).",
                    AuditActors.UNVERIFIED_PREFIX, AuditActors.UNATTRIBUTED);
        }
    }

    /**
     * The actor for the request currently bound to this thread, or {@link
     * AuditActors#UNATTRIBUTED} when there is no request at all.
     *
     * <p>Reading the request from {@link RequestContextHolder} rather than threading an
     * {@code HttpServletRequest} (or a resolved actor) through every service signature is a
     * deliberate trade. The alternative would have meant changing the signature of every
     * audited method in four service classes and every one of their call sites, which is a
     * large diff whose only content is plumbing — and any signature it missed would silently
     * fall back to an unattributed row. This way there is one place that can be wrong.
     *
     * <p>No request bound is the normal case for a scheduler or a slice test. It resolves to
     * {@code unattributed} rather than to an invented {@code system:…} principal: a genuine
     * platform action names its own component via {@link AuditActors#system(String)} at the
     * call site, and guessing a plausible principal here is precisely the bug T5-1 is about.
     */
    public String currentActor() {
        HttpServletRequest request = currentRequest();
        return request == null ? AuditActors.UNATTRIBUTED : resolve(request);
    }

    /** The client IP for the request currently bound to this thread, or {@code null}. */
    public String currentIp() {
        HttpServletRequest request = currentRequest();
        return request == null ? null : resolveIp(request);
    }

    /**
     * Resolve the actor for an explicit request. Never returns {@code null}, blank, or the bare
     * {@code "system"} literal.
     *
     * @throws UnattestedActorException when {@code gmepay.audit.actor.require-attestation} is on
     *         and the caller could not be attested.
     */
    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return AuditActors.UNATTRIBUTED;
        }
        Object memo = request.getAttribute(REQUEST_ATTRIBUTE);
        if (memo instanceof String cached) {
            return cached;
        }
        String resolved = compute(request);
        request.setAttribute(REQUEST_ATTRIBUTE, resolved);
        return resolved;
    }

    /**
     * The IP to record. Defaults to the transport-level peer address — the only value on the
     * request that the client cannot choose.
     *
     * <p>{@code X-Forwarded-For} is honoured only when the caller was attested AND
     * {@code gmepay.audit.actor.trust-forwarded-ip} is on, because an unauthenticated
     * {@code X-Forwarded-For} is free text: trusting it by default would mean the one column
     * that records where an action came from records whatever the actor typed.
     */
    public String resolveIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        if (trustForwardedIp && callerIsTrustedService(request)) {
            String forwarded = trimToNull(request.getHeader("X-Forwarded-For"));
            if (forwarded != null) {
                String first = trimToNull(forwarded.split(",")[0]);
                if (first != null) {
                    return clampIp(first);
                }
            }
        }
        return clampIp(request.getRemoteAddr());
    }

    // -------------------------------------------------------------------------- internals

    private String compute(HttpServletRequest request) {
        String claimed = claimedOperator(request);
        boolean attested = callerIsTrustedService(request);

        if (attested) {
            if (claimed == null) {
                return AuditActors.service(CALLER_SERVICE);
            }
            try {
                return AuditActors.attested(claimed);
            } catch (IllegalArgumentException e) {
                // The forwarded subject collides with a reserved namespace — a caller trying to
                // launder "system:auto-suspend" through an attested channel, or a genuinely odd
                // subject. Downgrade rather than accept: being an attested channel is not a
                // licence to mint a platform principal.
                log.warn("audit-actor: attested caller forwarded an unusable subject ({}) — "
                        + "recording it as a claim, not as a principal", e.getMessage());
                return AuditActors.unverified(claimed);
            }
        }

        if (requireAttestation) {
            throw new UnattestedActorException();
        }
        return AuditActors.unverified(claimed);
    }

    /**
     * The operator id the caller claims. {@link RbacHeaders#PRINCIPAL_ID} first (what the ops
     * BFF stamps today), {@code X-Actor} second (the platform's other claimed-operator header).
     */
    private static String claimedOperator(HttpServletRequest request) {
        String principal = trimToNull(request.getHeader(RbacHeaders.PRINCIPAL_ID));
        return principal != null ? principal : trimToNull(request.getHeader(ACTOR_HEADER));
    }

    /** Constant-time comparison of the presented internal token against the configured secret. */
    private boolean callerIsTrustedService(HttpServletRequest request) {
        if (internalSecret.isEmpty()) {
            return false;
        }
        String presented = trimToNull(request.getHeader(InternalAuthHeaders.INTERNAL_TOKEN));
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                internalSecret.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }

    private static String clampIp(String ip) {
        String v = trimToNull(ip);
        if (v == null) {
            return null;
        }
        return v.length() <= MAX_IP_LEN ? v : v.substring(0, MAX_IP_LEN);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Thrown when {@code gmepay.audit.actor.require-attestation} is on and the request could
     * not be attributed. A 401 rather than a 400: the request is refused because the caller
     * could not be identified, not because the payload was malformed.
     */
    public static class UnattestedActorException extends org.springframework.web.server.ResponseStatusException {
        public UnattestedActorException() {
            super(org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "the acting identity could not be attested and "
                            + "gmepay.audit.actor.require-attestation is on — present the "
                            + InternalAuthHeaders.INTERNAL_TOKEN + " token and stamp "
                            + RbacHeaders.PRINCIPAL_ID);
        }
    }
}
