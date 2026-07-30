package com.gme.pay.settlement.web;

import java.util.List;

/**
 * A persisted settlement batch and its lines (GAP T4-5) — the per-batch detail read that did not
 * exist before. The BFF's {@code detail(batchId)} used to return {@code null} unconditionally
 * (controller → 404) with a javadoc explaining that no upstream endpoint existed; it now maps this.
 *
 * @param batch        the batch, with its real status and honest transmission state
 * @param lines        the persisted {@code settlement_lines} rows, oldest first
 * @param matchedCount lines that reconciled cleanly
 * @param openCount    lines not yet matched — the operator's actual work queue for this batch
 */
public record SettlementBatchDetailResponse(
        SettlementBatchSummaryResponse batch,
        List<SettlementBatchLineResponse> lines,
        int matchedCount,
        int openCount) {

    public static SettlementBatchDetailResponse of(SettlementBatchSummaryResponse batch,
                                                   List<SettlementBatchLineResponse> lines) {
        int matched = (int) lines.stream().filter(SettlementBatchLineResponse::matched).count();
        return new SettlementBatchDetailResponse(batch, lines, matched, lines.size() - matched);
    }
}
