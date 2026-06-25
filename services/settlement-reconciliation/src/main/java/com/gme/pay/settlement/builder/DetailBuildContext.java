package com.gme.pay.settlement.builder;

import com.gme.pay.settlement.model.TransactionRecord;

import java.util.List;

/**
 * Per-TRANSACTION build input for the ZeroPay detail files — ZP0065 (payment detail) and ZP0066 (refund
 * detail) — as opposed to {@link BuildContext}, which is per-MERCHANT (one summary row per counterparty
 * in the ZP0061/ZP0063 request files). The detail files emit one DATA row per transaction, so the builder
 * needs the raw {@link TransactionRecord}s rather than pre-aggregated merchant rows.
 *
 * <p>Each {@link DetailRow} also carries the {@code settlementBatchRef} — the id of the ZP0061/ZP0063
 * REQUEST batch the transaction was settled in — which the detail file emits so ZeroPay can tie each
 * detail row back to the summary it already received.
 *
 * @param yyyymmdd settlement business date as YYYYMMDD (KST)
 * @param sequence intra-day file sequence (1-based)
 * @param rows     the transactions to emit, one DATA row each (payments for 0065, refunds for 0066)
 */
public record DetailBuildContext(String yyyymmdd, int sequence, List<DetailRow> rows) {

    /**
     * One detail line: the transaction plus the request batch it was settled in.
     *
     * @param txn               the transaction projection (merchant, refs, amount, approval instant, fee)
     * @param settlementBatchRef id of the ZP0061/ZP0063 request batch containing this txn (the tie-out key)
     */
    public record DetailRow(TransactionRecord txn, String settlementBatchRef) {}
}
