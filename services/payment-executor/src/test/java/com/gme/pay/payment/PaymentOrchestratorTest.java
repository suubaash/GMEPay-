package com.gme.pay.payment;

import com.gme.pay.payment.domain.InsufficientPrefundingException;
import com.gme.pay.payment.domain.PartnerType;
import com.gme.pay.payment.domain.PaymentOrchestrator;
import com.gme.pay.payment.domain.PaymentOrchestrator.MpmPaymentCommand;
import com.gme.pay.payment.domain.PaymentOrchestrator.PaymentResult;
import com.gme.pay.payment.domain.PaymentStatus;
import com.gme.pay.payment.domain.QuoteAmountMismatchException;
import com.gme.pay.payment.domain.SchemeBalanceUnavailableException;
import com.gme.pay.payment.domain.SchemeDeclinedException;
import com.gme.pay.payment.domain.client.PrefundingClient;
import com.gme.pay.payment.domain.client.QrClient;
import com.gme.pay.payment.domain.client.RateClient;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.TransactionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Plain JUnit 5 unit tests for {@link PaymentOrchestrator}.
 * No Spring context, no Docker, no Testcontainers.
 * All collaborators are hand-written fakes that record call order.
 */
class PaymentOrchestratorTest {

    // ---- hand-written fakes ----

    /** Records the sequence of service calls so tests can assert ordering. */
    private final List<String> callLog = new ArrayList<>();

    private final RateClient fakeRate = (quoteId, partnerId) -> {
        callLog.add("RATE:" + quoteId);
        return new RateClient.RateQuoteView(
                quoteId, partnerId, "zeropay", "inbound",
                new BigDecimal("50000"), "KRW",
                new BigDecimal("37.015197"), new BigDecimal("35.562589"),
                new BigDecimal("0.925380"), new BigDecimal("0.370152"),
                new BigDecimal("37.015197"), new BigDecimal("0.35"),
                new BigDecimal("37.365197"), "USD",
                new BigDecimal("1.025610"), new BigDecimal("1351.00"),
                Instant.now().plusSeconds(300), false
        );
    };

    private final QrClient fakeQr = qr -> {
        callLog.add("QR:" + qr);
        return QrClient.MerchantView.of("M001", "Test Merchant", "KRW", "zeropay");
    };

    private final TransactionClient fakeTxn = new TransactionClient() {
        @Override
        public CreateResult createPending(CreateRequest req) {
            callLog.add("TXN:CREATE");
            return new CreateResult("txn_001", "pay_001", Instant.now());
        }

        @Override
        public void commitStatus(String txnRef, StatusPatch patch) {
            callLog.add("TXN:COMMIT:" + patch.newStatus().name());
        }
    };

    /** Normal prefunding fake — deduction succeeds. */
    private final PrefundingClient fakePrefunding = new PrefundingClient() {
        @Override
        public DeductionResult deduct(long partnerId, String txnRef, BigDecimal amountUsd) {
            callLog.add("PREFUND:DEDUCT");
            return new DeductionResult(amountUsd, new BigDecimal("962.985"));
        }

        @Override
        public ReverseResult reverse(long partnerId, String txnRef) {
            callLog.add("PREFUND:REVERSE");
            return new ReverseResult(new BigDecimal("125.50"), new BigDecimal("1088.485"));
        }
    };

    /** Prefunding fake that always rejects (balance too low). */
    private final PrefundingClient insufficientPrefunding = new PrefundingClient() {
        @Override
        public DeductionResult deduct(long partnerId, String txnRef, BigDecimal amountUsd) {
            callLog.add("PREFUND:DEDUCT_FAIL");
            throw new InsufficientPrefundingException(new BigDecimal("5.00"), amountUsd);
        }

        @Override
        public ReverseResult reverse(long partnerId, String txnRef) {
            callLog.add("PREFUND:REVERSE");
            return new ReverseResult(new BigDecimal("125.50"), new BigDecimal("1088.485"));
        }
    };

