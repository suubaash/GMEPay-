package com.gme.pay.registry.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.kyb.ScreeningProvenance;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.bank.BankAccountEntity;
import com.gme.pay.registry.bank.BankAccountRepository;
import com.gme.pay.registry.bank.BankVerificationStatus;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.commercial.ContractEntity;
import com.gme.pay.registry.commercial.ContractRepository;
import com.gme.pay.registry.contact.ContactEntity;
import com.gme.pay.registry.contact.ContactRepository;
import com.gme.pay.registry.contact.ContactRole;
import com.gme.pay.registry.kyb.KybEntity;
import com.gme.pay.registry.kyb.KybRepository;
import com.gme.pay.registry.lifecycle.ActivationGateService.ActivationGateResult;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import com.gme.pay.registry.prefunding.PrefundingConfigEntity;
import com.gme.pay.registry.prefunding.PrefundingConfigRepository;
import com.gme.pay.registry.scheme.PartnerSchemeEntity;
import com.gme.pay.registry.scheme.PartnerSchemeRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * Slice 8 acceptance test for {@link ActivationGateService} — every unmet
 * code, the all-clear path, and the operator-override paths, wired end-to-end
 * against H2 in PostgreSQL mode with the full Flyway chain (V001..V025).
 *
 * <p>Each test seeds a fully-activatable partner via {@link #seedAllClear} and
 * then breaks exactly ONE pre-condition, asserting the gate reports exactly
 * that code — so a regression in any single check cannot hide behind another.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ActivationGateService.class, PartnerStore.class, CacheConfig.class})
class ActivationGateServiceTest {

    @Autowired
    private ActivationGateService gate;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private KybRepository kybRepository;

    @Autowired
    private BankAccountRepository bankAccountRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private PrefundingConfigRepository prefundingConfigRepository;

    @Autowired
    private PartnerSchemeRepository schemeRepository;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    // ------------------------------------------------------------- seeding

    private PartnerEntity seedPartner(String code, PartnerType type) {
        partnerStore.save(Partner.of(code, type, "USD", RoundingMode.HALF_UP));
        PartnerEntity entity = partnerRepository.findCurrentByPartnerCode(code).orElseThrow();
        entity.setLegalNameLocal("지엠이 " + code);
        entity.setLegalNameRomanized("GME " + code + " Co., Ltd.");
        return partnerRepository.saveAndFlush(entity);
    }

    /** Seed a partner satisfying EVERY activation pre-condition. */
    private PartnerEntity seedAllClear(String code) {
        PartnerEntity partner = seedPartner(code, PartnerType.OVERSEAS);
        addKyb(partner, "MEDIUM", null, "CLEAR");
        addBankAccount(partner, "USD", BankVerificationStatus.BANK_LETTER);
        addContract(partner, LocalDate.now().minusDays(1), Instant.now());
        addPrefunding(partner);
        addContacts(partner, ContactRole.OPS_24X7, ContactRole.FINANCE,
                ContactRole.COMPLIANCE_MLRO, ContactRole.TECH);
        addScheme(partner, true);
        return partner;
    }

    /**
     * Seed a KYB row whose screening came from a REAL provider — the shape a
     * screening-capable environment produces. T1-4: the provenance is mandatory,
     * because a bare {@code CLEAR} is no longer a passed sanctions check (and the
     * V042 CHECK {@code ck_partner_kyb_clear_requires_authority} refuses to store
     * one).
     */
    private void addKyb(PartnerEntity partner, String riskRating,
                        String riskRationale, String screeningStatus) {
        addKyb(partner, riskRating, riskRationale, screeningStatus, "octa-test", true, null);
    }

    /** Seed the stub-produced shape: nothing was screened. */
    private void addUnscreenedKyb(PartnerEntity partner, String riskRating, String riskRationale) {
        addKyb(partner, riskRating, riskRationale, "NOT_SCREENED_NO_PROVIDER",
                "stub", false, ScreeningProvenance.STUB_CAVEAT);
    }

