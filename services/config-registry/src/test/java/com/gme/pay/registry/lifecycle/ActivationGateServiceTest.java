package com.gme.pay.registry.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

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

    private void addKyb(PartnerEntity partner, String riskRating,
                        String riskRationale, String screeningStatus) {
        KybEntity kyb = new KybEntity();
        kyb.setPartnerId(partner.getId());
        kyb.setRiskRating(riskRating);
        kyb.setRiskRationale(riskRationale);
        kyb.setScreeningStatus(screeningStatus);
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
    @DisplayName("no KYB row -> KYB_NOT_APPROVED and SANCTIONS_NOT_CLEAR")
    void kybRowMissing() {
        PartnerEntity partner = seedAllClear("gate_kyb_01");
        kybRepository.findCurrentByPartnerId(partner.getId()).ifPresent(k -> {
            k.setSupersededAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
            kybRepository.saveAndFlush(k);
        });

        ActivationGateResult result = gate.check(partner);
        assertThat(codes(result)).containsExactlyInAnyOrder(
                ActivationGateService.KYB_NOT_APPROVED,
                ActivationGateService.SANCTIONS_NOT_CLEAR);
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
                ActivationGateService.SANCTIONS_NOT_CLEAR,
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
