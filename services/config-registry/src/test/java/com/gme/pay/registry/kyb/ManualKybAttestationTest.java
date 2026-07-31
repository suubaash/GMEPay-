package com.gme.pay.registry.kyb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.AuditActors;
import com.gme.pay.audit.AuditPublisher;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.KybCommand;
import com.gme.pay.contracts.KybView;
import com.gme.pay.contracts.UboView;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.audit.AuditLogRepository;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
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
 * The MANUAL KYB SOP screening authority (gap T1-4, owner decision 2026-07-28), end-to-end against
 * H2 in PostgreSQL mode with the real Flyway chain through V045 — so the V045 CHECKs are live and
 * every write here has to survive them as well as the Java rules.
 *
 * <h2>What this pins</h2>
 *
 * <ol>
 *   <li>An ATTESTED manual screening is authoritative, stores {@code CLEAR_MANUAL_ATTESTATION}
 *       (never a bare {@code CLEAR}), and satisfies the activation sanctions pre-condition.</li>
 *   <li>An UNATTESTED one does not exist: no verified attester ⇒ 403; no SOP reference, no SOP
 *       version, no sources, no typed assertion ⇒ 400. Nothing is written in any of those cases.</li>
 *   <li>A stub screening still satisfies nothing — the T1-4 coercion is untouched.</li>
 *   <li>The attestation is AUDITED under the attester, on the hash-chained trail, and the chain
 *       verifies (i.e. the sealed snapshot includes the attestation, so it is tamper-evident).</li>
 *   <li>A step-3 save carries the attestation forward, and a stub re-screen cannot silently
 *       destroy it.</li>
 * </ol>
 *
 * <p>Wiring mirrors {@link KybServiceTest} (the same {@code @DataJpaTest} slice, the same
 * in-process {@link StubKybClient} seam local dev runs) plus {@link AuditLogRepository} so the
 * audit rows can be read back and the chain verified.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ManualKybAttestationTest.TestConfig.class, KybService.class, StubKybClient.class,
        StubKybVerifyClient.class, AuditLogService.class, PartnerStore.class, CacheConfig.class})
class ManualKybAttestationTest {

    private static final String ATTESTER = "compliance.officer@gme.com";
    private static final String SOP_REF = "GME-COMP-SOP-014 Manual sanctions screening";
    private static final String SOP_VERSION = "v3";
    private static final String SOURCES =
            "UN consolidated list and the destination-jurisdiction lists named in SOP §4, searched"
            + " by romanized and local legal name plus every declared UBO";

    @Autowired
    private KybService service;

    @Autowired
    private KybRepository kybRepository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        RecordingAuditPublisher recordingAuditPublisher() {
            return new RecordingAuditPublisher();
        }

