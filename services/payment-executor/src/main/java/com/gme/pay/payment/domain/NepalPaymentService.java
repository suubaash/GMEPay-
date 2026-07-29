package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.GmeremitPaymentService.WalletResult;
import com.gme.pay.payment.domain.client.PrefundingClient;
import com.gme.pay.payment.domain.client.RevenueLedgerClient;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.TransactionClient;
import com.gme.pay.payment.persistence.ExecutionAttemptEntity;
import com.gme.pay.payment.persistence.ExecutionAttemptRepository;
import com.gme.pay.payment.persistence.RevenuePostingFailureStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The Nepal (Fonepay / NepalPay, Khalti issuance-extension) corridor money path — KRW in, NPR out.
 *
 * <h2>What changed (gap T4-1)</h2>
 * This service used to hand the wallet's KRW amount to the Nepal adapter labelled {@code NPR}: no FX,
 * no fee, no prefunding, no revenue. Its own javadoc said "the wallet-labeled KRW must not be sent as
 * NPR in production". It is now the corridor's <b>single</b> money path — {@link FailoverPaymentRouter}
 * delegates every Nepal candidate here rather than passing the amount through — and it runs the same
 * pipeline {@link SendmnPaymentService} runs for KRW→MNT:
 * <ol>
 *   <li><b>Price the corridor</b> from configuration ({@link NepalCorridorPricing}). Live KRW/NPR mid
 *       rate, configured FX margin, configured service fee. Anything missing ⇒ refuse
 *       ({@link CorridorPricingUnavailableException}) — never a guessed number.</li>
 *   <li><b>FX.</b> {@code offerRate = mid × (1 − margin)}; NPR payout = {@code amountKrw × offerRate}
 *       (HALF_UP to paisa). The margin is GME's payout-leg revenue.</li>
 *   <li><b>Fee.</b> {@code chargedKrw = amountKrw + feeKrw}.</li>
 *   <li><b>Regulatory gate</b> (T4-2) on {@code chargedUsd} — the exact USD figure the float debit
 *       below moves, so cap basis == money basis. A breach never reaches the scheme.</li>
 *   <li><b>Prefunding.</b> Deduct {@code chargedUsd} once, keyed on {@code partnerTxnRef};
 *       <b>reversed</b> on a scheme decline / definitive reject, and NEVER reversed on an outcome that
 *       may have landed.</li>
 *   <li><b>Scheme submit</b> of the real NPR payout (the adapter converts NPR→paisa).</li>
 *   <li><b>Transaction</b> committed with the REAL margin/collection values, not nulls.</li>
 *   <li><b>Revenue</b> booked via {@code postRevenueCapture} (FX margin + service charge) — not
 *       {@code postRoundingResidual}, which would bury corridor P&L in {@code REVENUE_ROUNDING}.</li>
 * </ol>
 *
 * <h2>Direction</h2>
 * The corridor collects KRW and pays NPR, so the wallet amount is KRW by default. A caller that
 * declares {@code NPR} is quoting the merchant's requested payout instead, and the KRW charge is
 * derived from it ({@code amountKrw = payoutNpr ÷ offerRate}) — the RECEIVE side of the same rate.
 * Either way FX is applied; there is no pass-through mode left. Any other currency is rejected.
 *
 * <h2>Fail-closed inventory</h2>
 * The corridor refuses, with no float moved and no scheme call, when: the FX margin is unconfigured,
 * the service fee is unconfigured, a live rate is unavailable, prefunding is not wired, or the
 * regulatory limit gate cannot evaluate a configured cap.
 */
@Service
public class NepalPaymentService {

    private static final Logger log = LoggerFactory.getLogger(NepalPaymentService.class);

    /** Router scheme code that selects the Nepal adapter (see {@code NepalRestSchemeClient.SCHEME_CODE}). */
    private static final String SCHEME_ID = "NEPAL";
    /** transaction-mgmt / persistence scheme tag for Nepal rows. */
    private static final String SCHEME_TAG = "nepal";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter KST_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx").withZone(KST);

