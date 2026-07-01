package com.gme.pay.registry.commercial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.LimitsCommand;
import com.gme.pay.contracts.LimitsView;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.BigDecimal;
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
 * Slice 6 acceptance test for {@link LimitsService} — the
 * {@code partner_limits} upsert (V020), wired end-to-end against H2 in
 * PostgreSQL mode with the full Flyway chain applied. Same slice pattern as
 * {@code PrefundingConfigServiceTest}.
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>Upsert persists the caps scale-4 normalized; a second upsert
 *       supersedes the first — paired SCD-6 writes sharing one
 *       MICROS-truncated instant.</li>
 *   <li>The 소액해외송금업 ({@code SOAEK_HAEOEMONG}) statutory caps are
 *       SERVER-enforced: missing or over-statute {@code perTxnMaxUsd} /
 *       {@code annualCapUsd} reject with 400 naming the licence; the exact
 *       statutory values (5,000 / 50,000) are accepted.</li>
 *   <li>Cross-field ordering (min &le; max, daily &le; monthly &le; annual)
 *       rejects with 400, side-effect free.</li>
 *   <li>One {@code partner_limits} audit event per write with canonical
 *       BEFORE/AFTER snapshots; 404/409 gates as every other step
 *       service.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({LimitsServiceTest.TestConfig.class, LimitsService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class,
        com.gme.pay.registry.prefunding.push.CreditLimitPusher.class,
        com.gme.pay.registry.prefunding.push.NoOpPrefundingCreditLimitClient.class})
class LimitsServiceTest {

    @Autowired
    private LimitsService service;

    @Autowired
    private LimitsRepository repository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private RecordingAuditPublisher publisher;

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

    private static LimitsCommand caps(String min, String max, String daily,
                                      String monthly, String annual, String license) {
        return new LimitsCommand(
                min == null ? null : new BigDecimal(min),
                max == null ? null : new BigDecimal(max),
                daily == null ? null : new BigDecimal(daily),
                monthly == null ? null : new BigDecimal(monthly),
                annual == null ? null : new BigDecimal(annual),
                license);
    }

    // -------------------------------------------------------------------- tests

    @Test
    void upsert_persistsScale4_andSecondUpsertSupersedes() {
        Long partnerId = seedPartner("LIM_UPSERT");

        LimitsView first = service.upsertLimits("LIM_UPSERT",
                caps("10", "1000", "5000", "100000", "1000000", null), "maker_kim");

        assertThat(first.perTxnMinUsd()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(first.perTxnMinUsd().scale())
                .as("money normalized to NUMERIC(19,4) scale").isEqualTo(4);
        assertThat(first.licenseType()).isNull();

        LimitsView second = service.upsertLimits("LIM_UPSERT",
                caps(null, "4999.99", null, null, "49999", "SOAEK_HAEOEMONG"), "maker_kim");

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.perTxnMaxUsd()).isEqualByComparingTo(new BigDecimal("4999.99"));
        assertThat(second.licenseType()).isEqualTo("SOAEK_HAEOEMONG");

        List<LimitsEntity> all = repository.findAll().stream()
                .filter(e -> partnerId.equals(e.getPartnerId())).toList();
        assertThat(all).hasSize(2);
        LimitsEntity prior = all.stream()
                .filter(e -> e.getSupersededAt() != null).findFirst().orElseThrow();
        LimitsEntity current = all.stream()
                .filter(e -> e.getSupersededAt() == null).findFirst().orElseThrow();
        assertThat(prior.getSupersededAt()).isEqualTo(current.getRecordedAt());
        assertThat(current.getRecordedAt().getNano() % 1000).isZero();
        assertThat(current.getValidFrom()).isEqualTo(prior.getValidFrom());

        assertThat(service.currentLimits("LIM_UPSERT").id()).isEqualTo(second.id());
    }

    @Test
    void soaekLicense_statutoryBoundaryValuesAccepted() {
        seedPartner("LIM_SOAEK_OK");
        // Exactly 5,000 / 50,000 are the statute itself — must pass.
        LimitsView view = service.upsertLimits("LIM_SOAEK_OK",
                caps(null, "5000", null, null, "50000", "SOAEK_HAEOEMONG"), "maker_kim");
        assertThat(view.perTxnMaxUsd()).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(view.annualCapUsd()).isEqualByComparingTo(new BigDecimal("50000"));
    }

