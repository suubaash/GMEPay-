package com.gme.pay.kybadapter.audit;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * A tiny JSON object writer with a <b>fixed, caller-controlled key order</b>, used to build the
 * {@code before_jsonb} / {@code after_jsonb} bytes of an {@code audit_log} row.
 *
 * <h2>Why not Jackson</h2>
 *
 * <p>Those bytes go into the SHA-256 digest that seals the row
 * ({@link com.gme.pay.audit.HashChain#canonicalise}). The digest is over the <i>raw bytes</i>, so any
 * variation in how the JSON was produced — key order, whether a null field was emitted or omitted, the
 * {@code ObjectMapper}'s module set at the time — changes the hash for a logically identical event.
 * Verification would then depend on Jackson configuration rather than on the data, and a config change
 * would surface months later as a chain that "broke" with no tampering having occurred — worse,
 * indistinguishable from real tampering.
 *
 * <p>So the encoding is deliberately dumb and total: order is the call order (there is no map, hence no
 * iteration order to depend on), nulls are emitted rather than skipped ({@code "caveat":null} — "there
 * was no caveat" and "this writer forgot the caveat field" must not encode identically), no
 * pretty-printing, and ASCII-safe escaping of the characters JSON requires plus all C0 controls.
 *
 * <p>This is a per-service copy of the same class in prefunding
 * ({@code com.gme.pay.prefunding.audit.CanonicalJson}, whose javadoc carries the argument at full
 * length). It is copied rather than shared because lib-audit deliberately stays free of an opinion about
 * payload shape, and because a shared encoder is a single point whose change would resilence every
 * service's chain at once.
 *
 * <p>Instances are single-use and not thread-safe: build one, call {@link #bytes()}, discard.
 */
public final class CanonicalJson {

    private final StringBuilder out = new StringBuilder(256);
    private boolean empty = true;

    private CanonicalJson() {
        out.append('{');
    }

    /** Start a new object. */
    public static CanonicalJson object() {
        return new CanonicalJson();
    }

    /** A string field; {@code null} is emitted as JSON {@code null}. */
    public CanonicalJson str(String key, String value) {
        key(key);
        if (value == null) {
            out.append("null");
        } else {
            quote(value);
        }
        return this;
    }

    /** An enum field by {@link Enum#name()}; {@code null} is emitted as JSON {@code null}. */
    public CanonicalJson enumeration(String key, Enum<?> value) {
        return str(key, value == null ? null : value.name());
    }

    /** An integral field as a JSON number; {@code null} → JSON {@code null}. */
    public CanonicalJson num(String key, Number value) {
        key(key);
        out.append(value == null ? "null" : value.toString());
        return this;
    }

    /** A boolean field. */
    public CanonicalJson bool(String key, boolean value) {
        key(key);
        out.append(value ? "true" : "false");
        return this;
    }

    /** An instant as its ISO-8601 string; {@code null} → JSON {@code null}. */
    public CanonicalJson at(String key, Instant value) {
        key(key);
        if (value == null) {
            out.append("null");
        } else {
            quote(value.toString());
        }
        return this;
    }

    /** Close the object and render the UTF-8 bytes that go into the column and the digest. */
    public byte[] bytes() {
        return (out.toString() + "}").getBytes(StandardCharsets.UTF_8);
    }

    /** The rendered JSON, for log lines and test diagnostics. */
    @Override
    public String toString() {
        return out + "}";
    }

    private void key(String key) {
        if (!empty) {
            out.append(',');
        }
        empty = false;
        quote(key);
        out.append(':');
    }

    /** Minimal, total JSON string escaping: the two mandatory escapes, the named shorthands, \\uXXXX for the rest of C0. */
    private void quote(String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