    /** Scheme fake that returns an approval. */
    private final SchemeClient fakeScheme = new SchemeClient() {
        @Override
        public MpmSubmitResponse submitMpm(MpmSubmitRequest req) {
            callLog.add("SCHEME:SUBMIT");
            return new MpmSubmitResponse(
                    "ZP20260608093115001234",
                    "ZP_TXN_" + req.txnRef(),
                    Instant.now()
            );
        }

        @Override
        public void cancelPayment(String schemeTxnRef, String reason) {
            callLog.add("SCHEME:CANCEL");
        }

        @Override
        public CpmSubmitResponse submitCpm(CpmSubmitRequest req) {
            callLog.add("SCHEME:CPM_SUBMIT");
            return new CpmSubmitResponse("ZP_CPM_APPROVAL", "ZP_CPM_TXN", Instant.now());
        }
    };

    /** Scheme fake that always declines. */
    private final SchemeClient decliningScheme = new SchemeClient() {
        @Override
        public MpmSubmitResponse submitMpm(MpmSubmitRequest req) {
            callLog.add("SCHEME:SUBMIT_DECLINE");
            throw new SchemeDeclinedException("ZP_ERR_001", "Transaction declined by scheme");
        }

        @Override
        public void cancelPayment(String schemeTxnRef, String reason) {}

        @Override
        public CpmSubmitResponse submitCpm(CpmSubmitRequest req) {
            throw new SchemeDeclinedException("ZP_ERR_CPM_001", "CPM declined");
        }
    };

    private MpmPaymentCommand sampleCommand;

    @BeforeEach
    void setUp() {
        callLog.clear();
        sampleCommand = new MpmPaymentCommand(
                42L, "qte_01HX7MNP9AB2CDEF3GH456IJ",
                "ZPQR00012345678901234567890",
                "zeropay", "inbound",
                "customer-001", "PTNR_TXN_001", "PARTNER_42",
                // matches the fake quote's collectionAmount/collectionCurrency
                new BigDecimal("37.365197"), "USD"
        );
    }

    // ======================================================================
    // Authorize gate: insufficient partner float → scheme is NEVER contacted
    // (the two-phase replacement for the retired single-shot insufficient test)
    // ======================================================================

    @Test
    @DisplayName("Authorize: insufficient partner float → scheme never balance-checked or submitted")
    void authorize_insufficientFloat_schemeNeverContacted() {
        PrefundingClient insufficientReserve = new PrefundingClient() {
            @Override
            public DeductionResult deduct(long p, String t, BigDecimal a) {
                throw new AssertionError("deduct must not be called in the two-phase flow");
            }
            @Override
            public ReverseResult reverse(long p, String t) {
                return new ReverseResult(BigDecimal.ZERO, null);
            }
            @Override
            public ReservationResult reserve(long p, String t, BigDecimal a) {
                callLog.add("PREFUND:RESERVE_FAIL");
                throw new InsufficientPrefundingException(new BigDecimal("5.00"), a);
            }
        };
        SchemeClient schemeMustNotBeTouched = new SchemeClient() {
            @Override
            public MpmSubmitResponse submitMpm(MpmSubmitRequest req) {
                throw new AssertionError("scheme submit must NOT happen on insufficient float");
            }
            @Override public void cancelPayment(String s, String r) { }
            @Override public CpmSubmitResponse submitCpm(CpmSubmitRequest req) { return null; }
            @Override
            public BalanceCheckResult checkBalance(String s, BigDecimal a, String c) {
                throw new AssertionError("scheme balance-check must NOT happen on insufficient float");
            }
        };
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, insufficientReserve, fakeQr, schemeMustNotBeTouched, fakeTxn);

        assertThrows(InsufficientPrefundingException.class,
                () -> orchestrator.authorizeMpm(sampleCommand, PartnerType.OVERSEAS));

