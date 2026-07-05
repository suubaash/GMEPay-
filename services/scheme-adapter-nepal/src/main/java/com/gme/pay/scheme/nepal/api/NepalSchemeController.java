package com.gme.pay.scheme.nepal.api;

import com.gme.pay.scheme.nepal.adapter.NepalSchemeAdapter;
import com.gme.pay.scheme.nepal.dto.DecodeRequest;
import com.gme.pay.scheme.nepal.dto.DecodeResponse;
import com.gme.pay.scheme.nepal.dto.StatusResponse;
import com.gme.pay.scheme.nepal.dto.SubmitRequest;
import com.gme.pay.scheme.nepal.dto.SubmitResponse;
import com.gme.pay.scheme.nepal.persistence.GmeSchemeBalanceEntity;
import com.gme.pay.scheme.nepal.prefund.GmeSchemeFloatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Internal REST API exposed by the Nepal QR scheme adapter service.
 *
 * <p>Endpoints are internal-only (consumed by payment-executor, not the public gateway),
 * mirroring {@code /internal/scheme/zeropay/...}.</p>
 *
 * <p><b>One-shot vs two-phase:</b> ZeroPay exposes authorize then commit; Nepal's partner
 * {@code pay} is synchronous and single-shot, so {@code /submit} here is authorize+commit
 * combined — there is deliberately no separate commit endpoint.</p>
 */
@RestController
@RequestMapping("/internal/scheme/nepal")
public class NepalSchemeController {

    private static final String APPROVED = "APPROVED";

    private static final Logger log = LoggerFactory.getLogger(NepalSchemeController.class);

    private final NepalSchemeAdapter adapter;
    private final GmeSchemeFloatService floatService;

    public NepalSchemeController(NepalSchemeAdapter adapter, GmeSchemeFloatService floatService) {
        this.adapter = adapter;
        this.floatService = floatService;
    }

    /** POST /internal/scheme/nepal/decode — resolve a scanned QR to merchant/receiver fields. */
    @PostMapping("/decode")
    public ResponseEntity<DecodeResponse> decode(@RequestBody DecodeRequest req) {
        return ResponseEntity.ok(adapter.decode(req.qs()));
    }

    /** POST /internal/scheme/nepal/submit — authorize+commit a payment via the partner /pay/. */
    @PostMapping("/submit")
    public ResponseEntity<SubmitResponse> submit(@RequestBody SubmitRequest req) {
        SubmitResponse response = adapter.submit(req);
        // Committed payout → debit GME's prepaid float (paisa → NPR). Idempotent on the reference,
        // so a retried submit never double-debits. A ledger hiccup must not fail the response — the
        // payout has already occurred at the scheme — so it is logged, not thrown.
        if (APPROVED.equals(response.status())) {
            try {
                floatService.debitPaisa(req.reference(), response.amountPaisa());
            } catch (RuntimeException ex) {
                log.error("failed to debit Nepal prepaid float for committed payout ref={} paisa={}: {}",
                        req.reference(), response.amountPaisa(), ex.getMessage(), ex);
            }
        }
        return ResponseEntity.ok(response);
    }

    /** GET /internal/scheme/nepal/status?reference= — look up the state via the partner /status/. */
    @GetMapping("/status")
    public ResponseEntity<StatusResponse> status(@RequestParam("reference") String reference) {
        return ResponseEntity.ok(adapter.status(reference));
    }

    /**
     * Pre-submit balance inquiry (SETTLEMENT_FLOW_SPEC §7.2): does GME hold enough prepaid float WITH
     * the Nepal scheme to fund {@code amount} (NPR)? POST /internal/scheme/nepal/balance-check.
     * Reads GME's REAL running float so a drained balance declines the payout at AUTHORIZE.
     */
    @PostMapping("/balance-check")
    public ResponseEntity<BalanceCheckResponse> balanceCheck(@RequestBody BalanceCheckRequest req) {
        GmeSchemeFloatService.BalanceCheck result = floatService.check(req.amount());
        return ResponseEntity.ok(new BalanceCheckResponse(result.allowed(), result.available()));
    }

    /** GET /internal/scheme/nepal/balance — how much prepaid float GME currently holds with Nepal (NPR). */
    @GetMapping("/balance")
    public ResponseEntity<BalanceResponse> balance() {
        GmeSchemeBalanceEntity bal = floatService.currentBalance();
        List<BalanceEntry> entries = floatService.recentEntries().stream()
                .map(e -> new BalanceEntry(e.getEntryType(), e.getTxnRef(), e.getAmount(),
                        e.getBalanceAfter(), e.getCreatedAt()))
                .toList();
        return ResponseEntity.ok(new BalanceResponse(
                bal.getSchemeCode(), bal.getCurrency(), bal.getBalance(), bal.getUpdatedAt(), entries));
    }

    /**
     * POST /internal/scheme/nepal/balance/topup — credit GME's prepaid float (a deposit to the scheme,
     * NPR). Idempotent on {@code reference}. Returns the new running balance.
     */
    @PostMapping("/balance/topup")
    public ResponseEntity<TopUpResponse> topUp(@RequestBody TopUpRequest req) {
        BigDecimal newBalance = floatService.credit(req.reference(), req.amountNpr());
        return ResponseEntity.ok(new TopUpResponse(floatService.schemeCode(), newBalance, floatService.currency()));
    }

    public record BalanceCheckRequest(String schemeId, BigDecimal amount, String currency) {}

    public record BalanceCheckResponse(boolean allowed, BigDecimal available) {}

    public record BalanceResponse(String schemeCode, String currency, BigDecimal balance,
                                  Instant updatedAt, List<BalanceEntry> recentEntries) {}

    public record BalanceEntry(String entryType, String txnRef, BigDecimal amount,
                               BigDecimal balanceAfter, Instant at) {}

    public record TopUpRequest(String reference, BigDecimal amountNpr) {}

    public record TopUpResponse(String schemeCode, BigDecimal balance, String currency) {}
}
