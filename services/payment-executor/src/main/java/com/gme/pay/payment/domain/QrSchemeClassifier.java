package com.gme.pay.payment.domain;

import java.util.Locale;

/**
 * Classifies a raw wallet-scanned QR payload into its {@link Classification} —
 * {@code (networkIdentifier, country, mode)} — per ADR-016 §1.
 *
 * <p>The QR's own <b>network identifier</b> is the deterministic routing key (not the country).
 * Two QR shapes are recognised:
 *
 * <h2>EMVCo (MPM merchant QR)</h2>
 * A TLV string of {@code TT LL VV} triples. Merchant Account Information lives in templates
 * <b>tags 26–51</b>; each template's <b>sub-tag {@code 00}</b> is a globally-unique network
 * identifier (reverse-domain / AID): {@code com.zeropay}, {@code fonepay.com}, a NepalPay GUID, …
 * The country is EMVCo <b>tag {@code 58}</b> (ISO-3166 alpha-2). Mode is {@link PaymentMode#MPM}
 * for a scanned merchant QR.
 *
 * <h2>JSON (Khalti / mobank etc.)</h2>
 * Wallet apps emit JSON QRs rather than EMVCo TLV. These are classified by structural shape /
 * marker keys ({@code khalti}, {@code mobank}) into a synthetic network identifier.
 *
 * <p>This class <b>retires {@code NepalQrDetector}</b>: the Nepal cases it string-matched are
 * subsumed here — a Fonepay QR classifies to {@code fonepay.com}, a NepalPay GUID to its GUID,
 * a Khalti JSON QR to {@code khalti}. ZeroPay ({@code com.zeropay} / country {@code KR}) keeps
 * classifying correctly and is never confused with a Nepal network.
 *
 * <p>Parsing is defensive: a malformed or unrecognised payload yields a best-effort
 * {@link Classification} with a {@code null}/{@code UNKNOWN} network so the caller can decline
 * cleanly rather than throw.
 */
public final class QrSchemeClassifier {

    /** Sentinel network id for a payload we could not classify. */
    public static final String UNKNOWN_NETWORK = "UNKNOWN";

    private QrSchemeClassifier() {
    }

    /**
     * Result of classifying a scanned QR.
     *
     * @param networkIdentifier the QR's network GUID / AID (e.g. {@code fonepay.com},
     *                          {@code com.zeropay}) or a synthetic id for JSON QRs
     *                          ({@code khalti}); {@link #UNKNOWN_NETWORK} if unrecognised.
     * @param country           ISO-3166 alpha-2 country from EMVCo tag 58 (nullable / upper-cased).
     * @param mode              payment mode — {@link PaymentMode#MPM} for a scanned merchant QR.
     */
    public record Classification(String networkIdentifier, String country, PaymentMode mode) {
        public boolean isKnown() {
            return networkIdentifier != null && !UNKNOWN_NETWORK.equals(networkIdentifier);
        }
    }

    /**
     * Classify a raw QR payload.
     *
     * @param qrPayload raw EMVCo TLV or JSON QR scanned by the wallet (may be null/blank)
     * @return a best-effort {@link Classification}; never null.
     */
    public static Classification classify(String qrPayload) {
        if (qrPayload == null || qrPayload.isBlank()) {
            return new Classification(UNKNOWN_NETWORK, null, PaymentMode.MPM);
        }
        String trimmed = qrPayload.trim();

        // JSON QRs (Khalti / mobank) — structural classification.
        if (trimmed.startsWith("{")) {
            return classifyJson(trimmed);
        }
        // EMVCo TLV.
        return classifyEmvco(trimmed);
    }

    // ---- EMVCo TLV ----

    private static Classification classifyEmvco(String payload) {
        String country = upper(readTag(payload, "58"));
        String network = readNetworkIdentifier(payload);
        if (network == null) {
            // Fall back to substring markers for payloads whose sub-tag 00 we could not
            // cleanly parse but which still carry a recognisable network domain/AID.
            network = fallbackMarker(payload);
        }
        return new Classification(network != null ? network : UNKNOWN_NETWORK, country, PaymentMode.MPM);
    }

