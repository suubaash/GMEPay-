package com.gme.pay.registry.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.SettlementConfigView;
import com.gme.pay.contracts.SettlementPreview;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
 * Slice 4 acceptance test for {@link SettlementConfigService} — the
 * {@code partner_settlement_config} upsert (V013) and the settlement preview
 * over the seeded {@code business_day_calendar} (V014), wired end-to-end
 * against H2 in PostgreSQL mode with the full Flyway chain applied. Mirrors
 * the {@code KybServiceTest} slice-test pattern: {@code @DataJpaTest} +
 * explicit {@code @Import} of the service/audit beans and a
 * {@link RecordingAuditPublisher} to observe ADR-007 publication.
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>Step-4 upsert inserts a CURRENT row (V013 defaults applied to null
 *       command fields); a second upsert supersedes the first — paired SCD-6
 *       writes sharing one MICROS-truncated instant.</li>
 *   <li>Server-side validation rejects bad method / cycle range / timezone
 *       with 400, without touching any row.</li>
 *   <li>One {@code partner_settlement_config} audit event per write with
 *       BEFORE/AFTER canonical snapshots.</li>
 *   <li>Unknown partner → 404; partner outside ONBOARDING → 409 (the
 *       2-signatory post-activation flow is Slice 8).</li>
 *   <li>The preview projects through the REAL V014 seed: Chuseok 2026 roll
 *       and the KR+KH cross-country union.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SettlementConfigServiceTest.TestConfig.class, SettlementConfigService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class SettlementConfigServiceTest {

    @Autowired
    private SettlementConfigService service;

    @Autowired
    private SettlementConfigRepository configRepository;

    @Autowired
    private BusinessDayCalendarRepository calendarRepository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private RecordingAuditPublisher publisher;

    /** Same publisher swap as {@code AuditLogTest} / {@code KybServiceTest}. */
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

    private static PartnerCommand.UpdateStep4Settlement cmd(
            Integer cycle, LocalTime cutoff, String tz, String method) {
        return new PartnerCommand.UpdateStep4Settlement(cycle, cutoff, tz, method);
    }

    // -------------------------------------------------------------------- tests

    @Test
    void upsert_appliesV013Defaults_andSecondUpsertSupersedes() {
        Long partnerId = seedPartner("SETTLE_UPSERT");

        // Nulls everywhere except the mandatory method -> V013 defaults.
        SettlementConfigView first = service.upsertStep4Settlement("SETTLE_UPSERT",
                cmd(null, null, null, "SWIFT_MT103"), "maker_kim");

        assertThat(first.id()).isNotNull();
        assertThat(first.cycleTPlusN()).isEqualTo(1);
        assertThat(first.cutoffTime()).isEqualTo(LocalTime.of(16, 30));
        assertThat(first.cutoffTimezone()).isEqualTo("Asia/Seoul");
        assertThat(first.settlementMethod()).isEqualTo("SWIFT_MT103");

        SettlementConfigView second = service.upsertStep4Settlement("SETTLE_UPSERT",
                cmd(3, LocalTime.of(11, 0), "Asia/Phnom_Penh", "BAKONG"), "maker_kim");

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.cycleTPlusN()).isEqualTo(3);
        assertThat(second.cutoffTime()).isEqualTo(LocalTime.of(11, 0));
        assertThat(second.cutoffTimezone()).isEqualTo("Asia/Phnom_Penh");
        assertThat(second.settlementMethod()).isEqualTo("BAKONG");

        // SCD-6: nothing deleted — 2 rows, prior superseded with an instant
        // EXACTLY equal to the fresh recorded_at (shared paired-write instant),
        // MICROS-truncated, business time continuous.
        List<SettlementConfigEntity> all = configRepository.findAll().stream()
                .filter(e -> partnerId.equals(e.getPartnerId())).toList();
        assertThat(all).hasSize(2);
        SettlementConfigEntity prior = all.stream()
                .filter(e -> e.getSupersededAt() != null).findFirst().orElseThrow();
        SettlementConfigEntity current = all.stream()
                .filter(e -> e.getSupersededAt() == null).findFirst().orElseThrow();
        assertThat(prior.getSupersededAt()).isEqualTo(current.getRecordedAt());
        assertThat(current.getRecordedAt().getNano() % 1000).isZero();
        assertThat(current.getValidFrom()).isEqualTo(prior.getValidFrom());

        // Rehydrate path returns the current row.
        assertThat(service.currentConfig("SETTLE_UPSERT").id()).isEqualTo(second.id());
    }

    @Test
    void validation_rejectsBadPayloadsWith400_withoutWritingRows() {
        Long partnerId = seedPartner("SETTLE_INVALID");

        record Bad(String label, PartnerCommand.UpdateStep4Settlement cmd) {}
        List<Bad> bads = List.of(
                new Bad("missing method", cmd(1, null, null, null)),
                new Bad("blank method", cmd(1, null, null, "  ")),
                new Bad("unknown method", cmd(1, null, null, "CARRIER_PIGEON")),
                new Bad("cycle above 5", cmd(6, null, null, "SWIFT_MT103")),
                new Bad("negative cycle", cmd(-1, null, null, "SWIFT_MT103")),
                new Bad("bogus timezone", cmd(1, null, "Asia/Gotham", "SWIFT_MT103")),
                new Bad("timezone over 40 chars",
                        cmd(1, null, "x".repeat(41), "SWIFT_MT103")));

        for (Bad bad : bads) {
            assertThatThrownBy(() -> service.upsertStep4Settlement(
                    "SETTLE_INVALID", bad.cmd(), "maker_kim"))
                    .as(bad.label())
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }

        // A 400 must be side-effect free: no config row landed.
        assertThat(configRepository.findCurrentByPartnerId(partnerId)).isEmpty();
    }

    @Test
    void nullBody_isRejectedWith400() {
        seedPartner("SETTLE_NULL");
        assertThatThrownBy(() -> service.upsertStep4Settlement("SETTLE_NULL", null, "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void unknownPartner_404_onAllThreeOperations() {
        assertThatThrownBy(() -> service.upsertStep4Settlement(
                "SETTLE_GHOST", cmd(1, null, null, "SWIFT_MT103"), "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.currentConfig("SETTLE_GHOST"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.preview(
                "SETTLE_GHOST", Instant.parse("2026-06-10T01:00:00Z"), null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void partnerWithoutConfig_404_onReadAndPreview() {
        seedPartner("SETTLE_EMPTY");
        assertThatThrownBy(() -> service.currentConfig("SETTLE_EMPTY"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.preview(
                "SETTLE_EMPTY", Instant.parse("2026-06-10T01:00:00Z"), null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getReason()).contains("settlement config");
                });
    }

    @Test
    void nonOnboardingPartner_409_postActivationFlowIsSlice8() {
        seedPartner("SETTLE_LIVE");
        service.upsertStep4Settlement("SETTLE_LIVE",
                cmd(1, null, null, "KR_FIRM_BANKING"), "maker_kim");

        PartnerEntity current = partnerRepository.findCurrentByPartnerCode("SETTLE_LIVE")
                .orElseThrow();
        current.setStatus(PartnerStatus.LIVE);
        partnerRepository.saveAndFlush(current);

        assertThatThrownBy(() -> service.upsertStep4Settlement("SETTLE_LIVE",
                cmd(2, null, null, "SWIFT_MT103"), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        // Reads stay open for LIVE partners (detail page).
        assertThat(service.currentConfig("SETTLE_LIVE").settlementMethod())
                .isEqualTo("KR_FIRM_BANKING");
    }

    @Test
    void audit_oneEventPerWrite_withCanonicalBeforeAfterSnapshots() {
        seedPartner("SETTLE_AUDIT");
        publisher.clear(); // drop the PARTNER_SAVED event from the seed

        service.upsertStep4Settlement("SETTLE_AUDIT",
                cmd(2, LocalTime.of(15, 0), "Asia/Seoul", "SWIFT_MT103"), "maker_kim");
        service.upsertStep4Settlement("SETTLE_AUDIT",
                cmd(1, null, null, "KR_FIRM_BANKING"), "checker_lee");

        List<AuditEvent> events = publisher.published();
        assertThat(events).hasSize(2);

        AuditEvent first = events.get(0);
        assertThat(first.aggregateType()).isEqualTo("partner_settlement_config");
        assertThat(first.aggregateId()).isEqualTo("SETTLE_AUDIT");
        assertThat(first.eventType()).isEqualTo("PARTNER_SETTLEMENT_CONFIG_SAVED");
        assertThat(first.actorId()).isEqualTo("maker_kim");
        assertThat(first.beforeJsonb()).as("first write — BEFORE must be null").isNull();
        assertThat(new String(first.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"cycleTPlusN\":2")
                .contains("\"cutoffTime\":\"15:00\"")
                .contains("\"settlementMethod\":\"SWIFT_MT103\"");

        AuditEvent second = events.get(1);
        assertThat(second.actorId()).isEqualTo("checker_lee");
        assertThat(new String(second.beforeJsonb(), StandardCharsets.UTF_8))
                .as("BEFORE carries the superseded row")
                .contains("\"settlementMethod\":\"SWIFT_MT103\"");
        assertThat(new String(second.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"settlementMethod\":\"KR_FIRM_BANKING\"");
    }

    // ----------------------------------------------------------- preview (V014)

    @Test
    void v014Seed_isPresent_forTheCountriesTheCalculatorNeeds() {
        // Sanity-pin the seed rows these preview tests lean on.
        assertThat(calendarRepository
                .findByCountryInAndHolidayDateBetweenOrderByHolidayDateAscCountryAsc(
                        List.of("KR"), LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 26)))
                .hasSize(3);
        assertThat(calendarRepository
                .findByCountryInAndHolidayDateBetweenOrderByHolidayDateAscCountryAsc(
                        List.of("KH"), LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12)))
                .hasSize(3);
    }

    @Test
    void preview_chuseokRoll_afterCutoffWednesdayT1_paysTuesday() {
        // The plan's exit-gate scenario against the REAL V014 seed: txn Wed
        // 2026-09-23 17:30 KST (after cutoff) -> value date enters the Chuseok
        // block (Sep 24-26) -> rolls over the weekend to Mon Sep 28; T+1 ->
        // Tue Sep 29.
        seedPartner("SETTLE_CHUSEOK");
        service.upsertStep4Settlement("SETTLE_CHUSEOK",
                cmd(1, LocalTime.of(16, 30), "Asia/Seoul", "KR_FIRM_BANKING"), "maker_kim");

        SettlementPreview p = service.preview(
                "SETTLE_CHUSEOK", Instant.parse("2026-09-23T08:30:00Z"), null);

        assertThat(p.payoutDate()).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(p.explanation()).anySatisfy(line ->
                assertThat(line).contains("Chuseok"));
        assertThat(p.explanation()).anySatisfy(line ->
                assertThat(line).contains("AFTER the 16:30 cutoff"));
    }

    @Test
    void preview_crossCountryUnion_krPlusKh_skipsBothCalendars() {
        // Thu 2026-10-08 10:00 KST, T+1, bank country KH: KR closes Fri Oct 9
        // (Hangul Day), KH closes Oct 10-12 (Pchum Ben) -> payout Tue Oct 13.
        // KR alone would have paid out Mon Oct 12 — the union is the point.
        seedPartner("SETTLE_KH");
        service.upsertStep4Settlement("SETTLE_KH",
                cmd(1, LocalTime.of(16, 30), "Asia/Seoul", "BAKONG"), "maker_kim");

        SettlementPreview krOnly = service.preview(
                "SETTLE_KH", Instant.parse("2026-10-08T01:00:00Z"), null);
        SettlementPreview union = service.preview(
                "SETTLE_KH", Instant.parse("2026-10-08T01:00:00Z"), "KH");

        assertThat(krOnly.payoutDate()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(union.payoutDate()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(union.explanation()).anySatisfy(line ->
                assertThat(line).contains("KR holiday: Hangul Day"));
        assertThat(union.explanation()).anySatisfy(line ->
                assertThat(line).contains("KH holiday: Pchum Ben"));
    }

    @Test
    void preview_fallsBackToIncorporationCountry_whenNoBankCountryGiven() {
        // Partner incorporated in KH: the union applies WITHOUT an explicit
        // bankCountry parameter (until the bank-account aggregate exposes a
        // primary PAYOUT account as the natural default).
        seedPartner("SETTLE_INC_KH");
        PartnerEntity partner = partnerRepository.findCurrentByPartnerCode("SETTLE_INC_KH")
                .orElseThrow();
        partner.setCountryOfIncorporation("KH");
        partnerRepository.saveAndFlush(partner);

        service.upsertStep4Settlement("SETTLE_INC_KH",
                cmd(1, LocalTime.of(16, 30), "Asia/Seoul", "BAKONG"), "maker_kim");

        SettlementPreview p = service.preview(
                "SETTLE_INC_KH", Instant.parse("2026-10-08T01:00:00Z"), null);

        assertThat(p.payoutDate()).isEqualTo(LocalDate.of(2026, 10, 13));
    }

    @Test
    void preview_rejectsBadInputsWith400() {
        seedPartner("SETTLE_BAD_PREVIEW");
        service.upsertStep4Settlement("SETTLE_BAD_PREVIEW",
                cmd(1, null, null, "SWIFT_MT103"), "maker_kim");

        assertThatThrownBy(() -> service.preview("SETTLE_BAD_PREVIEW", null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.preview(
                "SETTLE_BAD_PREVIEW", Instant.parse("2026-06-10T01:00:00Z"), "KOR"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("bankCountry");
                });
    }
}
