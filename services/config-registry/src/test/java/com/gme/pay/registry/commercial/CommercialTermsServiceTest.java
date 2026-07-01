package com.gme.pay.registry.commercial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.CommercialTermsView;
import com.gme.pay.contracts.ContractCommand;
import com.gme.pay.contracts.FeeScheduleCommand;
import com.gme.pay.contracts.FxConfigCommand;
import com.gme.pay.contracts.LimitsCommand;
import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 6 acceptance test for {@link CommercialTermsService} — the composite
 * step-6 facade over the four sub-resource services.
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>A full composite save applies all four sections and returns all four
 *       fresh views; FOUR audit events land, one per sub-aggregate under its
 *       own {@code aggregateType}.</li>
 *   <li>Null sections are left UNTOUCHED (a later fx-only save does not
 *       disturb the fee set).</li>
 *   <li>An all-null payload (and a null body) is a 400.</li>
 *   <li>ATOMICITY: a composite whose fee section is valid but whose limits
 *       section violates the 소액해외송금업 statute rolls back the
 *       already-applied fee writes — verified OUTSIDE a test-managed
 *       transaction ({@code NOT_SUPPORTED}) so the service's own transaction
 *       boundary is the one that rolls back.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CommercialTermsServiceTest.TestConfig.class, CommercialTermsService.class,
        FeeScheduleService.class, FxConfigService.class, LimitsService.class,
        ContractService.class, AuditLogService.class, PartnerStore.class, CacheConfig.class,
        com.gme.pay.registry.prefunding.push.CreditLimitPusher.class,
        com.gme.pay.registry.prefunding.push.NoOpPrefundingCreditLimitClient.class})
class CommercialTermsServiceTest {

    @Autowired
    private CommercialTermsService service;

    @Autowired
    private FeeScheduleService feeScheduleService;

    @Autowired
    private FeeScheduleRepository feeScheduleRepository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private RecordingAuditPublisher publisher;

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

    private Long seedPartner(String code) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
        return partnerRepository.findCurrentByPartnerCode(code).orElseThrow().getId();
    }

    private static PartnerCommand.UpdateStep6Commercial fullPayload() {
        return new PartnerCommand.UpdateStep6Commercial(
                List.of(new FeeScheduleCommand("zeropay_kr", "OUTBOUND",
                        new BigDecimal("1.50"), new BigDecimal("25"), null)),
                new FxConfigCommand(new BigDecimal("85"), "SEOUL_FX_BROKER", 300),
                new LimitsCommand(null, new BigDecimal("5000"), null, null,
                        new BigDecimal("50000"), "SOAEK_HAEOEMONG"),
                new ContractCommand(LocalDate.of(2026, 7, 1), LocalDate.of(2028, 6, 30),
                        true, 90, "SHARED", null));
    }

    // -------------------------------------------------------------------- tests

    @Test
    void fullComposite_appliesAllFourSections_withOneAuditEventEach() {
        seedPartner("STEP6_FULL");
        publisher.clear();

        CommercialTermsView view = service.upsertStep6Commercial(
                "STEP6_FULL", fullPayload(), "maker_kim");

        assertThat(view.feeSchedules()).hasSize(1);
        assertThat(view.feeSchedules().get(0).schemeId()).isEqualTo("zeropay_kr");
        assertThat(view.fxConfig().referenceRateSource()).isEqualTo("SEOUL_FX_BROKER");
        assertThat(view.limits().licenseType()).isEqualTo("SOAEK_HAEOEMONG");
        assertThat(view.contract().autoRenewal()).isTrue();

        // One audit row per sub-aggregate, each under its own aggregateType.
        assertThat(publisher.published()).hasSize(4);
        assertThat(publisher.published())
                .extracting(com.gme.pay.audit.AuditEvent::aggregateType)
                .containsExactlyInAnyOrder("partner_fee_schedule", "partner_fx_config",
                        "partner_limits", "partner_contract");
    }

    @Test
    void nullSections_leftUntouched() {
        seedPartner("STEP6_PARTIAL");
        service.upsertStep6Commercial("STEP6_PARTIAL", fullPayload(), "maker_kim");

        // FX-only save: the fee set must survive untouched.
        CommercialTermsView view = service.upsertStep6Commercial("STEP6_PARTIAL",
                new PartnerCommand.UpdateStep6Commercial(null,
                        new FxConfigCommand(null, "MID_MARKET", null), null, null),
                "maker_kim");

        assertThat(view.feeSchedules()).as("untouched section comes back null").isNull();
        assertThat(view.fxConfig().referenceRateSource()).isEqualTo("MID_MARKET");
        assertThat(view.limits()).isNull();
        assertThat(view.contract()).isNull();

        assertThat(feeScheduleService.currentFeeSchedules("STEP6_PARTIAL"))
                .as("fee set untouched by the fx-only save").hasSize(1);
    }

    @Test
    void allNullSections_andNullBody_rejectedWith400() {
        seedPartner("STEP6_EMPTY");
        assertThatThrownBy(() -> service.upsertStep6Commercial("STEP6_EMPTY",
                new PartnerCommand.UpdateStep6Commercial(null, null, null, null), "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("at least one");
                });
        assertThatThrownBy(() -> service.upsertStep6Commercial("STEP6_EMPTY", null, "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    /**
     * Runs WITHOUT a test-managed transaction so the facade's own
     * {@code @Transactional} boundary is real: the fee section lands, the
     * limits section then breaches the 소액해외송금업 statute, and the whole
     * composite must roll back — no fee row may remain.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void atomicity_failingLimitsSection_rollsBackAppliedFeeSection() {
        Long partnerId = seedPartner("STEP6_ATOMIC");

        PartnerCommand.UpdateStep6Commercial badComposite =
                new PartnerCommand.UpdateStep6Commercial(
                        List.of(new FeeScheduleCommand("zeropay_kr", "OUTBOUND",
                                BigDecimal.ONE, BigDecimal.TEN, null)),
                        null,
                        // USD 5,001 against the 소액해외송금업 5,000 statute.
                        new LimitsCommand(null, new BigDecimal("5001"), null, null,
                                new BigDecimal("50000"), "SOAEK_HAEOEMONG"),
                        null);

        assertThatThrownBy(() -> service.upsertStep6Commercial(
                "STEP6_ATOMIC", badComposite, "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("5000");
                });

        // The fee bulk replace ran BEFORE the limits validation threw — the
        // shared transaction must have rolled it back.
        assertThat(feeScheduleRepository.findCurrentByPartnerId(partnerId))
                .as("fee rows rolled back with the failing composite")
                .isEmpty();
    }
}
