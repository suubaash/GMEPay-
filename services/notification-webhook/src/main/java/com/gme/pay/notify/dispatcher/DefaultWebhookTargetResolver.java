package com.gme.pay.notify.dispatcher;

import com.gme.pay.notify.domain.WebhookPayloads;
import com.gme.pay.notify.persistence.WebhookDeliveryEntity;
import com.gme.pay.notify.persistence.WebhookEndpointEntity;
import com.gme.pay.notify.persistence.WebhookEndpointRepository;
import com.gme.pay.notify.provisioning.WebhookSecretDeriver;
import com.gme.pay.notify.provisioning.WebhookSecretVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Default {@link WebhookTargetResolver}: resolves the endpoint URL <b>and its own
 * signing secret</b> from the {@code webhook_endpoint} table.
 *
 * <p><b>Endpoint URL</b> — looked up by {@code (partnerId, environment)} from the rows
 * config-registry registers at partner activation. The partner id is read from the
 * delivery row's payload ({@code partnerId} field). Environment is set by
 * {@code gmepay.webhook.environment} (default {@code LIVE}).
 *
 * <h2>Signing secret — gap T5-4</h2>
 *
 * <p>This class used to return the one global {@code gmepay.webhook.signing-secret} for
 * every partner. Any holder of that value could forge events to all partners, and it was
 * not even the secret the partner had been handed at activation. It now derives
 * <b>this endpoint's</b> secret with {@link WebhookSecretDeriver} from
 * {@code (partnerId, environment, secret_generation)} and — the fail-closed part —
 * <b>checks the derived value against the digest stored on the row</b>
 * ({@code signing_secret_hash}) before handing it to the sender. Consequences:
 *
 * <ul>
 *   <li>a missing root key, a WRONG root key, a legacy row whose secret was CSPRNG
 *       randomness, or a row with no digest at all ⇒ {@link Optional#empty()}: the
 *       dispatcher leaves the row PENDING / counts a failed attempt. No path remains
 *       that signs with a shared or approximate key;</li>
 *   <li>what is signed is provably the exact secret revealed to that partner, so
 *       verification on their side cannot silently mismatch.</li>
 * </ul>
 *
 * <p><b>Rotation overlap:</b> while {@code previous_secret_expires_at} is in the future
 * the previous generation is re-derived and returned as
 * {@link ResolvedTarget#secondarySecret()}; the sender then emits both signatures so the
 * partner can cut over on their own schedule.
 */
@Component
public class DefaultWebhookTargetResolver implements WebhookTargetResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultWebhookTargetResolver.class);

    private final WebhookEndpointRepository endpoints;
    private final String environment;
    private final WebhookSecretDeriver deriver;
    private final Clock clock;

    public DefaultWebhookTargetResolver(
            WebhookEndpointRepository endpoints,
            @Value("${gmepay.webhook.environment:LIVE}") String environment,
            WebhookSecretDeriver deriver,
            Clock clock) {
        this.endpoints = Objects.requireNonNull(endpoints);
        this.environment = environment;
        this.deriver = Objects.requireNonNull(deriver);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Optional<ResolvedTarget> resolve(WebhookDeliveryEntity row) {
        Long partnerId = partnerIdOf(row);
        if (partnerId == null) {
            log.warn("webhook target unresolved: no numeric partnerId in payload for webhookId={}",
                    row.getWebhookId());
            return Optional.empty();
        }

        List<WebhookEndpointEntity> active =
                endpoints.findByPartnerIdAndEnvironmentAndActiveTrue(partnerId, environment);
        if (active.isEmpty()) {
            log.warn("webhook target unresolved: no active {} endpoint for partnerId={} (webhookId={})",
                    environment, partnerId, row.getWebhookId());
            return Optional.empty();
        }
        WebhookEndpointEntity endpoint = active.get(0);

        if (!deriver.isConfigured()) {
            log.warn("webhook signing secret unavailable for partnerId={} env={}: the derivation "
                            + "root key gmepay.webhook.signing-secret is not set, so no per-endpoint "
                            + "secret can be derived. Leaving webhookId={} PENDING (fail closed).",
                    partnerId, environment, row.getWebhookId());
            return Optional.empty();
        }

        String secret = deriveAndVerify(endpoint, endpoint.getSecretGeneration(),
                endpoint.getSigningSecretHash());
        if (secret == null) {
            log.error("webhook signing secret for endpointId={} partnerId={} env={} does not match the "
                            + "digest stored at registration (generation={}): refusing to sign with a "
                            + "secret the partner does not hold. Leaving webhookId={} PENDING. Check "
                            + "gmepay.webhook.signing-secret, or re-register / rotate the endpoint.",
                    endpoint.getId(), partnerId, environment, endpoint.getSecretGeneration(),
                    row.getWebhookId());
            return Optional.empty();
        }

        return Optional.of(new ResolvedTarget(endpoint.getWebhookUrl(), secret,
                previousSecretIfWindowOpen(endpoint)));
    }

    /**
     * Re-derives one generation's secret and proves it is the one the row was created
     * with. Returns {@code null} when that cannot be proven — the caller fails closed.
     *
     * <p>Delegates to {@link WebhookSecretVerifier} (T5-8) so that the operator-facing
     * signing-health read reports exactly the rule this dispatcher enforces. Behaviour is
     * unchanged from the T5-4 implementation this replaced.
     */
    private String deriveAndVerify(WebhookEndpointEntity endpoint, int generation, String expectedHash) {
        return WebhookSecretVerifier.deriveAndVerify(deriver, endpoint, generation, expectedHash);
    }

    /**
     * The retired secret while its overlap window is open, else {@code null}. An expired
     * (or unverifiable) previous secret is simply dropped — never a delivery failure.
     */
    private String previousSecretIfWindowOpen(WebhookEndpointEntity endpoint) {
        String previousHash = endpoint.getPreviousSecretHash();
        Instant expiresAt = endpoint.getPreviousSecretExpiresAt();
        if (previousHash == null || previousHash.isBlank() || expiresAt == null) {
            return null;
        }
        if (!Instant.now(clock).isBefore(expiresAt)) {
            return null; // window closed: sign with the current generation only
        }
        if (endpoint.getSecretGeneration() <= WebhookSecretDeriver.INITIAL_GENERATION) {
            return null; // no earlier generation exists
        }
        String previous = deriveAndVerify(endpoint, endpoint.getSecretGeneration() - 1, previousHash);
        if (previous == null) {
            log.warn("rotation overlap for endpointId={} names a previous secret that cannot be "
                    + "re-derived; signing with the current generation only", endpoint.getId());
        }
        return previous;
    }

    /**
     * The partner this delivery belongs to, read from the payload.
     *
     * <p>Deliberately the <b>payload</b> and not V009's {@code partner_id} column, even though the
     * column exists and is stamped from this same parse at enqueue time. The column's job is to let SQL
     * page fairly; deciding which endpoint a webhook is signed for and POSTed to is a different
     * question, and it should keep answering it from the event itself. If the two ever disagreed the
     * consequence would then be a row selected under the wrong partner's fair share — harmless — rather
     * than a webhook delivered to the wrong partner's endpoint.
     *
     * <p>One rule, in {@link WebhookPayloads}, shared with the drain and the DLQ alert.
     */
    private Long partnerIdOf(WebhookDeliveryEntity row) {
        return WebhookPayloads.partnerId(row.getPayload());
    }
}
