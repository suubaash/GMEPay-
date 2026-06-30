package com.gme.pay.scheme.zeropay.batch;

import com.gme.pay.scheme.zeropay.persistence.ZpCommittedTxnEntity;
import com.gme.pay.scheme.zeropay.persistence.ZpCommittedTxnRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ZpPersistenceBatchDataPort} mapping committed txns to ZP00xx records,
 * against a mocked repository. No Spring context, no database.
 */
class ZpPersistenceBatchDataPortTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 9);

    private final ZpCommittedTxnRepository repo = mock(ZpCommittedTxnRepository.class);
    private final ZpPersistenceBatchDataPort port = new ZpPersistenceBatchDataPort(repo);

    private static ZpCommittedTxnEntity payment(String ref, String merchant, String amount,
                                                String merchFee, String vanFee, String partner) {
        return ZpCommittedTxnEntity.payment(
                "GME-" + ref, ref, merchant, "QR-" + ref, DATE, LocalTime.of(10, 0, 0),
                new BigDecimal(amount), new BigDecimal(merchFee), new BigDecimal(vanFee),
                partner, "AP-" + ref, null);
    }

    private static ZpCommittedTxnEntity refund(String ref, String merchant, String amount,
                                               String origAppr) {
        return ZpCommittedTxnEntity.refund(
                "GME-" + ref, ref, merchant, "QR-" + ref, DATE, LocalTime.of(14, 0, 0),
                new BigDecimal(amount), new BigDecimal("250"), new BigDecimal("100"),
                "D", "RF-" + ref, origAppr, null);
    }

    @Test
    void fetchPaymentRecords_mapsAllFields() {
        when(repo.findByBusinessDateAndTxnKindOrderByTxnTimeAscIdAsc(
                eq(DATE), eq(ZpCommittedTxnEntity.KIND_PAYMENT)))
                .thenReturn(List.of(payment("ZP1", "M1", "50000", "250", "100", "I")));

        List<Zp0011Record> records = port.fetchPaymentRecords(DATE);
        assertEquals(1, records.size());
        Zp0011Record r = records.get(0);
        assertEquals("ZP1", r.zeroPayTxnRef());
        assertEquals("M1", r.merchantId());
        assertEquals(0, r.payoutAmountKrw().compareTo(new BigDecimal("50000")));
        assertEquals(0, r.merchantFeeAmt().compareTo(new BigDecimal("250")));
        assertEquals('I', r.partnerType());
        assertEquals("AP-ZP1", r.approvalCode());
        assertEquals('A', r.statusCode());
    }

    @Test
    void fetchRefundRecords_usesOriginalApprovalCodeAndRefundedStatus() {
        when(repo.findByBusinessDateAndTxnKindOrderByTxnTimeAscIdAsc(
                eq(DATE), eq(ZpCommittedTxnEntity.KIND_REFUND)))
                .thenReturn(List.of(refund("ZPR1", "M1", "50000", "AP-ZP1")));

        List<Zp0021Record> records = port.fetchRefundRecords(DATE);
        assertEquals(1, records.size());
        Zp0021Record r = records.get(0);
        assertEquals("AP-ZP1", r.originalApprovalCode());
        assertEquals('R', r.statusCode());
        assertEquals(0, r.refundAmountKrw().compareTo(new BigDecimal("50000")));
    }

    @Test
    void fetchSettlementRecords_aggregatesPerMerchantWithFeeNetting() {
        when(repo.findByBusinessDateOrderByMerchantIdAscTxnTimeAsc(eq(DATE)))
                .thenReturn(List.of(
                        payment("ZP1", "M1", "50000", "250", "100", "D"),
                        payment("ZP2", "M1", "30000", "150", "60", "D"),
                        refund("ZPR1", "M1", "20000", "AP-ZP1"),       // fees 250/100 reversed
                        payment("ZP3", "M2", "10000", "50", "20", "D")));

        List<ZpSettlementRequestRecord> records = port.fetchSettlementRecords(DATE);
        assertEquals(2, records.size());

        ZpSettlementRequestRecord m1 = records.stream()
                .filter(r -> r.merchantId().equals("M1")).findFirst().orElseThrow();
        assertEquals(2, m1.paymentCount());
        assertEquals(1, m1.refundCount());
        assertEquals(0, m1.grossAmountKrw().compareTo(new BigDecimal("80000")));   // 50000+30000
        assertEquals(0, m1.refundAmountKrw().compareTo(new BigDecimal("20000")));
        assertEquals(0, m1.netAmountKrw().compareTo(new BigDecimal("60000")));     // 80000-20000
        // merchant fees: 250 + 150 - 250 (reversal) = 150
        assertEquals(0, m1.merchantFeeKrw().compareTo(new BigDecimal("150")));
        // van fees: 100 + 60 - 100 (reversal) = 60
        assertEquals(0, m1.vanFeeKrw().compareTo(new BigDecimal("60")));

        ZpSettlementRequestRecord m2 = records.stream()
                .filter(r -> r.merchantId().equals("M2")).findFirst().orElseThrow();
        assertEquals(1, m2.paymentCount());
        assertEquals(0, m2.netAmountKrw().compareTo(new BigDecimal("10000")));
    }

    @Test
    void fetchPaymentDetailRecords_fallsBackSettlementDateToBusinessDate() {
        when(repo.findByBusinessDateAndTxnKindOrderByTxnTimeAscIdAsc(
                eq(DATE), eq(ZpCommittedTxnEntity.KIND_PAYMENT)))
                .thenReturn(List.of(payment("ZP1", "M1", "50000", "250", "100", "D")));

        List<Zp0065Record> records = port.fetchPaymentDetailRecords(DATE);
        assertEquals(1, records.size());
        assertEquals(DATE, records.get(0).settlementDate());
        assertEquals('A', records.get(0).statusCode());
    }

    @Test
    void fetchRefundDetailRecords_mapsRefundLeg() {
        when(repo.findByBusinessDateAndTxnKindOrderByTxnTimeAscIdAsc(
                eq(DATE), eq(ZpCommittedTxnEntity.KIND_REFUND)))
                .thenReturn(List.of(refund("ZPR1", "M1", "50000", "AP-ZP1")));

        List<Zp0066Record> records = port.fetchRefundDetailRecords(DATE);
        assertEquals(1, records.size());
        assertEquals("AP-ZP1", records.get(0).originalApprovalCode());
        assertEquals('R', records.get(0).statusCode());
    }

    // -- enrichment (IR-1 refund amount/merchant, IR-3 settlement value date) -------------------

    /** A refund leg captured with no merchant and amount 0 (the pre-enrichment commit-path state). */
    private static ZpCommittedTxnEntity bareRefund(String ref, String origAppr) {
        return ZpCommittedTxnEntity.refund(
                "GME-" + ref, ref, null, null, DATE, LocalTime.of(14, 0, 0),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "D", "RF-" + ref, origAppr, null);
    }

    private static ZpBatchEnrichmentPort enrichmentWith(
            java.util.Map<String, ZpBatchEnrichmentPort.RefundEnrichment> refunds,
            java.util.Map<String, LocalDate> valueDates) {
        return new ZpBatchEnrichmentPort() {
            @Override
            public java.util.Map<String, RefundEnrichment> refundEnrichment(LocalDate d) {
                return refunds;
            }

            @Override
            public java.util.Map<String, LocalDate> settlementValueDates(LocalDate d) {
                return valueDates;
            }
        };
    }

    @Test
    void refundEnrichment_fillsRealAmountAndMerchantInZp0021AndZp0066() {
        ZpBatchEnrichmentPort enrichment = enrichmentWith(
                java.util.Map.of("ZPR1", new ZpBatchEnrichmentPort.RefundEnrichment(
                        new BigDecimal("33000"), "M9", "QR-REAL")),
                java.util.Map.of());
        ZpPersistenceBatchDataPort enriched = new ZpPersistenceBatchDataPort(repo, enrichment);
        when(repo.findByBusinessDateAndTxnKindOrderByTxnTimeAscIdAsc(
                eq(DATE), eq(ZpCommittedTxnEntity.KIND_REFUND)))
                .thenReturn(List.of(bareRefund("ZPR1", "AP-ZP1")));

        Zp0021Record r21 = enriched.fetchRefundRecords(DATE).get(0);
        assertEquals(0, r21.refundAmountKrw().compareTo(new BigDecimal("33000")));
        assertEquals("M9", r21.merchantId());
        assertEquals("QR-REAL", r21.qrCodeId());

        Zp0066Record r66 = enriched.fetchRefundDetailRecords(DATE).get(0);
        assertEquals(0, r66.refundAmountKrw().compareTo(new BigDecimal("33000")));
        assertEquals("M9", r66.merchantId());
    }

    @Test
    void refundEnrichment_keepsCapturedValuesWhenNoUpstreamMatch() {
        // No-arg ctor => empty no-op enrichment; captured row already has merchant + amount.
        when(repo.findByBusinessDateAndTxnKindOrderByTxnTimeAscIdAsc(
                eq(DATE), eq(ZpCommittedTxnEntity.KIND_REFUND)))
                .thenReturn(List.of(refund("ZPR1", "M1", "50000", "AP-ZP1")));

        Zp0021Record r21 = port.fetchRefundRecords(DATE).get(0);
        assertEquals(0, r21.refundAmountKrw().compareTo(new BigDecimal("50000")));
        assertEquals("M1", r21.merchantId());
    }

    @Test
    void settlementValueDate_fromUpstreamUsedInZp0065AndZp0066() {
        LocalDate valueDate = LocalDate.of(2026, 6, 11);     // T+2, differs from business date
        ZpBatchEnrichmentPort enrichment = enrichmentWith(
                java.util.Map.of(),
                java.util.Map.of("ZP1", valueDate, "ZPR1", valueDate));
        ZpPersistenceBatchDataPort enriched = new ZpPersistenceBatchDataPort(repo, enrichment);
        when(repo.findByBusinessDateAndTxnKindOrderByTxnTimeAscIdAsc(
                eq(DATE), eq(ZpCommittedTxnEntity.KIND_PAYMENT)))
                .thenReturn(List.of(payment("ZP1", "M1", "50000", "250", "100", "D")));
        when(repo.findByBusinessDateAndTxnKindOrderByTxnTimeAscIdAsc(
                eq(DATE), eq(ZpCommittedTxnEntity.KIND_REFUND)))
                .thenReturn(List.of(refund("ZPR1", "M1", "20000", "AP-ZP1")));

        assertEquals(valueDate, enriched.fetchPaymentDetailRecords(DATE).get(0).settlementDate());
        assertEquals(valueDate, enriched.fetchRefundDetailRecords(DATE).get(0).settlementDate());
    }

    @Test
    void settlementValueDate_fallsBackToBusinessDateWhenNoUpstream() {
        // No upstream value date -> business-date fallback (DATE), matching pre-enrichment behaviour.
        when(repo.findByBusinessDateAndTxnKindOrderByTxnTimeAscIdAsc(
                eq(DATE), eq(ZpCommittedTxnEntity.KIND_PAYMENT)))
                .thenReturn(List.of(payment("ZP1", "M1", "50000", "250", "100", "D")));

        assertEquals(DATE, port.fetchPaymentDetailRecords(DATE).get(0).settlementDate());
    }
}
