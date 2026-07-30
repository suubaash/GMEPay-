package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.client.PrefundingClient;
import com.gme.pay.payment.domain.client.QrClient;
import com.gme.pay.payment.domain.client.RateClient;
import com.gme.pay.payment.domain.client.RevenueLedgerClient;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.TransactionClient;
import com.gme.pay.payment.persistence.ExecutionAttemptEntity;
import com.gme.pay.payment.persistence.ExecutionAttemptRepository;
import com.gme.pay.payment.persistence.RevenuePostingFailureStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the SENDMN overseas (KRW→MNT) payment path.
 *
 * <p>Flow:
 * <ol>
 *   <li>Resolve merchant QR (same lenient policy as GMEREMIT domestic).
 *   <li>Validate merchant ACTIVE.
 *   <li>Resolve the corridor's commercial terms through {@link SendmnCorridorPricing} (CFO#11):
 *       live KRW→MNT mid rate from the rate provider, plus the FX margin and the service fee from
 *       config-registry's commercial-terms surface — no longer constants in this class. Nothing is
 *       configured today, so the corridor's historical 2% / ₩500 remain in force as visibly-labelled
 *       code defaults; see {@link SendmnCorridorPricing} for why SENDMN keeps charging them instead
 *       of failing closed the way Nepal does.
 *   <li>Apply the FX margin: {@code offerRate = midRate * (1 - margin)} — partner gets fewer MNT per
 *       KRW. MNT payout = {@code amountKrw * offerRate}, rounded HALF_UP to 0 decimal places.
 *   <li>Service fee: chargedKrw = amountKrw + the resolved fee.
 *   <li>Compute USD equivalent for prefunding: {@code chargedKrw / krwPerUsd}, where the
 *       USD/KRW rate is fetched LIVE from sim-rate-provider; if that fetch fails we fall back
 *       to the conservative {@link #KRW_PER_USD} constant so the prefunding check still proceeds.
 *   <li>Deduct prefunding (USD). If insufficient → return DECLINED, no scheme call.
 *   <li>Submit MPM to the SendMN adapter via the router (schemeId="sendmn", currency = "MNT",
 *       carrying the real MNT payout + the raw scanned qrPayload).
 *   <li>On scheme decline → reverse prefunding. An in-body UNKNOWN outcome is resolved via
 *       {@code lookupStatus} (ADR-016 §4); PENDING/unresolved outcomes keep the prefund.
 *   <li>Record transaction in transaction-mgmt (resilient), carrying the REAL payout-leg margin.
 *   <li>Capture FX margin + service fee as revenue in revenue-ledger (resilient, and durable on
 *       failure via {@code revenue_posting_failures}) — NOT as a rounding residual (T2-1).
 *   <li>Return {@link GmeremitPaymentService.WalletResult} with FX fields populated.
 * </ol>
 *
 * <p>Margin formula note: the offered rate is the rate the partner sees.
 * {@code offerRate = midRate * (1 - fxMargin)}.  For a 2% margin:
 * if mid-rate is 3.5 KRW/MNT (i.e. 1 KRW = 3.5 MNT), the offer rate is
 * 3.5 * 0.98 = 3.43 MNT per KRW.  MNT payout = amountKrw * 3.43.
 */
@Service
public class SendmnPaymentService {

    private static final Logger log = LoggerFactory.getLogger(SendmnPaymentService.class);

    /**
     * Fallback KRW/USD rate for the prefunding deduction when the live USD/KRW rate is unavailable.
     * Sourced from {@link UsdAmountBasis} so the prefunding deduction and the T4-2 limit check cannot
     * drift onto different constants.
     *
     * <p><b>Kept on purpose (CFO#11).</b> Unlike the fee and the margin, this is not a price: it turns
     * the KRW charge into the USD figure the float is debited by and the regulatory cap is measured on.
     * Failing closed on it would take a live corridor down during a rate blip, and its wrongness is
     * already detected downstream (T2-2 {@code fallback_rate_basis_count}). An owner who prefers the
     * outage to the exposure sets {@code gmepay.payment.sendmn.usd-basis-strict=true}.
     */
    static final BigDecimal KRW_PER_USD = UsdAmountBasis.KRW_PER_USD_FALLBACK;

    /**
     * config-registry partner CODE whose {@code partner_limits} row governs this corridor (the
     * SENDMN wallet issuer — the limit subject, see {@link WalletPartnerRef}).
     */
    private static final String PARTNER_CODE = "SENDMN";

    /**
     * Router scheme code selecting the SendMN adapter (see {@code SendmnRestSchemeClient.SCHEME_CODE};
     * the router upper-cases). Was {@code "zeropay"} until Phase 2 of the QR scheme plan —
     * the SENDMN corridor now submits to the real SendMN scheme edge, not ZeroPay.
     */
    private static final String SCHEME_ID = "sendmn";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter KST_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx").withZone(KST);

    private final QrClient qrClient;
    private final RateClient rateClient;
    private final PrefundingClient prefundingClient;
    private final SchemeClient schemeClient;
    private final ExecutionAttemptRepository attemptRepository;
    private final boolean lenientMerchantValidation;
    /** CFO#11: the corridor's commercial terms, resolved per payment instead of hardcoded here. */
    private final SendmnCorridorPricing pricing;
    @Nullable private final TransactionClient transactionClient;
    @Nullable private final RevenueLedgerClient revenueLedgerClient;
    /** T2-1: durable sink for a revenue posting that could not be delivered. */
    @Nullable private final RevenuePostingFailureStore revenuePostingFailureStore;
    /** T4-2: per-txn + cumulative regulatory limit gate. Never null (see {@link WalletLimitGate#disabled()}). */
    private final WalletLimitGate limitGate;

    /**
     * Production constructor.
     * Spring 6: @Autowired must be on the @Value-bearing constructor.
     *
     * <p>CFO#11: the FX margin is no longer a {@code @Value} on this class. It — and the service fee —
     * come from {@link SendmnCorridorPricing}, which reads config-registry first and falls back to the
     * corridor's historical values with a loud, readable provenance rather than silently.
     */
    @Autowired
    public SendmnPaymentService(
            QrClient qrClient,
            RateClient rateClient,
            PrefundingClient prefundingClient,
            SchemeClient schemeClient,
            ExecutionAttemptRepository attemptRepository,
            @Value("${gmepay.payment.merchant-validation:strict}") String merchantValidation,
            SendmnCorridorPricing pricing,
            @Nullable TransactionClient transactionClient,
            @Nullable RevenueLedgerClient revenueLedgerClient,
            @Nullable RevenuePostingFailureStore revenuePostingFailureStore,
            @Nullable WalletLimitGate limitGate) {
        this(qrClient, rateClient, prefundingClient, schemeClient, attemptRepository,
                "lenient".equalsIgnoreCase(merchantValidation), pricing, transactionClient,
                revenueLedgerClient, revenuePostingFailureStore, limitGate);
    }

    /** Test constructor — no @Value needed. */
    SendmnPaymentService(QrClient qrClient,
                         RateClient rateClient,
                         PrefundingClient prefundingClient,
                         SchemeClient schemeClient,
                         ExecutionAttemptRepository attemptRepository,
                         boolean lenientMerchantValidation,
                         BigDecimal fxMargin,
                         @Nullable TransactionClient transactionClient,
                         @Nullable RevenueLedgerClient revenueLedgerClient) {
        this(qrClient, rateClient, prefundingClient, schemeClient, attemptRepository,
                lenientMerchantValidation, fxMargin, transactionClient, revenueLedgerClient, null, null);
    }

    /** Test constructor with the durable revenue-posting failure sink. */
    SendmnPaymentService(QrClient qrClient,
                         RateClient rateClient,
                         PrefundingClient prefundingClient,
                         SchemeClient schemeClient,
                         ExecutionAttemptRepository attemptRepository,
                         boolean lenientMerchantValidation,
                         BigDecimal fxMargin,
                         @Nullable TransactionClient transactionClient,
                         @Nullable RevenueLedgerClient revenueLedgerClient,
                         @Nullable RevenuePostingFailureStore revenuePostingFailureStore) {
        this(qrClient, rateClient, prefundingClient, schemeClient, attemptRepository,
                lenientMerchantValidation, fxMargin, transactionClient, revenueLedgerClient,
                revenuePostingFailureStore, null);
    }

    /**
     * Test constructor with the T4-2 limit gate, taking the margin directly — a convenience that wraps
     * it as {@link SendmnCorridorPricing}'s module-config override, so a test that pins the FX
     * arithmetic does not have to care where the margin came from. The fee resolves to the corridor's
     * code default (₩500), exactly as an unconfigured deployment does.
     */
    SendmnPaymentService(QrClient qrClient,
                         RateClient rateClient,
                         PrefundingClient prefundingClient,
                         SchemeClient schemeClient,
                         ExecutionAttemptRepository attemptRepository,
                         boolean lenientMerchantValidation,
                         BigDecimal fxMargin,
                         @Nullable TransactionClient transactionClient,
                         @Nullable RevenueLedgerClient revenueLedgerClient,
                         @Nullable RevenuePostingFailureStore revenuePostingFailureStore,
                         @Nullable WalletLimitGate limitGate) {
        this(qrClient, rateClient, prefundingClient, schemeClient, attemptRepository,
                lenientMerchantValidation,
                new SendmnCorridorPricing(rateClient, null,
                        fxMargin == null ? "" : fxMargin.toPlainString(), ""),
                transactionClient, revenueLedgerClient, revenuePostingFailureStore, limitGate);
    }

    /** Canonical constructor — the pricing resolver supplied explicitly. */
    SendmnPaymentService(QrClient qrClient,
                         RateClient rateClient,
                         PrefundingClient prefundingClient,
                         SchemeClient schemeClient,
                         ExecutionAttemptRepository attemptRepository,
                         boolean lenientMerchantValidation,
                         SendmnCorridorPricing pricing,
                         @Nullable TransactionClient transactionClient,
                         @Nullable RevenueLedgerClient revenueLedgerClient,
                         @Nullable RevenuePostingFailureStore revenuePostingFailureStore,
                         @Nullable WalletLimitGate limitGate) {
        this.qrClient = qrClient;
        this.rateClient = rateClient;
        this.prefundingClient = prefundingClient;
        this.schemeClient = schemeClient;
        this.attemptRepository = attemptRepository;
        this.lenientMerchantValidation = lenientMerchantValidation;
        this.pricing = pricing;
        this.transactionClient = transactionClient;
        this.revenueLedgerClient = revenueLedgerClient;
        this.revenuePostingFailureStore = revenuePostingFailureStore;
        this.limitGate = limitGate != null ? limitGate : WalletLimitGate.disabled();
    }

    /**
     * Executes the SENDMN overseas (KRW→MNT) payment.
     *
     * @param qrPayload  raw EMVCo QR string
     * @param amountKrw  KRW amount the wallet intends to pay (merchant receives MNT equivalent)
     * @param userRef    wallet user reference
     * @param partnerId  numeric partner ID for prefunding (SENDMN partner)
     * @return result — check {@link GmeremitPaymentService.WalletResult#approved()} before reading fields
     */
    public GmeremitPaymentService.WalletResult pay(
            String qrPayload, BigDecimal amountKrw, String userRef, long partnerId) {

        // Step 1: Resolve merchant
        QrClient.MerchantView merchant;
        try {
            merchant = qrClient.resolve(qrPayload);
        } catch (RuntimeException ex) {
            if (lenientMerchantValidation) {
                log.warn("SENDMN merchant-qr-data unreachable (lenient) — proceeding: {}",
                        ex.getMessage());
                merchant = new QrClient.MerchantView("UNKNOWN", "Unknown Merchant", "MNT",
                        SCHEME_ID, null, true);
            } else {
                throw ex;
            }
        }

        // Step 2: Validate merchant ACTIVE
        if (!merchant.active()) {
            log.warn("SENDMN payment declined: merchant {} inactive (qr={})",
                    merchant.merchantId(), qrPayload);
            return GmeremitPaymentService.WalletResult.declined(
                    merchant.merchantName(), "MERCHANT_INACTIVE");
        }

        // Step 3+4 (CFO#11): price the corridor from CONFIGURATION, not from constants in this file.
        //
        // resolveRates gives the live KRW→MNT mid rate, the resolved FX margin and therefore the offer
        // rate (mid × (1 − margin) — the partner receives fewer MNT per KRW), plus the USD/KRW basis the
        // float leg is measured in. The margin comes from config-registry's partner_fx_config when the
        // owner has entered it; until then it is SENDMN's historical 2%, carried as an explicitly
        // labelled code default (rates.marginFromCodeDefault()) rather than an invisible literal. The
        // corridor deliberately does NOT refuse for want of configured terms the way Nepal does — see
        // SendmnCorridorPricing: Nepal had no price, SENDMN has a working one and live traffic.
        CorridorPricing.Rates rates;
        try {
            rates = pricing.resolveRates(PARTNER_CODE);
        } catch (CorridorPricingUnavailableException ex) {
            // Reachable only on a live-rate outage (or a malformed override), never on absent terms.
            log.warn("SENDMN payment refused (unpriceable): {}", ex.getMessage());
            throw ex;
        }
        BigDecimal offerRate = rates.offerRate();

        // MNT payout = amountKrw * offerRate, rounded HALF_UP to whole MNT
        BigDecimal payAmountMnt = amountKrw.multiply(offerRate)
                .setScale(0, RoundingMode.HALF_UP);

        // FX margin revenue = payout at mid-rate minus payout at offer rate, in KRW terms
        // fxMarginKrw = amountKrw * margin (the KRW the house keeps as margin)
        BigDecimal fxMarginKrw = amountKrw.multiply(rates.marginFraction())
                .setScale(2, RoundingMode.HALF_UP);

        // Step 5: the service fee — from config-registry's partner_fee_schedule when configured,
        // otherwise SENDMN's historical flat ₩500 as a labelled code default. Resolved on this
        // transaction's USD volume so a tiered/bps schedule works the moment one is entered.
        // The USD basis is the SAME rate the float debit below uses (rates.krwPerUsd()).
        BigDecimal krwPerUsd = rates.krwPerUsd();
        BigDecimal amountUsd = amountKrw.divide(krwPerUsd, CorridorPricing.USD_SCALE, RoundingMode.HALF_UP);
        CorridorPricing.Fee fee = pricing.resolveFee(PARTNER_CODE, amountUsd, krwPerUsd);
        BigDecimal feeKrw = fee.feeKrw();
        BigDecimal chargedKrw = amountKrw.add(feeKrw);

        // Step 6: USD equivalent of chargedKrw for the prefunding deduction. rates.krwPerUsd() is the
        // LIVE USD/KRW rate, falling back to KRW_PER_USD when the rate provider is unavailable
        // (rates.usdBasisFallbackUsed() records which happened — see the KRW_PER_USD javadoc for why
        // this fallback is kept while the fee/margin ones were externalised).
        BigDecimal chargedUsd = chargedKrw.divide(krwPerUsd, CorridorPricing.USD_SCALE, RoundingMode.HALF_UP);

        String partnerTxnRef = "SENDMN-" + UUID.randomUUID();

        // Step 6b (T4-2 regulatory gate): per-transaction min/max USD + cumulative daily/monthly/
        // annual USD + the daily velocity count, on the partner's V020 partner_limits row. Runs on
        // chargedUsd — the EXACT figure (and therefore the exact rate basis) the prefunding deduct
        // below moves — so the cap and the money can never be evaluated on different rates. Placed
        // before the deduct and before the scheme submit: a breach moves no float and never touches
        // the scheme. The cumulative charge is keyed on partnerTxnRef, the same reference the deduct
        // and any later reverse use, so a decline nets the cap back out.
        WalletLimitGate.LimitCharge limitCharge;
        try {
            limitCharge = limitGate.enforceUsd(WalletPartnerRef.of(PARTNER_CODE, partnerId),
                    partnerTxnRef, chargedUsd);
        } catch (TransactionLimitExceededException | CumulativeLimitExceededException
                 | LimitCheckUnavailableException ex) {
            log.warn("SENDMN limit gate refused partner={} ref={} chargedUsd={}: {}",
                    partnerId, partnerTxnRef, chargedUsd, ex.getMessage());
            // Persist the attempt exactly like the other declines, then surface the structured
            // limit error (422 TRANSACTION_LIMIT_EXCEEDED / CUMULATIVE_LIMIT_EXCEEDED, or 503
            // LIMIT_CHECK_UNAVAILABLE) — the same shape POST /v1/payments/authorize produces.
            persistAttempt(partnerTxnRef, merchant.merchantId(), amountKrw,
                    PaymentStatus.FAILED, null);
            throw ex;
        }

        // Step 7: Prefunding deduct
        try {
            prefundingClient.deduct(partnerId, partnerTxnRef, chargedUsd);
        } catch (InsufficientPrefundingException ex) {
            log.warn("SENDMN insufficient prefunding for partner={} ref={}: {}",
                    partnerId, partnerTxnRef, ex.getMessage());
            // The cap was charged a moment ago but no payment will happen — give it back.
            limitGate.reverse(limitCharge);
            persistAttempt(partnerTxnRef, merchant.merchantId(), amountKrw,
                    PaymentStatus.FAILED, null);
            return GmeremitPaymentService.WalletResult.declined(
                    merchant.merchantName(), "INSUFFICIENT_PREFUNDING");
        }

        // Step 8: Submit to the SendMN adapter via the router (schemeId=sendmn, MPM). The
        // scheme is MNT-denominated: the adapter's Confirm needs the REAL MNT payout figure
        // (it computes the USD SETTLEMENT_AMOUNT from it), so the FX'd payAmountMnt goes on
        // the wire — the KRW charge stays a hub-side (prefunding/ledger) concern. The raw
        // scanned qrPayload is carried through for the adapter's VerifyQr step.
        SchemeClient.MpmSubmitResponse schemeResp;
        try {
            schemeResp = schemeClient.submitMpm(
                    new SchemeClient.MpmSubmitRequest(
                            partnerTxnRef,
                            merchant.merchantId(),
                            payAmountMnt,
                            "MNT",
                            SCHEME_ID,
                            qrPayload
                    )
            );
        } catch (SchemeDeclinedException ex) {
            log.warn("SENDMN scheme declined for merchant {}: {}",
                    merchant.merchantId(), ex.getMessage());
            // Reverse prefunding
            try {
                prefundingClient.reverse(partnerId, partnerTxnRef);
            } catch (RuntimeException reverseEx) {
                log.error("SENDMN prefunding reverse failed for {}: {}",
                        partnerTxnRef, reverseEx.getMessage());
            }
            // T4-2: the scheme declined, so this txn must not permanently consume cumulative cap.
            limitGate.reverse(limitCharge);
            persistAttempt(partnerTxnRef, merchant.merchantId(), amountKrw,
                    PaymentStatus.FAILED, null);
            return GmeremitPaymentService.WalletResult.declined(
                    merchant.merchantName(), ex.schemeErrorCode());
        }

        // Step 8b: Resolve non-approved in-body outcomes. The SendMN adapter never
        // auto-fails an ambiguous Confirm (ADR-016) — it answers UNKNOWN/PENDING in-body
        // (schemeApprovalCode ← canonical status) instead of throwing. UNKNOWN outcomes are
        // disambiguated with the idempotent lookupStatus probe (ADR-016 §4); a payment that
        // may have landed is NEVER reversed here.
        String schemeStatus = schemeResp.schemeApprovalCode();
        if ("UNKNOWN".equalsIgnoreCase(schemeStatus)) {
            SchemeClient.LookupStatus probe =
                    schemeClient.lookupStatus(SCHEME_ID, partnerTxnRef);
            if (probe == SchemeClient.LookupStatus.APPROVED) {
                log.info("SENDMN submit UNKNOWN but lookupStatus=APPROVED for {} — proceeding",
                        partnerTxnRef);
                // fall through to the approved path below
            } else if (probe == SchemeClient.LookupStatus.REJECTED) {
                // Definitive scheme reject — no money moved; reverse the prefund.
                log.warn("SENDMN submit UNKNOWN resolved to REJECTED for {}", partnerTxnRef);
                try {
                    prefundingClient.reverse(partnerId, partnerTxnRef);
                } catch (RuntimeException reverseEx) {
                    log.error("SENDMN prefunding reverse failed for {}: {}",
                            partnerTxnRef, reverseEx.getMessage());
                }
                // T4-2: definitive scheme reject — return the cumulative cap too.
                limitGate.reverse(limitCharge);
                persistAttempt(partnerTxnRef, merchant.merchantId(), amountKrw,
                        PaymentStatus.FAILED, schemeResp.schemeTxnRef());
                return GmeremitPaymentService.WalletResult.declined(
                        merchant.merchantName(), "SENDMN_REJECTED");
            } else {
                // PENDING / NOT_FOUND(map lost): outcome still unresolved — the Confirm may
                // have landed, so do NOT reverse the prefund and do NOT report approved.
                log.warn("SENDMN submit UNKNOWN unresolved (probe={}) for {} — surfacing PENDING",
                        probe, partnerTxnRef);
                persistAttempt(partnerTxnRef, merchant.merchantId(), amountKrw,
                        PaymentStatus.PENDING, schemeResp.schemeTxnRef());
                return GmeremitPaymentService.WalletResult.declined(
                        merchant.merchantName(), "PENDING");
            }
        } else if ("PENDING".equalsIgnoreCase(schemeStatus)) {
            // Scheme is still processing — not approved, but possibly paid: never reverse.
            log.warn("SENDMN submit PENDING for {} — surfacing PENDING, prefund kept", partnerTxnRef);
            persistAttempt(partnerTxnRef, merchant.merchantId(), amountKrw,
                    PaymentStatus.PENDING, schemeResp.schemeTxnRef());
            return GmeremitPaymentService.WalletResult.declined(
                    merchant.merchantName(), "PENDING");
        }

        Instant approvedAt =
                schemeResp.approvedAt() != null ? schemeResp.approvedAt() : Instant.now();

        // The FX margin expressed in USD — the currency revenue-ledger's revenue record is denominated
        // in (RevenueRecord.fxMarginUsd, 4dp). Converted at the SAME krwPerUsd used for the prefunding
        // deduction so the booked margin and the deducted float are on one rate basis.
        BigDecimal fxMarginUsd = fxMarginKrw.divide(krwPerUsd, 4, RoundingMode.HALF_UP);

        // T4-4: the merchant name to PERSIST. SendMN's own verify-qr answer wins over the hub's
        // merchant-qr-data row — it is the name the scheme will show on the Mongolian side, and on the
        // lenient branch above the hub value is the synthesised "Unknown Merchant" placeholder, which
        // realOrNull refuses so a receipt never claims a merchant we never looked up.
        String persistedMerchantName =
                MerchantNames.realOrNull(schemeResp.merchantName(), merchant.merchantName());

        // Step 9: Record in transaction-mgmt (resilient)
        String txnRef = partnerTxnRef;
        if (transactionClient != null) {
            try {
                TransactionClient.CreateResult created = transactionClient.createPending(
                        new TransactionClient.CreateRequest(
                                partnerId, partnerTxnRef, SCHEME_ID, "OVERSEAS", "MPM",
                                payAmountMnt, "MNT", amountKrw, "KRW",
                                merchant.merchantId(), null,
                                null,   // SENDMN wallet uses its own fee model, not the rate-based merchant fee
                                persistedMerchantName));
                txnRef = created.txnRef();
                // T2-1: the APPROVED commit used to pass the 5-arg StatusPatch, i.e. NULL margins — so
                // transaction-mgmt persisted a zero-revenue transaction and the payment.approved event
                // it emits carried nothing for revenue-ledger to capture. The real margin now rides the
                // commit, mirroring what the orchestrated path does with its locked-quote margins.
                //
                // The margin sits on the PAYOUT leg: the customer's KRW is collected at the live rate
                // (no collection-leg spread) while the merchant is paid MNT at offerRate = mid × (1 −
                // margin), so the KRW→MNT conversion is where GME earns. Settlement-booking fields stay
                // null — SENDMN has no per-partner settlement-rounding lock (its own residual is
                // register item T2-1's sibling, gap #15).
                transactionClient.commitStatus(txnRef,
                        new TransactionClient.StatusPatch(
                                PaymentStatus.APPROVED,
                                schemeResp.schemeTxnRef(),
                                schemeResp.schemeApprovalCode(),
                                chargedUsd,
                                approvedAt,
                                null,               // bookedSettlementAmount — no partner booking rule
                                null,               // settlementRoundingMode
                                null,               // roundingResidual
                                BigDecimal.ZERO,    // collectionMarginUsd — KRW collected at live rate
                                fxMarginUsd,        // payoutMarginUsd — the real KRW→MNT margin
                                chargedUsd,         // collectionUsd — USD equivalent of chargedKrw
                                null,               // costRateColl — not snapshotted on this path
                                null));             // costRatePay
            } catch (RuntimeException ex) {
                log.warn("SENDMN transaction-mgmt unavailable for {} — continuing: {}",
                        partnerTxnRef, ex.getMessage());
            }
        }

        // Step 10: Book the FX margin + service fee as REVENUE (T2-1).
        //
        // This used to be two postRoundingResidual calls, which landed both amounts in the
        // REVENUE_ROUNDING account — "rounding gain/loss vs partner booking" per MONEY_CONVENTION.md.
        // SENDMN's margin and its ₩500 fee are neither rounding nor residual: they are the corridor's
        // entire P&L, and booking them there made SENDMN revenue indistinguishable from rounding noise
        // while leaving the FX-margin / service-charge accounts empty.
        //
        // The correct call is the same one the orchestrated (GMEREMIT/ZeroPay) confirm path uses:
        // postRevenueCapture → POST /v1/revenue/capture, which records the margin as fxMarginUsd
        // (REVENUE_FX_MARGIN-equivalent) and the fee as a service charge in its own currency, keyed
        // idempotently on txnRef. REVENUE_ROUNDING is now left for true residuals only.
        //
        // feeSharePct = 0: SENDMN pays GME no share of a scheme merchant fee (the 0.70 default belongs
        // to the ZeroPay merchant-fee split), so recording 0.70 here would misstate the corridor.
        if (revenueLedgerClient != null) {
            LocalDate revenueDate = approvedAt.atZone(KST).toLocalDate();
            try {
                revenueLedgerClient.postRevenueCapture(
                        txnRef,
                        partnerId,
                        SchemeId.resolve(SCHEME_ID),
                        revenueDate,
                        BigDecimal.ZERO,   // collectionMarginUsd
                        fxMarginUsd,       // payoutMarginUsd — the KRW→MNT FX margin
                        feeKrw,            // serviceCharge — the resolved corridor fee
                        "KRW",             // serviceChargeCcy
                        BigDecimal.ZERO);  // feeSharePct
            } catch (RuntimeException ex) {
                // The production client swallows its own transport failures and records them durably;
                // this catch covers any other implementation that throws. Either way the posting must
                // NOT be lost: the money has already moved.
                log.warn("SENDMN revenue capture post failed for {}: {}", txnRef, ex.getMessage());
                recordFailedRevenueCapture(txnRef, partnerId, revenueDate, fxMarginUsd, feeKrw, ex);
            }
        }

        // Step 11: Persist local execution attempt
        persistAttempt(partnerTxnRef, merchant.merchantId(), amountKrw,
                PaymentStatus.APPROVED, schemeResp.schemeTxnRef());

        // Step 12: Build result
        String committedAt = KST_FMT.format(
                schemeResp.approvedAt() != null ? schemeResp.approvedAt() : Instant.now());

        // CFO#11: every approval states which commercial terms priced it, so "what did we charge and
        // on whose authority" is answerable from the transaction log and not only from the source tree.
        log.info("SENDMN APPROVED ref={} {} KRW + {} fee → {} MNT @ {} (margin {} from {}; fee from {};"
                        + " usdBasisFallback={})",
                partnerTxnRef, amountKrw, feeKrw, payAmountMnt, offerRate,
                rates.marginFraction(), rates.marginSource(), fee.source(),
                rates.usdBasisFallbackUsed());

        return GmeremitPaymentService.WalletResult.approvedFx(
                schemeResp.schemeTxnRef(),
                // T4-4: the SAME value that was just persisted, so the synchronous response and the
                // later transaction-detail read of this payment can never disagree about who was paid.
                persistedMerchantName,
                amountKrw,
                feeKrw,
                chargedKrw,
                committedAt,
                offerRate.setScale(6, RoundingMode.HALF_UP),
                payAmountMnt
        );
    }

    /**
     * T2-1 durability: persist a revenue capture that could not be delivered so an ops job can replay it
     * (the payload is the exact {@code POST /v1/revenue/capture} body). Before this, a failed posting was
     * a log line and the corridor's revenue for that transaction simply ceased to exist.
     *
     * <p>Never throws — the payment has already moved money. When no store is wired (minimal/test
     * context) this degrades to the log line only.
     */
    private void recordFailedRevenueCapture(String txnRef, long partnerId, LocalDate revenueDate,
                                            BigDecimal fxMarginUsd, BigDecimal feeKrw,
                                            RuntimeException cause) {
        if (revenuePostingFailureStore == null) {
            log.error("SENDMN revenue capture for {} is LOST (no failure store wired): {}",
                    txnRef, cause.toString());
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("txnRef", txnRef);
        payload.put("partnerId", partnerId);
        payload.put("schemeId", SchemeId.resolve(SCHEME_ID));
        payload.put("revenueDate", revenueDate == null ? null : revenueDate.toString());
        payload.put("collectionMarginUsd", BigDecimal.ZERO);
        payload.put("payoutMarginUsd", fxMarginUsd);
        payload.put("serviceChargeAmount", feeKrw);
        payload.put("serviceChargeCcy", "KRW");
        payload.put("feeSharePct", BigDecimal.ZERO);
        revenuePostingFailureStore.record(txnRef,
                RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE, payload, cause.toString());
    }

    private void persistAttempt(String partnerTxnRef, String merchantId, BigDecimal amountKrw,
                                 PaymentStatus outcome, String schemeTxnRef) {
        try {
            ExecutionAttemptEntity entity = new ExecutionAttemptEntity(
                    partnerTxnRef,
                    0L,
                    partnerTxnRef,
                    SCHEME_ID,
                    PaymentMode.MPM,
                    outcome,
                    Instant.now()
            );
            entity.setDirection(Direction.OVERSEAS);
            entity.setSchemeTxnRef(schemeTxnRef);
            entity.setCompletedAt(Instant.now());
            attemptRepository.save(entity);
        } catch (RuntimeException ex) {
            log.warn("SENDMN failed to persist execution attempt for {}: {}",
                    partnerTxnRef, ex.getMessage());
        }
    }
}
