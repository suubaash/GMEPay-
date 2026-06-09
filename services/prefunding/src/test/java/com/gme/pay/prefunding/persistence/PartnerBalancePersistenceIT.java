package com.gme.pay.prefunding.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

/**
 * Slice integration test for the prefunding persistence layer. Uses Flyway-managed H2 (in
 * PostgreSQL mode) from application.properties — no Docker, no Testcontainers.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PartnerBalancePersistenceIT {

    @Autowired
    private PartnerBalanceRepository balances;

    @Autowired
    private LedgerEntryRepository ledger;

    @Test
    void roundTripPersistsAndRetrievesPartnerBalance() {
        PartnerBalanceEntity saved = balances.save(new PartnerBalanceEntity(
                "P_RT", "USD",
                new BigDecimal("12345.67890000"),
                new BigDecimal("100.00000000"),
                Instant.now()));

        Optional<PartnerBalanceEntity> found = balances.findById(saved.getPartnerId());
        assertTrue(found.isPresent());
        assertEquals(0, found.get().getBalance().compareTo(new BigDecimal("12345.67890000")));
        assertEquals("USD", found.get().getCurrency());
    }

    @Test
    void ledgerEntriesPersistAndQueryByPartnerInChronologicalOrder() {
        balances.save(new PartnerBalanceEntity(
                "P_LE", "USD", new BigDecimal("1000.00000000"),
                new BigDecimal("100.00000000"), Instant.now()));

        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        ledger.save(new LedgerEntryEntity("P_LE", "tx-1", "DEBIT",
                new BigDecimal("36.97140000"), "USD", t0));
        ledger.save(new LedgerEntryEntity("P_LE", null, "CREDIT",
                new BigDecimal("500.00000000"), "USD", t0.plusSeconds(60)));

        List<LedgerEntryEntity> rows = ledger.findByPartnerIdOrderByCreatedAtAscIdAsc("P_LE");
        assertEquals(2, rows.size());
        assertNotNull(rows.get(0).getId());
        assertEquals("DEBIT", rows.get(0).getEntryType());
        assertEquals("tx-1", rows.get(0).getTxnRef());
        assertEquals(0, rows.get(0).getAmount().compareTo(new BigDecimal("36.97140000")));
        assertEquals("CREDIT", rows.get(1).getEntryType());
        assertEquals(2L, ledger.countByPartnerId("P_LE"));
    }
}
