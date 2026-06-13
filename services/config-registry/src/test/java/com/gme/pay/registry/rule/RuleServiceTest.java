package com.gme.pay.registry.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.RuleCommand;
import com.gme.pay.contracts.RuleView;
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
 * Slice 6 acceptance test for {@link RuleService} — the {@code partner_rule}
 * bulk replace (V017) plus the V016 collection/settle currency split, wired
 * end-to-end against H2 in PostgreSQL mode with the full Flyway chain applied.
 * Mirrors the {@code PrefundingConfigServiceTest} slice-test pattern:
 * {@code @DataJpaTest} + explicit {@code @Import} of the service/audit beans
 * and a {@link RecordingAuditPublisher} to observe ADR-007 publication.
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>The V016 Expand-phase contract: a partner created through the legacy
 *       four-field path gets collection_ccy / settle_a_ccy mirrored from
 *       settlement_currency, and the legacy column stays populated.</li>
 *   <li>Step-6 bulk replace inserts the CURRENT set (margins/money normalized
 *       to scale 4); a second replace supersedes the whole prior set — paired
 *       SCD-6 writes sharing one MICROS-truncated instant; an empty list
 *       clears.</li>
 *   <li>The lib-domain {@code Rule.validate} margin invariant (RATE-04 §11)
 *       gates the write: cross-border (collection KRW / settle USD) rules
 *       need mA + mB &ge; 2%; same-currency rules must carry zero margin.</li>
 *   <li>Field validation rejects bad direction / missing or over-scale
 *       margins / negative charge / duplicate (scheme, direction) keys with
 *       400, without touching any row.</li>
 *   <li>One {@code partner_rule} audit event per write with BEFORE/AFTER
 *       canonical snapshots (decimals as plain-decimal strings).</li>
 *   <li>Unknown partner → 404; partner outside ONBOARDING → 409.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RuleServiceTest.TestConfig.class, RuleService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class RuleServiceTest {

    @Autowired
    private RuleService service;

    @Autowired
    private RuleRepository ruleRepository;

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

    /** Create a partner draft through the canonical store path; returns its surrogate id. */
    private Long seedPartner(String code) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
        return partnerRepository.findCurrentByPartnerCode(code).orElseThrow().getId();
    }

    /**
     * Seed a partner and state a REAL currency split (collect KRW / settle
     * USD) on the current row — the cross-border corridor of the Slice 6 exit
     * gate. The commercial-terms step writes this in production; the test
     * stamps the columns directly.
     */
    private Long seedCrossBorderPartner(String code) {
        Long id = seedPartner(code);
        PartnerEntity current = partnerRepository.findCurrentByPartnerCode(code).orElseThrow();
        current.setCollectionCcy("KRW");
        current.setSettleACcy("USD");
        partnerRepository.saveAndFlush(current);
        return id;
    }

    private static RuleCommand rule(String schemeId, String direction,
                                    String mA, String mB, String charge) {
        return new RuleCommand(schemeId, direction,
                mA == null ? null : new BigDecimal(mA),
                mB == null ? null : new BigDecimal(mB),
                charge == null ? null : new BigDecimal(charge));
    }

    // -------------------------------------------------------------------- tests

    @Test
    void v016Split_mirrorsLegacySettlementCurrencyOnLegacyWrites() {
        seedPartner("RULE_SPLIT_MIRROR");
        PartnerEntity current = partnerRepository
                .findCurrentByPartnerCode("RULE_SPLIT_MIRROR").orElseThrow();

        // Expand-phase contract (ADR-013): the split mirrors the legacy column,
        // and the legacy column itself stays populated.
        assertThat(current.getSettlementCurrency()).isEqualTo("USD");
        assertThat(current.getCollectionCcy()).isEqualTo("USD");
        assertThat(current.getSettleACcy()).isEqualTo("USD");
    }

    @Test
    void v016Split_realSplitSurvivesAFourFieldSupersede() {
        seedCrossBorderPartner("RULE_SPLIT_CARRY");

        // A legacy four-field write (e.g. a rounding-mode change) supersedes the
        // row; the genuine split must ride onto the fresh current row instead of
        // being re-mirrored from settlement_currency.
        partnerStore.updateRoundingMode("RULE_SPLIT_CARRY", RoundingMode.DOWN);

        PartnerEntity current = partnerRepository
                .findCurrentByPartnerCode("RULE_SPLIT_CARRY").orElseThrow();
        assertThat(current.getCollectionCcy()).isEqualTo("KRW");
        assertThat(current.getSettleACcy()).isEqualTo("USD");
        assertThat(current.getSettlementCurrency()).isEqualTo("USD");
    }

    @Test
    void bulkReplace_insertsThenSupersedesWholeSet_scd6Paired() {
        Long partnerId = seedCrossBorderPartner("RULE_REPLACE");

        List<RuleView> first = service.replaceDraftRules("RULE_REPLACE", List.of(
                        rule("ZEROPAY", "OUTBOUND", "0.015", "0.01", "1.50"),
                        rule("ZEROPAY", "INBOUND", "0.02", "0.005", null)),
                "maker_kim");

        assertThat(first).hasSize(2);
        assertThat(first.get(0).schemeId()).isEqualTo("ZEROPAY");
        assertThat(first.get(0).direction()).isEqualTo("OUTBOUND");
        // Scale-4 normalization (stored == in-memory on both engines).
        assertThat(first.get(0).mA()).isEqualByComparingTo(new BigDecimal("0.0150"));
        assertThat(first.get(0).mA().scale()).isEqualTo(4);
        assertThat(first.get(0).serviceChargeUsd())
                .isEqualByComparingTo(new BigDecimal("1.5000"));
        // Null charge defaults to the V017 column DEFAULT 0.
        assertThat(first.get(1).serviceChargeUsd()).isEqualByComparingTo(BigDecimal.ZERO);

        List<RuleView> second = service.replaceDraftRules("RULE_REPLACE", List.of(
                        rule("ZEROPAY", "BOTH", "0.012", "0.008", "2")),
                "maker_kim");
        assertThat(second).hasSize(1);
        assertThat(second.get(0).direction()).isEqualTo("BOTH");

        // SCD-6: nothing deleted — 3 rows total, the prior 2 superseded with an
        // instant EXACTLY equal to the fresh recorded_at (shared paired-write
        // instant), MICROS-truncated.
        List<RuleEntity> all = ruleRepository.findAll().stream()
                .filter(e -> partnerId.equals(e.getPartnerId())).toList();
        assertThat(all).hasSize(3);
        List<RuleEntity> superseded = all.stream()
                .filter(e -> e.getSupersededAt() != null).toList();
        RuleEntity current = all.stream()
                .filter(e -> e.getSupersededAt() == null).findFirst().orElseThrow();
        assertThat(superseded).hasSize(2);
        assertThat(superseded).allSatisfy(p ->
                assertThat(p.getSupersededAt()).isEqualTo(current.getRecordedAt()));
        assertThat(current.getRecordedAt().getNano() % 1000).isZero();

        // Rehydrate path returns only the current set.
        assertThat(service.currentRules("RULE_REPLACE"))
                .extracting(RuleView::direction).containsExactly("BOTH");

        // An empty list clears all rules (replace semantics).
        assertThat(service.replaceDraftRules("RULE_REPLACE", List.of(), "maker_kim")).isEmpty();
        assertThat(service.currentRules("RULE_REPLACE")).isEmpty();
        assertThat(ruleRepository.findCurrentByPartnerId(partnerId)).isEmpty();
    }

    @Test
    void marginInvariant_crossBorderBelowTwoPercent_rejectedWith400() {
        Long partnerId = seedCrossBorderPartner("RULE_MIN_MARGIN");

        assertThatThrownBy(() -> service.replaceDraftRules("RULE_MIN_MARGIN", List.of(
                        rule("ZEROPAY", "OUTBOUND", "0.005", "0.005", null)),
                "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("rules[0]");
                    assertThat(e.getReason()).contains("cross-border");
                });
        assertThat(ruleRepository.findCurrentByPartnerId(partnerId)).isEmpty();

        // Exactly 2% combined passes the floor.
        assertThat(service.replaceDraftRules("RULE_MIN_MARGIN", List.of(
                        rule("ZEROPAY", "OUTBOUND", "0.015", "0.005", null)),
                "maker_kim")).hasSize(1);
    }

    @Test
    void marginInvariant_sameCurrencyNonZeroMargin_rejectedWith400() {
        // Mirror-split partner (collect USD / settle USD): the USD pool is
        // short-circuited, so any margin is a pricing error.
        Long partnerId = seedPartner("RULE_SAME_CCY");

        assertThatThrownBy(() -> service.replaceDraftRules("RULE_SAME_CCY", List.of(
                        rule("ZEROPAY", "OUTBOUND", "0.01", "0.01", null)),
                "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("same-currency");
                });
        assertThat(ruleRepository.findCurrentByPartnerId(partnerId)).isEmpty();

        // Zero margin + flat service charge is the valid same-currency shape.
        List<RuleView> saved = service.replaceDraftRules("RULE_SAME_CCY", List.of(
                        rule("ZEROPAY", "OUTBOUND", "0", "0", "3.50")),
                "maker_kim");
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).serviceChargeUsd())
                .isEqualByComparingTo(new BigDecimal("3.5"));
    }

    @Test
    void validation_rejectsBadPayloadsWith400_withoutWritingRows() {
        Long partnerId = seedCrossBorderPartner("RULE_INVALID");

        record Bad(String label, List<RuleCommand> rules) {}
        List<Bad> bads = List.of(
                new Bad("missing schemeId", List.of(
                        rule(null, "OUTBOUND", "0.015", "0.005", null))),
                new Bad("blank schemeId", List.of(
                        rule("  ", "OUTBOUND", "0.015", "0.005", null))),
                new Bad("schemeId over 40 chars", List.of(
                        rule("X".repeat(41), "OUTBOUND", "0.015", "0.005", null))),
                new Bad("missing direction", List.of(
                        rule("ZEROPAY", null, "0.015", "0.005", null))),
                new Bad("unknown direction", List.of(
                        rule("ZEROPAY", "SIDEWAYS", "0.015", "0.005", null))),
                new Bad("missing mA", List.of(
                        rule("ZEROPAY", "OUTBOUND", null, "0.02", null))),
                new Bad("missing mB", List.of(
                        rule("ZEROPAY", "OUTBOUND", "0.02", null, null))),
                new Bad("negative mA", List.of(
                        rule("ZEROPAY", "OUTBOUND", "-0.01", "0.03", null))),
                new Bad("mA over 4dp", List.of(
                        rule("ZEROPAY", "OUTBOUND", "0.01505", "0.01", null))),
                new Bad("mA over NUMERIC(7,4)", List.of(
                        rule("ZEROPAY", "OUTBOUND", "1000", "0", null))),
                new Bad("negative charge", List.of(
                        rule("ZEROPAY", "OUTBOUND", "0.015", "0.005", "-1"))),
                new Bad("charge over 4dp", List.of(
                        rule("ZEROPAY", "OUTBOUND", "0.015", "0.005", "1.12345"))),
                new Bad("duplicate (scheme, direction)", List.of(
                        rule("ZEROPAY", "OUTBOUND", "0.015", "0.005", null),
                        rule("ZEROPAY", "OUTBOUND", "0.02", "0.01", null))));

        for (Bad bad : bads) {
            assertThatThrownBy(() -> service.replaceDraftRules(
                    "RULE_INVALID", bad.rules(), "maker_kim"))
                    .as(bad.label())
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }

        // A 400 must be side-effect free: no rule row landed.
        assertThat(ruleRepository.findCurrentByPartnerId(partnerId)).isEmpty();
    }

    @Test
    void nullList_isRejectedWith400() {
        seedCrossBorderPartner("RULE_NULL");
        assertThatThrownBy(() -> service.replaceDraftRules("RULE_NULL", null, "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void unknownPartner_404_onBothOperations() {
        assertThatThrownBy(() -> service.replaceDraftRules(
                "RULE_GHOST", List.of(), "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.currentRules("RULE_GHOST"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void partnerWithoutRules_returnsEmptyListOnRead() {
        seedPartner("RULE_EMPTY");
        assertThat(service.currentRules("RULE_EMPTY")).isEmpty();
    }

    @Test
    void nonOnboardingPartner_409_postActivationFlowIsSlice8() {
        seedCrossBorderPartner("RULE_LIVE");
        service.replaceDraftRules("RULE_LIVE", List.of(
                rule("ZEROPAY", "OUTBOUND", "0.015", "0.005", null)), "maker_kim");

        PartnerEntity current = partnerRepository.findCurrentByPartnerCode("RULE_LIVE")
                .orElseThrow();
        current.setStatus(PartnerStatus.LIVE);
        partnerRepository.saveAndFlush(current);

        assertThatThrownBy(() -> service.replaceDraftRules("RULE_LIVE", List.of(),
                "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        // Reads stay open for LIVE partners (detail page tile).
        assertThat(service.currentRules("RULE_LIVE")).hasSize(1);
    }

    @Test
    void audit_oneEventPerWrite_withCanonicalBeforeAfterSnapshots() {
        seedCrossBorderPartner("RULE_AUDIT");
        publisher.clear(); // drop the PARTNER_SAVED event from the seed

        service.replaceDraftRules("RULE_AUDIT", List.of(
                rule("ZEROPAY", "OUTBOUND", "0.015", "0.01", "1.5")), "maker_kim");
        service.replaceDraftRules("RULE_AUDIT", List.of(
                rule("ZEROPAY", "BOTH", "0.02", "0.005", null)), "checker_lee");

        List<AuditEvent> events = publisher.published();
        assertThat(events).hasSize(2);

        AuditEvent first = events.get(0);
        assertThat(first.aggregateType()).isEqualTo("partner_rule");
        assertThat(first.aggregateId()).isEqualTo("RULE_AUDIT");
        assertThat(first.eventType()).isEqualTo("PARTNER_RULES_REPLACED");
        assertThat(first.actorId()).isEqualTo("maker_kim");
        assertThat(first.beforeJsonb()).as("first write — BEFORE must be null").isNull();
        assertThat(new String(first.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"schemeId\":\"ZEROPAY\"")
                .contains("\"direction\":\"OUTBOUND\"")
                // MONEY_CONVENTION: decimals as plain-decimal STRINGS, scale 4.
                .contains("\"mA\":\"0.0150\"")
                .contains("\"mB\":\"0.0100\"")
                .contains("\"serviceChargeUsd\":\"1.5000\"");

        AuditEvent second = events.get(1);
        assertThat(second.actorId()).isEqualTo("checker_lee");
        assertThat(new String(second.beforeJsonb(), StandardCharsets.UTF_8))
                .as("BEFORE carries the superseded set")
                .contains("\"direction\":\"OUTBOUND\"");
        assertThat(new String(second.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"direction\":\"BOTH\"")
                .contains("\"serviceChargeUsd\":\"0.0000\"");
    }
}
