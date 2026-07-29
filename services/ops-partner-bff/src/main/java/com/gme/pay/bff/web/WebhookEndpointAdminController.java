package com.gme.pay.bff.web;

import com.gme.pay.bff.client.OperatorActionAuditClient;
import com.gme.pay.bff.client.PartnerDirectory;
import com.gme.pay.bff.client.WebhookOpsClient;
import com.gme.pay.rbac.RbacHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Operator surface for partner <b>webhook endpoint secrets</b> — gap <b>T5-8</b>.
 *
 * <pre>
 * GET  /v1/admin/webhooks/endpoints[?partnerCode=]                — which endpoints can be signed for
 * POST /v1/admin/webhooks/endpoints/{endpointId}/rotate-secret    — rotate, revealing the new secret ONCE
 * </pre>
 *
 * <h2>Why this controller exists</h2>
 *
 * <p>T5-4 moved webhook signing from one global HMAC key to per-endpoint HKDF-derived secrets
 * and made the dispatcher <b>verify the re-derived secret against the digest stored at
 * registration before signing</b>. Rows minted before that change hold digests of CSPRNG
 * secrets whose plaintext was never stored, so they can never be re-derived: their deliveries
 * now correctly refuse to sign rather than sign with a key the partner does not hold. The
 * remedy — {@code POST /v1/webhooks/endpoints/{id}/rotate-secret} on notification-webhook —
 * existed but had <b>no caller anywhere</b>, and the broken endpoints were visible only as an
 * ERROR line per delivery attempt. This controller is the operator's way in.
 *
 * <h2>Authorization</h2>
 *
 * <p>Two layers, both must pass:
 * <ul>
 *   <li>{@link com.gme.pay.bff.config.AdminSurfaceRbacInterceptor} gates the whole
 *       {@code /v1/admin/**} URL space — {@code requireAdminRead()} on GET,
 *       {@code requireAdminWrite()} on POST;</li>
 *   <li>the rotate handler additionally calls {@link OpsRbacGuard#requireOps()}, the same
 *       fine-grained check the webhook-replay and platform-pause actions make. Rotation
 *       invalidates a secret a live partner integration is using, so the read-only
 *       HUB_OPERATOR grant set must not be able to fire it.</li>
 * </ul>
 *
 * <h2>One-time reveal (SEC-09 §4)</h2>
 *
 * <p>The rotate response carries the new {@code whsec_} plaintext. It is returned once and is
 * unrecoverable afterwards: notification-webhook stores only its digest, the BFF stores nothing
 * at all, and {@code IssuedCredentialBundleLogMaskingFilter} marks this path so no
 * body-logging component may capture it. This mirrors the partner-credential rotation flow.
 *
 * <h2>Not auto-rotating</h2>
 *
 * <p>The GET reports {@code fixableByRotation} and stops there. Rotation is deliberately NOT
 * automatic: the new secret must be communicated to the partner out of band, and a secret
 * rotated behind an operator's back would leave the partner verifying against a value nobody
 * told them about — a silent failure worse than the one being fixed.
 */
@RestController
@RequestMapping("/v1/admin/webhooks/endpoints")
public class WebhookEndpointAdminController {

    /** Audit verb for the rotation (the audit row never carries the secret). */
    static final String AUDIT_ACTION = "webhook.endpoint.rotate_secret";

    private final WebhookOpsClient webhooks;
    private final OperatorActionAuditClient audit;
    private final PartnerDirectory partners;
    private final OpsRbacGuard rbac;

    public WebhookEndpointAdminController(WebhookOpsClient webhooks,
                                         OperatorActionAuditClient audit,
                                         PartnerDirectory partners,
                                         OpsRbacGuard rbac) {
        this.webhooks = webhooks;
        this.audit = audit;
        this.partners = partners;
        this.rbac = rbac;
    }

    /**
     * Signing health of the active webhook endpoints — the T5-8 read.
     *
     * <p>{@code partnerCode} is the partner BUSINESS code the Admin UI holds; endpoints are
     * keyed by config-registry's numeric surrogate upstream, so {@link PartnerDirectory}
     * bridges them. An unresolvable code yields an EMPTY list rather than the platform-wide
     * sweep — a code we cannot resolve must never silently widen a partner-scoped read into a
     * cross-partner one.
     *
     * <p>Omitting {@code partnerCode} is the deliberate platform-wide sweep: that is how an
     * operator finds every endpoint that stopped delivering, without knowing which partners to
     * ask about.
     */
    @GetMapping
    public List<WebhookOpsClient.EndpointSigningHealth> signingHealth(
            @RequestParam(name = "partnerCode", required = false) String partnerCode) {
        if (partnerCode == null || partnerCode.isBlank()) {
            return webhooks.endpointSigningHealth(null);
        }
        Optional<Long> numericPartner = partners.numericIdOf(partnerCode);
        if (numericPartner.isEmpty()) {
            // Fail closed: no rows, never an unscoped query.
            return List.of();
        }
        return webhooks.endpointSigningHealth(numericPartner.get());
    }

    /**
     * Rotates one endpoint's signing secret and reveals the new plaintext ONCE.
     *
     * <p>Body (all optional): {@code { "reason": "...", "overlapMinutes": 1440 }}.
     * {@code overlapMinutes} controls how long the retired secret keeps riding along as a
     * second signature so the partner can redeploy on their own schedule; omitted, the upstream
     * default (24 h) applies; {@code 0} cuts over immediately.
     *
     * <p>A durable operator-action audit row is written BEFORE the rotation (fail-closed: if
     * the audit cannot be written the secret is not rotated), recording who rotated what and
     * why — never the secret itself.
     */
    @PostMapping("/{endpointId}/rotate-secret")
    public WebhookOpsClient.RotatedWebhookSecret rotateSecret(
            @PathVariable String endpointId,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = RbacHeaders.PRINCIPAL_ID, required = false) String principal) {
        rbac.requireOps();
        String actor = rbac.actor(principal);
        audit.recordDurable(AUDIT_ACTION, endpointId, actor, reason(body));
        return webhooks.rotateEndpointSecret(endpointId, overlapMinutes(body));
    }

    /** Free-text operator reason from the request body, or null. */
    private static String reason(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        Object reason = body.get("reason");
        return reason == null ? null : String.valueOf(reason);
    }

    /**
     * Optional {@code overlapMinutes} from the body. Accepts a number or a numeric string
     * (JSON from a form field); anything else is a 400 rather than a silent default, because
     * quietly substituting 24 h for what the operator typed is not a decision we get to make.
     */
    private static Long overlapMinutes(Map<String, Object> body) {
        if (body == null || body.get("overlapMinutes") == null) {
            return null;
        }
        Object raw = body.get("overlapMinutes");
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "overlapMinutes must be a number, was: " + raw);
        }
    }
}
