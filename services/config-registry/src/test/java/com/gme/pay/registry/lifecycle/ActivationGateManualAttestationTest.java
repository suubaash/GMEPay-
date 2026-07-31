package com.gme.pay.registry.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.gme.pay.domain.PartnerType;
import com.gme.pay.kyb.ScreeningProvenance;
import com.gme.pay.registry.bank.BankAccountRepository;
import com.gme.pay.registry.commercial.ContractRepository;
import com.gme.pay.registry.contact.ContactRepository;
import com.gme.pay.registry.kyb.KybEntity;
import com.gme.pay.registry.kyb.KybRepository;
import com.gme.pay.registry.lifecycle.ActivationGateService.ActivationGateResult;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.prefunding.PrefundingConfigRepository;
import com.gme.pay.registry.scheme.PartnerSchemeRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The activation gate's own manual-attestation completeness check (gap T1-4, owner decision
 * 2026-07-28), driven against a STUBBED {@link KybRepository} rather than a database.
 *
 * <h2>Why this test does not use the DB slice</h2>
 *
 * <p>V045's CHECKs make an incomplete manual attestation genuinely unstorable — {@code
 * ActivationGateServiceTest#incompleteManualAttestation_isRejectedByTheDatabase} proves that,
 * column by column, through a native UPDATE. So the gate's re-derivation of completeness from the
 * columns cannot be exercised through the schema at all.
 *
 * <p>It is still not redundant, and the reason it is not is the reason this test exists: the gate
 * reads rows, and a row can arrive from somewhere the CHECK never policed — a row written before
 * V045, a restore from an older dump, a future migration that adds a status value and forgets a
 * constraint. Trusting the constraint there means a partner going LIVE on an attestation that names
 * nobody. So the check is re-derived from the five columns, and this test drives exactly the row
 * shapes the constraint would have refused.
 */
class ActivationGateManualAttestationTest {

    private KybRepository kybRepository;
    private ActivationGateService gate;

    @BeforeEach
    void setUp() {
        kybRepository = Mockito.mock(KybRepository.class);
        BankAccountRepository bankAccounts = Mockito.mock(BankAccountRepository.class);
        ContactRepository contacts = Mockito.mock(ContactRepository.class);
        ContractRepository contracts = Mockito.mock(ContractRepository.class);
        PrefundingConfigRepository prefunding = Mockito.mock(PrefundingConfigRepository.class);
        PartnerSchemeRepository schemes = Mockito.mock(PartnerSchemeRepository.class);
        // Every non-KYB pre-condition is deliberately left unsatisfied and then filtered out of
        // the assertions: this test is about the sanctions codes only, and stubbing five other
        // aggregates into a passing state would make it a worse test of the one thing it checks.
        when(bankAccounts.findCurrentByPartnerId(any())).thenReturn(List.of());
        when(contacts.findCurrentByPartnerId(any())).thenReturn(List.of());
        when(contracts.findCurrentByPartnerId(any())).thenReturn(Optional.empty());
        when(prefunding.findCurrentByPartnerId(any())).thenReturn(Optional.empty());
        when(schemes.findAllCurrentByPartnerId(any())).thenReturn(List.of());
        gate = new ActivationGateService(kybRepository, bankAccounts, contacts, contracts,
                prefunding, schemes, false);
    }

    private static PartnerEntity partner() {
        PartnerEntity p = new PartnerEntity();
        p.setPartnerCode("MANUAL_GATE");
        p.setType(PartnerType.LOCAL);
        p.setLegalNameLocal("지엠이");
        p.setLegalNameRomanized("GME Co., Ltd.");
        p.setSettleACcy("KRW");
        return p;
    }

    /** A complete, well-formed manual attestation row. */
    private static KybEntity attested() {
        KybEntity kyb = new KybEntity();
        kyb.setRiskRating("MEDIUM");
        kyb.setScreeningStatus(KybEntity.SCREENING_CLEAR_MANUAL_ATTESTATION);
        kyb.setScreeningProviderId(KybEntity.MANUAL_ATTESTATION_PROVIDER_ID);
        kyb.setScreeningAuthoritative(true);
        kyb.setScreenedAt(Instant.parse("2026-07-28T09:15:00Z"));
        kyb.setManualAttesterActorId("compliance.officer@gme.com");
        kyb.setManualAttestedAt(Instant.parse("2026-07-28T09:15:00Z"));
        kyb.setManualSopDocumentRef("GME-COMP-SOP-014");
        kyb.setManualSopVersion("v3");
        kyb.setManualSourcesConsulted("UN consolidated list + SOP §4 jurisdiction lists");
        return kyb;
    }

