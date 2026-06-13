package com.gme.pay.registry.regulatory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.BokFxReportingCategory;
import com.gme.pay.contracts.BokRemitterType;
import com.gme.pay.contracts.LegalBasisCode;
import com.gme.pay.contracts.PartnerRegulatoryConfigCommand;
import com.gme.pay.contracts.PartnerRegulatoryConfigView;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.TravelRuleProtocol;
import com.gme.pay.contracts.VatTreatment;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 8 Lane C acceptance test for {@link PartnerRegulatoryConfigService} —
 * the {@code partner_regulatory_config} upsert (V029), wired end-to-end
 * against H2 in PostgreSQL mode with the full Flyway chain applied. Mirrors
 * the {@code PrefundingConfigServiceTest} slice-test pattern:
 * {@code @DataJpaTest} + explicit {@code @Import} of the service/audit beans
 * and a {@link RecordingAuditPublisher} to observe ADR-007 publication.
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>Step-8 upsert inserts a CURRENT row (V029 statutory defaults applied
 *       to null thresholds, KRW money normalized to scale 2, changed_by
 *       stamped); a second upsert supersedes the first — paired SCD-6 writes
 *       sharing one MICROS-truncated instant.</li>
 *   <li>Every roster-valued field (BOK category/remitter, VAT treatment,
 *       legal basis, Travel-Rule protocol) rejects off-roster values with
 *       400, without touching any row.</li>
 *   <li>bokTxnCode is held to the placeholder BOK shape ^\d{3}$ (OI-03
 *       TODO); the PIPA allowlist is held to real ISO-3166 alpha-2 codes,
 *       no blanks, no duplicates.</li>
 *   <li>CTR / Travel-Rule thresholds: strictly positive, ≤ 2 dp; the
 *       Travel-Rule endpoint is REQUIRED whenever the protocol is not
 *       NONE.</li>
 *   <li>One {@code partner_regulatory_config} audit event per write with
 *       BEFORE/AFTER canonical snapshots (KRW as plain-decimal strings).</li>
 *   <li>The Lane A activation-gate pre-req is queryable:
 *       {@code existsCurrentByPartnerId} flips false → true on the first
 *       step-8 save and stays true across supersessions.</li>
 *   <li>Unknown partner → 404; partner outside ONBOARDING → 409.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PartnerRegulatoryConfigServiceTest.TestConfig.class,
        PartnerRegulatoryConfigService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class PartnerRegulatoryConfigServiceTest {

    @Autowired
    private PartnerRegulatoryConfigService service;

    @Autowired
    private PartnerRegulatoryConfigRepository configRepository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private RecordingAuditPublisher publisher;

    /** Same publisher swap as {@code AuditLogTest} / {@code PrefundingConfigServiceTest}. */
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

    // ------------------------------------------------------------------ helpers

    /** Create a partner draft through the canonical store path; returns its surrogate id. */
    private Long seedPartner(String code) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
        return partnerRepository.findCurrentByPartnerCode(code).orElseThrow().getId();
    }

    /** Command with every field null — the legal "save an empty panel" draft progress. */
    private static PartnerRegulatoryConfigCommand emptyCmd() {
        return new PartnerRegulatoryConfigCommand(
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** A fully-populated, valid command. */
    private static PartnerRegulatoryConfigCommand fullCmd() {
        return new PartnerRegulatoryConfigCommand(
                "601",
                "INSTITUTIONAL",
                "CORPORATION",
                "vault-doc-cert-0001",
                "ZERO_RATED_EXPORT",
                "KOFIU-GME-001",
                new BigDecimal("20000000"),
                List.of("MN", "VN", "KH"),
                "CONTRACT",
                "TRP",
                "https://trp.partner.example.com/v1/transfers",
                new BigDecimal("1500000"));
    }

    // -------------------------------------------------------------------- tests

    @Test
    void upsert_appliesV029Defaults_andSecondUpsertSupersedes() {
        Long partnerId = seedPartner("REG_UPSERT");

        // Nulls everywhere -> the V029 statutory defaults.
        PartnerRegulatoryConfigView first =
                service.upsertStep8("REG_UPSERT", emptyCmd(), "maker_kim");

        assertThat(first.partnerId()).isEqualTo(partnerId);
        assertThat(first.bokTxnCode()).isNull();
        assertThat(first.bokFxReportingCategory()).isNull();
        assertThat(first.ctrThresholdKrw())
                .as("statutory KoFIU CTR default")
                .isEqualByComparingTo(new BigDecimal("10000000"));
        assertThat(first.ctrThresholdKrw().scale())
                .as("KRW normalized to NUMERIC(18,2) scale").isEqualTo(2);
        assertThat(first.travelRuleThresholdKrw())
                .as("statutory Travel-Rule default")
                .isEqualByComparingTo(new BigDecimal("1000000"));
        assertThat(first.pipaJurisdictionAllowlist()).isEmpty();
        assertThat(first.travelRuleProtocol()).isNull();

        PartnerRegulatoryConfigView second =
                service.upsertStep8("REG_UPSERT", fullCmd(), "checker_lee");

        assertThat(second.bokTxnCode()).isEqualTo("601");
        assertThat(second.bokFxReportingCategory())
                .isEqualTo(BokFxReportingCategory.INSTITUTIONAL);
        assertThat(second.bokRemitterType()).isEqualTo(BokRemitterType.CORPORATION);
        assertThat(second.hometaxIssuerCertId()).isEqualTo("vault-doc-cert-0001");
        assertThat(second.vatTreatment()).isEqualTo(VatTreatment.ZERO_RATED_EXPORT);
        assertThat(second.kofiuEntityId()).isEqualTo("KOFIU-GME-001");
        assertThat(second.ctrThresholdKrw()).isEqualByComparingTo(new BigDecimal("20000000"));
        assertThat(second.pipaJurisdictionAllowlist()).containsExactly("MN", "VN", "KH");
        assertThat(second.legalBasisCode()).isEqualTo(LegalBasisCode.CONTRACT);
        assertThat(second.travelRuleProtocol()).isEqualTo(TravelRuleProtocol.TRP);
        assertThat(second.travelRuleEndpointUrl())
                .isEqualTo("https://trp.partner.example.com/v1/transfers");
        assertThat(second.travelRuleThresholdKrw())
                .isEqualByComparingTo(new BigDecimal("1500000"));

        // SCD-6: nothing deleted — 2 rows, prior superseded with an instant
        // EXACTLY equal to the fresh recorded_at (shared paired-write instant),
        // MICROS-truncated, business time continuous, provenance stamped.
        List<PartnerRegulatoryConfigEntity> all = configRepository.findAll().stream()
                .filter(e -> partnerId.equals(e.getPartnerId())).toList();
        assertThat(all).hasSize(2);
        PartnerRegulatoryConfigEntity prior = all.stream()
                .filter(e -> e.getSupersededAt() != null).findFirst().orElseThrow();
        PartnerRegulatoryConfigEntity current = all.stream()
                .filter(e -> e.getSupersededAt() == null).findFirst().orElseThrow();
        assertThat(prior.getSupersededAt()).isEqualTo(current.getRecordedAt());
        assertThat(current.getRecordedAt().getNano() % 1000).isZero();
        assertThat(current.getValidFrom()).isEqualTo(prior.getValidFrom());
        assertThat(prior.getChangedBy()).isEqualTo("maker_kim");
        assertThat(current.getChangedBy()).isEqualTo("checker_lee");
        assertThat(current.getChangeRequestId())
                .as("direct ONBOARDING writes carry no change_request").isNull();

        // Rehydrate path returns the current row.
        assertThat(service.currentConfig("REG_UPSERT").kofiuEntityId())
                .isEqualTo("KOFIU-GME-001");
    }

    @Test
    void bokFxReportingCategory_offRoster_rejectedWith400() {
        seedPartner("REG_BOK_CAT");
        assertThatThrownBy(() -> service.upsertStep8("REG_BOK_CAT",
                new PartnerRegulatoryConfigCommand(null, "AGGREGATED_WEEKLY", null, null,
                        null, null, null, null, null, null, null, null),
                "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("bokFxReportingCategory")
                            .contains("INDIVIDUAL_AGGREGATE").contains("INSTITUTIONAL");
                });
    }

    @Test
    void bokRemitterType_offRoster_rejectedWith400() {
        seedPartner("REG_BOK_REM");
        assertThatThrownBy(() -> service.upsertStep8("REG_BOK_REM",
                new PartnerRegulatoryConfigCommand(null, null, "NGO", null,
                        null, null, null, null, null, null, null, null),
                "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("bokRemitterType")
                            .contains("FINANCIAL_INSTITUTION");
                });
    }

    @Test
    void vatTreatment_offRoster_rejectedWith400() {
        seedPartner("REG_VAT");
        assertThatThrownBy(() -> service.upsertStep8("REG_VAT",
                new PartnerRegulatoryConfigCommand(null, null, null, null,
                        "REDUCED_RATE", null, null, null, null, null, null, null),
                "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("vatTreatment")
                            .contains("ZERO_RATED_EXPORT");
                });
    }

    @Test
    void legalBasisCode_offRoster_rejectedWith400() {
        seedPartner("REG_BASIS");
        assertThatThrownBy(() -> service.upsertStep8("REG_BASIS",
                new PartnerRegulatoryConfigCommand(null, null, null, null,
                        null, null, null, null, "MARKETING", null, null, null),
                "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("legalBasisCode")
                            .contains("LEGITIMATE_INTEREST");
                });
    }

    @Test
    void travelRuleProtocol_offRoster_rejectedWith400() {
        seedPartner("REG_TRP_ROSTER");
        assertThatThrownBy(() -> service.upsertStep8("REG_TRP_ROSTER",
                new PartnerRegulatoryConfigCommand(null, null, null, null,
                        null, null, null, null, null, "OPENVASP", null, null),
                "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("travelRuleProtocol")
                            .contains("IVMS101");
                });
    }

    @Test
    void bokTxnCode_heldToThePlaceholderThreeDigitShape() {
        seedPartner("REG_BOK_CODE");

        for (String bad : List.of("60", "6011", "A01", "6 1", "abc")) {
            assertThatThrownBy(() -> service.upsertStep8("REG_BOK_CODE",
                    new PartnerRegulatoryConfigCommand(bad, null, null, null,
                            null, null, null, null, null, null, null, null),
                    "maker_kim"))
                    .as("bokTxnCode '" + bad + "'")
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getReason()).contains("bokTxnCode");
                    });
        }

        // Exactly 3 digits sails through (placeholder regex pending OI-03).
        PartnerRegulatoryConfigView view = service.upsertStep8("REG_BOK_CODE",
                new PartnerRegulatoryConfigCommand("042", null, null, null,
                        null, null, null, null, null, null, null, null),
                "maker_kim");
        assertThat(view.bokTxnCode()).isEqualTo("042");
    }

    @Test
    void pipaAllowlist_rejectsNonIsoBlankAndDuplicateCodes() {
        Long partnerId = seedPartner("REG_PIPA");

        record Bad(String label, List<String> codes) {}
        List<Bad> bads = List.of(
                new Bad("not a country", List.of("MN", "XX")),
                new Bad("lowercase", List.of("mn")),
                new Bad("3-letter", List.of("MNG")),
                new Bad("blank element", List.of("MN", "  ")),
                new Bad("duplicate", List.of("MN", "VN", "MN")));

        for (Bad bad : bads) {
            assertThatThrownBy(() -> service.upsertStep8("REG_PIPA",
                    new PartnerRegulatoryConfigCommand(null, null, null, null, null, null,
                            null, bad.codes(), null, null, null, null),
                    "maker_kim"))
                    .as(bad.label())
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getReason()).contains("pipaJurisdictionAllowlist");
                    });
        }

        // A 400 must be side-effect free: no config row landed.
        assertThat(configRepository.findCurrentByPartnerId(partnerId)).isEmpty();

        // Valid codes round-trip in order; the CSV column stores them joined.
        PartnerRegulatoryConfigView view = service.upsertStep8("REG_PIPA",
                new PartnerRegulatoryConfigCommand(null, null, null, null, null, null,
                        null, List.of("MN", "VN", "KH", "NP"), null, null, null, null),
                "maker_kim");
        assertThat(view.pipaJurisdictionAllowlist()).containsExactly("MN", "VN", "KH", "NP");
        assertThat(configRepository.findCurrentByPartnerId(partnerId).orElseThrow()
                .getPipaJurisdictionAllowlist()).isEqualTo("MN,VN,KH,NP");
    }

    @Test
    void ctrThreshold_mustBeStrictlyPositive_andAtMostTwoDecimals() {
        Long partnerId = seedPartner("REG_CTR");

        for (BigDecimal bad : List.of(BigDecimal.ZERO, new BigDecimal("-1"),
                new BigDecimal("10000000.123"))) {
            assertThatThrownBy(() -> service.upsertStep8("REG_CTR",
                    new PartnerRegulatoryConfigCommand(null, null, null, null, null, null,
                            bad, null, null, null, null, null),
                    "maker_kim"))
                    .as("ctrThresholdKrw " + bad.toPlainString())
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getReason()).contains("ctrThresholdKrw");
                    });
        }
        assertThat(configRepository.findCurrentByPartnerId(partnerId)).isEmpty();
    }

    @Test
    void travelRuleThreshold_mustBeStrictlyPositive_andAtMostTwoDecimals() {
        seedPartner("REG_TR_THRESH");

        for (BigDecimal bad : List.of(BigDecimal.ZERO, new BigDecimal("-0.01"),
                new BigDecimal("1000000.005"))) {
            assertThatThrownBy(() -> service.upsertStep8("REG_TR_THRESH",
                    new PartnerRegulatoryConfigCommand(null, null, null, null, null, null,
                            null, null, null, null, null, bad),
                    "maker_kim"))
                    .as("travelRuleThresholdKrw " + bad.toPlainString())
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode())
                                    .isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    @Test
    void travelRuleEndpoint_requiredForEveryProtocolExceptNone() {
        seedPartner("REG_TR_EP");

        for (String protocol : List.of("TRP", "SYGNA", "IVMS101")) {
            assertThatThrownBy(() -> service.upsertStep8("REG_TR_EP",
                    new PartnerRegulatoryConfigCommand(null, null, null, null, null, null,
                            null, null, null, protocol, null, null),
                    "maker_kim"))
                    .as("protocol " + protocol + " without endpoint")
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getReason()).contains("travelRuleEndpointUrl")
                                .contains(protocol);
                    });
        }

        // NONE legitimately carries no endpoint.
        PartnerRegulatoryConfigView none = service.upsertStep8("REG_TR_EP",
                new PartnerRegulatoryConfigCommand(null, null, null, null, null, null,
                        null, null, null, "NONE", null, null),
                "maker_kim");
        assertThat(none.travelRuleProtocol()).isEqualTo(TravelRuleProtocol.NONE);
        assertThat(none.travelRuleEndpointUrl()).isNull();

        // And a protocol WITH an endpoint is accepted.
        PartnerRegulatoryConfigView sygna = service.upsertStep8("REG_TR_EP",
                new PartnerRegulatoryConfigCommand(null, null, null, null, null, null,
                        null, null, null, "SYGNA",
                        "https://sygna.partner.example.com/bridge", null),
                "maker_kim");
        assertThat(sygna.travelRuleProtocol()).isEqualTo(TravelRuleProtocol.SYGNA);
        assertThat(sygna.travelRuleEndpointUrl())
                .isEqualTo("https://sygna.partner.example.com/bridge");
    }

    @Test
    void audit_oneEventPerWrite_withCanonicalBeforeAfterSnapshots() {
        seedPartner("REG_AUDIT");
        publisher.clear(); // drop the PARTNER_SAVED event from the seed

        service.upsertStep8("REG_AUDIT", fullCmd(), "maker_kim");
        service.upsertStep8("REG_AUDIT", emptyCmd(), "checker_lee");

        List<AuditEvent> events = publisher.published();
        assertThat(events).hasSize(2);

        AuditEvent first = events.get(0);
        assertThat(first.aggregateType()).isEqualTo("partner_regulatory_config");
        assertThat(first.aggregateId()).isEqualTo("REG_AUDIT");
        assertThat(first.eventType()).isEqualTo("PARTNER_REGULATORY_CONFIG_SAVED");
        assertThat(first.actorId()).isEqualTo("maker_kim");
        assertThat(first.beforeJsonb()).as("first write — BEFORE must be null").isNull();
        assertThat(new String(first.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"bokTxnCode\":\"601\"")
                .contains("\"bokFxReportingCategory\":\"INSTITUTIONAL\"")
                // MONEY_CONVENTION: KRW as a plain-decimal STRING, scale 2.
                .contains("\"ctrThresholdKrw\":\"20000000.00\"")
                .contains("\"travelRuleThresholdKrw\":\"1500000.00\"")
                .contains("\"pipaJurisdictionAllowlist\":\"MN,VN,KH\"")
                .contains("\"travelRuleProtocol\":\"TRP\"");

        AuditEvent second = events.get(1);
        assertThat(second.actorId()).isEqualTo("checker_lee");
        assertThat(new String(second.beforeJsonb(), StandardCharsets.UTF_8))
                .as("BEFORE carries the superseded row")
                .contains("\"kofiuEntityId\":\"KOFIU-GME-001\"");
        assertThat(new String(second.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"ctrThresholdKrw\":\"10000000.00\"")
                .contains("\"kofiuEntityId\":null");
    }

    @Test
    void activationGatePreReq_existsCurrentByPartnerId_flipsOnFirstSave() {
        Long partnerId = seedPartner("REG_GATE");
        Long otherId = seedPartner("REG_GATE_OTHER");

        // Lane A's ActivationGateService calls exactly this query for the
        // hard LIVE pre-condition "regulatory config exists".
        assertThat(configRepository.existsCurrentByPartnerId(partnerId)).isFalse();
        assertThat(service.hasCurrentConfig("REG_GATE")).isFalse();

        service.upsertStep8("REG_GATE", emptyCmd(), "maker_kim");

        assertThat(configRepository.existsCurrentByPartnerId(partnerId)).isTrue();
        assertThat(service.hasCurrentConfig("REG_GATE")).isTrue();
        // The pre-req survives supersession (the replacing row is current).
        service.upsertStep8("REG_GATE", fullCmd(), "maker_kim");
        assertThat(configRepository.existsCurrentByPartnerId(partnerId)).isTrue();
        // And it is per-partner: the sibling draft stays ungated.
        assertThat(configRepository.existsCurrentByPartnerId(otherId)).isFalse();
    }

    @Test
    void nullBody_isRejectedWith400() {
        seedPartner("REG_NULL");
        assertThatThrownBy(() -> service.upsertStep8("REG_NULL", null, "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void unknownPartner_404_onAllOperations() {
        assertThatThrownBy(() -> service.upsertStep8("REG_GHOST", emptyCmd(), "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.currentConfig("REG_GHOST"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.hasCurrentConfig("REG_GHOST"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void partnerWithoutConfig_404_onRead() {
        seedPartner("REG_EMPTY");
        assertThatThrownBy(() -> service.currentConfig("REG_EMPTY"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getReason()).contains("regulatory config");
                });
    }

    @Test
    void nonOnboardingPartner_409_postActivationFlowRidesChangeRequests() {
        seedPartner("REG_LIVE");
        service.upsertStep8("REG_LIVE", fullCmd(), "maker_kim");

        PartnerEntity current = partnerRepository.findCurrentByPartnerCode("REG_LIVE")
                .orElseThrow();
        current.setStatus(PartnerStatus.LIVE);
        partnerRepository.saveAndFlush(current);

        assertThatThrownBy(() -> service.upsertStep8("REG_LIVE", emptyCmd(), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        // Reads stay open for LIVE partners (detail page tile + filing jobs).
        assertThat(service.currentConfig("REG_LIVE").bokTxnCode()).isEqualTo("601");
    }
}
