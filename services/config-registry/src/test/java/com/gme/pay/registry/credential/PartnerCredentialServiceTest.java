package com.gme.pay.registry.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.IssuedCredentialBundle;
import com.gme.pay.contracts.PartnerCredentialView;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.client.AuthIdentityClient;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Slice 8 Lane B acceptance test for {@link PartnerCredentialService} — the
 * {@code partner_credential} ledger (V028) plus the issuance / rotation /
 * revocation flows, wired against H2 in PostgreSQL mode with a RECORDING
 * auth-identity double (no HTTP): the test observes exactly what crossed the
 * port and what landed in the ledger.
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>SANDBOX issuance: pk_test_/sk_test_/whsec_test_ prefixes, 3 ACTIVE
 *       ledger rows, the one-time bundle carries all three plaintexts.</li>
 *   <li>LIVE issuance ({@code issueForTransition("LIVE")}): pk_live_ tier,
 *       PRODUCTION environment.</li>
 *   <li>SEC-09 §4: NO plaintext secret ever lands in a ledger column, an
 *       audit snapshot, or the bundle's {@code toString()}.</li>
 *   <li>Double issuance is a 409 CREDENTIALS_ALREADY_ISSUED; the transition
 *       hook is idempotent instead (REACTIVATE-safe).</li>
 *   <li>Rotation: old rows ROTATED (+rotated_at), old keys revoked at
 *       auth-identity, fresh ACTIVE set issued, new bundle returned.</li>
 *   <li>Revocation: rows REVOKED (+revoked_at), keys revoked, no
 *       replacement.</li>
 *   <li>ADR-007: one audit event per write with the canonical verbs.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PartnerCredentialServiceTest.TestConfig.class, PartnerCredentialService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class PartnerCredentialServiceTest {

    @Autowired
    private PartnerCredentialService service;

    @Autowired
    private PartnerCredentialRepository repository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private RecordingAuditPublisher publisher;

    @Autowired
    private RecordingAuthIdentityClient authIdentity;

    /**
     * Deterministic auth-identity double: predictable token bodies so the
     * test can assert which plaintexts crossed the port, plus a revocation
     * ledger.
     */
    static class RecordingAuthIdentityClient implements AuthIdentityClient {
        final AtomicInteger counter = new AtomicInteger();
        final List<IssueKeyCommand> issued = new ArrayList<>();
        final List<String> revoked = new ArrayList<>();

        @Override
        public IssuedKey issueKey(IssueKeyCommand command) {
            issued.add(command);
            int n = counter.incrementAndGet();
            return new IssuedKey(
                    command.keyPrefix() + "KEY" + n + "abcd",
                    command.secretPrefix() + "SECRET" + n + "wxyz",
                    command.expiresAt());
        }

        @Override
        public void revokeKey(String keyId) {
            revoked.add(keyId);
        }
    }

    /** Same publisher swap as {@code RuleServiceTest}; plus the recording client. */
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
        @Primary
        RecordingAuthIdentityClient recordingAuthIdentityClient() {
            return new RecordingAuthIdentityClient();
        }
    }

    // ------------------------------------------------------------------ helpers

    private void seedPartner(String code) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
    }

    // ------------------------------------------------------------------ tests

    @Test
    void sandboxIssuance_testPrefixes_threeActiveLedgerRows_bundleCarriesPlaintexts() {
        seedPartner("CRD_SBX");
        IssuedCredentialBundle bundle =
                service.issueCredentials("CRD_SBX", "SANDBOX", "maker_kim");

        assertThat(bundle.apiKeyPlaintext()).startsWith("pk_test_");
        assertThat(bundle.hmacSecretPlaintext()).startsWith("sk_test_");
        assertThat(bundle.webhookSecretPlaintext()).startsWith("whsec_test_");
        assertThat(bundle.apiKeyId()).isEqualTo(bundle.apiKeyPlaintext());
        assertThat(bundle.prefix()).isEqualTo("pk_test_");
        assertThat(bundle.last4())
                .isEqualTo(bundle.apiKeyPlaintext().substring(
                        bundle.apiKeyPlaintext().length() - 4));
        assertThat(bundle.expiresAt()).isNotNull();

        List<PartnerCredentialView> ledger = service.listCredentials("CRD_SBX");
        assertThat(ledger).hasSize(3);
        assertThat(ledger).allSatisfy(v -> {
            assertThat(v.environment()).isEqualTo("SANDBOX");
            assertThat(v.status()).isEqualTo("ACTIVE");
            assertThat(v.issuedAt()).isNotNull();
            assertThat(v.expiresAt())
                    .isEqualTo(v.issuedAt().atOffset(java.time.ZoneOffset.UTC)
                            .plusMonths(PartnerCredentialService.VALIDITY_MONTHS)
                            .toInstant().truncatedTo(ChronoUnit.MICROS));
        });
        assertThat(ledger).extracting(PartnerCredentialView::credentialKind)
                .containsExactlyInAnyOrder("API_KEY", "HMAC_SECRET", "WEBHOOK_SECRET");

        // Two auth-identity calls minted the material: API pair + webhook.
        assertThat(authIdentity.issued).extracting(
                        AuthIdentityClient.IssueKeyCommand::purpose)
                .contains("API", "WEBHOOK");
    }

    @Test
    void liveTransition_issuesProductionTier_pkLivePrefixes() {
        seedPartner("CRD_LIVE");
        Optional<IssuedCredentialBundle> bundle =
                service.issueForTransition("CRD_LIVE", "LIVE", "checker_lee");

        assertThat(bundle).isPresent();
        assertThat(bundle.get().apiKeyPlaintext()).startsWith("pk_live_");
        assertThat(bundle.get().hmacSecretPlaintext()).startsWith("sk_live_");
        assertThat(bundle.get().webhookSecretPlaintext()).startsWith("whsec_live_");
        assertThat(service.listCredentials("CRD_LIVE"))
                .allSatisfy(v -> assertThat(v.environment()).isEqualTo("PRODUCTION"));
    }

    @Test
    void transitionHook_sandboxIssues_otherStatusesIssueNothing_reentryIdempotent() {
        seedPartner("CRD_HOOK");
        // Non-credential statuses are a no-op.
        assertThat(service.issueForTransition("CRD_HOOK", "KYB_PENDING", "x")).isEmpty();
        assertThat(service.issueForTransition("CRD_HOOK", "SUSPENDED", "x")).isEmpty();
        assertThat(service.issueForTransition("CRD_HOOK", null, "x")).isEmpty();

        // First SANDBOX entry issues; the second (re-entry) is idempotent.
        assertThat(service.issueForTransition("CRD_HOOK", "SANDBOX", "x")).isPresent();
        assertThat(service.issueForTransition("CRD_HOOK", "SANDBOX", "x")).isEmpty();
        assertThat(service.listCredentials("CRD_HOOK")).hasSize(3);
    }

    @Test
    void doubleDirectIssuance_is409CredentialsAlreadyIssued() {
        seedPartner("CRD_DUP");
        service.issueCredentials("CRD_DUP", "SANDBOX", "maker_kim");
        assertThatThrownBy(() -> service.issueCredentials("CRD_DUP", "SANDBOX", "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getReason()).contains("CREDENTIALS_ALREADY_ISSUED");
                });
        // But the OTHER environment is fine.
        assertThat(service.issueCredentials("CRD_DUP", "PRODUCTION", "maker_kim"))
                .isNotNull();
    }

    @Test
    void rotation_marksOldRotated_revokesOldKeys_issuesFreshActiveSet() {
        seedPartner("CRD_ROT");
        IssuedCredentialBundle first =
                service.issueCredentials("CRD_ROT", "SANDBOX", "maker_kim");

        IssuedCredentialBundle second =
                service.rotateCredentials("CRD_ROT", "SANDBOX", "checker_lee");

        assertThat(second.apiKeyPlaintext()).isNotEqualTo(first.apiKeyPlaintext());
        assertThat(second.hmacSecretPlaintext()).isNotEqualTo(first.hmacSecretPlaintext());

        List<PartnerCredentialView> ledger = service.listCredentials("CRD_ROT");
        assertThat(ledger).hasSize(6);
        assertThat(ledger.stream().filter(v -> "ROTATED".equals(v.status()))).hasSize(3);
        assertThat(ledger.stream().filter(v -> "ACTIVE".equals(v.status()))).hasSize(3);
        assertThat(ledger.stream().filter(v -> "ROTATED".equals(v.status())))
                .allSatisfy(v -> assertThat(v.rotatedAt()).isNotNull());

        // The old API-pair key and the old webhook key were revoked upstream.
        assertThat(authIdentity.revoked).contains(first.apiKeyId());
        assertThat(authIdentity.revoked).hasSize(2);

        // Rotating an environment with nothing ACTIVE is a 409.
        assertThatThrownBy(() ->
                service.rotateCredentials("CRD_ROT", "PRODUCTION", "checker_lee"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void revocation_marksRevoked_revokesKeys_noReplacementIssued() {
        seedPartner("CRD_REV");
        IssuedCredentialBundle bundle =
                service.issueCredentials("CRD_REV", "PRODUCTION", "maker_kim");

        List<PartnerCredentialView> revoked =
                service.revokeCredentials("CRD_REV", "PRODUCTION", "checker_lee");
        assertThat(revoked).hasSize(3);
        assertThat(revoked).allSatisfy(v -> {
            assertThat(v.status()).isEqualTo("REVOKED");
            assertThat(v.revokedAt()).isNotNull();
        });
        assertThat(authIdentity.revoked).contains(bundle.apiKeyId());
        // Nothing fresh was issued.
        assertThat(service.listCredentials("CRD_REV")).hasSize(3);
    }

    @Test
    void sec09_noPlaintextInLedger_orAuditSnapshots_orBundleToString() {
        seedPartner("CRD_SEC");
        publisher.clear();
        IssuedCredentialBundle bundle =
                service.issueCredentials("CRD_SEC", "SANDBOX", "maker_kim");

        String hmac = bundle.hmacSecretPlaintext();
        String webhook = bundle.webhookSecretPlaintext();

        // Ledger rows carry residue only: key id + prefix + last 4.
        for (PartnerCredentialEntity row : repository.findAll()) {
            assertThat(row.getAuthIdentityKeyId()).isNotEqualTo(hmac);
            assertThat(row.getAuthIdentityKeyId()).isNotEqualTo(webhook);
            assertThat(row.getPrefix().length()).isLessThanOrEqualTo(20);
            if (row.getLast4() != null) {
                assertThat(row.getLast4()).hasSize(4);
            }
        }

        // Audit snapshots never contain the secrets.
        for (AuditEvent event : publisher.published()) {
            String after = event.afterJsonb() == null
                    ? "" : new String(event.afterJsonb(), StandardCharsets.UTF_8);
            assertThat(after).doesNotContain(hmac);
            assertThat(after).doesNotContain(webhook);
        }

        // A stray logger call on the bundle leaks nothing.
        assertThat(bundle.toString())
                .contains("REDACTED")
                .doesNotContain(hmac)
                .doesNotContain(webhook);
    }

    @Test
    void audit_canonicalVerbs_oneEventPerWrite() {
        seedPartner("CRD_AUD");
        publisher.clear();

        service.issueCredentials("CRD_AUD", "SANDBOX", "maker_kim");
        service.rotateCredentials("CRD_AUD", "SANDBOX", "checker_lee");
        service.revokeCredentials("CRD_AUD", "SANDBOX", "checker_lee");

        List<AuditEvent> events = publisher.published().stream()
                .filter(e -> PartnerCredentialService.AGGREGATE_TYPE
                        .equals(e.aggregateType()))
                .toList();
        assertThat(events).extracting(AuditEvent::eventType).containsExactly(
                PartnerCredentialService.EVENT_TYPE_ISSUED,
                PartnerCredentialService.EVENT_TYPE_ROTATED,
                PartnerCredentialService.EVENT_TYPE_REVOKED);
        assertThat(events.get(0).beforeJsonb()).isNull();
        assertThat(events.get(1).beforeJsonb()).isNotNull();
    }

    @Test
    void unknownPartner_404_badEnvironment_400() {
        assertThatThrownBy(() -> service.issueCredentials("CRD_NOPE", "SANDBOX", "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        seedPartner("CRD_ENV");
        assertThatThrownBy(() -> service.issueCredentials("CRD_ENV", "STAGING", "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
