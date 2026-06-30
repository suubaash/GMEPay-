package com.gme.pay.reporting.infrastructure;

import com.gme.pay.reporting.domain.CommittedTransaction;
import com.gme.pay.reporting.domain.TransactionDirection;
import com.gme.pay.reporting.service.CommittedFxTransactionPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process fixture implementation of {@link CommittedFxTransactionPort}.
 *
 * <p>Active by default whenever no real adapter (an HTTP client against the
 * future transaction-mgmt committed-FX endpoint — INTEGRATION REQUEST #1) is
 * present. It carries the rate-locked {@code offerRateColl} (BOK FX1015 field #14)
 * and {@code crossRate} that the canonical {@code GET /v1/transactions} endpoint
 * cannot supply, so the FX1015 #14 path is exercised end-to-end and stays green.
 *
 * <p>The fixture starts empty (so production boots produce no synthetic filings)
 * but exposes {@link #seed} / {@link #clear} so tests and demos can stage
 * deterministic committed-FX transactions.
 */
@Component
@ConditionalOnMissingBean(value = CommittedFxTransactionPort.class,
        ignored = FixtureCommittedFxTransactionPort.class)
public class FixtureCommittedFxTransactionPort implements CommittedFxTransactionPort {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final List<CommittedTransaction> store = new CopyOnWriteArrayList<>();

    @Override
    public List<CommittedTransaction> fetchCommittedFx(LocalDate from, LocalDate to, Long partnerId) {
        List<CommittedTransaction> result = new ArrayList<>();
        for (CommittedTransaction txn : store) {
            LocalDate kstDate = LocalDate.ofInstant(txn.getCommittedAt(), KST);
            if (kstDate.isBefore(from) || kstDate.isAfter(to)) {
                continue;
            }
            if (partnerId != null && txn.getPartnerId() != partnerId) {
                continue;
            }
            result.add(txn);
        }
        return result;
    }

    /** Stages a committed-FX transaction for the fixture (test/demo use). */
    public void seed(CommittedTransaction txn) {
        store.add(txn);
    }

    /** Removes all staged transactions. */
    public void clear() {
        store.clear();
    }

    /**
     * Convenience builder for a fully-populated INBOUND committed-FX transaction with a
     * locked {@code offerRateColl} (FX1015 #14). For tests/demos.
     */
    public static CommittedTransaction inbound(
            long txnId, String txnRef, long partnerId,
            BigDecimal collectionAmount, String collectionCcy,
            BigDecimal payoutAmount, String payoutCcy,
            BigDecimal offerRateColl, BigDecimal crossRate,
            BigDecimal usdAmount, Instant committedAt) {
        return new CommittedTransaction(
                txnId, txnRef, TransactionDirection.INBOUND, false,
                offerRateColl, crossRate,
                collectionAmount, collectionCcy, payoutAmount, payoutCcy,
                usdAmount, committedAt, partnerId);
    }
}
