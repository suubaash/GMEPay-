package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.client.PrefundingClient;
import com.gme.pay.payment.domain.client.QrClient;
import com.gme.pay.payment.domain.client.RateClient;
import com.gme.pay.payment.domain.client.RevenueLedgerClient;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.TransactionClient;
import com.gme.pay.payment.domain.settlement.SettlementBooking;
import com.gme.pay.payment.domain.settlement.SettlementBookingService;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Orchestrates the live payment path (API-05 §4.3 / WBS 5.2-T08).
 *
 * <p>Call sequence (strict):
 * <ol>
 *   <li>Load and validate rate quote (RateClient)
 *   <li>Resolve merchant QR (QrClient)
 *   <li>Create PENDING transaction record (TransactionClient)
 *   <li>For OVERSEAS partners: deduct prefunding (PrefundingClient) — MUST precede scheme call
 *   <li>Submit to scheme (SchemeClient)
 *   <li>Book per-partner settlement liability (SettlementBookingService) — see MONEY_CONVENTION.md
 *   <li>Commit APPROVED status with rate-lock fields (TransactionClient)
 *   <li>Post rounding residual to revenue-ledger (RevenueLedgerClient) — non-blocking
 * </ol>
 *
 * <p>If prefunding deduction fails, the scheme is NEVER called.
 * If the scheme declines, a previously deducted prefunding amount is immediately reversed.
 * If the scheme times out, the deduction is left in place (UNCERTAIN state).
 */
public class PaymentOrchestrator {

    private final RateClient rateClient;
    private final PrefundingClient prefundingClient;
    private final QrClient qrClient;
    private final SchemeClient schemeClient;
    private final TransactionClient transactionClient;
    private final SettlementBookingService settlementBookingService;
    private final RevenueLedgerClient revenueLedgerClient;

    /**
     * Backwards-compatible 5-arg constructor used by existing wiring/tests that do not exercise
     * the per-partner settlement rounding path. Delegates to the full-arg constructor with the
     * settlement and revenue-ledger collaborators set to {@code null}, in which case the
     * rounding-lock steps are skipped.
     */
    public PaymentOrchestrator(
            RateClient rateClient,
            PrefundingClient prefundingClient,
            QrClient qrClient,
            SchemeClient schemeClient,
            TransactionClient transactionClient) {
        this(rateClient, prefundingClient, qrClient, schemeClient, transactionClient, null, null);
    }

    /**
     * Full-arg constructor wiring in the settlement-rounding collaborators introduced in
     * Phase 2.5 (per-partner rounding booked at commit, residual posted to revenue-ledger).
     */
    public PaymentOrchestrator(
            RateClient rateClient,
            PrefundingClient prefundingClient,
            QrClient qrClient,
            SchemeClient schemeClient,
            TransactionClient transactionClient,
            SettlementBookingService settlementBookingService,
            RevenueLedgerClient revenueLedgerClient) {
        this.rateClient = rateClient;
        this.prefundingClient = prefundingClient;
        this.qrClient = qrClient;
        this.schemeClient = schemeClient;
        this.transactionClient = transactionClient;
        this.settlementBookingService = settlementBookingService;
        this.revenueLedgerClient = revenueLedgerClient;
    }

