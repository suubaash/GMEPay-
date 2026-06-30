package com.gme.pay.kybadapter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.RecordingEventPublisher;
import com.gme.pay.kyb.KybSubject;
import com.gme.pay.kyb.ScreeningResult;
import com.gme.pay.kybadapter.event.KybVerificationEvent;
import com.gme.pay.kybadapter.kyb.BusinessRegistrationVerifier.BizRegStatus;
import com.gme.pay.kybadapter.kyb.KybDecision;
import com.gme.pay.kybadapter.kyb.KybVerificationRequest;
import com.gme.pay.kybadapter.kyb.KybVerificationResult;
import com.gme.pay.kybadapter.persistence.KybScreeningRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Orchestration tests through the real H2-backed wiring: StubKybAdapter +
 * StubBusinessRegistrationVerifier + JPA repository + a RecordingEventPublisher
 * standing in for the Kafka fan-out. Covers PASS / FAIL / MANUAL_REVIEW
 * decisioning, document completeness, idempotent replay and forced re-run.
 */
@SpringBootTest
class KybVerificationServiceTest {

    @Autowired
    private KybVerificationService service;

    @Autowired
    private KybScreeningRepository repository;

    @Autowired
    private RecordingEventPublisher events;

    @TestConfiguration
    static class TestEvents {
        @Bean
        @Primary
        RecordingEventPublisher recordingEventPublisher() {
            return new RecordingEventPublisher();
        }
    }

    @BeforeEach
    void reset() {
        repository.deleteAll();
        events.clear();
    }

    private static KybSubject subject(String partnerCode, String name, String taxId) {
        return new KybSubject(partnerCode, name, name, "KR", taxId, List.of());
    }

    private static KybVerificationRequest fullPack(KybSubject s) {
        return new KybVerificationRequest(s,
                List.of("BUSINESS_REGISTRATION", "AOA", "UBO_DECLARATION"), false);
    }

    @Test
    @DisplayName("clear subject + verified registration + full docs → PASS, persisted, event published")
    void pass() {
        KybVerificationResult r = service.verify(fullPack(subject("P_OK", "GME Co", "123-45")));

        assertThat(r.decision()).isEqualTo(KybDecision.PASS);
        assertThat(r.screeningStatus()).isEqualTo(ScreeningResult.Status.CLEAR);
        assertThat(r.bizRegStatus()).isEqualTo(BizRegStatus.VERIFIED);
        assertThat(r.missingDocumentList()).isEmpty();
        assertThat(r.idempotentReplay()).isFalse();

        assertThat(repository.findByProviderRef(r.providerRef())).isPresent();
        List<DomainEvent> published = events.published();
        assertThat(published).hasSize(1);
        assertThat(published.get(0)).isInstanceOfSatisfying(KybVerificationEvent.class, e -> {
            assertThat(e.eventType()).isEqualTo("kyb.verification");
            assertThat(e.aggregateId()).isEqualTo("P_OK");
            assertThat(e.decision()).isEqualTo(KybDecision.PASS);
        });
    }

    @Test
    @DisplayName("sanctions HIT → FAIL (outranks everything)")
    void hitFails() {
        KybVerificationResult r = service.verify(fullPack(subject("P_BAD", "Sanctioned Holdings", "123")));
        assertThat(r.decision()).isEqualTo(KybDecision.FAIL);
        assertThat(r.screeningStatus()).isEqualTo(ScreeningResult.Status.HIT);
        assertThat(r.decisionReason()).contains("HIT");
    }

