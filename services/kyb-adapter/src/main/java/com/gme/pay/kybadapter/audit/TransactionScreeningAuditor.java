package com.gme.pay.kybadapter.audit;

import com.gme.pay.audit.DbAuditPublisher;
import com.gme.pay.kyb.TransactionScreeningEvidence;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes the {@code audit_log} row for every payment-path screening outcome — gap <b>T5-3</b>, using
 * the T5-1 trail (non-spoofable actor, hash-chained).
 *
 * <h2>Why every outcome and not only the interesting ones</h2>
 *
 * <p>The tempting economy is to audit refusals and overrides and skip the rest. That would be wrong
 * here for a reason specific to this control: the platform's current state is that <b>nothing</b> is
 * screened, so "the interesting ones" is either every row or no row depending on configuration, and a
 * trail that only fills up once a vendor is bought cannot evidence the period before. A regulator's
 * question is "show me what you checked on this transaction", and the defensible answer for today is a
 * dated, sealed row saying that nothing was checked and why — not an absence, which is
 * indistinguishable from an absence of logging.
 *
 * <h2>Chain</h2>
 *
 * <p>The chain is per {@code (aggregate_type, aggregate_id)} and {@code aggregate_id} is the
 * <b>transaction reference</b>, so one transaction's screening history is one short chain. Keying on
 * the transaction rather than the partner is deliberate: a partner-keyed chain would be one unbounded
 * sequence per partner that a verifier must walk in full, and the unit a regulator actually asks about
 * is the transaction.
 *
 * <h2>Transaction participation</h2>
 *
 * <p>{@link DbAuditPublisher#append} runs on the caller's transaction, so the audit row commits if and
 * only if the {@code transaction_screening} row it describes commits. A rolled-back screening leaves no
 * audit row — correct, because nothing was recorded. The inverse would be worse than none.
 *
 * <h2>No subject PII in the payload</h2>
 *
 * <p>The payload carries {@code subjectAttributes} (which attributes were present) and never a name or
 * date of birth. See the V003 migration header.
 */
@Component
public class TransactionScreeningAuditor {

    private static final Logger log = LoggerFactory.getLogger(TransactionScreeningAuditor.class);

    /** One payment's counterparty screening. {@code aggregate_id} = the transaction reference. */
    public static final String AGG_TRANSACTION_SCREENING = "transaction_screening";

    /** A party was screened (or, today, recorded as not screened) and the payment proceeded. */
    public static final String TRANSACTION_SCREENED = "TRANSACTION_SCREENED";

    /** A party's screening outcome caused the payment to be refused. */
    public static final String TRANSACTION_SCREENING_REFUSED = "TRANSACTION_SCREENING_REFUSED";

    /**
     * A required screening did not happen and the payment proceeded anyway under the non-production
     * override. Its own verb, because this is the one event a reviewer must be able to find without
     * knowing what to filter for — {@code SELECT * FROM audit_log WHERE event_type =
     * 'TRANSACTION_SCREENING_OVERRIDDEN'} is the whole query.
     */
    public static final String TRANSACTION_SCREENING_OVERRIDDEN = "TRANSACTION_SCREENING_OVERRIDDEN";

    private final DbAuditPublisher publisher;
    private final AuditActorResolver actors;

    public TransactionScreeningAuditor(DbAuditPublisher publisher, AuditActorResolver actors) {
        this.publisher = publisher;
        this.actors = actors;
    }

    /**
     * Audit one party's screening outcome.
     *
     * <p>{@code before} is {@code null}: a screening outcome is an observation, not a mutation of a
     * prior state, and emitting a fabricated "before" snapshot would imply a transition that did not
     * occur. The {@code after} payload is the whole evidence record.
     *
     * @param evidence the outcome, already normalised by {@link TransactionScreeningEvidence}'s own
     *                 invariants — so what is sealed here can never be a non-authoritative CLEAR
     * @param actorId  who acted, or {@code null} to take the actor of the request being served
     */
    public void screeningOutcome(TransactionScreeningEvidence evidence, String actorId) {
        if (evidence == null) {
            return;
        }
        byte[] after = CanonicalJson.object()
                .str("txnRef", evidence.txnRef())
                .str("partnerId", evidence.partnerId())
                .enumeration("party", evidence.party())
                .str("subjectReference", evidence.subjectReference())
                .str("subjectAttributes", evidence.subjectAttributes())
                .str("providerId", evidence.providerId())
                .bool("providerAuthoritative", evidence.providerAuthoritative())
                .enumeration("status", evidence.status())
                // Derived, never stored — see TransactionScreeningEvidence. Sealing it means the audit
                // row states the conclusion as well as the inputs it was drawn from.
                .bool("completedScreening", evidence.completedScreening())
                .enumeration("unscreenedReason", evidence.unscreenedReason())
                .enumeration("posture", evidence.posture())
                .str("caveat", evidence.caveat())
                .at("screenedAt", evidence.screenedAt())
                .bytes();

        append(evidence.txnRef(), eventTypeFor(evidence), after, actorId);
    }

    private static String eventTypeFor(TransactionScreeningEvidence evidence) {
        if (evidence.refused()) {
            return TRANSACTION_SCREENING_REFUSED;
        }
        if (evidence.posture() != null && evidence.posture().mustAudit()) {
            return TRANSACTION_SCREENING_OVERRIDDEN;
        }
        return TRANSACTION_SCREENED;
    }

    private void append(String aggregateId, String eventType, byte[] after, String actorId) {
        String actor = actorId == null ? actors.currentActor() : actorId;
        String ip = actorId == null ? actors.currentActorIp() : null;
        try {
            publisher.append(AGG_TRANSACTION_SCREENING, aggregateId, actor, ip, eventType,
                    null, after, Instant.now().truncatedTo(ChronoUnit.MICROS));
        } catch (IllegalArgumentException e) {
            // An unusable actor is a programming error in the caller, not backpressure — rethrow so it
            // is found in a test rather than producing a silently unaudited screening.
            throw e;
        } catch (RuntimeException e) {
            // A SQL failure must not turn a recorded screening into a 500 for the payment path. The row
            // is already persisted; losing its audit copy is bad and is logged as such.
            log.error("audit: FAILED to seal screening outcome for txnRef={} ({}) — the "
                            + "transaction_screening row exists but has no audit_log entry",
                    aggregateId, eventType, e);
        }
    }
}
