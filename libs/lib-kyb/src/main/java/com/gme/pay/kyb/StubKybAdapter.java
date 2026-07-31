package com.gme.pay.kyb;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic {@link KybProvider} for dev / test (ADR-009; active until the
 * Octa Solution sandbox credentials land per ADR-014).
 *
 * <h2>Decision rules (case-insensitive, applied to the entity legal names and
 * every UBO name)</h2>
 *
 * <ul>
 *   <li>any screened name containing {@code "SANCTIONED"} →
 *       {@link ScreeningResult.Status#HIT} (one hit per matching name,
 *       list {@code STUB_WATCHLIST}, score 0.99);</li>
 *   <li>otherwise any name containing {@code "REVIEW"} →
 *       {@link ScreeningResult.Status#NEEDS_REVIEW} (one hit per matching
 *       name, list {@code STUB_FUZZY}, score 0.65);</li>
 *   <li>otherwise → {@link ScreeningResult.Status#NOT_SCREENED_NO_PROVIDER},
 *       no hits.</li>
 * </ul>
 *
 * <h2>This adapter can never report CLEAR (gap T1-4)</h2>
 *
 * <p>The rules above are unchanged from Slice 3 <em>except</em> in what the
 * no-trigger-word branch is called. It used to be {@code CLEAR}, which is a
 * claim — "we screened this entity against the sanctions, PEP and adverse-media
 * sources and it matched nothing". This class consults no source whatsoever, so
 * it stamps {@link ScreeningProvenance#stub()} on every result and
 * {@link ScreeningResult}'s constructor consequently records the clean branch as
 * {@link ScreeningResult.Status#NOT_SCREENED_NO_PROVIDER}. The keyword rules
 * were deliberately NOT made smarter: a smarter fake is a worse fake. What
 * changed is that the fake can no longer be mistaken for the real thing in a
 * database column, an API response or an activation checklist.
 *
 * <p>The HIT / NEEDS_REVIEW branches are untouched: both fail closed, and
 * staging one by naming a partner {@code "SANCTIONED HOLDINGS"} is how demos and
 * tests exercise the blocked paths.
 *
 * <p>HIT outranks NEEDS_REVIEW when both trigger words appear. The rules are
 * pure functions of the subject, so demo flows and tests can stage any outcome
 * just by naming the partner (e.g. legal name {@code "SANCTIONED HOLDINGS"}).
 *
 * <h2>Provider reference</h2>
 *
 * <p>{@code providerRef} is {@code "stub-<hash>"} where {@code <hash>} is the
 * first 12 hex chars of the SHA-256 of a canonical projection of the subject —
 * stable across JVMs and runs, so re-screening an unchanged subject yields the
 * same reference (useful for idempotency assertions in tests).
 *
 * <p>{@code screenedAt} is truncated to MICROS to match the platform's
 * TIMESTAMP persistence discipline (see {@code PartnerStore.save}).
 */
public class StubKybAdapter implements KybProvider {

    /** Trigger word forcing a HIT (checked first, outranks REVIEW). */
    static final String HIT_TOKEN = "SANCTIONED";

    /** Trigger word forcing NEEDS_REVIEW when no HIT token is present. */
    static final String REVIEW_TOKEN = "REVIEW";

    @Override
    public ScreeningResult screen(KybSubject subject) {
        if (subject == null) {
            throw new IllegalArgumentException("subject is required");
        }
        List<String> names = screenedNames(subject);

        List<ScreeningResult.Hit> hits = new ArrayList<>();
        for (String name : names) {
            if (containsIgnoreCase(name, HIT_TOKEN)) {
                hits.add(new ScreeningResult.Hit("STUB_WATCHLIST", name, 0.99));
            }
        }
        ScreeningResult.Status status;
        if (!hits.isEmpty()) {
            status = ScreeningResult.Status.HIT;
        } else {
            for (String name : names) {
                if (containsIgnoreCase(name, REVIEW_TOKEN)) {
                    hits.add(new ScreeningResult.Hit("STUB_FUZZY", name, 0.65));
                }
            }
            status = hits.isEmpty() ? ScreeningResult.Status.CLEAR : ScreeningResult.Status.NEEDS_REVIEW;
        }

        return new ScreeningResult(
                status,
                List.copyOf(hits),
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                providerRef(subject),
                // Non-authoritative by construction: ScreeningProvenance refuses to
                // let the stub claim authority, and ScreeningResult therefore records
                // the no-trigger-word branch as NOT_SCREENED_NO_PROVIDER, never CLEAR.
                ScreeningProvenance.stub());
    }

    @Override
    public KybRunResult runFullKyb(KybSubject subject) {
        ScreeningResult screening = screen(subject);
        // T1-4: the registry flags are FALSE on every stub run, because this class
        // checks no register. They used to be true on the clean branch (derived
        // from a CLEAR the stub can no longer produce), which meant a full "KYB
        // run" reported a verified license, a verified UBO register and a verified
        // corporate registry without contacting any of the three. Reporting
        // "verified" for a check that never happened is the defect; false is the
        // honest answer and fails closed everywhere it is read.
        boolean review = screening.status() == ScreeningResult.Status.NEEDS_REVIEW;
        boolean verified = screening.authoritative() && !review
                && screening.status() != ScreeningResult.Status.HIT;
        return new KybRunResult(
                screening,
                verified,
                verified,
                verified,
                providerRef(subject) + "-full",
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /** Every name the stub screens: both entity name forms + every UBO name. */
    private static List<String> screenedNames(KybSubject subject) {
        List<String> names = new ArrayList<>();
        if (subject.legalNameLocal() != null && !subject.legalNameLocal().isBlank()) {
            names.add(subject.legalNameLocal());
        }
        if (subject.legalNameRomanized() != null && !subject.legalNameRomanized().isBlank()) {
            names.add(subject.legalNameRomanized());
        }
        for (KybSubject.Ubo ubo : subject.ubos()) {
            if (ubo != null && ubo.name() != null && !ubo.name().isBlank()) {
                names.add(ubo.name());
            }
        }
        return names;
    }

    /**
     * {@code "stub-"} + first 12 hex chars of SHA-256 over a canonical
     * projection of the subject. Stable across JVMs (no reliance on
     * {@code hashCode}); two subjects differing in any screened field get
     * different refs.
     */
    static String providerRef(KybSubject subject) {
        StringBuilder canonical = new StringBuilder(128);
        canonical.append(nullSafe(subject.partnerCode())).append('|')
                .append(nullSafe(subject.legalNameLocal())).append('|')
                .append(nullSafe(subject.legalNameRomanized())).append('|')
                .append(nullSafe(subject.countryOfIncorporation())).append('|')
                .append(nullSafe(subject.taxId()));
        for (KybSubject.Ubo ubo : subject.ubos()) {
            canonical.append('|').append(ubo == null ? "" : nullSafe(ubo.name()));
        }
        return "stub-" + sha256Hex12(canonical.toString());
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack.toUpperCase(java.util.Locale.ROOT).contains(needle);
    }

    private static String sha256Hex12(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA spec on every JVM; unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
