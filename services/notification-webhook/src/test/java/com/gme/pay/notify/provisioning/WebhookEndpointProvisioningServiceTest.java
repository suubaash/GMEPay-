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
import org.springframework.context.annotation.Import;

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
@Import({WebhookEndpointProvisioningService.class, ClockConfig.class})
class WebhookEndpointProvisioningServiceTest {

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
}
