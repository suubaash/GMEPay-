package com.gme.pay.prefunding.api;

import com.gme.pay.contracts.BalanceDeductionEntry;
import com.gme.pay.contracts.PrefundingDeductionHistoryView;
import com.gme.pay.prefunding.service.PrefundingService;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public REST surface for the prefunding service's balance mutations.
 * Per INTER_SERVICE_CONTRACTS.md, this service exposes deduct/credit at /v1/prefunding/*.
 *
 * <p>Slice 5 moved {@code GET /{partnerCode}/balance} to
 * {@link BalanceProvisioningController}, which returns the canonical
 * {@link com.gme.pay.contracts.BalanceView} (currency / balance / threshold /
 * pctOfThreshold; money as decimal strings per docs/MONEY_CONVENTION.md). The
 * deduct/credit response shape here is unchanged — payment-executor binds
 * {@code {partnerId, balance}}.
 */
@RestController
@RequestMapping("/v1/prefunding")
public class PrefundingController {

    private final PrefundingService service;

    public PrefundingController(PrefundingService service) {
        this.service = service;
    }

    @PostMapping("/{partnerId}/deduct")
    public BalanceResponse deduct(@PathVariable String partnerId, @RequestBody DeductRequest req) {
        BigDecimal newBalance = service.deduct(partnerId, req.txnRef(), req.amount());
        return new BalanceResponse(partnerId, newBalance);
    }

    @PostMapping("/{partnerId}/credit")
    public BalanceResponse credit(@PathVariable String partnerId, @RequestBody CreditRequest req) {
        BigDecimal newBalance = service.credit(partnerId, req.amount());
        return new BalanceResponse(partnerId, newBalance);
    }

    /**
     * Reverse a prior deduction for {@code txnRef} (same-day cancel / refund). Returns the amount
     * actually credited back ({@code reversedUsd}) so the caller can record the real reversal rather
     * than a placeholder zero. Idempotent: a second reverse for the same txnRef reports
     * {@code reversedUsd=0}.
     */
    @PostMapping("/{partnerId}/reverse")
    public ReverseResponse reverse(@PathVariable String partnerId, @RequestBody ReverseRequest req) {
        PrefundingService.ReverseResult r = service.reverse(partnerId, req.txnRef());
        return new ReverseResponse(partnerId, r.reversedAmount(), r.balanceAfter());
    }

    /**
     * Reserve (hold) {@code amount} against the partner's available funds — authorize phase of the
     * two-phase flow (SETTLEMENT_FLOW_SPEC §7.1). No balance is moved; available = balance +
     * credit_limit - reserved. Idempotent by txnRef. 402 INSUFFICIENT_PREFUNDING if available < amount.
     */
    @PostMapping("/{partnerId}/reserve")
    public ReserveResponse reserve(@PathVariable String partnerId, @RequestBody ReserveRequest req) {
        PrefundingService.ReserveResult r = service.reserve(partnerId, req.txnRef(), req.amount());
        return new ReserveResponse(partnerId, r.reservedAmount(), r.available(), r.balance());
    }

    /**
     * Capture the active hold for {@code txnRef} — confirm phase: converts the hold to a real debit.
     * Idempotent: no active hold ⇒ capturedUsd=0.
     */
    @PostMapping("/{partnerId}/capture")
    public CaptureResponse capture(@PathVariable String partnerId, @RequestBody ReserveRequest req) {
        PrefundingService.CaptureResult r = service.capture(partnerId, req.txnRef());
        return new CaptureResponse(partnerId, r.capturedAmount(), r.balanceAfter());
    }

    /**
     * Release the active hold for {@code txnRef} without debiting — expiry / decline path.
     * Idempotent: no active hold ⇒ releasedUsd=0.
     */
    @PostMapping("/{partnerId}/release")
    public ReleaseResponse release(@PathVariable String partnerId, @RequestBody ReserveRequest req) {
        PrefundingService.ReleaseResult r = service.release(partnerId, req.txnRef());
        return new ReleaseResponse(partnerId, r.releasedAmount(), r.balance());
    }

    /**
     * Set the partner's credit headroom — the configured {@code credit_limit_usd} pushed from
     * config-registry. The authorize gate then allows holds up to balance + credit_limit. Idempotent.
     */
    @PutMapping("/{partnerId}/credit-limit")
    public CreditLimitResponse setCreditLimit(@PathVariable String partnerId,
                                              @RequestBody CreditLimitRequest req) {
        PrefundingService.CreditLimitResult r = service.setCreditLimit(partnerId, req.creditLimit());
        return new CreditLimitResponse(partnerId, r.creditLimit(), r.available(), r.balance());
    }

