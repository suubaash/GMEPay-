package com.gme.pay.settlement.batch;

import com.gme.pay.events.EventPublisher;
import com.gme.pay.money.BookedAmount;
import com.gme.pay.settlement.booking.SettlementBookingService;
import com.gme.pay.settlement.builder.AbstractZeroPayFileBuilder;
import com.gme.pay.settlement.builder.BuildContext;
import com.gme.pay.settlement.builder.ZP0061RequestBuilder;
import com.gme.pay.settlement.model.TransactionRecord;
import com.gme.pay.settlement.outbox.OutboxAppender;
import com.gme.pay.settlement.outbox.SettlementCompletedEvent;
import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.persistence.SettlementBatchRepository;
import com.gme.pay.settlement.persistence.SettlementLineEntity;
import com.gme.pay.settlement.persistence.SettlementLineRepository;
import com.gme.pay.settlement.port.PartnerConfigPort;
import com.gme.pay.settlement.port.TransactionQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Orchestrates one outbound settlement-file window run end-to-end, in a single transaction so the batch,
 * its lines, and the {@code settlement.completed} outbox row commit atomically:
 * <ol>
 *   <li>idempotent {@link SettlementBatchFactory#createOrGet} (no-op if the batch already advanced past PENDING);</li>
 *   <li>pull APPROVED transactions for the KST business date, group by <b>(merchant, settlement type)</b>;</li>
 *   <li>per group: resolve the partner's rounding mode, book the per-partner net under Addendum-001
 *       ({@link SettlementBookingService}), persist a line per txn;</li>
 *   <li>build the ZP0061/0063 file (checksum + record count), move PENDING → GENERATED;</li>
 *   <li>append {@link SettlementCompletedEvent} to the outbox.</li>
 * </ol>
 * On any failure the whole transaction rolls back atomically — no partial/stuck batch is left, and the
 * next run starts clean (the failure is logged; persisting an ERROR row for ops visibility in a separate
 * transaction is a follow-up).
 *
 * <p><b>Known upstream data gaps (flagged, not faked):</b> the transaction REST projection currently
 * yields {@code merchantFeeRate=0} (so NET fee books to 0 until transaction-mgmt exposes the rate) and
 * has no refund feed or window cutoff; per-partner grouping keys off {@code merchantId} as the partner
 * code. ZeroPay settles in KRW, so amounts are booked at KRW scale under the partner's mode.
 */
@Service
public class SettlementBatchJobService {

    private static final Logger log = LoggerFactory.getLogger(SettlementBatchJobService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String SETTLE_CCY = "KRW";   // ZeroPay settles in KRW (scale 0)
    private static final char GROUP_SEP = '|';   // delimiter unlikely to appear in a merchant id

    private final TransactionQueryPort txnPort;
    private final PartnerConfigPort partnerConfigPort;
    private final SettlementBookingService booking;
    private final SettlementBatchFactory batchFactory;
    private final SettlementBatchRepository batchRepo;
    private final SettlementLineRepository lineRepo;
    private final EventPublisher outbox;

    public SettlementBatchJobService(TransactionQueryPort txnPort,
                                     PartnerConfigPort partnerConfigPort,
                                     SettlementBookingService booking,
                                     SettlementBatchFactory batchFactory,
                                     SettlementBatchRepository batchRepo,
                                     SettlementLineRepository lineRepo,
                                     @Qualifier(OutboxAppender.BEAN_NAME) EventPublisher outbox) {
        this.txnPort = txnPort;
        this.partnerConfigPort = partnerConfigPort;
        this.booking = booking;
        this.batchFactory = batchFactory;
        this.batchRepo = batchRepo;
        this.lineRepo = lineRepo;
        this.outbox = outbox;
    }

    /** @param fileType "ZP0061" (morning) or "ZP0063" (afternoon); @param window e.g. "MORNING"/"AFTERNOON". */
    @Transactional
    public SettlementBatchEntity runWindow(String fileType, String window) {
        requireRequestFile(fileType);
        LocalDate date = LocalDate.now(KST);
        SettlementBatchEntity batch = batchFactory.createOrGet(fileType, date, window);

        // Generate only for a fresh PENDING batch. A batch that already advanced (GENERATED and beyond)
        // is a no-op — never re-delete its lines or re-emit the event.
        if (!SettlementBatchStatus.PENDING.name().equals(batch.getStatus())) {
            log.info("settlement batch {} already at {} — idempotent no-op", batch.getBatchId(), batch.getStatus());
            return batch;
        }

        lineRepo.deleteByBatchId(batch.getBatchId());   // clean (re-)generation of this PENDING batch

        List<TransactionRecord> txns = txnPort.findUnbatchedApproved(date).stream()
                .filter(TransactionRecord::isApproved)
                .collect(Collectors.toList());

        // Group by (merchantId, settlementType) so a merchant with both domestic NET and international
        // GROSS txns yields one row per type — never a blended row (mirrors SettlementService read-path).
        Map<String, List<TransactionRecord>> byMerchantType = txns.stream()
                .collect(Collectors.groupingBy(
                        t -> t.merchantId() + GROUP_SEP + t.settlementType(),
                        LinkedHashMap::new, Collectors.toList()));

        List<BuildContext.MerchantRow> rows = new ArrayList<>();
        BigDecimal netTotal = BigDecimal.ZERO;
        BigDecimal feeTotal = BigDecimal.ZERO;
        BigDecimal residualTotal = BigDecimal.ZERO;

        for (List<TransactionRecord> group : byMerchantType.values()) {
            String merchantId = group.get(0).merchantId();
            char type = group.get(0).settlementType();   // homogeneous within a (merchant, type) group
            PartnerConfigPort.PartnerSettlementConfig cfg = partnerConfigPort.resolve(merchantId);

            // KRW gross is integer by convention; normalise defensively so the file emits whole KRW and
            // fileFee (= gross - bookedNet) is provably non-negative for fee rates >= 0.
            BigDecimal grossSum = group.stream()
                    .map(t -> nz(t.targetPayoutKrw())).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(0, RoundingMode.HALF_UP);
            BigDecimal feeSumPrecise = BigDecimal.ZERO;
            if (type == 'N') {
                for (TransactionRecord t : group) {
                    feeSumPrecise = feeSumPrecise.add(nz(t.targetPayoutKrw()).multiply(nz(t.merchantFeeRate())));
                }
            }
            BigDecimal precise = (type == 'N') ? grossSum.subtract(feeSumPrecise) : grossSum;
            BookedAmount booked = booking.book(SETTLE_CCY, cfg.mode(), precise, type);
            // merchant_fee_total keeps the file balanced (gross = net + fee); it equals trueFee + residual.
            // The residual is also carried to rounding_residual for the (pending) REVENUE_ROUNDING post —
            // wire only ONE of the two before treating merchant_fee_total as pure fee revenue.
            BigDecimal fileFee = (type == 'N') ? grossSum.subtract(booked.booked()) : BigDecimal.ZERO;

            for (TransactionRecord t : group) {
                SettlementLineEntity line = new SettlementLineEntity(
                        batch.getBatchId(), t.txnRef(), nz(t.targetPayoutKrw()), SETTLE_CCY, false);
                line.setSettlementType(String.valueOf(type));
                line.setSettlementRoundingMode(booked.mode().name());
                lineRepo.save(line);
            }

            rows.add(new BuildContext.MerchantRow(merchantId, group.size(), grossSum,
                    0, BigDecimal.ZERO, fileFee, booked.booked(), booked.residual(), booked.mode(), type));
            netTotal = netTotal.add(booked.booked());
            feeTotal = feeTotal.add(fileFee);
            residualTotal = residualTotal.add(booked.residual());
        }

        BuildContext ctx = new BuildContext(date.format(DateTimeFormatter.BASIC_ISO_DATE), 1, rows);
        AbstractZeroPayFileBuilder.BuiltFile file = new ZP0061RequestBuilder(fileType).build(ctx);

        batch.setSettleCurrency(SETTLE_CCY);
        batch.setNetSettlementAmount(netTotal);
        batch.setMerchantFeeTotal(feeTotal);
        batch.setRoundingResidual(residualTotal);
        batch.setFileChecksum(file.checksum());
        batch.setRecordCount(file.recordCount());
        batch.setTotalAmount(netTotal);
        batch.setTotalCurrency(SETTLE_CCY);
        transition(batch, SettlementBatchStatus.GENERATED);
        batchRepo.save(batch);

        outbox.publish(new SettlementCompletedEvent(
                batch.getBatchId(), fileType, window, date, null,
                netTotal, SETTLE_CCY, txns.size(), file.checksum()));

        log.info("settlement batch {} GENERATED: {} row(s), net={} KRW, residual={}, checksum={}",
                batch.getBatchId(), rows.size(), netTotal, residualTotal, file.checksum());
        return batch;
    }

    private static void transition(SettlementBatchEntity batch, SettlementBatchStatus to) {
        SettlementBatchStatus from;
        try {
            from = SettlementBatchStatus.valueOf(batch.getStatus());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateTransitionException(
                    "settlement batch " + batch.getBatchId() + " has no/unknown status '" + batch.getStatus()
                            + "' — cannot transition to " + to);
        }
        if (!from.canMoveTo(to)) {
            throw new IllegalStateTransitionException(
                    "illegal settlement batch transition " + from + " -> " + to + " for " + batch.getBatchId());
        }
        batch.setStatus(to.name());
    }

    private static void requireRequestFile(String fileType) {
        if (!"ZP0061".equals(fileType) && !"ZP0063".equals(fileType)) {
            throw new IllegalArgumentException(
                    "runWindow handles ZP0061/ZP0063 request files only, got: " + fileType);
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
