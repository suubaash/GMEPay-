package com.gme.pay.prefunding.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.prefunding.persistence.BalanceAlertRepository;
import com.gme.pay.prefunding.persistence.LedgerEntryRepository;
import com.gme.pay.prefunding.persistence.PartnerBalanceEntity;
import com.gme.pay.prefunding.persistence.PartnerBalanceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Non-docker concurrency IT for the internal deduct path on Flyway-managed H2 (PostgreSQL mode),
 * where {@code SELECT ... FOR UPDATE} serialises the per-partner row. Backs the transaction-mgmt
 * integration contract: many concurrent deducts against a partner whose balance only covers some of
 * them must (a) never drive the balance negative, and (b) admit exactly as many winners as the
 * balance allows, each rejected loser carrying INSUFFICIENT_PREFUNDING. Also asserts that a
 * concurrent burst of replays for ONE idempotency key applies the debit at most once.
 */
@SpringBootTest(properties = "gmepay.outbox.poll-ms=3600000")
@ActiveProfiles("test")
class InternalDeductConcurrencyIT {

    private static final String PARTNER = "INT_CONC";
    private static final long AWAIT_SECONDS = 60;

    @Autowired private PrefundingService service;
    @Autowired private PartnerBalanceRepository balances;
    @Autowired private LedgerEntryRepository ledger;
    @Autowired private BalanceAlertRepository alerts;

    @BeforeEach
    void seed() {
        alerts.deleteAll();
        ledger.deleteAll();
        balances.deleteAll();
    }

    @Test
    void concurrentDeductsNeverGoNegativeAndAdmitExactlyTheAffordableCount() throws Exception {
        // Balance 500, credit limit 0 (strict prepaid). Ten threads each try to deduct 100 ⇒
        // at most five can win; the balance must end at exactly 0, never negative.
        balances.save(new PartnerBalanceEntity(PARTNER, "USD",
                new BigDecimal("500.00000000"), new BigDecimal("50.00000000"), Instant.now()));

        int threads = 10;
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger rejects = new AtomicInteger();
        try {
            Future<?>[] futures = new Future<?>[threads];
            for (int i = 0; i < threads; i++) {
                final String key = "C-" + i;
                futures[i] = pool.submit(() -> {
                    try {
                        gate.await(AWAIT_SECONDS, TimeUnit.SECONDS);
                        service.deductIdempotent(PARTNER, key, new BigDecimal("100.00000000"));
                        wins.incrementAndGet();
                    } catch (ApiException e) {
                        assertEquals(ErrorCode.INSUFFICIENT_PREFUNDING, e.errorCode());
                        rejects.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            gate.countDown();
            for (Future<?> f : futures) {
                f.get(AWAIT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(5, wins.get(), "exactly five 100-USD deducts fit within 500");
        assertEquals(5, rejects.get(), "the other five must be rejected");
        BigDecimal finalBalance = service.getBalance(PARTNER);
        assertEquals(0, finalBalance.compareTo(BigDecimal.ZERO), "balance must end at exactly 0");
        assertTrue(finalBalance.signum() >= 0, "balance must never be negative");
        assertEquals(5L, ledger.countByPartnerId(PARTNER), "one DEBIT per winner, none for losers");
    }

    @Test
    void concurrentReplaysOfOneKeyDebitAtMostOnce() throws Exception {
        balances.save(new PartnerBalanceEntity(PARTNER, "USD",
                new BigDecimal("1000.00000000"), new BigDecimal("50.00000000"), Instant.now()));

        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Future<?>[] futures = new Future<?>[threads];
            for (int i = 0; i < threads; i++) {
                futures[i] = pool.submit(() -> {
                    try {
                        gate.await(AWAIT_SECONDS, TimeUnit.SECONDS);
                        service.deductIdempotent(PARTNER, "SAME-KEY", new BigDecimal("100.00000000"));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            gate.countDown();
            for (Future<?> f : futures) {
                f.get(AWAIT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // Idempotency under contention: at most one DEBIT for the shared key; balance dropped by 100 once.
        assertEquals(1, ledger.findByPartnerIdAndTxnRef(PARTNER, "SAME-KEY").size(),
                "concurrent replays of one key must debit at most once");
        assertEquals(0, service.getBalance(PARTNER).compareTo(new BigDecimal("900.00000000")));
    }
}
