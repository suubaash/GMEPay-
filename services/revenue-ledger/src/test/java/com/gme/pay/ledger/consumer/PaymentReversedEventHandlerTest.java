package com.gme.pay.ledger.consumer;

import com.gme.pay.ledger.domain.ledger.LedgerPostingService;
import com.gme.pay.ledger.domain.ledger.RevenueReversalService;
import com.gme.pay.ledger.domain.model.EntryType;
import com.gme.pay.ledger.domain.model.Journal;
import com.gme.pay.ledger.domain.model.LedgerEntry;
import com.gme.pay.ledger.fees.SchemeFeeSplitCalculator;
import com.gme.pay.ledger.persistence.InMemoryJournalStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PaymentReversedEventHandler}: the async payment.reversed → reversing-journal
 * mapping. Broker-free — runs against the real {@link InMemoryJournalStore} wrapped in the real
 * {@link RevenueReversalService}, exercising the genuine idempotency + balance path the Kafka consumer
 * relies on. An original capture is seeded via {@link LedgerPostingService} so the reversal has
 * something to contra.
 */
class PaymentReversedEventHandlerTest {

    private InMemoryJournalStore store;
    private LedgerPostingService posting;
    private PaymentReversedEventHandler handler;

    @BeforeEach
    void setUp() {
        store = new InMemoryJournalStore();
        posting = new LedgerPostingService(store, new SchemeFeeSplitCalculator());
        handler = new PaymentReversedEventHandler(new RevenueReversalService(store));
    }

    /** Seed the capture side (margin + service charge + fee-share) for a txnRef. */
    private void seedCapture(String txnRef) {
        posting.postRevenueCapture(txnRef, new BigDecimal("1.50"), new BigDecimal("3.00"), "USD");
        posting.postFeeShareSplit(txnRef, 100_000L, new BigDecimal("0.0080"),
                new BigDecimal("0.0008"), new BigDecimal("0.70"));
    }

    /** Signed net (CREDIT − DEBIT) per (account, currency) across every posted line for a txnRef. */
    private Map<String, BigDecimal> netByAccountCcy(String txnRef) {
        Map<String, BigDecimal> net = new HashMap<>();
        for (Journal j : store.findByReference(txnRef)) {
            for (LedgerEntry e : j.entries()) {
                BigDecimal signed = e.type() == EntryType.CREDIT ? e.amount() : e.amount().negate();
                net.merge(e.account() + "|" + e.currency(), signed, BigDecimal::add);
            }
        }
        return net;
    }

    private static String reversedPayload(String txnRef) {
        return """
                {"eventType":"payment.reversed","txnRef":"%s","partnerId":"7","schemeId":"1",
                 "reversedAmount":"100000","currency":"KRW","reversedUsd":"1.50",
                 "reason":"operator force-resolve","source":"OPERATOR","occurredAt":"2026-06-20T09:00:00Z"}
                """.formatted(txnRef);
    }

    @Test
    void reversal_netsOriginalCaptureToZero() {
        seedCapture("TXN-100");
        // Sanity: before reversal, revenue accounts carry a non-zero credit balance.
        assertThat(netByAccountCcy("TXN-100").get("REVENUE_FX_MARGIN|USD")).isEqualByComparingTo("1.50");

        boolean booked = handler.handle("TXN-100", reversedPayload("TXN-100"));

        assertThat(booked).isTrue();
        // After the reversing journal, EVERY account nets to zero in every currency.
        netByAccountCcy("TXN-100").forEach((k, v) ->
                assertThat(v).as("account %s must net to zero", k).isEqualByComparingTo(BigDecimal.ZERO));
    }

    @Test
    void secondReversal_isIdempotentNoOp() {
        seedCapture("TXN-200");
        int journalsBefore = store.findByReference("TXN-200").size();

        assertThat(handler.handle("TXN-200", reversedPayload("TXN-200"))).isTrue();   // first reverses
        int afterFirst = store.findByReference("TXN-200").size();
        assertThat(afterFirst).isGreaterThan(journalsBefore);

        assertThat(handler.handle("TXN-200", reversedPayload("TXN-200"))).isFalse();  // redelivery: no-op
        assertThat(store.findByReference("TXN-200")).hasSize(afterFirst);             // no second contra

        // Still balanced to zero — not double-reversed.
        netByAccountCcy("TXN-200").forEach((k, v) ->
                assertThat(v).isEqualByComparingTo(BigDecimal.ZERO));
    }

    @Test
    void reversalForUnknownTxnRef_isSafeNoOp() {
        boolean booked = handler.handle("TXN-UNKNOWN", reversedPayload("TXN-UNKNOWN"));

        assertThat(booked).isFalse();
        assertThat(store.findByReference("TXN-UNKNOWN")).isEmpty();  // nothing posted
    }

    @Test
    void txnRefFallsBackToRecordKey_whenPayloadOmitsIt() {
        seedCapture("KEY-TXN-500");
        String payload = """
                {"eventType":"payment.reversed","partnerId":"7","source":"OPERATOR",
                 "occurredAt":"2026-06-20T09:00:00Z"}
                """;

        assertThat(handler.handle("KEY-TXN-500", payload)).isTrue();
        netByAccountCcy("KEY-TXN-500").forEach((k, v) ->
                assertThat(v).isEqualByComparingTo(BigDecimal.ZERO));
    }

    @Test
    void wrongEventType_isPoison() {
        assertThatThrownBy(() -> handler.handle("TXN-1", """
                {"eventType":"payment.approved","txnRef":"TXN-1"}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected eventType");
    }

    @Test
    void invalidJson_isPoison() {
        assertThatThrownBy(() -> handler.handle("TXN-1", "{not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void emptyPayload_isPoison() {
        assertThatThrownBy(() -> handler.handle("TXN-1", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty payload");
    }

    @Test
    void missingTxnRefAndKey_isPoison() {
        assertThatThrownBy(() -> handler.handle(null, """
                {"eventType":"payment.reversed","source":"OPERATOR"}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no txnRef");
    }
}
