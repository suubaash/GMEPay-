package com.gme.pay.registry.corridor;

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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Slice 7 repository slice for {@link PartnerCorridorRepository} /
 * {@link PartnerCorridorEntity} (V023) against H2 in PostgreSQL mode with the
 * full Flyway chain applied — the {@code RuleServiceTest} pattern.
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>The V023 partial-unique emulation: the stored GENERATED
 *       {@code is_current} column + composite UNIQUE index allows exactly one
 *       CURRENT row per (partner, src_country, src_ccy, dst_country, dst_ccy)
 *       and any number of historical rows for the same lane.</li>
 *   <li>The SCD-6 close-and-reinsert cycle: superseding a row (UPDATE of
 *       {@code superseded_at}) vacates the unique slot so a fresh row for the
 *       same lane can land in the same transaction.</li>
 *   <li>The {@code @PrePersist} defaults: MICROS-truncated {@code recorded_at},
 *       {@code valid_from} mirroring it, {@code is_active} defaulting TRUE.</li>
 *   <li>Both finders filter on {@code superseded_at IS NULL}.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PartnerCorridorRepositoryTest.TestConfig.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class PartnerCorridorRepositoryTest {

    @Autowired
    private PartnerCorridorRepository repository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private PartnerStore partnerStore;

    /** Same publisher swap as {@code RuleServiceTest} (PartnerStore needs the audit beans). */
    @TestConfiguration
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

    /** Create a partner draft through the canonical store path; returns its surrogate id. */
    private Long seedPartner(String code) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
        return partnerRepository.findCurrentByPartnerCode(code).orElseThrow().getId();
    }

    private static PartnerCorridorEntity corridor(Long partnerId,
                                                  String srcCountry, String srcCcy,
                                                  String dstCountry, String dstCcy) {
        PartnerCorridorEntity e = new PartnerCorridorEntity();
        e.setPartnerId(partnerId);
        e.setSrcCountry(srcCountry);
        e.setSrcCcy(srcCcy);
        e.setDstCountry(dstCountry);
        e.setDstCcy(dstCcy);
        return e;
    }

    @Test
    void prePersistDefaults_microsRecordedAt_validFromMirrors_isActiveTrue() {
        Long partnerId = seedPartner("CORR_REPO_DEFAULTS");

        PartnerCorridorEntity saved = repository.saveAndFlush(
                corridor(partnerId, "KR", "KRW", "MN", "MNT"));

        assertThat(saved.getId()).as("IDENTITY id assigned at flush").isNotNull();
        assertThat(saved.getRecordedAt()).isNotNull();
        // MICROS truncation: no sub-microsecond digits survive to storage.
        assertThat(saved.getRecordedAt().getNano() % 1000).isZero();
        assertThat(saved.getValidFrom()).isEqualTo(saved.getRecordedAt());
        assertThat(saved.getValidTo()).isNull();
        assertThat(saved.getSupersededAt()).isNull();
        // V023 DEFAULT TRUE mirrored by the entity hook.
        assertThat(saved.getIsActive()).isTrue();
    }

    @Test
    void currentRowUniqueness_secondCurrentRowForSameLaneIsRejected() {
        Long partnerId = seedPartner("CORR_REPO_UNIQUE");
        repository.saveAndFlush(corridor(partnerId, "KR", "KRW", "MN", "MNT"));

        // Same lane, still current → the composite UNIQUE over
        // (partner, lane, is_current=TRUE) must collide.
        assertThatThrownBy(() -> repository.saveAndFlush(
                corridor(partnerId, "KR", "KRW", "MN", "MNT")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void differentLaneOrDifferentPartner_doesNotCollide() {
        Long partnerId = seedPartner("CORR_REPO_LANES");
        Long otherPartnerId = seedPartner("CORR_REPO_LANES2");

        repository.saveAndFlush(corridor(partnerId, "KR", "KRW", "MN", "MNT"));
        // Any single differing key column is a different lane.
        repository.saveAndFlush(corridor(partnerId, "KR", "KRW", "MN", "USD"));
        repository.saveAndFlush(corridor(partnerId, "KR", "KRW", "VN", "VND"));
        // The same lane on ANOTHER partner is fine too.
        repository.saveAndFlush(corridor(otherPartnerId, "KR", "KRW", "MN", "MNT"));

        assertThat(repository.findAllCurrentByPartnerId(partnerId)).hasSize(3);
        assertThat(repository.findAllCurrentByPartnerId(otherPartnerId)).hasSize(1);
    }

    @Test
    void bitemporalCloseAndReinsert_supersededRowVacatesTheUniqueSlot() {
        Long partnerId = seedPartner("CORR_REPO_SCD6");

        PartnerCorridorEntity v1 = repository.saveAndFlush(
                corridor(partnerId, "KR", "KRW", "KH", "KHR"));

        // Close: the only sanctioned UPDATE under SCD-6. The DB-generated
        // is_current recomputes to NULL, vacating the unique slot.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        v1.setSupersededAt(now);
        repository.saveAndFlush(v1);

        // Reinsert the same lane (e.g. toggled inactive) — must NOT collide
        // with the historical row.
        PartnerCorridorEntity v2 = corridor(partnerId, "KR", "KRW", "KH", "KHR");
        v2.setIsActive(false);
        v2.setRecordedAt(now);
        PartnerCorridorEntity saved = repository.saveAndFlush(v2);

        // And the cycle repeats: a SECOND close-and-reinsert leaves two
        // historical rows for the same lane coexisting (NULL is_current never
        // collides under UNIQUE on either engine).
        saved.setSupersededAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        repository.saveAndFlush(saved);
        repository.saveAndFlush(corridor(partnerId, "KR", "KRW", "KH", "KHR"));

        assertThat(repository.findAll().stream()
                .filter(e -> partnerId.equals(e.getPartnerId())).count())
                .as("SCD-6 never deletes — 2 historical + 1 current")
                .isEqualTo(3);
        assertThat(repository.findAllCurrentByPartnerId(partnerId))
                .hasSize(1)
                .first()
                .satisfies(c -> {
                    assertThat(c.getSupersededAt()).isNull();
                    assertThat(c.getIsActive()).isTrue();
                });
    }

    @Test
    void findCurrentByPartnerIdAndCorridor_matchesExactLaneAndIgnoresSuperseded() {
        Long partnerId = seedPartner("CORR_REPO_FIND");

        PartnerCorridorEntity active = repository.saveAndFlush(
                corridor(partnerId, "KR", "KRW", "MN", "MNT"));
        active.setGoLiveDate(LocalDate.of(2026, 7, 1));
        repository.saveAndFlush(active);
        repository.saveAndFlush(corridor(partnerId, "KR", "KRW", "VN", "VND"));

        assertThat(repository.findCurrentByPartnerIdAndCorridor(
                partnerId, "KR", "KRW", "MN", "MNT"))
                .isPresent()
                .get()
                .satisfies(c -> assertThat(c.getGoLiveDate())
                        .isEqualTo(LocalDate.of(2026, 7, 1)));

        // Unknown lane → empty.
        assertThat(repository.findCurrentByPartnerIdAndCorridor(
                partnerId, "KR", "KRW", "KH", "KHR")).isEmpty();

        // Superseded lane → empty (the finder is current-rows-only).
        active.setSupersededAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        repository.saveAndFlush(active);
        assertThat(repository.findCurrentByPartnerIdAndCorridor(
                partnerId, "KR", "KRW", "MN", "MNT")).isEmpty();
    }
}
