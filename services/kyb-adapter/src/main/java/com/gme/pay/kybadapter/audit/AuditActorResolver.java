package com.gme.pay.kybadapter.audit;

import com.gme.pay.audit.AuditActors;
import com.gme.pay.internalauth.InternalAuthHeaders;
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
 * Decides what a request to kyb-adapter is allowed to claim about who asked for a screening, and
 * answers in the {@link AuditActors} vocabulary. Gaps T5-1 (non-spoofable actor) and T5-3.
 *
 * <h2>What identity kyb-adapter can actually verify</h2>
 *
 * <p>Being precise about this bounds what may be claimed, so state it plainly: kyb-adapter <b>does not
 * authenticate operators</b>. It has no resource server, no JWT decoder and no JWKS. What it does have
 * is a <b>mandatory</b> internal-auth gate — {@code gmepay.internal-auth.enabled} is pinned true and
 * {@link com.gme.pay.kybadapter.KybInternalAuthEnforcedConfig} refuses to start the service if the flag
 * or the secret is missing — covering every endpoint that takes or returns a screening subject. So any
 * request reaching a controller here has proved it came from a trusted in-cluster caller.
 *
 * <p>That gives a delegated attestation: <i>the shared secret proves the caller is a platform service →
 * that service verified the human → the forwarded {@code X-Actor} is attested</i>. It is exactly as
 * strong as the internal secret and no stronger, which is why the {@code svc:} and {@code unverified:}
 * namespaces below still exist.
 *
 * <h2>The rule</h2>
 *
 * <table border="1">
 *   <caption>Actor resolution</caption>
 *   <tr><th>Valid internal token?</th><th>{@code X-Actor} present?</th><th>Recorded as</th></tr>
 *   <tr><td>yes</td><td>yes</td><td>{@code alice@gme.com} — attested</td></tr>
 *   <tr><td>yes</td><td>no</td><td>{@code svc:internal-caller} — the caller is attested, the human is
 *       genuinely unknown, and the row says which of the two we know</td></tr>
 *   <tr><td>no</td><td>yes</td><td>{@code unverified:alice@gme.com} — kept for forensics, structurally
 *       impossible to mistake for a real principal</td></tr>
 *   <tr><td>no</td><td>no</td><td>{@code unattributed}</td></tr>
 * </table>
 *
 * <p>Rows three and four should be unreachable in a correctly-configured deployment (the gate 401s such
 * a request before any controller runs). They are implemented anyway because the resolver must not
 * depend on another component's configuration for its honesty, and because {@code MockMvc} slices can
 * bypass the filter chain. If they ever appear in production they are countable — {@code SELECT count(*)
 * FROM audit_log WHERE actor_id LIKE 'unverified:%' OR actor_id = 'unattributed'} — which is the point:
 * a hole shows up as a number in a report rather than as a plausible-looking operator name.
 *
 * <p>On the payment path the expected value is {@code svc:internal-caller}: a transaction screening is
 * asked for by a service on behalf of a customer, not by a named operator. That is the truthful answer
 * and it is deliberately not dressed up as anything more specific — the shared secret does not
 * distinguish which service presented it.
 *
 * <p>This is a per-service copy of prefunding's resolver of the same name, kept in step with it.
 */
@Component
public class AuditActorResolver {

    private static final Logger log = LoggerFactory.getLogger(AuditActorResolver.class);

    /** The claimed-operator header. Unauthenticated by itself; never trusted as-is. */
    public static final String ACTOR_HEADER = "X-Actor";

    /** Request attribute holding the resolved actor, so one request resolves exactly once. */
    static final String ACTOR_ATTRIBUTE = AuditActorResolver.class.getName() + ".actor";

    /** Companion attribute for the resolved client IP. */
    static final String IP_ATTRIBUTE = AuditActorResolver.class.getName() + ".actorIp";

