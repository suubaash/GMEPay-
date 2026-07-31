package com.gme.pay.registry.kyb;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.audit.AuditActors;
import com.gme.pay.audit.AuditPublisher;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.audit.AuditIntegrityService;
import com.gme.pay.registry.audit.AuditLogEntity;
import com.gme.pay.registry.audit.AuditLogRepository;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The honest handling of the V042 KYB reclassification (gap T5-1 task 3, warned about by the T1-4
 * report): an in-place correction of {@code partner_kyb.screening_status} must not read as
 * tampering — and must not be papered over either.
 *
 * <p>What is asserted:
 * <ol>
 *   <li>before the explanation is appended, the drift is reported as <b>UNEXPLAINED</b> — the check
 *       is not silenced;</li>
 *   <li>after appending, it is <b>EXPLAINED</b>, and the explaining row is attributed to the named
 *       system principal {@code system:migration-v042}, not to the bare {@code "system"} literal;</li>
 *   <li>the <b>hash chain stays intact</b> throughout, because the fix appends a row rather than
 *       re-sealing history;</li>
 *   <li>appending is idempotent, so a restart does not duplicate the explanation.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({KybReclassificationProvenanceTest.TestConfig.class, AuditLogService.class,
        AuditIntegrityService.class, KybReclassificationProvenance.class, PartnerStore.class,
        CacheConfig.class})
class KybReclassificationProvenanceTest {

    private static final String PARTNER = "KYB_RECLASS_1";

    @Autowired
    private KybReclassificationProvenance provenance;

    @Autowired
    private AuditIntegrityService integrity;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        AuditPublisher fanout() {
            return new RecordingAuditPublisher();
        }
    }

    /**
     * Seed a partner, one audit row whose AFTER snapshot claims {@code CLEAR} (what the log looked
     * like before V042 ran), and a {@code partner_kyb} row in the post-V042 state. This reproduces
     * the exact divergence the T1-4 report predicted.
     */
    @BeforeEach
    void seed() {
        partnerStore.save(
                Partner.of(PARTNER, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP),
                AuditActors.system("test-seeder"));
        Long partnerId = partnerRepository.findCurrentByPartnerCode(PARTNER).orElseThrow().getId();

        // The pre-V042 audit row: the trail says the screening came back CLEAR.
        jdbc.update("DELETE FROM audit_log WHERE aggregate_type = 'partner_kyb'");
        auditLogService().publish(KybService.AGGREGATE_TYPE, PARTNER,
                AuditActors.attested("alice@gme.com"), "10.0.0.1",
                KybService.EVENT_TYPE_SCREENED, null,
                "{\"screeningStatus\":\"CLEAR\"}".getBytes(StandardCharsets.UTF_8));

        // The post-V042 business row.
        jdbc.update("""
                INSERT INTO partner_kyb (partner_id, screening_status, screening_provider_id,
                        screening_authoritative, reclassified_from, reclassification_note)
                VALUES (?, 'NOT_SCREENED_NO_PROVIDER', 'stub', FALSE, 'CLEAR',
                        'V042 (T1-4): reclassified because no screening happened')
                """, partnerId);
    }

    @Autowired
    private AuditLogService auditLogService;

    private AuditLogService auditLogService() {
        return auditLogService;
    }

    @Test
    @DisplayName("before the explanation is appended the drift is reported, not silenced")
    void driftIsReportedBeforeItIsExplained() {
        KybReclassificationProvenance.DriftReport report = provenance.driftReport();

        assertThat(report.reclassifiedPartners()).isEqualTo(1);
        assertThat(report.allExplained())
                .as("silencing the check for these rows would disable the one control that would "
                        + "notice an UNAUTHORISED status change")
                .isFalse();
        assertThat(report.unexplained()).isEqualTo(1);
        assertThat(report.entries()).singleElement().satisfies(e -> {
            assertThat(e.partnerCode()).isEqualTo(PARTNER);
            assertThat(e.previousScreeningStatus()).isEqualTo("CLEAR");
            assertThat(e.currentScreeningStatus()).isEqualTo("NOT_SCREENED_NO_PROVIDER");
            assertThat(e.verdict()).contains("NOT explained");
        });
    }

    @Test
    @DisplayName("appending the reclassification event explains the drift and keeps the chain intact")
    void appendingTheEventExplainsTheDriftWithoutResealingHistory() {
        AuditIntegrityService.ChainResult before =
                integrity.verifyOne(KybService.AGGREGATE_TYPE, PARTNER);
        assertThat(before.intact()).isTrue();
        int rowsBefore = before.rows();

        int appended = provenance.sealReclassifications();
        assertThat(appended).isEqualTo(1);

        KybReclassificationProvenance.DriftReport report = provenance.driftReport();
        assertThat(report.allExplained()).isTrue();
        assertThat(report.unexplained()).isZero();
        assertThat(report.entries()).singleElement()
                .satisfies(e -> assertThat(e.verdict()).contains("system:migration-v042"));

        // The chain is still intact: one row was APPENDED, nothing was re-sealed.
        AuditIntegrityService.ChainResult after =
                integrity.verifyOne(KybService.AGGREGATE_TYPE, PARTNER);
        assertThat(after.intact())
                .as("re-hashing history would make an honest migration indistinguishable from an "
                        + "attacker who rewrote the log")
                .isTrue();
        assertThat(after.rows()).isEqualTo(rowsBefore + 1);

        // The appended row names the migration, and is NOT the bare "system" literal.
        List<AuditLogEntity> chain =
                auditLogRepository.findChainByAggregate(KybService.AGGREGATE_TYPE, PARTNER);
        AuditLogEntity explanation = chain.get(chain.size() - 1);
        assertThat(explanation.getEventType())
                .isEqualTo(KybReclassificationProvenance.EVENT_TYPE_RECLASSIFIED);
        assertThat(explanation.getActorId()).isEqualTo("system:migration-v042");
        assertThat(explanation.getActorId()).isNotEqualTo("system");
        assertThat(AuditActors.isSystem(explanation.getActorId())).isTrue();
        assertThat(AuditActors.isAttributable(explanation.getActorId()))
                .as("a NAMED system principal is attributable — we know exactly what acted")
                .isTrue();

        // BEFORE/AFTER carry the provenance, so the row is self-explaining without the migration
        // file in hand.
        assertThat(new String(explanation.getBeforeJsonb(), StandardCharsets.UTF_8))
                .contains("CLEAR");
        assertThat(new String(explanation.getAfterJsonb(), StandardCharsets.UTF_8))
                .contains("NOT_SCREENED_NO_PROVIDER")
                .contains("V042");
    }

    @Test
    @DisplayName("appending twice is a no-op — a restart does not duplicate the explanation")
    void sealingIsIdempotent() {
        assertThat(provenance.sealReclassifications()).isEqualTo(1);
        assertThat(provenance.sealReclassifications()).isZero();

        long explanations = auditLogRepository
                .findChainByAggregate(KybService.AGGREGATE_TYPE, PARTNER).stream()
                .filter(r -> KybReclassificationProvenance.EVENT_TYPE_RECLASSIFIED
                        .equals(r.getEventType()))
                .count();
        assertThat(explanations).isEqualTo(1);
        assertThat(integrity.verifyOne(KybService.AGGREGATE_TYPE, PARTNER).intact()).isTrue();
    }
}