    /**
     * Executes a Fixed MPM payment end-to-end.
     *
     * @param cmd           the payment command from the REST layer
     * @param partnerType   whether the partner is OVERSEAS (prefunding) or LOCAL
     * @return the orchestration result to be mapped to the HTTP response
     */
    public PaymentResult executeMpm(MpmPaymentCommand cmd, PartnerType partnerType) {

        // Step 1: Load and validate rate quote
        RateClient.RateQuoteView quote = rateClient.loadQuote(cmd.quoteId(), cmd.partnerId());

        // Step 2: Resolve merchant
        QrClient.MerchantView merchant = qrClient.resolve(cmd.merchantQr());

        // Step 3: Create PENDING transaction
        TransactionClient.CreateResult txn = transactionClient.createPending(
                new TransactionClient.CreateRequest(
                        cmd.partnerId(),
                        cmd.partnerTxnRef(),
                        cmd.schemeId(),
                        cmd.direction(),
                        PaymentMode.MPM.name(),
                        quote.targetPayout(),
                        quote.payoutCurrency(),
                        quote.collectionAmount(),
                        quote.collectionCurrency(),
                        merchant.merchantId(),
                        cmd.quoteId()
                )
        );

        // Step 4: Prefunding deduction for OVERSEAS partners (MUST happen before scheme call)
        PrefundingClient.DeductionResult deduction = null;
        if (partnerType == PartnerType.OVERSEAS) {
            // InsufficientPrefundingException propagates; scheme is never reached
            deduction = prefundingClient.deduct(
                    cmd.partnerId(), txn.txnRef(), quote.collectionUsd());
        }

        // Step 5: Submit to scheme
        SchemeClient.MpmSubmitResponse schemeResponse;
        try {
            schemeResponse = schemeClient.submitMpm(
                    SchemeClient.MpmSubmitRequest.of(
                            txn.txnRef(),
                            merchant.merchantId(),
                            quote.targetPayout(),
                            quote.payoutCurrency(),
                            cmd.schemeId()
                    )
            );
        } catch (SchemeDeclinedException ex) {
            // Reverse deduction immediately on synchronous decline
            if (partnerType == PartnerType.OVERSEAS) {
                prefundingClient.reverse(cmd.partnerId(), txn.txnRef());
            }
            transactionClient.commitStatus(txn.txnRef(),
                    new TransactionClient.StatusPatch(
                            PaymentStatus.FAILED, null, null, null, null));
            throw ex;
        } catch (SchemeTimeoutException ex) {
            // Leave deduction in place; mark UNCERTAIN for batch reconciliation
            transactionClient.commitStatus(txn.txnRef(),
                    new TransactionClient.StatusPatch(
                            PaymentStatus.UNCERTAIN, null, null,
                            deduction != null ? deduction.deductedUsd() : null, null));
            throw ex;
        }

        // Step 6 (pre-commit): per-partner settlement booking. Skipped when collaborators
        // are not wired (backwards-compat with 5-arg constructor used by older tests).
        SettlementBooking booking = (settlementBookingService != null)
                ? settlementBookingService.book(
                        cmd.partnerId(), quote.collectionAmount(), quote.collectionCurrency())
                : null;

        // Step 7: Commit APPROVED with rate-lock fields when booking was performed.
        BigDecimal deductedUsd = deduction != null ? deduction.deductedUsd() : null;
        BigDecimal bookedAmount = booking != null ? booking.booked() : null;
        String roundingModeName = booking != null ? booking.mode().name() : null;
        BigDecimal residual = booking != null ? booking.residual() : null;
        transactionClient.commitStatus(txn.txnRef(),
                new TransactionClient.StatusPatch(
                        PaymentStatus.APPROVED,
                        schemeResponse.schemeTxnRef(),
                        schemeResponse.schemeApprovalCode(),
                        deductedUsd,
                        schemeResponse.approvedAt(),
                        bookedAmount,
                        roundingModeName,
                        residual
                )
        );

        // Step 8: Post rounding residual to revenue-ledger AFTER commit. The client is
        // non-throwing per its contract (failures are logged for offline retry).
        if (booking != null && revenueLedgerClient != null) {
            revenueLedgerClient.postRoundingResidual(
                    txn.txnRef(), booking.residual(), booking.currency());
        }

        return new PaymentResult(
                txn.paymentId(),
                PaymentStatus.APPROVED,
                schemeResponse.schemeTxnRef(),
                merchant.merchantName(),
                merchant.merchantId(),
                quote.targetPayout(),
                quote.payoutCurrency(),
                quote.offerRateColl(),
                quote.collectionAmount(),
                quote.collectionCurrency(),
                quote.serviceCharge(),
                quote.collectionCurrency(),
                deductedUsd,
                cmd.partnerTxnRef(),
                txn.createdAt(),
                schemeResponse.approvedAt()
        );
    }

