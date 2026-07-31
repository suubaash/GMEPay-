package com.gme.pay.bff.web.dto;

import com.gme.pay.bff.client.SettlementClient;

import java.util.List;

/**
 * BFF wire shape for {@code GET /v1/admin/settlement/{batchId}}. Thin pass-through of the upstream
 * {@link SettlementClient.SettlementBatchDetail} so the Admin UI drawer can bind directly. Defined as
 * its own type so the UI import path stays stable if the upstream client moves.
 *
 * <p>GAP T4-5: this used to be unreachable in practice — {@code RestSettlementClient.detail} returned
 * {@code null} for every id because settlement-reconciliation exposed no per-batch read, so the
 * controller 404'd every request. It now carries a real persisted batch, its lines, and the
 * matched/open counts an operator triages by.
 */
public record SettlementBatchDetail(
        SettlementClient.SettlementBatchSummary batch,
        List<SettlementClient.SettlementLine> lines,
        int matchedCount,
        int openCount
) {}
