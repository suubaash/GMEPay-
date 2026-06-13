package com.gme.pay.notify.provisioning;

import com.gme.pay.contracts.WebhookEndpointRegistrationCommand;
import com.gme.pay.contracts.WebhookEndpointRegistrationView;
import com.gme.pay.notify.persistence.JpaWebhookConfigStore;
import com.gme.pay.notify.persistence.WebhookEndpointEntity;
import com.gme.pay.notify.persistence.WebhookEndpointRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Slice 8 Lane D — registers a partner webhook endpoint at activation time and
 * mints its HMAC signing secret. Called by config-registry's
 * {@code WebhookProvisioningService} through
 * {@code POST /v1/webhooks/endpoints} when a partner transitions to
 * {@code SANDBOX} (and later {@code LIVE}).
 *
 * <h2>One-time secret reveal</h2>
 *
 * <p>The secret is generated here ({@link SigningSecrets#newSecret()}), its
 * SHA-256 digest is persisted on the {@code webhook_endpoint} row (V004), and
 * the PLAINTEXT is returned exactly once in the
 * {@link WebhookEndpointRegistrationView}. It can never be re-read: the
 * idempotent path below returns the existing endpoint id with a {@code null}
 * secret.
 *
 * <h2>Idempotency</h2>
 *
 * <p>Activation retries must not mint a second secret or a duplicate
 * endpoint: when an ACTIVE row already exists for ({@code partnerId},
 * {@code environment}) the call short-circuits to that row with
 * {@code newlyRegistered=false} — the same at-least-once discipline as
 * {@code WebhookPersistenceService.enqueuePendingIfAbsent}.
 */
@Service
public class WebhookEndpointProvisioningService {

    /** V004 CHECK roster. */
    static final Set<String> ENVIRONMENTS = Set.of("SANDBOX", "LIVE");

    private static final int MAX_URL_LENGTH = 512;

    private final WebhookEndpointRepository repository;
    private final Clock clock;

    public WebhookEndpointProvisioningService(WebhookEndpointRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Registers (or idempotently re-resolves) the endpoint and returns the
     * registration view — plaintext secret present ONLY on the newly-created
     * path.
     *
     * @throws IllegalArgumentException on a malformed request (the controller
     *         maps it to 400): missing partnerId, non-HTTPS / over-long url,
     *         unknown environment, comma in an event type.
     */
    @Transactional
    public WebhookEndpointRegistrationView register(WebhookEndpointRegistrationCommand request) {
        validate(request);

        List<WebhookEndpointEntity> existing =
                repository.findByPartnerIdAndEnvironmentAndActiveTrue(
                        request.partnerId(), request.environment());
        if (!existing.isEmpty()) {
            // Idempotent replay: same endpoint, NO new secret (one-time reveal).
            return new WebhookEndpointRegistrationView(
                    String.valueOf(existing.get(0).getId()), null, false);
        }

        String secretPlaintext = SigningSecrets.newSecret();
        // MICROS truncation: stored TIMESTAMP must equal the in-memory value
        // on both PostgreSQL and H2 (project-wide discipline).
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);

        WebhookEndpointEntity entity = new WebhookEndpointEntity();
        entity.setPartnerId(request.partnerId());
        entity.setWebhookUrl(request.url());
        entity.setEventTypesCsv(JpaWebhookConfigStore.toCsv(request.eventTypes()));
        entity.setEnvironment(request.environment());
        entity.setSigningSecretHash(SigningSecrets.sha256Hex(secretPlaintext));
        entity.setActive(true);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        WebhookEndpointEntity saved = repository.saveAndFlush(entity);

        return new WebhookEndpointRegistrationView(
                String.valueOf(saved.getId()), secretPlaintext, true);
    }

    private static void validate(WebhookEndpointRegistrationCommand request) {
        if (request == null) {
            throw new IllegalArgumentException("request body required");
        }
        if (request.partnerId() == null || request.partnerId() <= 0) {
            throw new IllegalArgumentException("partnerId must be a positive partner id");
        }
        if (request.url() == null || !request.url().startsWith("https://")) {
            throw new IllegalArgumentException(
                    "url must use HTTPS, got: " + request.url());
        }
        if (request.url().length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException(
                    "url must be at most " + MAX_URL_LENGTH + " characters");
        }
        if (request.environment() == null || !ENVIRONMENTS.contains(request.environment())) {
            throw new IllegalArgumentException(
                    "environment must be one of " + ENVIRONMENTS
                            + ", was: " + request.environment());
        }
        // Comma is the CSV delimiter — JpaWebhookConfigStore.toCsv rethrows the
        // same complaint, but failing fast here keeps the row untouched.
        if (request.eventTypes() != null) {
            for (String type : request.eventTypes()) {
                if (type != null && type.contains(",")) {
                    throw new IllegalArgumentException(
                            "event type must not contain a comma: " + type);
                }
            }
        }
    }
}
