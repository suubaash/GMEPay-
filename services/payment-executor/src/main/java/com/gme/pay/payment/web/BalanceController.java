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
 *
 * <h2>Tenancy (T0-2 — this was an IDOR)</h2>
 *
 * <p>This endpoint used to resolve the partner entirely from caller-supplied headers, <b>with
 * fail-open defaults</b>: {@code X-Partner-Id} defaulted to {@code 1}, {@code X-Partner-Type}
 * defaulted to {@code OVERSEAS}, and when config-registry could not resolve
 * {@code X-Partner-Code} the code silently fell back to the caller's own {@code X-Partner-Type}
 * claim. Anyone who could reach the port therefore read <em>any</em> partner's float and deduction
 * history by changing one header — and with no headers at all they got partner 1's.
 *
 * <p>Three changes close it:
 *
 * <ol>
 *   <li><b>The endpoint is authenticated.</b> {@code /v1/balance} is gated behind the
 *       service-to-service internal-auth token (see
 *       {@link com.gme.pay.payment.config.SandboxSurfaceInternalAuthConfig#BALANCE_PATTERN} for why
 *       "internal" is the correct, most restrictive caller model here). The gate runs before this
 *       controller, so by the time a request arrives the caller is a trusted GMEPay+ service.</li>
 *   <li><b>{@code X-Partner-Code} is required, with no default.</b> A trusted service must say
 *       <em>which</em> partner it is asking about; the request is never silently interpreted as
 *       "partner 1". Absent or blank → {@code 400 VALIDATION_ERROR}.</li>
 *   <li><b>Partner type comes only from config-registry</b>, the service that owns it. The
 *       {@code X-Partner-Type} header is gone: it was an unverified claim that could turn a LOCAL
 *       partner into an OVERSEAS one and so bypass the 403. If config-registry cannot answer, the
 *       request fails ({@code 500 INTERNAL_ERROR}, retryable) instead of guessing — an unavailable
 *       authority must not downgrade to the caller's assertion. An unresolvable code is a
 *       {@code 400}.</li>
 * </ol>
 *
 * <p>Note that authenticating the caller is not the same as scoping it: the internal token says
 * "a trusted service is calling", not "this partner may only see itself". Per-partner scoping for an
 * externally reachable balance inquiry belongs with claim-scoped tenancy (T0-3) if this endpoint is
 * ever published; until then, internal-only is the boundary.
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
            @RequestHeader(value = "X-Partner-Code", required = false) String partnerCode,
            @RequestParam(value = "include_history", defaultValue = "false") boolean includeHistory,
            @RequestParam(value = "limit", required = false) Integer limit) {

        // T0-2: no default partner. An unspecified request is a caller bug, not "partner 1".
        if (partnerCode == null || partnerCode.isBlank()) {
            return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.httpStatus())
                    .body(ApiError.of(ErrorCode.VALIDATION_ERROR,
                            "X-Partner-Code is required — the balance inquiry is never resolved to a "
                            + "default partner",
                            UUID.randomUUID().toString()));
        }

        // T0-2: config-registry is the ONLY authority on partner type. No X-Partner-Type fallback:
        // an unverified header could present a LOCAL partner as OVERSEAS and walk past the 403 below.
        PartnerConfigClient.PartnerConfigView cfg;
        try {
            cfg = partnerConfigClient.loadPartner(partnerCode);
        } catch (RuntimeException e) {
            log.warn("partner-type resolution from config-registry failed for code={} — refusing the "
                    + "balance read rather than trusting a caller-supplied type: {}",
                    partnerCode, e.getMessage());
            return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                    .body(ApiError.of(ErrorCode.INTERNAL_ERROR,
                            "Partner configuration is unavailable; the balance inquiry cannot be "
                            + "authorised",
                            UUID.randomUUID().toString()));
        }
        if (cfg == null || cfg.type() == null || cfg.type().isBlank()) {
            return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.httpStatus())
                    .body(ApiError.of(ErrorCode.VALIDATION_ERROR,
                            "Unknown partner code " + partnerCode,
                            UUID.randomUUID().toString()));
        }

        PartnerType type;
        try {
            type = PartnerType.valueOf(cfg.type().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                    .body(ApiError.of(ErrorCode.INTERNAL_ERROR,
                            "config-registry returned an unrecognised partner type for " + partnerCode,
                            UUID.randomUUID().toString()));
        }
        if (type == PartnerType.LOCAL) {
            // Phase 2: canonical ErrorCode.FORBIDDEN (the String-literal workaround is retired).
            return ResponseEntity.status(ErrorCode.FORBIDDEN.httpStatus())
                    .body(ApiError.of(ErrorCode.FORBIDDEN,
                            "Prefunding balance is not applicable for LOCAL partners",
                            UUID.randomUUID().toString()));
        }

        long partnerId = numericPartnerId(cfg.partnerId());
        String lookupKey = partnerCode;
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

    /**
     * The numeric partner id echoed in the response, taken from config-registry's view (never from a
     * request header). {@code 0} when the registry's id is not numeric — a cosmetic response field,
     * not an authorisation input: the prefunding lookup is keyed on the partner CODE.
     */
    private static long numericPartnerId(String registryPartnerId) {
        if (registryPartnerId == null) {
            return 0L;
        }
        try {
            return Long.parseLong(registryPartnerId.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
