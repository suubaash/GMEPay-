package com.gme.pay.kyb;

import java.time.Instant;

/**
 * What was checked, for whom, by whom, when, and what was decided — the per-transaction screening
 * record a regulator asks to see. Gap <b>T5-3</b>.
 *
 * <h2>Why this is a type and not just a table</h2>
 *
 * <p>The T1-4 guarantee — a non-authoritative "no hit" cannot be represented as a completed screening
 * — lives in {@link ScreeningResult}'s constructor, on the WRITE path. That is only half of it. Once a
 * verdict is decomposed into database columns it can come back as something the write path would never
 * have produced: a row whose {@code status} column says {@code CLEAR} and whose
 * {@code provider_authoritative} column says {@code false}, because a migration backfilled it, because
 * an operator edited it, or because a future writer set the columns independently. Reading that row
 * back into a naive DTO reinstates exactly the defect T1-4 removed, one layer down.
 *
 * <p>So this record is the ONLY way the evidence is built, on both the write path and the read path,
 * and it applies the same coercion in both directions:
 *
 * <ul>
 *   <li>a {@link ScreeningResult.Status#CLEAR} without authoritative provenance is <b>coerced</b> to
 *       {@link ScreeningResult.Status#NOT_SCREENED_NO_PROVIDER} — reading a tampered or backfilled row
 *       yields the honest value, not the stored one;</li>
 *   <li>{@link #completedScreening()} is <b>derived</b>, never stored and never trusted from input, so
 *       there is no column anyone can flip to make an unscreened payment look screened;</li>
 *   <li>a non-authoritative record without a caveat gets one — absence of an explanation is never
 *       treated as an absence of a problem;</li>
 *   <li>the reserved non-authoritative provider ids ({@code stub}, {@code none}) can never be recorded
 *       as authoritative, mirroring {@link ScreeningProvenance}'s own refusal.</li>
 * </ul>
 *
 * <h2>Deliberately no subject PII</h2>
 *
 * <p>{@code subjectReference} is the opaque correlation handle and {@code subjectAttributes} is
 * {@link PaymentScreeningSubject#attributeSummary()} — which attributes were PRESENT, never their
 * values. The platform has no column encryption (gap T5-5); a screening evidence table full of payer
 * names and dates of birth would be a new PII store created by a control meant to reduce risk. What a
 * regulator needs from this row is that the check happened and what it could see, and presence answers
 * that.
 *
 * @param txnRef             the transaction this evidence belongs to; required
 * @param partnerId          the partner whose traffic it is; may be {@code null} on a wallet payment
 * @param party              the role screened; required
 * @param subjectReference   opaque handle for the party (never an identity — see
 *                           {@link PaymentScreeningSubject})
 * @param subjectAttributes  which attributes the subject carried, never their values
 * @param providerId         who answered — {@code none} when no provider is wired
 * @param providerAuthoritative whether that producer is an authority; never inferred from a clean-looking
 *                           status
 * @param status             the disposition, after coercion
 * @param screenedAt         when the producer answered; required
 * @param caveat             why this is not a completed screening; mandatory when not authoritative
 * @param posture            what the payment path did about it
 * @param unscreenedReason   the cause, when nothing was screened; {@code null} on a completed screening
 */
