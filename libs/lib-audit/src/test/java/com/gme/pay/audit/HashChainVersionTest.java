package com.gme.pay.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tamper-evidence half of gap T5-1: what the digest covers, and what happens to rows
 * sealed under the old one.
 *
 * <p>The CISO audit's finding was precise — {@code canonicalise()} hashed only
 * {@code eventType | actorId | recordedAt | before | after}, so {@code aggregateType},
 * {@code aggregateId} and {@code actorIp} "can be rewritten without breaking verification".
 * Each of the three now has a test that rewrites exactly that column and asserts the chain
 * breaks, because "we added the fields to the digest" is not the same claim as "editing them is
 * now detected".
 */
class HashChainVersionTest {

    private static final Instant T0 = Instant.parse("2026-07-28T00:00:00Z");

    @Test
    @DisplayName("new rows are sealed under v2")
    void newEventsAreV2() {
        AuditEvent e = event("partner", "P001", "alice", "10.0.0.1", HashChain.GENESIS);
        assertThat(e.chainVersion()).isEqualTo(HashChain.CHAIN_V2);
        assertThat(HashChain.CURRENT_CHAIN_VERSION).isEqualTo(HashChain.CHAIN_V2);
    }

    @Test
    @DisplayName("rewriting aggregate_id is now detected (it was not, under v1)")
    void rewritingAggregateIdBreaksTheChain() {
        AuditEvent original = event("partner", "P001", "alice", "10.0.0.1", HashChain.GENESIS);
        AuditEvent repointed = copyWith(original, "partner", "P002", original.actorIp());

        assertThat(HashChain.inspect(List.of(original)).intact()).isTrue();
        HashChain.ChainVerification v = HashChain.inspect(List.of(repointed));
        assertThat(v.intact())
                .as("re-pointing an audit row from one partner to another must be caught")
                .isFalse();
        assertThat(v.breakKind()).isEqualTo(HashChain.BreakKind.ROW_HASH_MISMATCH);
        assertThat(v.firstBrokenIndex()).isZero();
    }

    @Test
    @DisplayName("rewriting aggregate_type is now detected")
    void rewritingAggregateTypeBreaksTheChain() {
        AuditEvent original = event("partner", "P001", "alice", "10.0.0.1", HashChain.GENESIS);
        AuditEvent retyped = copyWith(original, "partner_kyb", "P001", original.actorIp());
        assertThat(HashChain.inspect(List.of(retyped)).intact()).isFalse();
    }

    @Test
    @DisplayName("erasing or changing actor_ip is now detected")
    void rewritingActorIpBreaksTheChain() {
        AuditEvent original = event("partner", "P001", "alice", "203.0.113.9", HashChain.GENESIS);
        // Erasing the IP an operator acted from is the interesting case: it is the edit that
        // makes an action harder to attribute without changing what the action was.
        AuditEvent erased = copyWith(original, "partner", "P001", null);
        AuditEvent moved = copyWith(original, "partner", "P001", "10.0.0.1");

        assertThat(HashChain.inspect(List.of(erased)).intact()).isFalse();
        assertThat(HashChain.inspect(List.of(moved)).intact()).isFalse();
    }

