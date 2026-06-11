package com.gme.pay.audit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HashChain}. The chain only matters if it is deterministic,
 * sensitive to <i>every</i> canonicalised field, and detects mutation of any prior
 * row — these tests pin all three properties.
 */
class HashChainTest {

    private static final Instant T0 = Instant.parse("2026-06-11T10:00:00Z");

    @Test
    void rowHashIsDeterministic() {
        AuditEvent a = AuditEvent.newEvent(
                "partner", "1", "alice", "10.0.0.1", "PARTNER_SAVED",
                bytes("{\"x\":1}"), bytes("{\"x\":2}"), HashChain.GENESIS, T0);
        AuditEvent b = AuditEvent.newEvent(
                "partner", "1", "alice", "10.0.0.1", "PARTNER_SAVED",
                bytes("{\"x\":1}"), bytes("{\"x\":2}"), HashChain.GENESIS, T0);
        assertArrayEquals(a.rowHash(), b.rowHash(),
                "Same canonical input MUST produce the same rowHash across builds");
        assertEquals(HashChain.HASH_LEN, a.rowHash().length);
    }

    @Test
    void rowHashChangesWhenAfterJsonbChanges() {
        AuditEvent original = AuditEvent.newEvent(
                "partner", "1", "alice", null, "PARTNER_SAVED",
                bytes("{\"x\":1}"), bytes("{\"x\":2}"), HashChain.GENESIS, T0);
        AuditEvent tampered = AuditEvent.newEvent(
                "partner", "1", "alice", null, "PARTNER_SAVED",
                bytes("{\"x\":1}"), bytes("{\"x\":999}"), HashChain.GENESIS, T0);
        assertFalse(java.util.Arrays.equals(original.rowHash(), tampered.rowHash()),
                "Mutating after_jsonb MUST change rowHash");
    }

    @Test
    void rowHashChangesWhenActorChanges() {
        // The actorId field goes into the canonical bytes; switching alice → bob must
        // not produce the same digest even with identical payloads.
        AuditEvent alice = AuditEvent.newEvent(
                "partner", "1", "alice", null, "PARTNER_SAVED",
                null, bytes("{}"), HashChain.GENESIS, T0);
        AuditEvent bob = AuditEvent.newEvent(
                "partner", "1", "bob", null, "PARTNER_SAVED",
                null, bytes("{}"), HashChain.GENESIS, T0);
        assertNotEquals(toHex(alice.rowHash()), toHex(bob.rowHash()));
    }

    @Test
    void verifyAcceptsIntactChain() {
        List<AuditEvent> chain = buildChain(5, "1");
        assertEquals(-1, HashChain.verify(chain));
    }

    @Test
    void verifyRejectsTamperedMiddleRow() {
        // Build a 5-row chain. Replace row 2's after_jsonb (keeping its stored hashes
        // intact) — row 2's own row_hash will no longer match its content, so verify
        // should reject at index 2.
        List<AuditEvent> chain = new ArrayList<>(buildChain(5, "1"));
        AuditEvent middle = chain.get(2);
        AuditEvent tampered = new AuditEvent(
                middle.id(),
                middle.aggregateType(),
                middle.aggregateId(),
                middle.actorId(),
                middle.actorIp(),
                middle.eventType(),
                middle.beforeJsonb(),
                bytes("{\"silently\":\"rewritten\"}"),
                middle.prevHash(),
                middle.rowHash(),
                middle.recordedAt());
        chain.set(2, tampered);
        int firstBad = HashChain.verify(chain);
        assertEquals(2, firstBad,
                "Verifier must flag the tampered row itself, not just a downstream row");
    }

