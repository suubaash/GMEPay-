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
 *   <li>otherwise → {@link ScreeningResult.Status#CLEAR}, no hits.</li>
 * </ul>
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
                providerRef(subject));
    }

    @Override
    public KybRunResult runFullKyb(KybSubject subject) {
        ScreeningResult screening = screen(subject);
        // Deterministic registry checks: everything "verifies" unless the
        // screening already flagged the subject — a HIT entity cannot have a
        // verified license in any sane vendor response, and NEEDS_REVIEW
        // surfaces an unverified UBO set so the review queue has something to
        // disposition.
        boolean clear = screening.status() == ScreeningResult.Status.CLEAR;
        boolean review = screening.status() == ScreeningResult.Status.NEEDS_REVIEW;
        return new KybRunResult(
                screening,
                clear || review,
                clear,
                clear || review,
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