    private void addKyb(PartnerEntity partner, String riskRating, String riskRationale,
                        String screeningStatus, String providerId, boolean authoritative,
                        String caveat) {
        KybEntity kyb = new KybEntity();
        kyb.setPartnerId(partner.getId());
        kyb.setRiskRating(riskRating);
        kyb.setRiskRationale(riskRationale);
        kyb.setScreeningStatus(screeningStatus);
        kyb.setScreeningProviderId(providerId);
        kyb.setScreeningAuthoritative(authoritative);
        kyb.setScreeningCaveat(caveat);
        kyb.setScreenedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        kybRepository.saveAndFlush(kyb);
    }

    private void addBankAccount(PartnerEntity partner, String currency,
                                BankVerificationStatus status) {
        BankAccountEntity account = new BankAccountEntity();
        account.setPartnerId(partner.getId());
        account.setCurrency(currency);
        account.setBankName("Standard Chartered");
        account.setIbanOrAccountNumber("0123456789");
        account.setAccountHolderName("GME Remit");
        account.setBankCountry("SG");
        account.setVerificationStatus(status);
        bankAccountRepository.saveAndFlush(account);
    }

    private void addContract(PartnerEntity partner, LocalDate effectiveFrom, Instant signedAt) {
        ContractEntity contract = new ContractEntity();
        contract.setPartnerId(partner.getId());
        contract.setEffectiveFrom(effectiveFrom);
        contract.setSignedAt(signedAt == null
                ? null : signedAt.truncatedTo(ChronoUnit.MICROS));
        contractRepository.saveAndFlush(contract);
    }

    private void addPrefunding(PartnerEntity partner) {
        PrefundingConfigEntity prefunding = new PrefundingConfigEntity();
        prefunding.setPartnerId(partner.getId());
        prefunding.setFundingModel("PREFUNDED");
        prefunding.setLowBalanceThresholdUsd(new BigDecimal("10000.0000"));
        prefundingConfigRepository.saveAndFlush(prefunding);
    }

    private void addContacts(PartnerEntity partner, ContactRole... roles) {
        for (ContactRole role : roles) {
            ContactEntity contact = new ContactEntity();
            contact.setPartnerId(partner.getId());
            contact.setRole(role);
            contact.setName("Contact " + role);
            contact.setEmail(role.name().toLowerCase() + "@partner.example");
            contactRepository.saveAndFlush(contact);
        }
    }

    private void addScheme(PartnerEntity partner, boolean enabled) {
        PartnerSchemeEntity scheme = new PartnerSchemeEntity();
        scheme.setPartnerId(partner.getId());
        scheme.setSchemeId("ZEROPAY");
        scheme.setDirection("OUTBOUND");
        scheme.setRole("ACQUIRER");
        scheme.setEnabled(enabled);
        schemeRepository.saveAndFlush(scheme);
    }

    private static java.util.List<String> descriptions(ActivationGateResult result) {
        return result.unmet().stream()
                .map(ActivationGateService.UnmetCondition::description).toList();
    }

    private static java.util.List<String> codes(ActivationGateResult result) {
        return result.unmet().stream()
                .map(ActivationGateService.UnmetCondition::code).toList();
    }

    // ------------------------------------------------------------- tests

    @Test
    @DisplayName("all pre-conditions satisfied -> passes with empty unmet list")
    void allClear_passes() {
        PartnerEntity partner = seedAllClear("gate_clear_01");
        ActivationGateResult result = gate.check(partner);
        assertThat(result.unmet()).isEmpty();
        assertThat(result.passes()).isTrue();
    }

    @Test
    @DisplayName("missing legal names -> LEGAL_NAME_MISSING")
    void legalNameMissing() {
        PartnerEntity partner = seedAllClear("gate_name_01");
        partner.setLegalNameRomanized(null);
        partnerRepository.saveAndFlush(partner);

        ActivationGateResult result = gate.check(partner);
        assertThat(result.passes()).isFalse();
        assertThat(codes(result)).containsExactly(ActivationGateService.LEGAL_NAME_MISSING);
    }

    @Test
    @DisplayName("no KYB row -> KYB_NOT_APPROVED and SANCTIONS_NOT_SCREENED")
    void kybRowMissing() {
        PartnerEntity partner = seedAllClear("gate_kyb_01");
        kybRepository.findCurrentByPartnerId(partner.getId()).ifPresent(k -> {
            k.setSupersededAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
            kybRepository.saveAndFlush(k);
        });

        ActivationGateResult result = gate.check(partner);
        assertThat(codes(result)).containsExactlyInAnyOrder(
                ActivationGateService.KYB_NOT_APPROVED,
                ActivationGateService.SANCTIONS_NOT_SCREENED);
    }