    @Test
    @DisplayName("a v1 row still verifies under the v1 digest — history is not invalidated")
    void legacyV1RowsStillVerify() {
        // Simulate a row written before this change: sealed with the five-field digest.
        AuditEvent unsealed = new AuditEvent(
                1L, "partner", "P001", "system", "10.0.0.1", "PARTNER_SAVED",
                null, json("{\"v\":1}"), HashChain.GENESIS, null, T0, HashChain.CHAIN_V1);
        byte[] v1Hash = HashChain.rowHash(HashChain.GENESIS, unsealed);
        AuditEvent legacy = new AuditEvent(
                1L, "partner", "P001", "system", "10.0.0.1", "PARTNER_SAVED",
                null, json("{\"v\":1}"), HashChain.GENESIS, v1Hash, T0, HashChain.CHAIN_V1);

        HashChain.ChainVerification v = HashChain.inspect(List.of(legacy));
        assertThat(v.intact())
                .as("re-sealing history would destroy the chain's meaning; old rows must still "
                        + "verify under the digest they were written with")
                .isTrue();
        assertThat(v.legacyV1Rows())
                .as("...but the report must SAY how many rows carry the weaker digest")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a v1 row cannot be laundered into a v2 row, or vice versa")
    void chainVersionIsSealedIntoTheV2Digest() {
        AuditEvent v2Row = event("partner", "P001", "alice", "10.0.0.1", HashChain.GENESIS);

        // Flip only the version column, keeping the stored hash. If the version were not inside
        // the v2 digest, this row would now be canonicalised under v1 — which ignores
        // aggregateType/aggregateId/actorIp — and an attacker could downgrade a row to make
        // those three columns editable again.
        AuditEvent downgraded = new AuditEvent(
                v2Row.id(), v2Row.aggregateType(), v2Row.aggregateId(), v2Row.actorId(),
                v2Row.actorIp(), v2Row.eventType(), v2Row.beforeJsonb(), v2Row.afterJsonb(),
                v2Row.prevHash(), v2Row.rowHash(), v2Row.recordedAt(), HashChain.CHAIN_V1);

        assertThat(HashChain.inspect(List.of(downgraded)).intact())
                .as("downgrading chain_version must break the row, not unlock it")
                .isFalse();
    }

    @Test
    @DisplayName("an unknown chain version reports UNVERIFIABLE rather than passing")
    void unknownChainVersionIsNotSilentlyAccepted() {
        AuditEvent futureRow = new AuditEvent(
                1L, "partner", "P001", "alice", null, "PARTNER_SAVED",
                null, json("{}"), HashChain.GENESIS, new byte[HashChain.HASH_LEN], T0, 99);
        HashChain.ChainVerification v = HashChain.inspect(List.of(futureRow));
        assertThat(v.intact()).isFalse();
        assertThat(v.breakKind()).isEqualTo(HashChain.BreakKind.UNVERIFIABLE_ROW);
    }

    @Test
    @DisplayName("the verifier names the FIRST broken row and says why")
    void verifierNamesTheFirstBrokenLink() {
        List<AuditEvent> chain = new ArrayList<>();
        byte[] prev = HashChain.GENESIS;
        for (int i = 0; i < 5; i++) {
            AuditEvent e = event("partner", "P001", "alice", "10.0.0.1", prev);
            chain.add(e);
            prev = e.rowHash();
        }
        assertThat(HashChain.inspect(chain).intact()).isTrue();

        // Edit row 3's payload in place, leaving its stored hashes alone.
        AuditEvent row3 = chain.get(3);
        chain.set(3, new AuditEvent(
                row3.id(), row3.aggregateType(), row3.aggregateId(), row3.actorId(),
                row3.actorIp(), row3.eventType(), row3.beforeJsonb(), json("{\"edited\":true}"),
                row3.prevHash(), row3.rowHash(), row3.recordedAt(), row3.chainVersion()));

        HashChain.ChainVerification v = HashChain.inspect(chain);
        assertThat(v.intact()).isFalse();
        assertThat(v.firstBrokenIndex())
                .as("the edited row itself must be named, not a downstream victim")
                .isEqualTo(3);
        assertThat(v.breakKind()).isEqualTo(HashChain.BreakKind.ROW_HASH_MISMATCH);
        assertThat(v.detail()).contains("MODIFIED");
        assertThat(v.rowsChecked()).isEqualTo(5);
    }

    @Test
    @DisplayName("a deleted leading row is reported as a linkage break, not as an intact short chain")
    void deletedLeadingRowIsDetected() {
        List<AuditEvent> chain = new ArrayList<>();
        byte[] prev = HashChain.GENESIS;
        for (int i = 0; i < 3; i++) {
            AuditEvent e = event("partner", "P001", "alice", null, prev);
            chain.add(e);
            prev = e.rowHash();
        }
        chain.remove(0); // the row that started the chain is gone

        HashChain.ChainVerification v = HashChain.inspect(chain);
        assertThat(v.intact()).isFalse();
        assertThat(v.breakKind()).isEqualTo(HashChain.BreakKind.PREV_HASH_MISMATCH);
        assertThat(v.detail()).contains("DELETED");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static AuditEvent event(String aggregateType, String aggregateId,
                                    String actor, String ip, byte[] prev) {
        return AuditEvent.newEvent(aggregateType, aggregateId, actor, ip, "PARTNER_SAVED",
                null, json("{\"ok\":true}"), prev, T0);
    }

    /** Rewrite the three columns v1 left unsealed, keeping the stored hashes as-is. */
    private static AuditEvent copyWith(AuditEvent e, String aggregateType, String aggregateId,
                                       String actorIp) {
        return new AuditEvent(e.id(), aggregateType, aggregateId, e.actorId(), actorIp,
                e.eventType(), e.beforeJsonb(), e.afterJsonb(), e.prevHash(), e.rowHash(),
                e.recordedAt(), e.chainVersion());
    }

    private static byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
