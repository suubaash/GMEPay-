package com.gme.pay.payment.domain;

import java.util.Locale;
import java.util.Map;

/**
 * Resolves a numeric scheme id from the scheme CODE the orchestrator carries (e.g. {@code "zeropay"}).
 *
 * <p>The orchestrator + transaction layer carry the scheme CODE, not config-registry's numeric id, so
 * the {@code payment.approved} event, the per-transaction revenue capture, and the transaction
 * create/commit historically rode {@code schemeId = 0}. config-registry's scheme catalog
 * ({@code GET /v1/schemes}, {@code SchemeCatalogService}) is the platform's single source of truth for
 * the supported-scheme roster, but it is a code-keyed roster with NO numeric surrogate — there is no
 * code→id endpoint to call.
 *
 * <p>This resolver therefore maps the canonical, insertion-ordered roster to stable 1-based numeric
 * ids (ZEROPAY=1, BAKONG=2, …) so the event + transaction rows carry a real, deterministic scheme id
 * instead of 0. The mapping is matched against the same roster config-registry advertises; matching is
 * case-insensitive and tolerant of the {@code zeropay}/{@code ZEROPAY} and {@code zeropay_kr}
 * adapter-code variants. An unknown / null code resolves to {@code 0} (unset), preserving the prior
 * behaviour for schemes outside the roster.
 */
public final class SchemeId {

    /** Sentinel for an unresolvable / absent scheme code. */
    public static final long UNSET = 0L;

    /**
     * Canonical roster → 1-based numeric id, mirroring config-registry's {@code SchemeCatalogService}
     * order (ZEROPAY first). Keys are normalised (upper-case, non-alphanumerics stripped).
     */
    private static final Map<String, Long> CODE_TO_ID = Map.ofEntries(
            Map.entry("ZEROPAY", 1L),
            Map.entry("BAKONG", 2L),
            Map.entry("KHQR", 3L),
            Map.entry("NAPAS247", 4L),
            Map.entry("PROMPTPAY", 5L),
            Map.entry("FASTSG", 6L),
            Map.entry("QRIS", 7L),
            // NEPAL (scheme-adapter-nepal) is a later addition to the roster; appended
            // with a fresh id (8) rather than inserted at its catalog position so the
            // existing 1..7 ids stay stable for already-persisted transaction rows.
            Map.entry("NEPAL", 8L));

    private SchemeId() {
    }

    /**
     * Resolve the numeric scheme id for a scheme code; {@link #UNSET} (0) when the code is null/blank
     * or not in the platform roster. Tolerates corridor suffixes (e.g. {@code zeropay_kr} → ZEROPAY).
     */
    public static long resolve(String schemeCode) {
        if (schemeCode == null || schemeCode.isBlank()) {
            return UNSET;
        }
        String key = schemeCode.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        Long id = CODE_TO_ID.get(key);
        if (id != null) {
            return id;
        }
        // Tolerate adapter-code corridor suffixes ("zeropaykr" → ZEROPAY) by prefix match.
        for (Map.Entry<String, Long> e : CODE_TO_ID.entrySet()) {
            if (key.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return UNSET;
    }
}