    /**
     * Executes a CPM (Consumer-Presented Mode) payment end-to-end.
     *
     * <p>CPM flow: the customer presents a QR code on their device;
     * the merchant terminal scans it and passes the token here for authorisation.
     *
     * @param cmd         the CPM payment command from the REST layer
     * @param partnerType whether the partner is OVERSEAS (prefunding) or LOCAL
     * @return the orchestration result
     */
    public PaymentResult executeCpm(CpmPaymentCommand cmd, PartnerType partnerType) {

        // Step 1: Create PENDING transaction record
        TransactionClient.CreateResult txn = transactionClient.createPending(
                new TransactionClient.CreateRequest(
                        cmd.partnerId(),
                        cmd.partnerTxnRef(),
                        cmd.schemeId(),
                        "CPM",
                        PaymentMode.CPM.name(),
                        cmd.payoutAmount(),
                        cmd.payoutCurrency(),
                        cmd.collectionAmount(),
                        cmd.collectionCurrency(),
                        cmd.merchantId(),
                        null  // no quoteId for CPM
                )
        );

        // Step 2: Prefunding deduction for OVERSEAS partners
        PrefundingClient.DeductionResult deduction = null;
        if (partnerType == PartnerType.OVERSEAS) {
            deduction = prefundingClient.deduct(
                    cmd.partnerId(), txn.txnRef(), cmd.collectionUsd());
        }

        // Step 3: Submit CPM to scheme (authorize + commit in one scheme-adapter call)
        SchemeClient.CpmSubmitResponse schemeResponse;
        try {
            schemeResponse = schemeClient.submitCpm(
                    new SchemeClient.CpmSubmitRequest(
                            txn.txnRef(),
                            cmd.cpmToken(),
                            cmd.payoutAmount(),
                            cmd.payoutCurrency(),
                            cmd.schemeId()
                    )
            );
        } catch (SchemeDeclinedException ex) {
            if (partnerType == PartnerType.OVERSEAS) {
                prefundingClient.reverse(cmd.partnerId(), txn.txnRef());
            }
            transactionClient.commitStatus(txn.txnRef(),
                    new TransactionClient.StatusPatch(
                            PaymentStatus.FAILED, null, null, null, null));
            throw ex;
        } catch (SchemeTimeoutException ex) {
            transactionClient.commitStatus(txn.txnRef(),
                    new TransactionClient.StatusPatch(
                            PaymentStatus.UNCERTAIN, null, null,
                            deduction != null ? deduction.deductedUsd() : null, null));
            throw ex;
        }

        // Step 4: Commit APPROVED
        BigDecimal deductedUsd = deduction != null ? deduction.deductedUsd() : null;
        transactionClient.commitStatus(txn.txnRef(),
                new TransactionClient.StatusPatch(
                        PaymentStatus.APPROVED,
                        schemeResponse.schemeTxnRef(),
                        schemeResponse.schemeApprovalCode(),
                        deductedUsd,
                        schemeResponse.approvedAt()
                )
        );

        // Step 5: Post rounding residual (non-blocking) — CPM does not have FX rounding
        // by design; skip unless a settlement booking was made.

        return new PaymentResult(
                txn.paymentId(),
                PaymentStatus.APPROVED,
                schemeResponse.schemeTxnRef(),
                cmd.merchantId(),   // merchantName not available without QR decode
                cmd.merchantId(),
                cmd.payoutAmount(),
                cmd.payoutCurrency(),
                null,               // no offer rate for CPM
                cmd.collectionAmount(),
                cmd.collectionCurrency(),
                null,               // no service charge at orchestrator level
                cmd.collectionCurrency(),
                deductedUsd,
                cmd.partnerTxnRef(),
                txn.createdAt(),
                schemeResponse.approvedAt()
        );
    }

