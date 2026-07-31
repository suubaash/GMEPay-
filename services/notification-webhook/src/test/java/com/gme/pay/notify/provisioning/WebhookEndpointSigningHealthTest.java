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
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gap <b>T5-8</b>: the signing-health report — "which endpoints cannot be signed for, and
 * does rotating fix them?"
 *
 * <p>The situation being pinned is the one T5-4 created and left unreported: a
 * {@code webhook_endpoint} row minted BEFORE per-endpoint derivation holds the digest of a
 * CSPRNG secret whose plaintext was never stored, so the dispatcher correctly refuses to sign
 * for it — silently, apart from one ERROR line per delivery attempt. These tests prove such a
 * row is now REPORTED as un-signable, that rotation turns it deliverable, and that the report
 * never leaks secret material.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({WebhookEndpointProvisioningService.class, ClockConfig.class,
        WebhookEndpointSigningHealthTest.DeriverConfig.class})
class WebhookEndpointSigningHealthTest {

    /** Test-fixture derivation root key — not a deployment secret. */
    static final String ROOT_KEY = "signing-health-test-root-key-0123456789";

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

    /**
     * Writes the endpoint row shape that existed before T5-4: a digest of pure CSPRNG
     * randomness, generation 1. This is what every historical row looks like — the plaintext
     * was returned once at activation and never stored, so nothing can re-derive it.
     */
    private WebhookEndpointEntity legacyUnderivableEndpoint(Long partnerId, String environment) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        WebhookEndpointEntity row = new WebhookEndpointEntity();
        row.setPartnerId(partnerId);
        row.setWebhookUrl("https://legacy.example.com/hooks/gmepay");
        row.setEnvironment(environment);
        row.setSigningSecretHash(SigningSecrets.sha256Hex(SigningSecrets.newSecret()));
        row.setSecretGeneration(WebhookSecretDeriver.INITIAL_GENERATION);
        row.setActive(true);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return repository.saveAndFlush(row);
    }

    private WebhookEndpointRegistrationView register(Long partnerId, String environment) {
        return service.register(new WebhookEndpointRegistrationCommand(
                partnerId, "https://partner.example.com/hooks/gmepay",
                List.of("payment.approved"), environment));
    }

    @Test
    @DisplayName("a freshly registered endpoint reports SIGNABLE / deliverable")
    void freshlyRegisteredEndpointIsSignable() {
        WebhookEndpointRegistrationView registered = register(4001L, "LIVE");

        List<WebhookEndpointSigningHealthView> health = service.signingHealth(4001L);

        assertThat(health).singleElement().satisfies(row -> {
            assertThat(row.endpointId()).isEqualTo(registered.endpointId());
            assertThat(row.status()).isEqualTo(WebhookEndpointSigningStatus.SIGNABLE.name());
            assertThat(row.deliverable()).isTrue();
            assertThat(row.fixableByRotation()).isFalse();
            assertThat(row.secretGeneration()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("T5-8: a pre-existing row whose secret cannot be derived is REPORTED "
            + "un-signable, and rotation makes it deliverable")
    void legacyRowIsReportedAndFixedByRotation() {
        WebhookEndpointEntity legacy = legacyUnderivableEndpoint(4002L, "LIVE");

        List<WebhookEndpointSigningHealthView> before = service.signingHealth(4002L);
        assertThat(before).singleElement().satisfies(row -> {
            assertThat(row.status())
                    .isEqualTo(WebhookEndpointSigningStatus.SECRET_NOT_DERIVABLE.name());
            assertThat(row.deliverable()).isFalse();
            // The whole point of the report: it tells the operator the remedy exists.
            assertThat(row.fixableByRotation()).isTrue();
            assertThat(row.detail()).contains("rotated");
        });

        WebhookSecretRotationView rotated = service.rotateSecret(legacy.getId(), 0L);
        assertThat(rotated.signingSecretPlaintext()).startsWith(SigningSecrets.SECRET_PREFIX);
        assertThat(rotated.secretGeneration()).isEqualTo(2);

        List<WebhookEndpointSigningHealthView> after = service.signingHealth(4002L);
        assertThat(after).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo(WebhookEndpointSigningStatus.SIGNABLE.name());
            assertThat(row.deliverable()).isTrue();
            assertThat(row.secretGeneration()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("the rotated secret is the one notification-webhook stored — the digest of "
            + "the revealed plaintext IS the row's signing_secret_hash")
    void rotatedSecretVerifiesAgainstWhatIsStored() {
        WebhookEndpointEntity legacy = legacyUnderivableEndpoint(4003L, "SANDBOX");

        WebhookSecretRotationView rotated = service.rotateSecret(legacy.getId(), 0L);

        WebhookEndpointEntity reloaded = repository.findById(legacy.getId()).orElseThrow();
        assertThat(SigningSecrets.matches(
                rotated.signingSecretPlaintext(), reloaded.getSigningSecretHash())).isTrue();
        // And it is the DERIVED secret for the new generation, i.e. the value the dispatcher
        // will independently reproduce at signing time — not fresh unrelated randomness.
        assertThat(rotated.signingSecretPlaintext()).isEqualTo(
                WebhookSecretDeriver.withRootKey(ROOT_KEY)
                        .derive(4003L, "SANDBOX", rotated.secretGeneration()));
    }

    @Test
    @DisplayName("a legacy V003 row with no digest at all reports NO_SECRET_DIGEST, also "
            + "fixable by rotation")
    void rowWithoutDigestIsReported() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        WebhookEndpointEntity row = new WebhookEndpointEntity();
        row.setPartnerId(4004L);
        row.setWebhookUrl("https://v003.example.com/hooks");
        row.setEnvironment("LIVE");
        row.setSigningSecretHash(null); // V003 rows were Vault-only
        row.setActive(true);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        repository.saveAndFlush(row);

        assertThat(service.signingHealth(4004L)).singleElement().satisfies(view -> {
            assertThat(view.status())
                    .isEqualTo(WebhookEndpointSigningStatus.NO_SECRET_DIGEST.name());
            assertThat(view.deliverable()).isFalse();
            assertThat(view.fixableByRotation()).isTrue();
        });
    }

    @Test
    @DisplayName("the platform-wide sweep (no partnerId) reports every active endpoint and "
            + "separates the deliverable from the dead")
    void platformWideSweepSeparatesDeliverableFromDead() {
        register(4010L, "LIVE");
        legacyUnderivableEndpoint(4011L, "LIVE");
        legacyUnderivableEndpoint(4012L, "SANDBOX");

        List<WebhookEndpointSigningHealthView> all = service.signingHealth(null);

        assertThat(all).hasSize(3);
        assertThat(all).filteredOn(WebhookEndpointSigningHealthView::deliverable).hasSize(1);
        assertThat(all).filteredOn(row -> !row.deliverable())
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.fixableByRotation()).isTrue());
    }

    @Test
    @DisplayName("the report carries no secret material — no plaintext, and not the digest")
    void reportLeaksNoSecretMaterial() {
        WebhookEndpointRegistrationView registered = register(4020L, "LIVE");
        String secretDigest = repository.findById(Long.valueOf(registered.endpointId()))
                .orElseThrow().getSigningSecretHash();

        String rendered = service.signingHealth(4020L).toString();

        assertThat(rendered).doesNotContain(SigningSecrets.SECRET_PREFIX);
        assertThat(rendered).doesNotContain(secretDigest);
        assertThat(rendered).doesNotContain(ROOT_KEY);
    }

    @Test
    @DisplayName("with no derivation root key every endpoint reports ROOT_KEY_MISSING and is "
            + "NOT presented as fixable by rotation (fix the config, not the row)")
    void withoutRootKeyNothingIsFixableByRotation() {
        WebhookEndpointEntity row = legacyUnderivableEndpoint(4030L, "LIVE");

        WebhookEndpointSigningStatus status = WebhookSecretVerifier.statusOf(
                WebhookSecretDeriver.withRootKey(""), row);

        assertThat(status).isEqualTo(WebhookEndpointSigningStatus.ROOT_KEY_MISSING);
        assertThat(status.isDeliverable()).isFalse();
        assertThat(status.isFixableByRotation()).isFalse();
    }
}