    /** NPR is a 2-decimal currency (paisa). */
    private static final int NPR_SCALE = 2;
    /** KRW is a 0-decimal currency. */
    private static final int KRW_SCALE = 0;

    private final SchemeClient schemeClient;
    private final ExecutionAttemptRepository attemptRepository;
    private final NepalCorridorPricing pricing;
    @Nullable private final PrefundingClient prefundingClient;
    @Nullable private final TransactionClient transactionClient;
    @Nullable private final RevenueLedgerClient revenueLedgerClient;
    /** Durable sink for a revenue posting that could not be delivered (T2-1). */
    @Nullable private final RevenuePostingFailureStore revenuePostingFailureStore;
    /** T4-2: per-txn + cumulative regulatory limit gate. Never null (see {@link WalletLimitGate#disabled()}). */
    private final WalletLimitGate limitGate;

    /** Production constructor. */
    @Autowired
    public NepalPaymentService(SchemeClient schemeClient,
                               ExecutionAttemptRepository attemptRepository,
                               NepalCorridorPricing pricing,
                               @Nullable PrefundingClient prefundingClient,
                               @Nullable TransactionClient transactionClient,
                               @Nullable RevenueLedgerClient revenueLedgerClient,
                               @Nullable RevenuePostingFailureStore revenuePostingFailureStore,
                               @Nullable WalletLimitGate limitGate) {
        this.schemeClient = schemeClient;
        this.attemptRepository = attemptRepository;
        this.pricing = pricing;
        this.prefundingClient = prefundingClient;
        this.transactionClient = transactionClient;
        this.revenueLedgerClient = revenueLedgerClient;
        this.revenuePostingFailureStore = revenuePostingFailureStore;
        this.limitGate = limitGate != null ? limitGate : WalletLimitGate.disabled();
    }

    /** Test constructor — pricing + prefunding + limit gate, no transaction/revenue sinks. */
    NepalPaymentService(SchemeClient schemeClient,
                       ExecutionAttemptRepository attemptRepository,
                       NepalCorridorPricing pricing,
                       @Nullable PrefundingClient prefundingClient,
                       @Nullable WalletLimitGate limitGate) {
        this(schemeClient, attemptRepository, pricing, prefundingClient, null, null, null, limitGate);
    }

    /**
     * Executes a Nepal wallet payment.
     *
     * @param qrPayload      raw Fonepay/NepalPay EMVCo QR scanned by the wallet
     * @param amount         the amount, denominated in {@code amountCurrency}
     * @param amountCurrency {@code KRW} (or null — the default) for a KRW-quoted send;
     *                       {@code NPR} to quote the merchant payout instead
     * @param userRef        wallet user reference (for logging)
     * @param partner        the wallet issuer: limit subject AND the prefunding float owner
     * @return result — check {@link WalletResult#approved()} before reading scheme fields
     * @throws CorridorPricingUnavailableException the corridor is not priceable (no float moved)
     * @throws TransactionLimitExceededException   per-txn cap breach (no float moved)
     * @throws CumulativeLimitExceededException    rolling/velocity cap breach (no float moved)
     * @throws LimitCheckUnavailableException      a configured cap could not be evaluated
     */
    public WalletResult pay(String qrPayload, BigDecimal amount, @Nullable String amountCurrency,
                            String userRef, WalletPartnerRef partner) {

        String partnerTxnRef = "NEPAL-" + UUID.randomUUID();
        String currency = normalizeCurrency(amountCurrency);
        long partnerId = partner == null ? 0L : partner.id();
        String partnerCode = partner == null ? null : partner.code();

        // ---- Step 1: price the corridor. Refuses (503) rather than inventing a rate/margin/fee. ----
        NepalCorridorPricing.Rates rates;
        try {
            rates = pricing.resolveRates(partnerCode);
        } catch (CorridorPricingUnavailableException ex) {
            log.warn("Nepal payment refused (unpriceable) partner={} ref={}: {}",
                    partnerCode, partnerTxnRef, ex.getMessage());
            persistAttempt(partnerTxnRef, PaymentStatus.FAILED, null);
            throw ex;
        }

        // ---- Step 2: FX. The KRW leg and the NPR leg are ALWAYS related by the offer rate; the
        // KRW-as-NPR pass-through this class used to perform no longer exists in either direction. ----
        BigDecimal amountKrw;
        BigDecimal payAmountNpr;
        if (NepalCorridorPricing.PAYOUT_CURRENCY.equals(currency)) {
            // RECEIVE quote: the caller fixed the merchant payout; derive the KRW to collect.
            payAmountNpr = amount.setScale(NPR_SCALE, RoundingMode.HALF_UP);
            amountKrw = payAmountNpr.divide(rates.offerRateNprPerKrw(), KRW_SCALE, RoundingMode.HALF_UP);
        } else {
            // SEND quote (default): the caller fixed the KRW; derive the NPR payout.
            amountKrw = amount;
            payAmountNpr = amountKrw.multiply(rates.offerRateNprPerKrw())
                    .setScale(NPR_SCALE, RoundingMode.HALF_UP);
        }
        if (payAmountNpr.signum() <= 0 || amountKrw.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Nepal payment amount too small to price: " + amount + " " + currency
                            + " → " + amountKrw + " KRW / " + payAmountNpr + " NPR");
        }