    // ------------------------------------------- T1-4: unscreened activation

    @Test
    @DisplayName("T1-4: a stub-derived KYB row cannot satisfy the sanctions pre-condition")
    void unscreenedKyb_refusesActivation() {
        PartnerEntity partner = seedPartner("gate_unscreened_01", PartnerType.OVERSEAS);
        addUnscreenedKyb(partner, "MEDIUM", null);
        addBankAccount(partner, "USD", BankVerificationStatus.BANK_LETTER);
        addContract(partner, LocalDate.now().minusDays(1), Instant.now());
        addPrefunding(partner);
        addContacts(partner, ContactRole.OPS_24X7, ContactRole.FINANCE,
                ContactRole.COMPLIANCE_MLRO, ContactRole.TECH);
        addScheme(partner, true);

        ActivationGateResult result = gate.check(partner);
        assertThat(result.passes()).isFalse();
        assertThat(codes(result)).containsExactly(ActivationGateService.SANCTIONS_NOT_SCREENED);
        assertThat(result.unscreenedBasis())
                .as("the gate refused, so there is no unscreened basis to record")
                .isNull();
        assertThat(descriptions(result).get(0))
                .contains("NOT AUTHORITATIVE")
                .contains("No operator override can satisfy this condition");
    }

    @Test
    @DisplayName("T1-4: a risk rationale cannot override a screening that never ran")
    void unscreenedKyb_isNotOverridableByRationale() {
        PartnerEntity partner = seedPartner("gate_unscreened_02", PartnerType.OVERSEAS);
        // A rationale that would legitimately override a HIT…
        addUnscreenedKyb(partner, "MEDIUM",
                "Compliance committee sign-off 2026-06-01, EDD on file");
        addBankAccount(partner, "USD", BankVerificationStatus.BANK_LETTER);
        addContract(partner, LocalDate.now().minusDays(1), Instant.now());
        addPrefunding(partner);
        addContacts(partner, ContactRole.OPS_24X7, ContactRole.FINANCE,
                ContactRole.COMPLIANCE_MLRO, ContactRole.TECH);
        addScheme(partner, true);

        // …does not manufacture a screening.
        assertThat(codes(gate.check(partner)))
                .containsExactly(ActivationGateService.SANCTIONS_NOT_SCREENED);
    }

