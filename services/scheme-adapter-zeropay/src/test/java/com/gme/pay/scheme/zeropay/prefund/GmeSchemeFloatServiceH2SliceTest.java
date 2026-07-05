package com.gme.pay.scheme.zeropay.prefund;

import com.gme.pay.scheme.zeropay.persistence.GmeSchemeBalanceEntryRepository;
import com.gme.pay.scheme.zeropay.persistence.GmeSchemeBalanceRepository;
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
 * Persistence-backed behaviour of the ZeroPay GME prepaid float ({@code gme_scheme_balance} V004):
 * lazy seeding, the pre-submit check reading the live balance, idempotent debit on payout, top-up
 * credit, and the gate flipping to declined once the float is drained. H2 in PostgreSQL mode, no Docker.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GmeSchemeFloatServiceH2SliceTest {

    private static final long OPENING = 100_000L;

    @Autowired
    private GmeSchemeBalanceRepository balanceRepo;
    @Autowired
    private GmeSchemeBalanceEntryRepository entryRepo;

    private GmeSchemeFloatService service;

    @BeforeEach
    void setUp() {
        service = new GmeSchemeFloatService(balanceRepo, entryRepo, "ZEROPAY", "KRW", OPENING);
    }

    @Test
    @DisplayName("seeds the opening balance on first access; check reflects it")
    void seedsAndChecks() {
        GmeSchemeFloatService.BalanceCheck under = service.check(new BigDecimal("40000"));
        assertTrue(under.allowed(), "40000 <= 100000 opening");
        assertEquals(0, under.available().compareTo(BigDecimal.valueOf(OPENING)));

        assertFalse(service.check(new BigDecimal("100001")).allowed(), "over the opening float is declined");
    }

    @Test
    @DisplayName("debit reduces the float; a drained float then declines the next payout")
    void debitDrainsAndGate() {
        service.debit("PAY-1", new BigDecimal("60000"));
        assertEquals(0, service.currentBalance().getBalance().compareTo(new BigDecimal("40000")));

        assertTrue(service.check(new BigDecimal("40000")).allowed());
        service.debit("PAY-2", new BigDecimal("40000"));
        assertEquals(0, service.currentBalance().getBalance().compareTo(BigDecimal.ZERO));

        assertFalse(service.check(new BigDecimal("1")).allowed(), "float exhausted → payout declined");
    }

    @Test
    @DisplayName("debit is idempotent on txnRef — a replayed payout does not double-debit")
    void debitIdempotent() {
        service.debit("PAY-DUP", new BigDecimal("25000"));
        BigDecimal afterReplay = service.debit("PAY-DUP", new BigDecimal("25000"));
        assertEquals(0, afterReplay.compareTo(new BigDecimal("75000")), "single debit applied");
        assertEquals(0, service.currentBalance().getBalance().compareTo(new BigDecimal("75000")));
    }

    @Test
    @DisplayName("top-up credit raises the float and re-opens a declined gate")
    void topUpCredits() {
        service.debit("PAY-1", new BigDecimal("100000"));   // drain to zero
        assertFalse(service.check(new BigDecimal("30000")).allowed());

        service.credit("TOPUP-1", new BigDecimal("50000"));
        assertEquals(0, service.currentBalance().getBalance().compareTo(new BigDecimal("50000")));
        assertTrue(service.check(new BigDecimal("30000")).allowed(), "top-up re-opens the gate");
    }
}
