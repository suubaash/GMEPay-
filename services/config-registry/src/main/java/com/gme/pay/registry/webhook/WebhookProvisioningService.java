package com.gme.pay.registry.webhook;

import com.gme.pay.contracts.IssuedWebhookSubscription;
import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.WebhookEndpointRegistrationCommand;
import com.gme.pay.contracts.WebhookEndpointRegistrationView;
import com.gme.pay.contracts.WebhookSubscriptionCommand;
import com.gme.pay.contracts.WebhookSubscriptionView;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.client.NotificationWebhookClient;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Slice 8 Lane D — owns the {@code partner_webhook_subscription} table (V030)
 * behind two seams:
 *
 * <ol>
 *   <li><b>Wizard step-8 draft save</b> ({@link #saveDraftSubscription}):
 *       persists the webhook URL + event-type selection per environment
 *       BEFORE activation, so the activation transaction can read the draft
 *       and provision accordingly.</li>
 *   <li><b>Activation provisioning</b> ({@link #provisionOnActivation}):
 *       called by Lane A's lifecycle service INSIDE the activation
 *       transaction when a partner transitions to {@code SANDBOX} (and later
 *       {@code LIVE}). Hands the draft off to the notification-webhook
 *       service via {@link NotificationWebhookClient}, stamps
 *       {@code endpoint_id} / {@code signing_secret_hash} /
 *       {@code last_rotated_at} / {@code status=PROVISIONED} on the V030 row,
 *       and returns the {@link IssuedWebhookSubscription} carrying the
 *       ONE-TIME plaintext signing secret. Lane B merges this record into the
 *       {@code IssuedCredentialBundle} alongside the API-key/HMAC
 *       material.</li>
 * </ol>
 *
 * <h2>One-time secret reveal + idempotency</h2>
 *
 * <p>The plaintext signing secret exists only in the activation response:
 * both services store a SHA-256 digest at rest. Re-provisioning an already
 * PROVISIONED environment (activation retry, SANDBOX&rarr;UAT&rarr;LIVE
 * walking past SANDBOX again) short-circuits to the existing endpoint id with
 * a {@code null} secret — a new secret is NOT minted.
 *
 * <h2>Failure path</h2>
 *
 * <p>A notification-webhook failure propagates (502 from
 * {@code RestNotificationWebhookClient}) and rolls the surrounding activation
 * transaction back — a half-activated partner with no webhook wiring is the
 * outcome this service exists to prevent. The V030 row stays DRAFT.
 *
 * <h2>Audit (ADR-007)</h2>
 *
 * <p>One audit row per mutation, {@code aggregateType="partner_webhook_subscription"},
 * keyed by the partner business code, BEFORE/AFTER =
 * {@link WebhookSubscriptionJson} canonical snapshots (secret hash excluded),
 * published inside the same transaction through the
 * {@link ObjectProvider}-resolved {@link AuditLogService} — the same wiring
 * contract as {@code RuleService} / {@code PrefundingConfigService}.
 */
@Service
public class WebhookProvisioningService {

    /** Aggregate-type discriminator on audit rows for webhook-subscription mutations. */
    public static final String AGGREGATE_TYPE = "partner_webhook_subscription";

    /** Audit verb for the step-8 draft save. */
    public static final String EVENT_TYPE_SAVED = "PARTNER_WEBHOOK_SUBSCRIPTION_SAVED";

    /** Audit verb for the activation-time provisioning. */
    public static final String EVENT_TYPE_PROVISIONED = "PARTNER_WEBHOOK_SUBSCRIPTION_PROVISIONED";

    /** V030 CHECK roster for environment. */
    static final Set<String> ENVIRONMENTS = Set.of("SANDBOX", "LIVE");

    private static final int MAX_URL_LENGTH = 512;
    private static final int MAX_EVENT_TYPES = 32;
    private static final int MAX_EVENT_TYPE_LENGTH = 100;

    /** Default actor until the Keycloak {@code sub} claim is threaded through (Slice 1B.4 carve-out). */
    private static final String DEFAULT_ACTOR = "system";

    private final PartnerWebhookSubscriptionRepository subscriptionRepository;
    private final PartnerRepository partnerRepository;
    private final NotificationWebhookClient webhookClient;
    private final ObjectProvider<AuditLogService> auditLogProvider;

    public WebhookProvisioningService(PartnerWebhookSubscriptionRepository subscriptionRepository,
                                      PartnerRepository partnerRepository,
                                      NotificationWebhookClient webhookClient,
                                      ObjectProvider<AuditLogService> auditLogProvider) {
        this.subscriptionRepository = subscriptionRepository;
        this.partnerRepository = partnerRepository;
        this.webhookClient = webhookClient;
        this.auditLogProvider = auditLogProvider;
    }

    /**
     * Save the step-8 draft webhook subscription for one environment —
     * in-place upsert of the (partner, environment) V030 row.
     *
     * @throws ResponseStatusException 404 when no current partner row matches;
     *         409 when the partner has left {@code ONBOARDING} (post-activation
     *         webhook changes ride the change_request approval flow) or when
     *         the row was already PROVISIONED; 400 on validation failure
     *         (missing body / non-HTTPS or over-long url / unknown environment
     *         / malformed event types).
     */
    @Transactional
    public WebhookSubscriptionView saveDraftSubscription(
            String partnerCode,
            PartnerCommand.UpdateStep8WebhookSubscription cmd,
            String actor) {
        if (cmd == null || cmd.subscription() == null) {
            throw badRequest("request body with a webhook subscription is required");
        }
        WebhookSubscriptionCommand subscription = cmd.subscription();
        validate(subscription);

        PartnerEntity partner = requirePartner(partnerCode);
        if (partner.getStatus() != PartnerStatus.ONBOARDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "partner '" + partnerCode + "' is in status " + partner.getStatus()
                            + ", step-8 webhook edits are only permitted while ONBOARDING");
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Optional<PartnerWebhookSubscriptionEntity> priorOpt =
                subscriptionRepository.findByPartnerIdAndEnvironment(
                        partner.getId(), subscription.environment());

        byte[] before = priorOpt.map(WebhookSubscriptionJson::canonical).orElse(null);
        PartnerWebhookSubscriptionEntity row;
        if (priorOpt.isPresent()) {
            row = priorOpt.get();
            if (row.getStatus() != WebhookSubscriptionStatus.DRAFT) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "webhook subscription for partner '" + partnerCode + "' environment "
                                + subscription.environment() + " is " + row.getStatus()
                                + " — provisioned subscriptions change via the approval flow");
            }
            row.setUrl(subscription.url());
            row.setEventTypesCsv(
                    PartnerWebhookSubscriptionEntity.eventTypesToCsv(subscription.eventTypes()));
            row.setUpdatedAt(now);
        } else {
            row = new PartnerWebhookSubscriptionEntity();
            row.setPartnerId(partner.getId());
            row.setEnvironment(subscription.environment());
            row.setUrl(subscription.url());
            row.setEventTypesCsv(
                    PartnerWebhookSubscriptionEntity.eventTypesToCsv(subscription.eventTypes()));
            row.setStatus(WebhookSubscriptionStatus.DRAFT);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
        }
        PartnerWebhookSubscriptionEntity saved = subscriptionRepository.saveAndFlush(row);
        publishAudit(partnerCode, actor, EVENT_TYPE_SAVED,
                before, WebhookSubscriptionJson.canonical(saved));
        return saved.toView();
    }

    /**
     * All webhook subscriptions of the partner (wizard rehydrate / detail
     * page). 404 on an unknown partner code; an empty list when step 8 has
     * not been saved yet.
     */
    @Transactional(readOnly = true)
    public List<WebhookSubscriptionView> currentSubscriptions(String partnerCode) {
        PartnerEntity partner = requirePartner(partnerCode);
        return subscriptionRepository.findByPartnerIdOrderByEnvironmentAsc(partner.getId())
                .stream()
                .map(PartnerWebhookSubscriptionEntity::toView)
                .toList();
    }

    /**
     * Activation-time provisioning — Lane A's lifecycle service calls this
     * INSIDE the activation transaction when the partner transitions into
     * {@code environment} ({@code SANDBOX} | {@code LIVE}).
     *
     * @return the {@link IssuedWebhookSubscription} with the ONE-TIME
     *         plaintext signing secret ({@code newlyProvisioned=true}); the
     *         existing endpoint with a {@code null} secret on the idempotent
     *         replay; or {@link Optional#empty()} when the partner saved no
     *         step-8 draft for the environment (nothing to provision — the
     *         activation gate decides whether that is acceptable).
     * @throws ResponseStatusException 404 unknown partner; 400 bad
     *         environment; 502 (propagated from the client) when the
     *         notification-webhook service fails — rolling the activation
     *         back, the V030 row stays DRAFT.
     */
    @Transactional
    public Optional<IssuedWebhookSubscription> provisionOnActivation(String partnerCode,
                                                                     String environment,
                                                                     String actor) {
        if (environment == null || !ENVIRONMENTS.contains(environment)) {
            throw badRequest("environment must be one of " + ENVIRONMENTS
                    + ", was: " + environment);
        }
        PartnerEntity partner = requirePartner(partnerCode);
        Optional<PartnerWebhookSubscriptionEntity> rowOpt =
                subscriptionRepository.findByPartnerIdAndEnvironment(partner.getId(), environment);
        if (rowOpt.isEmpty()) {
            return Optional.empty();
        }
        PartnerWebhookSubscriptionEntity row = rowOpt.get();
        List<String> eventTypes =
                PartnerWebhookSubscriptionEntity.eventTypesFromCsv(row.getEventTypesCsv());

        if (row.getStatus() == WebhookSubscriptionStatus.PROVISIONED) {
            // Idempotent replay: same endpoint, NO new secret (one-time reveal).
            return Optional.of(new IssuedWebhookSubscription(
                    environment, row.getEndpointId(), row.getUrl(), eventTypes, null, false));
        }

        byte[] before = WebhookSubscriptionJson.canonical(row);
        // The client call sits BEFORE the row mutation: a 4xx/502 propagates,
        // the transaction rolls back and the row stays DRAFT (failure path).
        WebhookEndpointRegistrationView registration = webhookClient.registerEndpoint(
                new WebhookEndpointRegistrationCommand(
                        partner.getId(), row.getUrl(), eventTypes, environment));

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        row.setEndpointId(registration.endpointId());
        if (registration.signingSecretPlaintext() != null) {
            row.setSigningSecretHash(sha256Hex(registration.signingSecretPlaintext()));
            row.setLastRotatedAt(now);
        }
        row.setStatus(WebhookSubscriptionStatus.PROVISIONED);
        row.setUpdatedAt(now);
        PartnerWebhookSubscriptionEntity saved = subscriptionRepository.saveAndFlush(row);
        publishAudit(partnerCode, actor, EVENT_TYPE_PROVISIONED,
                before, WebhookSubscriptionJson.canonical(saved));

        return Optional.of(new IssuedWebhookSubscription(
                environment,
                registration.endpointId(),
                saved.getUrl(),
                eventTypes,
                registration.signingSecretPlaintext(),
                registration.newlyRegistered()));
    }

    // -------------------------- Helpers --------------------------------------

    private PartnerEntity requirePartner(String partnerCode) {
        return partnerRepository.findCurrentByPartnerCode(partnerCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no partner '" + partnerCode + "'"));
    }

    /**
     * Field-format validation, whole payload before any row is touched (the
     * same fail-fast discipline as {@code PrefundingConfigService.validate}).
     */
    private static void validate(WebhookSubscriptionCommand subscription) {
        if (subscription.url() == null || !subscription.url().startsWith("https://")) {
            throw badRequest("url must use HTTPS, was: " + subscription.url());
        }
        if (subscription.url().length() > MAX_URL_LENGTH) {
            throw badRequest("url must be at most " + MAX_URL_LENGTH + " characters");
        }
        if (subscription.environment() == null
                || !ENVIRONMENTS.contains(subscription.environment())) {
            throw badRequest("environment must be one of " + ENVIRONMENTS
                    + ", was: " + subscription.environment());
        }
        if (subscription.eventTypes() != null) {
            if (subscription.eventTypes().size() > MAX_EVENT_TYPES) {
                throw badRequest("at most " + MAX_EVENT_TYPES + " event types per subscription");
            }
            for (String type : subscription.eventTypes()) {
                if (type == null || type.isBlank()) {
                    continue; // dropped by the CSV writer
                }
                if (type.contains(",")) {
                    throw badRequest("event type must not contain a comma: " + type);
                }
                if (type.length() > MAX_EVENT_TYPE_LENGTH) {
                    throw badRequest("event type must be at most " + MAX_EVENT_TYPE_LENGTH
                            + " characters: " + type);
                }
            }
        }
    }

    /** ADR-007 audit row, same-transaction (commits iff the V030 write commits). */
    private void publishAudit(String partnerCode, String actor, String eventType,
                              byte[] before, byte[] after) {
        AuditLogService auditLog = auditLogProvider.getIfAvailable();
        if (auditLog != null) {
            auditLog.publish(
                    AGGREGATE_TYPE,
                    partnerCode,
                    actor == null || actor.isBlank() ? DEFAULT_ACTOR : actor,
                    null,
                    eventType,
                    before,
                    after);
        }
    }

    /** Lowercase-hex SHA-256 of the secret's UTF-8 bytes — the V030 at-rest form. */
    static String sha256Hex(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
