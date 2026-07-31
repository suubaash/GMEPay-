package com.gme.pay.auth.audit;

import com.gme.pay.audit.DbAuditPublisher;
import com.gme.pay.audit.HashChain;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads {@code audit_log} back and re-derives the hash chain, so the tamper-evidence is
 * something this service can actually <i>demonstrate</i> rather than merely claim.
 *
 * <h2>Why a verifier is part of closing the gap, not an extra</h2>
 *
 * <p>A hash chain nobody verifies provides no assurance whatsoever. The chain does not prevent
 * an in-place edit of {@code audit_log}; it makes the edit <b>detectable</b> — and only by
 * something that recomputes the digests. Without a verification path, "the audit trail is
 * hash-chained" is an unfalsifiable statement about a column nobody reads, and a DBA who
 * rewrote a row would never be contradicted.
 *
 * <p>So the report is deliberately actionable rather than a boolean:
 *
 * <ul>
 *   <li><b>which row</b> broke — by primary key, not by list position. "Row 4 of the chain" is
 *       useless in an incident (row 4 of which snapshot?); {@code id = 91821} is what an
 *       investigator selects on and what a forensic export is scoped to.</li>
 *   <li><b>why</b> it broke — {@link HashChain.BreakKind} distinguishes the three cases, and
 *       they mean genuinely different things. {@code ROW_HASH_MISMATCH} = a sealed column of
 *       that row was edited in place. {@code PREV_HASH_MISMATCH} = a row was inserted, deleted
 *       or reordered around it (and at index 0, that leading rows were deleted — the classic
 *       "cover the start of the intrusion" move). {@code UNVERIFIABLE_ROW} = the row claims a
 *       chain version this build cannot canonicalise, which is corruption or a forward-dated
 *       write, and must never be reported as "intact".</li>
 *   <li><b>how much is still on the weaker digest</b> — {@code legacyV1Rows} counts rows sealed
 *       under {@link HashChain#CHAIN_V1}, whose digest omits {@code aggregate_type},
 *       {@code aggregate_id} and {@code actor_ip} and therefore cannot detect an edit to those
 *       three columns. This service's {@code audit_log} starts empty so the count should be 0,
 *       but it is reported rather than assumed: a non-zero value here means something wrote a
 *       v1 row, which is itself the finding.</li>
 * </ul>
 *
 * <h2>What it cannot detect, stated plainly</h2>
 *
 * <p>Two things, and neither is fixable by any per-aggregate chain:
 *
 * <ol>
 *   <li><b>Truncation of the tail.</b> Deleting the LAST rows of an aggregate's chain leaves a
 *       shorter chain that verifies perfectly — there is nothing after them to contradict the
 *       deletion. Detecting that needs an external anchor (a periodic notarised head hash, or
 *       the ADR-007 tier-2/tier-3 sinks), which is a platform-level piece of work and is
 *       explicitly out of this slice.</li>
 *   <li><b>Deletion of an entire aggregate.</b> If every row for one chain key is removed, the
 *       key vanishes from {@link DbAuditPublisher#listAggregates()} and a full sweep will not
 *       even know to look for it. Same remedy as above.</li>
 * </ol>
 *
 * <p>Recording those limits here is the point: a verifier that reported "intact" and let a
 * reader infer "nothing was removed" would be worse than no verifier.
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
     * <p>An aggregate with no rows is reported {@code intact} with {@code rowsChecked = 0} — an
     * empty chain is trivially consistent. It is NOT reported as an error, because "no rows"
     * and "rows were deleted" are indistinguishable from inside the table (see the class
     * javadoc), and dressing the ambiguity up as a failure would produce noise that trains
     * readers to ignore the report.
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
     * Sweep every {@code (aggregate_type, aggregate_id)} present in the table. Broken chains
     * come first so a long report cannot bury a finding below the fold.
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
     * @param intact           true when every row re-derived to its stored hash and every link
     *                         matched.
     * @param rowsChecked      how many rows were walked.
     * @param legacyV1Rows     how many are still sealed under the weaker
     *                         {@link HashChain#CHAIN_V1} digest.
     * @param firstBrokenRowId {@code audit_log.id} of the first bad row; {@code null} when
     *                         intact.
     * @param breakKind        {@link HashChain.BreakKind} name; {@code null} when intact.
     * @param detail           human-readable explanation; {@code null} when intact.
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
     * @param chainsChecked how many distinct {@code (aggregate_type, aggregate_id)} chains exist.
     * @param chainsBroken  how many failed. Non-zero is an incident, not a warning.
     * @param rowsChecked   total rows walked.
     * @param legacyV1Rows  total rows still on the {@link HashChain#CHAIN_V1} digest.
     * @param chains        per-chain detail, broken chains first.
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
