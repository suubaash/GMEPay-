package com.gme.pay.settlement.rerun;

import java.time.LocalDate;

/**
 * Result of an operator batch-generation re-run (gap <b>T3-4</b>). Mirrors {@link ReconRerunResponse}'s
 * shape: echo the operator, say exactly what was done, and carry the identifiers needed to verify it.
 *
 * @param operatorId       echoed from the request
 * @param fileType         the window's file type
 * @param settlementWindow the window
 * @param businessDate     the business date regenerated
 * @param outcome          {@code SUCCESS} | {@code FAILED} | {@code SKIPPED_NON_BUSINESS_DAY}
 * @param calendarVerdict  what the configured business-day calendar said about {@code businessDate}
 * @param batchId          the resulting settlement batch id, when the run produced/found one
 * @param batchStatus      that batch's status
 * @param recordCount      records in the generated file
 * @param runId            the {@code batch_runs.id} written for this re-run — the audit handle
 * @param detail           human-readable note (the failure message, or the idempotent-no-op explanation)
 */
public record BatchRerunResponse(
        String operatorId,
        String fileType,
        String settlementWindow,
        LocalDate businessDate,
        String outcome,
        String calendarVerdict,
        String batchId,
        String batchStatus,
        Integer recordCount,
        Long runId,
        String detail) {
}
