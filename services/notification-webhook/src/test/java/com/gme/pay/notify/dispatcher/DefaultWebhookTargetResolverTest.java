package com.gme.pay.notify.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gme.pay.notify.dispatcher.WebhookTargetResolver.ResolvedTarget;
import com.gme.pay.notify.persistence.WebhookDeliveryEntity;
import com.gme.pay.notify.persistence.WebhookEndpointEntity;
import com.gme.pay.notify.persistence.WebhookEndpointRepository;
import com.gme.pay.notify.provisioning.SigningSecrets;
import com.gme.pay.notify.provisioning.WebhookSecretDeriver;
import com.gme.pay.notify.signing.WebhookSigningService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DefaultWebhookTargetResolver}: partnerId extraction (it must read
 * {@code payload.partnerId} from the canonical outbox envelope as well as a flat top-level
 * field) and — gap <b>T5-4</b> — the per-endpoint signing-secret contract.
 *
 * <h2>What T5-4 changed here</h2>
 *
 * <p>The resolver used to return the single global {@code gmepay.webhook.signing-secret}
 * for every partner. These tests pin the replacement: the secret is derived for THIS
 * endpoint, is proven against the digest stored at registration before it is used, and the
 * resolver fails closed (empty ⇒ row stays PENDING) rather than signing with anything it
 * cannot prove.
 */
class DefaultWebhookTargetResolverTest {

