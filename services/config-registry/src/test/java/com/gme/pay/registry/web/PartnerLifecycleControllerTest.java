package com.gme.pay.registry.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.changerequest.ChangeRequestStateMachineConfig;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.bank.BankAccountEntity;
import com.gme.pay.registry.bank.BankAccountRepository;
import com.gme.pay.registry.bank.BankVerificationStatus;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.changerequest.ChangeRequestService;
import com.gme.pay.registry.commercial.ContractEntity;
import com.gme.pay.registry.commercial.ContractRepository;
import com.gme.pay.registry.contact.ContactEntity;
import com.gme.pay.registry.contact.ContactRepository;
import com.gme.pay.registry.contact.ContactRole;
import com.gme.pay.registry.kyb.KybEntity;
import com.gme.pay.registry.kyb.KybRepository;
import com.gme.pay.registry.lifecycle.ActivationGateService;
import com.gme.pay.registry.lifecycle.PartnerLifecycleChangeRequestApplier;
import com.gme.pay.registry.lifecycle.PartnerLifecycleService;
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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice 8 controller slice test for {@link PartnerLifecycleController} — the
 * full FSM walk, the two-call 4-eyes protocol, the 422 + unmet[] gate path,
 * and post-activation immutability. {@code @DataJpaTest} against H2 in
 * PostgreSQL mode with the full Flyway chain (V001..V025+); standalone MockMvc
 * mounting the lifecycle controller plus {@link PartnerController} (for the
 * immutability PATCH) behind the {@link RegistryApiExceptionHandler} advice.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PartnerLifecycleControllerTest.TestConfig.class,
        ChangeRequestService.class, ChangeRequestStateMachineConfig.class,
        PartnerLifecycleService.class, PartnerLifecycleChangeRequestApplier.class,
        ActivationGateService.class, AuditLogService.class,
        PartnerStore.class, CacheConfig.class})
class PartnerLifecycleControllerTest {

    @Autowired
    private PartnerLifecycleService lifecycleService;

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

    @Autowired
    private RecordingAuditPublisher publisher;

    private MockMvc mvc;

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

