package com.gme.pay.registry.audit;

import com.gme.pay.audit.AuditActors;
import com.gme.pay.audit.HashChain;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sweeps every hash chain in {@code audit_log} and reports whether the audit trail is
 * intact — the "make it verifiable" half of gap T5-1.
 *
 * <h2>Why a sweep was missing</h2>
 *
 * <p>{@link AuditLogService#verifyChain} could already verify <i>one</i> aggregate, and
 * {@code GET /v1/audit} exposed the resulting flag per row. Nothing could answer the question
 * an auditor actually asks — <i>"is the log intact?"</i> — because that requires walking every
 * aggregate, and nothing named the offending row when it was not. A boolean with no row id is
 * not actionable: an investigator cannot select on "false".
 *
 * <h2>What the report tells you, and what it deliberately does not claim</h2>
 *
 * <ul>
 *   <li><b>Intact / not intact per chain</b>, plus the {@code id} of the first row that broke
 *       and which of the three failure modes it is
 *       ({@link HashChain.BreakKind}) — inserted/deleted/reordered row, in-place edit of a
 *       sealed column, or a row this build cannot canonicalise at all.</li>
 *   <li><b>How many rows are still sealed under {@link HashChain#CHAIN_V1}</b>, whose digest
 *       omits {@code aggregate_type}, {@code aggregate_id} and {@code actor_ip}. Those rows
 *       verify, but they verify <i>less</i>, and a report that hid the distinction would be
 *       overstating the guarantee. The count only decreases over time.</li>
 *   <li><b>How many rows are not attributable to a verified principal</b>
 *       ({@code unverified:*} / {@code unattributed} / the historical bare {@code system}).
 *       A perfectly intact chain of unattributable rows is a sealed record of nothing, which
 *       was precisely the pre-T5-1 state; surfacing the number keeps that from being invisible
 *       again.</li>
 * </ul>
 *
 * <p>A "not intact" verdict is evidence of tampering <i>or</i> of a bug in a writer. It is not
 * self-evidently the former, and this service does not editorialise: it reports the row and
 * the failure mode, and the investigation is human.
 *
 * <p><b>Cost.</b> The sweep loads every row of every chain, so it is an operator-triggered /
 * scheduled-job operation, not something on a request path. There is no incremental mode: a
 * chain can only be verified from its genesis row, because that is what makes a deleted
 * leading row detectable.
 */
@Service
public class AuditIntegrityService {

    private final AuditLogRepository repository;

    public AuditIntegrityService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Verify every {@code (aggregate_type, aggregate_id)} chain in the table.
     *
     * <p>Read-only transaction so the sweep sees one consistent snapshot: without it, a chain
     * verified early could be extended by a concurrent writer while a later chain is still
     * being read, and the report's counts would describe two different databases.
     */
    @Transactional(readOnly = true)
    public AuditIntegrityReport verifyAll() {
        List<ChainResult> results = new ArrayList<>();
        int totalRows = 0;
        int legacyV1Rows = 0;
        int unattributableRows = 0;

        for (Object[] key : repository.findDistinctAggregates()) {
            String aggregateType = (String) key[0];
            String aggregateId = (String) key[1];
            List<AuditLogEntity> rows = repository.findChainByAggregate(aggregateType, aggregateId);

            HashChain.ChainVerification verification =
                    HashChain.inspect(rows.stream().map(AuditLogEntity::toDomain).toList());

            totalRows += rows.size();
            legacyV1Rows += verification.legacyV1Rows();
            for (AuditLogEntity row : rows) {
                if (!AuditActors.isAttributable(row.getActorId())) {
                    unattributableRows++;
                }
            }

            Long brokenId = verification.intact()
                    ? null
                    : rows.get(verification.firstBrokenIndex()).getId();
            results.add(new ChainResult(
                    aggregateType,
                    aggregateId,
                    verification.intact(),
                    brokenId,
                    verification.breakKind() == null ? null : verification.breakKind().name(),
                    verification.detail(),
                    rows.size(),
                    verification.legacyV1Rows()));
        }

        List<ChainResult> broken = results.stream().filter(r -> !r.intact()).toList();
        return new AuditIntegrityReport(
                broken.isEmpty(),
                results.size(),
                totalRows,
                legacyV1Rows,
                unattributableRows,
                broken,
                results);
    }

    /**
     * Verify one chain. Thin wrapper over {@link HashChain#inspect} that maps the failing index
     * back to the row {@code id}, which is the only identifier useful outside this JVM.
     */
    @Transactional(readOnly = true)
    public ChainResult verifyOne(String aggregateType, String aggregateId) {
        List<AuditLogEntity> rows = repository.findChainByAggregate(aggregateType, aggregateId);
        HashChain.ChainVerification v =
                HashChain.inspect(rows.stream().map(AuditLogEntity::toDomain).toList());
        return new ChainResult(
                aggregateType,
                aggregateId,
                v.intact(),
                v.intact() ? null : rows.get(v.firstBrokenIndex()).getId(),
                v.breakKind() == null ? null : v.breakKind().name(),
                v.detail(),
                rows.size(),
                v.legacyV1Rows());
    }

    /**
     * Verdict for a single chain.
     *
     * @param firstBrokenRowId the {@code audit_log.id} of the first row that failed
     *                         verification, or {@code null} when intact. This is the value to
     *                         put in an incident ticket.
     */
    public record ChainResult(
            String aggregateType,
            String aggregateId,
            boolean intact,
            Long firstBrokenRowId,
            String breakKind,
            String detail,
            int rows,
            int legacyV1Rows) {
    }

    /**
     * Whole-table verdict.
     *
     * @param intact             true only when EVERY chain verified.
     * @param chainsChecked      number of distinct (aggregateType, aggregateId) chains.
     * @param rowsChecked        total rows walked.
     * @param legacyV1Rows       rows still sealed under the weaker v1 digest.
     * @param unattributableRows rows whose actor is not a verified principal.
     * @param brokenChains       only the failures, so a caller does not have to filter.
     * @param chains             every chain's verdict.
     */
    public record AuditIntegrityReport(
            boolean intact,
            int chainsChecked,
            int rowsChecked,
            int legacyV1Rows,
            int unattributableRows,
            List<ChainResult> brokenChains,
            List<ChainResult> chains) {
    }
}
