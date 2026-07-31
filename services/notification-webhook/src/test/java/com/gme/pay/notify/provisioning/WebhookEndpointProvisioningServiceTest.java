package com.gme.pay.notify.provisioning;

import com.gme.pay.contracts.WebhookEndpointRegistrationCommand;
import com.gme.pay.contracts.WebhookEndpointRegistrationView;
import com.gme.pay.notify.config.ClockConfig;
import com.gme.pay.notify.persistence.WebhookEndpointEntity;
import com.gme.pay.notify.persistence.WebhookEndpointRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2 (PostgreSQL mode) slice test for
 * {@link WebhookEndpointProvisioningService} — the Slice 8 Lane D
 * partner-activation registration seam behind
 * {@code POST /v1/webhooks/endpoints} (V004 columns on
 * {@code webhook_endpoint}). Mirrors the {@code JpaWebhookConfigStoreTest}
 * harness.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({WebhookEndpointProvisioningService.class, ClockConfig.class,
        WebhookEndpointProvisioningServiceTest.DeriverConfig.class})
class WebhookEndpointProvisioningServiceTest {

    /** Root key for the T5-4 per-endpoint derivation (see {@link WebhookSecretDeriver}). */
    static final String ROOT_KEY = "provisioning-test-root-key-0123456789";

    /**
     * Supplies the deriver explicitly rather than relying on {@code @Value} placeholder
     * resolution inside a {@code @DataJpaTest} slice.
     */
    @TestConfiguration
    static class DeriverConfig {
        @Bean
        WebhookSecretDeriver webhookSecretDeriver() {
            return WebhookSecretDeriver.withRootKey(ROOT_KEY);
        }
    }

    @Autowired
    private WebhookEndpointProvisioningService service;

    @Autowired
    private WebhookEndpointRepository repository;

    private static WebhookEndpointRegistrationCommand command(
            Long partnerId, String environment) {
        return new WebhookEndpointRegistrationCommand(
                partnerId, "https://partner.example.com/hooks/gmepay",
                List.of("payment.approved", "payment.failed"), environment);
    }

    @Test
    @DisplayName("register mints a whsec_ secret, persists ONLY its SHA-256 and returns plaintext once")
    void register_storesHashNotPlaintext() {
        WebhookEndpointRegistrationView view = service.register(command(42L, "SANDBOX"));

        assertTrue(view.newlyRegistered());
        assertNotNull(view.endpointId());
        assertNotNull(view.signingSecretPlaintext());
        assertTrue(view.signingSecretPlaintext().startsWith(SigningSecrets.SECRET_PREFIX));

        WebhookEndpointEntity row =
                repository.findById(Long.valueOf(view.endpointId())).orElseThrow();
        assertEquals(42L, row.getPartnerId());
        assertEquals("SANDBOX", row.getEnvironment());
        assertEquals("payment.approved,payment.failed", row.getEventTypesCsv());
        assertTrue(row.isActive());
        // At rest: the digest, never the plaintext.
        assertEquals(SigningSecrets.sha256Hex(view.signingSecretPlaintext()),
                row.getSigningSecretHash());
        assertEquals(64, row.getSigningSecretHash().length());
        assertNotEquals(view.signingSecretPlaintext(), row.getSigningSecretHash());
        assertTrue(SigningSecrets.matches(view.signingSecretPlaintext(),
                row.getSigningSecretHash()));
        // MICROS truncation discipline on the stored stamps.
        assertEquals(0, row.getCreatedAt().getNano() % 1000);
    }

    @Test
    @DisplayName("register is idempotent per (partner, environment): replay returns same id, NO new secret")
    void register_idempotentReplay() {
        WebhookEndpointRegistrationView first = service.register(command(7L, "SANDBOX"));
        WebhookEndpointRegistrationView replay = service.register(command(7L, "SANDBOX"));

        assertFalse(replay.newlyRegistered());
        assertEquals(first.endpointId(), replay.endpointId());
        assertNull(replay.signingSecretPlaintext(), "one-time reveal — never re-issued");
        assertEquals(1, repository
                .findByPartnerIdAndEnvironmentAndActiveTrue(7L, "SANDBOX").size());
    }

    @Test
    @DisplayName("SANDBOX and LIVE are independent registrations with independent secrets")
    void register_environmentsAreIndependent() {
        WebhookEndpointRegistrationView sandbox = service.register(command(9L, "SANDBOX"));
        WebhookEndpointRegistrationView live = service.register(command(9L, "LIVE"));

        assertTrue(live.newlyRegistered());
        assertNotEquals(sandbox.endpointId(), live.endpointId());
        assertNotEquals(sandbox.signingSecretPlaintext(), live.signingSecretPlaintext());
        assertEquals("LIVE", repository
                .findById(Long.valueOf(live.endpointId())).orElseThrow().getEnvironment());
    }

    @Test
    @DisplayName("register rejects non-HTTPS urls, bad environments and malformed event types")
    void register_validation() {
        assertThrows(IllegalArgumentException.class, () -> service.register(
                new WebhookEndpointRegistrationCommand(
                        1L, "http://insecure.example.com/h", null, "SANDBOX")));
        assertThrows(IllegalArgumentException.class, () -> service.register(
                new WebhookEndpointRegistrationCommand(
                        1L, "https://p.example.com/h", null, "PROD")));
        assertThrows(IllegalArgumentException.class, () -> service.register(
                new WebhookEndpointRegistrationCommand(
                        null, "https://p.example.com/h", null, "SANDBOX")));
        assertThrows(IllegalArgumentException.class, () -> service.register(
                new WebhookEndpointRegistrationCommand(
                        1L, "https://p.example.com/h",
                        List.of("payment.approved,payment.failed"), "SANDBOX")));
        assertEquals(0, repository.count());
    }

