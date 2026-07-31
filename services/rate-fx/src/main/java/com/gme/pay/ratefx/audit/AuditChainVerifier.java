package com.gme.pay.ratefx.audit;

import com.gme.pay.audit.DbAuditPublisher;
import com.gme.pay.audit.HashChain;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads rate-fx's {@code audit_log} back and re-derives the hash chain, so the tamper-evidence is
 * something this service can <i>demonstrate</i> rather than merely claim.
 *
 * <h2>Why the verifier is part of closing the gap, not an extra</h2>
 *
 * <p>A hash chain nobody verifies provides no assurance at all. The chain does not <i>prevent</i> an
 * in-place edit of {@code audit_log}; it makes one <b>detectable</b>, and only by something that
 * recomputes the digests. Without a read path, "rate-fx's audit trail is hash-chained" is an
 * unfalsifiable claim about columns nobody reads, and a DBA who rewrote the operator on a manual FX override
 * would never be contradicted.
 *
 * <p>So the report is actionable rather than a boolean:
 *
 * <ul>
 *   <li><b>which row</b> broke — by {@code audit_log.id}, not by list position. "Row 4 of the chain"
 *       is useless in an incident (row 4 of which snapshot?); the id is what an investigator selects
 *       on and what a forensic export is scoped to.</li>
 *   <li><b>why</b> it broke — {@link HashChain.BreakKind} separates three genuinely different
 *       findings. {@code ROW_HASH_MISMATCH} = a sealed column of that row was edited in place.
 *       {@code PREV_HASH_MISMATCH} = a row was inserted, deleted or reordered around it (and at
 *       index 0, that leading rows were removed — the classic "cover the start" move).
 *       {@code UNVERIFIABLE_ROW} = the row claims a chain version this build cannot canonicalise,
 *       which is corruption or a forward-dated write and must never be reported as intact.</li>
 *   <li><b>how much is still on the weaker digest</b> — {@code legacyV1Rows} counts rows sealed under
 *       {@link HashChain#CHAIN_V1}, whose digest omits {@code aggregate_type}, {@code aggregate_id}
 *       and {@code actor_ip}. This table is created empty by V003 so the count should be 0; it is
 *       reported rather than assumed, because a non-zero value means something wrote a v1 row and
 *       that is itself the finding.</li>
 * </ul>
 *
 * <h2>Read-only, by construction</h2>
 *
 * <p>There is deliberately no re-seal, repair or backfill operation here or anywhere else in this
 * service. Re-hashing history with today's key would destroy the only property the chain provides:
 * after a re-seal nobody could distinguish an honest migration from an attacker who rewrote the log
 * and recomputed the hashes. A broken chain is an incident to investigate, not a row to fix.
 *
 * <h2>What it cannot detect, stated plainly</h2>
 *
 * <ol>
 *   <li><b>Truncation of the tail.</b> Deleting the LAST rows of an aggregate's chain leaves a
 *       shorter chain that verifies perfectly — there is nothing after them to contradict the
 *       deletion. Detecting that needs an external anchor (a periodic notarised head hash, or the
 *       ADR-007 tier-2/tier-3 sinks) and is out of scope here.</li>
 *   <li><b>Deletion of an entire aggregate.</b> Remove every row for one currency and the key vanishes
 *       from {@link DbAuditPublisher#listAggregates()}, so a full sweep will not know to look for it.
 *       Same remedy as above.</li>
 * </ol>
 *
 * <p>Recording those limits is the point: a verifier that said "intact" and let a reader infer
 * "nothing was removed" would be worse than no verifier at all.
 */
@Service
public class AuditChainVerifier {

    private final DbAuditPublisher publisher;

    public AuditChainVerifier(DbAuditPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Verify one aggregate's chain.
     *
     * <p>An aggregate with no rows is reported {@code intact} with {@code rowsChecked = 0} — an empty
     * chain is trivially consistent. It is NOT an error, because from inside the table "no rows" and
     * "all rows deleted" are indistinguishable (see the class javadoc), and dressing that ambiguity
     * up as a failure would train readers to ignore the report.
     */
    @Transactional(readOnly = true)
    public ChainReport verify(String aggregateType, String aggregateId) {
        List<DbAuditPublisher.ChainRow> rows = publisher.loadChainRows(aggregateType, aggregateId);
        HashChain.ChainVerification result = HashChain.inspect(rows);
        Long brokenId = null;
        if (!result.intact()) {
            int idx = result.firstBrokenIndex();
            if (idx >= 0 && idx < rows.size()) {
                brokenId = rows.get(idx).id();
            }
        }
        return new ChainReport(
                aggregateType,
                aggregateId,
                result.intact(),
                result.rowsChecked(),
                result.legacyV1Rows(),
                brokenId,
                result.breakKind() == null ? null : result.breakKind().name(),
                result.detail());
    }

    /**
     * Sweep every {@code (aggregate_type, aggregate_id)} pair present in the table. Broken chains are
     * listed first so a long report cannot bury a finding below the fold.
     */
    @Transactional(readOnly = true)
    public SweepReport verifyAll() {
        List<ChainReport> reports = new ArrayList<>();
        for (String[] aggregate : publisher.listAggregates()) {
            reports.add(verify(aggregate[0], aggregate[1]));
        }
        reports.sort((a, b) -> Boolean.compare(a.intact(), b.intact()));
        int broken = (int) reports.stream().filter(r -> !r.intact()).count();
        int rows = reports.stream().mapToInt(ChainReport::rowsChecked).sum();
        int legacy = reports.stream().mapToInt(ChainReport::legacyV1Rows).sum();
        return new SweepReport(reports.size(), broken, rows, legacy, reports);
    }

    /**
     * Verification outcome for one chain.
     *
     * @param intact           true when every row re-derived to its stored hash and every link matched
     * @param rowsChecked      how many rows were walked
     * @param legacyV1Rows     how many are still sealed under the weaker {@link HashChain#CHAIN_V1}
     * @param firstBrokenRowId {@code audit_log.id} of the first bad row; {@code null} when intact
     * @param breakKind        {@link HashChain.BreakKind} name; {@code null} when intact
     * @param detail           human-readable explanation; {@code null} when intact
     */
    public record ChainReport(
            String aggregateType,
            String aggregateId,
            boolean intact,
            int rowsChecked,
            int legacyV1Rows,
            Long firstBrokenRowId,
            String breakKind,
            String detail) {
    }

    /**
     * Full-table sweep outcome.
     *
     * @param chainsChecked how many distinct chains exist
     * @param chainsBroken  how many failed. Non-zero is an incident, not a warning
     * @param rowsChecked   total rows walked
     * @param legacyV1Rows  total rows still on the {@link HashChain#CHAIN_V1} digest
     * @param chains        per-chain detail, broken chains first
     */
    public record SweepReport(
            int chainsChecked,
            int chainsBroken,
            int rowsChecked,
            int legacyV1Rows,
            List<ChainReport> chains) {

        /** Convenience for a health check / alert rule. */
        public boolean intact() {
            return chainsBroken == 0;
        }
    }
}
