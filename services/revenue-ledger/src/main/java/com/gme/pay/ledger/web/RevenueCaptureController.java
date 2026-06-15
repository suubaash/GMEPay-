package com.gme.pay.ledger.web;

import com.gme.pay.ledger.revenue.RevenueRecord;
import com.gme.pay.ledger.revenue.RevenueRecordStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code POST /v1/revenue/capture} — records one committed transaction's revenue (FX margin +
 * service charge) in the {@link RevenueRecordStore}. This is the cross-service ingestion surface
 * consumed (sync) by {@code payment-executor} after a payment commits; it is what makes
 * {@code GET /v1/revenue} return real aggregates instead of zeros.
 *
 * <p><b>Not double-entry.</b> This only writes the per-transaction revenue audit row — it does NOT
 * call {@code LedgerPostingService} (the balanced journals). Mixing the two would double-count.
 *
 * <p>Idempotent by {@code txnRef}: a repeat of an already-captured transaction returns 200 OK with
 * the existing record (no second row); a fresh capture returns 201 Created.
 *
 * <p>Per {@code docs/INTER_SERVICE_CONTRACTS.md} revenue-ledger owns its DB; callers reach it only
 * via this endpoint.
 */
@RestController
@RequestMapping("/v1/revenue")
public class RevenueCaptureController {

    private static final Logger log = LoggerFactory.getLogger(RevenueCaptureController.class);

    private final RevenueRecordStore store;

    public RevenueCaptureController(RevenueRecordStore store) {
        this.store = Objects.requireNonNull(store, "store required");
    }

    @PostMapping("/capture")
    public ResponseEntity<?> capture(@RequestBody RevenueCaptureRequest body) {
        if (body == null) {
            return badRequest("MISSING_BODY", "request body required");
        }
        if (body.txnRef() == null || body.txnRef().isBlank()) {
            return badRequest("MISSING_TXN_REF", "txnRef required");
        }
        if (body.revenueDate() == null) {
            return badRequest("MISSING_REVENUE_DATE", "revenueDate required");
        }
        if (body.serviceChargeCcy() == null || body.serviceChargeCcy().isBlank()) {
            return badRequest("MISSING_CURRENCY", "serviceChargeCcy required");
        }
        if (body.collectionMarginUsd() == null || body.payoutMarginUsd() == null
                || body.serviceChargeAmount() == null || body.feeSharePct() == null) {
            return badRequest("MISSING_AMOUNT",
                    "collectionMarginUsd, payoutMarginUsd, serviceChargeAmount and feeSharePct are required");
        }

        // Idempotent replay: an already-captured txn returns 200 with the existing record.
        Optional<RevenueRecord> existing = store.findByTxnRef(body.txnRef());
        if (existing.isPresent()) {
            return ResponseEntity.ok(RevenueCaptureResponse.from(existing.get()));
        }

        RevenueRecord record;
        try {
            record = RevenueRecord.of(
                    body.txnRef(), body.partnerId(), body.schemeId(), body.revenueDate(),
                    body.collectionMarginUsd(), body.payoutMarginUsd(),
                    body.serviceChargeAmount(), body.serviceChargeCcy(), body.feeSharePct());
        } catch (IllegalArgumentException | NullPointerException e) {
            return badRequest("INVALID_REVENUE_RECORD", e.getMessage());
        }

        RevenueRecord saved = store.save(record);
        log.info("revenue captured: txnRef={} partnerId={} fxMarginUsd={} serviceCharge={} {}",
                saved.txnRef(), saved.partnerId(), saved.fxMarginUsd(),
                saved.serviceChargeAmount(), saved.serviceChargeCcy());
        return ResponseEntity.status(HttpStatus.CREATED).body(RevenueCaptureResponse.from(saved));
    }

    private static ResponseEntity<?> badRequest(String code, String message) {
        return ResponseEntity.badRequest().body(Map.of("error_code", code, "message", message));
    }
}
