package com.gme.pay.prefunding.api.internal;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.prefunding.service.PrefundingService;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal (service-to-service) REST surface consumed by <b>transaction-mgmt</b> to drive a
 * payment's prefunding leg WITHOUT cross-DB access (MSA rule: each service owns its own DB).
 *
 * <p>Two atomic, idempotent operations, each backed by a per-partner {@code SELECT FOR UPDATE}
 * (see {@link PrefundingService}):
 * <ul>
 *   <li>{@code POST /internal/v1/prefunding/{partnerId}/deduct} — move PENDING_DEBIT → DEBITED.
 *       Idempotent by the caller's idempotency key (body {@code idempotencyKey}, or the
 *       {@code Idempotency-Key} header). A replay returns the original DEBIT ledger entry id and the
 *       unchanged balance rather than double-charging. 402 INSUFFICIENT_PREFUNDING when a fresh
 *       deduction would overdraw.</li>
 *   <li>{@code POST /internal/v1/prefunding/{partnerId}/reverse} — undo a prior deduction (refund /
 *       UNCERTAIN→FAILED). Idempotent by {@code txnRef}: a second reverse credits nothing and reports
 *       {@code reversedUsd=0}. Restores the originally-debited amount onto the balance.</li>
 * </ul>
 *
 * <p>Both return the resulting balance + the ledger entry id so transaction-mgmt can record the
 * concrete ledger reference against its own transaction row. This path is internal-network only
 * (not routed via the public gateway); network-level policy is the trust boundary.
 */
@RestController
@RequestMapping("/internal/v1/prefunding")
public class PrefundingInternalController {

    static final String CURRENCY = "USD";

    private final PrefundingService service;

    public PrefundingInternalController(PrefundingService service) {
        this.service = service;
    }

    /**
     * Atomically deduct {@code amountUsd} for a confirmed payment. The idempotency key is taken from
     * the request body, falling back to the {@code Idempotency-Key} header; at least one MUST be
     * present (it is also persisted as the ledger {@code txn_ref} so the reverse path can find it).
     */
    @PostMapping("/{partnerId}/deduct")
    public DeductResponse deduct(@PathVariable String partnerId,
                                 @RequestBody DeductRequest req,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String headerKey) {
        String key = firstNonBlank(req == null ? null : req.idempotencyKey(), headerKey);
        if (key == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "idempotencyKey (body) or Idempotency-Key (header) is required");
        }
        if (req == null || req.amountUsd() == null || req.amountUsd().signum() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "amountUsd is required and must be > 0");
        }
        PrefundingService.DeductResult r = service.deductIdempotent(partnerId, key, req.amountUsd());
        return new DeductResponse(partnerId, r.balanceAfter(), CURRENCY, r.ledgerEntryId(), r.replayed());
    }

    /**
     * Atomically reverse the prior deduction identified by {@code txnRef} (the idempotency key used at
     * deduct time). Idempotent: a replay reports {@code reversedUsd=0} and the existing reversal entry
     * id (or {@code null} if there was nothing to reverse).
     */
    @PostMapping("/{partnerId}/reverse")
    public ReverseResponse reverse(@PathVariable String partnerId,
                                   @RequestBody ReverseRequest req,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String headerKey) {
        String txnRef = firstNonBlank(req == null ? null : req.txnRef(), headerKey);
        if (txnRef == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "txnRef (body) or Idempotency-Key (header) is required");
        }
        PrefundingService.ReverseResult r = service.reverse(partnerId, txnRef);
        return new ReverseResponse(partnerId, r.reversedAmount(), r.balanceAfter(), CURRENCY,
                r.ledgerEntryId());
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }

    /** {@code amountUsd} as a decimal (Jackson binds string→BigDecimal losslessly per MONEY_CONVENTION). */
    public record DeductRequest(String idempotencyKey, BigDecimal amountUsd) { }

    public record DeductResponse(String partnerId, BigDecimal balance, String currency,
                                 Long ledgerEntryId, boolean replayed) { }

    public record ReverseRequest(String txnRef) { }

    public record ReverseResponse(String partnerId, BigDecimal reversedUsd, BigDecimal balance,
                                  String currency, Long ledgerEntryId) { }
}