    // ------------------------------------------------------------------ T5-4

    @Test
    @DisplayName("T5-4: the minted secret is DERIVED for this endpoint, not unrelated randomness")
    void register_mintsTheDerivableEndpointSecret() {
        WebhookEndpointRegistrationView view = service.register(command(55L, "LIVE"));

        // The dispatcher re-derives exactly this value at send time and checks it against the
        // stored digest; if registration minted anything else, delivery would fail closed.
        String expected = WebhookSecretDeriver.withRootKey(ROOT_KEY)
                .derive(55L, "LIVE", WebhookSecretDeriver.INITIAL_GENERATION);
        assertEquals(expected, view.signingSecretPlaintext());

        WebhookEndpointEntity row =
                repository.findById(Long.valueOf(view.endpointId())).orElseThrow();
        assertEquals(WebhookSecretDeriver.INITIAL_GENERATION, row.getSecretGeneration());
        assertNull(row.getPreviousSecretHash());
        assertNull(row.getPreviousSecretExpiresAt());
    }

    @Test
    @DisplayName("T5-4: two partners registered under the same root key get different secrets")
    void register_secretsAreIsolatedBetweenPartners() {
        WebhookEndpointRegistrationView a = service.register(command(61L, "LIVE"));
        WebhookEndpointRegistrationView b = service.register(command(62L, "LIVE"));

        assertNotEquals(a.signingSecretPlaintext(), b.signingSecretPlaintext());
        // ...and neither plaintext can be recovered from the other's stored digest.
        WebhookEndpointEntity rowB =
                repository.findById(Long.valueOf(b.endpointId())).orElseThrow();
        assertFalse(SigningSecrets.matches(a.signingSecretPlaintext(), rowB.getSigningSecretHash()));
    }

    @Test
    @DisplayName("T5-4 rotation: next generation issued, previous kept for the overlap window")
    void rotate_issuesNextGenerationAndKeepsAnOverlap() {
        WebhookEndpointRegistrationView registered = service.register(command(70L, "LIVE"));
        Long endpointId = Long.valueOf(registered.endpointId());
        String firstSecret = registered.signingSecretPlaintext();

        WebhookSecretRotationView rotated = service.rotateSecret(endpointId, 60L);

        assertEquals(2, rotated.secretGeneration());
        assertNotEquals(firstSecret, rotated.signingSecretPlaintext());
        assertNotNull(rotated.previousSecretExpiresAt());
        assertEquals(WebhookSecretDeriver.withRootKey(ROOT_KEY).derive(70L, "LIVE", 2),
                rotated.signingSecretPlaintext());

        WebhookEndpointEntity row = repository.findById(endpointId).orElseThrow();
        // Current digest = the NEW secret; previous digest = the retired one, still live.
        assertTrue(SigningSecrets.matches(rotated.signingSecretPlaintext(),
                row.getSigningSecretHash()));
        assertTrue(SigningSecrets.matches(firstSecret, row.getPreviousSecretHash()));
        assertEquals(2, row.getSecretGeneration());
        Instant expiry = row.getPreviousSecretExpiresAt();
        assertNotNull(expiry);
        assertTrue(expiry.isAfter(Instant.now().minusSeconds(5)), "overlap must expire in the future");
    }

    @Test
    @DisplayName("T5-4 rotation: overlapMinutes=0 retires the old secret immediately")
    void rotate_zeroOverlapCutsOverImmediately() {
        WebhookEndpointRegistrationView registered = service.register(command(71L, "LIVE"));
        Long endpointId = Long.valueOf(registered.endpointId());

        WebhookSecretRotationView rotated = service.rotateSecret(endpointId, 0L);

        assertNull(rotated.previousSecretExpiresAt());
        WebhookEndpointEntity row = repository.findById(endpointId).orElseThrow();
        assertNull(row.getPreviousSecretHash(), "no overlap requested — old secret dies at once");
        assertNull(row.getPreviousSecretExpiresAt());
    }

    @Test
    @DisplayName("T5-4 rotation: repeated rotations keep walking the generation counter")
    void rotate_isRepeatable() {
        Long endpointId = Long.valueOf(service.register(command(72L, "LIVE")).endpointId());

        service.rotateSecret(endpointId, 30L);
        WebhookSecretRotationView third = service.rotateSecret(endpointId, 30L);

        assertEquals(3, third.secretGeneration());
        assertEquals(WebhookSecretDeriver.withRootKey(ROOT_KEY).derive(72L, "LIVE", 3),
                third.signingSecretPlaintext());
        // The overlap now names generation 2 (the one just retired), not generation 1.
        WebhookEndpointEntity row = repository.findById(endpointId).orElseThrow();
        assertTrue(SigningSecrets.matches(
                WebhookSecretDeriver.withRootKey(ROOT_KEY).derive(72L, "LIVE", 2),
                row.getPreviousSecretHash()));
    }

    @Test
    @DisplayName("T5-4 rotation: unknown endpoint and out-of-range windows are rejected")
    void rotate_validation() {
        Long endpointId = Long.valueOf(service.register(command(73L, "LIVE")).endpointId());

        assertThrows(IllegalArgumentException.class, () -> service.rotateSecret(999_999L, 60L));
        assertThrows(IllegalArgumentException.class, () -> service.rotateSecret(null, 60L));
        assertThrows(IllegalArgumentException.class, () -> service.rotateSecret(endpointId, -1L));
        assertThrows(IllegalArgumentException.class,
                () -> service.rotateSecret(endpointId, 60 * 24 * 365L));
        // Nothing was mutated by the rejected calls.
        assertEquals(1, repository.findById(endpointId).orElseThrow().getSecretGeneration());
    }
}
