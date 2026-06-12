package com.gme.pay.registry.commercial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.FxConfigCommand;
import com.gme.pay.contracts.FxConfigView;
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
 * Slice 6 acceptance test for {@link FxConfigService} — the
 * {@code partner_fx_config} upsert (V019), wired end-to-end against H2 in
 * PostgreSQL mode with the full Flyway chain applied. Same slice pattern as
 * {@code PrefundingConfigServiceTest}.
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>Upsert applies the V019 defaults (margin 0, hold 300) and a second
 *       upsert supersedes the first — paired SCD-6 writes sharing one
 *       MICROS-truncated instant, business time continuous.</li>
 *   <li>Validation rejects missing/unknown rate source, negative or
 *       over-envelope margin, hold outside 60..1800 — 400, side-effect
 *       free.</li>
 *   <li>One {@code partner_fx_config} audit event per write with canonical
 *       BEFORE/AFTER snapshots (bps as plain-decimal strings).</li>
 *   <li>Unknown partner → 404 (write + read); no config yet → 404 on read;
 *       non-ONBOARDING → 409 on write.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FxConfigServiceTest.TestConfig.class, FxConfigService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class FxConfigServiceTest {

    @Autowired
    private FxConfigService service;

    @Autowired
    private FxConfigRepository repository;

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

    // -------------------------------------------------------------------- tests

    @Test
    void upsert_appliesV019Defaults_andSecondUpsertSupersedes() {
        Long partnerId = seedPartner("FX_UPSERT");

        FxConfigView first = service.upsertFxConfig("FX_UPSERT",
                new FxConfigCommand(null, "SEOUL_FX_BROKER", null), "maker_kim");

        assertThat(first.id()).isNotNull();
        assertThat(first.marginBps()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(first.marginBps().scale())
                .as("bps normalized to NUMERIC(7,4) scale").isEqualTo(4);
        assertThat(first.referenceRateSource()).isEqualTo("SEOUL_FX_BROKER");
        assertThat(first.quoteHoldSeconds()).isEqualTo(300);

        FxConfigView second = service.upsertFxConfig("FX_UPSERT",
                new FxConfigCommand(new BigDecimal("85.5"), "MID_MARKET", 600), "maker_kim");

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.marginBps()).isEqualByComparingTo(new BigDecimal("85.5"));
        assertThat(second.referenceRateSource()).isEqualTo("MID_MARKET");
        assertThat(second.quoteHoldSeconds()).isEqualTo(600);

        // SCD-6: 2 rows, prior superseded at EXACTLY the fresh recorded_at,
        // MICROS-truncated, business time continuous.
        List<FxConfigEntity> all = repository.findAll().stream()
                .filter(e -> partnerId.equals(e.getPartnerId())).toList();
        assertThat(all).hasSize(2);
        FxConfigEntity prior = all.stream()
                .filter(e -> e.getSupersededAt() != null).findFirst().orElseThrow();
        FxConfigEntity current = all.stream()
                .filter(e -> e.getSupersededAt() == null).findFirst().orElseThrow();
        assertThat(prior.getSupersededAt()).isEqualTo(current.getRecordedAt());
        assertThat(current.getRecordedAt().getNano() % 1000).isZero();
        assertThat(current.getValidFrom()).isEqualTo(prior.getValidFrom());

        assertThat(service.currentFxConfig("FX_UPSERT").id()).isEqualTo(second.id());
    }

    @Test
    void quoteHoldBounds_60and1800Accepted() {
        seedPartner("FX_BOUNDS");
        assertThat(service.upsertFxConfig("FX_BOUNDS",
                new FxConfigCommand(null, "PARTNER_PROVIDED", 60), "x").quoteHoldSeconds())
                .isEqualTo(60);
        assertThat(service.upsertFxConfig("FX_BOUNDS",
                new FxConfigCommand(null, "PARTNER_PROVIDED", 1800), "x").quoteHoldSeconds())
                .isEqualTo(1800);
    }

    @Test
    void validation_rejectsBadPayloadsWith400_withoutWritingRows() {
        Long partnerId = seedPartner("FX_INVALID");

        record Bad(String label, FxConfigCommand cmd) {}
        List<Bad> bads = List.of(
                new Bad("missing source", new FxConfigCommand(null, null, null)),
                new Bad("blank source", new FxConfigCommand(null, "  ", null)),
                new Bad("unknown source", new FxConfigCommand(null, "BLOOMBERG", null)),
                new Bad("negative margin", new FxConfigCommand(
                        new BigDecimal("-1"), "MID_MARKET", null)),
                new Bad("margin over 4dp", new FxConfigCommand(
                        new BigDecimal("1.12345"), "MID_MARKET", null)),
                new Bad("margin over 3 integer digits", new FxConfigCommand(
                        new BigDecimal("1000"), "MID_MARKET", null)),
                new Bad("hold below 60", new FxConfigCommand(null, "MID_MARKET", 59)),
                new Bad("hold above 1800", new FxConfigCommand(null, "MID_MARKET", 1801)));

        for (Bad bad : bads) {
            assertThatThrownBy(() -> service.upsertFxConfig("FX_INVALID", bad.cmd(), "x"))
                    .as(bad.label())
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        assertThat(repository.findCurrentByPartnerId(partnerId)).isEmpty();
    }

    @Test
    void nullBody_isRejectedWith400() {
        seedPartner("FX_NULL");
        assertThatThrownBy(() -> service.upsertFxConfig("FX_NULL", null, "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void unknownPartner_404_andNoConfigYet_404() {
        assertThatThrownBy(() -> service.upsertFxConfig("FX_GHOST",
                new FxConfigCommand(null, "MID_MARKET", null), "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        seedPartner("FX_EMPTY");
        assertThatThrownBy(() -> service.currentFxConfig("FX_EMPTY"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getReason()).contains("fx config");
                });
    }

    @Test
    void nonOnboardingPartner_409() {
        seedPartner("FX_LIVE");
        service.upsertFxConfig("FX_LIVE",
                new FxConfigCommand(null, "MID_MARKET", null), "maker_kim");

        PartnerEntity current = partnerRepository.findCurrentByPartnerCode("FX_LIVE")
                .orElseThrow();
        current.setStatus(PartnerStatus.LIVE);
        partnerRepository.saveAndFlush(current);

        assertThatThrownBy(() -> service.upsertFxConfig("FX_LIVE",
                new FxConfigCommand(null, "MID_MARKET", null), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(service.currentFxConfig("FX_LIVE").referenceRateSource())
                .isEqualTo("MID_MARKET");
    }

    @Test
    void audit_oneEventPerWrite_withCanonicalBeforeAfterSnapshots() {
        seedPartner("FX_AUDIT");
        publisher.clear();

        service.upsertFxConfig("FX_AUDIT",
                new FxConfigCommand(new BigDecimal("120"), "SEOUL_FX_BROKER", 900), "maker_kim");
        service.upsertFxConfig("FX_AUDIT",
                new FxConfigCommand(null, "PARTNER_PROVIDED", null), "checker_lee");

        List<AuditEvent> events = publisher.published();
        assertThat(events).hasSize(2);

        AuditEvent first = events.get(0);
        assertThat(first.aggregateType()).isEqualTo("partner_fx_config");
        assertThat(first.eventType()).isEqualTo("PARTNER_FX_CONFIG_SAVED");
        assertThat(first.actorId()).isEqualTo("maker_kim");
        assertThat(first.beforeJsonb()).isNull();
        assertThat(new String(first.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"marginBps\":\"120.0000\"")
                .contains("\"referenceRateSource\":\"SEOUL_FX_BROKER\"")
                .contains("\"quoteHoldSeconds\":900");

        AuditEvent second = events.get(1);
        assertThat(second.actorId()).isEqualTo("checker_lee");
        assertThat(new String(second.beforeJsonb(), StandardCharsets.UTF_8))
                .contains("\"referenceRateSource\":\"SEOUL_FX_BROKER\"");
        assertThat(new String(second.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"referenceRateSource\":\"PARTNER_PROVIDED\"")
                .contains("\"marginBps\":\"0.0000\"")
                .contains("\"quoteHoldSeconds\":300");
    }
}
