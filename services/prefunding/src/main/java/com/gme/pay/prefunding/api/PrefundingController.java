package com.gme.pay.prefunding.api;

import com.gme.pay.prefunding.service.PrefundingService;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public REST surface for the prefunding service.
 * Per INTER_SERVICE_CONTRACTS.md, this service exposes balance/deduct/credit at /v1/prefunding/*.
 */
@RestController
@RequestMapping("/v1/prefunding")
public class PrefundingController {

    private final PrefundingService service;

    public PrefundingController(PrefundingService service) {
        this.service = service;
    }

    @GetMapping("/{partnerId}/balance")
    public BalanceResponse getBalance(@PathVariable String partnerId) {
        return new BalanceResponse(partnerId, service.getBalance(partnerId));
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

    public record DeductRequest(String txnRef, BigDecimal amount) { }

    public record CreditRequest(BigDecimal amount) { }

    public record BalanceResponse(String partnerId, BigDecimal balance) { }
}