        // The margin GME earns on the payout leg, in KRW then USD terms. Same shape as SENDMN: the
        // customer's KRW is collected at the live rate (no collection-leg spread) and the merchant is
        // paid at offerRate, so the whole spread sits on the payout leg.
        BigDecimal krwPerUsd = rates.krwPerUsd();
        BigDecimal fxMarginKrw = amountKrw.multiply(rates.marginFraction())
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal fxMarginUsd = fxMarginKrw.divide(krwPerUsd, 4, RoundingMode.HALF_UP);

        // ---- Step 3: the configured service fee, resolved on this transaction's USD volume. ----
        BigDecimal amountUsd = amountKrw.divide(krwPerUsd, NepalCorridorPricing.USD_SCALE,
                RoundingMode.HALF_UP);
        NepalCorridorPricing.Fee fee;
        try {
            fee = pricing.resolveFee(partnerCode, amountUsd, krwPerUsd);
        } catch (CorridorPricingUnavailableException ex) {
            log.warn("Nepal payment refused (no fee configured) partner={} ref={}: {}",
                    partnerCode, partnerTxnRef, ex.getMessage());
            persistAttempt(partnerTxnRef, PaymentStatus.FAILED, null);
            throw ex;
        }
        BigDecimal chargedKrw = amountKrw.add(fee.feeKrw());
        BigDecimal chargedUsd = chargedKrw.divide(krwPerUsd, NepalCorridorPricing.USD_SCALE,
                RoundingMode.HALF_UP);

        // ---- Step 4 (T4-2): regulatory gate on the EXACT USD figure the float debit will move. ----
        WalletLimitGate.LimitCharge limitCharge;
        try {
            limitCharge = limitGate.enforceUsd(partner, partnerTxnRef, chargedUsd);
        } catch (TransactionLimitExceededException | CumulativeLimitExceededException
                 | LimitCheckUnavailableException ex) {
            log.warn("Nepal limit gate refused partner={} ref={} chargedUsd={}: {}",
                    partnerCode, partnerTxnRef, chargedUsd, ex.getMessage());
            persistAttempt(partnerTxnRef, PaymentStatus.FAILED, null);
            throw ex;
        }

