package com.gme.pay.registry.actor;

import com.gme.pay.audit.AuditActors;
import com.gme.pay.internalauth.InternalAuthHeaders;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides what a request is allowed to claim about who is acting, and returns the answer in
 * the {@link AuditActors} vocabulary. This is the fix for the first half of gap T5-1: the
 * audited actor was unauthenticated client input.
 *
 * <h2>The problem</h2>
 *
 * <p>Every write endpoint took {@code @RequestHeader(value = "X-Actor", required = false)}
 * and 24 service classes fell back to {@code DEFAULT_ACTOR = "system"} when it was absent.
 * The api-gateway never stamped, validated or stripped that header — it handles the
 * {@code X-Gme-*} family and {@code X-Actor} is outside that set. So anyone with network
 * reach to this service could attribute a fee change, a credential rotation, a prefunding
 * limit or a KYB verdict to any operator name they liked, or omit the header and have the
 * change recorded as {@code "system"} — which is also the blanket carve-out in the 4-eyes
 * CHECK ({@code V005}), so a header-less propose plus a header-less approve self-approved.
 *
 * <h2>What identity this service can actually verify</h2>
 *
 * <p>Being precise about this matters more than the code, because the honest answer bounds
 * what can be claimed. config-registry <b>does not authenticate operators</b>. Its own
 * {@code application.properties} says so: <i>"The operator surface
 * ({@code /v1/partners/**}, {@code /v1/schemes/**}, …) is authenticated at the api-gateway /
 * ops BFF and is deliberately NOT gated here."</i> There is no resource server, no JWT
 * decoder, no JWKS — so this service cannot itself validate a token subject the way
 * {@code ops-partner-bff} does (see {@code OpsRbacGuard.actor()}, which reads the verified
 * {@code TokenClaims} subject).
 *
 * <p>What it CAN verify is the <b>caller</b>: the platform's shared internal-auth secret
 * ({@link InternalAuthHeaders#INTERNAL_TOKEN}, already configured here as
 * {@code gmepay.internal-auth.secret}). A request carrying that secret came from a trusted
 * in-cluster service, and the ops BFF derives {@code X-Actor} from a JWT subject it verified
 * itself. So the trust chain is: <i>secret proves the caller is the BFF → the BFF verified
 * the human → the forwarded name is attested</i>. That is a delegated attestation, not a
 * locally-verified one, and it is exactly as strong as the internal secret.
 *
 * <h2>The rule</h2>
 *
 * <table border="1">
 *   <caption>Actor resolution</caption>
 *   <tr><th>Caller proved itself?</th><th>{@code X-Actor} present?</th><th>Recorded as</th></tr>
 *   <tr><td>yes (valid internal token)</td><td>yes</td>
 *       <td>{@code alice@gme.com} — attested, via {@link AuditActors#attested}</td></tr>
 *   <tr><td>yes</td><td>no</td>
 *       <td>{@code svc:config-registry-caller} — the service is attested, the human is
 *           genuinely unknown, and the row says which of the two we know</td></tr>
 *   <tr><td>no</td><td>yes</td>
 *       <td>{@code unverified:alice@gme.com} — the claim is preserved for forensics but is
 *           structurally impossible to mistake for a real principal</td></tr>
 *   <tr><td>no</td><td>no</td>
 *       <td>{@code unattributed}</td></tr>
 * </table>
 *
 * <h2>Why an unproven claim is recorded rather than rejected</h2>
 *
 * <p>Rejecting the write (401) would be the stronger control and is where this should end
 * up. It is not what this change does, deliberately: {@code ops-partner-bff}'s
 * {@code RestConfigRegistryClient} sends {@code X-Actor} but <b>does not</b> send
 * {@code X-Gme-Internal} on the partner/scheme endpoints, and that client is owned by
 * another change in flight. Failing closed today would break every operator write in the
 * platform. So this lands as fail-visible instead of fail-closed: nothing is attributed
 * that was not proven, and the unproven writes are countable
 * ({@code SELECT count(*) FROM audit_log WHERE actor_id LIKE 'unverified:%'}) so the
 * follow-up is measurable rather than asserted.
 *
 * <p><b>Follow-up to close it fully:</b> have {@code RestConfigRegistryClient} present the
 * internal token on the config-registry calls (it already does for auth-identity and
 * prefunding), then flip {@code gmepay.audit.actor.require-attestation=true} — which makes
 * this resolver reject an unattested write outright.
 */
@Component
public class AuditActorResolver {

    private static final Logger log = LoggerFactory.getLogger(AuditActorResolver.class);

    /** The claimed-operator header. Unchanged wire contract; no longer trusted as-is. */
    public static final String ACTOR_HEADER = "X-Actor";

    /**
     * Request attribute set once per request by {@link AuditActorResolver#resolve} so the
     * same request never resolves to two different actors (and so the resolution is
     * observable from a filter or an error handler).
     */
    static final String ATTRIBUTE = AuditActorResolver.class.getName() + ".actor";

    /**
     * Actor recorded when a trusted service authenticated but forwarded no human principal.
     * Named for the role, not for a specific service, because the shared internal secret
     * does not distinguish which service presented it — claiming it identified the BFF
     * specifically would be a lie the token cannot support.
     */
    static final String CALLER_SERVICE = "internal-caller";

    private final String internalSecret;
    private final boolean requireAttestation;
    private final boolean trustForwardedIp;

    public AuditActorResolver(
            @Value("${gmepay.internal-auth.secret:}") String internalSecret,
            @Value("${gmepay.audit.actor.require-attestation:false}") boolean requireAttestation,
            @Value("${gmepay.audit.actor.trust-forwarded-ip:false}") boolean trustForwardedIp) {
        this.internalSecret = internalSecret == null ? "" : internalSecret.trim();
        this.requireAttestation = requireAttestation;
        this.trustForwardedIp = trustForwardedIp;
        if (this.internalSecret.isEmpty()) {
            log.warn("audit-actor: gmepay.internal-auth.secret is not configured — NO caller can "
                    + "be attested, so every audited write will be recorded as '{}...' or '{}'. "
                    + "This is honest but useless for attribution; set the secret (gap T5-1).",
                    AuditActors.UNVERIFIED_PREFIX, AuditActors.UNATTRIBUTED);
        }
    }

    /**
     * Resolve the actor for the current request. Never returns {@code null} or blank, and
     * never returns the bare {@code "system"} literal.
     *
     * @throws UnattestedActorException when {@code gmepay.audit.actor.require-attestation}
     *         is on and the caller could not be attested.
     */
    public String resolve(HttpServletRequest request) {
        if (request == null) {
            // No request context at all: a scheduler or an @PostConstruct path called into a
            // service method that takes an actor. That is a genuine system action, but it is
            // NOT this class's job to name the component — a resolver that invents
            // "system:something" here would re-create the guess-a-plausible-principal bug in
            // a new place. Such call paths pass AuditActors.system("<their component>")
            // explicitly and never reach this method.
            return AuditActors.UNATTRIBUTED;
        }
        Object cached = request.getAttribute(ATTRIBUTE);
        if (cached instanceof String s) {
            return s;
        }
        String resolved = compute(request);
        request.setAttribute(ATTRIBUTE, resolved);
        return resolved;
    }

    private String compute(HttpServletRequest request) {
        String claimed = trimToNull(request.getHeader(ACTOR_HEADER));
        boolean attested = callerIsTrustedService(request);

        if (attested) {
            if (claimed == null) {
                return AuditActors.service(CALLER_SERVICE);
            }
            try {
                return AuditActors.attested(claimed);
            } catch (IllegalArgumentException e) {
                // The forwarded subject collides with one of our reserved namespaces (a
                // caller trying to forge "system:auto-suspend" through an attested channel,
                // or a genuinely odd subject). Downgrade rather than accept: an attested
                // channel is not a licence to mint a system principal.
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
     * The client IP to record. Defaults to the transport-level peer address, which is the
     * only value that is not client-supplied.
     *
     * <p>{@code X-Forwarded-For} is honoured ONLY when the caller was attested AND
     * {@code gmepay.audit.actor.trust-forwarded-ip} is on, because an unauthenticated
     * {@code X-Forwarded-For} is a free-text field. {@code OpsControlService} previously read
     * it unconditionally, which meant the one place in the service that recorded an IP at all
     * recorded whatever the client typed.
     */
    public String resolveIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        if (trustForwardedIp && callerIsTrustedService(request)) {
            String forwarded = trimToNull(request.getHeader("X-Forwarded-For"));
            if (forwarded != null) {
                // Left-most entry is the originating client per the de-facto convention.
                String first = trimToNull(forwarded.split(",")[0]);
                if (first != null) {
                    return clampIp(first);
                }
            }
        }
        return clampIp(request.getRemoteAddr());
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
