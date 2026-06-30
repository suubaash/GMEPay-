package com.gme.pay.ledger.revenue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Idempotent write path for one committed transaction's revenue (FX margin + service charge).
 *
 * <p>Single source of truth shared by the two ingestion surfaces:
 * <ul>
 *   <li>the sync {@code POST /v1/revenue/capture} endpoint
 *       ({@link com.gme.pay.ledger.web.RevenueCaptureController}), and</li>
 *   <li>the async {@code gmepay.payment.approved} Kafka consumer
 *       ({@link com.gme.pay.ledger.consumer.PaymentApprovedKafkaConsumer}).</li>
 * </ul>
 * Both must produce exactly one row per {@code txnRef}; centralising the logic here keeps that
 * invariant in one place rather than duplicated across the controller and the consumer.
 *
 * <p><b>Not double-entry.</b> This writes the per-transaction revenue audit row only; it does NOT
 * post balanced journals via {@code LedgerPostingService} (mixing the two would double-count).
 *
 * <p>Idempotent by {@code txnRef}: re-capturing an already-stored transaction returns the existing
 * record with {@link Result#created()} {@code = false} and performs no second write. This makes the
 * consumer safe under Kafka at-least-once redelivery and the endpoint safe under client replays.
 */
@Service
public class RevenueCaptureService {

    private static final Logger log = LoggerFactory.getLogger(RevenueCaptureService.class);

    private final RevenueRecordStore store;

    public RevenueCaptureService(RevenueRecordStore store) {
        this.store = Objects.requireNonNull(store, "store required");
    }

    /**
     * Capture one transaction's revenue, idempotently by {@code txnRef}.
     *
     * @return {@link Result} carrying the stored record and whether this call created it
     * @throws IllegalArgumentException if the record fails validation (e.g. negative margin)
     */
    public Result capture(String txnRef,
                          long partnerId,
                          long schemeId,
                          LocalDate revenueDate,
                          BigDecimal collectionMarginUsd,
                          BigDecimal payoutMarginUsd,
                          BigDecimal serviceChargeAmount,
                          String serviceChargeCcy,
                          BigDecimal feeSharePct) {

        Optional<RevenueRecord> existing = store.findByTxnRef(txnRef);
        if (existing.isPresent()) {
            log.debug("revenue already captured, skipping: txnRef={}", txnRef);
            return new Result(existing.get(), false);
        }

        RevenueRecord record = RevenueRecord.of(
                txnRef, partnerId, schemeId, revenueDate,
                collectionMarginUsd, payoutMarginUsd,
                serviceChargeAmount, serviceChargeCcy, feeSharePct);

        // Store.save is itself idempotent (returns the pre-existing row on a race); a concurrent
        // capture of the same txnRef therefore still yields a single row. We report created=true
        // here for this caller's view — the duplicate-skip above already handles the common replay.
        RevenueRecord saved = store.save(record);
        log.info("revenue captured: txnRef={} partnerId={} fxMarginUsd={} serviceCharge={} {}",
                saved.txnRef(), saved.partnerId(), saved.fxMarginUsd(),
                saved.serviceChargeAmount(), saved.serviceChargeCcy());
        return new Result(saved, true);
    }

    /** Outcome of a capture: the stored record plus whether this call newly created it. */
    public record Result(RevenueRecord record, boolean created) {
        public Result {
            Objects.requireNonNull(record, "record required");
        }
    }
}