    /**
     * AML cumulative cap (authorize phase): charge {@code amountUsd} toward the partner's daily/monthly/
     * annual usage, rejecting with 422 CUMULATIVE_LIMIT_EXCEEDED if any non-null cap would be breached.
     * Race-free (per-partner row lock). Idempotent by txnRef.
     */
    @PostMapping("/{partnerId}/cumulative-charge")
    public CumulativeChargeResponse cumulativeCharge(@PathVariable String partnerId,
                                                     @RequestBody CumulativeChargeRequest req) {
        PrefundingService.CumulativeChargeResult r = service.chargeCumulative(
                partnerId, req.txnRef(), req.amountUsd(), req.dailyCapUsd(), req.monthlyCapUsd(),
                req.annualCapUsd(), req.dailyTxnCountLimit());
        return new CumulativeChargeResponse(partnerId, r.dailyUsage(), r.monthlyUsage(), r.annualUsage());
    }

    /**
     * Reverse a cumulative charge for {@code txnRef} (void / decline / expiry) so a held-but-not-confirmed
     * authorize does not permanently consume cap. Idempotent: nothing charged / already reversed ⇒ 0.
     */
    @PostMapping("/{partnerId}/cumulative-reverse")
    public CumulativeReverseResponse cumulativeReverse(@PathVariable String partnerId,
                                                       @RequestBody ReverseRequest req) {
        PrefundingService.CumulativeReverseResult r = service.reverseCumulative(partnerId, req.txnRef());
        return new CumulativeReverseResponse(partnerId, r.reversedAmount());
    }

    /**
     * Recent prefunding deductions for {@code code}, most-recent-first, capped at {@code limit}
     * (default 20, clamped 1..500). Unblocks payment-executor's balance {@code ?include_history=true}
     * (IR-pe-2). Returns the canonical {@link PrefundingDeductionHistoryView} wrapping
     * {@link BalanceDeductionEntry} rows ({@code amountUsd}, {@code at}, {@code txnRef}); money as
     * decimal strings per docs/MONEY_CONVENTION.md. An unknown/empty partner yields an empty list.
     */
    @GetMapping("/{code}/deductions")
    public PrefundingDeductionHistoryView deductions(
            @PathVariable String code,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        int capped = limit <= 0 ? 20 : Math.min(limit, 500);
        List<BalanceDeductionEntry> entries = service.recentDeductions(code, capped).stream()
                .map(r -> new BalanceDeductionEntry(r.amountUsd(), r.at(), r.txnRef()))
                .toList();
        return new PrefundingDeductionHistoryView(code, entries, capped);
    }

    public record DeductRequest(String txnRef, BigDecimal amount) { }

    public record CreditLimitRequest(BigDecimal creditLimit) { }

    public record CreditLimitResponse(String partnerId, BigDecimal creditLimit, BigDecimal available,
                                      BigDecimal balance) { }

    public record CreditRequest(BigDecimal amount) { }

    public record ReverseRequest(String txnRef) { }

    public record ReserveRequest(String txnRef, BigDecimal amount) { }

    public record BalanceResponse(String partnerId, BigDecimal balance) { }

    public record ReverseResponse(String partnerId, BigDecimal reversedUsd, BigDecimal balance) { }

    public record ReserveResponse(String partnerId, BigDecimal reservedUsd, BigDecimal available,
                                  BigDecimal balance) { }

    public record CaptureResponse(String partnerId, BigDecimal capturedUsd, BigDecimal balance) { }

    public record ReleaseResponse(String partnerId, BigDecimal releasedUsd, BigDecimal balance) { }

    public record CumulativeChargeRequest(String txnRef, BigDecimal amountUsd,
                                          BigDecimal dailyCapUsd, BigDecimal monthlyCapUsd,
                                          BigDecimal annualCapUsd, Integer dailyTxnCountLimit) { }

    public record CumulativeChargeResponse(String partnerId, BigDecimal dailyUsageUsd,
                                           BigDecimal monthlyUsageUsd, BigDecimal annualUsageUsd) { }

    public record CumulativeReverseResponse(String partnerId, BigDecimal reversedUsd) { }
}
