package com.gme.pay.bff.web.dto;

import com.gme.pay.bff.client.SettlementClient;

import java.util.List;

/**
 * BFF wire shape for {@code GET /v1/admin/settlement/{batchId}}. Thin pass-
 * through of the upstream {@link SettlementClient.SettlementBatchDetail} so the
 * Admin UI drawer can bind directly. Defined as its own type so the UI
 * import path stays stable if the upstream client moves.
 */
public record SettlementBatchDetail(
        SettlementClient.SettlementBatchSummary batch,
        List<SettlementClient.SettlementLine> lines
) {}
