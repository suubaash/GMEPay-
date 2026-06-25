package com.gme.pay.prefunding.api;

import com.gme.pay.prefunding.service.PrefundingService;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
