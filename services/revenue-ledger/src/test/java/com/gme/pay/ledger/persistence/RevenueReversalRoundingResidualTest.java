package com.gme.pay.ledger.persistence;

import com.gme.pay.ledger.domain.ledger.LedgerPostingService;
import com.gme.pay.ledger.domain.ledger.RevenueReversalService;
import com.gme.pay.ledger.domain.model.EntryType;
import com.gme.pay.ledger.domain.model.Journal;
import com.gme.pay.ledger.domain.model.LedgerEntry;
import com.gme.pay.ledger.fees.SchemeFeeSplitCalculator;
import com.gme.pay.ledger.outbox.OutboxWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Gap T2-9 regression: <b>reversing a transaction that also carries a rounding residual must succeed.</b>
 *
 * <h2>The defect</h2>
 *
 * <p>{@code RevenueReversalService} mirrored EVERY ledger line for a txnRef, including any
 * {@code REVENUE_ROUNDING} line. {@code JpaJournalStore} classifies any journal touching that account as a
 * rounding journal and writes the {@code reference}-keyed guard row in {@code rounding_residual_keys}
 * (Flyway V006). So a reversing journal for a transaction that had BOTH a revenue capture and a rounding
 * residual arrived at the store carrying a {@code REVENUE_ROUNDING} line and hit that already-taken guard
 * key. Reversing such a transaction failed — and because the write was a Spring Data {@code save()} on an
 * assigned-id entity, i.e. a {@code merge}, in the case where it did NOT fail it silently re-pointed the
 * residual's guard row at the reversing journal, so {@code findRoundingResidualByReference} then returned
 * the reversal.
 *
 * <p>This matters for T2-6 because a refund's whole point is to reach that reversal path.
 *
 * <h2>The fix these tests pin</h2>
 * <ol>
 *   <li>A reversal mirrors capture journals ONLY — rounding-residual journals are excluded whole, both legs
 *       together, so the reversing journal is still balanced. A residual is a settlement-booking artefact
 *       with its own idempotent lifecycle, not captured revenue.</li>
 *   <li>The guard row is a real {@code INSERT} ({@code RoundingResidualKeyEntity} is {@code Persistable}),
 *       so the PK is the concurrency backstop V006 says it is instead of a silent overwrite.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({JpaJournalStore.class, LedgerPostingService.class, SchemeFeeSplitCalculator.class,
        OutboxWriter.class, RevenueReversalService.class})
class RevenueReversalRoundingResidualTest {

    private static final String TXN = "TXN-T29-001";

    @Autowired private LedgerPostingService posting;
    @Autowired private RevenueReversalService reversal;
    @Autowired private JpaJournalStore store;
    @Autowired private RoundingResidualKeyRepository roundingKeys;

    @Test
    @DisplayName("T2-9: reversing a txn that ALSO has a rounding residual now succeeds")
    void reversingATxnWithARoundingResidual_succeeds() {
        // A capture (FX margin + service charge) and a rounding residual, both keyed on the SAME txnRef —
        // exactly the shape that used to make the reversal fail.
        posting.postCapturedRevenueJournal(TXN, new BigDecimal("1.2500"), new BigDecimal("500"), "KRW");
        Journal residual = posting.postRoundingResidual(TXN, new BigDecimal("0.30"), "KRW");
        assertTrue(residual != null, "fixture must actually have booked a residual");

        Optional<Journal> reversed = reversal.reverseCapture(TXN);

        assertTrue(reversed.isPresent(), "the reversal must be posted, not fail and not silently skip");

        // It mirrors the CAPTURE lines only — no REVENUE_ROUNDING line rides along.
        List<LedgerEntry> lines = reversed.get().entries();
        assertTrue(lines.stream().noneMatch(e -> "REVENUE_ROUNDING".equals(e.account())),
                "a reversal must not mirror the rounding residual: that is what collided with the V006 guard");
        assertTrue(lines.stream().anyMatch(e -> "REVENUE_FX_MARGIN".equals(e.account())
                        && e.type() == EntryType.DEBIT),
                "the FX-margin income is still backed out (a DEBIT reverses the capture's CREDIT)");
        assertTrue(lines.stream().anyMatch(e -> "REVENUE_SERVICE_CHARGE".equals(e.account())
                        && e.type() == EntryType.DEBIT),
                "the service charge is still backed out");
    }

