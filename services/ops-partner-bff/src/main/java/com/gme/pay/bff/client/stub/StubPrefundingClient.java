package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.PrefundingClient;
import com.gme.pay.contracts.BalanceAlertView;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Phase-1 in-memory stub of {@link PrefundingClient}. partner_test_003 is
 * deliberately below its threshold so the Admin dashboard "low balance count"
 * non-zero path is exercised.
 *
 * <p>Slice 5: {@link #getBalanceAlerts(String)} returns a deterministic alert
 * trail for partner_test_003 (its stub balance sits at 50% of the threshold, so
 * TIER_95 / TIER_85 / TIER_70 have historically fired, newest first). The
 * canonical balance view comes from the interface's default
 * {@code getAdminBalance} derivation over {@link #getBalance(String)}.
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

    /** Deterministic alert feed (newest first) matching partner_test_003's 50% balance. */
    private static final Map<String, List<BalanceAlertView>> ALERTS = Map.of(
            "partner_test_003", List.of(
                    new BalanceAlertView(3L, "partner_test_003", "TIER_70",
                            new BigDecimal("68000.0000"), new BigDecimal("100000.0000"),
                            Instant.parse("2026-06-03T11:00:00Z"), false),
                    new BalanceAlertView(2L, "partner_test_003", "TIER_85",
                            new BigDecimal("84000.0000"), new BigDecimal("100000.0000"),
                            Instant.parse("2026-06-02T10:00:00Z"), true),
                    new BalanceAlertView(1L, "partner_test_003", "TIER_95",
                            new BigDecimal("94000.0000"), new BigDecimal("100000.0000"),
                            Instant.parse("2026-06-01T09:00:00Z"), true)));

    @Override
    public BalanceView getBalance(String partnerId) {
        return STORE.get(partnerId);
    }

    @Override
    public List<BalanceAlertView> getBalanceAlerts(String partnerCode) {
        return ALERTS.getOrDefault(partnerCode, List.of());
    }

    /** Test/observability hook for the dashboard aggregation. */
    public Map<String, BalanceView> snapshot() {
        return STORE;
    }
}
