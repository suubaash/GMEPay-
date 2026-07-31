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
 *
 * <h2>The "AML gates" in this class are LIMITS, not AML screening (gap T5-3)</h2>
 * <p>Authorize gate 0 and gate 0b are named after the {@code partner_limits} (V020) and
 * {@code aml_velocity_*} (V034) columns they read, and that naming has repeatedly been read as evidence
 * that this platform performs AML checks on a transaction. It does not. Both gates are numeric
 * comparisons against operator-entered ceilings — per-transaction USD, daily/monthly/annual USD, and a
 * daily transaction count — enforcing the statutory 소액해외송금업 limits. <b>They screen nobody, consult
 * no list, and detect no pattern.</b>
 *
 * <p>The counterparty sanctions/PEP question is asked separately and earlier, by
 * {@link PaymentScreeningGate} via {@code OperationalGate}, and today its honest answer is that
 * <b>nothing is screened</b> — there is no provider configured, and neither payment contract carries an
 * originator name for one to match on. That absence is counted, alerted and queryable rather than
 * implied away; see {@link PaymentScreeningGate} and
 * {@code outputs/agent/fix_t5-aml-seam_2026-07-28.md}.
 */
public class PaymentOrchestrator {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PaymentOrchestrator.class);

    /** KST — the revenue date booked on a capture is the Korea business-calendar date of the commit. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /**
     * Default scheme fee-share fraction recorded as metadata on revenue rows. The fee-share split is
     * computed elsewhere (revenue-ledger's postFeeShareSplit); this is record metadata only and is
     * not surfaced by the per-partner aggregate.
     */
    private static final BigDecimal DEFAULT_FEE_SHARE_PCT = new BigDecimal("0.70");

    /**
     * Decimal places kept when pro-rating a partial refund's USD (T2-6). Matches prefunding's USD ledger
     * precision, so a refunded slice is representable on the float without a silent re-round on the way in.
     */
    private static final int USD_SCALE = 4;

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
     * Resolve the partner's configured transaction limits (per-txn + cumulative caps), or {@code null}
     * when no {@link PartnerConfigClient} is wired or none are configured/resolvable. Fail-soft: a
     * config-registry blip yields {@code null} (unconstrained) — never fails a payment. The per-txn cap is
     * enforced via {@link TransactionLimitPolicy} at Step 1c; the cumulative caps feed the Step-4 charge.
     */
    private PartnerConfigClient.TxnLimits resolveLimits(String partnerCode) {
        return partnerConfigClient == null
                ? null
                : partnerConfigClient.resolveLimits(partnerCode).orElse(null);
    }

    /** True when any cumulative amount cap OR the daily transaction-count velocity cap is configured. */
    private static boolean hasCumulativeCap(PartnerConfigClient.TxnLimits limits) {
        return limits != null
                && (limits.dailyCapUsd() != null || limits.monthlyCapUsd() != null
                || limits.annualCapUsd() != null || limits.dailyTxnCountLimit() != null);
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

        // Step 1c (authorize gate 0 — regulatory transaction LIMITS, not AML screening; see the T5-3
        // section of this class's javadoc): resolve the partner's limits ONCE (keyed by partner
        // CODE, like commission-split), then enforce the per-transaction USD cap (the statutory 소액해외송금업
        // ceiling among them) on the USD value of the agreed collection amount, BEFORE any side effect. The
        // CUMULATIVE daily/monthly/annual + velocity caps are charged after the float hold (Step 4) so they
        // ride prefunding's atomic per-partner lock — for EVERY partner type (T4-2), not just OVERSEAS.
        BigDecimal holdUsd = quote.collectionUsd().add(serviceFeeUsd(quote));
        PartnerConfigClient.TxnLimits limits = resolveLimits(cmd.partnerCode());
        TransactionLimitPolicy.enforcePerTransaction(cmd.partnerCode(), holdUsd, limits);

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
                        cmd.quoteId(), merchantFeeRate,
                        // Wave-3: carry the rate-lock pool from the locked quote so transaction-mgmt
                        // persists real margins (FX1015). Cost rates are not on the quote view → null.
                        quote.offerRateColl(), quote.crossRate(), null, null,
                        quote.collectionUsd(), quote.payoutUsdCost(),
                        quote.collectionMarginUsd(), quote.payoutMarginUsd(),
                        // T4-4: the merchant name Step 2 just resolved from merchant-qr-data, persisted
                        // at AUTHORIZE (the earliest moment it is known) so the transaction carries it
                        // for every later read even if the confirm never happens.
                        MerchantNames.realOrNull(merchant.merchantName())));

        // Step 4: RESERVE the partner float (hold, not debit) for OVERSEAS — authorize gate.
        // SETTLEMENT_FLOW_SPEC §D10/§7.4: the hold must equal payout-cost + FX-margin +
        // service-fee, i.e. the USD equivalent of the FULL amount the partner agreed to
        // (collectionAmount), NOT just the pool (collectionUsd). Reserving the pool alone
        // left the service fee in a ledger void — captured nowhere, yet booked on the
        // settlement leg and posted to revenue. Folding it in makes the float debit
        // reconcile with the agreed collectionAmount and the settlement booking.
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

        // Authorize gate 0b (CUMULATIVE limits — historically mislabelled "AML"): charge the partner's
        // daily/monthly/annual usage + the
        // daily velocity count, race-free under prefunding's per-partner lock. On breach — or any
        // error — void the authorization (release any hold + fail the orphan txn) and propagate.
        //
        // T4-2: this used to sit INSIDE the OVERSEAS branch above, so a LOCAL partner's configured
        // caps were never evaluated — an implicit, undocumented "LOCAL partners are uncapped" rule.
        // partner_limits (V020) has no partner-type discrimination and no seeded rows: a LOCAL
        // partner's row looks exactly like an OVERSEAS one, and the statutory ceilings are a property
        // of the LICENCE (license_type), not of the funding model. The float RESERVE stays
        // OVERSEAS-only — that is a genuine funding fact (LOCAL partners hold no float) — but cap
        // enforcement now applies to EVERY partner type whenever a cap is actually configured. A LOCAL
        // partner with no caps configured is still unconstrained, now explicitly (null caps) rather
        // than by branch.
        if (hasCumulativeCap(limits)) {
            try {
                prefundingClient.chargeCumulative(cmd.partnerId(), txn.txnRef(), holdUsd,
                        limits.dailyCapUsd(), limits.monthlyCapUsd(), limits.annualCapUsd(),
                        limits.dailyTxnCountLimit());
            } catch (RuntimeException ex) {
                voidAuthorization(cmd.partnerId(), txn.txnRef(), partnerType);
                throw ex;
            }
        } else if (partnerType == PartnerType.LOCAL) {
            log.debug("LOCAL partner {} has no cumulative cap configured — authorize proceeds"
                    + " uncapped by configuration, not by partner type", cmd.partnerCode());
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
            // Return the cumulative cap the authorize charged — this txn will not complete (no-op if
            // uncharged). T4-2: outside the OVERSEAS branch, because a LOCAL authorize now charges too.
            prefundingClient.reverseCumulative(ctx.partnerId(), ctx.txnRef());
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
                        bookedAmount, roundingModeName, residual,
                        // Wave-3: carry the locked-quote margins on the APPROVED commit so
                        // transaction-mgmt persists real margins (fixes FX1015 zero-margin). Cost
                        // rates are not snapshotted on the authorization → null.
                        ctx.collectionMarginUsd(), ctx.payoutMarginUsd(), ctx.collectionUsd(),
                        null, null));

        if (booking != null && revenueLedgerClient != null) {
            revenueLedgerClient.postRoundingResidual(ctx.txnRef(), booking.residual(), booking.currency());
        }
        if (revenueLedgerClient != null) {
            BigDecimal collMargin = ctx.collectionMarginUsd() != null ? ctx.collectionMarginUsd() : BigDecimal.ZERO;
            BigDecimal payMargin = ctx.payoutMarginUsd() != null ? ctx.payoutMarginUsd() : BigDecimal.ZERO;
            BigDecimal svcCharge = ctx.serviceCharge() != null ? ctx.serviceCharge() : BigDecimal.ZERO;
            LocalDate revenueDate = ctx.createdAt().atZone(KST).toLocalDate();
            long schemeId = SchemeId.resolve(ctx.schemeId());
            revenueLedgerClient.postRevenueCapture(
                    ctx.txnRef(), ctx.partnerId(), schemeId, revenueDate,
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
                            ctx.txnRef(), ctx.partnerId(), SchemeId.resolve(ctx.schemeId()), revenueDate,
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
        // T4-2: the cap reverse is partner-type-independent — a LOCAL authorize charges cap too, so an
        // abandoned LOCAL authorize must free it. No-op when nothing was charged.
        prefundingClient.reverseCumulative(partnerId, txnRef);
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
        try {
            // T4-2: free the cap for EVERY partner type (LOCAL authorizes now charge it). No-op if
            // uncharged; a failure here leaves cap consumed, which is the fail-safe direction.
            prefundingClient.reverseCumulative(partnerId, txnRef);
        } catch (RuntimeException ignore) {
            // best-effort compensation
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
            Instant createdAt, BigDecimal collectionUsd) {

        /** Backwards-compatible ctor (no collectionUsd) — defaults the pool collection USD to null. */
        public ConfirmContext(
                long partnerId, PartnerType partnerType, String partnerCode, String partnerTxnRef,
                String txnRef, String paymentId, String schemeId, String merchantQr,
                String merchantId, String merchantName,
                BigDecimal targetPayout, String payoutCurrency,
                BigDecimal collectionAmount, String collectionCurrency,
                BigDecimal reservedUsd, BigDecimal offerRate,
                BigDecimal collectionMarginUsd, BigDecimal payoutMarginUsd, BigDecimal serviceCharge,
                String direction, BigDecimal merchantFeeRate,
                Instant createdAt) {
            this(partnerId, partnerType, partnerCode, partnerTxnRef, txnRef, paymentId, schemeId,
                    merchantQr, merchantId, merchantName, targetPayout, payoutCurrency,
                    collectionAmount, collectionCurrency, reservedUsd, offerRate,
                    collectionMarginUsd, payoutMarginUsd, serviceCharge, direction, merchantFeeRate,
                    createdAt, null);
        }
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

        // NOTE: the per-transaction limit gate (see authorizeMpm) is NOT applied here yet — CpmPaymentCommand
        // carries only the numeric partnerId, and config-registry's limits endpoint keys by partner CODE.
        // Threading partnerCode into the CPM command (+ controller) is the follow-up that enables it.

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
                        resolveMerchantFeeRate(cmd.schemeId(), null),
                        // T4-4: null merchantName, honestly. CPM has no QR decode and no merchant
                        // lookup, so the name is genuinely unknown on this path; the detail read shows
                        // an em dash rather than a value we would have had to invent.
                        null
                )
        );

        // Step 2: RESERVE the partner float (hold, NOT debit) for OVERSEAS. CPM is synchronous
        // (the customer is present), but it rides the same money-safety spine as MPM (Step 4): the
        // float is only held until the scheme confirms the charge, never debited before the
        // irreversible submit. A failed reserve compensates the orphan PENDING txn.
        //
        // The CPM hold uses the canonical idempotent reserve contract (prefunding's
        // POST /internal/v1/prefunding/{partner}/reserve, keyed on idempotencyKey == txnRef) so a
        // retried CPM authorize never double-holds; the returned reservationId is carried so a
        // decline can RELEASE the exact hold via releaseCpm.
        BigDecimal reservedUsd = null;
        String reservationId = null;
        if (partnerType == PartnerType.OVERSEAS) {
            try {
                com.gme.pay.contracts.PrefundingReserveResponse res =
                        prefundingClient.reserveCpm(
                                cmd.partnerId(), cmd.collectionUsd(), txn.txnRef(), txn.txnRef());
                reservedUsd = res.reservedAmountUsd();
                reservationId = res.reservationId();
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
            // Decline: RELEASE the CPM hold (never captured), fail the txn. Idempotent on the reserve
            // key (== txnRef), so a retried release is a no-op.
            if (partnerType == PartnerType.OVERSEAS) {
                prefundingClient.releaseCpm(cmd.partnerId(), reservationId, txn.txnRef(), "SCHEME_DECLINED");
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
                // T4-4: merchantName is NOT available on the CPM path (no QR decode, no merchant
                // lookup). This slot used to be filled with the merchant ID, so every CPM response
                // and stored payment showed a terminal identifier under a "merchant name" label —
                // a fabricated value, and one that made the gap look closed. Null is the truth.
                null,
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
     * Scheme-less cancel — kept for callers that genuinely do not know the scheme. Routes to the
     * ZeroPay default (unchanged legacy behaviour). Prefer
     * {@link #cancelPayment(String, String, PartnerType, long, String, String, String)}.
     */
    public CancelResult cancelPayment(String paymentId,
                                      String schemeTxnRef,
                                      PartnerType partnerType,
                                      long partnerId,
                                      String txnRef,
                                      String reason) {
        return cancelPayment(paymentId, schemeTxnRef, partnerType, partnerId, txnRef, reason, null);
    }

    /**
     * Cancels an approved same-day payment.
     *
     * <p>T2-7: the scheme cancel is dispatched by {@code schemeId} so a Nepal/SendMN cancel reaches its
     * own adapter. Because the scheme call is the FIRST step, a scheme without a cancel round-trip
     * raises {@link SchemeOperationNotSupportedException} before any float is reversed or any status is
     * written — the transaction is left exactly as it was, and the caller sees a structured
     * {@code SCHEME_OPERATION_UNSUPPORTED} rather than a foreign ZeroPay decline.
     *
     * @param paymentId     the GMEPay+ payment ID
     * @param schemeTxnRef  the scheme's own transaction reference
     * @param partnerType   whether the partner is OVERSEAS (triggers reversal) or LOCAL
     * @param partnerId     the authenticated partner
     * @param reason        human-readable cancellation reason
     * @param schemeId      scheme CODE the payment was executed on; null/blank = ZeroPay default
     * @return cancellation result
     */
    public CancelResult cancelPayment(String paymentId,
                                      String schemeTxnRef,
                                      PartnerType partnerType,
                                      long partnerId,
                                      String txnRef,
                                      String reason,
                                      String schemeId) {

        schemeClient.cancelPayment(new SchemeClient.CancelRequest(schemeTxnRef, reason, schemeId));

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
        return refundPayment(paymentId, schemeTxnRef, partnerType, partnerId, txnRef, reason, null);
    }

    /**
     * Scheme-routed refund (T2-7). Same contract as
     * {@link #refundPayment(String, String, PartnerType, long, String, String)} but the scheme cancel is
     * dispatched by {@code schemeId}; a single-shot scheme raises
     * {@link SchemeOperationNotSupportedException} before the float is credited back or the status is
     * moved to REFUNDED, so a corridor with no scheme refund path never produces a half-applied refund.
     *
     * @param schemeId scheme CODE the payment was executed on; null/blank = ZeroPay default
     */
    public RefundResult refundPayment(String paymentId,
                                      String schemeTxnRef,
                                      PartnerType partnerType,
                                      long partnerId,
                                      String txnRef,
                                      String reason,
                                      String schemeId) {
        // A null amount means "refund the full refundable remainder" — the behaviour this signature has
        // always had. All of the work now lives in the amount-carrying overload.
        return refundPayment(paymentId, schemeTxnRef, partnerType, partnerId, txnRef, reason, schemeId,
                null, null);
    }

    /**
     * Refunds an APPROVED payment, in FULL or in PART (gap T2-6).
     *
     * <p><b>What was wrong.</b> Neither the DTOs nor this method carried an amount, so every refund
     * unconditionally reversed the ENTIRE prefunding hold; {@code refundAmountKrw} was never persisted so
     * settlement's claw-back netted nothing; and the reversal journal was booked from the float amount only,
     * meaning a LOCAL partner's refund was booked nowhere at all.
     *
     * <p><b>Order of operations, and why.</b> Every refusal happens before anything moves:
     * <ol>
     *   <li><b>Resolve + validate the amount</b> against the original payment. A partial refund with no
     *       readable original is refused ({@code REFUND_BASIS_UNAVAILABLE}); an amount that would push the
     *       cumulative refunded total past the original is refused
     *       ({@code REFUND_AMOUNT_EXCEEDS_ORIGINAL}); a non-positive or foreign-currency amount is refused
     *       ({@code REFUND_AMOUNT_INVALID}). No scheme call, no float, no status.</li>
     *   <li><b>Scheme leg.</b> A partial refund carries its amount on the {@link SchemeClient.CancelRequest};
     *       an adapter that cannot express a partial instruction raises
     *       {@link PartialRefundNotSupportedException} rather than sending a FULL cancel that would
     *       over-refund the customer at the scheme. A scheme with no cancel round-trip at all still raises
     *       {@link SchemeOperationNotSupportedException} first (T2-7). Scheme first = a corridor that cannot
     *       refund never produces a half-applied refund.</li>
     *   <li><b>Float leg</b> ({@code OVERSEAS} only) — see {@link #restorePartnerFloat}. The refunded USD is
     *       the original captured USD pro-rated by the refunded fraction, i.e. reversal AT THE ORIGINAL
     *       LOCKED RATE: no rate is read here, so no rate can have moved since the payment.</li>
     *   <li><b>Status + refund amount.</b> REFUNDED with the CUMULATIVE refunded KRW, which is what
     *       transaction-mgmt persists and settlement's claw-back nets on, and which makes the transaction
     *       discoverable by {@code GET /v1/transactions/refunded}. That commit is also what now emits the
     *       {@code payment.reversed} domain event, so revenue-ledger's reversal runs and the partner is
     *       notified.</li>
     *   <li><b>Reversal journal</b> — for the refunded portion, in the currency the money moved in
     *       (USD for a float reversal, else the collection currency). Non-fatal, as before.</li>
     * </ol>
     *
     * @param requestedAmount   the amount to refund, in {@code requestedCurrency}. {@code null} = refund the
     *                          full refundable remainder (the historical behaviour).
     * @param requestedCurrency ISO currency of {@code requestedAmount}; must be the original collection
     *                          currency (a refund reverses the original booking — it does not re-price).
     */
    public RefundResult refundPayment(String paymentId,
                                      String schemeTxnRef,
                                      PartnerType partnerType,
                                      long partnerId,
                                      String txnRef,
                                      String reason,
                                      String schemeId,
                                      BigDecimal requestedAmount,
                                      String requestedCurrency) {

        TransactionClient.RefundBasis basis = transactionClient.findRefundBasis(txnRef).orElse(null);
        RefundPlan plan = planRefund(txnRef, basis, requestedAmount, requestedCurrency);

        // 1) Scheme-side refund (ZeroPay: the 결제취소/refund path). Same call the cancel uses, plus the
        //    partial amount when this is a partial refund — an adapter that cannot express it refuses here,
        //    before any money moves.
        schemeClient.cancelPayment(new SchemeClient.CancelRequest(
                schemeTxnRef, reason, schemeId, plan.schemePartialAmount(), plan.currency()));

        // 2) Float leg: credit back exactly the refunded USD at the original locked rate.
        BigDecimal returnedUsd = null;
        if (partnerType == PartnerType.OVERSEAS) {
            returnedUsd = restorePartnerFloat(partnerId, txnRef, plan);
        }

        // 3) Status + the cumulative refunded KRW (settlement claw-back magnitude + refund-date query key).
        transactionClient.commitStatus(txnRef,
                TransactionClient.StatusPatch.refund(
                        PaymentStatus.REFUNDED, schemeTxnRef, null, returnedUsd,
                        plan.cumulativeRefundedKrw()));

        // 4) Book the reversal for the REFUNDED PORTION. Prefer the USD actually returned to the float; a
        //    LOCAL partner holds no float, so book it in the collection currency instead of (as before)
        //    booking nothing at all. Both use the existing REVENUE_REVERSAL / RECEIVABLE_PARTNER pair — no
        //    account code is introduced here.
        if (revenueLedgerClient != null) {
            BigDecimal journalAmount = returnedUsd != null && returnedUsd.signum() > 0
                    ? returnedUsd : plan.amount();
            String journalCurrency = returnedUsd != null && returnedUsd.signum() > 0
                    ? "USD" : plan.currency();
            if (journalAmount != null && journalAmount.signum() > 0 && journalCurrency != null) {
                revenueLedgerClient.postReversalJournal(txnRef, journalAmount, journalCurrency);
            }
        }

        return new RefundResult(paymentId, PaymentStatus.REFUNDED, Instant.now(),
                returnedUsd != null ? returnedUsd : plan.refundedUsd(),
                plan.amount(), plan.currency(), plan.cumulativeRefundedAmount(), plan.fullyRefunded());
    }

    /**
     * Validates the requested refund against the original payment and computes the locked-rate USD.
     *
     * <p>Kept separate from {@link #refundPayment} because it is the whole of the T2-6 arithmetic and every
     * refusal in it must be provable in isolation. It reads nothing but the basis it is handed.
     */
    private RefundPlan planRefund(String txnRef,
                                  TransactionClient.RefundBasis basis,
                                  BigDecimal requestedAmount,
                                  String requestedCurrency) {

        if (basis == null) {
            // No readable original. A FULL refund needs no validation (it reverses whatever was captured), so
            // it proceeds exactly as it always has. A PARTIAL refund is refused: we would have to guess both
            // the ceiling and the locked-rate fraction.
            if (requestedAmount != null) {
                throw RefundAmountInvalidException.basisUnavailable(txnRef,
                        "transaction-mgmt did not return the original payment");
            }
            return RefundPlan.fullUnknownBasis();
        }

        BigDecimal original = basis.collectionAmount();
        String currency = basis.collectionCurrency();
        BigDecimal already = basis.alreadyRefunded();

        if (requestedAmount == null) {
            // Full refund of whatever is left.
            BigDecimal remaining = original == null ? null : original.subtract(already);
            if (remaining != null && remaining.signum() <= 0) {
                throw RefundAmountInvalidException.exceedsOriginal(txnRef, BigDecimal.ZERO, already,
                        original, currency);
            }
            BigDecimal cumulative = original != null ? original : already;
            return new RefundPlan(remaining, currency, cumulative, true,
                    remainingUsd(basis, remaining, original), null);
        }

        if (requestedAmount.signum() <= 0) {
            throw RefundAmountInvalidException.invalid(txnRef,
                    "the refund amount must be positive, got " + requestedAmount.toPlainString());
        }
        if (requestedCurrency != null && currency != null
                && !requestedCurrency.equalsIgnoreCase(currency)) {
            // A refund is a reversal of the ORIGINAL booking. Converting here would need a rate, and any
            // rate we chose would not be the locked one — so this is refused, never converted.
            throw RefundAmountInvalidException.invalid(txnRef,
                    "refund currency " + requestedCurrency + " is not the original collection currency "
                            + currency + "; a refund reverses the original booking and cannot re-price it");
        }
        if (original == null) {
            throw RefundAmountInvalidException.basisUnavailable(txnRef,
                    "the original payment carries no collection amount to refund against");
        }

        BigDecimal cumulative = already.add(requestedAmount);
        if (cumulative.compareTo(original) > 0) {
            throw RefundAmountInvalidException.exceedsOriginal(txnRef, requestedAmount, already,
                    original, currency);
        }

        boolean full = cumulative.compareTo(original) == 0;
        BigDecimal capturedUsd = basis.prefundDeductedUsd();
        // Locked-rate pro-rata. A refund that completes the transaction takes the exact remaining USD so a
        // sequence of partials cannot leave a rounding crumb behind on the float.
        BigDecimal refundedUsd = null;
        BigDecimal retainedUsd = null;
        if (capturedUsd != null && capturedUsd.signum() > 0) {
            BigDecimal cumulativeUsd = full
                    ? capturedUsd
                    : capturedUsd.multiply(cumulative)
                            .divide(original, USD_SCALE, RoundingMode.HALF_UP);
            BigDecimal priorUsd = already.signum() == 0
                    ? BigDecimal.ZERO
                    : capturedUsd.multiply(already).divide(original, USD_SCALE, RoundingMode.HALF_UP);
            refundedUsd = cumulativeUsd.subtract(priorUsd);
            retainedUsd = capturedUsd.subtract(cumulativeUsd);
            if (retainedUsd.signum() < 0) {
                retainedUsd = BigDecimal.ZERO;
            }
        }

        return new RefundPlan(requestedAmount, currency, cumulative, full, refundedUsd,
                full ? null : retainedUsd);
    }

    /** The USD backing a full refund of the remaining amount, at the original locked rate. */
    private static BigDecimal remainingUsd(TransactionClient.RefundBasis basis,
                                           BigDecimal remaining,
                                           BigDecimal original) {
        BigDecimal capturedUsd = basis.prefundDeductedUsd();
        if (capturedUsd == null || capturedUsd.signum() <= 0) {
            return null;
        }
        if (original == null || original.signum() == 0 || remaining == null
                || remaining.compareTo(original) == 0) {
            return capturedUsd;
        }
        return capturedUsd.multiply(remaining).divide(original, USD_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Credits the refunded USD back onto the partner's float, composed from prefunding's two
     * <em>existing, idempotent</em> primitives — because prefunding has no partial-reverse and is owned
     * elsewhere:
     *
     * <ol>
     *   <li>{@code reverse(txnRef)} restores the WHOLE original deduction. Idempotent by design: a second
     *       call reports {@code reversedUsd = 0}, so a later partial refund does not re-credit it.</li>
     *   <li>{@code reverse(retainedKey(previousCumulative))} gives back whatever a PRIOR partial refund
     *       re-retained, so cumulative partials compose instead of stacking.</li>
     *   <li>{@code deduct(retainedKey(newCumulative), retainedUsd)} re-takes the portion that is NOT being
     *       refunded. Keyed by the cumulative refunded amount, which strictly increases, so each step has a
     *       distinct idempotency key and a replay is a no-op.</li>
     * </ol>
     *
     * Net float movement is exactly {@code capturedUsd − cumulativeRefundedUsd}, and every step is
     * individually replay-safe. The re-deduction can never overdraw: it always follows a credit of at least
     * as much on the same partner. A full refund performs step 1 only, i.e. it is byte-for-byte the
     * behaviour that existed before T2-6.
     *
     * @return the USD credited back for THIS refund (the plan's figure once known), or the raw reversal
     *         amount on a full refund with no computed basis
     */
    private BigDecimal restorePartnerFloat(long partnerId, String txnRef, RefundPlan plan) {
        PrefundingClient.ReverseResult reversal = prefundingClient.reverse(partnerId, txnRef);
        BigDecimal fullyReversedUsd = reversal != null ? reversal.reversedUsd() : null;

        BigDecimal priorCumulative = plan.priorCumulativeRefundedAmount();
        boolean firstRefund = priorCumulative == null || priorCumulative.signum() == 0;

        if (firstRefund && !plan.isPartial()) {
            // The common full refund: the whole hold goes back on the single reverse this path always did.
            // Nothing was ever re-retained, so there is nothing to unwind.
            return fullyReversedUsd != null && fullyReversedUsd.signum() > 0
                    ? fullyReversedUsd
                    : plan.refundedUsd();   // idempotent replay: report the intended figure, not zero
        }

        // Give back what a PREVIOUS partial refund re-retained. This runs for a completing refund too —
        // otherwise the slice retained by the earlier partial would stay deducted forever, which is exactly
        // the crumb `partialThenRemainder_leavesNoRoundingCrumb` guards against.
        if (!firstRefund) {
            prefundingClient.reverse(partnerId, retainedKey(txnRef, priorCumulative));
        }
        // Re-retain the portion that is NOT refunded (nothing, once the payment is fully refunded).
        BigDecimal retained = plan.retainedUsd();
        if (retained != null && retained.signum() > 0) {
            prefundingClient.deduct(partnerId, retainedKey(txnRef, plan.cumulativeRefundedAmount()),
                    retained);
        }
        return plan.refundedUsd();
    }

    /**
     * Idempotency key for the "not refunded" slice of a partially refunded payment's float. Keyed by the
     * cumulative refunded amount (which strictly increases) so every partial refund gets its own key and a
     * replay of any single step changes nothing.
     */
    private static String retainedKey(String txnRef, BigDecimal cumulativeRefunded) {
        return txnRef + "#REFUND-RETAINED@" + cumulativeRefunded.stripTrailingZeros().toPlainString();
    }

    /**
     * The validated outcome of {@link #planRefund}: what is being refunded, cumulatively how much has been,
     * and the locked-rate USD split.
     *
     * @param amount                   this refund's amount in {@code currency}; null only when the basis was
     *                                 unreadable on a full refund (legacy behaviour)
     * @param currency                 the original collection currency
     * @param cumulativeRefundedAmount total refunded including this refund
     * @param fullyRefunded            true when this refund completes the transaction
     * @param refundedUsd              USD credited back for THIS refund, at the original locked rate
     * @param retainedUsd              USD that must stay deducted (partial refunds only; null when full)
     */
    private record RefundPlan(
            BigDecimal amount,
            String currency,
            BigDecimal cumulativeRefundedAmount,
            boolean fullyRefunded,
            BigDecimal refundedUsd,
            BigDecimal retainedUsd
    ) {
        /** Legacy path: no readable original, so a FULL refund proceeds with no amount arithmetic. */
        static RefundPlan fullUnknownBasis() {
            return new RefundPlan(null, null, null, true, null, null);
        }

        boolean isPartial() {
            return !fullyRefunded;
        }

        /**
         * How much had already been refunded BEFORE this refund, or null when the basis was unreadable.
         * Drives whether a previously re-retained float slice has to be unwound.
         */
        BigDecimal priorCumulativeRefundedAmount() {
            if (cumulativeRefundedAmount == null || amount == null) {
                return null;
            }
            return cumulativeRefundedAmount.subtract(amount);
        }

        /** The amount to put on the scheme instruction: set ONLY for a partial refund. */
        BigDecimal schemePartialAmount() {
            return isPartial() ? amount : null;
        }

        /**
         * The cumulative refunded amount to persist as {@code refundAmountKrw} — only when the money really
         * is KRW. A non-KRW collection currency is left null rather than mislabelled: settlement's claw-back
         * treats this column as KRW, so writing MNT or VND into it would corrupt the settlement file.
         */
        BigDecimal cumulativeRefundedKrw() {
            return "KRW".equalsIgnoreCase(currency) ? cumulativeRefundedAmount : null;
        }
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

    /**
     * Outcome of a successful refund (full OR partial reversal of an APPROVED txn at the locked rate).
     *
     * @param prefundReturnedUsd       USD credited back to the partner float for THIS refund
     * @param refundedAmount           the amount refunded by THIS request, in {@code refundedCurrency}
     * @param refundedCurrency         the original collection currency
     * @param cumulativeRefundedAmount total refunded for the transaction, including this refund
     * @param fullyRefunded            true when the transaction is now refunded in full
     */
    public record RefundResult(
            String paymentId,
            PaymentStatus status,
            Instant refundedAt,
            BigDecimal prefundReturnedUsd,
            BigDecimal refundedAmount,
            String refundedCurrency,
            BigDecimal cumulativeRefundedAmount,
            boolean fullyRefunded
    ) {
        /** Pre-T2-6 shape: a full refund with no amount detail. */
        public RefundResult(String paymentId, PaymentStatus status, Instant refundedAt,
                            BigDecimal prefundReturnedUsd) {
            this(paymentId, status, refundedAt, prefundReturnedUsd, null, null, null, true);
        }
    }
}
