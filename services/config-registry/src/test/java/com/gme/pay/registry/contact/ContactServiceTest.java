package com.gme.pay.registry.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.ContactCommand;
import com.gme.pay.contracts.ContactView;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
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
 * Slice 2 (2A.1) acceptance test for {@link PartnerContactService} — the
 * {@code partner_contact} bulk-replace path (V009) wired end-to-end against the
 * H2 PostgreSQL-mode database with Flyway V001..V009 applied. Mirrors the
 * {@code AuditLogTest} slice-test pattern: {@code @DataJpaTest} + explicit
 * {@code @Import} of the service/audit beans + a {@link RecordingAuditPublisher}
 * to observe ADR-007 publication.
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>Bulk replace inserts the new set as CURRENT rows and a second replace
 *       supersedes the first set — paired SCD-6 writes sharing one
 *       MICROS-truncated instant (prior {@code superseded_at} == fresh
 *       {@code recorded_at}).</li>
 *   <li>Server-side validation rejects bad role / name / email / E.164 phone /
 *       over-long notes with 400, without touching any row.</li>
 *   <li>One {@code partner_contact} audit event per replace, BEFORE null on the
 *       first write and carrying the superseded set afterwards.</li>
 *   <li>Unknown partner code → 404; partner outside ONBOARDING → 409.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ContactServiceTest.TestConfig.class, PartnerContactService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class ContactServiceTest {

    @Autowired
    private PartnerContactService service;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private RecordingAuditPublisher publisher;

    /** Same publisher swap as {@code AuditLogTest}: record what ADR-007 fans out. */
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

    /** Create a partner draft through the canonical store path; returns its surrogate id. */
    private Long seedPartner(String code) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
        return partnerRepository.findCurrentByPartnerCode(code).orElseThrow().getId();
    }

    private static ContactCommand contact(String role, String name, String email,
                                          String phone, boolean signatory) {
        return new ContactCommand(role, name, email, phone, signatory, null);
    }

    // -------------------------------------------------------------------- tests

    @Test
    void bulkReplace_insertsCurrentSetAndSupersedesPriorSet() {
        Long partnerId = seedPartner("CT_REPLACE");

        // First save: two contacts.
        List<ContactView> first = service.replaceDraftContacts("CT_REPLACE", List.of(
                contact("OPS_24X7", "Ops Desk", "ops@partner.example", "+821012345678", false),
                contact("FINANCE", "Fin Lee", "finance@partner.example", null, true)),
                "maker_kim");

        assertThat(first).hasSize(2);
        assertThat(first.get(0).id()).isNotNull();
        assertThat(first.get(0).role()).isEqualTo("OPS_24X7");
        assertThat(first.get(1).authorizedSignatory()).isTrue();
        assertThat(contactRepository.findCurrentByPartnerId(partnerId)).hasSize(2);

        // Second save: replace with a different three-contact set.
        List<ContactView> second = service.replaceDraftContacts("CT_REPLACE", List.of(
                contact("OPS_24X7", "Ops Desk", "ops@partner.example", "+821012345678", false),
                contact("COMPLIANCE_MLRO", "MLRO Park", "mlro@partner.example", "+8429876543", true),
                contact("TECH", "Dev Choi", "tech@partner.example", null, false)),
                "maker_kim");

        assertThat(second).hasSize(3);

        // Current view is exactly the second set.
        List<ContactEntity> current = contactRepository.findCurrentByPartnerId(partnerId);
        assertThat(current).hasSize(3);
        assertThat(current).extracting(e -> e.getRole().name())
                .containsExactly("OPS_24X7", "COMPLIANCE_MLRO", "TECH");

        // SCD-6: nothing was deleted — 5 rows total, the first 2 superseded with
        // a superseded_at that EXACTLY equals the second set's recorded_at (the
        // paired-write instants are shared and MICROS-truncated).
        List<ContactEntity> all = contactRepository.findAll().stream()
                .filter(e -> partnerId.equals(e.getPartnerId())).toList();
        assertThat(all).hasSize(5);
        List<ContactEntity> superseded = all.stream()
                .filter(e -> e.getSupersededAt() != null).toList();
        assertThat(superseded).hasSize(2);
        java.time.Instant freshRecordedAt = current.get(0).getRecordedAt();
        assertThat(superseded).allSatisfy(e ->
                assertThat(e.getSupersededAt())
                        .as("prior superseded_at must equal the fresh recorded_at (paired write)")
                        .isEqualTo(freshRecordedAt));
        // MICROS discipline: stored instants must carry no sub-microsecond part.
        assertThat(freshRecordedAt.getNano() % 1000).isZero();
    }

    @Test
    void bulkReplace_emptyListClearsAllContacts() {
        Long partnerId = seedPartner("CT_CLEAR");
        service.replaceDraftContacts("CT_CLEAR", List.of(
                contact("LEGAL", "Law Kwon", "legal@partner.example", null, false)), null);
        assertThat(contactRepository.findCurrentByPartnerId(partnerId)).hasSize(1);

        List<ContactView> cleared = service.replaceDraftContacts("CT_CLEAR", List.of(), null);

        assertThat(cleared).isEmpty();
        assertThat(contactRepository.findCurrentByPartnerId(partnerId)).isEmpty();
        assertThat(service.currentContacts("CT_CLEAR")).isEmpty();
    }

    @Test
    void validation_rejectsBadPayloadsWith400_withoutWritingRows() {
        Long partnerId = seedPartner("CT_INVALID");

        record Bad(String label, ContactCommand cmd) {}
        List<Bad> bads = List.of(
                new Bad("unknown role", contact("CFO", "Kim", "kim@x.example", null, false)),
                new Bad("missing role", contact(null, "Kim", "kim@x.example", null, false)),
                new Bad("missing name", contact("TECH", "  ", "kim@x.example", null, false)),
                new Bad("bad email", contact("TECH", "Kim", "not-an-email", null, false)),
                new Bad("phone without +", contact("TECH", "Kim", "kim@x.example", "8210123", false)),
                new Bad("phone leading zero", contact("TECH", "Kim", "kim@x.example", "+0211234", false)),
                new Bad("phone too long", contact("TECH", "Kim", "kim@x.example", "+1234567890123456", false)),
                new Bad("notes too long", new ContactCommand("TECH", "Kim", "kim@x.example",
                        null, false, "x".repeat(501))));

        for (Bad bad : bads) {
            assertThatThrownBy(() -> service.replaceDraftContacts(
                    "CT_INVALID", List.of(bad.cmd()), "maker_kim"))
                    .as(bad.label())
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }

        // A 400 must be side-effect free: no contact rows landed.
        assertThat(contactRepository.findCurrentByPartnerId(partnerId)).isEmpty();
    }

    @Test
    void validation_badElementIndexIsCarriedInTheMessage() {
        seedPartner("CT_INDEX");
        assertThatThrownBy(() -> service.replaceDraftContacts("CT_INDEX", List.of(
                contact("TECH", "Ok Person", "ok@x.example", null, false),
                contact("TECH", "Bad Phone", "ok2@x.example", "12345", false)),
                "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("contacts[1].phoneE164");
                });
    }

    @Test
    void audit_publishesOneEventPerReplace_withBeforeAfterSnapshots() {
        seedPartner("CT_AUDIT");
        publisher.clear(); // drop the PARTNER_SAVED event from the seed

        service.replaceDraftContacts("CT_AUDIT", List.of(
                contact("FINANCE", "Fin One", "fin1@partner.example", null, true)), "maker_kim");
        service.replaceDraftContacts("CT_AUDIT", List.of(
                contact("FINANCE", "Fin Two", "fin2@partner.example", "+6512345678", true)), "checker_lee");

        List<AuditEvent> events = publisher.published();
        assertThat(events)
                .as("one audit event per bulk replace")
                .hasSize(2);

        AuditEvent firstWrite = events.get(0);
        assertThat(firstWrite.aggregateType()).isEqualTo("partner_contact");
        assertThat(firstWrite.aggregateId()).isEqualTo("CT_AUDIT");
        assertThat(firstWrite.eventType()).isEqualTo("PARTNER_CONTACTS_REPLACED");
        assertThat(firstWrite.actorId()).isEqualTo("maker_kim");
        assertThat(firstWrite.beforeJsonb())
                .as("first replace has no prior set — BEFORE must be null").isNull();
        assertThat(new String(firstWrite.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"name\":\"Fin One\"")
                .contains("\"authorizedSignatory\":true");

        AuditEvent secondWrite = events.get(1);
        assertThat(secondWrite.actorId()).isEqualTo("checker_lee");
        assertThat(new String(secondWrite.beforeJsonb(), StandardCharsets.UTF_8))
                .as("BEFORE carries the superseded set")
                .contains("\"name\":\"Fin One\"");
        assertThat(new String(secondWrite.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"name\":\"Fin Two\"")
                .contains("\"phoneE164\":\"+6512345678\"");
    }

    @Test
    void unknownPartner_404_onBothReadAndWrite() {
        assertThatThrownBy(() -> service.replaceDraftContacts("CT_GHOST", List.of(), "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.currentContacts("CT_GHOST"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void nullContactsList_isRejectedWith400() {
        seedPartner("CT_NULL");
        assertThatThrownBy(() -> service.replaceDraftContacts("CT_NULL", null, "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void nonOnboardingPartner_409_draftsAreImmutableAfterActivation() {
        seedPartner("CT_LIVE");
        PartnerEntity current = partnerRepository.findCurrentByPartnerCode("CT_LIVE").orElseThrow();
        current.setStatus(PartnerStatus.LIVE);
        partnerRepository.saveAndFlush(current);

        assertThatThrownBy(() -> service.replaceDraftContacts("CT_LIVE", List.of(
                contact("TECH", "Kim", "kim@x.example", null, false)), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        // Reads still work on a non-ONBOARDING partner.
        assertThat(service.currentContacts("CT_LIVE")).isEmpty();
    }

    @Test
    void currentContacts_returnsOnlyTheCurrentSet_inInsertionOrder() {
        seedPartner("CT_READ");
        service.replaceDraftContacts("CT_READ", List.of(
                contact("INCIDENT", "Sec Han", "sec@partner.example", "+85512345678", false),
                contact("OPS_24X7", "Ops Desk", "ops@partner.example", null, false)), null);

        List<ContactView> views = service.currentContacts("CT_READ");

        assertThat(views).hasSize(2);
        assertThat(views).extracting(ContactView::role)
                .containsExactly("INCIDENT", "OPS_24X7");
        assertThat(views.get(0).phoneE164()).isEqualTo("+85512345678");
        assertThat(views.get(0).validFrom()).isEqualTo(views.get(0).recordedAt());
        assertThat(views.get(0).validTo()).isNull();
    }
}
