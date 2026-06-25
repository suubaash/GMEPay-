package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.client.PartnerConfigClient;
import com.gme.pay.payment.domain.client.PrefundingClient;
import com.gme.pay.payment.domain.client.QrClient;
import com.gme.pay.payment.domain.client.RateClient;
import com.gme.pay.payment.domain.client.RevenueLedgerClient;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.TransactionClient;
import com.gme.pay.payment.domain.settlement.SettlementBooking;
import com.gme.pay.payment.domain.settlement.SettlementBookingService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Orchestrates the live payment path (API-05 §4.3 / SETTLEMENT_FLOW_SPEC §4/§7.1).
 *
 * <p>MPM is a strict TWO-PHASE flow — the irreversible scheme submit is the LAST step and happens
 * ONLY in confirm, after the partner has charged the customer (the non-negotiable):
 * <ol>
 *   <li><b>authorize</b> ({@link #authorizeMpm}): load+agreement-check the quote, resolve the
 *       merchant, create the PENDING txn, RESERVE (hold, not debit) the partner float, then
 *       balance-check the scheme. No scheme submit. Declines release the hold + fail the txn.
 *   <li><b>confirm</b> ({@link #confirmMpm}): submit to the scheme; ONLY on success CAPTURE the
 *       held float + book settlement + commit APPROVED + post revenue. Decline → release + FAILED;
 *       timeout → UNCERTAIN (hold retained for reconciliation).
 * </ol>
 *
 * <p>The legacy single-shot deduct-before-submit MPM path was retired (Step 4); CPM still uses the
 * single-shot {@link #executeCpm} until its two-phase rebuild.
 */
public class PaymentOrchestrator {

    /** KST — the revenue date booked on a capture is the Korea business-calendar date of the commit. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /**
     * Sentinel scheme id for revenue capture. The orchestrator carries the scheme CODE (e.g.
     * "zeropay"), not config-registry's numeric scheme id, so the per-transaction revenue row stores
     * 0 here. The per-partner revenue aggregate ({@code GET /v1/revenue}) does not key on scheme, so
     * this does not affect reported figures; a numeric scheme id is a follow-on once the scheme
     * catalog is numerically keyed.
     */
    private static final long REVENUE_SCHEME_ID_UNSET = 0L;
    /**
     * Default scheme fee-share fraction recorded as metadata on revenue rows. The fee-share split is
     * computed elsewhere (revenue-ledger's postFeeShareSplit); this is record metadata only and is
     * not surfaced by the per-partner aggregate.
     */
    private static final BigDecimal DEFAULT_FEE_SHARE_PCT = new BigDecimal("0.70");

    private final RateClient rateClient;
    private final PrefundingClient prefundingClient;
    private final QrClient qrClient;
    private final SchemeClient schemeClient;
    private final TransactionClient transactionClient;
    private final SettlementBookingService settlementBookingService;
    private final RevenueLedgerClient revenueLedgerClient;
    /** Optional (V032): resolves the gross merchant fee rate at creation; null = resolution skipped. */
    private final PartnerConfigClient partnerConfigClient;

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
        this(rateClient, prefundingClient, qrClient, schemeClient, transactionClient,
                settlementBookingService, revenueLedgerClient, null);
    }

    /**
     * Full-arg constructor (V032) adding the optional {@link PartnerConfigClient} used to resolve
     * the gross merchant fee rate at creation and snapshot it onto the transaction. When null
     * (legacy wiring / tests) the resolution is skipped and the snapshot is left empty — settlement
     * then treats the rate as 0, exactly as before.
     */
    public PaymentOrchestrator(
            RateClient rateClient,
            PrefundingClient prefundingClient,
            QrClient qrClient,
            SchemeClient schemeClient,
            TransactionClient transactionClient,
            SettlementBookingService settlementBookingService,
            RevenueLedgerClient revenueLedgerClient,
            PartnerConfigClient partnerConfigClient) {
        this.rateClient = rateClient;
        this.prefundingClient = prefundingClient;
        this.qrClient = qrClient;
        this.schemeClient = schemeClient;
        this.transactionClient = transactionClient;
        this.settlementBookingService = settlementBookingService;
        this.revenueLedgerClient = revenueLedgerClient;
        this.partnerConfigClient = partnerConfigClient;
    }

    /**
     * Resolves the gross merchant fee rate to snapshot onto a new transaction (V032). Null-safe and
     * non-fatal: returns null when no client is wired or nothing resolves, so settlement treats the
     * fee as 0 and a config hiccup never fails a payment.
     */
    private java.math.BigDecimal resolveMerchantFeeRate(String schemeId, String merchantType) {
        if (partnerConfigClient == null) {
            return null;
        }
        return partnerConfigClient.resolveMerchantFeeRate(schemeId, merchantType).orElse(null);
    }

    /**
     * Verifies the partner-asserted settlement amount/currency against the locked quote — this is
     * the partner's binding agreement to what we will bill and settle. Exact value match
     * (scale-insensitive {@code compareTo}) plus case-insensitive currency match. The partner must
     * echo the quote's {@code collection_amount}/{@code collection_currency} verbatim.
     *
     * <p>Skipped only when the caller asserts no amount ({@code null}); the REST path always
     * supplies it (a required, validated request field), so the production path is always guarded.
     *
     * @throws QuoteAmountMismatchException when the asserted amount/currency disagrees with the quote
     */
    private void assertQuoteAgreement(BigDecimal requestedAmount, String requestedCurrency,
                                      RateClient.RateQuoteView quote) {
        if (requestedAmount == null) {
            return;
        }
        boolean amountMatches = quote.collectionAmount() != null
                && requestedAmount.compareTo(quote.collectionAmount()) == 0;
        boolean currencyMatches = requestedCurrency != null
                && requestedCurrency.equalsIgnoreCase(quote.collectionCurrency());
        if (!amountMatches || !currencyMatches) {
            throw new QuoteAmountMismatchException(
                    requestedAmount, requestedCurrency,
                    quote.collectionAmount(), quote.collectionCurrency());
        }
    }

    /**
     * The service fee component of a quote expressed in USD (the partner's prefund float currency),
     * so it can be folded into the float hold alongside {@code collectionUsd} (SETTLEMENT_FLOW_SPEC
     * §D10/§7.4).
     *
     * <p>{@code quote.serviceCharge()} is in the COLLECTION currency (it is derived as
     * {@code collectionAmount − sendAmount}, both collection-ccy). We convert it to USD with the
     * quote's own USD-per-collection-ccy ratio {@code collectionUsd / sendAmount} — using the quote's
     * own numbers (not an external rate) guarantees the resulting hold equals the USD equivalent of
     * the partner-agreed {@code collectionAmount}, so the float debit, the settlement booking, and
     * the partner's agreement all reconcile to the same fee.
     *
     * <p>Returns ZERO when there is no fee, no send base, or a same-currency short-circuit — in
     * which case the hold stays exactly {@code collectionUsd} (the prior behaviour).
     */
    private static BigDecimal serviceFeeUsd(RateClient.RateQuoteView quote) {
        BigDecimal svc = quote.serviceCharge();
        BigDecimal send = quote.sendAmount();
        BigDecimal collUsd = quote.collectionUsd();
        if (svc == null || svc.signum() == 0 || send == null || send.signum() <= 0 || collUsd == null) {
            return BigDecimal.ZERO;
        }
        // serviceCharge(collection-ccy) × (collectionUsd / sendAmount) = serviceCharge in USD.
        return svc.multiply(collUsd).divide(send, 8, RoundingMode.HALF_UP);
    }

    // ======================================================================
    // Two-phase MPM (SETTLEMENT_FLOW_SPEC §4/§7.1): authorize → (partner charges
    // the customer) → confirm. The irreversible scheme submit happens ONLY in
    // confirm, after the partner has confirmed the customer wallet charge — the
    // non-negotiable. authorizeMpm reserves the float; confirmMpm captures it.
    // ======================================================================

    /**
     * Phase 1 — authorize. Loads + agreement-checks the quote, resolves the merchant, creates the
     * PENDING transaction, and RESERVES (holds, does not debit) the partner float for OVERSEAS
     * partners. Nothing irreversible happens here: no scheme call. The returned context is persisted
     * by the controller and replayed into {@link #confirmMpm}.
     */
    public AuthorizeResult authorizeMpm(MpmPaymentCommand cmd, PartnerType partnerType) {
        // Step 1 + 1b: load + enforce the partner's agreement to the settlement amount.
        RateClient.RateQuoteView quote = rateClient.loadQuote(cmd.quoteId(), cmd.partnerId());
        assertQuoteAgreement(cmd.collectionAmount(), cmd.collectionCurrency(), quote);

        // Step 2: resolve merchant.
        QrClient.MerchantView merchant = qrClient.resolve(cmd.merchantQr());

        // Step 3: create the PENDING transaction (snapshot the merchant fee rate). The rate is also
        // carried on AuthorizeResult so it is persisted on the authorization and replayed at confirm
        // for the commission split (Step 7) — snapshot-at-authorize, not re-resolved at confirm.
        BigDecimal merchantFeeRate = resolveMerchantFeeRate(cmd.schemeId(), merchant.merchantType());
        TransactionClient.CreateResult txn = transactionClient.createPending(
                new TransactionClient.CreateRequest(
                        cmd.partnerId(), cmd.partnerTxnRef(), cmd.schemeId(), cmd.direction(),
                        PaymentMode.MPM.name(), quote.targetPayout(), quote.payoutCurrency(),
                        quote.collectionAmount(), quote.collectionCurrency(), merchant.merchantId(),
                        cmd.quoteId(), merchantFeeRate));

        // Step 4: RESERVE the partner float (hold, not debit) for OVERSEAS — authorize gate.
        // SETTLEMENT_FLOW_SPEC §D10/§7.4: the hold must equal payout-cost + FX-margin +
        // service-fee, i.e. the USD equivalent of the FULL amount the partner agreed to
        // (collectionAmount), NOT just the pool (collectionUsd). Reserving the pool alone
        // left the service fee in a ledger void — captured nowhere, yet booked on the
        // settlement leg and posted to revenue. Folding it in makes the float debit
        // reconcile with the agreed collectionAmount and the settlement booking.
        BigDecimal holdUsd = quote.collectionUsd().add(serviceFeeUsd(quote));
        BigDecimal reservedUsd = null;
        if (partnerType == PartnerType.OVERSEAS) {
            try {
                // InsufficientPrefundingException propagates; no scheme call has happened.
                PrefundingClient.ReservationResult res =
                        prefundingClient.reserve(cmd.partnerId(), txn.txnRef(), holdUsd);
                reservedUsd = res.reservedUsd();
            } catch (RuntimeException ex) {
                // Compensate the just-created PENDING txn so a failed reserve never orphans it.
                safeFailTxn(txn.txnRef());
                throw ex;
            }
        }

        // Step 5 (authorize gate 2): scheme balance-check — does GME hold enough prepaid balance WITH
        // the scheme to fund the payout? A short scheme float declines HERE, before the customer is
        // charged (minimises scheme-outage-after-charge at confirm). On decline, void this
        // authorization (release the partner hold + fail the orphan txn) and stop.
        SchemeClient.BalanceCheckResult schemeBalance =
                schemeClient.checkBalance(cmd.schemeId(), quote.targetPayout(), quote.payoutCurrency());
        if (!schemeBalance.allowed()) {
            voidAuthorization(cmd.partnerId(), txn.txnRef(), partnerType);
            throw new SchemeBalanceUnavailableException(
                    cmd.schemeId(), quote.targetPayout(), quote.payoutCurrency());
        }

        return new AuthorizeResult(txn.txnRef(), txn.paymentId(), txn.createdAt(),
                merchant, quote, reservedUsd, merchantFeeRate);
    }

    /**
     * Phase 2 — confirm. Submits to the scheme (the irreversible "pay the merchant" step) and, ONLY
     * on success, CAPTURES the previously-reserved float and commits APPROVED. On a synchronous
     * decline the hold is released and the txn is FAILED; on a scheme timeout the hold is left in
     * place and the txn is UNCERTAIN for reconciliation (Step 3 adds the auto-refund path).
     */
    public PaymentResult confirmMpm(ConfirmContext ctx) {
        SchemeClient.MpmSubmitResponse schemeResponse;
        try {
            schemeResponse = schemeClient.submitMpm(
                    new SchemeClient.MpmSubmitRequest(
                            ctx.txnRef(), ctx.merchantId(), ctx.targetPayout(),
                            ctx.payoutCurrency(), ctx.schemeId(), ctx.merchantQr()));
        } catch (SchemeDeclinedException ex) {
            if (ctx.partnerType() == PartnerType.OVERSEAS) {
                prefundingClient.release(ctx.partnerId(), ctx.txnRef());
            }
            transactionClient.commitStatus(ctx.txnRef(),
                    new TransactionClient.StatusPatch(PaymentStatus.FAILED, null, null, null, null));
            throw ex;
        } catch (SchemeTimeoutException ex) {
            // Customer already charged, scheme outcome unknown: leave the hold, mark UNCERTAIN.
            transactionClient.commitStatus(ctx.txnRef(),
                    new TransactionClient.StatusPatch(
                            PaymentStatus.UNCERTAIN, null, null, ctx.reservedUsd(), null));
            throw ex;
        }

        // Success: capture the held float (OVERSEAS), book settlement, commit APPROVED, post revenue.
        BigDecimal capturedUsd = null;
        if (ctx.partnerType() == PartnerType.OVERSEAS) {
            PrefundingClient.CaptureResult cap = prefundingClient.capture(ctx.partnerId(), ctx.txnRef());
            capturedUsd = cap.capturedUsd();
        }

        SettlementBooking booking = (settlementBookingService != null
                        && ctx.partnerCode() != null && !ctx.partnerCode().isBlank())
                ? settlementBookingService.book(
                        ctx.partnerCode(), ctx.collectionAmount(), ctx.collectionCurrency())
                : null;
        BigDecimal bookedAmount = booking != null ? booking.booked() : null;
        String roundingModeName = booking != null ? booking.mode().name() : null;
        BigDecimal residual = booking != null ? booking.residual() : null;

        transactionClient.commitStatus(ctx.txnRef(),
                new TransactionClient.StatusPatch(
                        PaymentStatus.APPROVED, schemeResponse.schemeTxnRef(),
                        schemeResponse.schemeApprovalCode(), capturedUsd, schemeResponse.approvedAt(),
                        bookedAmount, roundingModeName, residual));

        if (booking != null && revenueLedgerClient != null) {
            revenueLedgerClient.postRoundingResidual(ctx.txnRef(), booking.residual(), booking.currency());
        }
        if (revenueLedgerClient != null) {
            BigDecimal collMargin = ctx.collectionMarginUsd() != null ? ctx.collectionMarginUsd() : BigDecimal.ZERO;
            BigDecimal payMargin = ctx.payoutMarginUsd() != null ? ctx.payoutMarginUsd() : BigDecimal.ZERO;
            BigDecimal svcCharge = ctx.serviceCharge() != null ? ctx.serviceCharge() : BigDecimal.ZERO;
            LocalDate revenueDate = ctx.createdAt().atZone(KST).toLocalDate();
            revenueLedgerClient.postRevenueCapture(
                    ctx.txnRef(), ctx.partnerId(), REVENUE_SCHEME_ID_UNSET, revenueDate,
                    collMargin, payMargin, svcCharge, ctx.collectionCurrency(), DEFAULT_FEE_SHARE_PCT);
            postCommissionSplit(ctx, revenueDate);
        }

        return new PaymentResult(
                ctx.paymentId(), PaymentStatus.APPROVED, schemeResponse.schemeTxnRef(),
                ctx.merchantName(), ctx.merchantId(), ctx.targetPayout(), ctx.payoutCurrency(),
                ctx.offerRate(), ctx.collectionAmount(), ctx.collectionCurrency(),
                ctx.serviceCharge(), ctx.collectionCurrency(), capturedUsd, ctx.partnerTxnRef(),
                ctx.createdAt(), schemeResponse.approvedAt());
    }

    /**
     * Step 7 (SETTLEMENT_FLOW_SPEC §7 / task #102): compute + post the two-sided commission split for
     * the KRW scheme leg. The {@code CommissionSplitCalculator} runs in revenue-ledger; here we resolve
     * the configurable shares (V031) from config-registry and hand revenue-ledger the inputs.
     *
     * <p>Non-fatal — the payment has already committed, so the client logs and retries offline, never
     * throws. No-op unless the payout is KRW (the merchant fee ZeroPay returns + splits is KRW), a
     * merchant-fee rate was snapshotted at authorize, and config-registry resolves BOTH commission
     * shares (an unconfigured partner/scheme simply skips the split).
     */
    private void postCommissionSplit(ConfirmContext ctx, LocalDate revenueDate) {
        if (partnerConfigClient == null
                || !"KRW".equalsIgnoreCase(ctx.payoutCurrency())
                || ctx.merchantFeeRate() == null || ctx.merchantFeeRate().signum() <= 0
                || ctx.targetPayout() == null || ctx.targetPayout().signum() <= 0) {
            return;
        }
        partnerConfigClient.resolveCommissionSplit(ctx.schemeId(), ctx.partnerCode(), ctx.direction())
                .ifPresent(cfg -> {
                    long payoutKrw = ctx.targetPayout().setScale(0, RoundingMode.FLOOR).longValueExact();
                    revenueLedgerClient.postCommissionSplit(
                            ctx.txnRef(), ctx.partnerId(), REVENUE_SCHEME_ID_UNSET, revenueDate,
                            payoutKrw, ctx.merchantFeeRate(), cfg.vanFeePct(),
                            cfg.gmeSharePct(), cfg.partnerSharePct());
                });
    }

    /**
     * Releases a held float reservation when an authorization expires or is abandoned (no confirm).
     * OVERSEAS only — LOCAL partners hold no float. Idempotent (the prefunding release is a no-op if
     * the hold was already captured/released).
     */
    public void releaseHold(long partnerId, String txnRef, PartnerType partnerType) {
        if (partnerType == PartnerType.OVERSEAS) {
            prefundingClient.release(partnerId, txnRef);
        }
    }

    /**
     * Abandons an authorization that never confirmed (expired, or a concurrent-duplicate loser):
     * releases the held float (OVERSEAS) and moves the orphan PENDING transaction to FAILED so it
     * cannot linger. Best-effort and idempotent — release/commit are no-ops if already done.
     */
    public void voidAuthorization(long partnerId, String txnRef, PartnerType partnerType) {
        if (partnerType == PartnerType.OVERSEAS) {
            try {
                prefundingClient.release(partnerId, txnRef);
            } catch (RuntimeException ignore) {
                // best-effort; the reservation sweeper / recon will close it otherwise
            }
        }
        safeFailTxn(txnRef);
    }

    /** Best-effort: move a transaction to FAILED, swallowing errors (used for compensation). */
    private void safeFailTxn(String txnRef) {
        try {
            transactionClient.commitStatus(txnRef,
                    new TransactionClient.StatusPatch(PaymentStatus.FAILED, null, null, null, null));
        } catch (RuntimeException ignore) {
            // best-effort compensation
        }
    }

    /** What {@link #authorizeMpm} produces — persisted by the controller, replayed into confirm. */
    public record AuthorizeResult(
            String txnRef, String paymentId, Instant createdAt,
            QrClient.MerchantView merchant, RateClient.RateQuoteView quote, BigDecimal reservedUsd,
            BigDecimal merchantFeeRate) {}

    /** Frozen context replayed into {@link #confirmMpm} (rebuilt from the persisted authorization). */
    public record ConfirmContext(
            long partnerId, PartnerType partnerType, String partnerCode, String partnerTxnRef,
            String txnRef, String paymentId, String schemeId, String merchantQr,
            String merchantId, String merchantName,
            BigDecimal targetPayout, String payoutCurrency,
            BigDecimal collectionAmount, String collectionCurrency,
            BigDecimal reservedUsd, BigDecimal offerRate,
            BigDecimal collectionMarginUsd, BigDecimal payoutMarginUsd, BigDecimal serviceCharge,
            String direction, BigDecimal merchantFeeRate,
            Instant createdAt) {}

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
                        null,  // no quoteId for CPM
                        // V032: no merchant lookup on the CPM path → resolve the scheme default rate.
                        resolveMerchantFeeRate(cmd.schemeId(), null)
                )
        );

        // Step 2: RESERVE the partner float (hold, NOT debit) for OVERSEAS. CPM is synchronous
        // (the customer is present), but it rides the same money-safety spine as MPM (Step 4): the
        // float is only held until the scheme confirms the charge, never debited before the
        // irreversible submit. A failed reserve compensates the orphan PENDING txn.
        BigDecimal reservedUsd = null;
        if (partnerType == PartnerType.OVERSEAS) {
            try {
                PrefundingClient.ReservationResult res =
                        prefundingClient.reserve(cmd.partnerId(), txn.txnRef(), cmd.collectionUsd());
                reservedUsd = res.reservedUsd();
            } catch (RuntimeException ex) {
                safeFailTxn(txn.txnRef());
                throw ex;
            }
        }

        // Step 3 (gate): scheme balance-check before charging the customer — a short scheme float
        // declines HERE, voiding the authorization (release the hold + fail the orphan txn).
        SchemeClient.BalanceCheckResult schemeBalance =
                schemeClient.checkBalance(cmd.schemeId(), cmd.payoutAmount(), cmd.payoutCurrency());
        if (!schemeBalance.allowed()) {
            voidAuthorization(cmd.partnerId(), txn.txnRef(), partnerType);
            throw new SchemeBalanceUnavailableException(
                    cmd.schemeId(), cmd.payoutAmount(), cmd.payoutCurrency());
        }

        // Step 4: submit CPM to the scheme — the irreversible charge, the LAST step before capture.
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
            // Decline: RELEASE the hold (never captured), fail the txn.
            if (partnerType == PartnerType.OVERSEAS) {
                prefundingClient.release(cmd.partnerId(), txn.txnRef());
            }
            transactionClient.commitStatus(txn.txnRef(),
                    new TransactionClient.StatusPatch(
                            PaymentStatus.FAILED, null, null, null, null));
            throw ex;
        } catch (SchemeTimeoutException ex) {
            // Outcome unknown: leave the hold in place, mark UNCERTAIN for reconciliation.
            transactionClient.commitStatus(txn.txnRef(),
                    new TransactionClient.StatusPatch(
                            PaymentStatus.UNCERTAIN, null, null, reservedUsd, null));
            throw ex;
        }

        // Step 5: success — CAPTURE the held float (OVERSEAS) + commit APPROVED.
        BigDecimal capturedUsd = null;
        if (partnerType == PartnerType.OVERSEAS) {
            PrefundingClient.CaptureResult cap = prefundingClient.capture(cmd.partnerId(), txn.txnRef());
            capturedUsd = cap.capturedUsd();
        }
        transactionClient.commitStatus(txn.txnRef(),
                new TransactionClient.StatusPatch(
                        PaymentStatus.APPROVED,
                        schemeResponse.schemeTxnRef(),
                        schemeResponse.schemeApprovalCode(),
                        capturedUsd,
                        schemeResponse.approvedAt()
                )
        );

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
                capturedUsd,
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
            // Read the ACTUAL reversed USD from prefunding (no longer a hardcoded ZERO).
            PrefundingClient.ReverseResult reversal = prefundingClient.reverse(partnerId, txnRef);
            returnedUsd = reversal != null ? reversal.reversedUsd() : null;
        }

        transactionClient.commitStatus(txnRef,
                new TransactionClient.StatusPatch(
                        PaymentStatus.REVERSED, schemeTxnRef, null, returnedUsd, null));

        // Post a structured reversal journal to revenue-ledger (non-blocking) so the cancellation is
        // booked rather than absorbed as a zero residual. Uses the actually-reversed prefund USD;
        // LOCAL cancels carry no prefund amount here, so the journal is skipped (a full revenue
        // reversal would need the original quote amounts — a follow-on).
        if (revenueLedgerClient != null && returnedUsd != null && returnedUsd.signum() > 0) {
            revenueLedgerClient.postReversalJournal(txnRef, returnedUsd, "USD");
        }

        return new CancelResult(paymentId, PaymentStatus.CANCELLED, Instant.now(), returnedUsd);
    }

    /**
     * Refunds an APPROVED payment — a FULL reversal at the ORIGINAL locked rate
     * (SETTLEMENT_FLOW_SPEC: "refund = full reversal at the original locked rate"). Distinct from
     * {@link #cancelPayment} (a same-day void → REVERSED): a refund reverses an already-captured txn
     * and moves it to REFUNDED. The reversed prefund USD is exactly the amount captured at the locked
     * rate, so reversing it IS the locked-rate reversal; a structured reversal journal books it on
     * revenue-ledger so the refund is accounted rather than absorbed as a zero residual.
     *
     * <p>Non-fatal revenue posting (like cancel): the scheme refund + float reversal + status are the
     * authoritative steps; a revenue-ledger hiccup is logged + retried offline, never thrown.
     */
    public RefundResult refundPayment(String paymentId,
                                      String schemeTxnRef,
                                      PartnerType partnerType,
                                      long partnerId,
                                      String txnRef,
                                      String reason) {

        // Scheme-side refund (ZeroPay: the 결제취소/refund path). Same call the cancel uses.
        schemeClient.cancelPayment(schemeTxnRef, reason);

        BigDecimal returnedUsd = null;
        if (partnerType == PartnerType.OVERSEAS) {
            // Credit the partner float back by the captured (locked-rate) USD.
            PrefundingClient.ReverseResult reversal = prefundingClient.reverse(partnerId, txnRef);
            returnedUsd = reversal != null ? reversal.reversedUsd() : null;
        }

        transactionClient.commitStatus(txnRef,
                new TransactionClient.StatusPatch(
                        PaymentStatus.REFUNDED, schemeTxnRef, null, returnedUsd, null));

        if (revenueLedgerClient != null && returnedUsd != null && returnedUsd.signum() > 0) {
            revenueLedgerClient.postReversalJournal(txnRef, returnedUsd, "USD");
        }

        return new RefundResult(paymentId, PaymentStatus.REFUNDED, Instant.now(), returnedUsd);
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
     * @param partnerCode    partner business code (e.g. "GMEREMIT") used to resolve the
     *                       partner's settlement-rounding rule from config-registry, which is
     *                       keyed by code, not the numeric surrogate (see PartnerStore). May be
     *                       {@code null} when unavailable, in which case settlement booking is
     *                       skipped (the {@code numeric} {@code partnerId} still drives
     *                       transaction-mgmt + prefunding).
     * @param collectionAmount   the settlement amount the partner asserts it is charging the
     *                       customer. Verified against the locked quote before execution (see
     *                       {@link #assertQuoteAgreement}). {@code null} means the caller asserts
     *                       no amount and the check is skipped — only internal/test callers do
     *                       this; the REST path always supplies it (a required, validated field).
     * @param collectionCurrency ISO currency the partner asserts for {@code collectionAmount};
     *                       must match the quote's collection currency (case-insensitive).
     */
    public record MpmPaymentCommand(
            long partnerId,
            String quoteId,
            String merchantQr,
            String schemeId,
            String direction,
            String customerRef,
            String partnerTxnRef,
            String partnerCode,
            BigDecimal collectionAmount,
            String collectionCurrency
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

    /** Outcome of a successful refund (full reversal of an APPROVED txn at the locked rate). */
    public record RefundResult(
            String paymentId,
            PaymentStatus status,
            Instant refundedAt,
            BigDecimal prefundReturnedUsd
    ) {}
}
