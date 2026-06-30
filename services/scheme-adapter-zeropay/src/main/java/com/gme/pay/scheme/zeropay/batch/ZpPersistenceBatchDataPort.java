package com.gme.pay.scheme.zeropay.batch;

import com.gme.pay.scheme.zeropay.persistence.ZpCommittedTxnEntity;
import com.gme.pay.scheme.zeropay.persistence.ZpCommittedTxnRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real {@link ZpBatchDataPort} that builds the day's ZeroPay batch records from the
 * locally-captured committed real-time transactions ({@code zp_committed_txns}, written by
 * {@code ZpCommittedTxnRecorder} on the MPM/CPM commit + refund path).
 *
 * <p>This replaces the zero-record {@link ZpStubBatchDataPort}: with this bean active the
 * scheduler produces ZP0011/ZP0021/ZP0061/ZP0063/ZP0065/ZP0066 files containing the actual
 * approved payments and refunds, with correct header/trailer control sums.</p>
 *
 * <p>It deliberately reads ONLY this service's own table — it does not reach into
 * transaction-management / settlement-mgmt (frozen; would need a published contract). The
 * captured rows are the authoritative scheme-side view of what GME submitted and what the
 * scheme committed, which is exactly what the ZeroPay daily files report.</p>
 *
 * <p>Activated by {@code adapter.zeropay.batch-data-source=persistence} (the default). Set it
 * to {@code stub} to fall back to the empty-file {@link ZpStubBatchDataPort} (e.g. for a clean
 * environment with no captured data).</p>
 */
@Component
@Primary
@ConditionalOnProperty(name = "adapter.zeropay.batch-data-source",
        havingValue = "persistence", matchIfMissing = true)
public class ZpPersistenceBatchDataPort implements ZpBatchDataPort {

    private final ZpCommittedTxnRepository repository;