        // ---- Step 5: prefunding deduct. A corridor with no float ledger cannot transact. ----
        if (prefundingClient == null) {
            limitGate.reverse(limitCharge);
            persistAttempt(partnerTxnRef, PaymentStatus.FAILED, null);
            throw CorridorPricingUnavailableException.notConfigured(NepalCorridorPricing.CORRIDOR,
                    "the prefunding float ledger", "no PrefundingClient is wired into payment-executor");
        }
        try {
            prefundingClient.deduct(partnerId, partnerTxnRef, chargedUsd);
        } catch (InsufficientPrefundingException ex) {
            log.warn("Nepal insufficient prefunding partner={} ref={}: {}",
                    partnerId, partnerTxnRef, ex.getMessage());
            limitGate.reverse(limitCharge);
            persistAttempt(partnerTxnRef, PaymentStatus.FAILED, null);
            return WalletResult.declined(null, "INSUFFICIENT_PREFUNDING");
        }

        // ---- Step 6: submit the REAL NPR payout. No ZeroPay merchant lookup: the Nepal
        // adapter/sim resolves the merchant from the QR itself. ----
        SchemeClient.MpmSubmitResponse schemeResp;
        try {
            schemeResp = schemeClient.submitMpm(
                    new SchemeClient.MpmSubmitRequest(
                            partnerTxnRef,
                            null,           // merchantId unknown here; the adapter resolves it from the QR
                            payAmountNpr,   // NPR; NepalRestSchemeClient converts to paisa (×100, HALF_UP)
                            NepalCorridorPricing.PAYOUT_CURRENCY,
                            SCHEME_ID,
                            qrPayload));
        } catch (SchemeDeclinedException ex) {
            log.warn("Nepal scheme declined userRef={} ref={}: {}", userRef, partnerTxnRef, ex.getMessage());
            reversePrefunding(partnerId, partnerTxnRef);
            limitGate.reverse(limitCharge);
            persistAttempt(partnerTxnRef, PaymentStatus.FAILED, null);
            // Carry the adapter's own reason, not a generic HUB_ERROR.
            return WalletResult.declined(null, ex.schemeErrorCode());
        } catch (PaymentException ex) {
            // Transport / timeout / non-2xx. A TIMEOUT leaves the outcome UNKNOWN, so the money-safe
            // action is to probe rather than assume: the adapter's idempotent status lookup decides
            // whether the float is returned (ADR-016 §4). Never blind-reverse a payment that may have
            // landed — that is a double-spend in the other direction.
            SchemeClient.LookupStatus probe = probeStatus(partnerTxnRef, ex);
            if (probe == SchemeClient.LookupStatus.REJECTED
                    || probe == SchemeClient.LookupStatus.NOT_FOUND) {
                log.warn("Nepal submit failed and lookupStatus={} for {} — returning the float",
                        probe, partnerTxnRef);
                reversePrefunding(partnerId, partnerTxnRef);
                limitGate.reverse(limitCharge);
                persistAttempt(partnerTxnRef, PaymentStatus.FAILED, null);
                return WalletResult.declined(null, ex.getMessage());
            }
            // APPROVED / PENDING / unprobeable: the payment may exist at the scheme. Keep the float
            // and the consumed cap, and surface a non-approved PENDING for ops reconciliation.
            log.warn("Nepal submit outcome UNRESOLVED (probe={}) for {} — prefund KEPT, surfacing PENDING",
                    probe, partnerTxnRef);
            persistAttempt(partnerTxnRef, PaymentStatus.PENDING, null);
            return WalletResult.declined(null, "PENDING");
        }

        // The Nepal adapter maps its response {schemeTxnRef,status,amountPaisa} onto MpmSubmitResponse:
        // schemeApprovalCode <- status, schemeTxnRef <- schemeTxnRef.
        String schemeStatus = schemeResp.schemeApprovalCode();
        if (!"APPROVED".equalsIgnoreCase(schemeStatus)) {
            if ("PENDING".equalsIgnoreCase(schemeStatus)) {
                // Possibly paid: never reverse.
                log.warn("Nepal submit PENDING for {} — prefund KEPT", partnerTxnRef);
                persistAttempt(partnerTxnRef, PaymentStatus.PENDING, schemeResp.schemeTxnRef());
                return WalletResult.declined(null, "PENDING");
            }
            log.warn("Nepal submit not APPROVED (status={}) ref={}", schemeStatus, partnerTxnRef);
            reversePrefunding(partnerId, partnerTxnRef);
            limitGate.reverse(limitCharge);
            persistAttempt(partnerTxnRef, PaymentStatus.FAILED, schemeResp.schemeTxnRef());
            return WalletResult.declined(null,
                    schemeStatus != null ? schemeStatus : "NEPAL_DECLINED");
        }

