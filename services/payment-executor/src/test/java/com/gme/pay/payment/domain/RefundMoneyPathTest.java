package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.client.PrefundingClient;
import com.gme.pay.payment.domain.client.QrClient;
import com.gme.pay.payment.domain.client.RateClient;
import com.gme.pay.payment.domain.client.RevenueLedgerClient;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.TransactionClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gap T2-6 — the refund money path.
 *
 * <p>Each test names the defect it pins. All collaborators are hand-written fakes; the prefunding fake keeps a
 * REAL per-key balance ledger so "the float ends up short by exactly the refunded amount" is an arithmetic
 * assertion on a simulated balance rather than a call-count assertion.
 *
 * <p>The scheme fake accepts a partial refund instruction, standing in for an adapter whose contract carries a
 * refund amount. The production ZeroPay client deliberately does NOT — that fail-closed refusal is pinned
 * separately by {@code RestSchemeClientPartialRefundTest}.
 */
class RefundMoneyPathTest {

    private static final long PARTNER = 42L;
    private static final String TXN = "txn_1";
    private static final String SCHEME_TXN = "ZP_TXN_1";

    // Original payment: 50 000 KRW collected, 37.5000 USD taken off the float at the locked rate.
    private static final BigDecimal ORIGINAL_KRW = new BigDecimal("50000");
    private static final BigDecimal CAPTURED_USD = new BigDecimal("37.5000");

    // ---- fakes -------------------------------------------------------------

    /** A prefunding fake with a real balance + per-key debit ledger, so net float movement is provable. */
    private static final class LedgerPrefunding implements PrefundingClient {
        private BigDecimal balance = new BigDecimal("1000.0000");
        /** key → outstanding debit; a reversal zeroes it (and is idempotent, as prefunding really is). */
        private final Map<String, BigDecimal> debits = new LinkedHashMap<>();
        private final List<String> calls = new ArrayList<>();

        LedgerPrefunding seedCapture(String key, BigDecimal amount) {
            debits.put(key, amount);
            balance = balance.subtract(amount);
            return this;
        }

        @Override
        public DeductionResult deduct(long partnerId, String txnRef, BigDecimal amountUsd) {
            calls.add("DEDUCT " + txnRef + " " + amountUsd.toPlainString());
            BigDecimal existing = debits.get(txnRef);
            if (existing != null && existing.signum() > 0) {
                return new DeductionResult(BigDecimal.ZERO, balance);   // idempotent replay
            }
            debits.put(txnRef, amountUsd);
            balance = balance.subtract(amountUsd);
            return new DeductionResult(amountUsd, balance);
        }

        @Override
        public ReverseResult reverse(long partnerId, String txnRef) {
            calls.add("REVERSE " + txnRef);
            BigDecimal outstanding = debits.getOrDefault(txnRef, BigDecimal.ZERO);
            if (outstanding.signum() == 0) {
                return new ReverseResult(BigDecimal.ZERO, balance);     // idempotent replay
            }
            debits.put(txnRef, BigDecimal.ZERO);
            balance = balance.add(outstanding);
            return new ReverseResult(outstanding, balance);
        }