    public ZpPersistenceBatchDataPort(ZpCommittedTxnRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<Zp0011Record> fetchPaymentRecords(LocalDate businessDate) {
        List<ZpCommittedTxnEntity> txns = repository
                .findByBusinessDateAndTxnKindOrderByTxnTimeAscIdAsc(
                        businessDate, ZpCommittedTxnEntity.KIND_PAYMENT);
        List<Zp0011Record> out = new ArrayList<>(txns.size());
        for (ZpCommittedTxnEntity t : txns) {
            out.add(new Zp0011Record(
                    t.getGmeTxnId(),
                    t.getZeropayTxnRef(),
                    t.getMerchantId(),
                    t.getQrCodeId(),
                    t.getBusinessDate(),
                    t.getTxnTime(),
                    t.getAmountKrw(),
                    t.getMerchantFeeKrw(),
                    t.getVanFeeKrw(),
                    partnerChar(t.getPartnerType()),
                    t.getApprovalCode(),
                    'A'));
        }
        return out;
    }

    @Override
    public List<Zp0021Record> fetchRefundRecords(LocalDate businessDate) {
        List<ZpCommittedTxnEntity> txns = repository
                .findByBusinessDateAndTxnKindOrderByTxnTimeAscIdAsc(
                        businessDate, ZpCommittedTxnEntity.KIND_REFUND);
        List<Zp0021Record> out = new ArrayList<>(txns.size());
        for (ZpCommittedTxnEntity t : txns) {
            out.add(new Zp0021Record(
                    t.getGmeTxnId(),
                    t.getZeropayTxnRef(),
                    t.getMerchantId(),
                    t.getQrCodeId(),
                    t.getBusinessDate(),
                    t.getTxnTime(),
                    t.getAmountKrw(),
                    t.getMerchantFeeKrw(),
                    t.getVanFeeKrw(),
                    partnerChar(t.getPartnerType()),
                    originalApproval(t),
                    'R'));
        }
        return out;
    }

    @Override
    public List<ZpSettlementRequestRecord> fetchSettlementRecords(LocalDate businessDate) {
        // Aggregate per merchant across both payments and refunds (insertion-ordered by merchant).
        Map<String, Aggregate> byMerchant = new LinkedHashMap<>();
        for (ZpCommittedTxnEntity t : repository
                .findByBusinessDateOrderByMerchantIdAscTxnTimeAsc(businessDate)) {
            Aggregate agg = byMerchant.computeIfAbsent(
                    t.getMerchantId() == null ? "" : t.getMerchantId(), k -> new Aggregate());
            if (ZpCommittedTxnEntity.KIND_PAYMENT.equals(t.getTxnKind())) {
                agg.paymentCount++;
                agg.gross = agg.gross.add(t.getAmountKrw());
                agg.merchantFee = agg.merchantFee.add(t.getMerchantFeeKrw());
                agg.vanFee = agg.vanFee.add(t.getVanFeeKrw());
            } else {
                agg.refundCount++;
                agg.refund = agg.refund.add(t.getAmountKrw());
                // Fee reversals reduce the aggregate fees.
                agg.merchantFee = agg.merchantFee.subtract(t.getMerchantFeeKrw());
                agg.vanFee = agg.vanFee.subtract(t.getVanFeeKrw());
            }
        }
        List<ZpSettlementRequestRecord> out = new ArrayList<>(byMerchant.size());
        for (Map.Entry<String, Aggregate> e : byMerchant.entrySet()) {
            Aggregate a = e.getValue();
            BigDecimal net = a.gross.subtract(a.refund);
            out.add(new ZpSettlementRequestRecord(
                    e.getKey(),
                    businessDate,
                    a.paymentCount,
                    a.gross,
                    a.refundCount,
                    a.refund,
                    net,
                    a.merchantFee,
                    a.vanFee));
        }
        return out;
    }

    @Override
    public List<Zp0065Record> fetchPaymentDetailRecords(LocalDate businessDate) {
        List<ZpCommittedTxnEntity> txns = repository
                .findByBusinessDateAndTxnKindOrderByTxnTimeAscIdAsc(
                        businessDate, ZpCommittedTxnEntity.KIND_PAYMENT);
        List<Zp0065Record> out = new ArrayList<>(txns.size());
        for (ZpCommittedTxnEntity t : txns) {
            out.add(new Zp0065Record(
                    t.getGmeTxnId(),
                    t.getZeropayTxnRef(),
                    t.getMerchantId(),
                    t.getQrCodeId(),
                    t.getBusinessDate(),
                    t.getTxnTime(),
                    t.getAmountKrw(),
                    t.getMerchantFeeKrw(),
                    t.getVanFeeKrw(),
                    partnerChar(t.getPartnerType()),
                    t.getApprovalCode(),
                    'A',
                    t.getSettlementDate() == null ? t.getBusinessDate() : t.getSettlementDate()));
        }
        return out;
    }

    @Override
    public List<Zp0066Record> fetchRefundDetailRecords(LocalDate businessDate) {
        List<ZpCommittedTxnEntity> txns = repository
                .findByBusinessDateAndTxnKindOrderByTxnTimeAscIdAsc(
                        businessDate, ZpCommittedTxnEntity.KIND_REFUND);
        List<Zp0066Record> out = new ArrayList<>(txns.size());
        for (ZpCommittedTxnEntity t : txns) {
            out.add(new Zp0066Record(
                    t.getGmeTxnId(),
                    t.getZeropayTxnRef(),
                    t.getMerchantId(),
                    t.getQrCodeId(),
                    t.getBusinessDate(),
                    t.getTxnTime(),
                    t.getAmountKrw(),
                    t.getMerchantFeeKrw(),
                    t.getVanFeeKrw(),
                    partnerChar(t.getPartnerType()),
                    originalApproval(t),
                    'R',
                    t.getSettlementDate() == null ? t.getBusinessDate() : t.getSettlementDate()));
        }
        return out;
    }

    private static char partnerChar(String partnerType) {
        return (partnerType == null || partnerType.isEmpty()) ? 'D' : partnerType.charAt(0);
    }

    /** Refund's original approval code; falls back to the refund id when unknown. */
    private static String originalApproval(ZpCommittedTxnEntity t) {
        return t.getOriginalApprovalCode() != null ? t.getOriginalApprovalCode() : t.getApprovalCode();
    }

    /** Mutable per-merchant accumulator for settlement aggregation. */
    private static final class Aggregate {
        int paymentCount;
        int refundCount;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal refund = BigDecimal.ZERO;
        BigDecimal merchantFee = BigDecimal.ZERO;
        BigDecimal vanFee = BigDecimal.ZERO;
    }
}
