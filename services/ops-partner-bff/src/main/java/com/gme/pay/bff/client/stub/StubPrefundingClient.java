package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.PrefundingClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Phase-1 in-memory stub of {@link PrefundingClient}. partner_test_003 is
 * deliberately below its threshold so the Admin dashboard "low balance count"
 * non-zero path is exercised.
 */
@Component
public class StubPrefundingClient implements PrefundingClient {

    private static final Map<String, BalanceView> STORE = Map.of(
            "partner_test_001", new BalanceView(
                    "partner_test_001", "USD",
                    new BigDecimal("10000.00"), new BigDecimal("1000.00")),
            "partner_test_002", new BalanceView(
                    "partner_test_002", "KRW",
                    new BigDecimal("5000000"), new BigDecimal("1000000")),
            "partner_test_003", new BalanceView(
                    "partner_test_003", "JPY",
                    new BigDecimal("50000"), new BigDecimal("100000")));

    @Override
    public BalanceView getBalance(String partnerId) {
        return STORE.get(partnerId);
    }

    /** Test/observability hook for the dashboard aggregation. */
    public Map<String, BalanceView> snapshot() {
        return STORE;
    }
}
