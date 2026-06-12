package com.gme.pay.registry.prefunding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.PrefundingConfigView;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.bank.BankAccountEntity;
import com.gme.pay.registry.bank.BankAccountPurpose;
import com.gme.pay.registry.bank.BankAccountRepository;
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
 * Slice 5 acceptance test for {@link PrefundingConfigService} — the
 * {@code partner_prefunding_config} upsert (V015), wired end-to-end against H2
 * in PostgreSQL mode with the full Flyway chain applied. Mirrors the
 * {@code SettlementConfigServiceTest} slice-test pattern: {@code @DataJpaTest}
 * + explicit {@code @Import} of the service/audit beans and a
 * {@link RecordingAuditPublisher} to observe ADR-007 publication.
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>Step-5 upsert inserts a CURRENT row (V015 defaults applied to null
 *       command fields, money normalized to scale 4); a second upsert
 *       supersedes the first — paired SCD-6 writes sharing one
 *       MICROS-truncated instant.</li>
 *   <li>Server-side validation rejects bad model / non-positive threshold /
 *       over-scale money / pattern without {partner_code} with 400, without
 *       touching any row.</li>
 *   <li>The top-up account reference must be a CURRENT V012 row of THIS
 *       partner with purpose=FLOAT_TOPUP — wrong purpose, unknown id and
 *       another partner's row are all 400.</li>
 *   <li>One {@code partner_prefunding_config} audit event per write with
 *       BEFORE/AFTER canonical snapshots (money as plain-decimal strings).</li>
 *   <li>Unknown partner → 404; partner outside ONBOARDING → 409 (the
 *       post-activation approval flow is Slice 8).</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PrefundingConfigServiceTest.TestConfig.class, PrefundingConfigService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class PrefundingConfigServiceTest {

    @Autowired
    private PrefundingConfigService service;

    @Autowired
    private PrefundingConfigRepository configRepository;

    @Autowired
    private BankAccountRepository bankAccountRepository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private RecordingAuditPublisher publisher;

    /** Same publisher swap as {@code AuditLogTest} / {@code SettlementConfigServiceTest}. */
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

    /** Seed one CURRENT V012 bank-account row directly (the Slice 4 service is a sibling). */
    private Long seedBankAccount(Long partnerId, BankAccountPurpose purpose) {
        BankAccountEntity account = new BankAccountEntity();
        account.setPartnerId(partnerId);
        account.setCurrency("USD");
        account.setBankName("Shinhan Bank");
        account.setIbanOrAccountNumber("110-123-456789");
        account.setAccountHolderName("Clean Corp Ltd");
        account.setBankCountry("KR");
        account.setPurpose(purpose);
        return bankAccountRepository.saveAndFlush(account).getId();
    }

    /** Command with every field defaulted except the mandatory funding model. */
    private static PartnerCommand.UpdateStep5 minimalCmd(String model) {
        return new PartnerCommand.UpdateStep5(
                model, null, null, null, null, null, null, null, null, null, null);
    }

    // -------------------------------------------------------------------- tests

    @Test
    void upsert_appliesV015Defaults_andSecondUpsertSupersedes() {
        Long partnerId = seedPartner("PREFUND_UPSERT");

        // Nulls everywhere except the mandatory model -> V015 defaults.
        PrefundingConfigView first = service.upsertStep5("PREFUND_UPSERT",
                minimalCmd("PREFUNDED"), "maker_kim");

        assertThat(first.id()).isNotNull();
        assertThat(first.fundingModel()).isEqualTo("PREFUNDED");
        assertThat(first.openingBalanceUsd()).isNull();
        assertThat(first.lowBalanceThresholdUsd())
                .isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(first.lowBalanceThresholdUsd().scale())
                .as("money normalized to NUMERIC(19,4) scale").isEqualTo(4);
        assertThat(first.alertTier70()).isTrue();
        assertThat(first.alertTier85()).isTrue();
        assertThat(first.alertTier95()).isTrue();
        assertThat(first.creditLimitUsd()).isNull();
        assertThat(first.autoSuspendOnBreach()).isTrue();
        assertThat(first.floatTopUpBankAccountId()).isNull();
        assertThat(first.topUpReferencePattern()).isEqualTo("GMP-{partner_code}-{yyyyMMdd}");
        assertThat(first.collateralAmountUsd()).isNull();

        PrefundingConfigView second = service.upsertStep5("PREFUND_UPSERT",
                new PartnerCommand.UpdateStep5(
                        "HYBRID",
                        new BigDecimal("250000"),
                        new BigDecimal("25000.50"),
                        true, true, false,
                        new BigDecimal("100000"),
                        false,
                        null,
                        "GMP-{partner_code}-TOPUP",
                        new BigDecimal("50000")),
                "maker_kim");

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.fundingModel()).isEqualTo("HYBRID");
        assertThat(second.openingBalanceUsd()).isEqualByComparingTo(new BigDecimal("250000"));
        assertThat(second.lowBalanceThresholdUsd())
                .isEqualByComparingTo(new BigDecimal("25000.50"));
        assertThat(second.alertTier95()).isFalse();
        assertThat(second.creditLimitUsd()).isEqualByComparingTo(new BigDecimal("100000"));
        assertThat(second.autoSuspendOnBreach()).isFalse();
        assertThat(second.topUpReferencePattern()).isEqualTo("GMP-{partner_code}-TOPUP");
        assertThat(second.collateralAmountUsd()).isEqualByComparingTo(new BigDecimal("50000"));

        // SCD-6: nothing deleted — 2 rows, prior superseded with an instant
        // EXACTLY equal to the fresh recorded_at (shared paired-write instant),
        // MICROS-truncated, business time continuous.
        List<PrefundingConfigEntity> all = configRepository.findAll().stream()
                .filter(e -> partnerId.equals(e.getPartnerId())).toList();
        assertThat(all).hasSize(2);
        PrefundingConfigEntity prior = all.stream()
                .filter(e -> e.getSupersededAt() != null).findFirst().orElseThrow();
        PrefundingConfigEntity current = all.stream()
                .filter(e -> e.getSupersededAt() == null).findFirst().orElseThrow();
        assertThat(prior.getSupersededAt()).isEqualTo(current.getRecordedAt());
        assertThat(current.getRecordedAt().getNano() % 1000).isZero();
        assertThat(current.getValidFrom()).isEqualTo(prior.getValidFrom());

        // Rehydrate path returns the current row.
        assertThat(service.currentConfig("PREFUND_UPSERT").id()).isEqualTo(second.id());
    }

    @Test
    void topUpAccount_floatTopupPurpose_accepted() {
        Long partnerId = seedPartner("PREFUND_TOPUP_OK");
        Long accountId = seedBankAccount(partnerId, BankAccountPurpose.FLOAT_TOPUP);

        PrefundingConfigView view = service.upsertStep5("PREFUND_TOPUP_OK",
                new PartnerCommand.UpdateStep5(
                        "PREFUNDED", null, null, null, null, null, null, null,
                        accountId, null, null),
                "maker_kim");

        assertThat(view.floatTopUpBankAccountId()).isEqualTo(accountId);
    }

    @Test
    void topUpAccount_wrongPurpose_rejectedWith400() {
        Long partnerId = seedPartner("PREFUND_TOPUP_BAD");
        Long payoutAccountId = seedBankAccount(partnerId, BankAccountPurpose.PAYOUT);

        assertThatThrownBy(() -> service.upsertStep5("PREFUND_TOPUP_BAD",
                new PartnerCommand.UpdateStep5(
                        "PREFUNDED", null, null, null, null, null, null, null,
                        payoutAccountId, null, null),
                "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("purpose=FLOAT_TOPUP");
                    assertThat(e.getReason()).contains("PAYOUT");
                });
        assertThat(configRepository.findCurrentByPartnerId(partnerId)).isEmpty();
    }

    @Test
    void topUpAccount_unknownOrForeignRow_rejectedWith400() {
        Long partnerId = seedPartner("PREFUND_TOPUP_GHOST");
        Long otherPartnerId = seedPartner("PREFUND_TOPUP_OTHER");
        Long foreignAccountId = seedBankAccount(otherPartnerId, BankAccountPurpose.FLOAT_TOPUP);

        // Unknown id.
        assertThatThrownBy(() -> service.upsertStep5("PREFUND_TOPUP_GHOST",
                new PartnerCommand.UpdateStep5(
                        "PREFUNDED", null, null, null, null, null, null, null,
                        999_999L, null, null),
                "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("not a current bank account");
                });

        // Another partner's FLOAT_TOPUP row must not be referenceable.
        assertThatThrownBy(() -> service.upsertStep5("PREFUND_TOPUP_GHOST",
                new PartnerCommand.UpdateStep5(
                        "PREFUNDED", null, null, null, null, null, null, null,
                        foreignAccountId, null, null),
                "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThat(configRepository.findCurrentByPartnerId(partnerId)).isEmpty();
    }

    @Test
    void validation_rejectsBadPayloadsWith400_withoutWritingRows() {
        Long partnerId = seedPartner("PREFUND_INVALID");

        record Bad(String label, PartnerCommand.UpdateStep5 cmd) {}
        List<Bad> bads = List.of(
                new Bad("missing model", minimalCmd(null)),
                new Bad("blank model", minimalCmd("  ")),
                new Bad("unknown model", minimalCmd("PAY_LATER")),
                new Bad("zero threshold", new PartnerCommand.UpdateStep5(
                        "PREFUNDED", null, BigDecimal.ZERO,
                        null, null, null, null, null, null, null, null)),
                new Bad("negative threshold", new PartnerCommand.UpdateStep5(
                        "PREFUNDED", null, new BigDecimal("-1"),
                        null, null, null, null, null, null, null, null)),
                new Bad("threshold over 4dp", new PartnerCommand.UpdateStep5(
                        "PREFUNDED", null, new BigDecimal("10000.12345"),
                        null, null, null, null, null, null, null, null)),
                new Bad("negative opening balance", new PartnerCommand.UpdateStep5(
                        "PREFUNDED", new BigDecimal("-0.01"), null,
                        null, null, null, null, null, null, null, null)),
                new Bad("negative credit limit", new PartnerCommand.UpdateStep5(
                        "POSTPAID", null, null, null, null, null,
                        new BigDecimal("-100"), null, null, null, null)),
                new Bad("negative collateral", new PartnerCommand.UpdateStep5(
                        "POSTPAID", null, null, null, null, null, null, null, null, null,
                        new BigDecimal("-100"))),
                new Bad("pattern without {partner_code}", new PartnerCommand.UpdateStep5(
                        "PREFUNDED", null, null, null, null, null, null, null, null,
                        "GMP-TOPUP-{yyyyMMdd}", null)),
                new Bad("pattern over 60 chars", new PartnerCommand.UpdateStep5(
                        "PREFUNDED", null, null, null, null, null, null, null, null,
                        "GMP-{partner_code}-" + "x".repeat(50), null)),
                new Bad("non-positive account id", new PartnerCommand.UpdateStep5(
                        "PREFUNDED", null, null, null, null, null, null, null,
                        0L, null, null)));

        for (Bad bad : bads) {
            assertThatThrownBy(() -> service.upsertStep5(
                    "PREFUND_INVALID", bad.cmd(), "maker_kim"))
                    .as(bad.label())
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }

        // A 400 must be side-effect free: no config row landed.
        assertThat(configRepository.findCurrentByPartnerId(partnerId)).isEmpty();
    }

    @Test
    void nullBody_isRejectedWith400() {
        seedPartner("PREFUND_NULL");
        assertThatThrownBy(() -> service.upsertStep5("PREFUND_NULL", null, "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void unknownPartner_404_onBothOperations() {
        assertThatThrownBy(() -> service.upsertStep5(
                "PREFUND_GHOST", minimalCmd("PREFUNDED"), "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.currentConfig("PREFUND_GHOST"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void partnerWithoutConfig_404_onRead() {
        seedPartner("PREFUND_EMPTY");
        assertThatThrownBy(() -> service.currentConfig("PREFUND_EMPTY"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getReason()).contains("prefunding config");
                });
    }

    @Test
    void nonOnboardingPartner_409_postActivationFlowIsSlice8() {
        seedPartner("PREFUND_LIVE");
        service.upsertStep5("PREFUND_LIVE", minimalCmd("PREFUNDED"), "maker_kim");

        PartnerEntity current = partnerRepository.findCurrentByPartnerCode("PREFUND_LIVE")
                .orElseThrow();
        current.setStatus(PartnerStatus.LIVE);
        partnerRepository.saveAndFlush(current);

        assertThatThrownBy(() -> service.upsertStep5("PREFUND_LIVE",
                minimalCmd("HYBRID"), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        // Reads stay open for LIVE partners (detail page tile).
        assertThat(service.currentConfig("PREFUND_LIVE").fundingModel())
                .isEqualTo("PREFUNDED");
    }

    @Test
    void audit_oneEventPerWrite_withCanonicalBeforeAfterSnapshots() {
        seedPartner("PREFUND_AUDIT");
        publisher.clear(); // drop the PARTNER_SAVED event from the seed

        service.upsertStep5("PREFUND_AUDIT",
                new PartnerCommand.UpdateStep5(
                        "PREFUNDED", new BigDecimal("50000"), new BigDecimal("12000"),
                        null, null, null, null, null, null, null, null),
                "maker_kim");
        service.upsertStep5("PREFUND_AUDIT", minimalCmd("POSTPAID"), "checker_lee");

        List<AuditEvent> events = publisher.published();
        assertThat(events).hasSize(2);

        AuditEvent first = events.get(0);
        assertThat(first.aggregateType()).isEqualTo("partner_prefunding_config");
        assertThat(first.aggregateId()).isEqualTo("PREFUND_AUDIT");
        assertThat(first.eventType()).isEqualTo("PARTNER_PREFUNDING_CONFIG_SAVED");
        assertThat(first.actorId()).isEqualTo("maker_kim");
        assertThat(first.beforeJsonb()).as("first write — BEFORE must be null").isNull();
        assertThat(new String(first.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"fundingModel\":\"PREFUNDED\"")
                // MONEY_CONVENTION: money is a plain-decimal STRING, scale 4.
                .contains("\"openingBalanceUsd\":\"50000.0000\"")
                .contains("\"lowBalanceThresholdUsd\":\"12000.0000\"")
                .contains("\"alertTier70\":true")
                .contains("\"autoSuspendOnBreach\":true");

        AuditEvent second = events.get(1);
        assertThat(second.actorId()).isEqualTo("checker_lee");
        assertThat(new String(second.beforeJsonb(), StandardCharsets.UTF_8))
                .as("BEFORE carries the superseded row")
                .contains("\"fundingModel\":\"PREFUNDED\"");
        assertThat(new String(second.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"fundingModel\":\"POSTPAID\"")
                .contains("\"lowBalanceThresholdUsd\":\"10000.0000\"");
    }
}
