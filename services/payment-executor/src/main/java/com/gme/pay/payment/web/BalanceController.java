package com.gme.pay.payment.web;

import com.gme.pay.errors.ApiError;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.payment.domain.PartnerType;
import com.gme.pay.payment.domain.client.PartnerConfigClient;
import com.gme.pay.payment.domain.client.PrefundingClient;
import com.gme.pay.payment.web.dto.PrefundingBalanceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gme.pay.contracts.PrefundingDeductionHistoryView;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * GET /v1/balance — an OVERSEAS partner's current prefunding balance inquiry (API-05 §4.8,
 * backlog 5.2-T27).
 *
 * <p>Read-only: delegates to the prefunding service ({@code GET /v1/prefunding/{code}/balance}) via
 * {@link PrefundingClient}; payment-executor owns no prefunding store (MSA — never reads another
 * service's DB). LOCAL partners receive HTTP 403 {@code FORBIDDEN} (prefunding does not apply to them).
 * Partner type is resolved from config-registry by {@code X-Partner-Code}, falling back to the
 * {@code X-Partner-Type} header when no code is supplied (fail-open, mirroring PaymentController).
 */
@RestController
public class BalanceController {

    private static final Logger log = LoggerFactory.getLogger(BalanceController.class);

    /** Default deduction-history page size when {@code ?include_history=true} without an explicit limit. */
    private static final int DEFAULT_HISTORY_LIMIT = 20;

    private final PrefundingClient prefundingClient;
    private final PartnerConfigClient partnerConfigClient;

    public BalanceController(PrefundingClient prefundingClient,
                             PartnerConfigClient partnerConfigClient) {
        this.prefundingClient = prefundingClient;
        this.partnerConfigClient = partnerConfigClient;
    }

    @GetMapping("/v1/balance")
    public ResponseEntity<?> getBalance(
            @RequestHeader(value = "X-Partner-Id", defaultValue = "1") long partnerId,
            @RequestHeader(value = "X-Partner-Code", required = false) String partnerCode,
            @RequestHeader(value = "X-Partner-Type", defaultValue = "OVERSEAS") String partnerTypeHeader,
            @RequestParam(value = "include_history", defaultValue = "false") boolean includeHistory,
            @RequestParam(value = "limit", required = false) Integer limit) {

        PartnerType type = resolvePartnerType(partnerCode, partnerTypeHeader);
        if (type == PartnerType.LOCAL) {
            // Phase 2: canonical ErrorCode.FORBIDDEN (the String-literal workaround is retired).
            return ResponseEntity.status(ErrorCode.FORBIDDEN.httpStatus())
                    .body(ApiError.of(ErrorCode.FORBIDDEN,
                            "Prefunding balance is not applicable for LOCAL partners",
                            UUID.randomUUID().toString()));
        }

        String lookupKey = (partnerCode != null && !partnerCode.isBlank())
                ? partnerCode : Long.toString(partnerId);
        PrefundingClient.BalanceSnapshot snap = prefundingClient.balance(lookupKey);

        BigDecimal balance = snap.balanceUsd();
        BigDecimal threshold = snap.lowBalanceThresholdUsd();
        boolean below = balance != null && threshold != null && balance.compareTo(threshold) < 0;

        // ?include_history=true → fetch the deduction history (IR-pe-2). Non-fatal: a history hiccup
        // must not fail the balance read, so it degrades to the balance-only response.
        List<com.gme.pay.contracts.BalanceDeductionEntry> history = null;
        if (includeHistory) {
            int effectiveLimit = (limit != null && limit > 0) ? limit : DEFAULT_HISTORY_LIMIT;
            try {
                PrefundingDeductionHistoryView view =
                        prefundingClient.deductionHistory(lookupKey, effectiveLimit);
                history = view != null ? view.entries() : null;
            } catch (RuntimeException ex) {
                log.warn("deduction-history fetch failed for {} — returning balance only: {}",
                        lookupKey, ex.getMessage());
            }
        }

        return ResponseEntity.ok(new PrefundingBalanceResponse(
                partnerId, balance, threshold, below, Instant.now(), history));
    }

    private PartnerType resolvePartnerType(String partnerCode, String headerFallback) {
        if (partnerCode != null && !partnerCode.isBlank()) {
            try {
                PartnerConfigClient.PartnerConfigView cfg = partnerConfigClient.loadPartner(partnerCode);
                if (cfg != null && cfg.type() != null && !cfg.type().isBlank()) {
                    return PartnerType.valueOf(cfg.type().toUpperCase());
                }
            } catch (RuntimeException e) {
                log.warn("partner-type resolution from config-registry failed for code={}; "
                        + "falling back to X-Partner-Type header: {}", partnerCode, e.getMessage());
            }
        }
        return PartnerType.valueOf(headerFallback.toUpperCase());
    }
}
