package com.gme.pay.scheme.zeropay.batch;

import java.time.LocalDate;
import java.util.List;

/**
 * Port for retrieving the day's transaction/refund/settlement data needed to build
 * ZeroPay batch files.
 *
 * <p>The stub implementation ({@link ZpStubBatchDataPort}) returns empty lists, which produce
 * valid zero-record batch files. The real implementation will query
 * transaction-management/settlement-mgmt once those services are wired.</p>
 *
 * <p>All data is already in KST business-date scope — callers do not need to perform
 * any timezone conversion.</p>
 */
public interface ZpBatchDataPort {

    /**
     * Returns approved payment transactions for the given KST business date.
     *
     * @param businessDate KST business date (e.g. 2026-06-15)
     * @return ordered list of {@link Zp0011Record}; empty list if none
     */
    List<Zp0011Record> fetchPaymentRecords(LocalDate businessDate);

    /**
     * Returns completed refund transactions for the given KST business date.
     *
     * @param businessDate KST business date
     * @return ordered list of {@link Zp0021Record}; empty list if none
     */
    List<Zp0021Record> fetchRefundRecords(LocalDate businessDate);

    /**
     * Returns per-merchant settlement summaries for the given KST business date.
     * Used by both ZP0061 (morning) and ZP0063 (afternoon) settlement request files.
     *
     * @param businessDate KST business date
     * @return ordered list of {@link ZpSettlementRequestRecord}; empty list if none
     */
    List<ZpSettlementRequestRecord> fetchSettlementRecords(LocalDate businessDate);

    /**
     * Returns payment detail records for the given KST business date (ZP0065).
     *
     * @param businessDate KST business date
     * @return ordered list of {@link Zp0065Record}; empty list if none
     */
    List<Zp0065Record> fetchPaymentDetailRecords(LocalDate businessDate);

    /**
     * Returns refund detail records for the given KST business date (ZP0066).
     *
     * @param businessDate KST business date
     * @return ordered list of {@link Zp0066Record}; empty list if none
     */
    List<Zp0066Record> fetchRefundDetailRecords(LocalDate businessDate);
}
