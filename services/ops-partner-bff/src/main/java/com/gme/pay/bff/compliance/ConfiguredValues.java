package com.gme.pay.bff.compliance;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One rule, in one place, for the question "is this regulatory value actually configured?"
 *
 * <h2>Why this exists (GAP T5-2)</h2>
 * <p>The compliance readiness board used to answer that question with {@code value != null}. The
 * shipped Hometax issuer certificate id is the literal string {@code stub-cert-id} and the BOK
 * transaction/category codes are still the placeholder {@code TODO_OI03} (OI-02 / OI-03 are
 * externally gated), so {@code != null} rendered a green "configured" tick on a lane that holds
 * <b>no credential at all</b>. A placeholder is not a credential, and claiming otherwise on a
 * regulatory board is the same class of untruth as a fabricated filing receipt.
 *
 * <p>Mirrors (deliberately, by value not by dependency — the BFF must not depend on a service
 * module) the placeholder rule {@code reporting-compliance}'s {@code FilingChannelRegistry}
 * applies to its own channel credentials, so both layers agree on what counts as real.
 */
public final class ConfiguredValues {

    /**
     * Whole values that are placeholders rather than credentials. Compared case-insensitively
     * after trimming.
     */
    private static final Set<String> PLACEHOLDER_VALUES = Set.of(
            "stub", "todo", "tbd", "changeme", "change-me", "change_me",
            "placeholder", "dummy", "sample", "example", "test",
            "n/a", "na", "none", "null", "-", "--", "xxx", "xxxx", "?");

    /**
     * Prefixes that mark a value as a placeholder, e.g. {@code stub-cert-id} (the shipped Hometax
     * cert id) and {@code TODO_OI03} (the shipped BOK code). Compared case-insensitively.
     */
    private static final List<String> PLACEHOLDER_PREFIXES = List.of(
            "stub-", "stub_", "todo", "tbd-", "tbd_", "changeme", "change-me",
            "placeholder", "dummy-", "dummy_", "sample-", "sample_",
            "example-", "example_", "xxx");

    private ConfiguredValues() {}

    /**
     * {@code true} only when {@code value} is a real, operator-entered value: non-null, non-blank
     * and not a known placeholder marker.
     */
    public static boolean isConfigured(String value) {
        return !isPlaceholder(value);
    }

    /**
     * {@code true} when {@code value} is absent, blank, or a recognised placeholder — i.e. when
     * treating it as "configured" would be a false claim.
     */
    public static boolean isPlaceholder(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (PLACEHOLDER_VALUES.contains(v)) {
            return true;
        }
        for (String prefix : PLACEHOLDER_PREFIXES) {
            if (v.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
