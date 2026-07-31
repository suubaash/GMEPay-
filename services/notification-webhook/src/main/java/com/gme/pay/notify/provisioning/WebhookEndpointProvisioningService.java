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
 *
 * <h2>Per-endpoint secrets + rotation (T5-4)</h2>
 *
 * <p>The minted secret is now <b>derived for this endpoint</b> via
 * {@link WebhookSecretDeriver} (HKDF over a root key, {@code info} =
 * partner|environment|generation) instead of being unrelated randomness that the
 * dispatcher then ignored in favour of one global secret. What is persisted is
 * unchanged — the SHA-256 digest only — so the T1-1 registration contract and its
 * cross-service digest agreement still hold.
 *
 * <p>{@link #rotateSecret} issues the next generation and keeps the previous digest
 * alive for an overlap window during which the dispatcher signs with BOTH secrets.
 * When no root key is configured, registration falls back to a CSPRNG secret so
 * partner activation still completes, but that endpoint is undeliverable by design
 * (the resolver fails closed) — see {@link WebhookSecretDeriver}.
 */
@Service
public class WebhookEndpointProvisioningService {

    /** V004 CHECK roster. */
    static final Set<String> ENVIRONMENTS = Set.of("SANDBOX", "LIVE");

    private static final int MAX_URL_LENGTH = 512;

    /** Overlap applied when a rotate call does not name its own window. */
    public static final long DEFAULT_OVERLAP_MINUTES = 1440L; // 24h

    /** Upper bound on the overlap: a rotation that never lands is not a rotation. */
    static final long MAX_OVERLAP_MINUTES = 30 * 24 * 60L; // 30 days

    private final WebhookEndpointRepository repository;
    private final Clock clock;
    private final WebhookSecretDeriver deriver;

    public WebhookEndpointProvisioningService(WebhookEndpointRepository repository, Clock clock,
                                              WebhookSecretDeriver deriver) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
        this.deriver = Objects.requireNonNull(deriver);
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

        // T5-4: the secret this endpoint will actually be signed with — derived from the
        // root key + THIS endpoint's identity, so it can never verify another partner's
        // payload. Without a root key we still mint (activation must not half-complete),
        // but the dispatcher will refuse to deliver: see WebhookSecretDeriver.
        String secretPlaintext = deriver.isConfigured()
                ? deriver.derive(request.partnerId(), request.environment(),
                        WebhookSecretDeriver.INITIAL_GENERATION)
                : SigningSecrets.newSecret();
        // MICROS truncation: stored TIMESTAMP must equal the in-memory value
        // on both PostgreSQL and H2 (project-wide discipline).
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);

        WebhookEndpointEntity entity = new WebhookEndpointEntity();
        entity.setPartnerId(request.partnerId());
        entity.setWebhookUrl(request.url());
        entity.setEventTypesCsv(JpaWebhookConfigStore.toCsv(request.eventTypes()));
        entity.setEnvironment(request.environment());
        entity.setSigningSecretHash(SigningSecrets.sha256Hex(secretPlaintext));
        entity.setSecretGeneration(WebhookSecretDeriver.INITIAL_GENERATION);
        entity.setActive(true);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        WebhookEndpointEntity saved = repository.saveAndFlush(entity);

        return new WebhookEndpointRegistrationView(
                String.valueOf(saved.getId()), secretPlaintext, true);
    }

    /**
     * Rotates one endpoint's signing secret — gap T5-4, the "rotation is supported" half.
     *
     * <p>Bumps {@code secret_generation}, so the new secret is an independent HKDF output;
     * the outgoing generation's digest moves to {@code previous_secret_hash} with an
     * expiry {@code overlapMinutes} in the future. Until that expiry the dispatcher signs
     * every delivery with BOTH secrets (two comma-separated values in
     * {@code X-GME-Webhook-Signature}), so the partner can switch whenever they redeploy
     * and no event is lost either side of the cutover. After it, only the new one.
     *
     * <p>Chosen over a "both secrets accepted" design because this side <b>produces</b>
     * signatures rather than verifying them: an inbound service can simply try two keys,
     * but an outbound signer has to decide what to put on the wire — so the overlap has
     * to live in the header, and the window is what bounds how long the retired secret
     * stays usable. The plaintext is revealed exactly once here, like registration.
     *
     * @param endpointId     the row to rotate (must be active)
     * @param overlapMinutes how long the previous secret stays valid; {@code null} =
     *                       {@link #DEFAULT_OVERLAP_MINUTES}, {@code 0} = immediate cutover
     * @return the new plaintext secret + the window, revealed once
     * @throws IllegalStateException    when no derivation root key is configured
     * @throws IllegalArgumentException unknown/inactive endpoint, or an out-of-range window
     */
    @Transactional
    public WebhookSecretRotationView rotateSecret(Long endpointId, Long overlapMinutes) {
        if (endpointId == null) {
            throw new IllegalArgumentException("endpointId is required");
        }
        long overlap = overlapMinutes == null ? DEFAULT_OVERLAP_MINUTES : overlapMinutes;
        if (overlap < 0 || overlap > MAX_OVERLAP_MINUTES) {
            throw new IllegalArgumentException("overlapMinutes must be between 0 and "
                    + MAX_OVERLAP_MINUTES + ", was: " + overlap);
        }
        if (!deriver.isConfigured()) {
            // Never rotate to a secret we cannot reproduce at dispatch time.
            throw new IllegalStateException("cannot rotate: gmepay.webhook.signing-secret "
                    + "(derivation root key) is not configured");
        }

        WebhookEndpointEntity row = repository.findById(endpointId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no webhook endpoint with id " + endpointId));
        if (!row.isActive()) {
            throw new IllegalArgumentException(
                    "webhook endpoint " + endpointId + " is not active; re-register instead of rotating");
        }

        int nextGeneration = row.getSecretGeneration() + 1;
        String newSecret = deriver.derive(row.getPartnerId(), row.getEnvironment(), nextGeneration);
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        Instant overlapUntil = overlap == 0 ? null : now.plus(overlap, ChronoUnit.MINUTES);

        row.setPreviousSecretHash(overlap == 0 ? null : row.getSigningSecretHash());
        row.setPreviousSecretExpiresAt(overlapUntil);
        row.setSigningSecretHash(SigningSecrets.sha256Hex(newSecret));
        row.setSecretGeneration(nextGeneration);
        row.setUpdatedAt(now);
        repository.saveAndFlush(row);

        return new WebhookSecretRotationView(String.valueOf(row.getId()), newSecret,
                nextGeneration, overlapUntil);
    }

    /**
     * Reports the signing state of every active endpoint — gap <b>T5-8</b>, the
     * "detect the undeliverable rows" half.
     *
     * <p>T5-4 made the dispatcher refuse to sign for an endpoint whose secret cannot be
     * re-derived and verified. That is correct, but it turned a whole class of endpoints —
     * every row minted before per-endpoint derivation existed — into silent non-deliverers
     * whose only symptom was an ERROR line per attempt. This read surfaces them, applying
     * {@link WebhookSecretVerifier} — literally the same rule the dispatcher enforces — so
     * an operator can see, before a partner complains, which endpoints are dead and which of
     * those rotation would revive.
     *
     * <p><b>Deliberately read-only.</b> Nothing here auto-rotates: rotation replaces a secret
     * the partner must be told about out of band, so it stays an explicit operator action
     * with a one-time reveal. Silently re-keying an endpoint would swap one broken state
     * (no signature) for a worse one (a signature the partner cannot verify and has no way
     * to learn about).
     *
     * <p>No secret material is derived into the response — only a pass/fail classification.
     *
     * @param partnerId restrict to one partner, or {@code null} for every active endpoint
     */
    @Transactional(readOnly = true)
    public List<WebhookEndpointSigningHealthView> signingHealth(Long partnerId) {
        List<WebhookEndpointEntity> rows = partnerId == null
                ? repository.findByActiveTrueOrderByIdAsc()
                : repository.findByPartnerIdAndActiveTrueOrderByIdAsc(partnerId);
        Instant now = Instant.now(clock);
        return rows.stream()
                .map(row -> WebhookEndpointSigningHealthView.of(
                        row, WebhookSecretVerifier.statusOf(deriver, row), now))
                .toList();
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
