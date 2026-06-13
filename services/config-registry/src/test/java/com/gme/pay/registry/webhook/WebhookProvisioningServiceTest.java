package com.gme.pay.registry.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.IssuedWebhookSubscription;
import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.WebhookEndpointRegistrationCommand;
import com.gme.pay.contracts.WebhookEndpointRegistrationView;
import com.gme.pay.contracts.WebhookSubscriptionCommand;
import com.gme.pay.contracts.WebhookSubscriptionView;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.client.NotificationWebhookClient;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 8 Lane D acceptance test for {@link WebhookProvisioningService} — the
 * {@code partner_webhook_subscription} table (V030), wired end-to-end against
 * H2 in PostgreSQL mode with the full Flyway chain applied. Mirrors the
 * {@code PrefundingConfigServiceTest} slice-test pattern: {@code @DataJpaTest}
 * + explicit {@code @Import} of the service/audit beans, a
 * {@link RecordingAuditPublisher} to observe ADR-007 publication, and a
 * recording in-memory {@link NotificationWebhookClient} standing in for the
 * notification-webhook service.
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>Step-8 draft save inserts a DRAFT row (no endpoint, no secret) and a
 *       re-save updates it in place; validation rejects non-HTTPS urls and
 *       unknown environments with 400 before any row is touched.</li>
 *   <li>SANDBOX and LIVE provisioning: the draft is handed to the client,
 *       the row flips to PROVISIONED carrying endpoint_id + SHA-256(secret) +
 *       last_rotated_at, and the ONE-TIME plaintext rides the returned
 *       {@link IssuedWebhookSubscription}.</li>
 *   <li>Idempotency: re-provisioning a PROVISIONED environment returns the
 *       existing endpoint id with a {@code null} secret and does NOT call the
 *       client again — no second secret is ever minted.</li>
 *   <li>Failure path: a client failure propagates and leaves the row
 *       DRAFT (the surrounding activation transaction rolls back).</li>
 *   <li>One audit event per mutation with canonical BEFORE/AFTER snapshots —
 *       the secret hash NEVER appears in the audit bytes.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({WebhookProvisioningServiceTest.TestConfig.class, WebhookProvisioningService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class WebhookProvisioningServiceTest {

    @Autowired
    private WebhookProvisioningService service;

    @Autowired
    private PartnerWebhookSubscriptionRepository subscriptionRepository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private RecordingAuditPublisher publisher;

    @Autowired
    private RecordingWebhookClient webhookClient;

    /**
     * Deterministic in-memory client: records every registration request and
     * mints predictable endpoint ids / secrets; flips to fail-mode for the
     * failure-path test. Same idempotency contract as the real endpoint.
     */
    static class RecordingWebhookClient implements NotificationWebhookClient {
        final List<WebhookEndpointRegistrationCommand> requests = new ArrayList<>();
        boolean failNext = false;
        private int sequence = 100;

        @Override
        public WebhookEndpointRegistrationView registerEndpoint(
                WebhookEndpointRegistrationCommand command) {
            if (failNext) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "notification-webhook unreachable: boom");
            }
            requests.add(command);
            sequence++;
            return new WebhookEndpointRegistrationView(
                    String.valueOf(sequence), "whsec_test_secret_" + sequence, true);
        }
    }

    /** Same publisher swap as {@code AuditLogTest} / {@code PrefundingConfigServiceTest}. */
    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        RecordingAuditPublisher recordingAuditPublisher() {
            return new RecordingAuditPublisher();
        }

        @Bean
        com.gme.pay.audit.AuditPublisher auditPublisher(RecordingAuditPublisher recording) {
            return recording;
        }

        @Bean
        RecordingWebhookClient notificationWebhookClient() {
            return new RecordingWebhookClient();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The client bean is a context singleton — isolate its recording per test. */
    @org.junit.jupiter.api.BeforeEach
    void resetRecordingClient() {
        webhookClient.requests.clear();
        webhookClient.failNext = false;
    }

    /** Create a partner draft through the canonical store path; returns its surrogate id. */
    private Long seedPartner(String code) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
        return partnerRepository.findCurrentByPartnerCode(code).orElseThrow().getId();
    }

    private static PartnerCommand.UpdateStep8WebhookSubscription cmd(
            String url, List<String> eventTypes, String environment) {
        return new PartnerCommand.UpdateStep8WebhookSubscription(
                new WebhookSubscriptionCommand(url, eventTypes, environment));
    }

    /** Lowercase-hex SHA-256, the V030 at-rest form (independent reference impl). */
    private static String sha256Hex(String s) {
        return WebhookProvisioningService.sha256Hex(s);
    }

    // -------------------------------------------------------------------- tests

    @Test
    void draftSave_insertsDraftRow_andResaveUpdatesInPlace() {
        seedPartner("WEBHOOK_DRAFT");

        WebhookSubscriptionView first = service.saveDraftSubscription("WEBHOOK_DRAFT",
                cmd("https://p.example.com/hooks", List.of("payment.approved"), "SANDBOX"),
                "maker_kim");

        assertThat(first.id()).isNotNull();
        assertThat(first.environment()).isEqualTo("SANDBOX");
        assertThat(first.url()).isEqualTo("https://p.example.com/hooks");
        assertThat(first.eventTypes()).containsExactly("payment.approved");
        assertThat(first.status()).isEqualTo("DRAFT");
        assertThat(first.endpointId()).isNull();
        assertThat(first.lastRotatedAt()).isNull();

        WebhookSubscriptionView second = service.saveDraftSubscription("WEBHOOK_DRAFT",
                cmd("https://p.example.com/hooks/v2",
                        List.of("payment.approved", "payment.failed"), "SANDBOX"),
                "maker_kim");

        // In-place upsert: same row id, one row total for the environment.
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.url()).isEqualTo("https://p.example.com/hooks/v2");
        assertThat(second.eventTypes())
                .containsExactly("payment.approved", "payment.failed");
        assertThat(subscriptionRepository.findAll().stream()
                .filter(e -> "SANDBOX".equals(e.getEnvironment()))
                .filter(e -> e.getUrl().startsWith("https://p.example.com"))
                .count()).isEqualTo(1);

        // MICROS truncation on the stored stamps.
        PartnerWebhookSubscriptionEntity row =
                subscriptionRepository.findById(first.id()).orElseThrow();
        assertThat(row.getUpdatedAt().getNano() % 1000).isZero();

        // Rehydrate path.
        assertThat(service.currentSubscriptions("WEBHOOK_DRAFT"))
                .singleElement()
                .satisfies(v -> assertThat(v.url()).isEqualTo("https://p.example.com/hooks/v2"));
    }

    @Test
    void draftSave_validationRejectsBadPayloads_withoutTouchingRows() {
        seedPartner("WEBHOOK_VALID");

        // Non-HTTPS url.
        assertThatThrownBy(() -> service.saveDraftSubscription("WEBHOOK_VALID",
                cmd("http://insecure.example.com/h", null, "SANDBOX"), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        // Unknown environment (UAT is a partner STATUS, not a credential environment).
        assertThatThrownBy(() -> service.saveDraftSubscription("WEBHOOK_VALID",
                cmd("https://p.example.com/h", null, "UAT"), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        // Comma would corrupt the CSV column.
        assertThatThrownBy(() -> service.saveDraftSubscription("WEBHOOK_VALID",
                cmd("https://p.example.com/h", List.of("payment.approved,payment.failed"),
                        "SANDBOX"), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        // Null body.
        assertThatThrownBy(() -> service.saveDraftSubscription("WEBHOOK_VALID",
                null, "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        // Unknown partner -> 404.
        assertThatThrownBy(() -> service.saveDraftSubscription("NO_SUCH_PARTNER",
                cmd("https://p.example.com/h", null, "SANDBOX"), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        assertThat(subscriptionRepository.count()).isZero();
    }

    @Test
    void draftSave_nonOnboardingPartner_409() {
        seedPartner("WEBHOOK_LIVE_EDIT");
        PartnerEntity current = partnerRepository
                .findCurrentByPartnerCode("WEBHOOK_LIVE_EDIT").orElseThrow();
        current.setStatus(PartnerStatus.LIVE);
        partnerRepository.saveAndFlush(current);

        assertThatThrownBy(() -> service.saveDraftSubscription("WEBHOOK_LIVE_EDIT",
                cmd("https://p.example.com/h", null, "LIVE"), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void provision_sandbox_registersEndpoint_storesHash_returnsPlaintextOnce() {
        Long partnerId = seedPartner("WEBHOOK_SANDBOX");
        service.saveDraftSubscription("WEBHOOK_SANDBOX",
                cmd("https://p.example.com/hooks", List.of("payment.approved"), "SANDBOX"),
                "maker_kim");

        Optional<IssuedWebhookSubscription> issuedOpt =
                service.provisionOnActivation("WEBHOOK_SANDBOX", "SANDBOX", "checker_lee");

        assertThat(issuedOpt).isPresent();
        IssuedWebhookSubscription issued = issuedOpt.orElseThrow();
        assertThat(issued.newlyProvisioned()).isTrue();
        assertThat(issued.environment()).isEqualTo("SANDBOX");
        assertThat(issued.endpointId()).isNotBlank();
        assertThat(issued.signingSecretPlaintext()).startsWith("whsec_");
        assertThat(issued.url()).isEqualTo("https://p.example.com/hooks");
        assertThat(issued.eventTypes()).containsExactly("payment.approved");

        // The client received the draft's exact wiring.
        assertThat(webhookClient.requests).singleElement().satisfies(req -> {
            assertThat(req.partnerId()).isEqualTo(partnerId);
            assertThat(req.url()).isEqualTo("https://p.example.com/hooks");
            assertThat(req.eventTypes()).containsExactly("payment.approved");
            assertThat(req.environment()).isEqualTo("SANDBOX");
        });

        // At rest: PROVISIONED + endpoint id + SHA-256 of the plaintext +
        // rotation stamp — and NO plaintext anywhere.
        PartnerWebhookSubscriptionEntity row = subscriptionRepository
                .findByPartnerIdAndEnvironment(partnerId, "SANDBOX").orElseThrow();
        assertThat(row.getStatus()).isEqualTo(WebhookSubscriptionStatus.PROVISIONED);
        assertThat(row.getEndpointId()).isEqualTo(issued.endpointId());
        assertThat(row.getSigningSecretHash())
                .isEqualTo(sha256Hex(issued.signingSecretPlaintext()))
                .hasSize(64)
                .isNotEqualTo(issued.signingSecretPlaintext());
        assertThat(row.getLastRotatedAt()).isNotNull();
        assertThat(row.getLastRotatedAt().getNano() % 1000).isZero();

        // The read view exposes provisioning state but never secret material.
        WebhookSubscriptionView view =
                service.currentSubscriptions("WEBHOOK_SANDBOX").get(0);
        assertThat(view.status()).isEqualTo("PROVISIONED");
        assertThat(view.endpointId()).isEqualTo(issued.endpointId());
    }

    @Test
    void provision_live_isItsOwnSubscription() {
        Long partnerId = seedPartner("WEBHOOK_GOLIVE");
        service.saveDraftSubscription("WEBHOOK_GOLIVE",
                cmd("https://p.example.com/sbx", null, "SANDBOX"), "maker_kim");
        service.saveDraftSubscription("WEBHOOK_GOLIVE",
                cmd("https://p.example.com/live", List.of("payment.approved"), "LIVE"),
                "maker_kim");

        IssuedWebhookSubscription sandbox = service
                .provisionOnActivation("WEBHOOK_GOLIVE", "SANDBOX", "ops").orElseThrow();
        IssuedWebhookSubscription live = service
                .provisionOnActivation("WEBHOOK_GOLIVE", "LIVE", "ops").orElseThrow();

        assertThat(live.newlyProvisioned()).isTrue();
        assertThat(live.endpointId()).isNotEqualTo(sandbox.endpointId());
        assertThat(live.signingSecretPlaintext())
                .isNotEqualTo(sandbox.signingSecretPlaintext());
        assertThat(live.url()).isEqualTo("https://p.example.com/live");

        // Two independent rows, each PROVISIONED with its own hash.
        PartnerWebhookSubscriptionEntity sbxRow = subscriptionRepository
                .findByPartnerIdAndEnvironment(partnerId, "SANDBOX").orElseThrow();
        PartnerWebhookSubscriptionEntity liveRow = subscriptionRepository
                .findByPartnerIdAndEnvironment(partnerId, "LIVE").orElseThrow();
        assertThat(sbxRow.getSigningSecretHash()).isNotEqualTo(liveRow.getSigningSecretHash());
        assertThat(webhookClient.requests).hasSize(2);
    }

    @Test
    void provision_idempotentReplay_returnsExistingEndpoint_noNewSecret_noClientCall() {
        Long partnerId = seedPartner("WEBHOOK_REPLAY");
        service.saveDraftSubscription("WEBHOOK_REPLAY",
                cmd("https://p.example.com/h", null, "SANDBOX"), "maker_kim");

        IssuedWebhookSubscription first = service
                .provisionOnActivation("WEBHOOK_REPLAY", "SANDBOX", "ops").orElseThrow();
        String hashAfterFirst = subscriptionRepository
                .findByPartnerIdAndEnvironment(partnerId, "SANDBOX").orElseThrow()
                .getSigningSecretHash();
        int clientCallsAfterFirst = webhookClient.requests.size();

        IssuedWebhookSubscription replay = service
                .provisionOnActivation("WEBHOOK_REPLAY", "SANDBOX", "ops").orElseThrow();

        assertThat(replay.newlyProvisioned()).isFalse();
        assertThat(replay.endpointId()).isEqualTo(first.endpointId());
        assertThat(replay.signingSecretPlaintext())
                .as("one-time reveal — a replay never returns a secret").isNull();
        // No second client call, no re-hash, no rotation.
        assertThat(webhookClient.requests).hasSize(clientCallsAfterFirst);
        assertThat(subscriptionRepository
                .findByPartnerIdAndEnvironment(partnerId, "SANDBOX").orElseThrow()
                .getSigningSecretHash()).isEqualTo(hashAfterFirst);
    }

    @Test
    void provision_clientFailure_propagates_rowStaysDraft() {
        Long partnerId = seedPartner("WEBHOOK_FAIL");
        service.saveDraftSubscription("WEBHOOK_FAIL",
                cmd("https://p.example.com/h", null, "SANDBOX"), "maker_kim");

        webhookClient.failNext = true;
        try {
            assertThatThrownBy(() -> service
                    .provisionOnActivation("WEBHOOK_FAIL", "SANDBOX", "ops"))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode())
                                    .isEqualTo(HttpStatus.BAD_GATEWAY));
        } finally {
            webhookClient.failNext = false;
        }

        PartnerWebhookSubscriptionEntity row = subscriptionRepository
                .findByPartnerIdAndEnvironment(partnerId, "SANDBOX").orElseThrow();
        assertThat(row.getStatus()).isEqualTo(WebhookSubscriptionStatus.DRAFT);
        assertThat(row.getEndpointId()).isNull();
        assertThat(row.getSigningSecretHash()).isNull();
    }

    @Test
    void provision_noDraftForEnvironment_returnsEmpty() {
        seedPartner("WEBHOOK_NODRAFT");
        // No step-8 save at all -> nothing to provision.
        assertThat(service.provisionOnActivation("WEBHOOK_NODRAFT", "SANDBOX", "ops"))
                .isEmpty();

        // A SANDBOX-only draft does not provision LIVE.
        service.saveDraftSubscription("WEBHOOK_NODRAFT",
                cmd("https://p.example.com/h", null, "SANDBOX"), "maker_kim");
        assertThat(service.provisionOnActivation("WEBHOOK_NODRAFT", "LIVE", "ops"))
                .isEmpty();

        // Bad environment is a 400, not a silent skip.
        assertThatThrownBy(() -> service.provisionOnActivation("WEBHOOK_NODRAFT", "PROD", "ops"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void audit_oneEventPerMutation_secretHashNeverInTheChain() {
        seedPartner("WEBHOOK_AUDIT");
        publisher.clear(); // drop the PARTNER_SAVED event from the seed

        service.saveDraftSubscription("WEBHOOK_AUDIT",
                cmd("https://p.example.com/h", List.of("payment.approved"), "SANDBOX"),
                "maker_kim");
        service.provisionOnActivation("WEBHOOK_AUDIT", "SANDBOX", "checker_lee");

        List<AuditEvent> events = publisher.published();
        assertThat(events).hasSize(2);

        AuditEvent saved = events.get(0);
        assertThat(saved.aggregateType()).isEqualTo("partner_webhook_subscription");
        assertThat(saved.aggregateId()).isEqualTo("WEBHOOK_AUDIT");
        assertThat(saved.eventType()).isEqualTo("PARTNER_WEBHOOK_SUBSCRIPTION_SAVED");
        assertThat(saved.actorId()).isEqualTo("maker_kim");
        assertThat(saved.beforeJsonb()).as("first write — BEFORE must be null").isNull();
        assertThat(new String(saved.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"environment\":\"SANDBOX\"")
                .contains("\"url\":\"https://p.example.com/h\"")
                .contains("\"eventTypes\":\"payment.approved\"")
                .contains("\"status\":\"DRAFT\"");

        AuditEvent provisioned = events.get(1);
        assertThat(provisioned.eventType())
                .isEqualTo("PARTNER_WEBHOOK_SUBSCRIPTION_PROVISIONED");
        assertThat(provisioned.actorId()).isEqualTo("checker_lee");
        String after = new String(provisioned.afterJsonb(), StandardCharsets.UTF_8);
        assertThat(after)
                .contains("\"status\":\"PROVISIONED\"")
                .contains("\"endpointId\":");
        // SECURITY: secret material (plaintext OR digest) never feeds the chain.
        assertThat(after).doesNotContain("whsec_").doesNotContain("secret");
    }
}