        /** The lifecycle service + applier parse/build payload JSON with this. */
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }
    }

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // PartnerController's draft/contact collaborators are not exercised by
        // the immutability PATCH path — null keeps the slice context lean.
        PartnerController partnerController =
                new PartnerController(partnerStore, partnerRepository, null, null);
        mvc = standaloneSetup(new PartnerLifecycleController(lifecycleService),
                partnerController)
                .setControllerAdvice(new RegistryApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    // ------------------------------------------------------------- seeding

    private PartnerEntity seedPartner(String code, PartnerStatus status) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
        PartnerEntity entity = partnerRepository.findCurrentByPartnerCode(code).orElseThrow();
        entity.setStatus(status);
        entity.setLegalNameLocal("지엠이 " + code);
        entity.setLegalNameRomanized("GME " + code + " Co., Ltd.");
        entity.setCountryOfIncorporation("KR");
        return partnerRepository.saveAndFlush(entity);
    }

    /** A UAT partner satisfying every activation pre-condition. */
    private PartnerEntity seedActivatable(String code) {
        PartnerEntity partner = seedPartner(code, PartnerStatus.UAT);

        KybEntity kyb = new KybEntity();
        kyb.setPartnerId(partner.getId());
        kyb.setRiskRating("MEDIUM");
        kyb.setScreeningStatus("CLEAR");
        kybRepository.saveAndFlush(kyb);

        BankAccountEntity account = new BankAccountEntity();
        account.setPartnerId(partner.getId());
        account.setCurrency("USD");
        account.setBankName("Standard Chartered");
        account.setIbanOrAccountNumber("0123456789");
        account.setAccountHolderName("GME Remit");
        account.setBankCountry("SG");
        account.setVerificationStatus(BankVerificationStatus.BANK_LETTER);
        bankAccountRepository.saveAndFlush(account);

        ContractEntity contract = new ContractEntity();
        contract.setPartnerId(partner.getId());
        contract.setEffectiveFrom(LocalDate.now().minusDays(1));
        contract.setSignedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        contractRepository.saveAndFlush(contract);

        PrefundingConfigEntity prefunding = new PrefundingConfigEntity();
        prefunding.setPartnerId(partner.getId());
        prefunding.setFundingModel("PREFUNDED");
        prefunding.setLowBalanceThresholdUsd(new BigDecimal("10000.0000"));
        prefundingConfigRepository.saveAndFlush(prefunding);

        for (ContactRole role : List.of(ContactRole.OPS_24X7, ContactRole.FINANCE,
                ContactRole.COMPLIANCE_MLRO, ContactRole.TECH)) {
            ContactEntity contact = new ContactEntity();
            contact.setPartnerId(partner.getId());
            contact.setRole(role);
            contact.setName("Contact " + role);
            contact.setEmail(role.name().toLowerCase() + "@partner.example");
            contactRepository.saveAndFlush(contact);
        }

        PartnerSchemeEntity scheme = new PartnerSchemeEntity();
        scheme.setPartnerId(partner.getId());
        scheme.setSchemeId("ZEROPAY");
        scheme.setDirection("OUTBOUND");
        scheme.setRole("ACQUIRER");
        scheme.setEnabled(true);
        schemeRepository.saveAndFlush(scheme);

        return partner;
    }

    /** A partner already LIVE (gone through first activation). */
    private PartnerEntity seedLive(String code) {
        PartnerEntity partner = seedPartner(code, PartnerStatus.LIVE);
        partner.setGoLiveAt(Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MICROS));
        partner.setActivatedBy("checker_lee");
        return partnerRepository.saveAndFlush(partner);
    }

    private PartnerEntity reload(String code) {
        return partnerRepository.findCurrentByPartnerCode(code).orElseThrow();
    }

    // ------------------------------------------------------------- tests

    @Test
    @DisplayName("GET /preconditions on unknown partner -> 404")
    void preconditions_unknownPartner_404() throws Exception {
        mvc.perform(get("/v1/admin/partners/{code}/lifecycle/preconditions", "NOPE"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /preconditions itemises the unmet checklist for a bare draft")
    void preconditions_listsUnmet() throws Exception {
        seedPartner("lc_pre_01", PartnerStatus.ONBOARDING);
        mvc.perform(get("/v1/admin/partners/{code}/lifecycle/preconditions", "lc_pre_01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passes").value(false))
                .andExpect(jsonPath("$.unmet").isArray())
                .andExpect(jsonPath("$.unmet[?(@.code == 'KYB_NOT_APPROVED')]").isNotEmpty())
                .andExpect(jsonPath("$.unmet[?(@.code == 'SCHEME_MISSING')]").isNotEmpty());
    }

    @Test
    @DisplayName("POST /activate (maker) parks a PROPOSED change_request -> 202")
    void activate_makerCall_202Pending() throws Exception {
        seedActivatable("lc_act_01");
        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/activate", "lc_act_01")
                        .header("X-Actor", "maker_kim")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("PROPOSED"))
                .andExpect(jsonPath("$.aggregateType").value("partner_lifecycle"))
                .andExpect(jsonPath("$.proposedBy").value("maker_kim"));
        // Nothing transitioned yet.
        assertThat(reload("lc_act_01").getStatus()).isEqualTo(PartnerStatus.UAT);
        assertThat(reload("lc_act_01").getGoLiveAt()).isNull();
    }

    @Test
    @DisplayName("4-eyes activate: checker approval transitions UAT -> LIVE, stamps go_live_at + activated_by, audits PARTNER_ACTIVATED")
    void activate_happyPath_goesLive() throws Exception {
        seedActivatable("lc_act_02");
        publisher.clear();

        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/activate", "lc_act_02")
                        .header("X-Actor", "maker_kim")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted());

        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/activate", "lc_act_02")
                        .header("X-Actor", "checker_lee")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LIVE"));

        PartnerEntity live = reload("lc_act_02");
        assertThat(live.getStatus()).isEqualTo(PartnerStatus.LIVE);
        assertThat(live.getGoLiveAt()).isNotNull();
        assertThat(live.getActivatedBy()).isEqualTo("checker_lee");

        List<AuditEvent> events = publisher.published();
        assertThat(events).anySatisfy(e -> {
            assertThat(e.eventType()).isEqualTo(
                    PartnerLifecycleChangeRequestApplier.EVENT_ACTIVATED);
            assertThat(e.aggregateId()).isEqualTo("lc_act_02");
        });
    }

    @Test
    @DisplayName("checker approval with a failing gate -> 422 + unmet[], change_request stays PROPOSED")
    void activate_gateFails_422WithUnmetBody() throws Exception {
        // UAT partner that satisfies NOTHING beyond the FSM position.
        seedPartner("lc_act_03", PartnerStatus.UAT);

        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/activate", "lc_act_03")
                        .header("X-Actor", "maker_kim")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted());

        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/activate", "lc_act_03")
                        .header("X-Actor", "checker_lee")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.passes").value(false))
                .andExpect(jsonPath("$.unmet[?(@.code == 'CONTRACT_MISSING')]").isNotEmpty())
                .andExpect(jsonPath("$.unmet[?(@.code == 'BANK_ACCOUNT_UNVERIFIED')]").isNotEmpty());

        // The transition did NOT happen and the proposal survives for retry.
        assertThat(reload("lc_act_03").getStatus()).isEqualTo(PartnerStatus.UAT);
    }

    @Test
    @DisplayName("self-approval of an ACTIVATE proposal -> 409 (V005 4-eyes CHECK)")
    void activate_selfApproval_409() throws Exception {
        seedActivatable("lc_act_04");

        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/activate", "lc_act_04")
                        .header("X-Actor", "maker_kim")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted());

        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/activate", "lc_act_04")
                        .header("X-Actor", "maker_kim")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /activate from a non-UAT status -> 422")
    void activate_wrongSourceStatus_422() throws Exception {
        seedPartner("lc_act_05", PartnerStatus.ONBOARDING);
        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/activate", "lc_act_05")
                        .header("X-Actor", "maker_kim")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("POST /suspend on a non-LIVE partner -> 422")
    void suspend_nonLive_422() throws Exception {
        seedPartner("lc_sus_01", PartnerStatus.UAT);
        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/suspend", "lc_sus_01")
                        .header("X-Actor", "maker_kim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"OPERATOR_INITIATED\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("POST /suspend without a reason -> 400; unknown reason -> 400")
    void suspend_badReason_400() throws Exception {
        seedLive("lc_sus_02");
        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/suspend", "lc_sus_02")
                        .header("X-Actor", "maker_kim")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/suspend", "lc_sus_02")
                        .header("X-Actor", "maker_kim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"BECAUSE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("4-eyes suspend: LIVE -> SUSPENDED with reason code + notes + suspended_at")
    void suspend_happyPath() throws Exception {
        seedLive("lc_sus_03");
        String body = "{\"reason\":\"SANCTIONS_HIT\",\"notes\":\"OFAC list match pending review\"}";

        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/suspend", "lc_sus_03")
                        .header("X-Actor", "maker_kim")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("PROPOSED"));

        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/suspend", "lc_sus_03")
                        .header("X-Actor", "checker_lee")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        PartnerEntity suspended = reload("lc_sus_03");
        assertThat(suspended.getStatus()).isEqualTo(PartnerStatus.SUSPENDED);
        assertThat(suspended.getSuspensionReason()).isEqualTo("SANCTIONS_HIT");
        assertThat(suspended.getSuspensionNotes()).contains("OFAC");
        assertThat(suspended.getSuspendedAt()).isNotNull();
    }

    @Test
    @DisplayName("4-eyes reactivate: SUSPENDED -> LIVE clears the suspension fields, keeps go_live_at")
    void reactivate_happyPath_clearsSuspension() throws Exception {
        PartnerEntity partner = seedLive("lc_rea_01");
        Instant originalGoLive = partner.getGoLiveAt();
        partner.setStatus(PartnerStatus.SUSPENDED);
        partner.setSuspensionReason("LIMIT_BREACH");
        partner.setSuspensionNotes("breached USD float floor");
        partner.setSuspendedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        partnerRepository.saveAndFlush(partner);

        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/reactivate", "lc_rea_01")
                        .header("X-Actor", "maker_kim")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/reactivate", "lc_rea_01")
                        .header("X-Actor", "checker_lee")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LIVE"));

        PartnerEntity reactivated = reload("lc_rea_01");
        assertThat(reactivated.getStatus()).isEqualTo(PartnerStatus.LIVE);
        assertThat(reactivated.getSuspensionReason()).isNull();
        assertThat(reactivated.getSuspensionNotes()).isNull();
        assertThat(reactivated.getSuspendedAt()).isNull();
        // go_live_at marks the FIRST activation — reactivation must not move it.
        assertThat(reactivated.getGoLiveAt()).isEqualTo(originalGoLive);
    }

    @Test
    @DisplayName("POST /terminate without a reason -> 400")
    void terminate_missingReason_400() throws Exception {
        seedLive("lc_ter_01");
        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/terminate", "lc_ter_01")
                        .header("X-Actor", "maker_kim")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("4-eyes terminate from SUSPENDED stamps terminated_at + reason; TERMINATED is terminal")
    void terminate_fromSuspended_terminal() throws Exception {
        PartnerEntity partner = seedLive("lc_ter_02");
        partner.setStatus(PartnerStatus.SUSPENDED);
        partner.setSuspensionReason("CONTRACT_EXPIRED");
        partner.setSuspendedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        partnerRepository.saveAndFlush(partner);

        String body = "{\"reason\":\"contract lapsed, partner declined renewal\"}";
        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/terminate", "lc_ter_02")
                        .header("X-Actor", "maker_kim")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/terminate", "lc_ter_02")
                        .header("X-Actor", "checker_lee")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"));

        PartnerEntity terminated = reload("lc_ter_02");
        assertThat(terminated.getStatus()).isEqualTo(PartnerStatus.TERMINATED);
        assertThat(terminated.getTerminatedAt()).isNotNull();
        assertThat(terminated.getTerminationReason()).contains("declined renewal");

        // Terminal: no lifecycle action may leave TERMINATED.
        mvc.perform(post("/v1/admin/partners/{code}/lifecycle/suspend", "lc_ter_02")
                        .header("X-Actor", "maker_kim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"OPERATOR_INITIATED\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("post-activation immutability: PATCH step-1 changing country_of_incorporation -> 400 IMMUTABLE_AFTER_ACTIVATION")
    void immutability_countryChangeAfterGoLive_400() throws Exception {
        seedLive("lc_imm_01"); // incorporated KR, go_live_at stamped

        mvc.perform(patch("/v1/partners/partner-code/{code}/step-1", "lc_imm_01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countryOfIncorporation\":\"VN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMMUTABLE_AFTER_ACTIVATION"));

        assertThat(reload("lc_imm_01").getCountryOfIncorporation()).isEqualTo("KR");
    }

    @Test
    @DisplayName("post-activation immutability: four-field write changing partner_type throws IMMUTABLE_AFTER_ACTIVATION")
    void immutability_typeChangeAfterGoLive_throws() {
        seedLive("lc_imm_02"); // OVERSEAS

        assertThatThrownBy(() -> partnerStore.save(
                Partner.of("lc_imm_02", PartnerType.LOCAL, "USD", RoundingMode.HALF_UP)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).errorCode())
                        .isEqualTo(ErrorCode.IMMUTABLE_AFTER_ACTIVATION));

        // Non-locked writes still pass: same type/currency, new rounding mode.
        partnerStore.save(Partner.of("lc_imm_02", PartnerType.OVERSEAS, "USD",
                RoundingMode.FLOOR));
        PartnerEntity after = reload("lc_imm_02");
        assertThat(after.getSettlementRoundingMode()).isEqualTo(RoundingMode.FLOOR);
        // ...and the SCD-6 generation carried the lifecycle stamps forward.
        assertThat(after.getStatus()).isEqualTo(PartnerStatus.LIVE);
        assertThat(after.getGoLiveAt()).isNotNull();
    }
}
