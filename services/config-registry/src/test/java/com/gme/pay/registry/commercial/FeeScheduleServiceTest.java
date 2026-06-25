package com.gme.pay.registry.commercial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.FeeScheduleCommand;
import com.gme.pay.contracts.FeeScheduleView;
import com.gme.pay.contracts.FeeTier;
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
 * Slice 6 acceptance test for {@link FeeScheduleService} — the
 * {@code partner_fee_schedule} bulk replace (V018), wired end-to-end against
 * H2 in PostgreSQL mode with the full Flyway chain applied. Mirrors the
 * {@code PrefundingConfigServiceTest} / {@code BankAccountServiceTest} slice
 * pattern: {@code @DataJpaTest} + explicit {@code @Import} of the
 * service/audit beans and a {@link RecordingAuditPublisher} to observe
 * ADR-007 publication.
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>Bulk replace inserts the CURRENT set (V018 defaults applied to null
 *       fees, money normalized to scale 4, tier table stored canonically); a
 *       second replace supersedes the first whole set — paired SCD-6 writes
 *       sharing one MICROS-truncated instant.</li>
 *   <li>Server-side validation rejects bad direction / negative or
 *       over-envelope fees / non-ascending tiers / duplicate
 *       (schemeId, direction) keys with 400, without touching any row.</li>
 *   <li>One {@code partner_fee_schedule} audit event per replace with
 *       BEFORE/AFTER canonical snapshots (money as plain-decimal strings,
 *       tier table embedded).</li>
 *   <li>Unknown partner → 404; partner outside ONBOARDING → 409.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FeeScheduleServiceTest.TestConfig.class, FeeScheduleService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class FeeScheduleServiceTest {

    @Autowired
    private FeeScheduleService service;

    @Autowired
    private FeeScheduleRepository repository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private RecordingAuditPublisher publisher;

    /** Same publisher swap as {@code AuditLogTest} / {@code PrefundingConfigServiceTest}. */
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

    private static FeeScheduleCommand fee(String schemeId, String direction,
                                          String fixed, String bps, List<FeeTier> tiers) {
        return new FeeScheduleCommand(schemeId, direction,
                fixed == null ? null : new BigDecimal(fixed),
                bps == null ? null : new BigDecimal(bps),
                tiers);
    }

    // -------------------------------------------------------------------- tests

    @Test
    void replace_appliesDefaults_andSecondReplaceSupersedesWholeSet() {
        Long partnerId = seedPartner("FEE_UPSERT");

        // Two rows; nulls default to 0 per the V018 column DEFAULTs.
        List<FeeScheduleView> first = service.replaceDraftFeeSchedules("FEE_UPSERT",
                List.of(fee("zeropay_kr", "OUTBOUND", "1.50", "25", null),
                        fee(null, null, null, null, null)),
                "maker_kim");

        assertThat(first).hasSize(2);
        assertThat(first.get(0).schemeId()).isEqualTo("zeropay_kr");
        assertThat(first.get(0).direction()).isEqualTo("OUTBOUND");
        assertThat(first.get(0).fixedFeeUsd()).isEqualByComparingTo(new BigDecimal("1.50"));
        assertThat(first.get(0).fixedFeeUsd().scale())
                .as("money normalized to NUMERIC(19,4) scale").isEqualTo(4);
        assertThat(first.get(0).bpsFee()).isEqualByComparingTo(new BigDecimal("25"));
        assertThat(first.get(0).tiers()).isNull();
        assertThat(first.get(1).schemeId()).as("wildcard default row").isNull();
        assertThat(first.get(1).direction()).isNull();
        assertThat(first.get(1).fixedFeeUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(first.get(1).bpsFee()).isEqualByComparingTo(BigDecimal.ZERO);

        // Replace with ONE row carrying a tier table.
        List<FeeScheduleView> second = service.replaceDraftFeeSchedules("FEE_UPSERT",
                List.of(fee("zeropay_kr", "BOTH", "2", "30",
                        List.of(new FeeTier(new BigDecimal("10000"), new BigDecimal("25")),
                                new FeeTier(new BigDecimal("50000"), new BigDecimal("20"))))),
                "maker_kim");

        assertThat(second).hasSize(1);
        assertThat(second.get(0).direction()).isEqualTo("BOTH");
        assertThat(second.get(0).tiers()).hasSize(2);
        assertThat(second.get(0).tiers().get(0).fromVolumeUsd())
                .isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(second.get(0).tiers().get(1).bpsOverride())
                .isEqualByComparingTo(new BigDecimal("20"));

        // SCD-6: nothing deleted — 3 rows total, the first TWO superseded with
        // an instant EXACTLY equal to the fresh recorded_at, MICROS-truncated.
        List<FeeScheduleEntity> all = repository.findAll().stream()
                .filter(e -> partnerId.equals(e.getPartnerId())).toList();
        assertThat(all).hasSize(3);
        List<FeeScheduleEntity> superseded = all.stream()
                .filter(e -> e.getSupersededAt() != null).toList();
        FeeScheduleEntity current = all.stream()
                .filter(e -> e.getSupersededAt() == null).findFirst().orElseThrow();
        assertThat(superseded).hasSize(2);
        assertThat(superseded).allSatisfy(p ->
                assertThat(p.getSupersededAt()).isEqualTo(current.getRecordedAt()));
        assertThat(current.getRecordedAt().getNano() % 1000).isZero();

        // The stored tier table is the canonical FeeTierTableJson form.
        assertThat(current.getTierTableJson()).isEqualTo(
                "[{\"fromVolumeUsd\":\"10000.0000\",\"bpsOverride\":\"25.0000\"},"
                        + "{\"fromVolumeUsd\":\"50000.0000\",\"bpsOverride\":\"20.0000\"}]");

        // Rehydrate path returns the current set only.
        assertThat(service.currentFeeSchedules("FEE_UPSERT")).hasSize(1);
    }

    @Test
    void emptyList_clearsAllFeeRows() {
        seedPartner("FEE_CLEAR");
        service.replaceDraftFeeSchedules("FEE_CLEAR",
                List.of(fee("zeropay_kr", "OUTBOUND", "1", "10", null)), "maker_kim");

        List<FeeScheduleView> cleared =
                service.replaceDraftFeeSchedules("FEE_CLEAR", List.of(), "maker_kim");

        assertThat(cleared).isEmpty();
        assertThat(service.currentFeeSchedules("FEE_CLEAR")).isEmpty();
    }

    @Test
    void validation_rejectsBadPayloadsWith400_withoutWritingRows() {
        Long partnerId = seedPartner("FEE_INVALID");

        record Bad(String label, List<FeeScheduleCommand> fees) {}
        List<Bad> bads = List.of(
                new Bad("unknown direction",
                        List.of(fee("s", "SIDEWAYS", null, null, null))),
                new Bad("V017-roster-only direction (DOMESTIC not in V018 CHECK)",
                        List.of(fee("s", "DOMESTIC", null, null, null))),
                new Bad("negative fixed fee",
                        List.of(fee("s", "OUTBOUND", "-1", null, null))),
                new Bad("fixed fee over 4dp",
                        List.of(fee("s", "OUTBOUND", "1.12345", null, null))),
                new Bad("negative bps",
                        List.of(fee("s", "OUTBOUND", null, "-5", null))),
                new Bad("bps over NUMERIC(7,4) integer digits",
                        List.of(fee("s", "OUTBOUND", null, "1000", null))),
                new Bad("schemeId over 40 chars",
                        List.of(fee("x".repeat(41), "OUTBOUND", null, null, null))),
                new Bad("duplicate (schemeId, direction) pair",
                        List.of(fee("s", "OUTBOUND", null, null, null),
                                fee("s", "OUTBOUND", "1", "1", null))),
                new Bad("duplicate wildcard pair",
                        List.of(fee(null, null, null, null, null),
                                fee(null, null, "1", "1", null))),
                new Bad("tier missing bpsOverride",
                        List.of(fee("s", "OUTBOUND", null, null,
                                List.of(new FeeTier(BigDecimal.ONE, null))))),
                new Bad("tiers not strictly ascending",
                        List.of(fee("s", "OUTBOUND", null, null,
                                List.of(new FeeTier(new BigDecimal("100"), BigDecimal.ONE),
                                        new FeeTier(new BigDecimal("100"), BigDecimal.TEN))))),
                new Bad("tier bps over envelope",
                        List.of(fee("s", "OUTBOUND", null, null,
                                List.of(new FeeTier(BigDecimal.ONE,
                                        new BigDecimal("1000")))))));

        for (Bad bad : bads) {
            assertThatThrownBy(() -> service.replaceDraftFeeSchedules(
                    "FEE_INVALID", bad.fees(), "maker_kim"))
                    .as(bad.label())
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }

        // A 400 must be side-effect free: no fee row landed.
        assertThat(repository.findCurrentByPartnerId(partnerId)).isEmpty();
    }

    @Test
    void nullList_isRejectedWith400() {
        seedPartner("FEE_NULL");
        assertThatThrownBy(() -> service.replaceDraftFeeSchedules("FEE_NULL", null, "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("feeSchedules is required");
                });
    }

    @Test
    void unknownPartner_404_onBothOperations() {
        assertThatThrownBy(() -> service.replaceDraftFeeSchedules(
                "FEE_GHOST", List.of(), "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.currentFeeSchedules("FEE_GHOST"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void nonOnboardingPartner_409_postActivationFlowIsSlice8() {
        seedPartner("FEE_LIVE");
        service.replaceDraftFeeSchedules("FEE_LIVE",
                List.of(fee("zeropay_kr", "OUTBOUND", "1", "10", null)), "maker_kim");

        PartnerEntity current = partnerRepository.findCurrentByPartnerCode("FEE_LIVE")
                .orElseThrow();
        current.setStatus(PartnerStatus.LIVE);
        partnerRepository.saveAndFlush(current);

        assertThatThrownBy(() -> service.replaceDraftFeeSchedules("FEE_LIVE",
                List.of(), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        // Reads stay open for LIVE partners (detail page tile).
        assertThat(service.currentFeeSchedules("FEE_LIVE")).hasSize(1);
    }

    // ---- Step 6: resolveServiceFee (wire partner_fee_schedule into pricing) ----

    @Test
    void resolveServiceFee_fixedPlusBps_andTierOverride() {
        seedPartner("FEE_RESOLVE");
        // zeropay_kr OUTBOUND: fixed 1.50 USD + 25 bps flat, with a tier: ≥10,000 → 20 bps.
        service.replaceDraftFeeSchedules("FEE_RESOLVE",
                List.of(fee("zeropay_kr", "OUTBOUND", "1.50", "25",
                        List.of(new FeeTier(new BigDecimal("10000"), new BigDecimal("20"))))),
                "maker_kim");

        // Below the tier band (volume 1,000): flat 25 bps → 1.50 + 1000*25/10000 = 1.50 + 2.50 = 4.00
        assertThat(service.resolveServiceFee("FEE_RESOLVE", "zeropay_kr", "OUTBOUND", new BigDecimal("1000"))
                .orElseThrow()).isEqualByComparingTo(new BigDecimal("4.0000"));

        // At/above the band (volume 20,000): 20 bps override → 1.50 + 20000*20/10000 = 1.50 + 40.00 = 41.50
        assertThat(service.resolveServiceFee("FEE_RESOLVE", "zeropay_kr", "OUTBOUND", new BigDecimal("20000"))
                .orElseThrow()).isEqualByComparingTo(new BigDecimal("41.5000"));
    }

    @Test
    void resolveServiceFee_mostSpecificMatchWins_overWildcards() {
        seedPartner("FEE_MATCH");
        service.replaceDraftFeeSchedules("FEE_MATCH",
                List.of(fee(null, null, "0.10", "5", null),                 // partner-wide wildcard
                        fee("zeropay_kr", "OUTBOUND", "1.00", "10", null)),  // exact scheme+direction
                "maker_kim");

        // Exact (scheme+direction) beats the wildcard: 1.00 + 1000*10/10000 = 1.00 + 1.00 = 2.00
        assertThat(service.resolveServiceFee("FEE_MATCH", "zeropay_kr", "OUTBOUND", new BigDecimal("1000"))
                .orElseThrow()).isEqualByComparingTo(new BigDecimal("2.0000"));

        // A different scheme falls back to the wildcard: 0.10 + 1000*5/10000 = 0.10 + 0.50 = 0.60
        assertThat(service.resolveServiceFee("FEE_MATCH", "other_scheme", "INBOUND", new BigDecimal("1000"))
                .orElseThrow()).isEqualByComparingTo(new BigDecimal("0.6000"));
    }

    @Test
    void resolveServiceFee_lenientEmpty_whenNoPartnerOrNoMatch() {
        // Unknown partner → empty (caller defaults, never fails).
        assertThat(service.resolveServiceFee("FEE_GHOST", "zeropay_kr", "OUTBOUND", BigDecimal.TEN))
                .isEmpty();

        // Known partner but no fee rows configured → empty.
        seedPartner("FEE_NONE");
        assertThat(service.resolveServiceFee("FEE_NONE", "zeropay_kr", "OUTBOUND", BigDecimal.TEN))
                .isEmpty();
    }

    @Test
    void audit_oneEventPerReplace_withCanonicalBeforeAfterSnapshots() {
        seedPartner("FEE_AUDIT");
        publisher.clear(); // drop the PARTNER_SAVED event from the seed

        service.replaceDraftFeeSchedules("FEE_AUDIT",
                List.of(fee("zeropay_kr", "OUTBOUND", "1.5", "25",
                        List.of(new FeeTier(new BigDecimal("10000"), new BigDecimal("20"))))),
                "maker_kim");
        service.replaceDraftFeeSchedules("FEE_AUDIT", List.of(), "checker_lee");

        List<AuditEvent> events = publisher.published();
        assertThat(events).hasSize(2);

        AuditEvent first = events.get(0);
        assertThat(first.aggregateType()).isEqualTo("partner_fee_schedule");
        assertThat(first.aggregateId()).isEqualTo("FEE_AUDIT");
        assertThat(first.eventType()).isEqualTo("PARTNER_FEE_SCHEDULES_REPLACED");
        assertThat(first.actorId()).isEqualTo("maker_kim");
        assertThat(first.beforeJsonb()).as("first replace — BEFORE must be null").isNull();
        assertThat(new String(first.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"schemeId\":\"zeropay_kr\"")
                .contains("\"direction\":\"OUTBOUND\"")
                // MONEY_CONVENTION: money is a plain-decimal STRING, scale 4.
                .contains("\"fixedFeeUsd\":\"1.5000\"")
                .contains("\"bpsFee\":\"25.0000\"")
                // Tier table embedded in its stored canonical form.
                .contains("\"tiers\":[{\"fromVolumeUsd\":\"10000.0000\","
                        + "\"bpsOverride\":\"20.0000\"}]");

        AuditEvent second = events.get(1);
        assertThat(second.actorId()).isEqualTo("checker_lee");
        assertThat(new String(second.beforeJsonb(), StandardCharsets.UTF_8))
                .as("BEFORE carries the superseded set")
                .contains("\"schemeId\":\"zeropay_kr\"");
        assertThat(new String(second.afterJsonb(), StandardCharsets.UTF_8))
                .as("AFTER of a clear is the empty array").isEqualTo("[]");
    }
}