    private List<String> sanctionsCodes(KybEntity kyb) {
        when(kybRepository.findCurrentByPartnerId(any())).thenReturn(Optional.of(kyb));
        ActivationGateResult result = gate.check(partner());
        return result.unmet().stream()
                .map(ActivationGateService.UnmetCondition::code)
                .filter(c -> c.startsWith("SANCTIONS_"))
                .toList();
    }

    private String sanctionsDescription(KybEntity kyb) {
        when(kybRepository.findCurrentByPartnerId(any())).thenReturn(Optional.of(kyb));
        return gate.check(partner()).unmet().stream()
                .filter(u -> u.code().startsWith("SANCTIONS_"))
                .map(ActivationGateService.UnmetCondition::description)
                .findFirst()
                .orElse("");
    }

    @Test
    @DisplayName("a complete manual attestation raises no sanctions condition at all")
    void completeAttestationSatisfiesTheSanctionsChecks() {
        assertThat(sanctionsCodes(attested())).isEmpty();
    }

    @Test
    @DisplayName("each missing attestation field alone raises SANCTIONS_MANUAL_ATTESTATION_INCOMPLETE")
    void everyMissingFieldRefusesActivation() {
        KybEntity noAttester = attested();
        noAttester.setManualAttesterActorId(null);
        assertThat(sanctionsCodes(noAttester)).containsExactly(
                ActivationGateService.SANCTIONS_MANUAL_ATTESTATION_INCOMPLETE);
        assertThat(sanctionsDescription(noAttester)).contains("attester");

        KybEntity noInstant = attested();
        noInstant.setManualAttestedAt(null);
        assertThat(sanctionsCodes(noInstant)).containsExactly(
                ActivationGateService.SANCTIONS_MANUAL_ATTESTATION_INCOMPLETE);
        assertThat(sanctionsDescription(noInstant)).contains("attestation instant");

        KybEntity noSop = attested();
        noSop.setManualSopDocumentRef(null);
        assertThat(sanctionsCodes(noSop)).containsExactly(
                ActivationGateService.SANCTIONS_MANUAL_ATTESTATION_INCOMPLETE);
        assertThat(sanctionsDescription(noSop)).contains("SOP document reference");

        KybEntity noVersion = attested();
        noVersion.setManualSopVersion("   ");
        assertThat(sanctionsCodes(noVersion)).containsExactly(
                ActivationGateService.SANCTIONS_MANUAL_ATTESTATION_INCOMPLETE);
        assertThat(sanctionsDescription(noVersion)).contains("SOP version");

        KybEntity noSources = attested();
        noSources.setManualSourcesConsulted(null);
        assertThat(sanctionsCodes(noSources)).containsExactly(
                ActivationGateService.SANCTIONS_MANUAL_ATTESTATION_INCOMPLETE);
        assertThat(sanctionsDescription(noSources)).contains("sources consulted");
    }

    @Test
    @DisplayName("a risk rationale cannot override an incomplete attestation")
    void rationaleCannotOverrideAnIncompleteAttestation() {
        KybEntity kyb = attested();
        kyb.setManualSopVersion(null);
        // A rationale that WOULD legitimately override a real HIT.
        kyb.setRiskRationale("Compliance committee sign-off 2026-06-01, EDD on file");

        assertThat(sanctionsCodes(kyb)).containsExactly(
                ActivationGateService.SANCTIONS_MANUAL_ATTESTATION_INCOMPLETE);
    }

    @Test
    @DisplayName("a manual-SOP row that is not even authoritative is NOT_SCREENED, not INCOMPLETE")
    void nonAuthoritativeManualRowIsNotScreened() {
        // The two codes must not collapse into one another: this row claims nothing was performed,
        // so the remedy is to screen, not to complete a record.
        KybEntity kyb = attested();
        kyb.setScreeningAuthoritative(false);
        kyb.setScreeningStatus(KybEntity.SCREENING_NOT_PERFORMED);
        kyb.setScreeningCaveat(ScreeningProvenance.UNKNOWN_CAVEAT);

        assertThat(sanctionsCodes(kyb))
                .containsExactly(ActivationGateService.SANCTIONS_NOT_SCREENED);
    }

    @Test
    @DisplayName("the stub path still refuses, unchanged by the manual authority")
    void stubStillRefuses() {
        KybEntity kyb = new KybEntity();
        kyb.setRiskRating("MEDIUM");
        kyb.setScreeningStatus(KybEntity.SCREENING_NOT_PERFORMED);
        kyb.setScreeningProviderId(ScreeningProvenance.STUB_PROVIDER_ID);
        kyb.setScreeningAuthoritative(false);
        kyb.setScreeningCaveat(ScreeningProvenance.STUB_CAVEAT);

        assertThat(sanctionsCodes(kyb))
                .containsExactly(ActivationGateService.SANCTIONS_NOT_SCREENED);
        assertThat(sanctionsDescription(kyb)).contains("NOT A SANCTIONS SCREENING");
    }
}