    @Test
    @DisplayName("T2-9: the residual's guard row still points at the RESIDUAL, not at the reversal")
    void theResidualGuardIsNotHijackedByTheReversal() {
        posting.postCapturedRevenueJournal(TXN, new BigDecimal("1.2500"), BigDecimal.ZERO, "KRW");
        Journal residual = posting.postRoundingResidual(TXN, new BigDecimal("0.30"), "KRW");

        reversal.reverseCapture(TXN);

        RoundingResidualKeyEntity guard = roundingKeys.findById(TXN).orElseThrow();
        assertEquals(residual.journalId(), guard.getJournalId(),
                "the guard must still resolve to the residual journal; a merge used to overwrite it");
        assertEquals(residual.journalId(),
                store.findRoundingResidualByReference(TXN).orElseThrow().journalId(),
                "and the residual must remain findable by its reference");
    }

    @Test
    @DisplayName("T2-9: the residual stays booked and the reversal nets the CAPTURE to zero")
    void residualSurvivesAndCaptureNetsToZero() {
        posting.postCapturedRevenueJournal(TXN, new BigDecimal("1.2500"), new BigDecimal("500"), "KRW");
        posting.postRoundingResidual(TXN, new BigDecimal("0.30"), "KRW");
        reversal.reverseCapture(TXN);

        List<LedgerEntry> all = store.findByReference(TXN).stream()
                .flatMap(j -> j.entries().stream())
                .toList();

        // Every income account nets to zero: capture CREDIT + reversal DEBIT.
        assertEquals(0, signedTotal(all, "REVENUE_FX_MARGIN").signum(),
                "FX margin is fully backed out");
        assertEquals(0, signedTotal(all, "REVENUE_SERVICE_CHARGE").signum(),
                "service charge is fully backed out");
        // The residual is NOT backed out — deliberately. It is a settlement-rounding gain, not revenue this
        // service captured, and it has its own idempotent lifecycle keyed by its own guard row.
        assertEquals(0, signedTotal(all, "REVENUE_ROUNDING").compareTo(new BigDecimal("0.30")),
                "the rounding residual remains booked, unchanged, on its own account");
    }

    @Test
    @DisplayName("a residual-only transaction is not 'reversed' at all — there is no capture to back out")
    void residualOnlyTransaction_hasNothingToReverse() {
        posting.postRoundingResidual(TXN, new BigDecimal("0.30"), "KRW");

        assertTrue(reversal.reverseCapture(TXN).isEmpty(),
                "no capture means nothing to reverse; posting a mirror of the residual would have "
                        + "double-counted it and re-keyed its guard");
    }

    @Test
    @DisplayName("the reversal stays idempotent when a residual is present")
    void reversalRemainsIdempotent() {
        posting.postCapturedRevenueJournal(TXN, new BigDecimal("1.2500"), BigDecimal.ZERO, "KRW");
        posting.postRoundingResidual(TXN, new BigDecimal("0.30"), "KRW");

        assertTrue(reversal.reverseCapture(TXN).isPresent());
        assertTrue(reversal.reverseCapture(TXN).isEmpty(), "a second reversal must be a no-op");

        List<LedgerEntry> all = store.findByReference(TXN).stream()
                .flatMap(j -> j.entries().stream())
                .toList();
        assertEquals(1, all.stream()
                        .filter(e -> "REVENUE_FX_MARGIN".equals(e.account())
                                && e.type() == EntryType.DEBIT)
                        .count(),
                "exactly one contra line, so the reversal was not double-booked");
    }

    @Test
    @DisplayName("the V006 guard is a real PK insert: a duplicate residual reference is refused, not merged")
    void roundingGuardIsARealInsert() {
        posting.postRoundingResidual(TXN, new BigDecimal("0.30"), "KRW");
        String firstJournalId = roundingKeys.findById(TXN).orElseThrow().getJournalId();

        // Racing the application-level pre-check: post a rounding journal for the SAME reference directly
        // through the store. Before the Persistable fix this was a silent SELECT+UPDATE of the guard row.
        boolean refused = false;
        try {
            store.save(Journal.post(List.of(
                    new LedgerEntry("REVENUE_ROUNDING", new BigDecimal("0.99"), "KRW",
                            EntryType.DEBIT, TXN),
                    new LedgerEntry("RECEIVABLE_PARTNER", new BigDecimal("0.99"), "KRW",
                            EntryType.CREDIT, TXN))));
        } catch (RuntimeException expected) {
            refused = true;
        }

        assertTrue(refused, "a second rounding journal for the same reference must trip the V006 primary key");
        assertEquals(firstJournalId, roundingKeys.findById(TXN).orElseThrow().getJournalId(),
                "and the guard row must be unchanged — never re-pointed at the loser");
        assertFalse(firstJournalId.isBlank());
    }

    /** Signed total for an account: CREDIT positive, DEBIT negative (income-account convention). */
    private static BigDecimal signedTotal(List<LedgerEntry> entries, String account) {
        return entries.stream()
                .filter(e -> account.equals(e.account()))
                .map(e -> e.type() == EntryType.CREDIT ? e.amount() : e.amount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