    @Test
    @DisplayName("business registration NOT_FOUND → FAIL even with a clean screen")
    void notFoundFails() {
        KybVerificationResult r = service.verify(fullPack(subject("P_NF", "Clean Co", "BIZREG_NOTFOUND")));
        assertThat(r.decision()).isEqualTo(KybDecision.FAIL);
        assertThat(r.bizRegStatus()).isEqualTo(BizRegStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("fuzzy screening match → MANUAL_REVIEW")
    void fuzzyReview() {
        KybVerificationResult r = service.verify(fullPack(subject("P_RV", "Review Trading", "123")));
        assertThat(r.decision()).isEqualTo(KybDecision.MANUAL_REVIEW);
        assertThat(r.screeningStatus()).isEqualTo(ScreeningResult.Status.NEEDS_REVIEW);
    }

    @Test
    @DisplayName("registration MISMATCH → MANUAL_REVIEW")
    void mismatchReview() {
        KybVerificationResult r = service.verify(fullPack(subject("P_MM", "Clean Co", "BIZREG_MISMATCH")));
        assertThat(r.decision()).isEqualTo(KybDecision.MANUAL_REVIEW);
        assertThat(r.bizRegStatus()).isEqualTo(BizRegStatus.MISMATCH);
    }

    @Test
    @DisplayName("missing required document downgrades a clean run to MANUAL_REVIEW")
    void missingDocReview() {
        KybVerificationRequest req = new KybVerificationRequest(
                subject("P_DOC", "Clean Co", "123"),
                List.of("BUSINESS_REGISTRATION"), false); // AOA + UBO_DECLARATION missing
        KybVerificationResult r = service.verify(req);
        assertThat(r.decision()).isEqualTo(KybDecision.MANUAL_REVIEW);
        assertThat(r.missingDocumentList()).containsExactlyInAnyOrder("AOA", "UBO_DECLARATION");
    }

    @Test
    @DisplayName("blank tax id (SKIPPED biz-reg) → MANUAL_REVIEW")
    void skippedReview() {
        KybVerificationResult r = service.verify(fullPack(subject("P_SK", "Clean Co", "")));
        assertThat(r.decision()).isEqualTo(KybDecision.MANUAL_REVIEW);
        assertThat(r.bizRegStatus()).isEqualTo(BizRegStatus.SKIPPED);
    }

    @Test
    @DisplayName("re-verifying an unchanged subject replays the stored run, no duplicate row or event")
    void idempotentReplay() {
        KybSubject s = subject("P_IDEM", "GME Co", "123-45");
        KybVerificationResult first = service.verify(fullPack(s));
        events.clear();

        KybVerificationResult second = service.verify(fullPack(s));

        assertThat(second.providerRef()).isEqualTo(first.providerRef());
        assertThat(second.decision()).isEqualTo(first.decision());
        assertThat(second.idempotentReplay()).isTrue();
        assertThat(repository.findAll()).hasSize(1);
        assertThat(events.published()).isEmpty(); // replay does not re-publish
    }

    @Test
    @DisplayName("force=true re-runs, replaces the stored row and re-publishes")
    void forcedReRun() {
        KybSubject s = subject("P_FORCE", "GME Co", "123-45");
        service.verify(fullPack(s));
        events.clear();

        KybVerificationRequest forced = new KybVerificationRequest(s,
                List.of("BUSINESS_REGISTRATION", "AOA", "UBO_DECLARATION"), true);
        KybVerificationResult r = service.verify(forced);

        assertThat(r.idempotentReplay()).isFalse();
        assertThat(repository.findAll()).hasSize(1); // replaced, not duplicated
        assertThat(events.published()).hasSize(1);
    }

    @Test
    @DisplayName("findByProviderRef returns the persisted run as a replay; empty for unknown ref")
    void retrieval() {
        KybVerificationResult r = service.verify(fullPack(subject("P_GET", "GME Co", "123-45")));

        assertThat(service.findByProviderRef(r.providerRef()))
                .hasValueSatisfying(found -> {
                    assertThat(found.partnerCode()).isEqualTo("P_GET");
                    assertThat(found.decision()).isEqualTo(KybDecision.PASS);
                    assertThat(found.idempotentReplay()).isTrue();
                });
        assertThat(service.findByProviderRef("stub-nope")).isEmpty();
    }
}
