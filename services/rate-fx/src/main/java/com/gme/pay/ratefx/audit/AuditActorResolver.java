package com.gme.pay.ratefx.audit;

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
 * Decides what a request to rate-fx is allowed to claim about who is setting a treasury rate, and
 * answers in the {@link AuditActors} vocabulary. Gap T5-1 / CISO §9.
 *
 * <h2>Why rate-fx needs this at all</h2>
 *
 * <p>{@code POST /v1/rates/snapshots} appends an effective-dated snapshot, and the latest effective
 * snapshot <b>wins at resolution time</b> — so one {@code source=MANUAL} write re-prices every
 * subsequent quote and payment in that currency. {@code rate_snapshots} has no actor column, so the
 * platform could show you the rate it traded at and not who chose it.
 *
 * <h2>What identity rate-fx can actually verify</h2>
 *
 * <p>rate-fx does <b>not</b> authenticate operators: no resource server, no JWT decoder, no JWKS. It
 * does have a mandatory internal-auth gate over exactly the snapshot path
 * ({@code gmepay.internal-auth.path-patterns}, asserted at startup by
 * {@link com.gme.pay.ratefx.config.InternalAuthEnforcedConfig}), so a request that reaches the admin
 * controller has proved it came from a trusted in-cluster caller.
 *
 * <p>That yields a delegated attestation: <i>the shared secret proves the caller is a platform
 * service → that service verified the human → the forwarded {@code X-Actor} is attested</i>. Exactly
 * as strong as the internal secret, no stronger — hence the {@code svc:} and {@code unverified:}
 * namespaces below.
 *
 * <table border="1">
 *   <caption>Actor resolution</caption>
 *   <tr><th>Caller presented a valid internal token?</th><th>{@code X-Actor} present?</th><th>Recorded as</th></tr>
 *   <tr><td>yes</td><td>yes</td><td>{@code alice@gme.com} — attested</td></tr>
 *   <tr><td>yes</td><td>no</td>
 *       <td>{@code svc:internal-caller} — the caller is attested, the human is unknown, and the row
 *           says which of the two we know. For a manual rate override this is a weak but
 *           <i>honest</i> attribution, and it is countable, which is what makes the residual gap
 *           measurable rather than asserted.</td></tr>
 *   <tr><td>no</td><td>yes</td><td>{@code unverified:alice@gme.com}</td></tr>
 *   <tr><td>no</td><td>no</td><td>{@code unattributed}</td></tr>
 * </table>
 *
 * <p>The bottom two rows should be unreachable in a correctly-configured deployment (the gate 401s
 * such a request first). They are implemented anyway, because this class's honesty must not depend on
 * another component's configuration, and because a MockMvc slice can bypass the filter chain.
 *
 * <h2>The scheduler does not use this</h2>
 *
 * <p>{@link com.gme.pay.ratefx.xe.XeRateFetchScheduler} runs off-request and passes
 * {@link RateAuditor#SYSTEM_XE_FETCH_SCHEDULER} explicitly. {@link #currentActor()} returns
 * {@link AuditActors#UNATTRIBUTED} off-request rather than inventing a plausible
 * {@code system:something} — inventing one is the exact bug this gap is about.
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
     * Recorded when a trusted caller authenticated but forwarded no human principal. Named for the
     * role rather than a specific service: the shared secret does not distinguish which service
     * presented it, so claiming it identified the ops BFF would be a lie the token cannot support.
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
                            + "attested, so every audited rate change would be recorded as '{}…' or "
                            + "'{}'. (InternalAuthEnforcedConfig should already have refused to start.)",
                    AuditActors.UNVERIFIED_PREFIX, AuditActors.UNATTRIBUTED);
        }
    }

    /** Resolve (and memoise on the request) the actor. Never null, blank, or the bare {@code "system"}. */
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
                // The forwarded subject collides with a reserved namespace — a caller trying to pass
                // a manual override off as the XE scheduler, or a genuinely odd subject. Downgrade
                // rather than accept: an attested channel is not a licence to mint a system principal.
                log.warn("audit-actor: attested caller forwarded an unusable subject ({}) — recording "
                        + "it as a claim, not as a principal", e.getMessage());
                return AuditActors.unverified(claimed);
            }
        }
        return AuditActors.unverified(claimed);
    }

    /**
     * The client IP to record. Defaults to the transport-level peer address — the only value in the
     * request that is not client-supplied. {@code X-Forwarded-For} is honoured only when the caller
     * was attested AND {@code gmepay.audit.actor.trust-forwarded-ip} is on.
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
     * The actor resolved for the request currently being served — for write paths that take no actor
     * argument. {@link AuditActors#UNATTRIBUTED} off-request; see the class javadoc.
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
