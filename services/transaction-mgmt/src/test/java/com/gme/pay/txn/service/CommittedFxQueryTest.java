package com.gme.pay.txn.service;

import com.gme.pay.contracts.CommittedFxView;
import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;
import com.gme.pay.txn.domain.statemachine.TransactionStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Service-tier tests for the committed-FX projection ({@code findCommittedFx} → CommittedFxView)
 * and the refund-date query ({@code findRefundedOn}). Fake repo, no Spring/DB.
 */
class CommittedFxQueryTest {

    private FakeRepo repo;
    private TransactionService service;

    @BeforeEach
    void setUp() {
        repo = new FakeRepo();
        EventPublisher capturing = new ArrayList<DomainEvent>()::add;
        service = new TransactionService(repo, new TransactionStateMachine(capturing));
    }

    private static Transaction committed() {
        Transaction txn = new Transaction(
                700L, "PTX-1", "zeropay_kr", "OUTBOUND", "CPM",
                new BigDecimal("130000.00000000"), "KRW",
                new BigDecimal("11000000.00000000"), "IDR",
                "M-1", "Q-1");
        txn.applyStatusPatch("SCH-1", "AP-1", new BigDecimal("673.07690000"),
                Instant.now(), null, null, null);
        txn.captureCommittedFxAtCommit(new BigDecimal("6.73080000"), new BigDecimal("6.73080000"),
                Instant.parse("2026-06-20T03:00:00Z"));
        return txn;
    }

    @Test
    @DisplayName("findCommittedFx maps the aggregate into the canonical CommittedFxView")
    void mapsToCommittedFxView() {
        repo.committed.add(committed());
        List<CommittedFxView> views = service.findCommittedFx(
                LocalDate.of(2026, 6, 20), LocalDate.of(2026, 6, 20), 700L);

        assertEquals(1, views.size());
        CommittedFxView v = views.get(0);
        assertEquals(700L, v.partnerId());
        assertEquals("OUTBOUND", v.direction());
        assertEquals("IDR", v.collectionCcy());
        assertEquals("KRW", v.payoutCcy());
        assertFalse(v.sameCcyShortcircuit());
        assertNotNull(v.offerRateColl());   // FX1015 #14
        assertNotNull(v.crossRate());
        assertEquals(0, v.usdAmount().compareTo(new BigDecimal("673.07690000")));
        assertEquals(0, v.collectionMarginUsd().compareTo(new BigDecimal("6.73080000")));
        assertEquals(0, v.payoutAmount().compareTo(new BigDecimal("130000")));
        assertEquals(0, v.collectionAmount().compareTo(new BigDecimal("11000000")));
    }

    @Test
    @DisplayName("findRefundedOn requires a date and passes it through to the repo")
    void refundedOnRequiresDate() {
        assertThrows(com.gme.pay.errors.ApiException.class, () -> service.findRefundedOn(null));

        Transaction refunded = committed();
        refunded.applyStatus(TransactionStatus.REFUNDED);
        refunded.applyRefundEnrichment(new BigDecimal("130000"), "QR-1",
                Instant.parse("2026-06-25T08:00:00Z"), "ORIG-1");
        repo.refunded.add(refunded);

        List<Transaction> hits = service.findRefundedOn(LocalDate.of(2026, 6, 25));
        assertEquals(1, hits.size());
        assertEquals("ORIG-1", hits.get(0).originalPaymentTxnRef());
    }

    private static final class FakeRepo implements TransactionRepository {
        final List<Transaction> committed = new ArrayList<>();
        final List<Transaction> refunded = new ArrayList<>();

        @Override public Transaction save(Transaction txn) { return txn; }
        @Override public Optional<Transaction> findByTxnRef(String txnRef) { return Optional.empty(); }
        @Override public Page<Transaction> findByFilters(LocalDate from, LocalDate to,
                                                         TransactionStatus status, Long partnerId,
                                                         String txnRef, String schemeTxnRef, String merchantId,
                                                         String userRef, String reference,
                                                         Pageable pageable) { return Page.empty(); }
        @Override public List<Transaction> findExpiredNonTerminal(Instant expiryBefore) { return List.of(); }
        @Override public List<Transaction> findStuck(Instant stuckBefore, List<String> sweepStatuses) { return List.of(); }
        @Override public List<Transaction> findCommittedFx(LocalDate from, LocalDate to, Long partnerId) {
            return List.copyOf(committed);
        }
        @Override public List<Transaction> findRefundedOn(LocalDate refundedOn) {
            return List.copyOf(refunded);
        }
    }
}
