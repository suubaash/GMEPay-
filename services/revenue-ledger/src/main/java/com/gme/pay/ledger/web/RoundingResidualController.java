package com.gme.pay.ledger.web;

import com.gme.pay.ledger.domain.ledger.LedgerPostingService;
import com.gme.pay.ledger.domain.model.Journal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/**
 * REST endpoint for posting per-partner settlement rounding residuals to the rounding ledger.
 *
 * <p>This is the cross-service surface consumed (sync) by {@code payment-executor} after it
 * books the partner-facing settlement liability under the partner's rounding mode. The residual
 * ({@code precise - booked}) lands here so the difference is captured as a balanced
 * {@code REVENUE_ROUNDING} gain/loss journal rather than being silently absorbed.
 *
 * <p>Per {@code docs/INTER_SERVICE_CONTRACTS.md} revenue-ledger owns its DB; callers reach it
 * only via this endpoint.
 *
 * <h2>Contract</h2>
 * <pre>
 *   POST /v1/journals/rounding-residual
 *   { "reference": "TXN-00001", "residual": "0.007", "currency": "USD" }
 *
 *   200 OK with the posted Journal when residual != 0
 *   204 No Content when residual == 0 (nothing posted)
 *   400 Bad Request when reference or currency is missing
 * </pre>
 */
@RestController
@RequestMapping("/v1/journals")
public class RoundingResidualController {

    private final LedgerPostingService ledgerPostingService;

    public RoundingResidualController(LedgerPostingService ledgerPostingService) {
        this.ledgerPostingService = Objects.requireNonNull(ledgerPostingService, "ledgerPostingService required");
    }

    /**
     * Post a rounding residual to the {@code REVENUE_ROUNDING} account.
     *
     * @param body the residual request — see {@link RoundingResidualRequest}
     * @return 200 OK with the journal when posted; 204 No Content when residual is zero;
     *         400 Bad Request when reference/currency/residual are missing
     */
    @PostMapping("/rounding-residual")
    public ResponseEntity<?> post(@RequestBody RoundingResidualRequest body) {
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error_code", "MISSING_BODY",
                    "message", "request body required"));
        }
        if (body.reference() == null || body.reference().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error_code", "MISSING_REFERENCE",
                    "message", "reference required"));
        }
        if (body.currency() == null || body.currency().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error_code", "MISSING_CURRENCY",
                    "message", "currency required"));
        }
        if (body.residual() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error_code", "MISSING_RESIDUAL",
                    "message", "residual required"));
        }

        Journal journal = ledgerPostingService.postRoundingResidual(
                body.reference(), body.residual(), body.currency());

        if (journal == null) {
            // Zero residual — nothing to post per MONEY_CONVENTION.md.
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(journal);
    }
}
