package com.gme.pay.auth.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builder for the small JSON documents that go into {@code audit_log.before_jsonb} /
 * {@code after_jsonb}, plus the two redaction primitives every credential-bearing call site
 * in this service must go through.
 *
 * <h2>Why not Jackson</h2>
 *
 * <p>Three reasons, in order of weight:
 *
 * <ol>
 *   <li><b>The bytes are inside the hash.</b> {@code HashChain} canonicalises over the raw
 *       {@code before}/{@code after} bytes, so serialisation must be a function of the data and
 *       nothing else. A shared {@code ObjectMapper} carries service-wide configuration
 *       (property naming, null inclusion, date format, registered modules) that other code may
 *       change; the same logical event would then hash differently before and after that
 *       change. A hand-rolled writer with insertion-ordered keys removes the coupling.</li>
 *   <li><b>Reflective serialisation of a domain object is how secrets leak.</b> Handing an
 *       entity or a request DTO to an {@code ObjectMapper} writes whatever fields it happens to
 *       have — including the ones added next quarter. Every key here is typed out by hand, so a
 *       new secret-bearing field cannot appear in an audit row by default.</li>
 *   <li>No Jackson on the call path means these builders are usable from a plain unit test with
 *       no context.</li>
 * </ol>
 *
 * <h2>The redaction rule</h2>
 *
 * <p><b>No secret, API-key secret, HMAC signature, password or raw token is ever put in an
 * audit row.</b> {@code audit_log} is append-only, long-lived, broadly readable (the
 * verification and reporting surfaces read it), and exported to the cold sink — it is the worst
 * available place to durably store a live credential, and a credential written there outlives
 * every rotation that was supposed to retire it.
 *
 * <p>Where the identity of the material matters forensically, use {@link #fingerprint} — a
 * truncated SHA-256 that lets an incident tie a presented token to the row that issued it
 * without the row containing anything that can be replayed. Where only the shape matters, use
 * {@link #leading} on a value that is <i>already</i> public (an api key id, a key prefix).
 */
public final class AuditPayload {

    /**
     * Fingerprint width in hex characters (16 hex = 64 bits of a SHA-256). Long enough that
     * two distinct credentials will not collide in any realistic log, short enough that the
     * value is obviously not the material itself.
     */
    static final int FINGERPRINT_HEX_LEN = 16;

    /** Marker prefix so a reader can never mistake a fingerprint for the thing it describes. */
    static final String FINGERPRINT_PREFIX = "sha256:";

    private final Map<String, String> fields = new LinkedHashMap<>();

    private AuditPayload() {}

    /** Start a new payload. */
    public static AuditPayload of() {
        return new AuditPayload();
    }

    /**
     * Add a string field. A {@code null} value is DROPPED rather than written as JSON
     * {@code null}: an absent key and an explicitly-null key read the same to an investigator,
     * and dropping keeps the rows (and therefore the digests) small.
     */
    public AuditPayload put(String key, String value) {
        if (value != null) {
            fields.put(key, quote(value));
        }
        return this;
    }

    /** Add a numeric field (written unquoted). */
    public AuditPayload put(String key, Number value) {
        if (value != null) {
            fields.put(key, value.toString());
        }
        return this;
    }

    /** Add a boolean field (written unquoted). */
    public AuditPayload put(String key, Boolean value) {
        if (value != null) {
            fields.put(key, value.toString());
        }
        return this;
    }

    /** Add an {@link java.time.Instant} as an ISO-8601 string. */
    public AuditPayload put(String key, java.time.Instant value) {
        return put(key, value == null ? null : value.toString());
    }

    /**
     * Add a collection of strings as a JSON array. Used for permission-code sets, where the
     * before/after difference is the whole point of the row.
     */
    public AuditPayload putAll(String key, java.util.Collection<String> values) {
        if (values == null) {
            return this;
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String v : values) {
            if (!first) {
                sb.append(',');
            }
            sb.append(quote(v == null ? "" : v));
            first = false;
        }
        return raw(key, sb.append(']').toString());
    }

    private AuditPayload raw(String key, String jsonValue) {
        fields.put(key, jsonValue);
        return this;
    }

    /** Render the payload. Never returns {@code null}; an empty payload renders as {@code {}}. */
    public String json() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(quote(e.getKey())).append(':').append(e.getValue());
            first = false;
        }
        return sb.append('}').toString();
    }

    @Override
    public String toString() {
        return json();
    }

    // --------------------------------------------------------------------- redaction

    /**
     * A non-replayable identifier for credential material: {@code sha256:} + the first
     * {@value #FINGERPRINT_HEX_LEN} hex characters of its SHA-256.
     *
     * <p>This is what makes an authentication-failure row useful without making it dangerous.
     * "A token with fingerprint {@code sha256:9f2c…} was rejected 400 times, and here is the
     * {@code TOKEN_ISSUED} row that minted that same fingerprint" is a complete incident
     * narrative; the row still contains nothing that can be presented to anything.
     *
     * <p>Safe against the obvious objection (an offline dictionary attack on the digest)
     * <i>for the values this service fingerprints</i>: they are all high-entropy machine
     * credentials — a 40-character random secret, an HS256 JWT, a 64-hex HMAC output. It must
     * NOT be used on a low-entropy value such as a human-chosen password, where a truncated
     * digest is a crackable record of the password.
     *
     * @return {@code null} when {@code material} is null/blank, so an absent credential does
     *         not become a fingerprint of the empty string (which would be a constant, and
     *         would read as if something had been presented).
     */
    public static String fingerprint(String material) {
        if (material == null || material.isBlank()) {
            return null;
        }
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Mandated by every Java SE 7+ JRE.
            throw new IllegalStateException("SHA-256 unavailable in this JVM", e);
        }
        byte[] digest = md.digest(material.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(FINGERPRINT_HEX_LEN);
        for (int i = 0; hex.length() < FINGERPRINT_HEX_LEN && i < digest.length; i++) {
            hex.append(String.format("%02x", digest[i]));
        }
        return FINGERPRINT_PREFIX + hex.substring(0, FINGERPRINT_HEX_LEN);
    }

    /**
     * The leading {@code n} characters of an <b>already-public</b> value — an api key
     * identifier, a key prefix. Never call this on secret material: a prefix of a secret is
     * still a piece of the secret.
     *
     * @return {@code null} for a null/blank input.
     */
    public static String leading(String value, int n) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        return v.length() <= n ? v : v.substring(0, n);
    }

    // ------------------------------------------------------------------------ internals

    /**
     * JSON string escaping. Control characters are escaped as {@code \\uXXXX} rather than
     * dropped: the audit payload frequently carries attacker-supplied text (a claimed actor
     * name, an unresolved api key), and a writer that silently drops bytes would let a caller
     * choose what the audit row says.
     */
    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