    /**
     * Reads the network identifier from the first Merchant Account Information template
     * (tags 26–51), sub-tag {@code 00}. Returns the GUID/AID value (e.g. {@code com.zeropay},
     * {@code fonepay.com}) or {@code null} if none parses.
     */
    private static String readNetworkIdentifier(String payload) {
        int i = 0;
        int n = payload.length();
        while (i + 4 <= n) {
            String tag = safeSub(payload, i, i + 2);
            i += 2;
            int len = parseLen(safeSub(payload, i, i + 2));
            i += 2;
            if (len < 0 || i + len > n) {
                break; // malformed — stop scanning
            }
            String value = safeSub(payload, i, i + len);
            i += len;

            int tagNum = parseIntSafe(tag);
            if (tagNum >= 26 && tagNum <= 51) {
                // Merchant Account Information template — its sub-tag 00 is the network id.
                String sub00 = readTag(value, "00");
                if (sub00 != null && !sub00.isBlank()) {
                    return sub00.toLowerCase(Locale.ROOT);
                }
            }
        }
        return null;
    }

    /**
     * Reads a top-level EMVCo tag's value (returns the raw value string, or null if absent /
     * malformed). Used for tag 58 (country) and for sub-tags within a template.
     */
    private static String readTag(String tlv, String wantTag) {
        if (tlv == null) return null;
        int i = 0;
        int n = tlv.length();
        while (i + 4 <= n) {
            String tag = safeSub(tlv, i, i + 2);
            i += 2;
            int len = parseLen(safeSub(tlv, i, i + 2));
            i += 2;
            if (len < 0 || i + len > n) {
                return null;
            }
            String value = safeSub(tlv, i, i + len);
            i += len;
            if (wantTag.equals(tag)) {
                return value;
            }
        }
        return null;
    }

    /**
     * Substring-marker fallback: when the TLV sub-tag 00 could not be parsed, recognise the
     * common network domains/AIDs directly in the payload. Keeps well-known networks routable
     * even from a slightly non-conformant QR.
     */
    private static String fallbackMarker(String payload) {
        String q = payload.toLowerCase(Locale.ROOT);
        if (q.contains("com.zeropay")) return "com.zeropay";
        if (q.contains("fonepay.com")) return "fonepay.com";
        if (q.contains("nepalpay") || q.contains("npqr")) return "nepalpay";
        if (q.contains("khalti")) return "khalti";
        return null;
    }

    // ---- JSON QRs ----

    private static Classification classifyJson(String payload) {
        String q = payload.toLowerCase(Locale.ROOT);
        String network;
        String country = null;
        if (q.contains("khalti")) {
            network = "khalti";
            country = "NP";
        } else if (q.contains("mobank")) {
            network = "mobank";
            country = "NP";
        } else {
            network = UNKNOWN_NETWORK;
        }
        // Best-effort country extraction from a JSON "country"/"countryCode" field if present.
        String jsonCountry = readJsonCountry(payload);
        if (jsonCountry != null) {
            country = upper(jsonCountry);
        }
        return new Classification(network, country, PaymentMode.MPM);
    }

    private static String readJsonCountry(String payload) {
        for (String key : new String[]{"\"countryCode\"", "\"country\""}) {
            int idx = payload.indexOf(key);
            if (idx < 0) continue;
            int colon = payload.indexOf(':', idx);
            if (colon < 0) continue;
            int q1 = payload.indexOf('"', colon);
            if (q1 < 0) continue;
            int q2 = payload.indexOf('"', q1 + 1);
            if (q2 < 0) continue;
            return payload.substring(q1 + 1, q2);
        }
        return null;
    }

    // ---- small helpers ----

    private static String safeSub(String s, int from, int to) {
        if (from < 0 || to > s.length() || from > to) return "";
        return s.substring(from, to);
    }

    private static int parseLen(String twoDigits) {
        return parseIntSafe(twoDigits);
    }

    private static int parseIntSafe(String s) {
        if (s == null || s.length() != 2) return -1;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }
}
