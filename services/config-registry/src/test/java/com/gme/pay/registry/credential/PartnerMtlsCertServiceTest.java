package com.gme.pay.registry.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.PartnerMtlsCertView;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 8 Lane B acceptance test for {@link PartnerMtlsCertService} — the
 * {@code partner_mtls_cert} SCD-6 aggregate (V027), wired against H2 in
 * PostgreSQL mode with the full Flyway chain applied. Mirrors the
 * {@code PartnerSchemeServiceTest} slice-test pattern.
 *
 * <h2>Fixtures</h2>
 *
 * <p>Three real, openssl-generated PEMs under {@code src/test/resources/certs}:
 * <ul>
 *   <li>{@code valid.pem} — notAfter 2097 (long-lived; if this test outlives
 *       it, civilisational congratulations);</li>
 *   <li>{@code expired.pem} — notAfter 2021-01-01;</li>
 *   <li>{@code future.pem} — notBefore 2090-01-01.</li>
 * </ul>
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>A valid PEM parses: fingerprint = SHA-256 over the DER encoding
 *       (recomputed independently here), subject/issuer DNs + the X.509
 *       window land on the row, status ACTIVE.</li>
 *   <li>Expired and not-yet-valid leafs are 400s; garbage is a 400.</li>
 *   <li>Re-upload supersedes the prior binding (SCD-6 paired write, shared
 *       instant); the identical fingerprint re-uploaded is a 409.</li>
 *   <li>Environment scoping: SANDBOX and PRODUCTION bindings coexist.</li>
 *   <li>Revoke: the ACTIVE row is superseded and a REVOKED successor
 *       inserted — history keeps both.</li>
 *   <li>ADR-007: one audit event per write, fingerprint in the snapshot,
 *       PEM body NEVER in the snapshot.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PartnerMtlsCertServiceTest.TestConfig.class, PartnerMtlsCertService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class PartnerMtlsCertServiceTest {

    @Autowired
    private PartnerMtlsCertService service;

    @Autowired
    private PartnerMtlsCertRepository repository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private RecordingAuditPublisher publisher;

    /** Same publisher swap as {@code AuditLogTest} / {@code RuleServiceTest}. */
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
    }

    // ------------------------------------------------------------------ helpers

    private Long seedPartner(String code) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
        return partnerRepository.findCurrentByPartnerCode(code).orElseThrow().getId();
    }

    private static String pem(String name) {
        try {
            return new String(new ClassPathResource("certs/" + name).getInputStream()
                    .readAllBytes(), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException("missing test fixture certs/" + name, e);
        }
    }

    /** Independent fingerprint recomputation — pins the DER + SHA-256 contract. */
    private static String expectedFingerprint(String pemBody) throws CertificateException {
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(
                        pemBody.getBytes(StandardCharsets.US_ASCII)));
        return PartnerMtlsCertService.sha256Hex(cert.getEncoded());
    }

    // ------------------------------------------------------------------ tests

    @Test
    void validCert_parses_fingerprintIsSha256OfDer_dnsAndWindowStored() throws Exception {
        seedPartner("MTL_OK");
        PartnerMtlsCertView view =
                service.uploadCert("MTL_OK", "SANDBOX", pem("valid.pem"), "maker_kim");

        assertThat(view.fingerprintSha256())
                .isEqualTo(expectedFingerprint(pem("valid.pem")))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
        assertThat(view.subjectDn()).contains("partner-valid.example.com");
        assertThat(view.issuerDn()).isNotBlank();
        assertThat(view.notBefore()).isNotNull();
        assertThat(view.notAfter()).isAfter(view.notBefore());
        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.environment()).isEqualTo("SANDBOX");

        // The PEM body is stored on the row (gateway provisioning needs it).
        PartnerMtlsCertEntity row = repository.findById(view.id()).orElseThrow();
        assertThat(row.getCertPem()).contains("BEGIN CERTIFICATE");
        assertThat(row.getCurrentCertKey()).endsWith(view.fingerprintSha256());
    }

    @Test
    void expiredCert_is400() {
        seedPartner("MTL_EXP");
        assertThatThrownBy(() ->
                service.uploadCert("MTL_EXP", "SANDBOX", pem("expired.pem"), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("expired");
                });
        assertThat(repository.count()).isZero();
    }

    @Test
    void notYetValidCert_is400_andGarbageIs400() {
        seedPartner("MTL_FUT");
        assertThatThrownBy(() ->
                service.uploadCert("MTL_FUT", "SANDBOX", pem("future.pem"), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("not yet valid");
                });
        assertThatThrownBy(() ->
                service.uploadCert("MTL_FUT", "SANDBOX", "not a pem at all", "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(repository.count()).isZero();
    }

    @Test
    void reupload_supersedesPriorBinding_scd6Paired_identicalFingerprintIs409() {
        seedPartner("MTL_REPL");
        PartnerMtlsCertView first =
                service.uploadCert("MTL_REPL", "SANDBOX", pem("valid.pem"), "maker_kim");

        // The identical cert again is a 409 (nothing to replace).
        assertThatThrownBy(() ->
                service.uploadCert("MTL_REPL", "SANDBOX", pem("valid.pem"), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        // A different leaf replaces it: prior row superseded, key vacated.
        PartnerMtlsCertView second =
                service.uploadCert("MTL_REPL", "SANDBOX", pem("valid2.pem"), "maker_kim");
        assertThat(second.fingerprintSha256()).isNotEqualTo(first.fingerprintSha256());

        PartnerMtlsCertEntity firstRow = repository.findById(first.id()).orElseThrow();
        PartnerMtlsCertEntity secondRow = repository.findById(second.id()).orElseThrow();
        assertThat(firstRow.getSupersededAt()).isNotNull();
        assertThat(firstRow.getCurrentCertKey()).isNull();
        assertThat(secondRow.getSupersededAt()).isNull();
        // Paired write: supersede + insert share the transaction instant.
        assertThat(firstRow.getSupersededAt()).isEqualTo(secondRow.getRecordedAt());

        assertThat(service.currentCerts("MTL_REPL")).hasSize(1);
    }

    @Test
    void environmentScoping_sandboxAndProductionBindingsCoexist() {
        seedPartner("MTL_ENV");
        service.uploadCert("MTL_ENV", "SANDBOX", pem("valid.pem"), "maker_kim");
        service.uploadCert("MTL_ENV", "PRODUCTION", pem("valid.pem"), "maker_kim");

        List<PartnerMtlsCertView> current = service.currentCerts("MTL_ENV");
        assertThat(current).hasSize(2);
        assertThat(current).extracting(PartnerMtlsCertView::environment)
                .containsExactlyInAnyOrder("SANDBOX", "PRODUCTION");

        // Bad environment is a 400; unknown partner is a 404.
        assertThatThrownBy(() ->
                service.uploadCert("MTL_ENV", "STAGING", pem("valid.pem"), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.currentCerts("MTL_NOPE"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void revoke_supersedesActiveRow_insertsRevokedSuccessor_historyKeepsBoth() {
        seedPartner("MTL_REV");
        PartnerMtlsCertView active =
                service.uploadCert("MTL_REV", "PRODUCTION", pem("valid.pem"), "maker_kim");

        PartnerMtlsCertView revoked =
                service.revokeCert("MTL_REV", "PRODUCTION", "checker_lee");
        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(revoked.fingerprintSha256()).isEqualTo(active.fingerprintSha256());

        // SCD-6: ACTIVE row superseded, REVOKED row current; both persisted.
        PartnerMtlsCertEntity activeRow = repository.findById(active.id()).orElseThrow();
        assertThat(activeRow.getSupersededAt()).isNotNull();
        assertThat(repository.count()).isEqualTo(2);
        List<PartnerMtlsCertView> current = service.currentCerts("MTL_REV");
        assertThat(current).hasSize(1);
        assertThat(current.get(0).status()).isEqualTo("REVOKED");

        // Revoking again finds no ACTIVE binding → 404.
        assertThatThrownBy(() -> service.revokeCert("MTL_REV", "PRODUCTION", "checker_lee"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void audit_oneEventPerWrite_fingerprintInSnapshot_pemNeverInSnapshot() {
        seedPartner("MTL_AUD");
        publisher.clear();

        PartnerMtlsCertView uploaded =
                service.uploadCert("MTL_AUD", "SANDBOX", pem("valid.pem"), "maker_kim");
        service.revokeCert("MTL_AUD", "SANDBOX", "checker_lee");

        List<AuditEvent> events = publisher.published().stream()
                .filter(e -> PartnerMtlsCertService.AGGREGATE_TYPE.equals(e.aggregateType()))
                .toList();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).eventType())
                .isEqualTo(PartnerMtlsCertService.EVENT_TYPE_UPLOADED);
        assertThat(events.get(1).eventType())
                .isEqualTo(PartnerMtlsCertService.EVENT_TYPE_REVOKED);

        for (AuditEvent event : events) {
            String after = new String(event.afterJsonb(), StandardCharsets.UTF_8);
            assertThat(after).contains(uploaded.fingerprintSha256());
            assertThat(after).doesNotContain("BEGIN CERTIFICATE");
        }
    }
}
