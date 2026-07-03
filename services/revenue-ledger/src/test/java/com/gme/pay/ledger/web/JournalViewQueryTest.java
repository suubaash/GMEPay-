package com.gme.pay.ledger.web;

import com.gme.pay.ledger.domain.ledger.LedgerPostingService;
import com.gme.pay.ledger.domain.model.EntryType;
import com.gme.pay.ledger.domain.model.Journal;
import com.gme.pay.ledger.domain.model.LedgerEntry;
import com.gme.pay.ledger.fees.SchemeFeeSplitCalculator;
import com.gme.pay.ledger.outbox.OutboxWriter;
import com.gme.pay.ledger.persistence.InMemoryJournalStore;
import com.gme.pay.ledger.persistence.JournalQueryService;
import com.gme.pay.ledger.persistence.JpaJournalStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * {@code @DataJpaTest} for the {@code GET /v1/journals} read path — exercises
 * {@link JournalQueryService} against the real H2 schema (Flyway V001/V002). Seeds a couple of
 * balanced journals with explicit {@code postedAt}/{@code reference} via {@link JpaJournalStore}
 * (which preserves the rehydrated timestamp), then asserts the {@link JournalPage} shape, that each
 * journal's lines BALANCE per currency, and that the reference / date-window / paging filters work.
 *
 * <p>No broker, no Docker — mirrors {@code JournalPersistenceIT}'s harness.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({JpaJournalStore.class, InMemoryJournalStore.class, LedgerPostingService.class,
        SchemeFeeSplitCalculator.class, OutboxWriter.class, JournalQueryService.class})
class JournalViewQueryTest {

    @Autowired
    private JpaJournalStore store;

    @Autowired
    private JournalQueryService query;

    private static final Instant T0 = Instant.parse("2026-06-15T10:00:00Z");

    /** Build + persist a balanced 2-line journal (DEBIT/CREDIT of the same amount) at a fixed time. */
    private Journal seed(String journalId, String reference, Instant postedAt,
                         BigDecimal amount, String currency) {
        List<LedgerEntry> lines = List.of(
                new LedgerEntry("RECEIVABLE_PARTNER", amount, currency, EntryType.DEBIT, reference),
                new LedgerEntry("REVENUE_SERVICE_CHARGE", amount, currency, EntryType.CREDIT, reference));
        return store.save(Journal.rehydrate(journalId, postedAt, lines));
    }

    @Test
    void list_returnsShape_linesBalance_andNewestFirst() {
        seed("jrnl-A", "TXN-AAA", T0, new BigDecimal("100.00000000"), "USD");
        seed("jrnl-B", "TXN-BBB", T0.plus(1, ChronoUnit.HOURS), new BigDecimal("250.00000000"), "USD");

        JournalPage p = query.list(T0.minus(1, ChronoUnit.DAYS), T0.plus(1, ChronoUnit.DAYS),
                null, 0, 50);

        assertEquals(0, p.page());
        assertEquals(50, p.size());
        assertEquals(2, p.total());
        assertEquals(2, p.items().size());

        // Newest-first: jrnl-B (T0+1h) before jrnl-A (T0).
        assertEquals("jrnl-B", p.items().get(0).journalId());
        assertEquals("jrnl-A", p.items().get(1).journalId());

        for (JournalView jv : p.items()) {
            assertNotNull(jv.createdAt());
            assertEquals(2, jv.lines().size());
            // side is the stored entry_type verbatim, currency is the stored column.
            assertTrue(jv.lines().stream().anyMatch(l -> "DEBIT".equals(l.side())));
            assertTrue(jv.lines().stream().anyMatch(l -> "CREDIT".equals(l.side())));
            assertLinesBalance(jv);
        }
    }

    @Test
    void list_filtersByReference() {
        seed("jrnl-A", "TXN-AAA", T0, new BigDecimal("100"), "USD");
        seed("jrnl-B", "TXN-BBB", T0.plus(1, ChronoUnit.HOURS), new BigDecimal("250"), "USD");

        JournalPage p = query.list(T0.minus(1, ChronoUnit.DAYS), T0.plus(1, ChronoUnit.DAYS),
                "TXN-BBB", 0, 50);

        assertEquals(1, p.total());
        assertEquals(1, p.items().size());
        assertEquals("jrnl-B", p.items().get(0).journalId());
        assertEquals("TXN-BBB", p.items().get(0).reference());
    }

    @Test
    void list_filtersByDateWindow_excludesOutsideRange() {
        seed("jrnl-old", "TXN-OLD", T0.minus(60, ChronoUnit.DAYS), new BigDecimal("10"), "USD");
        seed("jrnl-in", "TXN-IN", T0, new BigDecimal("20"), "USD");

        // Window is the last ~30 days ending just after T0 — the 60-day-old journal is excluded.
        JournalPage p = query.list(T0.minus(30, ChronoUnit.DAYS), T0.plus(1, ChronoUnit.SECONDS),
                null, 0, 50);

        assertEquals(1, p.total());
        assertEquals("jrnl-in", p.items().get(0).journalId());
    }

    @Test
    void list_pagesAndCapsSize() {
        for (int i = 0; i < 5; i++) {
            seed("jrnl-" + i, "TXN-" + i, T0.plus(i, ChronoUnit.MINUTES), new BigDecimal("1"), "USD");
        }
        Instant from = T0.minus(1, ChronoUnit.DAYS);
        Instant to = T0.plus(1, ChronoUnit.DAYS);

        JournalPage page0 = query.list(from, to, null, 0, 2);
        assertEquals(5, page0.total());
        assertEquals(2, page0.items().size());
        // Newest-first: jrnl-4 (T0+4m) then jrnl-3.
        assertEquals("jrnl-4", page0.items().get(0).journalId());

        JournalPage page2 = query.list(from, to, null, 2, 2);
        assertEquals(1, page2.items().size(), "last page has the remaining 1 of 5");
        assertEquals("jrnl-0", page2.items().get(0).journalId());

        // Oversized size is capped at 200 (still returns all 5 here).
        JournalPage capped = query.list(from, to, null, 0, 100_000);
        assertEquals(200, capped.size(), "size capped at 200");
        assertEquals(5, capped.items().size());
    }

    private static void assertLinesBalance(JournalView jv) {
        var currencies = jv.lines().stream().map(JournalView.Line::currency).distinct().toList();
        for (String ccy : currencies) {
            BigDecimal debits = jv.lines().stream()
                    .filter(l -> ccy.equals(l.currency()) && "DEBIT".equals(l.side()))
                    .map(JournalView.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal credits = jv.lines().stream()
                    .filter(l -> ccy.equals(l.currency()) && "CREDIT".equals(l.side()))
                    .map(JournalView.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(0, debits.compareTo(credits),
                    "journal " + jv.journalId() + " must balance for " + ccy);
        }
    }
}