        BigDecimal netDebited() {
            return debits.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /** Records the cancel instruction the scheme was handed, and accepts partial refunds. */
    private static final class RecordingScheme implements SchemeClient {
        CancelRequest lastCancel;
        int cancelCount;

        @Override
        public MpmSubmitResponse submitMpm(MpmSubmitRequest req) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancelPayment(String schemeTxnRef, String reason) {
            cancelCount++;
            lastCancel = new CancelRequest(schemeTxnRef, reason, null);
        }

        @Override
        public void cancelPayment(CancelRequest request) {
            cancelCount++;
            lastCancel = request;
        }

        @Override
        public CpmSubmitResponse submitCpm(CpmSubmitRequest req) {
            throw new UnsupportedOperationException();
        }
    }

    /** A transaction fake backed by a mutable basis, so cumulative refunds across calls are real. */
    private static final class BasisTransactionClient implements TransactionClient {
        private RefundBasis basis;
        final List<StatusPatch> patches = new ArrayList<>();

        BasisTransactionClient(RefundBasis basis) {
            this.basis = basis;
        }

        @Override
        public CreateResult createPending(CreateRequest request) {
            return new CreateResult(TXN, "pay_1", Instant.now());
        }

        @Override
        public void commitStatus(String txnRef, StatusPatch patch) {
            patches.add(patch);
            // Mirror what transaction-mgmt persists, so a follow-up refund sees the new cumulative total.
            if (patch.refundAmountKrw() != null && basis != null) {
                basis = new RefundBasis(basis.txnRef(), "REFUNDED", basis.collectionAmount(),
                        basis.collectionCurrency(), basis.prefundDeductedUsd(), patch.refundAmountKrw());
            }
        }

        @Override
        public Optional<RefundBasis> findRefundBasis(String txnRef) {
            return Optional.ofNullable(basis);
        }

        StatusPatch lastPatch() {
            return patches.get(patches.size() - 1);
        }
    }

    /** Records reversal journals. */
    private static final class RecordingLedger implements RevenueLedgerClient {
        record Posting(String reference, BigDecimal amount, String currency) {}

        final List<Posting> reversals = new ArrayList<>();
        final List<Posting> residuals = new ArrayList<>();

        @Override
        public void postRoundingResidual(String reference, BigDecimal residual, String currency) {
            residuals.add(new Posting(reference, residual, currency));
        }

        @Override
        public void postReversalJournal(String reference, BigDecimal reversalAmount, String currency) {
            reversals.add(new Posting(reference, reversalAmount, currency));
        }
    }

    private static final RateClient NO_RATE = (quoteId, partnerId) -> {
        throw new UnsupportedOperationException();
    };
    private static final QrClient NO_QR = qr -> {
        throw new UnsupportedOperationException();
    };

    private static TransactionClient.RefundBasis basis(BigDecimal alreadyRefunded) {
        return new TransactionClient.RefundBasis(TXN, "APPROVED", ORIGINAL_KRW, "KRW",
                CAPTURED_USD, alreadyRefunded);
    }

    private static PaymentOrchestrator orchestrator(PrefundingClient prefunding,
                                                   SchemeClient scheme,
                                                   TransactionClient txn,
                                                   RevenueLedgerClient ledger) {
        return new PaymentOrchestrator(NO_RATE, prefunding, NO_QR, scheme, txn, null, ledger);
    }

    // ======================================================================
    // Defect 1 — no partial refunds: the DTOs carried no amount and the path
    // always reversed the FULL prefunding hold at the original locked rate.
    // ======================================================================

    @Test
    @DisplayName("partial refund reverses ONLY the requested amount, at the ORIGINAL locked rate")
    void partialRefund_reversesOnlyTheRequestedAmountAtTheLockedRate() {
        LedgerPrefunding prefunding = new LedgerPrefunding().seedCapture(TXN, CAPTURED_USD);
        RecordingScheme scheme = new RecordingScheme();
        BasisTransactionClient txn = new BasisTransactionClient(basis(null));
        RecordingLedger ledger = new RecordingLedger();

        // Refund 20 000 of the 50 000 KRW collected = 40%.
        PaymentOrchestrator.RefundResult result = orchestrator(prefunding, scheme, txn, ledger)
                .refundPayment("pay_1", SCHEME_TXN, PartnerType.OVERSEAS, PARTNER, TXN,
                        "CUSTOMER_REQUEST", "ZEROPAY", new BigDecimal("20000"), "KRW");

        // 40% of the 37.5000 USD captured at the LOCKED rate = 15.0000 USD. No rate was read to get there:
        // the figure is a fraction of what the original payment actually took off the float.
        assertEquals(0, result.prefundReturnedUsd().compareTo(new BigDecimal("15.0000")),
                "the refunded USD is the captured USD pro-rated at the original locked rate");
        assertEquals(0, new BigDecimal("20000").compareTo(result.refundedAmount()));
        assertEquals("KRW", result.refundedCurrency());
        assertEquals(false, result.fullyRefunded(), "40% refunded is not a full refund");

        // The float is short by exactly the 22.5000 USD that was NOT refunded — not by zero (which a full
        // reverse would leave) and not by 37.5000 (which no reverse at all would leave).
        assertEquals(0, prefunding.netDebited().compareTo(new BigDecimal("22.5000")),
                "net float movement = captured - refunded, i.e. only the requested slice came back");

        // The scheme was told the amount, so an adapter that cannot express a partial can refuse it.
        assertEquals(0, new BigDecimal("20000").compareTo(scheme.lastCancel.partialAmount()));
        assertTrue(scheme.lastCancel.isPartial());

        // The reversal journal books the refunded USD only.
        assertEquals(1, ledger.reversals.size());
        assertEquals(0, ledger.reversals.get(0).amount().compareTo(new BigDecimal("15.0000")));
        assertEquals("USD", ledger.reversals.get(0).currency());
        assertTrue(ledger.residuals.isEmpty(), "a refund must never be booked as a rounding residual");
    }

    @Test
    @DisplayName("a FULL refund still reverses the whole hold and touches no retained-slice key")
    void fullRefund_isUnchanged() {
        LedgerPrefunding prefunding = new LedgerPrefunding().seedCapture(TXN, CAPTURED_USD);
        RecordingScheme scheme = new RecordingScheme();
        BasisTransactionClient txn = new BasisTransactionClient(basis(null));
        RecordingLedger ledger = new RecordingLedger();

        PaymentOrchestrator.RefundResult result = orchestrator(prefunding, scheme, txn, ledger)
                .refundPayment("pay_1", SCHEME_TXN, PartnerType.OVERSEAS, PARTNER, TXN,
                        "PARTNER_INITIATED", "ZEROPAY");

        assertEquals(0, result.prefundReturnedUsd().compareTo(CAPTURED_USD));
        assertTrue(result.fullyRefunded());
        assertEquals(0, prefunding.netDebited().signum(), "the whole hold came back");
        assertEquals(List.of("REVERSE " + TXN), prefunding.calls,
                "a full refund performs the single reverse it always did — no extra float calls");
        assertNull(scheme.lastCancel.partialAmount(), "a full refund carries no partial amount");
    }

    @Test
    @DisplayName("sequential partial refunds compose: the float ends short by the cumulative refund only")
    void sequentialPartials_composeOnTheFloat() {
        LedgerPrefunding prefunding = new LedgerPrefunding().seedCapture(TXN, CAPTURED_USD);
        RecordingScheme scheme = new RecordingScheme();
        BasisTransactionClient txn = new BasisTransactionClient(basis(null));
        RecordingLedger ledger = new RecordingLedger();
        PaymentOrchestrator orchestrator = orchestrator(prefunding, scheme, txn, ledger);

        orchestrator.refundPayment("pay_1", SCHEME_TXN, PartnerType.OVERSEAS, PARTNER, TXN,
                "R1", "ZEROPAY", new BigDecimal("20000"), "KRW");
        PaymentOrchestrator.RefundResult second = orchestrator.refundPayment(
                "pay_1", SCHEME_TXN, PartnerType.OVERSEAS, PARTNER, TXN,
                "R2", "ZEROPAY", new BigDecimal("10000"), "KRW");

        // 30 000 of 50 000 refunded = 60% of 37.5000 = 22.5000 USD back; 15.0000 stays deducted.
        assertEquals(0, new BigDecimal("30000").compareTo(second.cumulativeRefundedAmount()));
        assertEquals(0, second.prefundReturnedUsd().compareTo(new BigDecimal("7.5000")),
                "the second refund returns its OWN slice, not the cumulative total");
        assertEquals(0, prefunding.netDebited().compareTo(new BigDecimal("15.0000")),
                "net float movement = captured - cumulative refunded");
    }

    @Test
    @DisplayName("a partial refund that completes the payment leaves NO float crumb behind")
    void partialThenRemainder_leavesNoRoundingCrumb() {
        BasisTransactionClient txn = new BasisTransactionClient(
                new TransactionClient.RefundBasis(TXN, "APPROVED", new BigDecimal("30000"), "KRW",
                        new BigDecimal("22.2223"), null));
        LedgerPrefunding fresh = new LedgerPrefunding().seedCapture(TXN, new BigDecimal("22.2223"));
        PaymentOrchestrator orchestrator =
                orchestrator(fresh, new RecordingScheme(), txn, new RecordingLedger());

        // 10 000 of 30 000 is a third — deliberately not representable exactly.
        orchestrator.refundPayment("pay_1", SCHEME_TXN, PartnerType.OVERSEAS, PARTNER, TXN,
                "R1", "ZEROPAY", new BigDecimal("10000"), "KRW");
        orchestrator.refundPayment("pay_1", SCHEME_TXN, PartnerType.OVERSEAS, PARTNER, TXN,
                "R2", "ZEROPAY", new BigDecimal("20000"), "KRW");

        assertEquals(0, fresh.netDebited().signum(),
                "the refund that completes the payment takes the exact remainder, so no crumb is stranded");
    }

    @Nested
    @DisplayName("the cumulative guard")
    class CumulativeGuard {

        @Test
        @DisplayName("cumulative partial refunds may not exceed the original — the excess is rejected")
        void cumulativePartials_cannotExceedTheOriginal() {
            LedgerPrefunding prefunding = new LedgerPrefunding().seedCapture(TXN, CAPTURED_USD);
            RecordingScheme scheme = new RecordingScheme();
            BasisTransactionClient txn = new BasisTransactionClient(basis(null));
            PaymentOrchestrator orchestrator =
                    orchestrator(prefunding, scheme, txn, new RecordingLedger());

            orchestrator.refundPayment("pay_1", SCHEME_TXN, PartnerType.OVERSEAS, PARTNER, TXN,
                    "R1", "ZEROPAY", new BigDecimal("20000"), "KRW");
            orchestrator.refundPayment("pay_1", SCHEME_TXN, PartnerType.OVERSEAS, PARTNER, TXN,
                    "R2", "ZEROPAY", new BigDecimal("20000"), "KRW");
            int schemeCallsBefore = scheme.cancelCount;
            int patchesBefore = txn.patches.size();

            // 20 000 + 20 000 + 20 000 = 60 000 > the 50 000 collected.
            RefundAmountInvalidException ex = assertThrows(RefundAmountInvalidException.class,
                    () -> orchestrator.refundPayment("pay_1", SCHEME_TXN, PartnerType.OVERSEAS, PARTNER,
                            TXN, "R3", "ZEROPAY", new BigDecimal("20000"), "KRW"));

            assertEquals(RefundAmountInvalidException.CODE_EXCEEDS_ORIGINAL, ex.code(),
                    "a structured, machine-readable over-refund rejection");
            assertEquals(false, ex.retryable(), "retrying an over-refund can never succeed");
            assertTrue(ex.getMessage().contains("10000"), "the message states what IS still refundable");

            // Rejected BEFORE anything moved.
            assertEquals(schemeCallsBefore, scheme.cancelCount, "no scheme call on an over-refund");
            assertEquals(patchesBefore, txn.patches.size(), "no status write on an over-refund");
            assertEquals(0, prefunding.netDebited().compareTo(new BigDecimal("7.5000")),
                    "the float is untouched by the rejected refund (80% refunded, 20% still held)");
        }

        @Test
        @DisplayName("a single refund larger than the original is rejected")
        void singleOverRefund_isRejected() {
            RefundAmountInvalidException ex = assertThrows(RefundAmountInvalidException.class,
                    () -> orchestrator(new LedgerPrefunding().seedCapture(TXN, CAPTURED_USD),
                            new RecordingScheme(), new BasisTransactionClient(basis(null)),
                            new RecordingLedger())
                            .refundPayment("pay_1", SCHEME_TXN, PartnerType.OVERSEAS, PARTNER, TXN,
                                    "R", "ZEROPAY", new BigDecimal("50001"), "KRW"));
            assertEquals(RefundAmountInvalidException.CODE_EXCEEDS_ORIGINAL, ex.code());
        }

        @Test
        @DisplayName("a fully refunded payment cannot be refunded again")
        void alreadyFullyRefunded_cannotRefundAgain() {
            RefundAmountInvalidException ex = assertThrows(RefundAmountInvalidException.class,
                    () -> orchestrator(new LedgerPrefunding(), new RecordingScheme(),
                            new BasisTransactionClient(basis(ORIGINAL_KRW)), new RecordingLedger())
                            .refundPayment("pay_1", SCHEME_TXN, PartnerType.OVERSEAS, PARTNER, TXN,
                                    "R", "ZEROPAY"));
            assertEquals(RefundAmountInvalidException.CODE_EXCEEDS_ORIGINAL, ex.code());
        }

        @Test
        @DisplayName("a non-positive amount, and a foreign currency, are both rejected structurally")
        void invalidAmounts_areRejected() {
            PaymentOrchestrator orchestrator = orchestrator(
                    new LedgerPrefunding().seedCapture(TXN, CAPTURED_USD), new RecordingScheme(),
                    new BasisTransactionClient(basis(null)), new RecordingLedger());

            assertEquals(RefundAmountInvalidException.CODE_INVALID,
                    assertThrows(RefundAmountInvalidException.class,
                            () -> orchestrator.refundPayment("pay_1", SCHEME_TXN, PartnerType.OVERSEAS,
                                    PARTNER, TXN, "R", "ZEROPAY", BigDecimal.ZERO, "KRW")).code());

            // A refund reverses the ORIGINAL booking; converting USD→KRW here would need a rate, and any
            // rate we picked would not be the locked one. So it is refused, never converted.
            assertEquals(RefundAmountInvalidException.CODE_INVALID,
                    assertThrows(RefundAmountInvalidException.class,
                            () -> orchestrator.refundPayment("pay_1", SCHEME_TXN, PartnerType.OVERSEAS,
                                    PARTNER, TXN, "R", "ZEROPAY", new BigDecimal("10"), "USD")).code());
        }

        @Test
        @DisplayName("a PARTIAL refund is refused when the original payment cannot be read; a FULL one is not")
        void unreadableBasis_blocksPartialOnly() {
            TransactionClient noBasis = new TransactionClient() {
                final List<StatusPatch> patches = new ArrayList<>();

                @Override
                public CreateResult createPending(CreateRequest request) {
                    return new CreateResult(TXN, "pay_1", Instant.now());
                }

                @Override
                public void commitStatus(String txnRef, StatusPatch patch) {
                    patches.add(patch);
                }
                // findRefundBasis: the interface default — Optional.empty()
            };

            RefundAmountInvalidException ex = assertThrows(RefundAmountInvalidException.class,
                    () -> orchestrator(new LedgerPrefunding().seedCapture(TXN, CAPTURED_USD),
                            new RecordingScheme(), noBasis, new RecordingLedger())
                            .refundPayment("pay_1", SCHEME_TXN, PartnerType.OVERSEAS, PARTNER, TXN,
                                    "R", "ZEROPAY", new BigDecimal("100"), "KRW"));
            assertEquals(RefundAmountInvalidException.CODE_BASIS_UNAVAILABLE, ex.code());
            assertTrue(ex.retryable(), "an unreadable original is an availability failure, so retryable");

            // The legacy full refund still works against a client that cannot answer the read at all.
            LedgerPrefunding prefunding = new LedgerPrefunding().seedCapture(TXN, CAPTURED_USD);
            PaymentOrchestrator.RefundResult full = orchestrator(prefunding, new RecordingScheme(),
                    noBasis, new RecordingLedger())
                    .refundPayment("pay_1", SCHEME_TXN, PartnerType.OVERSEAS, PARTNER, TXN, "R", "ZEROPAY");
            assertEquals(PaymentStatus.REFUNDED, full.status());
            assertEquals(0, prefunding.netDebited().signum());
        }
    }

    // ======================================================================
    // Defect 2 — refundAmountKrw was never populated, so the settlement
    // claw-back read null and netted nothing.
    // ======================================================================

    @Test
    @DisplayName("the REFUNDED commit carries the CUMULATIVE refunded KRW, so the claw-back has a magnitude")
    void refundAmountKrw_isCarriedOnTheCommit() {
        BasisTransactionClient txn = new BasisTransactionClient(basis(null));
        PaymentOrchestrator orchestrator = orchestrator(
                new LedgerPrefunding().seedCapture(TXN, CAPTURED_USD), new RecordingScheme(), txn,
                new RecordingLedger());

        orchestrator.refundPayment("pay_1", SCHEME_TXN, PartnerType.OVERSEAS, PARTNER, TXN,
                "R1", "ZEROPAY", new BigDecimal("20000"), "KRW");
        assertEquals(PaymentStatus.REFUNDED, txn.lastPatch().newStatus());
        assertEquals(0, new BigDecimal("20000").compareTo(txn.lastPatch().refundAmountKrw()));

        orchestrator.refundPayment("pay_1", SCHEME_TXN, PartnerType.OVERSEAS, PARTNER, TXN,
                "R2", "ZEROPAY", new BigDecimal("5000"), "KRW");
        assertEquals(0, new BigDecimal("25000").compareTo(txn.lastPatch().refundAmountKrw()),
                "cumulative, not per-refund: the claw-back needs the total owed back");
    }

    @Test
    @DisplayName("a non-KRW collection currency leaves refundAmountKrw NULL rather than mislabelling it")
    void nonKrwRefund_doesNotWriteAForeignAmountIntoTheKrwColumn() {
        BasisTransactionClient txn = new BasisTransactionClient(
                new TransactionClient.RefundBasis(TXN, "APPROVED", new BigDecimal("100.00"), "USD",
                        new BigDecimal("100.0000"), null));

        orchestrator(new LedgerPrefunding().seedCapture(TXN, new BigDecimal("100.0000")),
                new RecordingScheme(), txn, new RecordingLedger())
                .refundPayment("pay_1", SCHEME_TXN, PartnerType.OVERSEAS, PARTNER, TXN,
                        "R", "ZEROPAY", new BigDecimal("40.00"), "USD");

        assertNull(txn.lastPatch().refundAmountKrw(),
                "settlement treats that column as KRW; writing USD into it would corrupt the file");
    }

    // ======================================================================
    // The reversal journal: a LOCAL partner's refund used to be booked NOWHERE
    // (the journal was posted only from the float amount, which LOCAL has none of).
    // ======================================================================

    @Test
    @DisplayName("a LOCAL partner's refund books a real reversal in the collection currency")
    void localPartnerRefund_isBookedInTheCollectionCurrency() {
        RecordingLedger ledger = new RecordingLedger();
        BasisTransactionClient txn = new BasisTransactionClient(basis(null));

        orchestrator(new LedgerPrefunding(), new RecordingScheme(), txn, ledger)
                .refundPayment("pay_1", SCHEME_TXN, PartnerType.LOCAL, PARTNER, TXN,
                        "R", "ZEROPAY", new BigDecimal("20000"), "KRW");

        assertEquals(1, ledger.reversals.size(), "a LOCAL refund is no longer booked nowhere");
        assertEquals(TXN, ledger.reversals.get(0).reference());
        assertEquals(0, ledger.reversals.get(0).amount().compareTo(new BigDecimal("20000")));
        assertEquals("KRW", ledger.reversals.get(0).currency());
    }

    // ======================================================================
    // T2-7 regression: an unsupported corridor still refuses before anything moves,
    // now also when an amount is supplied.
    // ======================================================================

    @Test
    @DisplayName("a scheme with no refund round-trip refuses a PARTIAL refund before any money moves")
    void unsupportedScheme_refusesPartialBeforeAnySideEffect() {
        LedgerPrefunding prefunding = new LedgerPrefunding().seedCapture(TXN, CAPTURED_USD);
        BasisTransactionClient txn = new BasisTransactionClient(basis(null));
        SchemeClient unsupported = new SchemeClient() {
            @Override public MpmSubmitResponse submitMpm(MpmSubmitRequest r) {
                throw new UnsupportedOperationException();
            }
            @Override public void cancelPayment(String schemeTxnRef, String reason) {
                throw new SchemeOperationNotSupportedException("SENDMN", "cancelPayment", "single-shot");
            }
            @Override public CpmSubmitResponse submitCpm(CpmSubmitRequest r) {
                throw new UnsupportedOperationException();
            }
        };

        assertThrows(SchemeOperationNotSupportedException.class,
                () -> orchestrator(prefunding, unsupported, txn, new RecordingLedger())
                        .refundPayment("pay_1", "SMN-1", PartnerType.OVERSEAS, PARTNER, TXN,
                                "R", "SENDMN", new BigDecimal("10000"), "KRW"));

        assertEquals(0, prefunding.netDebited().compareTo(CAPTURED_USD), "no float moved");
        assertTrue(txn.patches.isEmpty(), "no status written");
    }

    @Test
    @DisplayName("a partial refund is refused when the adapter cannot express one — nothing is mutated")
    void partialRefundUnsupportedByAdapter_refusesBeforeAnySideEffect() {
        LedgerPrefunding prefunding = new LedgerPrefunding().seedCapture(TXN, CAPTURED_USD);
        BasisTransactionClient txn = new BasisTransactionClient(basis(null));
        RecordingLedger ledger = new RecordingLedger();
        SchemeClient noPartial = new SchemeClient() {
            @Override public MpmSubmitResponse submitMpm(MpmSubmitRequest r) {
                throw new UnsupportedOperationException();
            }
            @Override public void cancelPayment(String schemeTxnRef, String reason) { }
            @Override public void cancelPayment(CancelRequest request) {
                if (request.isPartial()) {
                    throw new PartialRefundNotSupportedException("ZEROPAY",
                            request.partialAmount(), request.partialCurrency());
                }
            }
            @Override public CpmSubmitResponse submitCpm(CpmSubmitRequest r) {
                throw new UnsupportedOperationException();
            }
        };

        PartialRefundNotSupportedException ex = assertThrows(PartialRefundNotSupportedException.class,
                () -> orchestrator(prefunding, noPartial, txn, ledger)
                        .refundPayment("pay_1", SCHEME_TXN, PartnerType.OVERSEAS, PARTNER, TXN,
                                "R", "ZEROPAY", new BigDecimal("10000"), "KRW"));

        assertEquals(PartialRefundNotSupportedException.CODE, ex.code());
        assertEquals(0, prefunding.netDebited().compareTo(CAPTURED_USD),
                "refusing is the point: no float moved, so no over-refund at the scheme is possible");
        assertTrue(txn.patches.isEmpty());
        assertTrue(ledger.reversals.isEmpty());

        // The same payment refunded in FULL through the same adapter still works.
        PaymentOrchestrator.RefundResult full = orchestrator(prefunding, noPartial, txn, ledger)
                .refundPayment("pay_1", SCHEME_TXN, PartnerType.OVERSEAS, PARTNER, TXN, "R", "ZEROPAY");
        assertEquals(PaymentStatus.REFUNDED, full.status());
    }

    /** Guards the record-shape contract the fakes above rely on. */
    @Test
    @DisplayName("RefundBasis reports what is still refundable")
    void refundBasis_reportsRemaining() {
        TransactionClient.RefundBasis b = basis(new BigDecimal("20000"));
        assertEquals(0, b.alreadyRefunded().compareTo(new BigDecimal("20000")));
        assertEquals(0, b.refundableRemaining().compareTo(new BigDecimal("30000")));
        assertEquals(0, basis(null).alreadyRefunded().signum(), "absent = nothing refunded");
    }
}
