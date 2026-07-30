package com.gme.pay.kybadapter.screening;

import com.gme.pay.kyb.TransactionScreeningEvidence;
import java.util.List;

/**
 * What the payment path must do about one transaction, plus the evidence behind it — gap <b>T5-3</b>.
 *
 * <p>{@code allowed} is the only field a caller may branch on, and it is a decision, not an assessment:
 * {@code allowed == true} means "no configured screening requirement was violated", which in the
 * shipped configuration (no party required) is always the case and says nothing whatever about whether
 * the counterparties are sanctioned. Anything that renders this as a green tick, a "screening: passed"
 * badge or a boolean called {@code clean} is misreporting it. {@code screeningInert} is carried
 * alongside precisely so a consumer can tell the two states apart without inferring.
 *
 * @param txnRef         the transaction
 * @param allowed        {@code false} only when a configured requirement was violated
 * @param refusalReason  why, when {@code allowed} is {@code false}; {@code null} otherwise
 * @param evidence       one entry per party, in the order screened; may be empty when the payment
 *                       presented no screenable parties at all
 * @param screeningInert {@code true} when no party is required to be screened — i.e. this outcome could
 *                       not have refused anything regardless of what the provider said
 */
public record TransactionScreeningOutcome(
        String txnRef,
        boolean allowed,
        String refusalReason,
        List<TransactionScreeningEvidence> evidence,
        boolean screeningInert) {

    public TransactionScreeningOutcome {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    /**
     * {@code true} only when every party recorded here carries a COMPLETED authoritative screening.
     * Derived from the evidence, never from {@code allowed} — the two are independent, and today this
     * is {@code false} for every transaction on the platform.
     *
     * <p>An empty evidence list returns {@code false}: a transaction nobody screened has not been
     * screened, and vacuous truth is the wrong answer to a compliance question.
     */
    public boolean fullyScreened() {
        return !evidence.isEmpty()
                && evidence.stream().allMatch(TransactionScreeningEvidence::completedScreening);
    }
}
