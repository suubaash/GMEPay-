package com.gme.pay.payment.domain;

import java.util.Locale;
import java.util.Set;

/**
 * Guard for the merchant DISPLAY NAME that gets persisted onto a transaction (gap T4-4).
 *
 * <p>The name shown on a receipt is a factual claim about who was paid, so the only two acceptable
 * values are <em>the real name the corridor resolved</em> or <em>null</em>. This class exists because
 * payment-executor holds several values that LOOK like a merchant name but are not one:
 * <ul>
 *   <li>{@link #PLACEHOLDER "Unknown Merchant"} — synthesised when merchant-qr-data is unreachable
 *       (SENDMN lenient mode, GMEREMIT's dev-synth escape hatch). It means "we did not look the
 *       merchant up", and persisting it would be indistinguishable from a merchant genuinely called
 *       "Unknown Merchant".</li>
 *   <li>The merchant / terminal <em>id</em> — carried right next to the name on most commands and
 *       therefore the tempting substitute. An id rendered under a "Merchant name" label is a lie
 *       dressed as data; the id already has its own field.</li>
 *   <li>Blank / whitespace-only strings from an upstream that answered with an empty field.</li>
 * </ul>
 *
 * <p>So: {@link #realOrNull} returns the first candidate that is a genuine name, else null. Callers
 * pass candidates in order of authority (the scheme's own answer before the hub's cached record) and
 * pass the resulting value — including null — straight through to the create contract. Nothing here
 * invents a value; the whole point is that "unknown" stays unknown all the way to the UI's em dash.
 */
public final class MerchantNames {

    /**
     * The literal synthesised by the lenient / dev-synth merchant fallbacks. Kept here so the
     * fallbacks and this filter cannot drift apart: if that string is ever changed in one place the
     * other stops matching and a placeholder starts being stored as a real name.
     */
    public static final String PLACEHOLDER = "Unknown Merchant";

    /** Case-insensitive set of values that are known NOT to be real merchant names. */
    private static final Set<String> NOT_A_NAME = Set.of(
            PLACEHOLDER.toLowerCase(Locale.ROOT),
            "unknown",
            "null");

    private MerchantNames() {}

    /**
     * Returns the first candidate that is a real merchant name, or {@code null} when none is.
     *
     * <p>Order matters: pass the most authoritative source first (e.g. the scheme adapter's verify-qr
     * answer, which is the name the scheme itself will show on its side, ahead of the hub's
     * merchant-qr-data record). Null / blank / placeholder candidates are skipped rather than
     * returned, and an all-unusable candidate list yields null — never a synthesised label.
     *
     * @param candidates possible names, most authoritative first; may be empty or contain nulls
     * @return a real merchant name, or null meaning "not known"
     */
    public static String realOrNull(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (isReal(candidate)) {
                return candidate.trim();
            }
        }
        return null;
    }

    /** True when {@code name} is a usable merchant display name (not null/blank/placeholder). */
    public static boolean isReal(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return !NOT_A_NAME.contains(name.trim().toLowerCase(Locale.ROOT));
    }
}
