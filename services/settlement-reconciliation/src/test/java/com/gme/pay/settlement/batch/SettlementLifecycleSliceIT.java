package com.gme.pay.settlement.batch;

import com.gme.pay.settlement.builder.AbstractZeroPayFileBuilder;
import com.gme.pay.settlement.builder.BuildContext;
import com.gme.pay.settlement.builder.ZP0061RequestBuilder;
import com.gme.pay.settlement.model.TransactionRecord;
import com.gme.pay.settlement.outbox.OutboxEntity;
import com.gme.pay.settlement.outbox.OutboxRepository;
import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.persistence.SettlementLineEntity;
import com.gme.pay.settlement.persistence.SettlementLineRepository;
import com.gme.pay.settlement.port.PartnerConfigPort;
import com.gme.pay.settlement.port.RefundedTransactionPort;
import com.gme.pay.settlement.port.TransactionQueryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The wired settlement-lifecycle slice (checklist §8): proves
 * <b>payment → persisted per-partner batch + lines → generated ZP0061 file content → outbox event</b>
 * against the REAL Spring context, REAL JPA repositories and REAL transactional outbox (H2) —
 * only the three cross-service ports are doubles. The existing unit tests each cover one piece
 * with mocked repos; this is the single test where the whole booked lifecycle runs end-to-end
 * in-process, and where the file bytes are re-derived independently and tied to the batch's
 * stored checksum. (Transport is deliberately out of scope: {@code transmitted_at} stays null
 * until the sftp-gateway lands.)
 */
@SpringBootTest
@DisplayName("Settlement lifecycle slice: txns -> booked batch + lines -> ZP0061 bytes -> outbox")
class SettlementLifecycleSliceIT {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String MERCHANT = "ZP-M777";
    private static final BigDecimal PAY_1 = new BigDecimal("50000");
    private static final BigDecimal PAY_2 = new BigDecimal("34720");
    private static final BigDecimal FEE_RATE = new BigDecimal("0.005");

    @MockBean private TransactionQueryPort txnPort;
    @MockBean private PartnerConfigPort partnerConfigPort;
    @MockBean private RefundedTransactionPort refundedPort;

    @Autowired private SettlementBatchJobService job;
    @Autowired private SettlementLineRepository lineRepo;
    @Autowired private OutboxRepository outboxRepo;

    @Test
    void morningWindow_booksBatch_persistsLines_generatesFile_emitsOutboxEvent() {
        LocalDate today = LocalDate.now(KST);
        // Approved well before the 04:30 morning cutoff so both txns settle in THIS window.
        OffsetDateTime approvedAt = today.atTime(3, 0).atZone(KST).toOffsetDateTime();

        when(txnPort.findUnbatchedApproved(any(LocalDate.class))).thenReturn(List.of(
                txn(1L, "TXN-SL-1", PAY_1, approvedAt),
                txn(2L, "TXN-SL-2", PAY_2, approvedAt)));
        when(txnPort.findUnbatchedRefunded(any(LocalDate.class))).thenReturn(List.of());
        when(refundedPort.findRefundedOn(any(LocalDate.class))).thenReturn(List.of());
        when(partnerConfigPort.resolve(anyString())).thenReturn(
                new PartnerConfigPort.PartnerSettlementConfig(MERCHANT, "KRW", RoundingMode.HALF_UP));

        SettlementBatchEntity batch = job.runWindow("ZP0061", "MORNING");

        // --- 1) The per-partner batch row is really persisted with the booked money. ---
        // NET: gross 84720, fee = 84720 * 0.005 = 423.60 -> precise net 84296.40 -> HALF_UP 84296;
        // file fee = gross - booked = 424 (true fee + rounding residual keeps the file balanced).
        assertEquals(SettlementBatchStatus.GENERATED.name(), batch.getStatus());
        assertEquals(0, new BigDecimal("84296").compareTo(batch.getNetSettlementAmount()),
                "booked net under HALF_UP");
        assertEquals(0, new BigDecimal("424").compareTo(batch.getMerchantFeeTotal()),
                "file fee = gross - booked keeps gross = net + fee");
        assertEquals(1, batch.getRecordCount(), "one DATA row per (merchant, type)");
        assertNotNull(batch.getFileChecksum(), "generated file checksum stored on the batch");

        // --- 2) One line per payment, positive whole-KRW, snapshotting detail-file fields. ---
        List<SettlementLineEntity> lines = lineRepo.findAll().stream()
                .filter(l -> l.getBatchId().equals(batch.getBatchId())).toList();
        assertEquals(2, lines.size());
        assertTrue(lines.stream().allMatch(l -> l.getAmount().signum() > 0));
        assertTrue(lines.stream().anyMatch(l -> "TXN-SL-1".equals(l.getTxnRef())));
        assertTrue(lines.stream().anyMatch(l -> "TXN-SL-2".equals(l.getTxnRef())));

        // --- 3) The ZP0061 file content is reproducible byte-for-byte from the booked figures:
        // rebuilding the same BuildContext independently must yield the SAME checksum the job stored.
        // Mirror the job's arithmetic exactly (scales matter for the byte-identical rebuild):
        // precise = gross - Σ(payout*rate); booked = HALF_UP@KRW; residual = precise - booked.
        BigDecimal gross = PAY_1.add(PAY_2);
        BigDecimal feePrecise = PAY_1.multiply(FEE_RATE).add(PAY_2.multiply(FEE_RATE));
        BigDecimal precise = gross.subtract(feePrecise);
        BigDecimal booked = precise.setScale(0, RoundingMode.HALF_UP);
        assertEquals(0, new BigDecimal("84296").compareTo(booked), "sanity: expected booked net");
        BigDecimal fileFee = gross.subtract(booked);
        BigDecimal residual = precise.subtract(booked);
        BuildContext ctx = new BuildContext(today.format(DateTimeFormatter.BASIC_ISO_DATE), 1, List.of(
                new BuildContext.MerchantRow(MERCHANT, 2, gross, 0, BigDecimal.ZERO,
                        fileFee, booked, residual, RoundingMode.HALF_UP, 'N')));
        AbstractZeroPayFileBuilder.BuiltFile rebuilt = new ZP0061RequestBuilder("ZP0061").build(ctx);
        assertEquals(rebuilt.checksum(), batch.getFileChecksum(),
                "independently rebuilt ZP0061 bytes must hash to the stored batch checksum");
        assertEquals(rebuilt.recordCount(), batch.getRecordCount());

        // --- 4) The settlement.completed event committed to the REAL outbox table atomically. ---
        List<OutboxEntity> events = outboxRepo.findAll().stream()
                .filter(e -> "settlement.completed".equals(e.getEventType())
                        && batch.getBatchId().toString().equals(e.getAggregateId()))
                .toList();
        assertEquals(1, events.size(), "exactly one settlement.completed outbox row for this batch");
        assertTrue(events.get(0).getPayload().contains(batch.getFileChecksum()),
                "outbox payload carries the file checksum for downstream verification");
    }

    private static TransactionRecord txn(Long id, String ref, BigDecimal payoutKrw, OffsetDateTime approvedAt) {
        return new TransactionRecord(id, ref, "ZP-" + ref, MERCHANT, payoutKrw,
                'N', FEE_RATE, "APPROVED", approvedAt, null);
    }
}
