package com.gme.pay.prefunding.api;

import com.gme.pay.prefunding.service.PrefundingService;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public record DeductRequest(String txnRef, BigDecimal amount) { }

    public record CreditRequest(BigDecimal amount) { }

    public record ReverseRequest(String txnRef) { }

    public record BalanceResponse(String partnerId, BigDecimal balance) { }

    public record ReverseResponse(String partnerId, BigDecimal reversedUsd, BigDecimal balance) { }
}
