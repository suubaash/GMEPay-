package com.gme.pay.kybadapter.service;

import com.gme.pay.events.EventPublisher;
import com.gme.pay.kyb.KybProvider;
import com.gme.pay.kyb.KybSubject;
import com.gme.pay.kyb.ScreeningResult;
import com.gme.pay.kybadapter.event.KybVerificationEvent;
import com.gme.pay.kybadapter.kyb.BusinessRegistrationVerifier;
import com.gme.pay.kybadapter.kyb.BusinessRegistrationVerifier.BizRegResult;
import com.gme.pay.kybadapter.kyb.BusinessRegistrationVerifier.BizRegStatus;
import com.gme.pay.kybadapter.kyb.KybDecision;
import com.gme.pay.kybadapter.kyb.KybVerificationRequest;
import com.gme.pay.kybadapter.kyb.KybVerificationResult;
import com.gme.pay.kybadapter.persistence.KybScreeningRecord;
import com.gme.pay.kybadapter.persistence.KybScreeningRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates a FULL KYB verification run and owns its durable record.
 *
 * <p>One run folds three checks into one operator-facing decision:
 * <ol>
 *   <li>sanctions / PEP screening via the active lib-kyb {@link KybProvider}
 *       (stub or Octa);</li>
 *   <li>business-registration verification via the active
 *       {@link BusinessRegistrationVerifier} (stub or KFTC);</li>
 *   <li>document-completeness against
 *       {@link KybVerificationRequest#REQUIRED_DOCUMENTS}.</li>
 * </ol>
 *
 * <h2>Decisioning</h2>
 * <pre>
 *   FAIL          ← screening HIT, or business registration NOT_FOUND
 *   MANUAL_REVIEW ← NO AUTHORITATIVE SCREENING RAN, screening NEEDS_REVIEW,
 *                   biz-reg MISMATCH/SKIPPED, or any required document missing
 *   PASS          ← screening CLEAR *from an authoritative provider*, biz-reg
 *                   VERIFIED, documents complete
 * </pre>
 * FAIL conditions outrank MANUAL_REVIEW (a watchlist hit is never "review
 * later"). The verdict is evidence-driven GUIDANCE — final activation stays an
 * ADR-008 4-eyes operator decision.
 *
 * <h2>PASS requires a screening that happened (gap T1-4)</h2>
 *
 * <p>Before this was explicit, a run through the in-process stub produced
 * {@code PASS} for every partner whose name lacked the word "SANCTIONED",
 * because the stub's clean branch was called {@code CLEAR}. It is now
 * {@link ScreeningResult.Status#NOT_SCREENED_NO_PROVIDER} carrying
 * non-authoritative {@link com.gme.pay.kyb.ScreeningProvenance}, and
 * {@link #decide} refuses to collapse a PASS out of it — the run lands in
 * MANUAL_REVIEW with the provider's caveat as the reason. The stub still never
 * REJECTs (inventing a rejection would be a worse defect); it simply can no
 * longer approve.
 *
 * <h2>Idempotency</h2>
 * <p>The run is keyed by the provider's deterministic {@code providerRef}
 * (lib-kyb's {@code "stub-<hash>"} is a pure function of the subject). A repeat
 * verification of an unchanged subject finds the persisted row and REPLAYS it —
 * no second vendor call, no duplicate event — unless {@link
 * KybVerificationRequest#force()} forces a fresh run. The fresh run's event is
 * published best-effort (a broker outage must not fail a completed verification
 * whose verdict is already persisted).
 */
@Service
public class KybVerificationService {

    private static final Logger log = LoggerFactory.getLogger(KybVerificationService.class);

    private final KybProvider kybProvider;
    private final BusinessRegistrationVerifier bizRegVerifier;
    private final KybScreeningRepository repository;
    private final EventPublisher eventPublisher;

    public KybVerificationService(KybProvider kybProvider,
            BusinessRegistrationVerifier bizRegVerifier,
            KybScreeningRepository repository,
            EventPublisher eventPublisher) {
        this.kybProvider = kybProvider;
        this.bizRegVerifier = bizRegVerifier;
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Run (or idempotently replay) a full KYB verification.
     *
     * @throws IllegalArgumentException if the subject or its partner code is absent.
     */
    @Transactional
    public KybVerificationResult verify(KybVerificationRequest request) {
        if (request == null || request.subject() == null) {
            throw new IllegalArgumentException("subject is required");
        }
        KybSubject subject = request.subject();
        if (subject.partnerCode() == null || subject.partnerCode().isBlank()) {
            throw new IllegalArgumentException("partnerCode is required");
        }

        // Screen first: its providerRef is the idempotency key.
        ScreeningResult screening = kybProvider.screen(subject);
        String providerRef = screening.providerRef();

        if (!request.force()) {
            Optional<KybScreeningRecord> existing = repository.findByProviderRef(providerRef);
            if (existing.isPresent()) {
                log.info("idempotent replay of kyb verification {} for partner {}",
                        providerRef, subject.partnerCode());
                return toResult(existing.get(), screening.hitList()).asReplay();
            }
        }

        BizRegResult bizReg = bizRegVerifier.verify(subject);
        List<String> missing = missingDocuments(request);
        boolean documentsComplete = missing.isEmpty();

        Decision d = decide(screening, bizReg.status(), documentsComplete);
        Instant screenedAt = screening.screenedAt() == null
                ? Instant.now().truncatedTo(ChronoUnit.MICROS)
                : screening.screenedAt();

        if (!screening.screeningPerformed()) {
            // One WARN per run that reached a verdict without a screening — the
            // operational counterpart to the structural block: an environment
            // running unscreened onboarding says so in its logs.
            log.warn("KYB verification {} for partner {} completed WITHOUT A SANCTIONS SCREENING"
                            + " (provider={}, status={}, decision={}). {}",
                    providerRef, subject.partnerCode(), screening.provenance().providerId(),
                    screening.status(), d.decision, screening.caveat());
        }

        KybScreeningRecord saved = persist(providerRef, subject.partnerCode(), screening,
                bizReg, documentsComplete, d, screenedAt, request.force());

        KybVerificationResult result = new KybVerificationResult(
                providerRef, subject.partnerCode(), d.decision, d.reason,
                screening.status(), screening.hitList(), bizReg.status(),
                missing, false, saved.getScreenedAt(),
                screening.provenance().providerId(), screening.authoritative(),
                screening.caveat());

        publishBestEffort(result);
        return result;
    }

    /** Look up a persisted run by its provider reference (GET /v1/kyb/result/{ref}). */
    @Transactional(readOnly = true)
    public Optional<KybVerificationResult> findByProviderRef(String providerRef) {
        // Stored runs carry only the hit COUNT; the full hit detail is not
        // re-derivable here, so the retrieval surfaces an empty hit list with
        // the count implied by the persisted screening status.
        return repository.findByProviderRef(providerRef)
                .map(rec -> toResult(rec, List.of()).asReplay());
    }

    // ----- decisioning -------------------------------------------------------

    private record Decision(KybDecision decision, String reason) {
    }

    private static Decision decide(ScreeningResult result, BizRegStatus bizReg,
            boolean documentsComplete) {
        ScreeningResult.Status screening = result.status();
        // FAIL conditions first — a watchlist hit or an absent registration is
        // never downgraded to "review later".
        if (screening == ScreeningResult.Status.HIT) {
            return new Decision(KybDecision.FAIL, "sanctions/watchlist HIT on a screened name");
        }
        if (bizReg == BizRegStatus.NOT_FOUND) {
            return new Decision(KybDecision.FAIL, "business registration not found");
        }
        // T1-4: no authoritative screening ran, so there is nothing to pass. Not a
        // FAIL — the absence of a provider is not evidence against the partner —
        // but it can never be an approval either.
        if (!result.screeningPerformed()) {
            return new Decision(KybDecision.MANUAL_REVIEW,
                    "no authoritative sanctions screening was performed ("
                            + result.provenance().providerId() + "): " + result.caveat());
        }
        // MANUAL_REVIEW conditions.
        if (screening == ScreeningResult.Status.NEEDS_REVIEW) {
            return new Decision(KybDecision.MANUAL_REVIEW, "fuzzy screening match requires analyst review");
        }
        if (bizReg == BizRegStatus.MISMATCH) {
            return new Decision(KybDecision.MANUAL_REVIEW, "business-registration name/status mismatch");
        }
        if (bizReg == BizRegStatus.SKIPPED) {
            return new Decision(KybDecision.MANUAL_REVIEW, "no tax/registration number supplied to verify");
        }
        if (!documentsComplete) {
            return new Decision(KybDecision.MANUAL_REVIEW, "required onboarding documents incomplete");
        }
        // Unreachable unless screening is an authoritative CLEAR (guarded above).
        return new Decision(KybDecision.PASS, "screening clear, registration verified, documents complete");
    }

    private static List<String> missingDocuments(KybVerificationRequest request) {
        List<String> supplied = request.documents().stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .toList();
        List<String> missing = new ArrayList<>();
        for (String required : KybVerificationRequest.REQUIRED_DOCUMENTS) {
            if (!supplied.contains(required)) {
                missing.add(required);
            }
        }
        return List.copyOf(missing);
    }

    // ----- persistence / events ---------------------------------------------

    private KybScreeningRecord persist(String providerRef, String partnerCode,
            ScreeningResult screening, BizRegResult bizReg, boolean documentsComplete,
            Decision d, Instant screenedAt, boolean force) {
        // On a forced re-run an existing row for the same providerRef is replaced
        // so the unique index never trips and the stored verdict reflects the
        // latest run.
        if (force) {
            repository.findByProviderRef(providerRef).ifPresent(repository::delete);
            repository.flush();
        }
        KybScreeningRecord record = new KybScreeningRecord(
                providerRef, partnerCode, screening.status(), screening.provenance(),
                bizReg.status(), bizReg.ref(),
                documentsComplete, d.decision, d.reason, screening.hitList().size(),
                screenedAt, Instant.now().truncatedTo(ChronoUnit.MICROS));
        return repository.save(record);
    }

    private void publishBestEffort(KybVerificationResult result) {
        try {
            eventPublisher.publish(KybVerificationEvent.of(result));
        } catch (RuntimeException e) {
            // The verdict is already persisted (regulator-defensible); losing one
            // Kafka emit is recoverable, failing the request is not.
            log.error("failed to publish kyb.verification event for partner {}: {}",
                    result.partnerCode(), e.getMessage(), e);
        }
    }

    private static KybVerificationResult toResult(KybScreeningRecord rec,
            List<ScreeningResult.Hit> hits) {
        return new KybVerificationResult(
                rec.getProviderRef(), rec.getPartnerCode(), rec.getDecision(),
                rec.getDecisionReason(), rec.getScreeningStatus(), hits,
                rec.getBizRegStatus(),
                // Persisted rows do not retain the missing-document list; an empty
                // list is correct for a PASS and acceptable evidence for a replay.
                List.of(), false, rec.getScreenedAt(),
                // Provenance is read back from the row, so a replay is exactly as
                // caveated as the original run (a pre-V002 row reads back as the
                // non-authoritative "unknown" producer).
                rec.provenance().providerId(), rec.isScreeningAuthoritative(),
                rec.provenance().caveat());
    }
}
