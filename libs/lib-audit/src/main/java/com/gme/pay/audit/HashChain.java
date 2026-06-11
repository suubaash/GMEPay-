package com.gme.pay.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Objects;

/**
 * SHA-256 hash chain helper for the audit log per ADR-007.
 *
 * <p>Every {@code audit_log} row carries two 32-byte fields:
 * <ul>
 *   <li>{@code prev_hash} — the {@code row_hash} of the immediately prior row for the
 *       <i>same {@code aggregate_id}</i>, or the 32-byte zero genesis vector when this
 *       is the first row for that aggregate.</li>
 *   <li>{@code row_hash}  — {@code SHA-256(prev_hash || canonicalised(event))}.</li>
 * </ul>
 *
 * <p>This produces a per-aggregate Merkle chain: silently mutating a middle row's
 * {@code after_jsonb} (or any other canonicalised field) leaves that row's stored
 * {@code row_hash} stale, so any subsequent row's {@code prev_hash} no longer matches
 * its predecessor's recomputed hash. Verification walks the rows in {@code id} order
 * for the aggregate and rejects the chain at the first mismatch.
 *
 * <p>Canonicalisation is intentionally <b>not</b> JSON serialisation — we pin a stable
 * byte order independent of Jackson configuration so two JVMs hashing the same logical
 * event get the same digest:
 *
 * <pre>
 *   eventType + 0x1F + actorId + 0x1F + recordedAt-as-epoch-millis + 0x1F
 *     + beforeJsonb-bytes + 0x1F + afterJsonb-bytes
 * </pre>
 *
 * <p>{@code 0x1F} is the ASCII <i>unit separator</i> control character; we use it
 * (rather than a printable delimiter like {@code "|"}) so that the separator never
 * collides with a legitimate byte inside a JSON value.
 *
 * <p>The helper is intentionally free of Spring or any framework wiring so it can be
 * called from migration scripts (Flyway Java callbacks), batch verifiers, and unit
 * tests with no context boot.
 */
public final class HashChain {

    /** Length in bytes of every hash field in the chain. */
    public static final int HASH_LEN = 32;

    /**
     * Genesis vector — the {@code prev_hash} used for the very first row of an
     * aggregate's chain. All zeros, distinguishable from any real SHA-256 output
     * (which is collision-resistant against all-zero pre-images for practical
     * purposes).
     */
    public static final byte[] GENESIS = new byte[HASH_LEN];

    /** ASCII unit separator (0x1F). Used as a field delimiter inside the digest input. */
    private static final byte FIELD_SEP = 0x1F;

    private HashChain() {
        // utility
    }

    /**
     * Compute {@code row_hash = SHA-256(prevHash || canonicalised(event))}.
     *
     * @param prevHash the prior row's {@code row_hash}, or {@link #GENESIS} for the
     *                 first row of an aggregate. Must be exactly {@link #HASH_LEN}
     *                 bytes; a {@code null} is treated as {@link #GENESIS} for
     *                 caller convenience.
     * @param event    the event being chained. Must carry a non-null {@code eventType},
     *                 {@code actorId} and {@code recordedAt}. Null jsonb payloads are
     *                 treated as the empty byte sequence.
     * @return the 32-byte SHA-256 digest.
     */
    public static byte[] rowHash(byte[] prevHash, AuditEvent event) {
        Objects.requireNonNull(event, "event");
        byte[] prev = (prevHash == null) ? GENESIS : prevHash;
        if (prev.length != HASH_LEN) {
            throw new IllegalArgumentException(
                    "prevHash must be " + HASH_LEN + " bytes, got " + prev.length);
        }
        MessageDigest md = sha256();
        md.update(prev);
        md.update(canonicalise(event));
        return md.digest();
    }

    /**
     * Build the canonicalised byte sequence used inside {@link #rowHash}. Exposed for
     * the verifier so re-canonicalisation matches the original write exactly.
     */
    public static byte[] canonicalise(AuditEvent event) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(event.eventType(), "eventType");
        Objects.requireNonNull(event.actorId(), "actorId");
        Objects.requireNonNull(event.recordedAt(), "recordedAt");

        byte[] eventType = event.eventType().getBytes(StandardCharsets.UTF_8);
        byte[] actorId = event.actorId().getBytes(StandardCharsets.UTF_8);
        byte[] recordedAt =
                Long.toString(event.recordedAt().toEpochMilli()).getBytes(StandardCharsets.UTF_8);
        byte[] before = nullToEmpty(event.beforeJsonb());
        byte[] after = nullToEmpty(event.afterJsonb());

        int total = eventType.length + 1 + actorId.length + 1 + recordedAt.length + 1
                + before.length + 1 + after.length;
        byte[] out = new byte[total];
        int p = 0;
        p = copy(eventType, out, p);
        out[p++] = FIELD_SEP;
        p = copy(actorId, out, p);
        out[p++] = FIELD_SEP;
        p = copy(recordedAt, out, p);
        out[p++] = FIELD_SEP;
        p = copy(before, out, p);
        out[p++] = FIELD_SEP;
        copy(after, out, p);
        return out;
    }

    /**
     * Verify a chain of rows previously emitted for the same {@code aggregate_id} in
     * ascending {@code id} order. The first row's {@code prev_hash} must equal
     * {@link #GENESIS}; every subsequent row's {@code prev_hash} must equal the
     * predecessor's recomputed {@code row_hash}; every row's stored {@code row_hash}
     * must equal {@link #rowHash} re-applied to its content. Returns the index of the
     * first bad row, or {@code -1} when the chain is intact.
     */
    public static int verify(java.util.List<? extends AuditEvent> rows) {
        Objects.requireNonNull(rows, "rows");
        byte[] expectedPrev = GENESIS;
        for (int i = 0; i < rows.size(); i++) {
            AuditEvent row = rows.get(i);
            byte[] storedPrev = row.prevHash() == null ? GENESIS : row.prevHash();
            if (!constantTimeEq(storedPrev, expectedPrev)) {
                return i;
            }
            byte[] recomputed = rowHash(storedPrev, row);
            if (!constantTimeEq(recomputed, row.rowHash())) {
                return i;
            }
            expectedPrev = row.rowHash();
        }
        return -1;
    }

    private static byte[] nullToEmpty(byte[] b) {
        return b == null ? new byte[0] : b;
    }

    private static int copy(byte[] src, byte[] dst, int pos) {
        System.arraycopy(src, 0, dst, pos, src.length);
        return pos + src.length;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by every JRE that meets the Java SE 7+ spec.
            throw new IllegalStateException("SHA-256 unavailable in this JVM", e);
        }
    }

    /**
     * Constant-time byte equality. We use it for hash compares not because audit
     * verification is on a side-channel-sensitive path (it isn't — verification runs
     * out-of-band) but because timing-leaky compares are a code-review smell next to
     * any cryptographic digest and reviewers should not have to second-guess each
     * site.
     */
    private static boolean constantTimeEq(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= (a[i] ^ b[i]);
        }
        return diff == 0;
    }

    /**
     * Marker view of an audit row used by {@link #verify}. {@link AuditEvent} (the
     * production record) satisfies this contract directly; tests and the verifier
     * can pass any class that exposes the same accessors.
     */
    public interface AuditEvent {
        String eventType();

        String actorId();

        Instant recordedAt();

        byte[] beforeJsonb();

        byte[] afterJsonb();

        byte[] prevHash();

        byte[] rowHash();
    }
}
