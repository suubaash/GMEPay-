package com.gme.pay.registry.kyb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.KybCommand;
import com.gme.pay.contracts.KybView;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.UboView;
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
import java.time.Instant;
import java.time.LocalDate;
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
 * Slice 3 acceptance test for {@link KybService} — the {@code partner_kyb}
 * upsert + screening path (V011) wired end-to-end against H2 in PostgreSQL
 * mode with Flyway V001..V011 applied. Mirrors the {@code ContactServiceTest}
 * slice-test pattern: {@code @DataJpaTest} + explicit {@code @Import} of the
 * service/audit beans, a {@link RecordingAuditPublisher} to observe ADR-007
 * publication, and the in-process {@link StubKybClient} as the screening seam
 * (the same bean local dev runs).
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>Step-3 upsert inserts a CURRENT row; a second upsert supersedes the
 *       first — paired SCD-6 writes sharing one MICROS-truncated instant.</li>
 *   <li>Screening (stub client, deterministic by name) lands the verdict on a
 *       fresh row, carries step-3 fields forward, and a subsequent step-3
 *       save carries the screening verdict forward.</li>
 *   <li>Server-side validation rejects bad risk rating / over-range UBO pct /
 *       over-long fields with 400, without touching any row.</li>
 *   <li>One {@code partner_kyb} audit event per write with BEFORE/AFTER
 *       canonical snapshots.</li>
 *   <li>Unknown partner → 404; partner outside ONBOARDING → 409 for step-3
 *       but screening still allowed (rescreen path).</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({KybServiceTest.TestConfig.class, KybService.class, StubKybClient.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class KybServiceTest {

    @Autowired
    private KybService service;

    @Autowired
    private KybRepository kybRepository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private RecordingAuditPublisher publisher;

    /** Same publisher swap as {@code AuditLogTest} / {@code ContactServiceTest}. */
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

    /** Seed a partner and stamp a romanized legal name (what the stub screens on). */
    private Long seedPartnerWithLegalName(String code, String legalNameRomanized) {
        Long id = seedPartner(code);
        PartnerEntity current = partnerRepository.findCurrentByPartnerCode(code).orElseThrow();
        current.setLegalNameRomanized(legalNameRomanized);
        partnerRepository.saveAndFlush(current);
        return id;
    }

    private static KybCommand.UpdateStep3 step3(String riskRating, List<UboView> ubos) {
        return new KybCommand.UpdateStep3(
                riskRating,
                "rated " + riskRating + " per corridor matrix",
                LocalDate.of(2027, 6, 1),
                "REMITTANCE",
                "RL-2026-0042",
                "Bank of Korea",
                LocalDate.of(2028, 12, 31),
                ubos,
                null);
    }

    private static UboView ubo(String name, String pct, boolean pep) {
        return new UboView(name, new BigDecimal(pct), pep, "KR");
    }

    // -------------------------------------------------------------------- tests

    @Test
    void upsert_insertsCurrentRow_andSecondUpsertSupersedesIt() {
        Long partnerId = seedPartner("KYB_UPSERT");

        KybView first = service.upsertStep3("KYB_UPSERT",
                step3("LOW", List.of(ubo("Hong Gil Dong", "60", false))), "maker_kim");

        assertThat(first.id()).isNotNull();
        assertThat(first.riskRating()).isEqualTo("LOW");
        assertThat(first.licenseNumber()).isEqualTo("RL-2026-0042");
        assertThat(first.uboList()).hasSize(1);
        assertThat(first.uboList().get(0).ownershipPct()).isEqualByComparingTo("60");
        assertThat(first.screeningStatus()).as("no screening yet").isNull();

        KybView second = service.upsertStep3("KYB_UPSERT",
                step3("HIGH", List.of(
                        ubo("Hong Gil Dong", "60", false),
                        ubo("Kim Pep", "40", true))), "maker_kim");

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.riskRating()).isEqualTo("HIGH");
        assertThat(second.uboList()).hasSize(2);
        assertThat(second.uboList().get(1).isPep()).isTrue();

        // SCD-6: nothing deleted — 2 rows total, prior superseded with an
        // instant EXACTLY equal to the fresh recorded_at (shared paired-write
        // instant) and carrying no sub-microsecond part.
        List<KybEntity> all = kybRepository.findAll().stream()
                .filter(e -> partnerId.equals(e.getPartnerId())).toList();
        assertThat(all).hasSize(2);
        KybEntity prior = all.stream().filter(e -> e.getSupersededAt() != null).findFirst().orElseThrow();
        KybEntity current = all.stream().filter(e -> e.getSupersededAt() == null).findFirst().orElseThrow();
        assertThat(prior.getSupersededAt()).isEqualTo(current.getRecordedAt());
        assertThat(current.getRecordedAt().getNano() % 1000).isZero();
        // Business time stays continuous across transaction-time writes.
        assertThat(current.getValidFrom()).isEqualTo(prior.getValidFrom());
    }

    @Test
    void screening_viaStubClient_storesVerdictOnFreshRow_andKeepsStep3Fields() {
        seedPartnerWithLegalName("KYB_SCREEN_HIT", "Sanctioned Holdings PLC");
        service.upsertStep3("KYB_SCREEN_HIT", step3("MEDIUM", List.of()), "maker_kim");

        KybView screened = service.runScreening("KYB_SCREEN_HIT", "checker_lee");

        assertThat(screened.screeningStatus()).isEqualTo("HIT");
        assertThat(screened.screeningProviderRef()).startsWith("stub-");
        assertThat(screened.screenedAt()).isNotNull();
        assertThat(screened.screenedAt().getNano() % 1000).isZero();
        // Step-3 fields survived the screening write.
        assertThat(screened.riskRating()).isEqualTo("MEDIUM");
        assertThat(screened.licenseAuthority()).isEqualTo("Bank of Korea");

        // ... and the verdict survives the next step-3 save (carry-forward).
        KybView resaved = service.upsertStep3("KYB_SCREEN_HIT",
                step3("HIGH", List.of()), "maker_kim");
        assertThat(resaved.screeningStatus()).isEqualTo("HIT");
        assertThat(resaved.screeningProviderRef()).isEqualTo(screened.screeningProviderRef());
        assertThat(resaved.screenedAt()).isEqualTo(screened.screenedAt());
    }

    @Test
    void screening_uboNameTriggersVerdict_andWorksWithoutPriorStep3Row() {
        // UBO-name trigger: entity name clean, UBO contains REVIEW.
        seedPartnerWithLegalName("KYB_SCREEN_UBO", "Clean Corp Ltd");
        service.upsertStep3("KYB_SCREEN_UBO",
                step3("LOW", List.of(ubo("Review Person", "25", false))), "maker_kim");
        assertThat(service.runScreening("KYB_SCREEN_UBO", null).screeningStatus())
                .isEqualTo("NEEDS_REVIEW");

        // No prior KYB row at all: screening creates the first row.
        seedPartnerWithLegalName("KYB_SCREEN_FRESH", "Totally Clean GmbH");
        KybView fresh = service.runScreening("KYB_SCREEN_FRESH", null);
        assertThat(fresh.screeningStatus()).isEqualTo("CLEAR");
        assertThat(fresh.riskRating()).isNull();
        assertThat(service.currentKyb("KYB_SCREEN_FRESH").screeningStatus()).isEqualTo("CLEAR");
    }

    @Test
    void validation_rejectsBadPayloadsWith400_withoutWritingRows() {
        Long partnerId = seedPartner("KYB_INVALID");

        record Bad(String label, KybCommand.UpdateStep3 cmd) {}
        List<Bad> bads = List.of(
                new Bad("unknown risk rating", step3("EXTREME", List.of())),
                new Bad("rationale too long", new KybCommand.UpdateStep3(
                        "LOW", "x".repeat(1001), null, null, null, null, null, null, null)),
                new Bad("licenseType too long", new KybCommand.UpdateStep3(
                        "LOW", null, null, "x".repeat(51), null, null, null, null, null)),
                new Bad("ubo missing name", step3("LOW", List.of(
                        new UboView("  ", BigDecimal.ONE, false, "KR")))),
                new Bad("ubo pct over 100", step3("LOW", List.of(ubo("Kim", "100.01", false)))),
                new Bad("ubo pct negative", step3("LOW", List.of(ubo("Kim", "-1", false)))),
                new Bad("ubo bad country", step3("LOW", List.of(
                        new UboView("Kim", BigDecimal.TEN, false, "KOR")))));

        for (Bad bad : bads) {
            assertThatThrownBy(() -> service.upsertStep3("KYB_INVALID", bad.cmd(), "maker_kim"))
                    .as(bad.label())
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }

        // A 400 must be side-effect free: no KYB row landed.
        assertThat(kybRepository.findCurrentByPartnerId(partnerId)).isEmpty();
    }

    @Test
    void validation_badUboIndexIsCarriedInTheMessage() {
        seedPartner("KYB_INDEX");
        assertThatThrownBy(() -> service.upsertStep3("KYB_INDEX",
                step3("LOW", List.of(ubo("Ok Person", "10", false), ubo("Bad Pct", "150", false))),
                "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("uboList[1].ownershipPct");
                });
    }

    @Test
    void audit_oneEventPerWrite_withCanonicalBeforeAfterSnapshots() {
        seedPartnerWithLegalName("KYB_AUDIT", "Clean Audit Corp");
        publisher.clear(); // drop the PARTNER_SAVED event from the seed

        service.upsertStep3("KYB_AUDIT", step3("LOW", List.of(ubo("Kim", "55.5", true))), "maker_kim");
        service.runScreening("KYB_AUDIT", "checker_lee");

        List<AuditEvent> events = publisher.published();
        assertThat(events).hasSize(2);

        AuditEvent saved = events.get(0);
        assertThat(saved.aggregateType()).isEqualTo("partner_kyb");
        assertThat(saved.aggregateId()).isEqualTo("KYB_AUDIT");
        assertThat(saved.eventType()).isEqualTo("PARTNER_KYB_SAVED");
        assertThat(saved.actorId()).isEqualTo("maker_kim");
        assertThat(saved.beforeJsonb()).as("first write — BEFORE must be null").isNull();
        String savedAfter = new String(saved.afterJsonb(), StandardCharsets.UTF_8);
        assertThat(savedAfter)
                .contains("\"riskRating\":\"LOW\"")
                .contains("\"ownershipPct\":\"55.5\"")
                .contains("\"isPep\":true")
                .contains("\"screeningStatus\":null");

        AuditEvent screened = events.get(1);
        assertThat(screened.eventType()).isEqualTo("PARTNER_KYB_SCREENED");
        assertThat(screened.actorId()).isEqualTo("checker_lee");
        assertThat(new String(screened.beforeJsonb(), StandardCharsets.UTF_8))
                .as("BEFORE carries the superseded (unscreened) row")
                .contains("\"screeningStatus\":null");
        assertThat(new String(screened.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"screeningStatus\":\"CLEAR\"");
    }

    @Test
    void unknownPartner_404_onAllThreeOperations() {
        assertThatThrownBy(() -> service.upsertStep3("KYB_GHOST", step3("LOW", List.of()), "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.runScreening("KYB_GHOST", "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.currentKyb("KYB_GHOST"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void partnerWithoutKybRow_404_onRead() {
        seedPartner("KYB_EMPTY");
        assertThatThrownBy(() -> service.currentKyb("KYB_EMPTY"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void nonOnboardingPartner_409_forStep3_butScreeningStillAllowed() {
        seedPartnerWithLegalName("KYB_LIVE", "Live Clean Corp");
        service.upsertStep3("KYB_LIVE", step3("LOW", List.of()), "maker_kim");

        PartnerEntity current = partnerRepository.findCurrentByPartnerCode("KYB_LIVE").orElseThrow();
        current.setStatus(PartnerStatus.LIVE);
        partnerRepository.saveAndFlush(current);

        assertThatThrownBy(() -> service.upsertStep3("KYB_LIVE", step3("HIGH", List.of()), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        // Daily rescreen path: screening is NOT gated on ONBOARDING.
        assertThat(service.runScreening("KYB_LIVE", "system").screeningStatus()).isEqualTo("CLEAR");
    }

    @Test
    void nullBody_isRejectedWith400() {
        seedPartner("KYB_NULL");
        assertThatThrownBy(() -> service.upsertStep3("KYB_NULL", null, "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void emptyUboList_isStoredAsEmptyArray_nullAsNull() {
        seedPartner("KYB_UBO_NULL");
        KybView withEmpty = service.upsertStep3("KYB_UBO_NULL", step3("LOW", List.of()), null);
        assertThat(withEmpty.uboList()).isEmpty();

        KybView withNull = service.upsertStep3("KYB_UBO_NULL",
                new KybCommand.UpdateStep3("LOW", null, null, null, null, null, null, null, null), null);
        assertThat(withNull.uboList()).as("null = not captured (distinct from declared-empty)").isNull();
    }
}
