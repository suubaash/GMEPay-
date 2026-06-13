package com.gme.pay.registry.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.changerequest.ChangeRequestState;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.changerequest.ChangeRequestEntity;
import com.gme.pay.registry.changerequest.ChangeRequestRepository;
import com.gme.pay.registry.changerequest.ChangeRequestService;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Slice 8 Lane B acceptance test for {@link PartnerCredentialRotationScheduler}
 * — the weekly 11-month rotation sweep. {@link ChangeRequestService} is
 * mocked (the propose side-effects are ADR-008's, pinned elsewhere); the
 * repositories are real H2 with the full Flyway chain so the threshold query
 * runs against the V028 schema.
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>The 11-month threshold: an 11.5-month-old ACTIVE credential set gets
 *       ONE proposal per (partner, environment); a 10-month-old set and a
 *       ROTATED 12-month-old set get none.</li>
 *   <li>No double-propose: an open PROPOSED change_request for the partner
 *       suppresses re-proposal on the next sweep.</li>
 *   <li>One {@code CREDENTIAL_ROTATION_PROPOSED} audit row per proposal,
 *       carrying environment + credential ids (display residue only).</li>
 *   <li>The cron wiring: Monday 02:00 Asia/Seoul.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PartnerCredentialRotationSchedulerTest.TestConfig.class,
        PartnerCredentialRotationScheduler.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class PartnerCredentialRotationSchedulerTest {

    @Autowired
    private PartnerCredentialRotationScheduler scheduler;

    @Autowired
    private PartnerCredentialRepository credentialRepository;

    @Autowired
    private ChangeRequestRepository changeRequestRepository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private RecordingAuditPublisher publisher;

    @MockBean
    private ChangeRequestService changeRequestService;

    /** Same publisher swap as {@code RuleServiceTest}. */
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

    private Long seedPartner(String code) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
        return partnerRepository.findCurrentByPartnerCode(code).orElseThrow().getId();
    }

    private PartnerCredentialEntity credential(Long partnerId, String env, String kind,
                                               String status, Instant issuedAt) {
        PartnerCredentialEntity e = new PartnerCredentialEntity();
        e.setPartnerId(partnerId);
        e.setEnvironment(env);
        e.setCredentialKind(kind);
        e.setAuthIdentityKeyId("pk_x_" + kind + "_" + partnerId);
        e.setPrefix("pk_test_");
        e.setLast4("abcd");
        e.setIssuedAt(issuedAt.truncatedTo(ChronoUnit.MICROS));
        e.setStatus(status);
        return credentialRepository.saveAndFlush(e);
    }

    private static Instant monthsAgo(Instant now, long months, long extraDays) {
        return now.atOffset(ZoneOffset.UTC).minusMonths(months).minusDays(extraDays)
                .toInstant().truncatedTo(ChronoUnit.MICROS);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void elevenMonthThreshold_overdueProposed_youngAndRotatedSkipped() {
        Instant now = Instant.now();
        Long overdue = seedPartner("ROT_OLD");
        Long young = seedPartner("ROT_YOUNG");
        Long retired = seedPartner("ROT_DONE");

        // 11.5 months old + ACTIVE → must be proposed (one proposal, whole set).
        credential(overdue, "SANDBOX", "API_KEY", "ACTIVE", monthsAgo(now, 11, 15));
        credential(overdue, "SANDBOX", "HMAC_SECRET", "ACTIVE", monthsAgo(now, 11, 15));
        // 10 months old → too young.
        credential(young, "SANDBOX", "API_KEY", "ACTIVE", monthsAgo(now, 10, 0));
        // 12 months old but already ROTATED → not ACTIVE, skipped.
        credential(retired, "SANDBOX", "API_KEY", "ROTATED", monthsAgo(now, 12, 0));

        int proposed = scheduler.sweep(now);

        assertThat(proposed).isEqualTo(1);
        verify(changeRequestService).propose(
                eq(PartnerCredentialRotationScheduler.AGGREGATE_TYPE),
                eq("ROT_OLD"),
                eq("system"),
                contains("\"environment\":\"SANDBOX\""),
                any(String[].class));
        verify(changeRequestService, never()).propose(
                any(), eq("ROT_YOUNG"), any(), any(), any());
        verify(changeRequestService, never()).propose(
                any(), eq("ROT_DONE"), any(), any(), any());
    }

    @Test
    void noDoublePropose_openProposalSuppressesTheNextSweep() {
        Instant now = Instant.now();
        Long partnerId = seedPartner("ROT_OPEN");
        credential(partnerId, "PRODUCTION", "API_KEY", "ACTIVE", monthsAgo(now, 12, 0));

        // An open PROPOSED rotation change_request already exists (a prior
        // sweep's output, checker has not acted yet).
        ChangeRequestEntity open = new ChangeRequestEntity();
        open.setId(990001L);
        open.setAggregateType(PartnerCredentialRotationScheduler.AGGREGATE_TYPE);
        open.setAggregateId("ROT_OPEN");
        open.setState(ChangeRequestState.PROPOSED);
        open.setProposedBy("system");
        open.setProposedAt(now.truncatedTo(ChronoUnit.MICROS));
        changeRequestRepository.saveAndFlush(open);

        assertThat(scheduler.sweep(now)).isZero();
        verifyNoInteractions(changeRequestService);

        // Once the proposal is closed (REJECTED), the next sweep re-proposes.
        open.setState(ChangeRequestState.REJECTED);
        open.setRejectedReason("stale");
        changeRequestRepository.saveAndFlush(open);
        assertThat(scheduler.sweep(now)).isEqualTo(1);
    }

    @Test
    void audit_oneProposedEventPerProposal_withEnvironmentAndIds() {
        Instant now = Instant.now();
        Long partnerId = seedPartner("ROT_AUD");
        PartnerCredentialEntity row = credential(
                partnerId, "SANDBOX", "WEBHOOK_SECRET", "ACTIVE", monthsAgo(now, 11, 1));
        publisher.clear();

        scheduler.sweep(now);

        List<AuditEvent> events = publisher.published().stream()
                .filter(e -> PartnerCredentialRotationScheduler.EVENT_TYPE_PROPOSED
                        .equals(e.eventType()))
                .toList();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).aggregateId()).isEqualTo("ROT_AUD");
        assertThat(events.get(0).actorId()).isEqualTo("system");
        String after = new String(events.get(0).afterJsonb(), StandardCharsets.UTF_8);
        assertThat(after).contains("\"environment\":\"SANDBOX\"");
        assertThat(after).contains(String.valueOf(row.getId()));
        // Residue only — never key ids' secret material (none exists) nor prefix leak concerns.
        assertThat(after).doesNotContain("sk_");
    }

    @Test
    void exactlyAtElevenMonths_isNotYetOverdue_strictlyOlderIs() {
        Instant now = Instant.now();
        Long partnerId = seedPartner("ROT_EDGE");
        // Exactly 11 months (to the microsecond) — NOT strictly before the
        // threshold, so not swept. One day older — swept.
        credential(partnerId, "SANDBOX", "API_KEY", "ACTIVE", monthsAgo(now, 11, 0));
        assertThat(scheduler.sweep(now)).isZero();

        credential(partnerId, "SANDBOX", "HMAC_SECRET", "ACTIVE", monthsAgo(now, 11, 1));
        assertThat(scheduler.sweep(now)).isEqualTo(1);
    }

    @Test
    void cronWiring_mondayTwoAmSeoul() throws Exception {
        Scheduled scheduled = PartnerCredentialRotationScheduler.class
                .getMethod("proposeOverdueRotations").getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 0 2 * * MON");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    }
}
