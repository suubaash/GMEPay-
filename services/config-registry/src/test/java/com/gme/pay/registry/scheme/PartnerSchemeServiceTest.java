package com.gme.pay.registry.scheme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.PartnerSchemeCommand;
import com.gme.pay.contracts.PartnerSchemeView;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
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
 * Slice 7 acceptance test for {@link PartnerSchemeService} — the
 * {@code partner_scheme} bulk replace (V022), wired end-to-end against H2 in
 * PostgreSQL mode with the full Flyway chain applied. Mirrors the
 * {@code RuleServiceTest} slice-test pattern: {@code @DataJpaTest} + explicit
 * {@code @Import} of the service/audit beans and a
 * {@link RecordingAuditPublisher} to observe ADR-007 publication.
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>Step-7 bulk replace inserts the CURRENT set; a second replace
 *       supersedes the whole prior set — paired SCD-6 writes sharing one
 *       MICROS-truncated instant; an empty list clears.</li>
 *   <li>The ZEROPAY cross-field invariant (Slice 7): an ENABLED ZEROPAY row
 *       without {@code zeropayMerchantId} + {@code kftcInstitutionCode} is a
 *       400 VALIDATION_ERROR — but a DISABLED incomplete row saves fine
 *       (drafts may be incomplete; the rule is service-layer, not a DB
 *       CHECK).</li>
 *   <li>Field validation rejects roster violations (scheme / direction /
 *       role / partnerTypeChar / approval methods), over-width fields and
 *       duplicate schemeIds with 400, without touching any row.</li>
 *   <li>One {@code partner_scheme} audit event per write with BEFORE/AFTER
 *       canonical snapshots.</li>
 *   <li>Unknown partner → 404; partner outside ONBOARDING → 409.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PartnerSchemeServiceTest.TestConfig.class, PartnerSchemeService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class PartnerSchemeServiceTest {

    @Autowired
    private PartnerSchemeService service;

    @Autowired
    private PartnerSchemeRepository schemeRepository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private RecordingAuditPublisher publisher;

    /** Same publisher swap as {@code AuditLogTest} / {@code RuleServiceTest}. */
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

    /** Stamp the operating country onto the current partner row (the location read joins on it). */
    private void setOperatingCountry(Long partnerId, String country) {
        PartnerEntity partner = partnerRepository.findById(partnerId).orElseThrow();
        partner.setOperatingCountry(country);
        partnerRepository.saveAndFlush(partner);
    }

    /** A fully-wired ZEROPAY enablement (passes the cross-field invariant). */
    private static PartnerSchemeCommand zeropay(Boolean enabled) {
        return new PartnerSchemeCommand("ZEROPAY", "OUTBOUND", "ACQUIRER",
                "ZPM-0001", "ZPSM-0001", "KFTC097", "D", "vault-zp-1",
                "CONFIRMATION", "SILENT", enabled);
    }

    /** A minimal non-ZEROPAY enablement (no scheme-side wiring needed). */
    private static PartnerSchemeCommand scheme(String schemeId, String direction, String role) {
        return new PartnerSchemeCommand(schemeId, direction, role,
                null, null, null, null, null, null, null, null);
    }

    // -------------------------------------------------------------------- tests

    @Test
    void bulkReplace_insertsThenSupersedesWholeSet_scd6Paired() {
        Long partnerId = seedPartner("SCH_REPLACE");

        List<PartnerSchemeView> first = service.replaceDraftSchemes("SCH_REPLACE", List.of(
                zeropay(true), scheme("BAKONG", "INBOUND", "ISSUER")), "maker_kim");

        assertThat(first).hasSize(2);
        assertThat(first.get(0).partnerId()).isEqualTo(partnerId);
        assertThat(first.get(0).schemeId()).isEqualTo("ZEROPAY");
        assertThat(first.get(0).zeropayMerchantId()).isEqualTo("ZPM-0001");
        assertThat(first.get(0).kftcInstitutionCode()).isEqualTo("KFTC097");
        assertThat(first.get(0).partnerTypeChar()).isEqualTo("D");
        assertThat(first.get(0).approvalMethodCpm()).isEqualTo("CONFIRMATION");
        assertThat(first.get(0).enabled()).isTrue();
        assertThat(first.get(1).schemeId()).isEqualTo("BAKONG");

        List<PartnerSchemeView> second = service.replaceDraftSchemes("SCH_REPLACE", List.of(
                scheme("NAPAS_247", "BOTH", "BOTH")), "maker_kim");
        assertThat(second).hasSize(1);
        assertThat(second.get(0).schemeId()).isEqualTo("NAPAS_247");

        // SCD-6: nothing deleted — 3 rows total, the prior 2 superseded with an
        // instant EXACTLY equal to the fresh recorded_at (shared paired-write
        // instant), MICROS-truncated; their partial-unique key slots vacated.
        List<PartnerSchemeEntity> all = schemeRepository.findAll().stream()
                .filter(e -> partnerId.equals(e.getPartnerId())).toList();
        assertThat(all).hasSize(3);
        List<PartnerSchemeEntity> superseded = all.stream()
                .filter(e -> e.getSupersededAt() != null).toList();
        PartnerSchemeEntity current = all.stream()
                .filter(e -> e.getSupersededAt() == null).findFirst().orElseThrow();
        assertThat(superseded).hasSize(2);
        assertThat(superseded).allSatisfy(p -> {
            assertThat(p.getSupersededAt()).isEqualTo(current.getRecordedAt());
            assertThat(p.getCurrentSchemeKey()).isNull();
        });
        assertThat(current.getRecordedAt().getNano() % 1000).isZero();
        assertThat(current.getCurrentSchemeKey())
                .isEqualTo(partnerId + ":NAPAS_247");

        // Rehydrate path returns only the current set.
        assertThat(service.currentSchemes("SCH_REPLACE"))
                .extracting(PartnerSchemeView::schemeId).containsExactly("NAPAS_247");

        // An empty list clears all schemes (replace semantics).
        assertThat(service.replaceDraftSchemes("SCH_REPLACE", List.of(), "maker_kim")).isEmpty();
        assertThat(service.currentSchemes("SCH_REPLACE")).isEmpty();
        assertThat(schemeRepository.findAllCurrentByPartnerId(partnerId)).isEmpty();
    }

    @Test
    void resolveByLocation_carriesCountryAndDerivedFields_filtersByCountry() {
        Long krId = seedPartner("SCH_LOC_KR");
        Long vnId = seedPartner("SCH_LOC_VN");
        // Stamp operating countries on the current partner rows (the location
        // read joins on partners.operating_country).
        setOperatingCountry(krId, "KR");
        setOperatingCountry(vnId, "VN");

        // KR partner: a fully-wired ZEROPAY row (both approval methods set) +
        // a CPM-only row (only approvalMethodCpm set).
        service.replaceDraftSchemes("SCH_LOC_KR",
                List.of(zeropay(true), scheme("BAKONG", "INBOUND", "ISSUER")), "maker");
        service.replaceDraftSchemes("SCH_LOC_VN",
                List.of(scheme("NAPAS_247", "BOTH", "BOTH")), "maker");

        // Filter to KR: only the two KR enablements, each carrying countryCode +
        // derived supportsCpm/Mpm + status.
        List<PartnerSchemeView> kr = service.resolveByLocation("KR");
        assertThat(kr).extracting(PartnerSchemeView::schemeId)
                .containsExactlyInAnyOrder("ZEROPAY", "BAKONG");
        assertThat(kr).allSatisfy(v -> {
            assertThat(v.countryCode()).isEqualTo("KR");
            assertThat(v.status()).isEqualTo("ACTIVE");
        });
        PartnerSchemeView zp = kr.stream()
                .filter(v -> v.schemeId().equals("ZEROPAY")).findFirst().orElseThrow();
        // zeropay() sets both approval methods → supports both modes.
        assertThat(zp.supportsCpm()).isTrue();
        assertThat(zp.supportsMpm()).isTrue();
        PartnerSchemeView bakong = kr.stream()
                .filter(v -> v.schemeId().equals("BAKONG")).findFirst().orElseThrow();
        // scheme() leaves both approval methods null → supports neither.
        assertThat(bakong.supportsCpm()).isFalse();
        assertThat(bakong.supportsMpm()).isFalse();

        // Unknown country → empty list (no 404); null → every current enablement.
        assertThat(service.resolveByLocation("ZZ")).isEmpty();
        assertThat(service.resolveByLocation(null))
                .extracting(PartnerSchemeView::schemeId)
                .containsExactlyInAnyOrder("ZEROPAY", "BAKONG", "NAPAS_247");
    }

    @Test
    void resolveByLocation_disabledRowReportsSuspendedStatus() {
        Long id = seedPartner("SCH_LOC_SUSPEND");
        setOperatingCountry(id, "KR");
        PartnerSchemeCommand disabled = new PartnerSchemeCommand(
                "PROMPT_PAY", "OUTBOUND", "ISSUER",
                null, null, null, null, null, null, null, false);
        service.replaceDraftSchemes("SCH_LOC_SUSPEND", List.of(disabled), "maker");

        PartnerSchemeView v = service.resolveByLocation("KR").get(0);
        assertThat(v.enabled()).isFalse();
        assertThat(v.status()).isEqualTo("SUSPENDED");
    }

    @Test
    void enabledDefaultsToTrue_whenNull() {
        seedPartner("SCH_DEFAULT_ON");
        List<PartnerSchemeView> saved = service.replaceDraftSchemes("SCH_DEFAULT_ON",
                List.of(scheme("PROMPT_PAY", "OUTBOUND", "ISSUER")), "maker_kim");
        assertThat(saved.get(0).enabled()).isTrue();
    }

    @Test
    void zeropayEnabled_missingMerchantId_raisesValidationError() {
        Long partnerId = seedPartner("SCH_ZP_NO_MID");

        PartnerSchemeCommand noMerchant = new PartnerSchemeCommand(
                "ZEROPAY", "OUTBOUND", "ACQUIRER",
                null, null, "KFTC097", "D", null, null, null, true);

        assertThatThrownBy(() -> service.replaceDraftSchemes("SCH_ZP_NO_MID",
                List.of(noMerchant), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("VALIDATION_ERROR");
                    assertThat(e.getReason()).contains("schemes[0]");
                    assertThat(e.getReason()).contains("zeropayMerchantId");
                });
        // A 400 must be side-effect free: no scheme row landed.
        assertThat(schemeRepository.findAllCurrentByPartnerId(partnerId)).isEmpty();
    }

    @Test
    void zeropayEnabled_missingInstitutionCode_raisesValidationError() {
        seedPartner("SCH_ZP_NO_KFTC");

        PartnerSchemeCommand noInstitution = new PartnerSchemeCommand(
                "ZEROPAY", "OUTBOUND", "ACQUIRER",
                "ZPM-0001", null, null, "D", null, null, null, null); // enabled null = true

        assertThatThrownBy(() -> service.replaceDraftSchemes("SCH_ZP_NO_KFTC",
                List.of(noInstitution), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("VALIDATION_ERROR");
                    assertThat(e.getReason()).contains("kftcInstitutionCode");
                });
    }

    @Test
    void zeropayDisabled_incompleteWiring_savesFine_draftsMayBeIncomplete() {
        seedPartner("SCH_ZP_DISABLED");

        PartnerSchemeCommand disabledIncomplete = new PartnerSchemeCommand(
                "ZEROPAY", "OUTBOUND", "ACQUIRER",
                null, null, null, null, null, null, null, false);

        List<PartnerSchemeView> saved = service.replaceDraftSchemes("SCH_ZP_DISABLED",
                List.of(disabledIncomplete), "maker_kim");
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).enabled()).isFalse();
        assertThat(saved.get(0).zeropayMerchantId()).isNull();
    }

    @Test
    void zeropayEnabled_fullyWired_passes() {
        seedPartner("SCH_ZP_OK");
        List<PartnerSchemeView> saved = service.replaceDraftSchemes("SCH_ZP_OK",
                List.of(zeropay(true)), "maker_kim");
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).enabled()).isTrue();
    }

    @Test
    void validation_rejectsBadPayloadsWith400_withoutWritingRows() {
        Long partnerId = seedPartner("SCH_INVALID");

        record Bad(String label, List<PartnerSchemeCommand> schemes) {}
        List<Bad> bads = List.of(
                new Bad("missing schemeId", List.of(new PartnerSchemeCommand(
                        null, "OUTBOUND", "ACQUIRER",
                        null, null, null, null, null, null, null, false))),
                new Bad("unknown schemeId", List.of(new PartnerSchemeCommand(
                        "ALIPAY", "OUTBOUND", "ACQUIRER",
                        null, null, null, null, null, null, null, false))),
                new Bad("missing direction", List.of(new PartnerSchemeCommand(
                        "BAKONG", null, "ACQUIRER",
                        null, null, null, null, null, null, null, false))),
                new Bad("unknown direction", List.of(new PartnerSchemeCommand(
                        "BAKONG", "SIDEWAYS", "ACQUIRER",
                        null, null, null, null, null, null, null, false))),
                new Bad("missing role", List.of(new PartnerSchemeCommand(
                        "BAKONG", "OUTBOUND", null,
                        null, null, null, null, null, null, null, false))),
                new Bad("unknown role", List.of(new PartnerSchemeCommand(
                        "BAKONG", "OUTBOUND", "BYSTANDER",
                        null, null, null, null, null, null, null, false))),
                new Bad("bad partnerTypeChar", List.of(new PartnerSchemeCommand(
                        "BAKONG", "OUTBOUND", "ACQUIRER",
                        null, null, null, "X", null, null, null, false))),
                new Bad("bad approvalMethodCpm", List.of(new PartnerSchemeCommand(
                        "BAKONG", "OUTBOUND", "ACQUIRER",
                        null, null, null, null, null, "LOUD", null, false))),
                new Bad("bad approvalMethodMpm", List.of(new PartnerSchemeCommand(
                        "BAKONG", "OUTBOUND", "ACQUIRER",
                        null, null, null, null, null, null, "LOUD", false))),
                new Bad("merchantId over 40 chars", List.of(new PartnerSchemeCommand(
                        "BAKONG", "OUTBOUND", "ACQUIRER",
                        "X".repeat(41), null, null, null, null, null, null, false))),
                new Bad("kftc code over 20 chars", List.of(new PartnerSchemeCommand(
                        "BAKONG", "OUTBOUND", "ACQUIRER",
                        null, null, "X".repeat(21), null, null, null, null, false))),
                new Bad("vaultSecretId over 64 chars", List.of(new PartnerSchemeCommand(
                        "BAKONG", "OUTBOUND", "ACQUIRER",
                        null, null, null, null, "X".repeat(65), null, null, false))),
                new Bad("duplicate schemeId", List.of(
                        scheme("BAKONG", "OUTBOUND", "ACQUIRER"),
                        scheme("BAKONG", "INBOUND", "ISSUER"))));

        for (Bad bad : bads) {
            assertThatThrownBy(() -> service.replaceDraftSchemes(
                    "SCH_INVALID", bad.schemes(), "maker_kim"))
                    .as(bad.label())
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }

        // A 400 must be side-effect free: no scheme row landed.
        assertThat(schemeRepository.findAllCurrentByPartnerId(partnerId)).isEmpty();
    }

    @Test
    void nullList_isRejectedWith400() {
        seedPartner("SCH_NULL");
        assertThatThrownBy(() -> service.replaceDraftSchemes("SCH_NULL", null, "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void unknownPartner_404_onBothOperations() {
        assertThatThrownBy(() -> service.replaceDraftSchemes(
                "SCH_GHOST", List.of(), "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.currentSchemes("SCH_GHOST"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void partnerWithoutSchemes_returnsEmptyListOnRead() {
        seedPartner("SCH_EMPTY");
        assertThat(service.currentSchemes("SCH_EMPTY")).isEmpty();
    }

    @Test
    void nonOnboardingPartner_409_postActivationFlowIsSlice8() {
        seedPartner("SCH_LIVE");
        service.replaceDraftSchemes("SCH_LIVE", List.of(zeropay(true)), "maker_kim");

        PartnerEntity current = partnerRepository.findCurrentByPartnerCode("SCH_LIVE")
                .orElseThrow();
        current.setStatus(PartnerStatus.LIVE);
        partnerRepository.saveAndFlush(current);

        assertThatThrownBy(() -> service.replaceDraftSchemes("SCH_LIVE", List.of(),
                "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        // Reads stay open for LIVE partners (detail page tile).
        assertThat(service.currentSchemes("SCH_LIVE")).hasSize(1);
    }

    @Test
    void audit_oneEventPerWrite_withCanonicalBeforeAfterSnapshots() {
        seedPartner("SCH_AUDIT");
        publisher.clear(); // drop the PARTNER_SAVED event from the seed

        service.replaceDraftSchemes("SCH_AUDIT", List.of(zeropay(true)), "maker_kim");
        service.replaceDraftSchemes("SCH_AUDIT",
                List.of(scheme("FAST_SG", "BOTH", "BOTH")), "checker_lee");

        List<AuditEvent> events = publisher.published();
        assertThat(events).hasSize(2);

        AuditEvent first = events.get(0);
        assertThat(first.aggregateType()).isEqualTo("partner_scheme");
        assertThat(first.aggregateId()).isEqualTo("SCH_AUDIT");
        assertThat(first.eventType()).isEqualTo("PARTNER_SCHEMES_REPLACED");
        assertThat(first.actorId()).isEqualTo("maker_kim");
        assertThat(first.beforeJsonb()).as("first write — BEFORE must be null").isNull();
        assertThat(new String(first.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"schemeId\":\"ZEROPAY\"")
                .contains("\"direction\":\"OUTBOUND\"")
                .contains("\"role\":\"ACQUIRER\"")
                .contains("\"zeropayMerchantId\":\"ZPM-0001\"")
                .contains("\"kftcInstitutionCode\":\"KFTC097\"")
                .contains("\"enabled\":true");

        AuditEvent second = events.get(1);
        assertThat(second.actorId()).isEqualTo("checker_lee");
        assertThat(new String(second.beforeJsonb(), StandardCharsets.UTF_8))
                .as("BEFORE carries the superseded set")
                .contains("\"schemeId\":\"ZEROPAY\"");
        assertThat(new String(second.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"schemeId\":\"FAST_SG\"")
                .contains("\"zeropayMerchantId\":null");
    }

    // ---------------------------------------------- ADR-016 network_identifier

    /**
     * ADR-016: a seeded ZEROPAY row exposes {@code com.zeropay} and a seeded
     * NEPAL row exposes the full six-network CSV — through BOTH read surfaces
     * (the partner-schemes list {@code toView} and the {@code /resolve}
     * location read {@code toLocationView}). Derived from {@code scheme_id} on
     * insert (the write command carries no networkIdentifier field).
     */
    @Test
    void seededSchemes_exposeExpectedNetworkIdentifier_viaBothReadPaths() {
        Long id = seedPartner("SCH_NET_SEED");
        setOperatingCountry(id, "NP");
        List<PartnerSchemeView> saved = service.replaceDraftSchemes("SCH_NET_SEED",
                List.of(zeropay(true), scheme("NEPAL", "OUTBOUND", "ACQUIRER")), "maker");

        // toView path (patch response + currentSchemes rehydrate).
        assertThat(saved).filteredOn(v -> v.schemeId().equals("ZEROPAY"))
                .singleElement()
                .extracting(PartnerSchemeView::networkIdentifier)
                .isEqualTo("com.zeropay");
        assertThat(service.currentSchemes("SCH_NET_SEED"))
                .filteredOn(v -> v.schemeId().equals("NEPAL"))
                .singleElement()
                .extracting(PartnerSchemeView::networkIdentifier)
                .isEqualTo("fonepay.com,nepalpay,khalti,mobank,unionpay,smartqr");

        // toLocationView path (GET /v1/schemes/resolve).
        assertThat(service.resolveByLocation("NP"))
                .filteredOn(v -> v.schemeId().equals("ZEROPAY"))
                .singleElement()
                .extracting(PartnerSchemeView::networkIdentifier)
                .isEqualTo("com.zeropay");
    }

    /**
     * ADR-016: a row serving a network is DISCOVERABLE by that network's GUID
     * via CSV membership (the smart-router filter is a "contains", not
     * equality) — the NEPAL row fronts six networks, each individually findable;
     * a GUID it does not serve does not match.
     */
    @Test
    void schemeServingNetwork_isDiscoverableByGuidMembership() {
        Long id = seedPartner("SCH_NET_MEMBER");
        setOperatingCountry(id, "NP");
        service.replaceDraftSchemes("SCH_NET_MEMBER",
                List.of(scheme("NEPAL", "OUTBOUND", "ACQUIRER")), "maker");

        List<PartnerSchemeView> np = service.resolveByLocation("NP");
        for (String guid : List.of("fonepay.com", "nepalpay", "khalti",
                "mobank", "unionpay", "smartqr")) {
            assertThat(np).as("network %s must be discoverable via CSV membership", guid)
                    .anySatisfy(v -> assertThat(csvContains(v.networkIdentifier(), guid)).isTrue());
        }
        assertThat(np).noneSatisfy(v ->
                assertThat(csvContains(v.networkIdentifier(), "com.zeropay")).isTrue());
    }

    /** A scheme with no network mapping keeps a null identifier (null-safe). */
    @Test
    void unmappedScheme_exposesNullNetworkIdentifier() {
        seedPartner("SCH_NET_UNMAPPED");
        List<PartnerSchemeView> saved = service.replaceDraftSchemes("SCH_NET_UNMAPPED",
                List.of(scheme("BAKONG", "INBOUND", "ISSUER")), "maker");
        assertThat(saved.get(0).networkIdentifier()).isNull();
    }

    /** Membership test mirroring smart-router's CSV "contains" (null-safe). */
    private static boolean csvContains(String csv, String guid) {
        if (csv == null) {
            return false;
        }
        for (String token : csv.split(",")) {
            if (token.trim().equals(guid)) {
                return true;
            }
        }
        return false;
    }
}
