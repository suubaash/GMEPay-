package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.SettlementClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Phase-1 in-memory stub of {@link SettlementClient}.
 */
@Component
public class StubSettlementClient implements SettlementClient {

    private static final List<SettlementBatchSummary> STORE = List.of(
            new SettlementBatchSummary(
                    "BATCH-20260608-001", "partner_test_001",
                    LocalDate.of(2026, 6, 8), "USD",
                    new BigDecimal("9875.42"), "COMPLETED"),
            new SettlementBatchSummary(
                    "BATCH-20260608-002", "partner_test_002",
                    LocalDate.of(2026, 6, 8), "KRW",
                    new BigDecimal("12450000"), "COMPLETED"),
            new SettlementBatchSummary(
                    "BATCH-20260607-001", "partner_test_001",
                    LocalDate.of(2026, 6, 7), "USD",
                    new BigDecimal("11203.91"), "COMPLETED"));

    @Override
    public List<SettlementBatchSummary> recent(String partnerId, int limit) {
        return STORE.stream()
                .filter(b -> partnerId == null || partnerId.equals(b.partnerId()))
                .limit(Math.max(0, limit))
                .toList();
    }
}
