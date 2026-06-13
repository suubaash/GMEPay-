package com.gme.pay.registry.commercial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.ContractCommand;
import com.gme.pay.contracts.ContractView;
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
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 6 acceptance test for {@link ContractService} — the
 * {@code partner_contract} upsert (V021), wired end-to-end against H2 in
 * PostgreSQL mode with the full Flyway chain applied. Same slice pattern as
 * {@code PrefundingConfigServiceTest}.
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>Upsert persists the contract TERM (effective_from/effective_to,
 *       DATE-granular) with auto-renewal defaulting FALSE; a second upsert
 *       supersedes the first — paired SCD-6 writes sharing one
 *       MICROS-truncated instant (the term and the ADR-010 row axes stay
 *       distinct).</li>
 *   <li>Validation rejects a missing effectiveFrom, an effectiveTo before
 *       effectiveFrom (a same-day term is legal), an unknown
 *       refund/chargeback policy, a negative notice period and an
 *       over-length termination reason — 400, side-effect free.</li>
 *   <li>One {@code partner_contract} audit event per write with canonical
 *       BEFORE/AFTER snapshots (dates as ISO strings); 404/409 gates as
 *       every other step service.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ContractServiceTest.TestConfig.class, ContractService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class ContractServiceTest {

    @Autowired
    private ContractService service;

    @Autowired
    private ContractRepository repository;

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

    private static final LocalDate FROM = LocalDate.of(2026, 7, 1);
    private static final LocalDate TO = LocalDate.of(2028, 6, 30);

    // -------------------------------------------------------------------- tests

    @Test
    void upsert_persistsTerm_andSecondUpsertSupersedes() {
        Long partnerId = seedPartner("CON_UPSERT");

        ContractView first = service.upsertContract("CON_UPSERT",
                new ContractCommand(FROM, null, null, null, null, null), "maker_kim");

        assertThat(first.effectiveFrom()).isEqualTo(FROM);
        assertThat(first.effectiveTo()).as("open-ended / evergreen").isNull();
        assertThat(first.autoRenewal()).as("defaults FALSE per V021").isFalse();
        assertThat(first.noticePeriodDays()).isNull();
        assertThat(first.refundChargebackPolicy()).isNull();

        ContractView second = service.upsertContract("CON_UPSERT",
                new ContractCommand(FROM, TO, true, 90, "SHARED", null), "maker_kim");

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.effectiveTo()).isEqualTo(TO);
        assertThat(second.autoRenewal()).isTrue();
        assertThat(second.noticePeriodDays()).isEqualTo(90);
        assertThat(second.refundChargebackPolicy()).isEqualTo("SHARED");

        List<ContractEntity> all = repository.findAll().stream()
                .filter(e -> partnerId.equals(e.getPartnerId())).toList();
        assertThat(all).hasSize(2);
        ContractEntity prior = all.stream()
                .filter(e -> e.getSupersededAt() != null).findFirst().orElseThrow();
        ContractEntity current = all.stream()
                .filter(e -> e.getSupersededAt() == null).findFirst().orElseThrow();
        assertThat(prior.getSupersededAt()).isEqualTo(current.getRecordedAt());
        assertThat(current.getRecordedAt().getNano() % 1000).isZero();
        assertThat(current.getValidFrom()).isEqualTo(prior.getValidFrom());

        assertThat(service.currentContract("CON_UPSERT").id()).isEqualTo(second.id());
    }

    @Test
    void sameDayTerm_isLegal() {
        seedPartner("CON_ONE_DAY");
        ContractView view = service.upsertContract("CON_ONE_DAY",
                new ContractCommand(FROM, FROM, null, null, null, null), "x");
        assertThat(view.effectiveTo()).isEqualTo(FROM);
    }

    @Test
    void validation_rejectsBadPayloadsWith400_withoutWritingRows() {
        Long partnerId = seedPartner("CON_INVALID");

        record Bad(String label, ContractCommand cmd) {}
        List<Bad> bads = List.of(
                new Bad("missing effectiveFrom", new ContractCommand(
                        null, TO, null, null, null, null)),
                new Bad("effectiveTo before effectiveFrom", new ContractCommand(
                        FROM, FROM.minusDays(1), null, null, null, null)),
                new Bad("negative notice period", new ContractCommand(
                        FROM, null, null, -1, null, null)),
                new Bad("unknown policy", new ContractCommand(
                        FROM, null, null, null, "CUSTOMER_BEARS", null)),
                new Bad("termination reason over 200 chars", new ContractCommand(
                        FROM, null, null, null, null, "x".repeat(201))));

        for (Bad bad : bads) {
            assertThatThrownBy(() -> service.upsertContract("CON_INVALID", bad.cmd(), "x"))
                    .as(bad.label())
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        assertThat(repository.findCurrentByPartnerId(partnerId)).isEmpty();
    }

    @Test
    void nullBody_isRejectedWith400() {
        seedPartner("CON_NULL");
        assertThatThrownBy(() -> service.upsertContract("CON_NULL", null, "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void unknownPartner_404_andNoContractYet_404() {
        assertThatThrownBy(() -> service.upsertContract("CON_GHOST",
                new ContractCommand(FROM, null, null, null, null, null), "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        seedPartner("CON_EMPTY");
        assertThatThrownBy(() -> service.currentContract("CON_EMPTY"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getReason()).contains("contract");
                });
    }

    @Test
    void nonOnboardingPartner_409() {
        seedPartner("CON_LIVE");
        service.upsertContract("CON_LIVE",
                new ContractCommand(FROM, TO, null, null, null, null), "x");

        PartnerEntity current = partnerRepository.findCurrentByPartnerCode("CON_LIVE")
                .orElseThrow();
        current.setStatus(PartnerStatus.LIVE);
        partnerRepository.saveAndFlush(current);

        assertThatThrownBy(() -> service.upsertContract("CON_LIVE",
                new ContractCommand(FROM, null, null, null, null, null), "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(service.currentContract("CON_LIVE").effectiveTo()).isEqualTo(TO);
    }

    @Test
    void audit_oneEventPerWrite_withCanonicalBeforeAfterSnapshots() {
        seedPartner("CON_AUDIT");
        publisher.clear();

        service.upsertContract("CON_AUDIT",
                new ContractCommand(FROM, TO, true, 90, "PARTNER_BEARS", null), "maker_kim");
        service.upsertContract("CON_AUDIT",
                new ContractCommand(FROM, null, null, null, null, null), "checker_lee");

        List<AuditEvent> events = publisher.published();
        assertThat(events).hasSize(2);

        AuditEvent first = events.get(0);
        assertThat(first.aggregateType()).isEqualTo("partner_contract");
        assertThat(first.eventType()).isEqualTo("PARTNER_CONTRACT_SAVED");
        assertThat(first.beforeJsonb()).isNull();
        assertThat(new String(first.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"effectiveFrom\":\"2026-07-01\"")
                .contains("\"effectiveTo\":\"2028-06-30\"")
                .contains("\"autoRenewal\":true")
                .contains("\"noticePeriodDays\":90")
                .contains("\"refundChargebackPolicy\":\"PARTNER_BEARS\"")
                .contains("\"terminationReason\":null");

        AuditEvent second = events.get(1);
        assertThat(second.actorId()).isEqualTo("checker_lee");
        assertThat(new String(second.beforeJsonb(), StandardCharsets.UTF_8))
                .contains("\"refundChargebackPolicy\":\"PARTNER_BEARS\"");
        assertThat(new String(second.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"effectiveTo\":null")
                .contains("\"autoRenewal\":false");
    }
}
