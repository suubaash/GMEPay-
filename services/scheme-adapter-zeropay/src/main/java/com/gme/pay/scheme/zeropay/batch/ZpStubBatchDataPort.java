package com.gme.pay.scheme.zeropay.batch;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Stub implementation of {@link ZpBatchDataPort}.
 *
 * <p>Returns empty lists for every query, which produce valid zero-record batch files
 * with correct header/trailer control sums (= 0). This is the default active
 * implementation until transaction-management/settlement-mgmt are wired.</p>
 *
 * <p>Replace or override this bean with a real query-backed implementation in the
 * production Spring profile.</p>
 */
@Component
public class ZpStubBatchDataPort implements ZpBatchDataPort {

    @Override
    public List<Zp0011Record> fetchPaymentRecords(LocalDate businessDate) {
        return List.of();
    }

    @Override
    public List<Zp0021Record> fetchRefundRecords(LocalDate businessDate) {
        return List.of();
    }

    @Override
    public List<ZpSettlementRequestRecord> fetchSettlementRecords(LocalDate businessDate) {
        return List.of();
    }

    @Override
    public List<Zp0065Record> fetchPaymentDetailRecords(LocalDate businessDate) {
        return List.of();
    }

    @Override
    public List<Zp0066Record> fetchRefundDetailRecords(LocalDate businessDate) {
        return List.of();
    }
}
