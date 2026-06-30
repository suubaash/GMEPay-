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
 *
 * <p>Refund amount/merchant/qrCode (IR-1) and the settlement value date (IR-3) are enriched from
 * transaction-management via {@link ZpBatchEnrichmentPort} when
 * {@code adapter.zeropay.enrichment.enabled=true}; otherwise the captured values (and a
 * business-date value-date fallback) are used.</p>
 *
 * <p>TODO (REMAINING INTEGRATION REQUEST — fees): {@code merchantFeeKrw}/{@code vanFeeKrw} are
 * still sourced from the captured rows, which are 0 because the commit-time path does not receive
 * computed fees. Fee values belong to the commission/config side (backlog #98), not this adapter —
 * do NOT build a fee table here. Once the fee source publishes per-txn fees, enrich them the same
 * way as the refund/value-date fields above.</p>
 */
@Component
@Primary
@ConditionalOnProperty(name = "adapter.zeropay.batch-data-source",
        havingValue = "persistence", matchIfMissing = true)
public class ZpPersistenceBatchDataPort implements ZpBatchDataPort {

    private final ZpCommittedTxnRepository repository;
    private final ZpBatchEnrichmentPort enrichment;

    public ZpPersistenceBatchDataPort(ZpCommittedTxnRepository repository,
                                      ZpBatchEnrichmentPort enrichment) {
        this.repository = repository;
        this.enrichment = enrichment;
    }

    /**
     * Convenience constructor used by unit tests that do not exercise cross-service enrichment;
     * enrichment defaults to an empty no-op (refund amounts stay as captured, value date falls
     * back to the business date).
     */
    ZpPersistenceBatchDataPort(ZpCommittedTxnRepository repository) {
        this(repository, new ZpBatchEnrichmentPort() {
            @Override
            public Map<String, ZpBatchEnrichmentPort.RefundEnrichment> refundEnrichment(LocalDate d) {
                return Map.of();
            }

            @Override
            public Map<String, LocalDate> settlementValueDates(LocalDate d) {
                return Map.of();
            }
        });
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
        Map<String, ZpBatchEnrichmentPort.RefundEnrichment> refunds =
                enrichment.refundEnrichment(businessDate);
        List<Zp0021Record> out = new ArrayList<>(txns.size());
        for (ZpCommittedTxnEntity t : txns) {
            ZpBatchEnrichmentPort.RefundEnrichment e = refunds.get(t.getZeropayTxnRef());
            out.add(new Zp0021Record(
                    t.getGmeTxnId(),
                    t.getZeropayTxnRef(),
                    merchantId(t, e),
                    qrCodeId(t, e),
                    t.getBusinessDate(),
                    t.getTxnTime(),
                    refundAmount(t, e),
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
        Map<String, ZpBatchEnrichmentPort.RefundEnrichment> refunds =
                enrichment.refundEnrichment(businessDate);
        // Aggregate per merchant across both payments and refunds (insertion-ordered by merchant).
        Map<String, Aggregate> byMerchant = new LinkedHashMap<>();
        for (ZpCommittedTxnEntity t : repository
                .findByBusinessDateOrderByMerchantIdAscTxnTimeAsc(businessDate)) {
            if (ZpCommittedTxnEntity.KIND_PAYMENT.equals(t.getTxnKind())) {
                Aggregate agg = byMerchant.computeIfAbsent(
                        t.getMerchantId() == null ? "" : t.getMerchantId(), k -> new Aggregate());
                agg.paymentCount++;
                agg.gross = agg.gross.add(t.getAmountKrw());
                agg.merchantFee = agg.merchantFee.add(t.getMerchantFeeKrw());
                agg.vanFee = agg.vanFee.add(t.getVanFeeKrw());
            } else {
                // Refund leg: use enriched merchant + amount where the captured row lacks them.
                ZpBatchEnrichmentPort.RefundEnrichment e = refunds.get(t.getZeropayTxnRef());
                String merchant = merchantId(t, e);
                Aggregate agg = byMerchant.computeIfAbsent(
                        merchant == null ? "" : merchant, k -> new Aggregate());
                agg.refundCount++;
                agg.refund = agg.refund.add(refundAmount(t, e));
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
        Map<String, LocalDate> valueDates = enrichment.settlementValueDates(businessDate);
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
                    settlementValueDate(t, valueDates)));
        }
        return out;
    }

    @Override
    public List<Zp0066Record> fetchRefundDetailRecords(LocalDate businessDate) {
        List<ZpCommittedTxnEntity> txns = repository
                .findByBusinessDateAndTxnKindOrderByTxnTimeAscIdAsc(
                        businessDate, ZpCommittedTxnEntity.KIND_REFUND);
        Map<String, ZpBatchEnrichmentPort.RefundEnrichment> refunds =
                enrichment.refundEnrichment(businessDate);
        Map<String, LocalDate> valueDates = enrichment.settlementValueDates(businessDate);
        List<Zp0066Record> out = new ArrayList<>(txns.size());
        for (ZpCommittedTxnEntity t : txns) {
            ZpBatchEnrichmentPort.RefundEnrichment e = refunds.get(t.getZeropayTxnRef());
            out.add(new Zp0066Record(
                    t.getGmeTxnId(),
                    t.getZeropayTxnRef(),
                    merchantId(t, e),
                    qrCodeId(t, e),
                    t.getBusinessDate(),
                    t.getTxnTime(),
                    refundAmount(t, e),
                    t.getMerchantFeeKrw(),
                    t.getVanFeeKrw(),
                    partnerChar(t.getPartnerType()),
                    originalApproval(t),
                    'R',
                    settlementValueDate(t, valueDates)));
        }
        return out;
    }

    // -- enrichment helpers ---------------------------------------------------

    /**
     * Settlement value date (IR-3): the committed {@code settlementDate} from upstream when known,
     * else the row's own captured settlement date, else a business-date fallback.
     */
    private static LocalDate settlementValueDate(ZpCommittedTxnEntity t,
                                                 Map<String, LocalDate> valueDates) {
        LocalDate upstream = valueDates.get(t.getZeropayTxnRef());
        if (upstream != null) {
            return upstream;
        }
        return t.getSettlementDate() == null ? t.getBusinessDate() : t.getSettlementDate();
    }

    /** Refund amount (IR-1): enriched amount when the captured row has none (0), else captured. */
    private static BigDecimal refundAmount(ZpCommittedTxnEntity t,
                                           ZpBatchEnrichmentPort.RefundEnrichment e) {
        BigDecimal captured = t.getAmountKrw();
        if (e != null && e.refundAmountKrw() != null
                && (captured == null || captured.signum() == 0)) {
            return e.refundAmountKrw();
        }
        return captured;
    }

    /** Merchant id (IR-1): enriched value when the captured row is blank, else captured. */
    private static String merchantId(ZpCommittedTxnEntity t,
                                     ZpBatchEnrichmentPort.RefundEnrichment e) {
        if (isBlank(t.getMerchantId()) && e != null && !isBlank(e.merchantId())) {
            return e.merchantId();
        }
        return t.getMerchantId();
    }

    /** QR code id (IR-1): enriched value when the captured row is blank, else captured. */
    private static String qrCodeId(ZpCommittedTxnEntity t,
                                   ZpBatchEnrichmentPort.RefundEnrichment e) {
        if (isBlank(t.getQrCodeId()) && e != null && !isBlank(e.qrCodeId())) {
            return e.qrCodeId();
        }
        return t.getQrCodeId();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
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