public record TransactionScreeningEvidence(
        String txnRef,
        String partnerId,
        PaymentParty party,
        String subjectReference,
        String subjectAttributes,
        String providerId,
        boolean providerAuthoritative,
        ScreeningResult.Status status,
        Instant screenedAt,
        String caveat,
        TransactionScreeningPolicy.Posture posture,
        UnscreenedReason unscreenedReason) {

    /** Caveat supplied when a non-authoritative record arrived without one. */
    public static final String MISSING_CAVEAT =
            "NOT A COMPLETED SCREENING: this record was produced by a non-authoritative source and"
            + " carried no explanation of why. Treated as unscreened (gap T5-3).";

    public TransactionScreeningEvidence {
        if (txnRef == null || txnRef.isBlank()) {
            throw new IllegalArgumentException("txnRef is required on screening evidence");
        }
        if (party == null) {
            throw new IllegalArgumentException("party is required on screening evidence");
        }
        if (screenedAt == null) {
            throw new IllegalArgumentException("screenedAt is required on screening evidence");
        }
        txnRef = txnRef.trim();
        partnerId = trimToNull(partnerId);
        subjectReference = trimToNull(subjectReference);
        subjectAttributes = trimToNull(subjectAttributes);

        providerId = trimToNull(providerId);
        if (providerId == null) {
            // A row that does not say who produced it is not evidence of a check.
            providerId = ScreeningProvenance.UNKNOWN_PROVIDER_ID;
            providerAuthoritative = false;
        }
        if (providerAuthoritative
                && (ScreeningProvenance.STUB_PROVIDER_ID.equals(providerId)
                        || ScreeningProvenance.NO_PROVIDER_ID.equals(providerId)
                        || ScreeningProvenance.UNKNOWN_PROVIDER_ID.equals(providerId))) {
            // Structural, same rule as ScreeningProvenance: these ids can never claim authority, on the
            // write path OR coming back out of a column.
            providerAuthoritative = false;
        }

        if (status == null) {
            status = ScreeningResult.Status.NOT_SCREENED_NO_PROVIDER;
        }
        // THE invariant. A clean-looking status from a non-authority is not a clean result.
        if (status == ScreeningResult.Status.CLEAR && !providerAuthoritative) {
            status = ScreeningResult.Status.NOT_SCREENED_NO_PROVIDER;
        }

        caveat = trimToNull(caveat);
        if (!providerAuthoritative && caveat == null) {
            caveat = MISSING_CAVEAT;
        }
        if (providerAuthoritative) {
            caveat = null;
        }

        if (posture == null) {
            posture = TransactionScreeningPolicy.Posture.PROCEED_NOT_REQUIRED;
        }
        boolean completed = providerAuthoritative
                && status != ScreeningResult.Status.NOT_SCREENED_NO_PROVIDER;
        if (completed) {
            // A completed screening has no unscreened reason, whatever a column says.
            unscreenedReason = null;
        } else if (unscreenedReason == null) {
            unscreenedReason = ScreeningProvenance.NO_PROVIDER_ID.equals(providerId)
                    ? UnscreenedReason.NO_PROVIDER
                    : UnscreenedReason.PROVIDER_NOT_AUTHORITATIVE;
        }
    }

    /**
     * Build the evidence for one party from what actually happened.
     *
     * @param txnRef   the transaction reference
     * @param partnerId the partner, or {@code null}
     * @param subject  the party as presented to the provider
     * @param result   the provider's answer; {@code null} is treated as no answer
     * @param decision what the policy did about it
     */
    public static TransactionScreeningEvidence of(String txnRef,
                                                  String partnerId,
                                                  PaymentScreeningSubject subject,
                                                  ScreeningResult result,
                                                  TransactionScreeningPolicy.Decision decision,
                                                  Instant fallbackAt) {
        if (subject == null) {
            throw new IllegalArgumentException("subject is required to build screening evidence");
        }
        ScreeningProvenance provenance = result == null ? null : result.provenance();
        boolean authoritative = provenance != null && provenance.authoritative();
        ScreeningResult.Status status = result == null
                ? ScreeningResult.Status.NOT_SCREENED_NO_PROVIDER
                : result.status();
        Instant at = result == null || result.screenedAt() == null ? fallbackAt : result.screenedAt();
        return new TransactionScreeningEvidence(
                txnRef,
                partnerId,
                subject.party(),
                subject.reference(),
                subject.attributeSummary(),
                provenance == null ? null : provenance.providerId(),
                authoritative,
                status,
                at == null ? Instant.now() : at,
                provenance == null ? null : provenance.caveat(),
                decision == null ? null : decision.posture(),
                reasonFor(subject, result));
    }

    /**
     * Why nothing was screened, distinguishing the four causes that have four different owners (see
     * {@link UnscreenedReason}). Returns {@code null} when the screening actually completed.
     */
    private static UnscreenedReason reasonFor(PaymentScreeningSubject subject, ScreeningResult result) {
        if (result != null && result.screeningPerformed()) {
            return null;
        }
        if (subject != null && !subject.screenable()) {
            // Checked BEFORE the provider verdict: a provider handed no name could only ever answer
            // "not found", so the missing identity is the real cause even if a provider did respond.
            return UnscreenedReason.NO_SUBJECT_IDENTITY;
        }
        if (result == null) {
            return UnscreenedReason.PROVIDER_ERROR;
        }
        if (result.provenance() != null
                && ScreeningProvenance.NO_PROVIDER_ID.equals(result.provenance().providerId())) {
            return UnscreenedReason.NO_PROVIDER;
        }
        return UnscreenedReason.PROVIDER_NOT_AUTHORITATIVE;
    }

    /**
     * {@code true} only when an authoritative provider actually screened this party. <b>Derived, never
     * stored</b> — there is deliberately no column, field or setter that can make this true on its own.
     * Every caller asking "has this party been screened" must read this and never {@code status !=
     * HIT}.
     */
    public boolean completedScreening() {
        return providerAuthoritative && status != ScreeningResult.Status.NOT_SCREENED_NO_PROVIDER;
    }

    /** {@code true} when the payment path refused because of this party. */
    public boolean refused() {
        return posture != null && posture.refuses();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
