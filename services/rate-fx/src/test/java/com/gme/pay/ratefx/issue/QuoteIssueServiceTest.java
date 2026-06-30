package com.gme.pay.ratefx.issue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gme.pay.errors.ApiException;
import com.gme.pay.ratefx.RateEngine;
import com.gme.pay.ratefx.RateInput;
import com.gme.pay.ratefx.RateResult;
import com.gme.pay.ratefx.client.PartnerConfigPort;
import com.gme.pay.ratefx.client.PartnerConfigPort.PartnerCurrencies;
import com.gme.pay.ratefx.client.PartnerConfigPort.PartnerRule;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link QuoteIssueService#buildRateInput} — the money-critical field sourcing that
 * makes the partner's stored config (not the caller) authoritative at quote-issue time (closes the
 * Step 5 + Step 6 tails). Uses a hand-rolled {@link PartnerConfigPort} fake + a mocked
 * {@link CostRateResolver}; {@code QuoteService} is unused by buildRateInput so it is null.
 */
class QuoteIssueServiceTest {

    private static PartnerConfigPort port(PartnerCurrencies currencies, List<PartnerRule> rules) {
        return new PartnerConfigPort() {
            @Override
            public PartnerCurrencies getPartnerCurrencies(String partnerCode) {
                return currencies;
            }
            @Override
            public List<PartnerRule> getRules(String partnerCode) {
                return rules;
            }
        };
    }

    @Test
    void buildRateInput_sourcesCurrenciesMarginsAndFeeFromConfig_usdSettleA() {
        // Overseas partner: collect + settle-A in USD; payout KRW. Rule: 1% + 1% + $3 fee.
        PartnerConfigPort port = port(
                new PartnerCurrencies("USD", "USD", "USD"),
                List.of(new PartnerRule("zeropay", "INBOUND",
                        new BigDecimal("0.01"), new BigDecimal("0.01"), new BigDecimal("3.00"))));
        CostRateResolver rates = mock(CostRateResolver.class);
        when(rates.resolve("USD")).thenReturn(null);          // identity leg
        when(rates.resolve("KRW")).thenReturn(new BigDecimal("1380"));

        QuoteIssueService svc = new QuoteIssueService(port, rates, null, null);
        RateInput in = svc.buildRateInput(new PartnerQuoteRequest(
                "GMEREMIT", "zeropay", "INBOUND", new BigDecimal("50000"), "KRW"));

        assertEquals(0, in.targetPayout().compareTo(new BigDecimal("50000")));
        assertEquals("USD", in.collectionCurrency());
        assertEquals("USD", in.settleACurrency());
        assertEquals("KRW", in.settleBCurrency());     // payout leg settles in the payout ccy
        assertEquals("KRW", in.payoutCurrency());
        assertNull(in.costRateColl(), "USD settle-A is an identity leg");
        assertEquals(0, in.costRatePay().compareTo(new BigDecimal("1380")));
        assertEquals(0, in.mA().compareTo(new BigDecimal("0.01")), "margin mA from the rule");
        assertEquals(0, in.mB().compareTo(new BigDecimal("0.01")), "margin mB from the rule");
        assertEquals(0, in.serviceCharge().compareTo(new BigDecimal("3.00")),
                "USD settle-A: fee passes through un-converted");

        // ...and the built input is engine-priceable (pool identity holds, no exception). Exact
        // cascade values are covered by lib-rate's own engine tests; here we assert structure.
        RateResult r = new RateEngine().quote(in);
        assertTrue(r.collectionAmount().signum() > 0, "priced collection amount is positive");
        assertTrue(r.payoutUsdCost().compareTo(new BigDecimal("36")) > 0
                        && r.payoutUsdCost().compareTo(new BigDecimal("37")) < 0,
                "payoutUsdCost ≈ 50000/1380 (between 36 and 37 USD)");
    }

    @Test
    void buildRateInput_convertsUsdFeeIntoNonUsdSettleA() {
        // Settle-A in KRW: the rule's USD fee must convert to KRW (sendAmount is KRW).
        PartnerConfigPort port = port(
                new PartnerCurrencies("KRW", "KRW", "KRW"),
                List.of(new PartnerRule("zeropay", "BOTH",
                        new BigDecimal("0.01"), new BigDecimal("0.01"), new BigDecimal("3.00"))));
        CostRateResolver rates = mock(CostRateResolver.class);
        when(rates.resolve("KRW")).thenReturn(new BigDecimal("1380"));

        QuoteIssueService svc = new QuoteIssueService(port, rates, null, null);
        RateInput in = svc.buildRateInput(new PartnerQuoteRequest(
                "PTNR", "zeropay", "INBOUND", new BigDecimal("50000"), "KRW"));

        assertEquals("KRW", in.settleACurrency());
        // $3.00 × 1380 = 4140 KRW
        assertEquals(0, in.serviceCharge().compareTo(new BigDecimal("4140")),
                "USD fee converted to settle-A (KRW) via costRateColl");
        // BOTH rule matched the INBOUND request.
        assertEquals(0, in.mA().compareTo(new BigDecimal("0.01")));
    }

    @Test
    void buildRateInput_mostSpecificRuleWins_andNoRuleThrows() {
        PartnerConfigPort port = port(
                new PartnerCurrencies("USD", "USD", "USD"),
                List.of(
                        new PartnerRule(null, null,
                                new BigDecimal("0.02"), new BigDecimal("0.02"), BigDecimal.ZERO), // wildcard
                        new PartnerRule("zeropay", "INBOUND",
                                new BigDecimal("0.01"), new BigDecimal("0.01"), BigDecimal.ZERO))); // exact
        CostRateResolver rates = mock(CostRateResolver.class);
        when(rates.resolve("USD")).thenReturn(null);
        when(rates.resolve("KRW")).thenReturn(new BigDecimal("1380"));

        QuoteIssueService svc = new QuoteIssueService(port, rates, null, null);

        // exact (zeropay, INBOUND) beats the (*, *) wildcard → mA = 0.01
        RateInput in = svc.buildRateInput(new PartnerQuoteRequest(
                "PTNR", "zeropay", "INBOUND", new BigDecimal("50000"), "KRW"));
        assertEquals(0, in.mA().compareTo(new BigDecimal("0.01")));

        // a scheme with no applicable rule is a hard error (partner not priced for that corridor).
        PartnerConfigPort empty = port(new PartnerCurrencies("USD", "USD", "USD"), List.of());
        QuoteIssueService svc2 = new QuoteIssueService(empty, rates, null, null);
        ApiException ex = assertThrows(ApiException.class, () -> svc2.buildRateInput(
                new PartnerQuoteRequest("PTNR", "zeropay", "INBOUND", new BigDecimal("50000"), "KRW")));
        assertTrue(ex.getMessage().contains("no pricing rule"));
    }
}