        @Bean
        AuditPublisher auditPublisher(RecordingAuditPublisher recording) {
            return recording;
        }
    }

    // ------------------------------------------------------------------ helpers

    private void seedPartner(String code, String legalName) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
        PartnerEntity current = partnerRepository.findCurrentByPartnerCode(code).orElseThrow();
        current.setLegalNameRomanized(legalName);
        partnerRepository.saveAndFlush(current);
    }

    private static ManualAttestationCommand cmd(String outcome) {
        return new ManualAttestationCommand(outcome, SOP_REF, SOP_VERSION, SOURCES,
                ManualAttestationCommand.REQUIRED_ASSERTION);
    }

    private KybEntity currentRow(String code) {
        Long id = partnerRepository.findCurrentByPartnerCode(code).orElseThrow().getId();
        return kybRepository.findCurrentByPartnerId(id).orElseThrow();
    }

    private static KybCommand.UpdateStep3 step3(String riskRating) {
        return new KybCommand.UpdateStep3(riskRating, "rated " + riskRating,
                LocalDate.of(2027, 6, 1), "REMITTANCE", "RL-2026-0042", "Bank of Korea",
                LocalDate.of(2028, 12, 31),
                List.of(new UboView("Hong Gil Dong", new BigDecimal("60"), false, "KR")),
                null);
    }

    // ---------------------------------------------------------------- the happy path

    @Test
    @DisplayName("an attested manual screening is authoritative and satisfies the sanctions gate")
    void attestedManualScreeningIsAuthoritative() {
        seedPartner("MAN_OK", "ACME REMIT CO LTD");
        service.upsertStep3("MAN_OK", step3("LOW"), AuditActors.attested("maker.kim@gme.com"));

        KybView view = service.recordManualScreeningAttestation("MAN_OK", cmd("CLEAR"),
                AuditActors.attested(ATTESTER));

        // The status carries the provenance: a manual clearance is NEVER a bare CLEAR.
        assertThat(view.screeningStatus()).isEqualTo("CLEAR_MANUAL_ATTESTATION");

        KybEntity row = currentRow("MAN_OK");
        assertThat(row.getScreeningProviderId()).isEqualTo("manual-sop");
        assertThat(row.getScreeningAuthoritative()).isTrue();
        assertThat(row.getScreeningCaveat()).isNull();
        assertThat(row.getManualAttesterActorId()).isEqualTo(ATTESTER);
        assertThat(row.getManualAttestedAt()).isNotNull();
        assertThat(row.getManualSopDocumentRef()).isEqualTo(SOP_REF);
        assertThat(row.getManualSopVersion()).isEqualTo(SOP_VERSION);
        assertThat(row.getManualSourcesConsulted()).isEqualTo(SOURCES);
        assertThat(row.getScreeningProviderRef()).contains(SOP_VERSION);

        // The two predicates the activation gate uses.
        assertThat(row.hasAuthoritativeScreening()).isTrue();
        assertThat(row.hasCompleteManualAttestation()).isTrue();
        assertThat(row.screeningIsClear()).isTrue();
        assertThat(row.isManuallyAttestedScreening()).isTrue();

        // Step-3 fields survive the attestation write (it is not a full-state replace).
        assertThat(row.getRiskRating()).isEqualTo("LOW");
    }

    @Test
    @DisplayName("the provenance read model distinguishes a manual clearance from a vendor one")
    void provenanceReadModelIsExplicit() {
        seedPartner("MAN_PROV", "ACME REMIT CO LTD");
        service.recordManualScreeningAttestation("MAN_PROV", cmd("CLEAR"),
                AuditActors.attested(ATTESTER));

        ScreeningProvenanceView p = service.currentScreeningProvenance("MAN_PROV");

        assertThat(p.screeningStatus()).isEqualTo("CLEAR_MANUAL_ATTESTATION");
        assertThat(p.providerId()).isEqualTo("manual-sop");
        assertThat(p.authoritative()).isTrue();
        assertThat(p.manuallyAttested()).isTrue();
        assertThat(p.satisfiesActivation()).isTrue();
        assertThat(p.manualAttestation()).isNotNull();
        assertThat(p.manualAttestation().attesterActorId()).isEqualTo(ATTESTER);
        assertThat(p.manualAttestation().complete()).isTrue();
        // It must say what it is, and must not read as a vendor screening.
        assertThat(p.interpretation())
                .contains("MANUALLY")
                .contains(ATTESTER)
                .contains(SOP_VERSION)
                .contains("NOT a vendor screening");
    }

    @Test
    @DisplayName("a manual HIT is recordable, keeps its verdict, and is not 'clear'")
    void manualHitIsRecordable() {
        seedPartner("MAN_HIT", "ACME REMIT CO LTD");

        KybView view = service.recordManualScreeningAttestation("MAN_HIT", cmd("HIT"),
                AuditActors.attested(ATTESTER));

        assertThat(view.screeningStatus()).isEqualTo("HIT");
        KybEntity row = currentRow("MAN_HIT");
        assertThat(row.hasAuthoritativeScreening()).isTrue();
        assertThat(row.screeningIsClear()).isFalse();
        assertThat(row.hasCompleteManualAttestation()).isTrue();
    }

    // ---------------------------------------------------------------- refusals

    @Test
    @DisplayName("an unverified, service or unattributed actor cannot attest — 403, nothing written")
    void onlyAVerifiedHumanCanAttest() {
        seedPartner("MAN_NOACTOR", "ACME REMIT CO LTD");

        for (String notAPerson : List.of(
                AuditActors.unverified(ATTESTER),
                AuditActors.service("internal-caller"),
                AuditActors.system("migration-v045"),
                AuditActors.UNATTRIBUTED)) {
            assertThatThrownBy(() -> service.recordManualScreeningAttestation(
                    "MAN_NOACTOR", cmd("CLEAR"), notAPerson))
                    .as("%s must not be able to attest", notAPerson)
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThatThrownBy(() -> service.recordManualScreeningAttestation(
                "MAN_NOACTOR", cmd("CLEAR"), null))
                .isInstanceOf(ResponseStatusException.class);

        Long id = partnerRepository.findCurrentByPartnerCode("MAN_NOACTOR").orElseThrow().getId();
        assertThat(kybRepository.findCurrentByPartnerId(id))
                .as("a refused attestation must leave no KYB row behind")
                .isEmpty();
    }

    @Test
    @DisplayName("an attestation with no SOP reference or version is refused — 400, nothing written")
    void sopReferenceIsMandatory() {
        seedPartner("MAN_NOSOP", "ACME REMIT CO LTD");

        List<ManualAttestationCommand> incomplete = List.of(
                new ManualAttestationCommand("CLEAR", null, SOP_VERSION, SOURCES,
                        ManualAttestationCommand.REQUIRED_ASSERTION),
                new ManualAttestationCommand("CLEAR", "   ", SOP_VERSION, SOURCES,
                        ManualAttestationCommand.REQUIRED_ASSERTION),
                new ManualAttestationCommand("CLEAR", SOP_REF, null, SOURCES,
                        ManualAttestationCommand.REQUIRED_ASSERTION),
                new ManualAttestationCommand("CLEAR", SOP_REF, SOP_VERSION, null,
                        ManualAttestationCommand.REQUIRED_ASSERTION));
        for (ManualAttestationCommand bad : incomplete) {
            assertThatThrownBy(() -> service.recordManualScreeningAttestation(
                    "MAN_NOSOP", bad, AuditActors.attested(ATTESTER)))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        Long id = partnerRepository.findCurrentByPartnerCode("MAN_NOSOP").orElseThrow().getId();
        assertThat(kybRepository.findCurrentByPartnerId(id)).isEmpty();
    }

    @Test
    @DisplayName("the typed assertion must be sent verbatim — it cannot be defaulted or omitted")
    void assertionMustBeSentVerbatim() {
        seedPartner("MAN_NOASSERT", "ACME REMIT CO LTD");

        for (String bad : List.of("", "   ", "yes", "I attest")) {
            assertThatThrownBy(() -> service.recordManualScreeningAttestation("MAN_NOASSERT",
                    new ManualAttestationCommand("CLEAR", SOP_REF, SOP_VERSION, SOURCES, bad),
                    AuditActors.attested(ATTESTER)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("attestation field must be exactly");
        }
        assertThatThrownBy(() -> service.recordManualScreeningAttestation("MAN_NOASSERT",
                new ManualAttestationCommand("CLEAR", SOP_REF, SOP_VERSION, SOURCES, null),
                AuditActors.attested(ATTESTER)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("only CLEAR / HIT / NEEDS_REVIEW may be attested; the stored spelling is not an input")
    void outcomeRoster() {
        seedPartner("MAN_OUTCOME", "ACME REMIT CO LTD");

        for (String bad : List.of("NOT_SCREENED_NO_PROVIDER", "CLEAR_MANUAL_ATTESTATION",
                "APPROVED", "")) {
            assertThatThrownBy(() -> service.recordManualScreeningAttestation(
                    "MAN_OUTCOME", cmdWithOutcome(bad), AuditActors.attested(ATTESTER)))
                    .as("outcome '%s' must be refused", bad)
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
        // ...and lower case is accepted (an operator-typed value), because refusing on case would
        // be pedantry rather than a control.
        service.recordManualScreeningAttestation("MAN_OUTCOME", cmdWithOutcome("clear"),
                AuditActors.attested(ATTESTER));
        assertThat(currentRow("MAN_OUTCOME").getScreeningStatus())
                .isEqualTo("CLEAR_MANUAL_ATTESTATION");
    }

    private static ManualAttestationCommand cmdWithOutcome(String outcome) {
        return new ManualAttestationCommand(outcome, SOP_REF, SOP_VERSION, SOURCES,
                ManualAttestationCommand.REQUIRED_ASSERTION);
    }

    @Test
    @DisplayName("a stub screening still satisfies nothing — the T1-4 coercion is untouched")
    void stubScreeningIsStillNotAnAuthority() {
        seedPartner("MAN_STUB", "ACME REMIT CO LTD");

        service.runScreening("MAN_STUB", AuditActors.attested("maker.kim@gme.com"));

        KybEntity row = currentRow("MAN_STUB");
        assertThat(row.getScreeningStatus()).isEqualTo("NOT_SCREENED_NO_PROVIDER");
        assertThat(row.hasAuthoritativeScreening()).isFalse();
        assertThat(row.isManuallyAttestedScreening()).isFalse();
        assertThat(row.hasCompleteManualAttestation()).isFalse();
        assertThat(service.currentScreeningProvenance("MAN_STUB").satisfiesActivation()).isFalse();
        assertThat(service.currentScreeningProvenance("MAN_STUB").interpretation())
                .contains("NOTHING WAS SCREENED");
    }

    // ---------------------------------------------------------------- audit

    @Test
    @DisplayName("the attestation is audited under the attester and the hash chain verifies")
    void attestationIsAuditedAndChainVerifies() {
        seedPartner("MAN_AUDIT", "ACME REMIT CO LTD");
        service.upsertStep3("MAN_AUDIT", step3("MEDIUM"),
                AuditActors.attested("maker.kim@gme.com"));
        service.recordManualScreeningAttestation("MAN_AUDIT", cmd("CLEAR"),
                AuditActors.attested(ATTESTER));

        var rows = auditLogRepository.findChainByAggregate(KybService.AGGREGATE_TYPE, "MAN_AUDIT");
        assertThat(rows).hasSize(2);
        var attestationRow = rows.get(rows.size() - 1);
        assertThat(attestationRow.getEventType())
                .isEqualTo(KybService.EVENT_TYPE_MANUAL_ATTESTED);
        // Attributed to the human, not to whatever service carried the request.
        assertThat(attestationRow.getActorId()).isEqualTo(ATTESTER);
        assertThat(AuditActors.isAttestedHuman(attestationRow.getActorId())).isTrue();

        // The sealed AFTER snapshot contains the attestation, so editing the attester or the SOP
        // version in place breaks the chain rather than going unnoticed.
        String after = new String(attestationRow.toDomain().afterJsonb(), StandardCharsets.UTF_8);
        assertThat(after)
                .contains("\"manualAttesterActorId\":\"" + ATTESTER + "\"")
                .contains("\"manualSopVersion\":\"" + SOP_VERSION + "\"")
                .contains("\"screeningStatus\":\"CLEAR_MANUAL_ATTESTATION\"");
        assertThat(after).contains("\"manualSourcesConsulted\"");

        assertThat(auditLogService.verifyChain(KybService.AGGREGATE_TYPE, "MAN_AUDIT"))
                .as("the attestation must survive the tamper check")
                .isEqualTo(-1L);
    }

    // ---------------------------------------------------------------- durability

    @Test
    @DisplayName("a step-3 save carries the attestation forward instead of stripping it")
    void step3SaveCarriesTheAttestationForward() {
        seedPartner("MAN_CARRY", "ACME REMIT CO LTD");
        service.recordManualScreeningAttestation("MAN_CARRY", cmd("CLEAR"),
                AuditActors.attested(ATTESTER));

        // A full-state replace of the operator-editable fields. Without the carry-forward this
        // would either strip the attestation or (worse) be refused by the V045 CHECK.
        service.upsertStep3("MAN_CARRY", step3("MEDIUM"),
                AuditActors.attested("maker.kim@gme.com"));

        KybEntity row = currentRow("MAN_CARRY");
        assertThat(row.getRiskRating()).isEqualTo("MEDIUM");
        assertThat(row.getScreeningStatus()).isEqualTo("CLEAR_MANUAL_ATTESTATION");
        assertThat(row.getManualAttesterActorId()).isEqualTo(ATTESTER);
        assertThat(row.hasCompleteManualAttestation()).isTrue();
        assertThat(row.hasAuthoritativeScreening()).isTrue();
    }

    @Test
    @DisplayName("an unscreened re-run cannot silently destroy a manual attestation — 409")
    void stubRescreenCannotDestroyTheAttestation() {
        seedPartner("MAN_RESCREEN", "ACME REMIT CO LTD");
        service.recordManualScreeningAttestation("MAN_RESCREEN", cmd("CLEAR"),
                AuditActors.attested(ATTESTER));

        assertThatThrownBy(() -> service.runScreening("MAN_RESCREEN",
                AuditActors.attested("maker.kim@gme.com")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThatThrownBy(() -> service.runVerification("MAN_RESCREEN", List.of(), false,
                AuditActors.attested("maker.kim@gme.com")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        // The attestation is intact and still activating.
        KybEntity row = currentRow("MAN_RESCREEN");
        assertThat(row.getScreeningStatus()).isEqualTo("CLEAR_MANUAL_ATTESTATION");
        assertThat(row.hasAuthoritativeScreening()).isTrue();

        // Re-attesting IS allowed — that is the documented way forward.
        service.recordManualScreeningAttestation("MAN_RESCREEN", cmd("CLEAR"),
                AuditActors.attested(ATTESTER));
        assertThat(currentRow("MAN_RESCREEN").getScreeningStatus())
                .isEqualTo("CLEAR_MANUAL_ATTESTATION");
    }

    @Test
    @DisplayName("attesting an unknown partner is 404, not a row created out of nothing")
    void unknownPartnerIs404() {
        assertThatThrownBy(() -> service.recordManualScreeningAttestation(
                "NO_SUCH_PARTNER", cmd("CLEAR"), AuditActors.attested(ATTESTER)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
