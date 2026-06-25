package com.gme.pay.prefunding.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.prefunding.persistence.LedgerEntryEntity;
import com.gme.pay.prefunding.persistence.LedgerEntryRepository;
import com.gme.pay.prefunding.persistence.PartnerBalanceEntity;
import com.gme.pay.prefunding.persistence.PartnerBalanceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-to-end test of {@link PrefundingService} against a Flyway-managed H2 (PostgreSQL mode)
 * database. Verifies that {@code deduct} reduces the balance and writes a ledger entry, and that
 * an over-deduction throws INSUFFICIENT_PREFUNDING without mutating either table.
 */
@SpringBootTest
@ActiveProfiles("test")
class PrefundingServiceIT {

    private static final String PARTNER = "PIT_PARTNER";

    @Autowired
    private PrefundingService service;

    @Autowired
    private PartnerBalanceRepository balances;

    @Autowired
    private LedgerEntryRepository ledger;

    @BeforeEach
    void seedPartner() {
        // Each test seeds its own partner row; @ActiveProfiles("test") suppresses the demo runner.
        ledger.deleteAll();
        balances.deleteAll();
        balances.save(new PartnerBalanceEntity(
                PARTNER, "USD",
                new BigDecimal("1000.00000000"),
                new BigDecimal("100.00000000"),
                Instant.now()));
    }

    @Test
    @Transactional
    void deductReducesBalanceAndWritesLedgerEntry() {
        BigDecimal newBalance = service.deduct(PARTNER, "txn-A", new BigDecimal("250.00000000"));

        assertEquals(0, newBalance.compareTo(new BigDecimal("750.00000000")));
        assertEquals(0, service.getBalance(PARTNER).compareTo(new BigDecimal("750.00000000")));

        List<LedgerEntryEntity> rows = ledger.findByPartnerIdOrderByCreatedAtAscIdAsc(PARTNER);
        assertEquals(1, rows.size());
        LedgerEntryEntity row = rows.get(0);
        assertEquals("DEBIT", row.getEntryType());
        assertEquals("txn-A", row.getTxnRef());
        assertEquals(0, row.getAmount().compareTo(new BigDecimal("250.00000000")));
        assertEquals("USD", row.getCurrency());
    }

    @Test
    void deductBeyondBalanceThrowsAndLeavesBalanceUnchanged() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.deduct(PARTNER, "txn-B", new BigDecimal("1500.00000000")));
        assertEquals(ErrorCode.INSUFFICIENT_PREFUNDING, ex.errorCode());

        // Balance must be unchanged and no ledger row written.
        assertEquals(0, service.getBalance(PARTNER).compareTo(new BigDecimal("1000.00000000")));
        assertEquals(0L, ledger.countByPartnerId(PARTNER));
    }

    @Test
    @Transactional
    void reverseRestoresBalanceAndReportsActualReversedAmount() {
        service.deduct(PARTNER, "txn-R", new BigDecimal("250.00000000"));
        assertEquals(0, service.getBalance(PARTNER).compareTo(new BigDecimal("750.00000000")));

        // reverse returns the ACTUAL reversed USD (not a placeholder zero) and restores the balance.
        PrefundingService.ReverseResult r = service.reverse(PARTNER, "txn-R");
        assertEquals(0, r.reversedAmount().compareTo(new BigDecimal("250.00000000")),
                "reverse must report the originally-deducted amount");
        assertEquals(0, service.getBalance(PARTNER).compareTo(new BigDecimal("1000.00000000")),
                "balance must be fully restored");

        // A CREDIT entry tagged with the txnRef is the reversal marker.
        List<LedgerEntryEntity> rows = ledger.findByPartnerIdAndTxnRef(PARTNER, "txn-R");
        assertEquals(2, rows.size(), "one DEBIT + one CREDIT(reversal) for txn-R");

        // Idempotent: a second reverse is a no-op (reports zero, balance unchanged).
        PrefundingService.ReverseResult again = service.reverse(PARTNER, "txn-R");
        assertEquals(0, again.reversedAmount().compareTo(BigDecimal.ZERO),
                "second reverse must be a no-op");
        assertEquals(0, service.getBalance(PARTNER).compareTo(new BigDecimal("1000.00000000")));
    }

    @Test
    @Transactional
    void creditLimitExtendsAvailableForReservations() {
        // balance 1000 + credit limit 500 => available 1500.
        service.setCreditLimit(PARTNER, new BigDecimal("500.00000000"));

        // Reserve 1400 (> balance, < balance + credit) succeeds; available drops to 100.
        PrefundingService.ReserveResult r =
                service.reserve(PARTNER, "txn-C1", new BigDecimal("1400.00000000"));
        assertEquals(0, r.reservedAmount().compareTo(new BigDecimal("1400.00000000")));
        assertEquals(0, r.available().compareTo(new BigDecimal("100.00000000")));

        // A further reserve beyond the remaining 100 is declined.
        ApiException ex = assertThrows(ApiException.class,
                () -> service.reserve(PARTNER, "txn-C2", new BigDecimal("200.00000000")));
        assertEquals(ErrorCode.INSUFFICIENT_PREFUNDING, ex.errorCode());

        // Capture converts the hold to a real debit, drawing into the credit line (balance < 0).
        PrefundingService.CaptureResult cap = service.capture(PARTNER, "txn-C1");
        assertEquals(0, cap.capturedAmount().compareTo(new BigDecimal("1400.00000000")));
        assertEquals(0, service.getBalance(PARTNER).compareTo(new BigDecimal("-400.00000000")),
                "capture beyond balance draws into the credit line");
    }

    @Test
    @Transactional
    void reserveThenReleaseRestoresAvailableWithoutDebit() {
        PrefundingService.ReserveResult r =
                service.reserve(PARTNER, "txn-D", new BigDecimal("300.00000000"));
        assertEquals(0, r.available().compareTo(new BigDecimal("700.00000000")));

        // Release the hold: balance unchanged, the held amount frees up again.
        PrefundingService.ReleaseResult rel = service.release(PARTNER, "txn-D");
        assertEquals(0, rel.releasedAmount().compareTo(new BigDecimal("300.00000000")));
        assertEquals(0, service.getBalance(PARTNER).compareTo(new BigDecimal("1000.00000000")));

        // A fresh reserve for the full balance now succeeds (the prior hold was released).
        PrefundingService.ReserveResult r2 =
                service.reserve(PARTNER, "txn-D2", new BigDecimal("1000.00000000"));
        assertEquals(0, r2.available().compareTo(BigDecimal.ZERO));
    }

    @Test
    @Transactional
    void reverseUnknownTxnRefIsZeroNoOp() {
        PrefundingService.ReverseResult r = service.reverse(PARTNER, "never-deducted");
        assertEquals(0, r.reversedAmount().compareTo(BigDecimal.ZERO));
        assertEquals(0, service.getBalance(PARTNER).compareTo(new BigDecimal("1000.00000000")));
    }
}