    /**
     * Recorded when a trusted caller authenticated but forwarded no human principal. Named for the role
     * rather than for a specific service because the shared secret does not distinguish which service
     * presented it — claiming it identified payment-executor would be a lie the token cannot support.
     */
    static final String CALLER_SERVICE = "internal-caller";

    private final String internalSecret;
    private final boolean trustForwardedIp;

    public AuditActorResolver(
            @Value("${gmepay.internal-auth.secret:}") String internalSecret,
            @Value("${gmepay.audit.actor.trust-forwarded-ip:false}") boolean trustForwardedIp) {
        this.internalSecret = internalSecret == null ? "" : internalSecret.trim();
        this.trustForwardedIp = trustForwardedIp;
        if (this.internalSecret.isEmpty()) {
            log.warn("audit-actor: gmepay.internal-auth.secret is not configured — NO caller can be "
                            + "attested, so every audited screening would be recorded as '{}…' or '{}'. "
                            + "(KybInternalAuthEnforcedConfig should already have refused to start.)",
                    AuditActors.UNVERIFIED_PREFIX, AuditActors.UNATTRIBUTED);
        }
    }

    /**
     * Resolve (and memoise on the request) the actor for {@code request}. Never returns null, blank or
     * the bare {@code "system"} literal.
     */
    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return AuditActors.UNATTRIBUTED;
        }
        Object cached = request.getAttribute(ACTOR_ATTRIBUTE);
        if (cached instanceof String s) {
            return s;
        }
        String resolved = compute(request);
        request.setAttribute(ACTOR_ATTRIBUTE, resolved);
        return resolved;
    }

    private String compute(HttpServletRequest request) {
        String claimed = trimToNull(request.getHeader(ACTOR_HEADER));
        if (callerIsTrustedService(request)) {
            if (claimed == null) {
                return AuditActors.service(CALLER_SERVICE);
            }
            try {
                return AuditActors.attested(claimed);
            } catch (IllegalArgumentException e) {
                // The forwarded subject collides with a reserved namespace — someone trying to forge
                // "system:something" through an attested channel, or a genuinely odd subject. Downgrade
                // rather than accept: an attested channel is not a licence to mint a system principal.
                log.warn("audit-actor: attested caller forwarded an unusable subject ({}) — recording it "
                        + "as a claim, not as a principal", e.getMessage());
                return AuditActors.unverified(claimed);
            }
        }
        return AuditActors.unverified(claimed);
    }

    /**
     * The client IP to record. Defaults to the transport-level peer address — the only value in the
     * request that is not client-supplied. {@code X-Forwarded-For} is honoured only when the caller was
     * attested AND {@code gmepay.audit.actor.trust-forwarded-ip} is on, because an unauthenticated
     * {@code X-Forwarded-For} is free text.
     */
    public String resolveIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object cached = request.getAttribute(IP_ATTRIBUTE);
        if (cached instanceof String s) {
            return s;
        }
        String ip = computeIp(request);
        if (ip != null) {
            request.setAttribute(IP_ATTRIBUTE, ip);
        }
        return ip;
    }

    private String computeIp(HttpServletRequest request) {
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

    /**
     * The actor resolved for the request currently being served, for write paths that take no actor
     * argument. Reads the attribute {@link AuditActorFilter} set once per request, so the answer is
     * identical to what {@link #resolve} returned at the edge.
     *
     * <p>{@link AuditActors#UNATTRIBUTED} off-request. Background jobs must pass their own named system
     * principal instead of leaning on that.
     */
    public String currentActor() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return AuditActors.UNATTRIBUTED;
        }
        return resolve(request);
    }

    /** Companion to {@link #currentActor()} for the client IP; {@code null} off-request. */
    public String currentActorIp() {
        HttpServletRequest request = currentRequest();
        return request == null ? null : resolveIp(request);
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
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

    /** {@code actor_ip} is {@code VARCHAR(45)} (IPv6 textual length). */
    private static String clampIp(String ip) {
        String v = trimToNull(ip);
        if (v == null) {
            return null;
        }
        return v.length() <= 45 ? v : v.substring(0, 45);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