    @Test
    @DisplayName("T1-4: the database itself refuses a CLEAR that names no authority")
    void clearWithoutAuthority_isRejectedByTheDatabase() {
        PartnerEntity partner = seedPartner("gate_unscreened_03", PartnerType.OVERSEAS);
        KybEntity kyb = new KybEntity();
        kyb.setPartnerId(partner.getId());
        kyb.setRiskRating("LOW");
        kyb.setScreeningStatus("CLEAR");
        // No provenance at all — exactly what a psql UPDATE or a legacy writer does.
        kyb.setScreenedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));

        assertThatThrownBy(() -> kybRepository.saveAndFlush(kyb))
                .as("V042 ck_partner_kyb_clear_requires_authority must reject it")
                .isInstanceOf(Exception.class);
    }

    // ---------------------------------- T1-4 owner decision: manual KYB SOP authority

    /**
     * Seed the row an attested MANUAL screening produces — the interim authority the owner chose
     * (2026-07-28). Note the V045 CHECKs are live in this slice, so a partially-attested row
     * cannot even be seeded through this helper; the incomplete case below has to break the row
     * AFTER insert, which is exactly how a legacy row / restored dump / hand-edit would present.
     */
    private void addManuallyAttestedKyb(PartnerEntity partner, String riskRating,
                                        String screeningStatus) {
        KybEntity kyb = new KybEntity();
        kyb.setPartnerId(partner.getId());
        kyb.setRiskRating(riskRating);
        kyb.setScreeningStatus(screeningStatus);
        kyb.setScreeningProviderId(KybEntity.MANUAL_ATTESTATION_PROVIDER_ID);
        kyb.setScreeningAuthoritative(true);
        kyb.setScreenedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        kyb.setManualAttesterActorId("compliance.officer@gme.com");
        kyb.setManualAttestedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        kyb.setManualSopDocumentRef("GME-COMP-SOP-014");
        kyb.setManualSopVersion("v3");
        kyb.setManualSourcesConsulted("UN consolidated list + SOP §4 jurisdiction lists");
        kybRepository.saveAndFlush(kyb);
    }

    private PartnerEntity seedAllClearExceptKyb(String code) {
        PartnerEntity partner = seedPartner(code, PartnerType.OVERSEAS);
        addBankAccount(partner, "USD", BankVerificationStatus.BANK_LETTER);
        addContract(partner, LocalDate.now().minusDays(1), Instant.now());
        addPrefunding(partner);
        addContacts(partner, ContactRole.OPS_24X7, ContactRole.FINANCE,
                ContactRole.COMPLIANCE_MLRO, ContactRole.TECH);
        addScheme(partner, true);
        return partner;
    }

    @Test
    @DisplayName("T1-4: an attested manual screening satisfies the sanctions pre-condition")
    void attestedManualScreening_passesTheGate() {
        PartnerEntity partner = seedAllClearExceptKyb("gate_manual_01");
        addManuallyAttestedKyb(partner, "MEDIUM", KybEntity.SCREENING_CLEAR_MANUAL_ATTESTATION);

        ActivationGateResult result = gate.check(partner);

        assertThat(result.passes()).isTrue();
        assertThat(result.unmet()).isEmpty();
        assertThat(result.unscreenedBasis())
                .as("a manual attestation IS a screening — nothing unscreened to record")
                .isNull();
    }

    @Test
    @DisplayName("T1-4: a manual HIT still needs an override note, like any other HIT")
    void manualHit_stillNeedsAnOverrideNote() {
        PartnerEntity partner = seedAllClearExceptKyb("gate_manual_05");
        addManuallyAttestedKyb(partner, "MEDIUM", "HIT");

        assertThat(codes(gate.check(partner)))
                .containsExactly(ActivationGateService.SANCTIONS_NOT_CLEAR);
    }

    /**
     * The database will not let an incomplete manual attestation exist at all — not even through a
     * native UPDATE that bypasses JPA entirely, which is the route a hand-edit or a backfill would
     * take. Each of the five columns is load-bearing.
     *
     * <p>The gate's own {@code SANCTIONS_MANUAL_ATTESTATION_INCOMPLETE} check therefore cannot be
     * exercised from here; it guards rows this schema cannot produce (a pre-V045 row, an older
     * dump, a future migration) and is pinned by
     * {@code ActivationGateManualAttestationTest} against a stubbed repository instead.
     */
    @Test
    @DisplayName("T1-4: the database refuses an incomplete manual attestation, column by column")
    void incompleteManualAttestation_isRejectedByTheDatabase() {
        PartnerEntity partner = seedAllClearExceptKyb("gate_manual_06");
        addManuallyAttestedKyb(partner, "MEDIUM", KybEntity.SCREENING_CLEAR_MANUAL_ATTESTATION);

        for (String column : new String[] {
                "manual_attester_actor_id", "manual_attested_at", "manual_sop_document_ref",
                "manual_sop_version", "manual_sources_consulted"}) {
            assertThatThrownBy(() -> nullOutAttestationColumn(partner, column))
                    .as("V045 must refuse to let %s be nulled out under a manual clearance", column)
                    .isInstanceOf(Exception.class);
        }
    }

    private void nullOutAttestationColumn(PartnerEntity partner, String column) {
        em.createNativeQuery("UPDATE partner_kyb SET " + column
                        + " = NULL WHERE partner_id = :pid AND superseded_at IS NULL")
                .setParameter("pid", partner.getId())
                .executeUpdate();
        em.flush();
    }

    @Test
    @DisplayName("HIGH risk rating without override note -> KYB_NOT_APPROVED")
    void highRiskWithoutOverride() {
        PartnerEntity partner = seedAllClear("gate_kyb_02");
        KybEntity kyb = kybRepository.findCurrentByPartnerId(partner.getId()).orElseThrow();
        kyb.setRiskRating("HIGH");
        kyb.setRiskRationale(null);
        kybRepository.saveAndFlush(kyb);

        assertThat(codes(gate.check(partner)))
                .containsExactly(ActivationGateService.KYB_NOT_APPROVED);
    }

    @Test
    @DisplayName("HIGH risk rating WITH documented override note -> passes (override path)")
    void highRiskWithOverridePasses() {
        PartnerEntity partner = seedAllClear("gate_kyb_03");
        KybEntity kyb = kybRepository.findCurrentByPartnerId(partner.getId()).orElseThrow();
        kyb.setRiskRating("HIGH");
        kyb.setRiskRationale("Compliance committee sign-off 2026-06-01, EDD on file");
        kybRepository.saveAndFlush(kyb);

        assertThat(gate.check(partner).passes()).isTrue();
    }

    @Test
    @DisplayName("screening HIT without override note -> SANCTIONS_NOT_CLEAR")
    void sanctionsHitWithoutOverride() {
        PartnerEntity partner = seedAllClear("gate_scr_01");
        KybEntity kyb = kybRepository.findCurrentByPartnerId(partner.getId()).orElseThrow();
        kyb.setScreeningStatus("HIT");
        kyb.setRiskRationale(null);
        kybRepository.saveAndFlush(kyb);

        assertThat(codes(gate.check(partner)))
                .containsExactly(ActivationGateService.SANCTIONS_NOT_CLEAR);
    }

    @Test
    @DisplayName("screening NEEDS_REVIEW with override note -> passes (override path)")
    void sanctionsNeedsReviewWithOverridePasses() {
        PartnerEntity partner = seedAllClear("gate_scr_02");
        KybEntity kyb = kybRepository.findCurrentByPartnerId(partner.getId()).orElseThrow();
        kyb.setScreeningStatus("NEEDS_REVIEW");
        kyb.setRiskRationale("False positive — name collision, cleared by MLRO");
        kybRepository.saveAndFlush(kyb);

        assertThat(gate.check(partner).passes()).isTrue();
    }

    @Test
    @DisplayName("no VERIFIED account in settle_a_ccy -> BANK_ACCOUNT_UNVERIFIED")
    void bankAccountUnverified() {
        PartnerEntity partner = seedAllClear("gate_bank_01");
        // Demote the USD account to UNVERIFIED and add a VERIFIED account in
        // the WRONG currency — neither satisfies the check.
        BankAccountEntity usd = bankAccountRepository
                .findCurrentByPartnerId(partner.getId()).get(0);
        usd.setVerificationStatus(BankVerificationStatus.UNVERIFIED);
        bankAccountRepository.saveAndFlush(usd);
        addBankAccount(partner, "KRW", BankVerificationStatus.KFTC_VERIFIED);

        assertThat(codes(gate.check(partner)))
                .containsExactly(ActivationGateService.BANK_ACCOUNT_UNVERIFIED);
    }

    @Test
    @DisplayName("no contract row -> CONTRACT_MISSING")
    void contractMissing() {
        PartnerEntity partner = seedAllClear("gate_con_01");
        ContractEntity contract = contractRepository
                .findCurrentByPartnerId(partner.getId()).orElseThrow();
        contract.setSupersededAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        contractRepository.saveAndFlush(contract);

        assertThat(codes(gate.check(partner)))
                .containsExactly(ActivationGateService.CONTRACT_MISSING);
    }

    @Test
    @DisplayName("contract without signed_at -> CONTRACT_NOT_SIGNED")
    void contractNotSigned() {
        PartnerEntity partner = seedAllClear("gate_con_02");
        ContractEntity contract = contractRepository
                .findCurrentByPartnerId(partner.getId()).orElseThrow();
        contract.setSignedAt(null);
        contractRepository.saveAndFlush(contract);

        assertThat(codes(gate.check(partner)))
                .containsExactly(ActivationGateService.CONTRACT_NOT_SIGNED);
    }

    @Test
    @DisplayName("contract effective_from in the future -> CONTRACT_NOT_EFFECTIVE")
    void contractNotEffective() {
        PartnerEntity partner = seedAllClear("gate_con_03");
        ContractEntity contract = contractRepository
                .findCurrentByPartnerId(partner.getId()).orElseThrow();
        contract.setEffectiveFrom(LocalDate.now().plusDays(30));
        contractRepository.saveAndFlush(contract);

        assertThat(codes(gate.check(partner)))
                .containsExactly(ActivationGateService.CONTRACT_NOT_EFFECTIVE);
    }

    @Test
    @DisplayName("OVERSEAS partner without prefunding config -> PREFUNDING_MISSING")
    void overseasWithoutPrefunding() {
        PartnerEntity partner = seedAllClear("gate_pre_01");
        PrefundingConfigEntity prefunding = prefundingConfigRepository
                .findCurrentByPartnerId(partner.getId()).orElseThrow();
        prefunding.setSupersededAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        prefundingConfigRepository.saveAndFlush(prefunding);

        assertThat(codes(gate.check(partner)))
                .containsExactly(ActivationGateService.PREFUNDING_MISSING);
    }

    @Test
    @DisplayName("LOCAL partner WITH a prefunding config -> PREFUNDING_NOT_APPLICABLE")
    void localWithPrefunding() {
        PartnerEntity partner = seedPartner("gate_pre_02", PartnerType.LOCAL);
        addKyb(partner, "LOW", null, "CLEAR");
        addBankAccount(partner, "USD", BankVerificationStatus.BANK_LETTER);
        addContract(partner, LocalDate.now().minusDays(1), Instant.now());
        addContacts(partner, ContactRole.OPS_24X7, ContactRole.FINANCE,
                ContactRole.COMPLIANCE_MLRO, ContactRole.TECH);
        addScheme(partner, true);
        addPrefunding(partner); // misconfigured: LOCAL partners settle T+1, no float

        assertThat(codes(gate.check(partner)))
                .containsExactly(ActivationGateService.PREFUNDING_NOT_APPLICABLE);
    }

    @Test
    @DisplayName("only 3 distinct contact roles (duplicates don't count) -> CONTACT_ROLES_INSUFFICIENT")
    void contactRolesInsufficient() {
        PartnerEntity partner = seedPartner("gate_cnt_01", PartnerType.OVERSEAS);
        addKyb(partner, "MEDIUM", null, "CLEAR");
        addBankAccount(partner, "USD", BankVerificationStatus.MICRO_DEPOSIT);
        addContract(partner, LocalDate.now().minusDays(1), Instant.now());
        addPrefunding(partner);
        addScheme(partner, true);
        // Four rows but only three DISTINCT roles.
        addContacts(partner, ContactRole.OPS_24X7, ContactRole.FINANCE,
                ContactRole.FINANCE, ContactRole.TECH);

        assertThat(codes(gate.check(partner)))
                .containsExactly(ActivationGateService.CONTACT_ROLES_INSUFFICIENT);
    }

    @Test
    @DisplayName("scheme exists but disabled -> SCHEME_MISSING")
    void schemeDisabled() {
        PartnerEntity partner = seedPartner("gate_sch_01", PartnerType.OVERSEAS);
        addKyb(partner, "MEDIUM", null, "CLEAR");
        addBankAccount(partner, "USD", BankVerificationStatus.BANK_LETTER);
        addContract(partner, LocalDate.now().minusDays(1), Instant.now());
        addPrefunding(partner);
        addContacts(partner, ContactRole.OPS_24X7, ContactRole.FINANCE,
                ContactRole.COMPLIANCE_MLRO, ContactRole.LEGAL);
        addScheme(partner, false);

        assertThat(codes(gate.check(partner)))
                .containsExactly(ActivationGateService.SCHEME_MISSING);
    }

    @Test
    @DisplayName("a freshly-drafted partner reports the full unmet roster, not an exception")
    void freshDraftReportsManyUnmet() {
        PartnerEntity partner = seedPartner("gate_all_01", PartnerType.OVERSEAS);
        partner.setLegalNameLocal(null);
        partner.setLegalNameRomanized(null);
        partnerRepository.saveAndFlush(partner);

        ActivationGateResult result = gate.check(partner);
        assertThat(result.passes()).isFalse();
        assertThat(codes(result)).contains(
                ActivationGateService.LEGAL_NAME_MISSING,
                ActivationGateService.KYB_NOT_APPROVED,
                ActivationGateService.SANCTIONS_NOT_SCREENED,
                ActivationGateService.BANK_ACCOUNT_UNVERIFIED,
                ActivationGateService.CONTRACT_MISSING,
                ActivationGateService.PREFUNDING_MISSING,
                ActivationGateService.CONTACT_ROLES_INSUFFICIENT,
                ActivationGateService.SCHEME_MISSING);
        // every unmet condition carries a human-readable description
        assertThat(result.unmet()).allSatisfy(u ->
                assertThat(u.description()).isNotBlank());
    }
}
