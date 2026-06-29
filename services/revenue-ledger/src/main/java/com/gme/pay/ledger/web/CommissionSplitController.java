package com.gme.pay.ledger.web;

import com.gme.pay.ledger.fees.CommissionSplitRecordService;
import com.gme.pay.ledger.persistence.CommissionSplitRecordEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/revenue/commission-split} — computes + records one committed transaction's
 * TWO-SIDED commission split (Step 7 / task #102). The {@link CommissionSplitCalculator} runs HERE
 * (revenue-ledger owns it); payment-executor resolves the configurable shares (V031) from
 * config-registry at confirm and posts the inputs to this endpoint.
 *
 * <p>Idempotent by {@code txnRef}: a replay returns 200 with the existing split; a fresh capture
 * returns 201. Bad inputs (rates/shares that violate the calculator's guards) return 400. Like the
 * revenue-capture endpoint this writes only the per-transaction record — it does NOT post the
 * double-entry journal (that remains {@code LedgerPostingService.postFeeShareSplit}).
 */
@RestController
@RequestMapping("/v1/revenue")
public class CommissionSplitController {

    private static final Logger log = LoggerFactory.getLogger(CommissionSplitController.class);

    private final CommissionSplitRecordService service;

    public CommissionSplitController(CommissionSplitRecordService service) {
        this.service = service;
    }

    /**
     * Body: txnRef, partnerId, schemeId, revenueDate, payoutAmountKrw, merchantFeeRate, vanFeeRate,
     * gmeSharePct, partnerSharePct. Rates/shares are decimal fractions (0.0080 = 0.80%, 0.70 = 70%).
     */
    public record CommissionSplitCaptureRequest(
            String txnRef, long partnerId, long schemeId, LocalDate revenueDate,
            long payoutAmountKrw, BigDecimal merchantFeeRate, BigDecimal vanFeeRate,
            BigDecimal gmeSharePct, BigDecimal partnerSharePct) {
    }

    @PostMapping("/commission-split")
    public ResponseEntity<?> capture(@RequestBody CommissionSplitCaptureRequest body) {
        if (body == null) {
            return badRequest("MISSING_BODY", "request body required");
        }
        if (body.txnRef() == null || body.txnRef().isBlank()) {
            return badRequest("MISSING_TXN_REF", "txnRef required");
        }
        if (body.revenueDate() == null) {
            return badRequest("MISSING_REVENUE_DATE", "revenueDate required");
        }
        if (body.merchantFeeRate() == null || body.vanFeeRate() == null
                || body.gmeSharePct() == null || body.partnerSharePct() == null) {
            return badRequest("MISSING_AMOUNT",
                    "merchantFeeRate, vanFeeRate, gmeSharePct and partnerSharePct are required");
        }

        CommissionSplitRecordService.Result result;
        try {
            result = service.recordIfAbsent(
                    body.txnRef(), body.partnerId(), body.schemeId(), body.revenueDate(),
                    body.payoutAmountKrw(), body.merchantFeeRate(), body.vanFeeRate(),
                    body.gmeSharePct(), body.partnerSharePct());
        } catch (IllegalArgumentException | ArithmeticException e) {
            return badRequest("INVALID_COMMISSION_SPLIT", e.getMessage());
        }

        CommissionSplitRecordEntity r = result.record();
        Map<String, Object> response = Map.of(
                "txnRef", r.getTxnRef(),
                "partnerId", r.getPartnerId(),
                "schemeId", r.getSchemeId(),
                "netMerchantFeeKrw", r.getNetMerchantFeeKrw(),
                "schemeShareKrw", r.getSchemeShareKrw(),
                "gmeGrossShareKrw", r.getGmeGrossShareKrw(),
                "partnerShareKrw", r.getPartnerShareKrw(),
                "gmeNetShareKrw", r.getGmeNetShareKrw());
        if (result.created()) {
            log.info("commission split recorded: txnRef={} partnerId={} gmeNet={} partner={} scheme={}",
                    r.getTxnRef(), r.getPartnerId(), r.getGmeNetShareKrw(),
                    r.getPartnerShareKrw(), r.getSchemeShareKrw());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }
        return ResponseEntity.ok(response);
    }

    private static ResponseEntity<?> badRequest(String code, String message) {
        return ResponseEntity.badRequest().body(Map.of("error_code", code, "message", message));
    }
}
