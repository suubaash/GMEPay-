package com.gme.pay.settlement.rerun;

import java.time.LocalDate;

/**
 * Operator request to re-run one settlement-generation window for a named business date (gap <b>T3-4</b>).
 * Deliberately the same shape as {@link ReconRerunRequest} — {@code operatorId} + {@code reason} carried on
 * the request so who/why lands in the run ledger and the log, not just what.
 *
 * @param fileType         {@code ZP0061} | {@code ZP0063} | {@code ZP0065} | {@code ZP0066}
 * @param settlementWindow {@code MORNING} | {@code AFTERNOON} | {@code DETAIL}; derived from
 *                         {@code fileType} when omitted, since the pairing is fixed
 * @param businessDate     the KST business date to regenerate; required — a re-run with no date would be
 *                         "today", which is exactly the case an operator does NOT need
 * @param operatorId       who is asking (recorded on {@code batch_runs.operator_id})
 * @param reason           why (recorded on {@code batch_runs.reason})
 * @param force            re-run even though a SUCCESSFUL run already exists for these coordinates.
 *                         Default {@code false} = refuse. See {@link BatchRerunService} for why the
 *                         default has to be refusal.
 */
public record BatchRerunRequest(
        String fileType,
        String settlementWindow,
        LocalDate businessDate,
        String operatorId,
        String reason,
        boolean force) {
}