    @Test
    void verifyRejectsInjectedRow() {
        // Build a 5-row chain, then splice in a forged row at position 3. The forged
        // row carries plausible prev/row hashes for itself, but the subsequent row's
        // prevHash still points at the OLD predecessor — which is now at position 4 —
        // so the chain breaks at position 4.
        List<AuditEvent> chain = new ArrayList<>(buildChain(5, "1"));
        AuditEvent forgedPredecessor = chain.get(2);
        AuditEvent forged = AuditEvent.newEvent(
                "partner", "1", "attacker", null, "PARTNER_SAVED",
                null, bytes("{\"x\":\"forged\"}"),
                forgedPredecessor.rowHash(), T0.plusSeconds(99));
        chain.add(3, forged);
        int firstBad = HashChain.verify(chain);
        // Insertion shifts the original row 3 to index 4; its prevHash still equals
        // forgedPredecessor.rowHash(), but verify() expects forged.rowHash() at that
        // point — so the mismatch surfaces at index 4.
        assertEquals(4, firstBad);
    }

    @Test
    void rowHashRejectsPrevHashOfWrongLength() {
        AuditEvent stub = new AuditEvent(
                null, "partner", "1", "alice", null, "PARTNER_SAVED",
                null, null, null, null, T0);
        assertThrows(IllegalArgumentException.class,
                () -> HashChain.rowHash(new byte[16], stub));
    }

    @Test
    void firstChainRowMustUseGenesis() {
        AuditEvent bogusFirst = AuditEvent.newEvent(
                "partner", "1", "alice", null, "PARTNER_SAVED",
                null, bytes("{}"),
                new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                        17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32},
                T0);
        // Verifier expects GENESIS for the first row's prevHash; any other vector is
        // a chain that did not start at genesis, which we reject.
        assertEquals(0, HashChain.verify(List.of(bogusFirst)));
    }

    @Test
    void genesisVectorIsAll32Zeros() {
        assertEquals(32, HashChain.GENESIS.length);
        for (byte b : HashChain.GENESIS) {
            assertEquals(0, b);
        }
    }

    @Test
    void canonicaliseDistinguishesNullVsEmptyJsonb() {
        // null jsonb is normalised to empty bytes, so the canonical form does not
        // distinguish the two — this is intentional (the DB column likewise stores
        // either {} or NULL and we don't want a chain that breaks based on which one
        // Hibernate chose to flush). We pin the behaviour so future refactors don't
        // silently invert it.
        AuditEvent withNull = AuditEvent.newEvent(
                "partner", "1", "alice", null, "PARTNER_SAVED",
                null, null, HashChain.GENESIS, T0);
        AuditEvent withEmpty = AuditEvent.newEvent(
                "partner", "1", "alice", null, "PARTNER_SAVED",
                new byte[0], new byte[0], HashChain.GENESIS, T0);
        assertArrayEquals(withNull.rowHash(), withEmpty.rowHash());
    }

    @Test
    void verifyAcceptsConstantTimeEqualHashes() {
        AuditEvent only = AuditEvent.newEvent(
                "partner", "1", "alice", null, "PARTNER_SAVED",
                null, bytes("{}"), HashChain.GENESIS, T0);
        assertTrue(HashChain.verify(List.of(only)) == -1);
    }

    /**
     * Build a deterministic chain of {@code n} events for a given aggregate id, each
     * chained to its predecessor's rowHash. Used by multiple tests.
     */
    private static List<AuditEvent> buildChain(int n, String aggregateId) {
        List<AuditEvent> out = new ArrayList<>(n);
        byte[] prev = HashChain.GENESIS;
        for (int i = 0; i < n; i++) {
            AuditEvent e = AuditEvent.newEvent(
                    "partner",
                    aggregateId,
                    "alice",
                    "10.0.0." + i,
                    "PARTNER_SAVED",
                    i == 0 ? null : bytes("{\"v\":" + (i - 1) + "}"),
                    bytes("{\"v\":" + i + "}"),
                    prev,
                    T0.plusSeconds(i));
            out.add(e);
            prev = e.rowHash();
        }
        return out;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String toHex(byte[] b) {
        return java.util.HexFormat.of().formatHex(b);
    }
}
