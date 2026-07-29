package com.gme.pay.ledger.revenue;

import com.gme.pay.ledger.domain.ledger.LedgerPostingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p><b>Double-entry (T2-4).</b> This writes the per-transaction revenue audit row <em>and</em>, in the
 * SAME transaction, the balanced journal for it via
 * {@link LedgerPostingService#postCapturedRevenueJournal} — so the main P&amp;L reaches the double-entry
 * journal and a trial balance is possible. Record and journal are committed together and can never
 * diverge: if the journal cannot be posted the revenue record is rolled back with it. There is no
 * double-counting risk because the journal post is idempotent on {@code txnRef} (it refuses to add a
 * second CREDIT to an income account for the same reference).
 *
 * <p>Idempotent by {@code txnRef}: re-capturing an already-stored transaction returns the existing
 * record with {@link Result#created()} {@code = false} and performs no second revenue write. This makes
 * the consumer safe under Kafka at-least-once redelivery and the endpoint safe under client replays. A
 * replay does still run the (idempotent) journal post, which is what back-fills a journal for a record
 * captured before T2-4 — it can only ever ADD a missing journal, never a duplicate one.
 */
@Service
public class RevenueCaptureService {

    private static final Logger log = LoggerFactory.getLogger(RevenueCaptureService.class);

    private final RevenueRecordStore store;
    private final LedgerPostingService ledgerPostingService;

    public RevenueCaptureService(RevenueRecordStore store, LedgerPostingService ledgerPostingService) {
        this.store = Objects.requireNonNull(store, "store required");
        this.ledgerPostingService = Objects.requireNonNull(ledgerPostingService, "ledgerPostingService required");
    }

    /**
     * Capture one transaction's revenue, idempotently by {@code txnRef}, and journal it double-entry in
     * the same transaction.
     *
     * @return {@link Result} carrying the stored record and whether this call created it
     * @throws IllegalArgumentException if the record fails validation (e.g. negative margin)
     */
    @Transactional
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
            // Still attempt the (idempotent) journal so a record written before T2-4 — or one whose
            // journal is missing for any other reason — is back-filled rather than staying invisible
            // to the trial balance. A normally-journalled record posts nothing here.
            journal(existing.get());
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
        journal(saved);
        log.info("revenue captured: txnRef={} partnerId={} fxMarginUsd={} serviceCharge={} {}",
                saved.txnRef(), saved.partnerId(), saved.fxMarginUsd(),
                saved.serviceChargeAmount(), saved.serviceChargeCcy());
        return new Result(saved, true);
    }

    /**
     * Post the balanced capture journal for {@code record}. Idempotent, so safe on replay; a genuinely
     * zero-revenue record posts nothing (and is reported as {@code zeroRevenue} by the reconciliation
     * self-check rather than as a nominal zero journal).
     */
    private void journal(RevenueRecord record) {
        ledgerPostingService.postCapturedRevenueJournal(
                        record.txnRef(),
                        record.fxMarginUsd(),
                        record.serviceChargeAmount(),
                        record.serviceChargeCcy())
                .ifPresent(j -> log.info("revenue capture journalled: txnRef={} journalId={} lines={}",
                        record.txnRef(), j.journalId(), j.entries().size()));
    }

    /** Outcome of a capture: the stored record plus whether this call newly created it. */
    public record Result(RevenueRecord record, boolean created) {
        public Result {
            Objects.requireNonNull(record, "record required");
        }
    }
}
