package com.gme.pay.kybadapter.screening;

import com.gme.pay.kyb.PaymentScreeningPort;
import com.gme.pay.kyb.PaymentScreeningSubject;
import com.gme.pay.kyb.ScreeningProvenance;
import com.gme.pay.kyb.ScreeningResult;
import com.gme.pay.kyb.TransactionScreeningEvidence;
import com.gme.pay.kyb.TransactionScreeningPolicy;
import com.gme.pay.kyb.UnscreenedReason;
import com.gme.pay.kybadapter.audit.TransactionScreeningAuditor;
import com.gme.pay.kybadapter.persistence.TransactionScreeningEntity;
import com.gme.pay.kybadapter.persistence.TransactionScreeningRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Screens the parties to one payment, records what happened, audits it, and reports what the payment
 * path must do — gap <b>T5-3</b>.
 *
 * <h2>The three things this does, in order, and why the order matters</h2>
 *
 * <ol>
 *   <li><b>Ask the provider</b> ({@link PaymentScreeningPort}) — today always the no-provider default,
 *       which answers {@code NOT_SCREENED_NO_PROVIDER}.</li>
 *   <li><b>Record the outcome</b> in {@code transaction_screening} and seal it in the hash-chained
 *       {@code audit_log}. This happens for EVERY party on EVERY call, including — especially — when
 *       nothing was screened. Recording before deciding means the evidence exists whether the payment
 *       proceeds or is refused; a service that only recorded refusals could not evidence the period
 *       during which it refused nothing.</li>
 *   <li><b>Apply the policy</b> ({@link TransactionScreeningPolicy}) and report an allow/refuse. With
 *       the shipped empty requirement this is always allow.</li>
 * </ol>
 *
 * <h2>This service does not decide AML policy</h2>
 *
 * <p>No thresholds, no risk scores, no list roster, no match tolerance, no structuring or velocity
 * heuristics appear anywhere below. The one judgement encoded is "a required check that did not happen
 * is a refusal", which is the definition of the word required, not a policy choice. Everything else is
 * a compliance input.
 *
 * <h2>It never throws at the payment path for a screening problem</h2>
 *
 * <p>A provider that throws is caught and recorded as {@link UnscreenedReason#PROVIDER_ERROR}; the
 * policy then decides whether an unavailable check refuses the payment. The distinction matters: a
 * refusal is a deliberate, explained, audited decision, whereas an exception escaping into the payment
 * path is an outage of the screening component becoming an outage of the money path with no record of
 * why.
 */
@Service
public class TransactionScreeningService {

    private static final Logger log = LoggerFactory.getLogger(TransactionScreeningService.class);

    /** Caveat recorded when the platform holds nothing a list-matching provider could act on. */
    static final String NO_IDENTITY_CAVEAT =
            "NOT SCREENED: this payment carried no screenable identity for the party (no name), so no"
            + " list-matching provider could be asked. A provider handed only an opaque customer"
            + " reference can answer nothing but 'not found', which is not a clean result. Fixing this"
            + " is an API-contract change, not a vendor purchase (gap T5-3).";

    private final PaymentScreeningPort port;
    private final TransactionScreeningPolicy policy;
    private final TransactionScreeningRepository repository;
    private final TransactionScreeningAuditor auditor;
    private final Clock clock;

    public TransactionScreeningService(PaymentScreeningPort port,
                                       TransactionScreeningPolicy policy,
                                       TransactionScreeningRepository repository,
                                       TransactionScreeningAuditor auditor,
                                       Clock clock) {
        this.port = port;
        this.policy = policy;
        this.repository = repository;
        this.auditor = auditor;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Screen every party of one payment.
     *
     * @param txnRef    the transaction reference; required
     * @param partnerId the partner whose traffic it is, or {@code null} for wallet traffic
     * @param subjects  the parties to screen; an empty list produces an allow with no evidence, which
     *                  is itself worth noticing — see the WARN below
     * @return the aggregate outcome; never {@code null}
     */
    @Transactional
    public TransactionScreeningOutcome screen(String txnRef,
                                              String partnerId,
                                              List<PaymentScreeningSubject> subjects) {
        if (txnRef == null || txnRef.isBlank()) {
            throw new IllegalArgumentException("txnRef is required to screen a transaction");
        }
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        List<TransactionScreeningEvidence> recorded = new ArrayList<>();
        List<TransactionScreeningPolicy.Decision> refusals = new ArrayList<>();

        if (subjects == null || subjects.isEmpty()) {
            // Not an error: the wallet path genuinely has parties we cannot enumerate yet. But a
            // transaction with zero screened parties must not read as "screened, nothing found".
            log.warn("T5-3 screening: transaction {} presented NO parties to screen — nothing was"
                    + " checked and no evidence row exists for it", txnRef);
            return new TransactionScreeningOutcome(txnRef, true, null, List.of(), policy.inert());
        }

        for (PaymentScreeningSubject subject : subjects) {
            if (subject == null) {
                continue;
            }
            TransactionScreeningEvidence evidence = screenOne(txnRef, partnerId, subject, now);
            persist(evidence, now);
            auditor.screeningOutcome(evidence, null);
            recorded.add(evidence);
            if (evidence.refused()) {
                refusals.add(new TransactionScreeningPolicy.Decision(
                        evidence.party(), evidence.posture(), evidence.caveat(), null));
            }
        }

        if (refusals.isEmpty()) {
            return new TransactionScreeningOutcome(txnRef, true, null, recorded, policy.inert());
        }
        // Report the FIRST refusing party rather than a merged sentence: an operator chasing a refused
        // corridor needs one actionable cause, and the rest are in the evidence list.
        TransactionScreeningEvidence first = recorded.stream()
                .filter(TransactionScreeningEvidence::refused)
                .findFirst()
                .orElseThrow();
        String reason = first.posture() + " for " + first.party()
                + (first.caveat() == null ? "" : ": " + first.caveat());
        return new TransactionScreeningOutcome(txnRef, false, reason, recorded, policy.inert());
    }

    /** Ask the provider about one party and turn the answer into normalised evidence. */
    private TransactionScreeningEvidence screenOne(String txnRef,
                                                   String partnerId,
                                                   PaymentScreeningSubject subject,
                                                   Instant now) {
        if (!subject.screenable()) {
            // Do NOT call the provider. A provider handed no name returns a no-match that is
            // indistinguishable from a clean result — the exact defect gap T1-4 removed from the KYB
            // path. The honest record is that we had nothing to screen with.
            ScreeningResult unscreenable = new ScreeningResult(
                    ScreeningResult.Status.NOT_SCREENED_NO_PROVIDER, List.of(), now, null,
                    ScreeningProvenance.nonAuthoritative(port.providerId(), NO_IDENTITY_CAVEAT));
            return TransactionScreeningEvidence.of(txnRef, partnerId, subject, unscreenable,
                    policy.decide(subject.party(), unscreenable), now);
        }

        ScreeningResult result;
        try {
            result = port.screen(subject);
        } catch (RuntimeException e) {
            // The port's contract says do not throw. One that does is contained here rather than
            // becoming a 500 on the money path, but it forfeits the ability to explain itself, so the
            // reason is recorded as PROVIDER_ERROR against the provider that failed.
            log.error("T5-3 screening: provider '{}' threw for {} on transaction {} — recorded as"
                    + " PROVIDER_ERROR", port.providerId(), subject.attributeSummary(), txnRef, e);
            ScreeningResult failed = new ScreeningResult(
                    ScreeningResult.Status.NOT_SCREENED_NO_PROVIDER, List.of(), now, null,
                    ScreeningProvenance.nonAuthoritative(port.providerId(),
                            UnscreenedReason.PROVIDER_ERROR.description()
                                    + ": " + e.getClass().getSimpleName()));
            TransactionScreeningPolicy.Decision decision = policy.decide(subject.party(), failed);
            return new TransactionScreeningEvidence(
                    txnRef, partnerId, subject.party(), subject.reference(),
                    subject.attributeSummary(), port.providerId(), false,
                    ScreeningResult.Status.NOT_SCREENED_NO_PROVIDER, now,
                    failed.caveat(), decision.posture(), UnscreenedReason.PROVIDER_ERROR);
        }
        return TransactionScreeningEvidence.of(txnRef, partnerId, subject, result,
                policy.decide(subject.party(), result), now);
    }

    /**
     * Write (or overwrite) the single current row for this {@code (txn_ref, party)}. The append-only
     * history of how the answer changed is the audit chain, not this table.
     */
    private void persist(TransactionScreeningEvidence evidence, Instant now) {
        Optional<TransactionScreeningEntity> existing =
                repository.findByTxnRefAndParty(evidence.txnRef(), evidence.party());
        TransactionScreeningEntity row = existing.orElseGet(
                () -> TransactionScreeningEntity.from(evidence, now));
        existing.ifPresent(e -> e.apply(evidence, now));
        repository.save(row);
    }

    /**
     * The evidence recorded for one transaction, read back through
     * {@link TransactionScreeningEntity#toEvidence()} so every invariant is re-applied — a row that a
     * migration or a hand-edit left as a non-authoritative CLEAR comes back as
     * {@code NOT_SCREENED_NO_PROVIDER}.
     */
    @Transactional(readOnly = true)
    public List<TransactionScreeningEvidence> evidenceFor(String txnRef) {
        return repository.findByTxnRefOrderByIdAsc(txnRef).stream()
                .map(TransactionScreeningEntity::toEvidence)
                .toList();
    }

    /** The active posture, for the ops surface — so a zero coverage count cannot be misread. */
    public TransactionScreeningPolicy policy() {
        return policy;
    }

    /** Which provider is answering, and whether it is an authority. */
    public PaymentScreeningPort port() {
        return port;
    }
}