        Instant approvedAt =
                schemeResp.approvedAt() != null ? schemeResp.approvedAt() : Instant.now();

        // ---- Step 7: transaction-mgmt, carrying the REAL money values (resilient). ----
        String txnRef = partnerTxnRef;
        if (transactionClient != null) {
            try {
                TransactionClient.CreateResult created = transactionClient.createPending(
                        new TransactionClient.CreateRequest(
                                partnerId, partnerTxnRef, SCHEME_TAG, "OVERSEAS", "MPM",
                                payAmountNpr, NepalCorridorPricing.PAYOUT_CURRENCY,
                                amountKrw, NepalCorridorPricing.COLLECTION_CURRENCY,
                                null, null,
                                null));  // the Nepal wallet fee is a partner fee, not a scheme merchant fee
                txnRef = created.txnRef();
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
                                fxMarginUsd,        // payoutMarginUsd — the real KRW→NPR margin
                                chargedUsd,         // collectionUsd — USD equivalent of chargedKrw
                                null,               // costRateColl — not snapshotted on this path
                                null));             // costRatePay
            } catch (RuntimeException ex) {
                log.warn("Nepal transaction-mgmt unavailable for {} — continuing: {}",
                        partnerTxnRef, ex.getMessage());
            }
        }

        // ---- Step 8: book the corridor's REVENUE — FX margin + service charge. ----
        // postRevenueCapture (POST /v1/revenue/capture), NOT postRoundingResidual: the margin and the
        // fee ARE this corridor's P&L, and REVENUE_ROUNDING is reserved for true residuals.
        // feeSharePct = 0 — Nepal pays GME no share of a scheme merchant fee.
        if (revenueLedgerClient != null) {
            LocalDate revenueDate = approvedAt.atZone(KST).toLocalDate();
            try {
                revenueLedgerClient.postRevenueCapture(
                        txnRef,
                        partnerId,
                        SchemeId.resolve(SCHEME_TAG),
                        revenueDate,
                        BigDecimal.ZERO,   // collectionMarginUsd
                        fxMarginUsd,       // payoutMarginUsd — the KRW→NPR FX margin
                        fee.feeKrw(),      // serviceCharge
                        NepalCorridorPricing.COLLECTION_CURRENCY,
                        BigDecimal.ZERO);  // feeSharePct
            } catch (RuntimeException ex) {
                // The money has already moved — the posting must not be lost.
                log.warn("Nepal revenue capture post failed for {}: {}", txnRef, ex.getMessage());
                recordFailedRevenueCapture(txnRef, partnerId, revenueDate, fxMarginUsd, fee, ex);
            }
        }

        persistAttempt(partnerTxnRef, PaymentStatus.APPROVED, schemeResp.schemeTxnRef());

        String committedAt = KST_FMT.format(approvedAt);
        log.info("Nepal APPROVED ref={} {} KRW + {} fee → {} NPR @ {} (margin {} from {})",
                partnerTxnRef, amountKrw, fee.feeKrw(), payAmountNpr,
                rates.offerRateNprPerKrw(), rates.marginFraction(), rates.marginSource());

        return WalletResult.approvedFxInCurrency(
                txnRef,
                schemeResp.schemeTxnRef(),
                null,               // merchantName resolved by the adapter; not surfaced yet (T4-4)
                amountKrw,
                fee.feeKrw(),
                chargedKrw,
                committedAt,
                rates.offerRateNprPerKrw().setScale(6, RoundingMode.HALF_UP),
                payAmountNpr,
                NepalCorridorPricing.PAYOUT_CURRENCY);
    }

    /**
     * Accepted amount currencies: KRW (the corridor's collection currency, the default) and NPR (a
     * payout-quoted request). Anything else is a caller error — silently reinterpreting it is how the
     * KRW-as-NPR defect happened.
     */
    private static String normalizeCurrency(@Nullable String amountCurrency) {
        if (amountCurrency == null || amountCurrency.isBlank()) {
            return NepalCorridorPricing.COLLECTION_CURRENCY;
        }
        String ccy = amountCurrency.trim().toUpperCase(Locale.ROOT);
        if (NepalCorridorPricing.COLLECTION_CURRENCY.equals(ccy)
                || NepalCorridorPricing.PAYOUT_CURRENCY.equals(ccy)) {
            return ccy;
        }
        throw new IllegalArgumentException(
                "Nepal corridor accepts KRW (collection) or NPR (payout) amounts, got: " + ccy);
    }

    /** Idempotent status probe; NOT_FOUND-on-failure is deliberately NOT assumed. */
    @Nullable
    private SchemeClient.LookupStatus probeStatus(String partnerTxnRef, RuntimeException cause) {
        try {
            return schemeClient.lookupStatus(SCHEME_ID, partnerTxnRef);
        } catch (RuntimeException ex) {
            log.warn("Nepal lookupStatus failed for {} after {} — treating the outcome as unresolved",
                    partnerTxnRef, cause.getMessage());
            return null;
        }
    }

    /** Best-effort float reversal; never masks the decline that triggered it. */
    private void reversePrefunding(long partnerId, String partnerTxnRef) {
        if (prefundingClient == null) {
            return;
        }
        try {
            prefundingClient.reverse(partnerId, partnerTxnRef);
        } catch (RuntimeException ex) {
            log.error("Nepal prefunding reverse failed for {}: {}", partnerTxnRef, ex.getMessage());
        }
    }

    /**
     * Persist a revenue capture that could not be delivered so an ops job can replay it (the payload is
     * the exact {@code POST /v1/revenue/capture} body). Never throws — the money has already moved.
     */
    private void recordFailedRevenueCapture(String txnRef, long partnerId, LocalDate revenueDate,
                                            BigDecimal fxMarginUsd, NepalCorridorPricing.Fee fee,
                                            RuntimeException cause) {
        if (revenuePostingFailureStore == null) {
            log.error("Nepal revenue capture for {} is LOST (no failure store wired): {}",
                    txnRef, cause.toString());
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("txnRef", txnRef);
        payload.put("partnerId", partnerId);
        payload.put("schemeId", SchemeId.resolve(SCHEME_TAG));
        payload.put("revenueDate", revenueDate == null ? null : revenueDate.toString());
        payload.put("collectionMarginUsd", BigDecimal.ZERO);
        payload.put("payoutMarginUsd", fxMarginUsd);
        payload.put("serviceChargeAmount", fee.feeKrw());
        payload.put("serviceChargeCcy", NepalCorridorPricing.COLLECTION_CURRENCY);
        payload.put("feeSharePct", BigDecimal.ZERO);
        revenuePostingFailureStore.record(txnRef,
                RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE, payload, cause.toString());
    }

    private void persistAttempt(String partnerTxnRef, PaymentStatus outcome, String schemeTxnRef) {
        try {
            ExecutionAttemptEntity entity = new ExecutionAttemptEntity(
                    partnerTxnRef,
                    0L,
                    partnerTxnRef,
                    SCHEME_TAG,
                    PaymentMode.MPM,
                    outcome,
                    Instant.now());
            entity.setDirection(Direction.OVERSEAS);
            entity.setSchemeTxnRef(schemeTxnRef);
            entity.setCompletedAt(Instant.now());
            attemptRepository.save(entity);
        } catch (RuntimeException ex) {
            log.warn("Nepal failed to persist execution attempt for {}: {}", partnerTxnRef, ex.getMessage());
        }
    }
}
