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
}
