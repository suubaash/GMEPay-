package com.gme.pay.scheme.nepal.prefund;

import com.gme.pay.scheme.nepal.persistence.GmeSchemeBalanceEntryRepository;
import com.gme.pay.scheme.nepal.persistence.GmeSchemeBalanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence-backed behaviour of the Nepal GME prepaid float ({@code gme_scheme_balance} V001):
 * lazy seeding, the pre-submit check reading the live NPR balance, idempotent paisa→NPR debit on
 * payout, top-up credit, and the gate declining once the float is drained. H2 in PostgreSQL mode.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GmeSchemeFloatServiceH2SliceTest {

    private static final long OPENING_NPR = 100_000L;

    @Autowired
    private GmeSchemeBalanceRepository balanceRepo;
    @Autowired
    private GmeSchemeBalanceEntryRepository entryRepo;

    private GmeSchemeFloatService service;

    @BeforeEach
    void setUp() {
        service = new GmeSchemeFloatService(balanceRepo, entryRepo, "NEPAL", "NPR", OPENING_NPR);
    }

    @Test
    @DisplayName("seeds the opening NPR balance; check reflects it")
    void seedsAndChecks() {
        assertTrue(service.check(new BigDecimal("40000")).allowed());
        assertEquals(0, service.currentBalance().getBalance().compareTo(new BigDecimal("100000.00")));
        assertFalse(service.check(new BigDecimal("100001")).allowed());
    }

    @Test
    @DisplayName("debitPaisa converts paisa→NPR, drains the float, then declines the next payout")
    void debitPaisaDrainsAndGate() {
        // 6,000,000 paisa = 60,000.00 NPR
        service.debitPaisa("PAY-1", 6_000_000L);
        assertEquals(0, service.currentBalance().getBalance().compareTo(new BigDecimal("40000.00")));

        service.debitPaisa("PAY-2", 4_000_000L);            // 40,000 NPR → zero
        assertEquals(0, service.currentBalance().getBalance().compareTo(new BigDecimal("0.00")));
        assertFalse(service.check(new BigDecimal("1")).allowed(), "float exhausted → payout declined");
    }

    @Test
    @DisplayName("debitPaisa is idempotent on reference — a replayed payout does not double-debit")
    void debitIdempotent() {
        service.debitPaisa("PAY-DUP", 2_500_000L);          // 25,000 NPR
        BigDecimal afterReplay = service.debitPaisa("PAY-DUP", 2_500_000L);
        assertEquals(0, afterReplay.compareTo(new BigDecimal("75000.00")), "single debit applied");
    }

    @Test
    @DisplayName("top-up credit raises the float and re-opens a declined gate")
    void topUpCredits() {
        service.debitPaisa("PAY-1", 10_000_000L);           // drain 100,000 NPR to zero
        assertFalse(service.check(new BigDecimal("30000")).allowed());

        service.credit("TOPUP-1", new BigDecimal("50000"));
        assertEquals(0, service.currentBalance().getBalance().compareTo(new BigDecimal("50000.00")));
        assertTrue(service.check(new BigDecimal("30000")).allowed(), "top-up re-opens the gate");
    }
}
