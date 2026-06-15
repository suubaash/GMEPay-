package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.SettlementClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Phase-1 in-memory stub of {@link SettlementClient}.
 */
@Component
@ConditionalOnProperty(
        name = "gmepay.settlement-reconciliation.client",
        havingValue = "stub",
        matchIfMissing = true)
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

    /** Deterministic per-batch line set so the Admin drawer always has rows to render. */
    private static final Map<String, List<SettlementLine>> LINES = Map.of(
            "BATCH-20260608-001", List.of(
                    new SettlementLine("TXN-1001", new BigDecimal("125.50"), "USD", true),
                    new SettlementLine("TXN-1002", new BigDecimal("75.00"),  "USD", true),
                    new SettlementLine("TXN-1099", new BigDecimal("9.92"),   "USD", false)),
            "BATCH-20260608-002", List.of(
                    new SettlementLine("TXN-1003", new BigDecimal("50000"), "KRW", true)),
            "BATCH-20260607-001", List.of(
                    new SettlementLine("TXN-0901", new BigDecimal("210.00"), "USD", true),
                    new SettlementLine("TXN-0902", new BigDecimal("310.00"), "USD", true)));

    @Override
    public List<SettlementBatchSummary> recent(String partnerId, int limit) {
        return STORE.stream()
                .filter(b -> partnerId == null || partnerId.equals(b.partnerId()))
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public SettlementBatchDetail detail(String batchId) {
        SettlementBatchSummary batch = STORE.stream()
                .filter(b -> Objects.equals(b.batchId(), batchId))
                .findFirst()
                .orElse(null);
        if (batch == null) {
            return null;
        }
        List<SettlementLine> lines = LINES.getOrDefault(batchId, List.of());
        return new SettlementBatchDetail(batch, lines);
    }
}