    /**
     * Cancels an approved same-day payment.
     *
     * @param paymentId     the GMEPay+ payment ID
     * @param schemeTxnRef  the scheme's own transaction reference
     * @param partnerType   whether the partner is OVERSEAS (triggers reversal) or LOCAL
     * @param partnerId     the authenticated partner
     * @param reason        human-readable cancellation reason
     * @return cancellation result
     */
    public CancelResult cancelPayment(String paymentId,
                                      String schemeTxnRef,
                                      PartnerType partnerType,
                                      long partnerId,
                                      String txnRef,
                                      String reason) {

        schemeClient.cancelPayment(schemeTxnRef, reason);

        BigDecimal returnedUsd = null;
        if (partnerType == PartnerType.OVERSEAS) {
            prefundingClient.reverse(partnerId, txnRef);
            // The actual amount reversed would come from the ledger; for the result we
            // return a sentinel — callers that need the exact amount query the ledger.
            returnedUsd = BigDecimal.ZERO; // placeholder; real impl reads from ledger
        }

        transactionClient.commitStatus(txnRef,
                new TransactionClient.StatusPatch(
                        PaymentStatus.REVERSED, schemeTxnRef, null, returnedUsd, null));

        return new CancelResult(paymentId, PaymentStatus.CANCELLED, Instant.now(), returnedUsd);
    }

    // ---- value objects ----

    /**
     * Command object for an MPM payment execution.
     *
     * @param partnerId      authenticated caller
     * @param quoteId        previously issued rate quote
     * @param merchantQr     scanned QR string
     * @param schemeId       requested scheme (e.g. "zeropay")
     * @param direction      payment direction
     * @param customerRef    customer reference text
     * @param partnerTxnRef  partner's own transaction reference (must be unique)
     */
    public record MpmPaymentCommand(
            long partnerId,
            String quoteId,
            String merchantQr,
            String schemeId,
            String direction,
            String customerRef,
            String partnerTxnRef
    ) {}

    /** Outcome of a successful MPM payment orchestration. */
    public record PaymentResult(
            String paymentId,
            PaymentStatus status,
            String schemeTxnId,
            String merchantName,
            String merchantId,
            BigDecimal targetPayout,
            String payoutCurrency,
            BigDecimal offerRate,
            BigDecimal collectionAmount,
            String collectionCurrency,
            BigDecimal serviceCharge,
            String serviceChargeCurrency,
            BigDecimal prefundDeductedUsd,
            String partnerTxnRef,
            Instant createdAt,
            Instant approvedAt
    ) {}

    /**
     * Command object for a CPM payment execution.
     *
     * @param partnerId        authenticated caller
     * @param partnerTxnRef    partner's own transaction reference (must be unique)
     * @param schemeId         requested scheme (e.g. "zeropay")
     * @param cpmToken         the CPM token (issued by the scheme, presented by the customer)
     * @param merchantId       the merchant identifier (from QR decode or partner-provided)
     * @param payoutAmount     the amount to pay out (denomination in payoutCurrency)
     * @param payoutCurrency   ISO currency code (e.g. "KRW")
     * @param collectionAmount the amount charged to the partner (denomination in collectionCurrency)
     * @param collectionCurrency ISO currency code for the collection
     * @param collectionUsd    USD equivalent of the collection amount (for prefunding)
     */
    public record CpmPaymentCommand(
            long partnerId,
            String partnerTxnRef,
            String schemeId,
            String cpmToken,
            String merchantId,
            BigDecimal payoutAmount,
            String payoutCurrency,
            BigDecimal collectionAmount,
            String collectionCurrency,
            BigDecimal collectionUsd
    ) {}

    /** Outcome of a successful cancellation. */
    public record CancelResult(
            String paymentId,
            PaymentStatus status,
            Instant cancelledAt,
            BigDecimal prefundReturnedUsd
    ) {}
}
