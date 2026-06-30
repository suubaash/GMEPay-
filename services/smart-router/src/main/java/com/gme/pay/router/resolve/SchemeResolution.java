package com.gme.pay.router.resolve;

import java.util.List;

/**
 * Result of a successful scheme-for-location resolution.
 *
 * @param scheme      the chosen scheme id (the preferred row that matched all
 *                    three axes).
 * @param candidates  every scheme id that matched, in priority order
 *                    ({@code candidates.get(0) == scheme}); &gt; 1 element means
 *                    the corridor was ambiguous and {@code scheme} is the
 *                    priority-disambiguated winner.
 * @param ambiguous   true when more than one scheme matched.
 */
public record SchemeResolution(String scheme, List<String> candidates, boolean ambiguous) {

    public static SchemeResolution of(List<String> orderedCandidates) {
        return new SchemeResolution(
                orderedCandidates.get(0),
                List.copyOf(orderedCandidates),
                orderedCandidates.size() > 1);
    }
}