    @Test
    void soaekLicense_overStatuteOrMissingCaps_rejectedWith400() {
        Long partnerId = seedPartner("LIM_SOAEK_BAD");

        // perTxnMax over the 5,000 statute (the Slice 6 exit-gate USD 5,001 case).
        assertThatThrownBy(() -> service.upsertLimits("LIM_SOAEK_BAD",
                caps(null, "5001", null, null, "50000", "SOAEK_HAEOEMONG"), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("5000")
                            .contains("SOAEK_HAEOEMONG").contains("소액해외송금업");
                });

        // annualCap over the 50,000 statute.
        assertThatThrownBy(() -> service.upsertLimits("LIM_SOAEK_BAD",
                caps(null, "5000", null, null, "50000.01", "SOAEK_HAEOEMONG"), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("50000");
                });

        // The licence REQUIRES both caps to be present (the DB CHECK alone
        // would pass NULL by three-valued logic — the service must not).
        assertThatThrownBy(() -> service.upsertLimits("LIM_SOAEK_BAD",
                caps(null, null, null, null, "50000", "SOAEK_HAEOEMONG"), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("perTxnMaxUsd is required");
                });
        assertThatThrownBy(() -> service.upsertLimits("LIM_SOAEK_BAD",
                caps(null, "5000", null, null, null, "SOAEK_HAEOEMONG"), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("annualCapUsd is required");
                });

        // 400s are side-effect free.
        assertThat(repository.findCurrentByPartnerId(partnerId)).isEmpty();
    }

    @Test
    void validation_rejectsOrderingViolationsAndBadMoney() {
        Long partnerId = seedPartner("LIM_INVALID");

        record Bad(String label, LimitsCommand cmd) {}
        List<Bad> bads = List.of(
                new Bad("min over max", caps("100", "50", null, null, null, null)),
                new Bad("daily over monthly", caps(null, null, "1000", "500", null, null)),
                new Bad("monthly over annual", caps(null, null, null, "1000", "500", null)),
                new Bad("daily over annual", caps(null, null, "1000", null, "500", null)),
                new Bad("negative cap", caps(null, null, "-1", null, null, null)),
                new Bad("cap over 4dp", caps(null, null, "1.12345", null, null, null)),
                new Bad("license over 30 chars", caps(null, null, null, null, null,
                        "X".repeat(31))));

        for (Bad bad : bads) {
            assertThatThrownBy(() -> service.upsertLimits("LIM_INVALID", bad.cmd(), "x"))
                    .as(bad.label())
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        assertThat(repository.findCurrentByPartnerId(partnerId)).isEmpty();
    }

    @Test
    void nullBody_isRejectedWith400() {
        seedPartner("LIM_NULL");
        assertThatThrownBy(() -> service.upsertLimits("LIM_NULL", null, "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void unknownPartner_404_andNoLimitsYet_404() {
        assertThatThrownBy(() -> service.upsertLimits("LIM_GHOST",
                caps(null, null, null, null, null, null), "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        seedPartner("LIM_EMPTY");
        assertThatThrownBy(() -> service.currentLimits("LIM_EMPTY"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getReason()).contains("limits");
                });
    }

    @Test
    void nonOnboardingPartner_409() {
        seedPartner("LIM_LIVE");
        service.upsertLimits("LIM_LIVE", caps(null, "1000", null, null, null, null), "x");

        PartnerEntity current = partnerRepository.findCurrentByPartnerCode("LIM_LIVE")
                .orElseThrow();
        current.setStatus(PartnerStatus.LIVE);
        partnerRepository.saveAndFlush(current);

        assertThatThrownBy(() -> service.upsertLimits("LIM_LIVE",
                caps(null, "2000", null, null, null, null), "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(service.currentLimits("LIM_LIVE").perTxnMaxUsd())
                .isEqualByComparingTo(new BigDecimal("1000"));
    }

    @Test
    void audit_oneEventPerWrite_withCanonicalBeforeAfterSnapshots() {
        seedPartner("LIM_AUDIT");
        publisher.clear();

        service.upsertLimits("LIM_AUDIT",
                caps("1", "5000", null, null, "50000", "SOAEK_HAEOEMONG"), "maker_kim");
        service.upsertLimits("LIM_AUDIT",
                caps(null, "9000", null, null, null, null), "checker_lee");

        List<AuditEvent> events = publisher.published();
        assertThat(events).hasSize(2);

        AuditEvent first = events.get(0);
        assertThat(first.aggregateType()).isEqualTo("partner_limits");
        assertThat(first.eventType()).isEqualTo("PARTNER_LIMITS_SAVED");
        assertThat(first.beforeJsonb()).isNull();
        assertThat(new String(first.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"perTxnMinUsd\":\"1.0000\"")
                .contains("\"perTxnMaxUsd\":\"5000.0000\"")
                .contains("\"dailyCapUsd\":null")
                .contains("\"annualCapUsd\":\"50000.0000\"")
                .contains("\"licenseType\":\"SOAEK_HAEOEMONG\"");

        AuditEvent second = events.get(1);
        assertThat(new String(second.beforeJsonb(), StandardCharsets.UTF_8))
                .contains("\"licenseType\":\"SOAEK_HAEOEMONG\"");
        assertThat(new String(second.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"perTxnMaxUsd\":\"9000.0000\"")
                .contains("\"licenseType\":null");
    }
}
