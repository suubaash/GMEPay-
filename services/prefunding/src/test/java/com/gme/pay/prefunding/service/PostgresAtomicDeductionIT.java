package com.gme.pay.prefunding.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.prefunding.persistence.LedgerEntryEntity;
import com.gme.pay.prefunding.persistence.LedgerEntryRepository;
import com.gme.pay.prefunding.persistence.PartnerBalanceEntity;
import com.gme.pay.prefunding.persistence.PartnerBalanceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Docker-backed IT for the atomic-deduction guarantee on a real PostgreSQL 16 container, where
 * SELECT ... FOR UPDATE actually blocks (H2's emulation is not the acceptance target).
 *
 * <p>The key test launches two real threads that release simultaneously via a
 * {@link CountDownLatch} and deduct from the same partner row in separate transactions. The
 * row-level write lock must serialize them: the pair of returned balances corresponds to exactly
 * one of the two possible serial orderings, the final balance reflects both deductions (no lost
 * update), and the ledger holds one DEBIT per successful deduction.
 *
 * <p>Excluded from the plain `test` task (tag "docker"); CI runs it via `integrationTest`.
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class PostgresAtomicDeductionIT {

    private static final String PARTNER = "PG_CONC";
    private static final long AWAIT_SECONDS = 60;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired
    private PrefundingService service;

    @Autowired
    private PartnerBalanceRepository balances;

    @Autowired
    private LedgerEntryRepository ledger;

    @BeforeEach
    void cleanTables() {
        ledger.deleteAll();
        balances.deleteAll();
    }

    private void seedPartner(String openingBalance) {
        balances.save(new PartnerBalanceEntity(
                PARTNER, "USD",
                new BigDecimal(openingBalance),
                new BigDecimal("100.00000000"),
                Instant.now()));
    }

    /** Result of one concurrent deduction attempt: the new balance, or the rejection. */
    private record Outcome(BigDecimal newBalance, ApiException error) {
    }

    private Outcome attemptDeduct(CountDownLatch startGate, String txnRef, String amount) {
        try {
            startGate.await(AWAIT_SECONDS, TimeUnit.SECONDS);
            return new Outcome(service.deduct(PARTNER, txnRef, new BigDecimal(amount)), null);
        } catch (ApiException e) {
            return new Outcome(null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted before deduct could run", e);
        }
    }

    @Test
    void concurrentDeductionsAreSerializedWithNoLostUpdate() throws Exception {
        seedPartner("1000.00000000");

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Outcome> deductA = () -> attemptDeduct(startGate, "txn-A", "250.00000000");
            Callable<Outcome> deductB = () -> attemptDeduct(startGate, "txn-B", "100.00000000");
            Future<Outcome> futureA = pool.submit(deductA);
            Future<Outcome> futureB = pool.submit(deductB);
            startGate.countDown();

            Outcome a = futureA.get(AWAIT_SECONDS, TimeUnit.SECONDS);
            Outcome b = futureB.get(AWAIT_SECONDS, TimeUnit.SECONDS);

            // Both fit within the balance, so both must succeed.
            assertNull(a.error(), "deduct A must succeed");
            assertNull(b.error(), "deduct B must succeed");

            // Exactly one serial ordering: either A ran first (A sees 750, B sees 650) or B ran
            // first (B sees 900, A sees 650). A pair like (750, 900) would mean both read the
            // initial balance concurrently — the classic lost update FOR UPDATE must prevent.
            boolean aThenB = a.newBalance().compareTo(new BigDecimal("750.00000000")) == 0
                    && b.newBalance().compareTo(new BigDecimal("650.00000000")) == 0;
            boolean bThenA = b.newBalance().compareTo(new BigDecimal("900.00000000")) == 0
                    && a.newBalance().compareTo(new BigDecimal("650.00000000")) == 0;
            assertTrue(aThenB ^ bThenA,
                    "expected exactly one serial ordering but got A=" + a.newBalance()
                            + ", B=" + b.newBalance());
        } finally {
            pool.shutdownNow();
        }

        // Final committed state: both deductions applied, one DEBIT ledger row each.
        assertEquals(0, service.getBalance(PARTNER).compareTo(new BigDecimal("650.00000000")));
        List<LedgerEntryEntity> rows = ledger.findByPartnerIdOrderByCreatedAtAscIdAsc(PARTNER);
        assertEquals(2, rows.size());
        for (LedgerEntryEntity row : rows) {
            assertEquals("DEBIT", row.getEntryType());
            assertNotNull(row.getId());
        }
        BigDecimal totalDebited = rows.get(0).getAmount().add(rows.get(1).getAmount());
        assertEquals(0, totalDebited.compareTo(new BigDecimal("350.00000000")));
    }

    @Test
    void concurrentDeductionsBeyondBalanceAllowExactlyOneWinner() throws Exception {
        seedPartner("100.00000000");

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Outcome a;
        Outcome b;
        try {
            Future<Outcome> futureA =
                    pool.submit(() -> attemptDeduct(startGate, "txn-W1", "60.00000000"));
            Future<Outcome> futureB =
                    pool.submit(() -> attemptDeduct(startGate, "txn-W2", "60.00000000"));
            startGate.countDown();
            a = futureA.get(AWAIT_SECONDS, TimeUnit.SECONDS);
            b = futureB.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // The balance only covers one 60.00 deduction: exactly one thread wins, the other is
        // rejected with INSUFFICIENT_PREFUNDING after the lock is released.
        Outcome winner = a.error() == null ? a : b;
        Outcome loser = a.error() == null ? b : a;
        assertNull(winner.error(), "exactly one deduction must succeed");
        assertNotNull(loser.error(), "the other deduction must be rejected");
        assertEquals(ErrorCode.INSUFFICIENT_PREFUNDING, loser.error().errorCode());
        assertEquals(0, winner.newBalance().compareTo(new BigDecimal("40.00000000")));

        // Committed state: one deduction applied, exactly one DEBIT row, nothing from the loser.
        assertEquals(0, service.getBalance(PARTNER).compareTo(new BigDecimal("40.00000000")));
        List<LedgerEntryEntity> rows = ledger.findByPartnerIdOrderByCreatedAtAscIdAsc(PARTNER);
        assertEquals(1, rows.size());
        assertEquals("DEBIT", rows.get(0).getEntryType());
        assertEquals(0, rows.get(0).getAmount().compareTo(new BigDecimal("60.00000000")));
        assertTrue(rows.get(0).getTxnRef().equals("txn-W1") || rows.get(0).getTxnRef().equals("txn-W2"));
    }

    @Test
    void insufficientBalanceRejectionRollsBackCleanly() {
        seedPartner("1000.00000000");

        ApiException ex = assertThrows(ApiException.class,
                () -> service.deduct(PARTNER, "txn-too-big", new BigDecimal("1500.00000000")));
        assertEquals(ErrorCode.INSUFFICIENT_PREFUNDING, ex.errorCode());

        // Per the domain contract the rejection writes nothing: the transaction rolls back, the
        // balance is untouched and no ledger row exists for the rejected reference.
        assertEquals(0, service.getBalance(PARTNER).compareTo(new BigDecimal("1000.00000000")));
        assertEquals(0L, ledger.countByPartnerId(PARTNER));

        // The row is still usable afterwards: a valid deduction goes through.
        BigDecimal after = service.deduct(PARTNER, "txn-after", new BigDecimal("10.00000000"));
        assertEquals(0, after.compareTo(new BigDecimal("990.00000000")));
        assertEquals(1L, ledger.countByPartnerId(PARTNER));
    }
}
