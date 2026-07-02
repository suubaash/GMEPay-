package com.gme.pay.txn.service;

import com.gme.pay.events.DomainEvent;
import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;
import com.gme.pay.txn.domain.statemachine.TransactionStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the 360° operator search ({@link TransactionService#queryTransactions} with the
 * flexible filters). A recording fake repo applies the merchant/scheme/txnRef filters so the test
 * asserts the service threads them through and returns the filtered projection.
 */
class TransactionSearchTest {

    private RecordingRepo repo;
    private TransactionService service;

    @BeforeEach
    void setUp() {
        repo = new RecordingRepo();
        service = new TransactionService(repo, new TransactionStateMachine(new ArrayList<DomainEvent>()::add));
    }

    private Transaction txn(String merchantId) {
        return new Transaction(
                700L, "PARTNER-" + merchantId, "zeropay", "INBOUND", "MPM",
                new BigDecimal("45000"), "KRW", new BigDecimal("33.88"), "USD", merchantId, "Q-1");
    }

    @Test
    @DisplayName("search filters by merchantId and returns only matching rows")
    void searchFiltersByMerchant() {
        repo.rows.add(txn("M-1"));
        repo.rows.add(txn("M-2"));

        Page<Transaction> page = service.queryTransactions(
                null, null, null, null, null, null, "M-1", 0, 20);

        assertEquals(1, page.getTotalElements());
        assertEquals("M-1", page.getContent().get(0).merchantId());
        // Service forwarded the flexible filters verbatim.
        assertEquals("M-1", repo.lastMerchantId);
    }

    @Test
    @DisplayName("search with no filters returns everything")
    void searchNoFilters() {
        repo.rows.add(txn("M-1"));
        repo.rows.add(txn("M-2"));
        Page<Transaction> page = service.queryTransactions(null, null, null, null, 0, 20);
        assertEquals(2, page.getTotalElements());
    }

    @Test
    @DisplayName("search by userRef returns only the matching customer's transaction")
    void searchFiltersByUserRef() {
        Transaction a = txn("M-1"); a.applyUserRef("WALLET-AAA");
        Transaction b = txn("M-2"); b.applyUserRef("WALLET-BBB");
        repo.rows.add(a);
        repo.rows.add(b);

        Page<Transaction> page = service.queryTransactions(
                null, null, null, null, null, null, null, "WALLET-AAA", null, 0, 20);

        assertEquals(1, page.getTotalElements());
        assertEquals("WALLET-AAA", page.getContent().get(0).userRef());
        assertEquals("WALLET-AAA", repo.lastUserRef);
    }

    @Test
    @DisplayName("search by reference matches the partner's own reference (partnerTxnRef)")
    void searchFiltersByReference() {
        repo.rows.add(txn("M-1")); // partnerTxnRef = "PARTNER-M-1"
        repo.rows.add(txn("M-2")); // partnerTxnRef = "PARTNER-M-2"

        Page<Transaction> page = service.queryTransactions(
                null, null, null, null, null, null, null, null, "PARTNER-M-2", 0, 20);

        assertEquals(1, page.getTotalElements());
        assertEquals("PARTNER-M-2", page.getContent().get(0).partnerTxnRef());
        assertEquals("PARTNER-M-2", repo.lastReference);
    }

    private static final class RecordingRepo implements TransactionRepository {
        final List<Transaction> rows = new ArrayList<>();
        String lastMerchantId;
        String lastSchemeTxnRef;
        String lastTxnRef;

        @Override public Transaction save(Transaction txn) { rows.add(txn); return txn; }
        @Override public Optional<Transaction> findByTxnRef(String txnRef) { return Optional.empty(); }

        String lastUserRef;
        String lastReference;

        @Override public Page<Transaction> findByFilters(LocalDate from, LocalDate to,
                                                         TransactionStatus status, Long partnerId,
                                                         String txnRef, String schemeTxnRef, String merchantId,
                                                         String userRef, String reference,
                                                         Pageable pageable) {
            this.lastMerchantId = merchantId;
            this.lastSchemeTxnRef = schemeTxnRef;
            this.lastTxnRef = txnRef;
            this.lastUserRef = userRef;
            this.lastReference = reference;
            List<Transaction> filtered = rows.stream()
                    .filter(t -> merchantId == null || merchantId.equals(t.merchantId()))
                    .filter(t -> txnRef == null || txnRef.equals(t.txnRef()))
                    .filter(t -> partnerId == null || partnerId.equals(t.partnerId()))
                    .filter(t -> userRef == null || userRef.equals(t.userRef()))
                    .filter(t -> reference == null || reference.equals(t.partnerTxnRef()))
                    .toList();
            return new PageImpl<>(filtered, pageable, filtered.size());
        }

        @Override public List<Transaction> findExpiredNonTerminal(Instant expiryBefore) { return List.of(); }
        @Override public List<Transaction> findStuck(Instant stuckBefore, List<String> sweepStatuses) { return List.of(); }
        @Override public List<Transaction> findCommittedFx(LocalDate from, LocalDate to, Long partnerId) { return List.of(); }
        @Override public List<Transaction> findRefundedOn(LocalDate refundedOn) { return List.of(); }
    }
}
