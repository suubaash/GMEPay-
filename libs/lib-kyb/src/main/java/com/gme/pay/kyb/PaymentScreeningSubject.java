package com.gme.pay.kyb;

import java.util.Locale;

/**
 * One party on one payment, as a screening subject — gap <b>T5-3</b>.
 *
 * <p>This record exists to answer a question that a boolean cannot: <b>could this party have been
 * screened at all?</b> Wiring a sanctions vendor into the payment path is necessary but not
 * sufficient, because a name-matching provider needs a name, and on this platform the payer's name
 * does not exist in payment-executor's process. So the subject carries the attributes we actually hold
 * and reports, structurally, whether they amount to something a provider could act on.
 *
 * <h2>Why {@code reference} is not an identity</h2>
 * <p>{@code reference} is the opaque handle the caller uses for this party — a wallet user UUID
 * ({@code userRef}) or a partner's own customer reference ({@code customer_ref}). It correlates rows;
 * it screens nothing. No sanctions, PEP or adverse-media list is keyed by a counterparty's internal
 * customer id, so a provider handed only a reference can only ever answer "not found", and recording
 * that as a clean result would be the exact T1-4 defect one layer down. {@link #screenable()}
 * therefore requires a {@link #name()}, and a subject without one is counted as
 * {@link UnscreenedReason#NO_SUBJECT_IDENTITY} rather than passed to a provider that cannot help.
 *
 * <h2>Attributes are optional on purpose</h2>
 * <p>Every field except {@code party} may be {@code null}. This is not laxity — it is the current,
 * honest shape of the platform's payment contracts, and pretending otherwise (a required-name
 * constructor) would force callers to synthesise a placeholder name, which is how a fake screening
 * subject gets born. The gap is recorded, not papered over.
 *
 * @param party        the role this subject plays; required
 * @param reference    opaque correlation handle (wallet user ref / partner customer ref); never an
 *                     identity for screening purposes
 * @param name         the party's full legal name as presented — the ONLY attribute any list-matching
 *                     provider can use. {@code null} when the payment contract does not carry it.
 * @param countryCode  ISO-3166 alpha-2 country of the party, when known; a jurisdiction narrows a
 *                     match but cannot substitute for a name
 * @param dateOfBirth  ISO-8601 date (yyyy-MM-dd) of a natural person, when known; used by real
 *                     providers to discriminate common-name false positives. Never invented here.
 */
public record PaymentScreeningSubject(
        PaymentParty party,
        String reference,
        String name,
        String countryCode,
        String dateOfBirth) {

    public PaymentScreeningSubject {
        if (party == null) {
            throw new IllegalArgumentException("party is required on a screening subject");
        }
        reference = trimToNull(reference);
        name = trimToNull(name);
        countryCode = countryCode == null ? null
                : trimToNull(countryCode.toUpperCase(Locale.ROOT));
        dateOfBirth = trimToNull(dateOfBirth);
    }

    /**
     * A subject known only by an opaque reference — the shape both of this platform's payment entry
     * points actually produce today.
     */
    public static PaymentScreeningSubject byReferenceOnly(PaymentParty party, String reference) {
        return new PaymentScreeningSubject(party, reference, null, null, null);
    }

    /** A subject whose name IS known (e.g. a resolved merchant / beneficiary name). */
    public static PaymentScreeningSubject named(PaymentParty party, String reference, String name) {
        return new PaymentScreeningSubject(party, reference, name, null, null);
    }

    /**
     * {@code true} only when this subject carries at least one attribute a list-matching provider can
     * actually match on — today, a name. A reference-only subject is NOT screenable.
     *
     * <p>Callers must branch on this before invoking a {@link PaymentScreeningPort}: sending an
     * unscreenable subject to a provider produces a no-match that is indistinguishable from a clean
     * result, which is the failure mode gap T1-4 removed from the KYB path.
     */
    public boolean screenable() {
        return name != null;
    }

    /**
     * A log/alert-safe description of this subject: the role and WHICH attributes are present, never
     * the attribute values. Payer names and DOBs are exactly the PII that must not land in an ops
     * alert or a log aggregator (the platform has no column encryption — gap T5-5), so the coverage
     * record is deliberately about presence, not content.
     */
    public String attributeSummary() {
        StringBuilder sb = new StringBuilder(party.name()).append('[');
        sb.append("reference=").append(reference == null ? "absent" : "present");
        sb.append(", name=").append(name == null ? "ABSENT" : "present");
        sb.append(", country=").append(countryCode == null ? "absent" : countryCode);
        sb.append(", dob=").append(dateOfBirth == null ? "absent" : "present");
        return sb.append(']').toString();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