        assertPresent("PREFUND:RESERVE_FAIL");
        assertAbsent("SCHEME:SUBMIT", "scheme must never be submitted when the float is short");
    }

    // ======================================================================
    // Test 5: OVERSEAS cancel records the REAL reversed USD + posts a reversal journal (P1-2)
    // ======================================================================

    @Test
    @DisplayName("OVERSEAS cancel: reverses the real prefund USD and posts a reversal journal")
    void overseas_cancel_reversesRealUsdAndPostsReversalJournal() {
        java.util.concurrent.atomic.AtomicReference<String> revRef = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<BigDecimal> revAmt = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> revCcy = new java.util.concurrent.atomic.AtomicReference<>();
        com.gme.pay.payment.domain.client.RevenueLedgerClient recordingLedger =
                new com.gme.pay.payment.domain.client.RevenueLedgerClient() {
                    @Override
                    public void postRoundingResidual(String reference, BigDecimal residual, String currency) { }
                    @Override
                    public void postReversalJournal(String reference, BigDecimal reversalAmount, String currency) {
                        revRef.set(reference);
                        revAmt.set(reversalAmount);
                        revCcy.set(currency);
                    }
                };

        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, fakePrefunding, fakeQr, fakeScheme, fakeTxn, null, recordingLedger);

        PaymentOrchestrator.CancelResult result = orchestrator.cancelPayment(
                "pay_1", "ZP_TXN_1", PartnerType.OVERSEAS, 42L, "txn_1", "PARTNER_INITIATED");

        // The cancel result carries the ACTUAL reversed USD (fakePrefunding returns 125.50), not ZERO.
        assertEquals(0, result.prefundReturnedUsd().compareTo(new BigDecimal("125.50")),
                "cancel must surface the real reversed prefund USD");
        // A structured reversal journal was posted to revenue-ledger for that amount.
        assertEquals("txn_1", revRef.get(), "reversal journal keyed by txnRef");
        assertEquals(0, revAmt.get().compareTo(new BigDecimal("125.50")));
        assertEquals("USD", revCcy.get());
    }

    // ======================================================================
    // Step 9: refund — full reversal of an APPROVED txn at the locked rate → REFUNDED
    // ======================================================================

    @Test
    @DisplayName("OVERSEAS refund: reverses the locked-rate prefund USD, posts a reversal journal, commits REFUNDED")
    void overseas_refund_reversesAtLockedRateAndCommitsRefunded() {
        java.util.concurrent.atomic.AtomicReference<String> revRef = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<BigDecimal> revAmt = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<PaymentStatus> committed = new java.util.concurrent.atomic.AtomicReference<>();

        com.gme.pay.payment.domain.client.RevenueLedgerClient recordingLedger =
                new com.gme.pay.payment.domain.client.RevenueLedgerClient() {
                    @Override
                    public void postRoundingResidual(String reference, BigDecimal residual, String currency) { }
                    @Override
                    public void postReversalJournal(String reference, BigDecimal reversalAmount, String currency) {
                        revRef.set(reference);
                        revAmt.set(reversalAmount);
                    }
                };
        TransactionClient capturingTxn = new TransactionClient() {
            @Override
            public CreateResult createPending(CreateRequest req) {
                return new CreateResult("txn_1", "pay_1", Instant.now());
            }
            @Override
            public void commitStatus(String txnRef, StatusPatch patch) {
                committed.set(patch.newStatus());
            }
        };

        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, fakePrefunding, fakeQr, fakeScheme, capturingTxn, null, recordingLedger);

        PaymentOrchestrator.RefundResult result = orchestrator.refundPayment(
                "pay_1", "ZP_TXN_1", PartnerType.OVERSEAS, 42L, "txn_1", "PARTNER_INITIATED");

        assertEquals(PaymentStatus.REFUNDED, result.status());
        assertEquals(0, result.prefundReturnedUsd().compareTo(new BigDecimal("125.50")),
                "refund credits the locked-rate prefund USD back");
        assertEquals(PaymentStatus.REFUNDED, committed.get(), "txn committed REFUNDED (not REVERSED)");
        assertEquals("txn_1", revRef.get(), "reversal journal keyed by txnRef");
        assertEquals(0, revAmt.get().compareTo(new BigDecimal("125.50")));
    }

    // ======================================================================
    // Test 6: Partner amount agreement — a collection_amount that disagrees with
    // the locked quote is rejected BEFORE any side effect (no txn, no deduct, no scheme).
    // ======================================================================

    @Test
    @DisplayName("Amount mismatch: rejected before any side effect")
    void mpm_amountMismatch_rejectedBeforeAnySideEffect() {
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, fakePrefunding, fakeQr, fakeScheme, fakeTxn);

        // Quote's collectionAmount is 37.365197 USD; the partner asserts a different amount.
        MpmPaymentCommand mismatched = new MpmPaymentCommand(
                42L, "qte_01HX7MNP9AB2CDEF3GH456IJ", "ZPQR00012345678901234567890",
                "zeropay", "inbound", "customer-001", "PTNR_TXN_001", "PARTNER_42",
                new BigDecimal("99.99"), "USD");

        assertThrows(QuoteAmountMismatchException.class,
                () -> orchestrator.authorizeMpm(mismatched, PartnerType.OVERSEAS));

        // The quote was loaded (the check runs right after), but NOTHING with a side effect ran.
        assertPresent("RATE:qte_01HX7MNP9AB2CDEF3GH456IJ");
        assertAbsent("QR:ZPQR00012345678901234567890", "merchant must not be resolved on mismatch");
        assertAbsent("TXN:CREATE", "no transaction may be created on amount mismatch");
        assertAbsent("PREFUND:DEDUCT", "prefunding must not be deducted on amount mismatch");
        assertAbsent("SCHEME:SUBMIT", "scheme must never be called on amount mismatch");
    }

    @Test
    @DisplayName("Currency mismatch: rejected before any side effect")
    void mpm_currencyMismatch_rejected() {
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, fakePrefunding, fakeQr, fakeScheme, fakeTxn);

        // Amount matches the quote but the currency does not (quote is USD).
        MpmPaymentCommand wrongCcy = new MpmPaymentCommand(
                42L, "qte_01HX7MNP9AB2CDEF3GH456IJ", "ZPQR00012345678901234567890",
                "zeropay", "inbound", "customer-001", "PTNR_TXN_001", "PARTNER_42",
                new BigDecimal("37.365197"), "KRW");

        assertThrows(QuoteAmountMismatchException.class,
                () -> orchestrator.authorizeMpm(wrongCcy, PartnerType.OVERSEAS));
        assertAbsent("SCHEME:SUBMIT", "scheme must never be called on currency mismatch");
    }

    @Test
    @DisplayName("Matching amount/currency (differing scale): agreement passes authorize")
    void mpm_matchingAmount_proceeds() {
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, twoPhasePrefunding(), fakeQr, fakeScheme, fakeTxn);

        // Same value as the quote (37.365197) but extra scale + lowercase ccy — compareTo +
        // equalsIgnoreCase match, so authorize proceeds and RESERVES (it never submits to the scheme).
        MpmPaymentCommand matched = new MpmPaymentCommand(
                42L, "qte_01HX7MNP9AB2CDEF3GH456IJ", "ZPQR00012345678901234567890",
                "zeropay", "inbound", "customer-001", "PTNR_TXN_001", "PARTNER_42",
                new BigDecimal("37.36519700"), "usd");

        PaymentOrchestrator.AuthorizeResult auth =
                orchestrator.authorizeMpm(matched, PartnerType.OVERSEAS);

        assertNotNull(auth.txnRef());
        assertPresent("PREFUND:RESERVE");
        assertAbsent("SCHEME:SUBMIT", "authorize never submits to the scheme");
    }

    @Test
    @DisplayName("Null asserted amount: agreement check is skipped (internal/legacy caller)")
    void mpm_nullAmount_skipsCheck() {
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, twoPhasePrefunding(), fakeQr, fakeScheme, fakeTxn);

        MpmPaymentCommand noAmount = new MpmPaymentCommand(
                42L, "qte_01HX7MNP9AB2CDEF3GH456IJ", "ZPQR00012345678901234567890",
                "zeropay", "inbound", "customer-001", "PTNR_TXN_001", "PARTNER_42",
                null, null);

        PaymentOrchestrator.AuthorizeResult auth =
                orchestrator.authorizeMpm(noAmount, PartnerType.OVERSEAS);
        assertNotNull(auth.txnRef());
    }

    // ======================================================================
    // Test 7-8: Two-phase spine (SETTLEMENT_FLOW_SPEC §4/§7.1) — authorize RESERVES
    // the float and never calls the scheme; confirm SUBMITS then CAPTURES.
    // ======================================================================

    /** Prefunding fake that records reserve/capture/release and forbids the legacy deduct. */
    private PrefundingClient twoPhasePrefunding() {
        return new PrefundingClient() {
            @Override
            public DeductionResult deduct(long p, String t, BigDecimal a) {
                throw new AssertionError("deduct must NOT be called in the two-phase flow");
            }
            @Override
            public ReverseResult reverse(long p, String t) {
                callLog.add("PREFUND:REVERSE");
                return new ReverseResult(BigDecimal.ZERO, null);
            }
            @Override
            public ReservationResult reserve(long p, String t, BigDecimal a) {
                callLog.add("PREFUND:RESERVE");
                return new ReservationResult(a, new BigDecimal("962.21"), new BigDecimal("1000.00"));
            }
            @Override
            public CaptureResult capture(long p, String t) {
                callLog.add("PREFUND:CAPTURE");
                return new CaptureResult(new BigDecimal("37.79"), new BigDecimal("962.21"));
            }
            @Override
            public ReleaseResult release(long p, String t) {
                callLog.add("PREFUND:RELEASE");
                return new ReleaseResult(new BigDecimal("37.79"), new BigDecimal("1000.00"));
            }
        };
    }

    private PaymentOrchestrator.ConfirmContext ctxFrom(
            PaymentOrchestrator.AuthorizeResult a, MpmPaymentCommand cmd) {
        var q = a.quote();
        var m = a.merchant();
        return new PaymentOrchestrator.ConfirmContext(
                cmd.partnerId(), PartnerType.OVERSEAS, cmd.partnerCode(), cmd.partnerTxnRef(),
                a.txnRef(), a.paymentId(), cmd.schemeId(), cmd.merchantQr(),
                m.merchantId(), m.merchantName(), q.targetPayout(), q.payoutCurrency(),
                q.collectionAmount(), q.collectionCurrency(), a.reservedUsd(), q.offerRateColl(),
                q.collectionMarginUsd(), q.payoutMarginUsd(), q.serviceCharge(),
                cmd.direction(), a.merchantFeeRate(), a.createdAt());
    }

    @Test
    @DisplayName("Two-phase: authorize RESERVES + never calls the scheme; confirm SUBMITS then CAPTURES")
    void twoPhase_authorizeReserves_confirmSubmitsThenCaptures() {
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, twoPhasePrefunding(), fakeQr, fakeScheme, fakeTxn);

        PaymentOrchestrator.AuthorizeResult auth =
                orchestrator.authorizeMpm(sampleCommand, PartnerType.OVERSEAS);

        assertNotNull(auth.txnRef());
        assertNotNull(auth.paymentId());
        assertPresent("PREFUND:RESERVE");
        // THE non-negotiable: the scheme is never touched during authorize.
        assertAbsent("SCHEME:SUBMIT", "scheme must NOT be called during authorize");

        PaymentResult result = orchestrator.confirmMpm(ctxFrom(auth, sampleCommand));

        assertEquals(PaymentStatus.APPROVED, result.status());
        assertPresent("SCHEME:SUBMIT");
        assertPresent("PREFUND:CAPTURE");
        assertOrder("RESERVE before SUBMIT",
                indexOf("PREFUND:RESERVE"), indexOf("SCHEME:SUBMIT"));
        assertOrder("SUBMIT before CAPTURE",
                indexOf("SCHEME:SUBMIT"), indexOf("PREFUND:CAPTURE"));
    }

    // ======================================================================
    // Step 6 (SETTLEMENT_FLOW_SPEC §D10/§7.4): the float hold must include the
    // service fee, so the partner-float debit reconciles with the partner's
    // agreed collectionAmount (and the settlement booking). Previously the hold
    // was collectionUsd alone — the service fee was dropped from float accounting.
    // ======================================================================

    @Test
    @DisplayName("Step 6: authorize folds the service fee into the float hold (= agreed collectionAmount in USD)")
    void authorize_foldsServiceFeeIntoFloatHold() {
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, twoPhasePrefunding(), fakeQr, fakeScheme, fakeTxn);

        PaymentOrchestrator.AuthorizeResult auth =
                orchestrator.authorizeMpm(sampleCommand, PartnerType.OVERSEAS);

        // Quote: collectionUsd=37.015197, serviceCharge=0.35 (collection ccy == USD,
        // sendAmount=37.015197) => serviceFeeUsd=0.35, hold=37.365197 = the agreed
        // collectionAmount. Pre-fix the hold was 37.015197 (fee dropped).
        assertEquals(0, auth.reservedUsd().compareTo(new BigDecimal("37.365197")),
                "float hold must equal collectionUsd + serviceFeeUsd (the agreed collectionAmount in USD)");
    }

    @Test
    @DisplayName("Step 6: service fee in a non-USD collection currency is converted to USD for the float hold")
    void authorize_foldsServiceFee_convertsCollectionCcyToUsd() {
        // A quote where the collection currency is MNT: 1 USD = 2 MNT (offerRateColl=2).
        // collectionUsd=37.015197 USD, sendAmount=74.030394 MNT, serviceCharge=0.70 MNT,
        // collectionAmount=74.730394 MNT. serviceFeeUsd = 0.70 * 37.015197/74.030394 = 0.35,
        // so the USD hold is 37.365197 — the service fee correctly converted from MNT.
        RateClient mntRate = (quoteId, partnerId) -> new RateClient.RateQuoteView(
                quoteId, partnerId, "zeropay", "inbound",
                new BigDecimal("50000"), "KRW",
                new BigDecimal("37.015197"), new BigDecimal("35.562589"),
                new BigDecimal("0.925380"), new BigDecimal("0.370152"),
                new BigDecimal("74.030394"), new BigDecimal("0.70"),
                new BigDecimal("74.730394"), "MNT",
                new BigDecimal("2.00"), new BigDecimal("1351.00"),
                Instant.now().plusSeconds(300), false);
        MpmPaymentCommand mntCmd = new MpmPaymentCommand(
                42L, "qte_01HX7MNP9AB2CDEF3GH456IJ", "ZPQR00012345678901234567890",
                "zeropay", "inbound", "customer-001", "PTNR_TXN_001", "PARTNER_42",
                new BigDecimal("74.730394"), "MNT");

        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                mntRate, twoPhasePrefunding(), fakeQr, fakeScheme, fakeTxn);

        PaymentOrchestrator.AuthorizeResult auth =
                orchestrator.authorizeMpm(mntCmd, PartnerType.OVERSEAS);

        assertEquals(0, auth.reservedUsd().compareTo(new BigDecimal("37.365197")),
                "MNT service fee (0.70) must convert to 0.35 USD and fold into the hold");
    }

    // ======================================================================
    // Step 7 (task #102): confirm threads the commission split into revenue posting —
    // resolves the configurable two-sided shares + posts the snapshotted inputs.
    // ======================================================================

    @Test
    @DisplayName("Step 7: confirm posts the commission split with resolved shares + snapshotted merchant fee")
    void confirm_postsCommissionSplit() {
        java.util.concurrent.atomic.AtomicReference<Long> capPayoutKrw =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<BigDecimal> capMerchantFee =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<BigDecimal> capVan =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<BigDecimal> capGme =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<BigDecimal> capPartner =
                new java.util.concurrent.atomic.AtomicReference<>();

        com.gme.pay.payment.domain.client.RevenueLedgerClient recordingLedger =
                new com.gme.pay.payment.domain.client.RevenueLedgerClient() {
            @Override
            public void postRoundingResidual(String reference, BigDecimal residual, String currency) { }
            @Override
            public void postCommissionSplit(String txnRef, long partnerId, long schemeId,
                    java.time.LocalDate revenueDate, long payoutAmountKrw, BigDecimal merchantFeeRate,
                    BigDecimal vanFeeRate, BigDecimal gmeSharePct, BigDecimal partnerSharePct) {
                callLog.add("LEDGER:COMMISSION_SPLIT");
                capPayoutKrw.set(payoutAmountKrw);
                capMerchantFee.set(merchantFeeRate);
                capVan.set(vanFeeRate);
                capGme.set(gmeSharePct);
                capPartner.set(partnerSharePct);
            }
        };

        com.gme.pay.payment.domain.client.PartnerConfigClient fakePartnerConfig =
                new com.gme.pay.payment.domain.client.PartnerConfigClient() {
            @Override
            public PartnerConfigView loadPartner(String id) {
                return new PartnerConfigView(id, "OVERSEAS", "USD", java.math.RoundingMode.HALF_UP);
            }
            @Override
            public java.util.Optional<BigDecimal> resolveMerchantFeeRate(String schemeId, String merchantType) {
                return java.util.Optional.of(new BigDecimal("0.0080")); // snapshotted at authorize
            }
            @Override
            public java.util.Optional<CommissionSplitConfig> resolveCommissionSplit(
                    String schemeId, String partnerCode, String direction) {
                return java.util.Optional.of(new CommissionSplitConfig(
                        new BigDecimal("0.70"), new BigDecimal("0.0008"), new BigDecimal("0.30")));
            }
        };

        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, twoPhasePrefunding(), fakeQr, fakeScheme, fakeTxn,
                null, recordingLedger, fakePartnerConfig);

        PaymentOrchestrator.AuthorizeResult auth =
                orchestrator.authorizeMpm(sampleCommand, PartnerType.OVERSEAS);
        PaymentResult result = orchestrator.confirmMpm(ctxFrom(auth, sampleCommand));

        assertEquals(PaymentStatus.APPROVED, result.status());
        assertPresent("LEDGER:COMMISSION_SPLIT");
        // Quote payout is 50,000 KRW; merchant fee snapshotted at authorize; shares from config.
        assertEquals(50000L, capPayoutKrw.get(), "payout KRW from the quote targetPayout");
        assertEquals(0, capMerchantFee.get().compareTo(new BigDecimal("0.0080")),
                "merchant fee snapshotted at authorize is replayed at confirm");
        assertEquals(0, capVan.get().compareTo(new BigDecimal("0.0008")));
        assertEquals(0, capGme.get().compareTo(new BigDecimal("0.70")));
        assertEquals(0, capPartner.get().compareTo(new BigDecimal("0.30")));
    }

    @Test
    @DisplayName("Step 7: no merchant fee snapshotted → commission split is skipped (non-fatal)")
    void confirm_skipsCommissionSplit_whenNoMerchantFee() {
        // Default fakeRate/5-arg-style wiring: partnerConfigClient null → merchantFeeRate null.
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, twoPhasePrefunding(), fakeQr, fakeScheme, fakeTxn);

        PaymentOrchestrator.AuthorizeResult auth =
                orchestrator.authorizeMpm(sampleCommand, PartnerType.OVERSEAS);
        PaymentResult result = orchestrator.confirmMpm(ctxFrom(auth, sampleCommand));

        assertEquals(PaymentStatus.APPROVED, result.status());
        assertAbsent("LEDGER:COMMISSION_SPLIT",
                "split must be skipped when no merchant fee was snapshotted");
    }

    @Test
    @DisplayName("Two-phase confirm decline: hold RELEASED (not captured), txn FAILED")
    void twoPhase_confirmDecline_releasesHold() {
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, twoPhasePrefunding(), fakeQr, decliningScheme, fakeTxn);

        PaymentOrchestrator.AuthorizeResult auth =
                orchestrator.authorizeMpm(sampleCommand, PartnerType.OVERSEAS);

        assertThrows(SchemeDeclinedException.class,
                () -> orchestrator.confirmMpm(ctxFrom(auth, sampleCommand)));

        assertPresent("PREFUND:RELEASE");
        assertPresent("TXN:COMMIT:FAILED");
        assertAbsent("PREFUND:CAPTURE", "no capture when the scheme declines");
    }

    @Test
    @DisplayName("Authorize: scheme balance short → declined, hold released, scheme never submitted")
    void authorize_schemeBalanceShort_releasesHoldNoSubmit() {
        SchemeClient schemeBalanceShort = new SchemeClient() {
            @Override
            public MpmSubmitResponse submitMpm(MpmSubmitRequest req) {
                callLog.add("SCHEME:SUBMIT");
                throw new AssertionError("scheme must NOT be submitted when balance is short");
            }
            @Override public void cancelPayment(String schemeTxnRef, String reason) { }
            @Override public CpmSubmitResponse submitCpm(CpmSubmitRequest req) { return null; }
            @Override
            public BalanceCheckResult checkBalance(String schemeId, BigDecimal amount, String currency) {
                callLog.add("SCHEME:BALANCE_CHECK");
                return new BalanceCheckResult(false, new BigDecimal("0"));
            }
        };
        PaymentOrchestrator orchestrator = new PaymentOrchestrator(
                fakeRate, twoPhasePrefunding(), fakeQr, schemeBalanceShort, fakeTxn);

        assertThrows(SchemeBalanceUnavailableException.class,
                () -> orchestrator.authorizeMpm(sampleCommand, PartnerType.OVERSEAS));

        assertPresent("PREFUND:RESERVE");        // partner gate passed (reserved)
        assertPresent("SCHEME:BALANCE_CHECK");   // then the scheme gate ran
        assertPresent("PREFUND:RELEASE");        // void released the hold
        assertPresent("TXN:COMMIT:FAILED");      // void failed the orphan PENDING txn
        assertAbsent("SCHEME:SUBMIT", "scheme must NEVER be submitted when its balance is short");
    }

    // ---- helpers ----

    private int indexOf(String step) {
        int idx = callLog.indexOf(step);
        if (idx < 0) {
            throw new AssertionError("Expected call log to contain '" + step
                    + "' but log was: " + callLog);
        }
        return idx;
    }

    private void assertOrder(String description, int earlier, int later) {
        if (earlier >= later) {
            throw new AssertionError(description + ": expected index " + earlier
                    + " < " + later + " in log " + callLog);
        }
    }

    private void assertPresent(String step) {
        if (!callLog.contains(step)) {
            throw new AssertionError("Expected '" + step + "' in call log but got: " + callLog);
        }
    }

    private void assertAbsent(String step, String message) {
        if (callLog.contains(step)) {
            throw new AssertionError(message + " — but '" + step
                    + "' was found in call log: " + callLog);
        }
    }
}
