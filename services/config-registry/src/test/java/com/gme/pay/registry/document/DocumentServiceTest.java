package com.gme.pay.registry.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.DocumentView;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import com.gme.pay.vault.InMemoryVaultClient;
import com.gme.pay.vault.VaultClient;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
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
 * Slice 3 (3A.1) acceptance test for {@link PartnerDocumentService} — the
 * {@code partner_document} upload path (V010) wired end-to-end against H2
 * PG-mode with Flyway V001..V010 applied and the {@link InMemoryVaultClient}
 * standing in for MinIO (the same bean the service runs with when
 * {@code gmepay.vault.endpoint} is unset). Mirrors the {@code ContactServiceTest}
 * slice-test pattern: {@code @DataJpaTest} + explicit {@code @Import} +
 * {@link RecordingAuditPublisher}.
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>Upload → list → download round trip: bytes/metadata survive, sha256 is
 *       recorded on the row and matches the actual digest.</li>
 *   <li>Re-upload of the same doc type supersedes the prior row (SCD-6 paired
 *       write, shared MICROS instant) and bumps version to v2 while v1 stays
 *       downloadable (version history).</li>
 *   <li>One {@code partner_document} audit event per upload, BEFORE null on
 *       first upload of a type, carrying the superseded row afterwards.</li>
 *   <li>400 on a bad doc type (no row, no audit), 404 unknown partner, 409
 *       non-ONBOARDING partner, 404 cross-partner docId probing.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DocumentServiceTest.TestConfig.class, PartnerDocumentService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class DocumentServiceTest {

    @Autowired
    private PartnerDocumentService service;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private RecordingAuditPublisher publisher;

    /**
     * Same publisher swap as {@code ContactServiceTest}, plus the in-memory
     * vault — the slice context does not run auto-configurations, so the
     * dev-default {@link InMemoryVaultClient} is registered explicitly here.
     */
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
        VaultClient vaultClient() {
            return new InMemoryVaultClient();
        }
    }

    // ------------------------------------------------------------------ helpers

    private Long seedPartner(String code) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
        return partnerRepository.findCurrentByPartnerCode(code).orElseThrow().getId();
    }

    private static InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private DocumentView upload(String code, String docType, String filename, String body) {
        return service.upload(code, docType, null, filename, "application/pdf",
                stream(body), "maker_kim");
    }

    // -------------------------------------------------------------------- tests

    @Test
    void upload_list_download_roundTrip_withSha256Recorded() throws Exception {
        seedPartner("DOC_RT");

        DocumentView uploaded = service.upload("DOC_RT", "LICENSE", LocalDate.of(2027, 6, 30),
                "license-2026.pdf", "application/pdf", stream("LICENSE-PDF-BYTES"), "maker_kim");

        assertThat(uploaded.id()).isNotNull();
        assertThat(uploaded.docType()).isEqualTo("LICENSE");
        assertThat(uploaded.filename()).isEqualTo("license-2026.pdf");
        assertThat(uploaded.contentType()).isEqualTo("application/pdf");
        assertThat(uploaded.version()).isEqualTo(1);
        assertThat(uploaded.vaultUri()).contains("/DOC_RT/LICENSE/").endsWith("/v1.pdf");
        assertThat(uploaded.sha256()).isEqualTo(sha256("LICENSE-PDF-BYTES"));
        assertThat(uploaded.expiryDate()).isEqualTo(LocalDate.of(2027, 6, 30));
        assertThat(uploaded.verifiedBy()).isNull();
        assertThat(uploaded.validFrom()).isEqualTo(uploaded.recordedAt());
        assertThat(uploaded.validTo()).isNull();

        List<DocumentView> current = service.currentDocuments("DOC_RT");
        assertThat(current).hasSize(1);
        assertThat(current.get(0).id()).isEqualTo(uploaded.id());

        PartnerDocumentService.DocumentDownload download =
                service.download("DOC_RT", uploaded.id());
        try (InputStream in = download.object().content()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("LICENSE-PDF-BYTES");
        }
        assertThat(download.object().contentType()).isEqualTo("application/pdf");
        assertThat(download.meta().sha256()).isEqualTo(uploaded.sha256());

        assertThat(service.metadata("DOC_RT", uploaded.id()).filename())
                .isEqualTo("license-2026.pdf");
    }

    @Test
    void reUpload_supersedesPriorRow_andBumpsVersion_priorVersionStaysDownloadable()
            throws Exception {
        Long partnerId = seedPartner("DOC_V2");

        DocumentView v1 = upload("DOC_V2", "LICENSE", "lic-v1.pdf", "license v1");
        DocumentView v2 = upload("DOC_V2", "LICENSE", "lic-v2.pdf", "license v2 renewed");

        assertThat(v1.version()).isEqualTo(1);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.vaultUri()).isNotEqualTo(v1.vaultUri());

        // Current set carries only v2.
        List<DocumentView> current = service.currentDocuments("DOC_V2");
        assertThat(current).hasSize(1);
        assertThat(current.get(0).version()).isEqualTo(2);

        // SCD-6: nothing deleted — 2 rows, v1 superseded with a superseded_at
        // EXACTLY equal to v2's recorded_at (paired write, MICROS-truncated).
        List<DocumentEntity> all = documentRepository.findAll().stream()
                .filter(e -> partnerId.equals(e.getPartnerId())).toList();
        assertThat(all).hasSize(2);
        DocumentEntity supersededRow = all.stream()
                .filter(e -> e.getSupersededAt() != null).findFirst().orElseThrow();
        assertThat(supersededRow.getId()).isEqualTo(v1.id());
        assertThat(supersededRow.getSupersededAt()).isEqualTo(v2.recordedAt());
        assertThat(v2.recordedAt().getNano() % 1000).isZero();

        // Version history: the superseded id still downloads the v1 bytes.
        try (InputStream in = service.download("DOC_V2", v1.id()).object().content()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("license v1");
        }

        // Doc types version independently: first AOA upload is v1.
        assertThat(upload("DOC_V2", "AOA", "aoa.pdf", "aoa bytes").version()).isEqualTo(1);
        assertThat(service.currentDocuments("DOC_V2")).hasSize(2);
    }

    @Test
    void audit_publishesOneEventPerUpload_withBeforeAfterSnapshots() throws Exception {
        seedPartner("DOC_AUDIT");
        publisher.clear(); // drop the PARTNER_SAVED event from the seed

        DocumentView v1 = upload("DOC_AUDIT", "CBDDQ", "cbddq.pdf", "wolfsberg pack v1");
        upload("DOC_AUDIT", "CBDDQ", "cbddq-fixed.pdf", "wolfsberg pack v2");

        List<AuditEvent> events = publisher.published();
        assertThat(events).as("one audit event per upload").hasSize(2);

        AuditEvent first = events.get(0);
        assertThat(first.aggregateType()).isEqualTo("partner_document");
        assertThat(first.aggregateId()).isEqualTo("DOC_AUDIT");
        assertThat(first.eventType()).isEqualTo("PARTNER_DOCUMENT_UPLOADED");
        assertThat(first.actorId()).isEqualTo("maker_kim");
        assertThat(first.beforeJsonb())
                .as("first upload of a type has no prior row — BEFORE must be null").isNull();
        String firstAfter = new String(first.afterJsonb(), StandardCharsets.UTF_8);
        assertThat(firstAfter)
                .contains("\"docType\":\"CBDDQ\"")
                .contains("\"filename\":\"cbddq.pdf\"")
                .contains("\"version\":1")
                .contains("\"sha256\":\"" + sha256("wolfsberg pack v1") + "\"");

        AuditEvent second = events.get(1);
        assertThat(new String(second.beforeJsonb(), StandardCharsets.UTF_8))
                .as("BEFORE carries the superseded row")
                .contains("\"filename\":\"cbddq.pdf\"")
                .contains("\"id\":" + v1.id());
        assertThat(new String(second.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"filename\":\"cbddq-fixed.pdf\"")
                .contains("\"version\":2");
    }

    @Test
    void badDocType_isRejectedWith400_withoutWritingAnything() {
        Long partnerId = seedPartner("DOC_BADTYPE");
        publisher.clear();

        assertThatThrownBy(() -> upload("DOC_BADTYPE", "PASSPORT", "p.pdf", "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("docType");
                });
        assertThatThrownBy(() -> upload("DOC_BADTYPE", null, "p.pdf", "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.upload("DOC_BADTYPE", "LICENSE", null, " ",
                "application/pdf", stream("x"), null))
                .as("blank filename")
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThat(documentRepository.findCurrentByPartnerId(partnerId)).isEmpty();
        assertThat(publisher.published()).isEmpty();
    }

    @Test
    void unknownPartner_404_onAllPaths() {
        assertThatThrownBy(() -> upload("DOC_GHOST", "LICENSE", "l.pdf", "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.currentDocuments("DOC_GHOST"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.download("DOC_GHOST", 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void nonOnboardingPartner_409_uploadsAreGated_readsStillWork() {
        seedPartner("DOC_LIVE");
        DocumentView uploaded = upload("DOC_LIVE", "LICENSE", "l.pdf", "license bytes");

        PartnerEntity current = partnerRepository.findCurrentByPartnerCode("DOC_LIVE").orElseThrow();
        current.setStatus(PartnerStatus.LIVE);
        partnerRepository.saveAndFlush(current);

        assertThatThrownBy(() -> upload("DOC_LIVE", "LICENSE", "l2.pdf", "renewal"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        // Reads on a LIVE partner keep working (examiners read live partners).
        assertThat(service.currentDocuments("DOC_LIVE")).hasSize(1);
        assertThat(service.metadata("DOC_LIVE", uploaded.id())).isNotNull();
    }

    @Test
    void docIdOfAnotherPartner_is404_notLeaked() {
        seedPartner("DOC_OWNER");
        seedPartner("DOC_PROBER");
        DocumentView owned = upload("DOC_OWNER", "FINANCIALS", "fs.pdf", "financials");

        assertThatThrownBy(() -> service.metadata("DOC_PROBER", owned.id()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.download("DOC_PROBER", owned.id()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
