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
 * <h2>Chain versions — what the digest covers</h2>
 *
 * <p>{@link #CHAIN_V1} (rows written before gap T5-1 was closed on 2026-07-28) sealed
 * five fields:
 *
 * <pre>
 *   eventType | actorId | recordedAt-epoch-millis | beforeJsonb | afterJsonb
 * </pre>
 *
 * <p>That left {@code aggregate_type}, {@code aggregate_id} and {@code actor_ip}
 * <b>outside the digest</b> — a hole the CISO audit called out, because those three
 * columns could be rewritten in place and the chain would still verify. Re-pointing a
 * row from one partner to another, or erasing the IP an operator acted from, was
 * undetectable.
 *
 * <p>{@link #CHAIN_V2} (every row written from now on) seals all of it, and seals the
 * version number itself so a v2 row cannot be downgraded to a v1 row by flipping the
 * {@code chain_version} column:
 *
 * <pre>
 *   "2" | aggregateType | aggregateId | actorIp | eventType | actorId
 *       | recordedAt-epoch-millis | beforeJsonb | afterJsonb
 * </pre>
 *
 * <p><b>Existing rows are not re-sealed.</b> Re-hashing history with a new algorithm
 * would destroy the very property the chain exists to provide: after a re-seal, nobody
 * could tell an honest migration from an attacker who rewrote the log and recomputed the
 * hashes. Instead each row records the version it was sealed under, verification
 * canonicalises per-row under that version, and {@link ChainVerification} reports how
 * many rows are still on the weaker v1 digest so the residual exposure is a number in a
 * report rather than an unstated assumption. That count only goes down (v1 rows are
 * append-only history; nothing writes new ones).
 *
 * <p>Canonicalisation is intentionally <b>not</b> JSON serialisation — we pin a stable
 * byte order independent of Jackson configuration so two JVMs hashing the same logical
 * event get the same digest. {@code 0x1F} is the ASCII <i>unit separator</i> control
 * character; we use it (rather than a printable delimiter like {@code "|"}) so the
 * separator never collides with a legitimate byte inside a JSON value. A {@code null}
 * field is encoded as an empty segment, which is why {@code actorIp} null and
 * {@code actorIp} {@code ""} hash identically — the column is nullable and the empty
 * string is not a meaningful IP.
 *
 * <p>The helper is intentionally free of Spring or any framework wiring so it can be
 * called from migration scripts (Flyway Java callbacks), batch verifiers, and unit
 * tests with no context boot.
 */
public final class HashChain {

    /** Length in bytes of every hash field in the chain. */
    public static final int HASH_LEN = 32;

    /**
     * Legacy digest: {@code eventType | actorId | recordedAt | before | after}. Rows sealed
     * before 2026-07-28. Never written again — see the class javadoc for why they are not
     * re-sealed.
     */
    public static final int CHAIN_V1 = 1;

    /**
     * Current digest: additionally seals the chain version, {@code aggregateType},
     * {@code aggregateId} and {@code actorIp}.
     */
    public static final int CHAIN_V2 = 2;

    /** The version every new row is sealed under. */
    public static final int CURRENT_CHAIN_VERSION = CHAIN_V2;

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
     * Compute {@code row_hash = SHA-256(prevHash || canonicalised(event))} under the
     * event's own {@link AuditEvent#chainVersion()}.
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
     * Build the canonicalised byte sequence used inside {@link #rowHash}, under the
     * event's own {@link AuditEvent#chainVersion()}. Exposed for the verifier so
     * re-canonicalisation matches the original write exactly.
     *
     * @throws IllegalArgumentException on an unknown chain version — an unrecognised
     *         version is a corrupt or forward-dated row, and guessing a digest for it
     *         would report "intact" for something we cannot actually check.
     */
    public static byte[] canonicalise(AuditEvent event) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(event.eventType(), "eventType");
        Objects.requireNonNull(event.actorId(), "actorId");
        Objects.requireNonNull(event.recordedAt(), "recordedAt");

        int version = event.chainVersion();
        return switch (version) {
            case CHAIN_V1 -> canonicaliseV1(event);
            case CHAIN_V2 -> canonicaliseV2(event);
            default -> throw new IllegalArgumentException(
                    "unknown audit chain_version " + version + " — cannot verify this row");
        };
    }

    private static byte[] canonicaliseV1(AuditEvent event) {
        return join(
                utf8(event.eventType()),
                utf8(event.actorId()),
                utf8(Long.toString(event.recordedAt().toEpochMilli())),
                nullToEmpty(event.beforeJsonb()),
                nullToEmpty(event.afterJsonb()));
    }

    private static byte[] canonicaliseV2(AuditEvent event) {
        return join(
                utf8(Integer.toString(CHAIN_V2)),
                utf8(event.aggregateType()),
                utf8(event.aggregateId()),
                utf8(event.actorIp()),
                utf8(event.eventType()),
                utf8(event.actorId()),
                utf8(Long.toString(event.recordedAt().toEpochMilli())),
                nullToEmpty(event.beforeJsonb()),
                nullToEmpty(event.afterJsonb()));
    }

    /**
     * Verify a chain of rows previously emitted for the same {@code aggregate_id} in
     * ascending {@code id} order. The first row's {@code prev_hash} must equal
     * {@link #GENESIS}; every subsequent row's {@code prev_hash} must equal the
     * predecessor's recomputed {@code row_hash}; every row's stored {@code row_hash}
     * must equal {@link #rowHash} re-applied to its content. Returns the index of the
     * first bad row, or {@code -1} when the chain is intact.
     *
     * <p>Kept as-is for the callers that only need a yes/no. New callers should prefer
     * {@link #inspect}, which also says <i>why</i> the chain broke.
     */
    public static int verify(java.util.List<? extends AuditEvent> rows) {
        return inspect(rows).firstBrokenIndex();
    }

    /**
     * Verify a chain and report the outcome in enough detail to act on: which row broke,
     * why it broke, and how many rows are still sealed under the weaker
     * {@link #CHAIN_V1} digest.
     */
    public static ChainVerification inspect(java.util.List<? extends AuditEvent> rows) {
        Objects.requireNonNull(rows, "rows");
        byte[] expectedPrev = GENESIS;
        int legacyRows = 0;
        for (int i = 0; i < rows.size(); i++) {
            AuditEvent row = rows.get(i);
            if (row.chainVersion() == CHAIN_V1) {
                legacyRows++;
            }
            byte[] storedPrev = row.prevHash() == null ? GENESIS : row.prevHash();
            if (!constantTimeEq(storedPrev, expectedPrev)) {
                return ChainVerification.broken(
                        i,
                        rows.size(),
                        legacyRows,
                        BreakKind.PREV_HASH_MISMATCH,
                        i == 0
                                ? "first row's prev_hash is not the genesis vector — one or more "
                                        + "leading rows were DELETED from this aggregate's chain"
                                : "prev_hash does not match the preceding row's row_hash — a row was "
                                        + "inserted, deleted or reordered at this position");
            }
            byte[] recomputed;
            try {
                recomputed = rowHash(storedPrev, row);
            } catch (RuntimeException e) {
                return ChainVerification.broken(
                        i, rows.size(), legacyRows, BreakKind.UNVERIFIABLE_ROW,
                        "row cannot be canonicalised: " + e.getMessage());
            }
            if (!constantTimeEq(recomputed, row.rowHash())) {
                return ChainVerification.broken(
                        i, rows.size(), legacyRows, BreakKind.ROW_HASH_MISMATCH,
                        "stored row_hash does not match the row's content — a sealed column of this "
                                + "row was MODIFIED after it was written");
            }
            expectedPrev = row.rowHash();
        }
        return ChainVerification.intact(rows.size(), legacyRows);
    }

    /** Why a chain failed verification. */
    public enum BreakKind {
        /** A row was inserted, removed or reordered — the linkage broke. */
        PREV_HASH_MISMATCH,
        /** A sealed column of the row itself was edited in place. */
        ROW_HASH_MISMATCH,
        /** The row carries a chain version this build cannot canonicalise. */
        UNVERIFIABLE_ROW
    }

    /**
     * Outcome of {@link #inspect}. {@code firstBrokenIndex} is {@code -1} on an intact
     * chain; otherwise it is the 0-based position of the first row that failed, which the
     * caller maps back to a row {@code id} for the report.
     *
     * @param intact           true when every row verified.
     * @param firstBrokenIndex 0-based index of the first bad row, or {@code -1}.
     * @param rowsChecked      how many rows were walked.
     * @param legacyV1Rows     how many of the walked rows are still sealed under
     *                         {@link #CHAIN_V1} (whose digest omits aggregateType /
     *                         aggregateId / actorIp).
     * @param breakKind        {@code null} when intact.
     * @param detail           human-readable explanation; {@code null} when intact.
     */
    public record ChainVerification(
            boolean intact,
            int firstBrokenIndex,
            int rowsChecked,
            int legacyV1Rows,
            BreakKind breakKind,
            String detail) {

        static ChainVerification intact(int rowsChecked, int legacyV1Rows) {
            return new ChainVerification(true, -1, rowsChecked, legacyV1Rows, null, null);
        }

        static ChainVerification broken(int index, int rowsChecked, int legacyV1Rows,
                                        BreakKind kind, String detail) {
            return new ChainVerification(false, index, rowsChecked, legacyV1Rows, kind, detail);
        }
    }

    private static byte[] utf8(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] nullToEmpty(byte[] b) {
        return b == null ? new byte[0] : b;
    }

    /** Concatenate segments separated by {@link #FIELD_SEP}. */
    private static byte[] join(byte[]... segments) {
        int total = Math.max(0, segments.length - 1);
        for (byte[] s : segments) {
            total += s.length;
        }
        byte[] out = new byte[total];
        int p = 0;
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out[p++] = FIELD_SEP;
            }
            System.arraycopy(segments[i], 0, out, p, segments[i].length);
            p += segments[i].length;
        }
        return out;
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
     * Marker view of an audit row used by {@link #verify} / {@link #inspect}.
     * {@link com.gme.pay.audit.AuditEvent} (the production record) satisfies this
     * contract directly; the verifier and tests can pass any class that exposes the same
     * accessors.
     *
     * <p>{@code aggregateType} / {@code aggregateId} / {@code actorIp} / {@code chainVersion}
     * joined this interface when {@link #CHAIN_V2} landed: a read-side row view that cannot
     * supply them cannot verify a v2 row, so they are abstract rather than defaulted — a
     * silent {@code null} default would make a v2 chain fail verification for a reason that
     * looks like tampering.
     */
    public interface AuditEvent {
        String aggregateType();

        String aggregateId();

        String actorId();

        String actorIp();

        String eventType();

        Instant recordedAt();

        byte[] beforeJsonb();

        byte[] afterJsonb();

        byte[] prevHash();

        byte[] rowHash();

        /** {@link #CHAIN_V1} or {@link #CHAIN_V2} — which digest sealed this row. */
        int chainVersion();
    }
}
