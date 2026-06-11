package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.TransactionMgmtClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * Phase-1 in-memory stub of {@link TransactionMgmtClient}.
 */
@Component
public class StubTransactionMgmtClient implements TransactionMgmtClient {

    private static final List<TransactionSummary> STORE = List.of(
            new TransactionSummary("TXN-1001", "partner_test_001", "COMMITTED",
                    new BigDecimal("125.50"), "USD",
                    Instant.parse("2026-06-09T10:15:30Z")),
            new TransactionSummary("TXN-1002", "partner_test_001", "COMMITTED",
                    new BigDecimal("75.00"), "USD",
                    Instant.parse("2026-06-09T11:02:11Z")),
            new TransactionSummary("TXN-1003", "partner_test_002", "COMMITTED",
                    new BigDecimal("50000"), "KRW",
                    Instant.parse("2026-06-09T12:45:00Z")),
            new TransactionSummary("TXN-1004", "partner_test_003", "FAILED",
                    new BigDecimal("8000"), "JPY",
                    Instant.parse("2026-06-09T13:20:00Z")));

    @Override
    public TransactionSummary getTransaction(String txnId) {
        return STORE.stream()
                .filter(t -> Objects.equals(t.txnId(), txnId))
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<TransactionSummary> recent(String partnerId, int limit) {
        return STORE.stream()
                .filter(t -> partnerId == null || partnerId.equals(t.partnerId()))
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public Page<TransactionSummary> list(Filter filter) {
        List<TransactionSummary> filtered = STORE.stream()
                .filter(t -> filter.partnerId() == null || filter.partnerId().equals(t.partnerId()))
                .filter(t -> filter.state() == null || filter.state().equals(t.state()))
                .filter(t -> {
                    if (filter.fromDate() == null) {
                        return true;
                    }
                    LocalDate committed = t.committedAt().atOffset(ZoneOffset.UTC).toLocalDate();
                    return !committed.isBefore(filter.fromDate());
                })
                .filter(t -> {
                    if (filter.toDate() == null) {
                        return true;
                    }
                    LocalDate committed = t.committedAt().atOffset(ZoneOffset.UTC).toLocalDate();
                    return !committed.isAfter(filter.toDate());
                })
                // schemeId is not modeled in the stub TransactionSummary; ignore it for filtering
                // but accept it on the wire so the Admin UI form binding is exercised.
                .toList();

        int page = Math.max(0, filter.page());
        int size = filter.size() <= 0 ? 20 : filter.size();
        int fromIndex = Math.min(page * size, filtered.size());
        int toIndex = Math.min(fromIndex + size, filtered.size());
        List<TransactionSummary> slice = filtered.subList(fromIndex, toIndex);
        return new Page<>(slice, page, size, filtered.size());
    }
}
