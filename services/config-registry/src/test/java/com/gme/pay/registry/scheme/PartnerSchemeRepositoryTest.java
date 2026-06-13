package com.gme.pay.registry.scheme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Slice 7 repository/storage-contract test for {@link PartnerSchemeRepository}
 * over the V022 {@code partner_scheme} table on H2 in PostgreSQL mode (full
 * Flyway chain applied).
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>The SCD-6 close-and-reinsert lifecycle: superseding a current row
 *       (close) and inserting a replacement (reinsert) keeps BOTH rows in the
 *       table, with {@code current_scheme_key} vacated on the closed row and
 *       claimed by the fresh one.</li>
 *   <li>The V022 partial-unique emulation: a SECOND current row for the same
 *       (partner, scheme) collides on the {@code partner_scheme_current}
 *       UNIQUE index and is rejected by the engine.</li>
 *   <li>{@code findAllCurrentByPartnerId} filters superseded rows.</li>
 *   <li>{@code @PrePersist} stamps a MICROS-truncated {@code recorded_at}
 *       (stored == in-memory on both engines) and defaults
 *       {@code enabled = true}.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PartnerSchemeRepositoryTest.TestConfig.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class PartnerSchemeRepositoryTest {

    @Autowired
    private PartnerSchemeRepository repository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private PartnerStore partnerStore;

    /** Same publisher swap as the other @DataJpaTest slices (PartnerStore needs audit beans). */
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

    private Long seedPartner(String code) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
        return partnerRepository.findCurrentByPartnerCode(code).orElseThrow().getId();
    }

    private static PartnerSchemeEntity entity(Long partnerId, String schemeId) {
        PartnerSchemeEntity e = new PartnerSchemeEntity();
        e.setPartnerId(partnerId);
        e.setSchemeId(schemeId);
        e.setDirection("OUTBOUND");
        e.setRole("ACQUIRER");
        return e;
    }

    @Test
    void bitemporalCloseAndReinsert_keepsBothRows_andMovesTheCurrentKey() {
        Long partnerId = seedPartner("SCHREPO_CYCLE");

        // INSERT the first current row (saveAndFlush + returned managed entity —
        // the IDENTITY id is only assigned at flush).
        PartnerSchemeEntity v1 = repository.saveAndFlush(entity(partnerId, "ZEROPAY"));
        assertThat(v1.getId()).isNotNull();
        assertThat(v1.getCurrentSchemeKey()).isEqualTo(partnerId + ":ZEROPAY");

        // CLOSE: the only sanctioned UPDATE under SCD-6 — stamp superseded_at;
        // the @PreUpdate hook must vacate the unique-index slot.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        v1.setSupersededAt(now);
        repository.saveAndFlush(v1);

        // REINSERT: a fresh current row for the same (partner, scheme) claims
        // the vacated slot within the same transaction.
        PartnerSchemeEntity v2 = entity(partnerId, "ZEROPAY");
        v2.setRecordedAt(now);
        v2.setZeropayMerchantId("ZPM-0002");
        v2 = repository.saveAndFlush(v2);

        assertThat(v2.getId()).isNotEqualTo(v1.getId());
        assertThat(v2.getCurrentSchemeKey()).isEqualTo(partnerId + ":ZEROPAY");
        assertThat(repository.findById(v1.getId()).orElseThrow().getCurrentSchemeKey())
                .as("closed row must have vacated the partial-unique slot")
                .isNull();

        // Both versions remain — nothing is ever deleted.
        assertThat(repository.findAll().stream()
                .filter(e -> partnerId.equals(e.getPartnerId())).count()).isEqualTo(2);
        assertThat(repository.findAllCurrentByPartnerId(partnerId))
                .extracting(PartnerSchemeEntity::getZeropayMerchantId)
                .containsExactly("ZPM-0002");
    }

    @Test
    void currentRowUniqueIndex_rejectsSecondCurrentRowForSameKey() {
        Long partnerId = seedPartner("SCHREPO_UNIQUE");
        repository.saveAndFlush(entity(partnerId, "ZEROPAY"));

        // A second CURRENT row for the same (partner, scheme) must collide on
        // the partner_scheme_current UNIQUE index — the storage-level backstop
        // behind the service's payload-level duplicate check.
        assertThatThrownBy(() -> repository.saveAndFlush(entity(partnerId, "ZEROPAY")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void differentSchemes_orHistoricalRows_doNotCollide() {
        Long partnerId = seedPartner("SCHREPO_NOCLASH");

        // Different scheme on the same partner: a different key value — fine.
        repository.saveAndFlush(entity(partnerId, "ZEROPAY"));
        repository.saveAndFlush(entity(partnerId, "BAKONG"));

        // Two HISTORICAL rows of the same key: both carry a NULL key, and both
        // engines treat UNIQUE-index NULLs as distinct — fine.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        for (PartnerSchemeEntity current : repository.findAllCurrentByPartnerId(partnerId)) {
            current.setSupersededAt(now);
            repository.saveAndFlush(current);
        }
        repository.saveAndFlush(entity(partnerId, "ZEROPAY"));

        assertThat(repository.findAll().stream()
                .filter(e -> partnerId.equals(e.getPartnerId())).count()).isEqualTo(3);
        assertThat(repository.findAllCurrentByPartnerId(partnerId))
                .extracting(PartnerSchemeEntity::getSchemeId)
                .containsExactly("ZEROPAY");
    }

    @Test
    void prePersist_truncatesRecordedAtToMicros_andDefaultsEnabled() {
        Long partnerId = seedPartner("SCHREPO_MICROS");

        PartnerSchemeEntity saved = repository.saveAndFlush(entity(partnerId, "KHQR"));

        // MICROS truncation: no sub-microsecond component survives, so the
        // stored TIMESTAMP equals the in-memory Instant on both PG and H2.
        assertThat(saved.getRecordedAt()).isNotNull();
        assertThat(saved.getRecordedAt().getNano() % 1000).isZero();
        assertThat(saved.getValidFrom()).isEqualTo(saved.getRecordedAt());
        // V022 column DEFAULT TRUE mirrored by the entity hook.
        assertThat(saved.getEnabled()).isTrue();
    }
}