    private static final String ROOT_KEY = "root-key-for-tests-0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-07-28T10:00:00Z");

    private final WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private DefaultWebhookTargetResolver resolverWithRootKey(String rootKey) {
        return new DefaultWebhookTargetResolver(
                endpoints, "LIVE", WebhookSecretDeriver.withRootKey(rootKey), clock);
    }

    private WebhookDeliveryEntity row(String payload) {
        WebhookDeliveryEntity r = mock(WebhookDeliveryEntity.class);
        when(r.getPayload()).thenReturn(payload);
        lenient().when(r.getWebhookId()).thenReturn("TX-1");
        return r;
    }

    /** A registered endpoint whose stored digest matches the secret derived from {@code rootKey}. */
    private WebhookEndpointEntity stubActiveEndpoint(long partnerId, String rootKey) {
        String secret = WebhookSecretDeriver.withRootKey(rootKey)
                .derive(partnerId, "LIVE", WebhookSecretDeriver.INITIAL_GENERATION);
        WebhookEndpointEntity ep = endpoint(partnerId, SigningSecrets.sha256Hex(secret),
                WebhookSecretDeriver.INITIAL_GENERATION);
        when(endpoints.findByPartnerIdAndEnvironmentAndActiveTrue(partnerId, "LIVE"))
                .thenReturn(List.of(ep));
        return ep;
    }

    private WebhookEndpointEntity endpoint(long partnerId, String secretHash, int generation) {
        WebhookEndpointEntity ep = new WebhookEndpointEntity();
        ep.setId(partnerId); // value is irrelevant to behaviour; keeps log lines distinct
        ep.setPartnerId(partnerId);
        ep.setEnvironment("LIVE");
        ep.setWebhookUrl("https://partner-" + partnerId + ".example/hook");
        ep.setSigningSecretHash(secretHash);
        ep.setSecretGeneration(generation);
        ep.setActive(true);
        ep.setCreatedAt(NOW);
        ep.setUpdatedAt(NOW);
        return ep;
    }

    // ------------------------------------------------------------------ partnerId extraction

    @Test
    void resolvesPartnerIdFromNestedOutboxEnvelope() {
        stubActiveEndpoint(700L, ROOT_KEY);
        Optional<ResolvedTarget> target = resolverWithRootKey(ROOT_KEY).resolve(row(
                "{\"eventType\":\"payment.approved\",\"aggregateId\":\"TX-1\","
                        + "\"payload\":{\"txnRef\":\"TX-1\",\"partnerId\":700,\"toStatus\":\"APPROVED\"}}"));
        assertThat(target).isPresent();
        assertThat(target.get().url()).isEqualTo("https://partner-700.example/hook");
        assertThat(target.get().secret()).startsWith(SigningSecrets.SECRET_PREFIX);
        assertThat(target.get().secondarySecret()).isNull();
    }

    @Test
    void resolvesFlatTopLevelPartnerId() {
        stubActiveEndpoint(700L, ROOT_KEY);
        assertThat(resolverWithRootKey(ROOT_KEY).resolve(row("{\"partnerId\":700}"))).isPresent();
    }

    @Test
    void emptyWhenNoPartnerIdAnywhere() {
        assertThat(resolverWithRootKey(ROOT_KEY).resolve(row(
                "{\"eventType\":\"payment.approved\",\"payload\":{\"toStatus\":\"APPROVED\"}}")))
                .isEmpty();
    }

    // ------------------------------------------------------------------ T5-4: per-endpoint secrets

    @Test
    @DisplayName("T5-4: the resolved secret IS the endpoint's registered secret (hash-verified)")
    void resolvedSecretMatchesTheDigestStoredAtRegistration() {
        WebhookEndpointEntity ep = stubActiveEndpoint(700L, ROOT_KEY);

        String secret = resolverWithRootKey(ROOT_KEY).resolve(row("{\"partnerId\":700}"))
                .orElseThrow().secret();

        assertThat(SigningSecrets.matches(secret, ep.getSigningSecretHash())).isTrue();
    }

    @Test
    @DisplayName("T5-4: two endpoints get DIFFERENT secrets, and one cannot verify the other's payload")
    void perEndpointSecretsAreIsolated() {
        stubActiveEndpoint(700L, ROOT_KEY);
        stubActiveEndpoint(800L, ROOT_KEY);
        DefaultWebhookTargetResolver resolver = resolverWithRootKey(ROOT_KEY);

        String secret700 = resolver.resolve(row("{\"partnerId\":700}")).orElseThrow().secret();
        String secret800 = resolver.resolve(row("{\"partnerId\":800}")).orElseThrow().secret();

        // Before T5-4 both of these were the one global gmepay.webhook.signing-secret.
        assertThat(secret700).isNotEqualTo(secret800);

        // The cross-forgery check: partner 700's secret cannot authenticate a payload signed
        // for partner 800, so holding one partner's secret forges nothing for the other.
        WebhookSigningService signing = new WebhookSigningService();
        String body = "{\"eventType\":\"payment.approved\",\"partnerId\":800}";
        String signedFor800 = signing.sign(body.getBytes(StandardCharsets.UTF_8), secret800);

        assertThat(signing.verifySignature(body, secret800, signedFor800)).isTrue();
        assertThat(signing.verifySignature(body, secret700, signedFor800)).isFalse();
    }

    @Test
    @DisplayName("T5-4 fail closed: no root key ⇒ no delivery (never a shared fallback secret)")
    void failsClosedWithoutARootKey() {
        stubActiveEndpoint(700L, ROOT_KEY);

        assertThat(resolverWithRootKey("").resolve(row("{\"partnerId\":700}"))).isEmpty();
    }

    @Test
    @DisplayName("T5-4 fail closed: a WRONG root key derives a secret the partner never got ⇒ no delivery")
    void failsClosedWhenTheDerivedSecretDoesNotMatchTheStoredDigest() {
        stubActiveEndpoint(700L, ROOT_KEY);

        // Same code path as a mis-set GMEPAY_WEBHOOK_SIGNING_SECRET in one environment: the
        // derived secret is well-formed but is NOT the one revealed at registration. Signing
        // with it would produce a signature the partner must reject, so we refuse to send.
        assertThat(resolverWithRootKey("a-different-root-key").resolve(row("{\"partnerId\":700}")))
                .isEmpty();
    }

    @Test
    @DisplayName("T5-4 fail closed: a legacy row with no stored digest is undeliverable")
    void failsClosedOnLegacyRowWithoutADigest() {
        WebhookEndpointEntity legacy = endpoint(900L, null, WebhookSecretDeriver.INITIAL_GENERATION);
        when(endpoints.findByPartnerIdAndEnvironmentAndActiveTrue(900L, "LIVE"))
                .thenReturn(List.of(legacy));

        assertThat(resolverWithRootKey(ROOT_KEY).resolve(row("{\"partnerId\":900}"))).isEmpty();
    }

    // ------------------------------------------------------------------ T5-4: rotation overlap

    @Test
    @DisplayName("T5-4 rotation: inside the overlap window BOTH generations are returned")
    void rotationOverlapReturnsPreviousSecretWhileWindowIsOpen() {
        WebhookSecretDeriver deriver = WebhookSecretDeriver.withRootKey(ROOT_KEY);
        String gen1 = deriver.derive(700L, "LIVE", 1);
        String gen2 = deriver.derive(700L, "LIVE", 2);

        WebhookEndpointEntity rotated = endpoint(700L, SigningSecrets.sha256Hex(gen2), 2);
        rotated.setPreviousSecretHash(SigningSecrets.sha256Hex(gen1));
        rotated.setPreviousSecretExpiresAt(NOW.plus(Duration.ofHours(1)));
        when(endpoints.findByPartnerIdAndEnvironmentAndActiveTrue(700L, "LIVE"))
                .thenReturn(List.of(rotated));

        ResolvedTarget target = resolverWithRootKey(ROOT_KEY)
                .resolve(row("{\"partnerId\":700}")).orElseThrow();

        assertThat(target.secret()).isEqualTo(gen2);
        assertThat(target.secondarySecret()).isEqualTo(gen1);
    }

    @Test
    @DisplayName("T5-4 rotation: once the window closes only the current generation is signed with")
    void rotationOverlapDropsPreviousSecretAfterExpiry() {
        WebhookSecretDeriver deriver = WebhookSecretDeriver.withRootKey(ROOT_KEY);
        String gen1 = deriver.derive(700L, "LIVE", 1);
        String gen2 = deriver.derive(700L, "LIVE", 2);

        WebhookEndpointEntity rotated = endpoint(700L, SigningSecrets.sha256Hex(gen2), 2);
        rotated.setPreviousSecretHash(SigningSecrets.sha256Hex(gen1));
        rotated.setPreviousSecretExpiresAt(NOW.minusSeconds(1)); // window already closed
        when(endpoints.findByPartnerIdAndEnvironmentAndActiveTrue(700L, "LIVE"))
                .thenReturn(List.of(rotated));

        ResolvedTarget target = resolverWithRootKey(ROOT_KEY)
                .resolve(row("{\"partnerId\":700}")).orElseThrow();

        assertThat(target.secret()).isEqualTo(gen2);
        assertThat(target.secondarySecret()).isNull();
    }
}
