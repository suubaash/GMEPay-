package com.gme.pay.router.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The seeded in-process fixture returns priority-ordered rows and normalizes case. */
class InMemoryPartnerSchemeRegistryTest {

    private final InMemoryPartnerSchemeRegistry registry = new InMemoryPartnerSchemeRegistry();

    @Test
    @DisplayName("KH corridor returns KHQR (priority 0) before BAKONG (priority 1)")
    void khOrderedByPriority() {
        List<PartnerSchemeRecord> rows = registry.schemesForCountry("kh");
        assertEquals(List.of("KHQR", "BAKONG"),
                rows.stream().map(PartnerSchemeRecord::schemeId).toList());
    }

    @Test
    @DisplayName("NP corridor: NEPAL (priority 0) before the failover partner (priority 1)")
    void npOrderedByPriority() {
        List<PartnerSchemeRecord> rows = registry.schemesForCountry("np");
        assertEquals(List.of("NEPAL", "NEPAL_FONEPAY_DIRECT"),
                rows.stream().map(PartnerSchemeRecord::schemeId).toList());
    }

    @Test
    void unknownCountryIsEmptyNotNull() {
        assertTrue(registry.schemesForCountry("ZZ").isEmpty());
        assertTrue(registry.schemesForCountry(null).isEmpty());
        assertTrue(registry.schemesForCountry("  ").isEmpty());
    }

    @Test
    @DisplayName("staged corridor rows are visible and re-sorted by priority")
    void stagedRowsVisible() {
        registry.add(new PartnerSchemeRecord("QRIS", "ID", "INBOUND", false, true, 0));
        assertEquals(List.of("QRIS"),
                registry.schemesForCountry("ID").stream()
                        .map(PartnerSchemeRecord::schemeId).toList());
    }
}
