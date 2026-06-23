package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.client.PrefundingClient;
import com.gme.pay.payment.domain.client.QrClient;
import com.gme.pay.payment.domain.client.RateClient;
import com.gme.pay.payment.domain.client.RevenueLedgerClient;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.TransactionClient;
import com.gme.pay.payment.persistence.ExecutionAttemptEntity;
import com.gme.pay.payment.persistence.ExecutionAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Orchestrates the SENDMN overseas (KRW→MNT) payment path.
 *
 * <p>Flow:
 * <ol>
 *   <li>Resolve merchant QR (same lenient policy as GMEREMIT domestic).
 *   <li>Validate merchant ACTIVE.
 *   <li>Fetch live KRW→MNT mid-rate from sim-rate-provider (:9101).
 *   <li>Apply FX margin ({@code gmepay.payment.sendmn.fx-margin}, default 2%):
 *       {@code offerRate = midRate * (1 - margin)} — partner gets fewer MNT per KRW.
 *       MNT payout = {@code amountKrw * offerRate}, rounded HALF_UP to 0 decimal places.
 *   <li>Fixed service fee: ₩500. chargedKrw = amountKrw + 500.
 *   <li>Compute USD equivalent for prefunding: {@code chargedKrw / krwPerUsd}, where the
 *       USD/KRW rate is fetched LIVE from sim-rate-provider; if that fetch fails we fall back
 *       to the conservative {@link #KRW_PER_USD} constant so the prefunding check still proceeds.
 *   <li>Deduct prefunding (USD). If insufficient → return DECLINED, no scheme call.
 *   <li>Submit MPM to ZeroPay (same as domestic path, currency = "KRW").
 *   <li>On scheme decline → reverse prefunding.
 *   <li>Record transaction in transaction-mgmt (resilient).
 *   <li>Book FX margin + fee to revenue-ledger (resilient).
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

    /** Fixed service fee in KRW. */
    static final BigDecimal FEE_KRW = new BigDecimal("500");

    /** Fallback KRW/USD rate for the prefunding deduction when the live USD/KRW rate is unavailable. */
    static final BigDecimal KRW_PER_USD = new BigDecimal("1350");

    private static final String SCHEME_ID = "zeropay";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter KST_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx").withZone(KST);

    private final QrClient qrClient;
    private final RateClient rateClient;
    private final PrefundingClient prefundingClient;
    private final SchemeClient schemeClient;
    private final ExecutionAttemptRepository attemptRepository;
    private final boolean lenientMerchantValidation;
    private final BigDecimal fxMargin;
    @Nullable private final TransactionClient transactionClient;
    @Nullable private final RevenueLedgerClient revenueLedgerClient;

    /**
     * Production constructor.
     * Spring 6: @Autowired must be on the @Value-bearing constructor.
     */
    @Autowired
    public SendmnPaymentService(
            QrClient qrClient,
            RateClient rateClient,
            PrefundingClient prefundingClient,
            SchemeClient schemeClient,
            ExecutionAttemptRepository attemptRepository,
            @Value("${gmepay.payment.merchant-validation:strict}") String merchantValidation,
            @Value("${gmepay.payment.sendmn.fx-margin:0.02}") BigDecimal fxMargin,
            @Nullable TransactionClient transactionClient,
            @Nullable RevenueLedgerClient revenueLedgerClient) {
        this.qrClient = qrClient;
        this.rateClient = rateClient;
        this.prefundingClient = prefundingClient;
        this.schemeClient = schemeClient;
        this.attemptRepository = attemptRepository;
        this.lenientMerchantValidation = "lenient".equalsIgnoreCase(merchantValidation);
        this.fxMargin = fxMargin;
        this.transactionClient = transactionClient;
        this.revenueLedgerClient = revenueLedgerClient;
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
        this.qrClient = qrClient;
        this.rateClient = rateClient;
        this.prefundingClient = prefundingClient;
        this.schemeClient = schemeClient;
        this.attemptRepository = attemptRepository;
        this.lenientMerchantValidation = lenientMerchantValidation;
        this.fxMargin = fxMargin;
        this.transactionClient = transactionClient;
        this.revenueLedgerClient = revenueLedgerClient;
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

        // Step 3: Fetch KRW→MNT live rate
        RateClient.LiveRate liveRate = rateClient.fetchLiveRate("KRW", "MNT");
        BigDecimal midRate = liveRate.rate(); // e.g. 3.5 (1 KRW = 3.5 MNT)

        // Step 4: Apply FX margin — offer rate is lower (partner receives fewer MNT)
        // offerRate = midRate * (1 - fxMargin)
        BigDecimal offerRate = midRate.multiply(BigDecimal.ONE.subtract(fxMargin),
                new MathContext(10, RoundingMode.HALF_UP));

        // MNT payout = amountKrw * offerRate, rounded HALF_UP to whole MNT
        BigDecimal payAmountMnt = amountKrw.multiply(offerRate)
                .setScale(0, RoundingMode.HALF_UP);

        // FX margin revenue = payout at mid-rate minus payout at offer rate, in KRW terms
        // fxMarginKrw = amountKrw * fxMargin (the KRW the house keeps as margin)
        BigDecimal fxMarginKrw = amountKrw.multiply(fxMargin)
                .setScale(2, RoundingMode.HALF_UP);

        // Step 5: Fixed service fee
        BigDecimal chargedKrw = amountKrw.add(FEE_KRW);

        // Step 6: Compute USD equivalent of chargedKrw for prefunding deduction, using the LIVE
        // USD/KRW rate (falls back to KRW_PER_USD if the rate provider is unavailable).
        BigDecimal krwPerUsd = fetchKrwPerUsd();
        BigDecimal chargedUsd = chargedKrw.divide(krwPerUsd, 8, RoundingMode.HALF_UP);

        // Step 7: Prefunding deduct
        String partnerTxnRef = "SENDMN-" + UUID.randomUUID();
        try {
            prefundingClient.deduct(partnerId, partnerTxnRef, chargedUsd);
        } catch (InsufficientPrefundingException ex) {
            log.warn("SENDMN insufficient prefunding for partner={} ref={}: {}",
                    partnerId, partnerTxnRef, ex.getMessage());
            persistAttempt(partnerTxnRef, merchant.merchantId(), amountKrw,
                    PaymentStatus.FAILED, null);
            return GmeremitPaymentService.WalletResult.declined(
                    merchant.merchantName(), "INSUFFICIENT_PREFUNDING");
        }

        // Step 8: Submit to ZeroPay (MPM, with KRW amount — scheme is KRW-denominated)
        SchemeClient.MpmSubmitResponse schemeResp;
        try {
            schemeResp = schemeClient.submitMpm(
                    new SchemeClient.MpmSubmitRequest(
                            partnerTxnRef,
                            merchant.merchantId(),
                            amountKrw,
                            "KRW",
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
            persistAttempt(partnerTxnRef, merchant.merchantId(), amountKrw,
                    PaymentStatus.FAILED, null);
            return GmeremitPaymentService.WalletResult.declined(
                    merchant.merchantName(), ex.schemeErrorCode());
        }

        // Step 9: Record in transaction-mgmt (resilient)
        String txnRef = partnerTxnRef;
        if (transactionClient != null) {
            try {
                TransactionClient.CreateResult created = transactionClient.createPending(
                        new TransactionClient.CreateRequest(
                                partnerId, partnerTxnRef, SCHEME_ID, "OVERSEAS", "MPM",
                                payAmountMnt, "MNT", amountKrw, "KRW",
                                merchant.merchantId(), null,
                                null));  // SENDMN wallet uses its own fee model, not the rate-based merchant fee
                txnRef = created.txnRef();
                transactionClient.commitStatus(txnRef,
                        new TransactionClient.StatusPatch(
                                PaymentStatus.APPROVED,
                                schemeResp.schemeTxnRef(),
                                schemeResp.schemeApprovalCode(),
                                chargedUsd,
                                schemeResp.approvedAt() != null ? schemeResp.approvedAt() : Instant.now()));
            } catch (RuntimeException ex) {
                log.warn("SENDMN transaction-mgmt unavailable for {} — continuing: {}",
                        partnerTxnRef, ex.getMessage());
            }
        }

        // Step 10: Book FX margin + fee to revenue-ledger (resilient)
        if (revenueLedgerClient != null) {
            // Book FX margin
            try {
                revenueLedgerClient.postRoundingResidual(txnRef, fxMarginKrw, "KRW");
            } catch (RuntimeException ex) {
                log.warn("SENDMN revenue-ledger FX margin post failed for {}: {}",
                        txnRef, ex.getMessage());
            }
            // Book service fee
            try {
                revenueLedgerClient.postRoundingResidual(txnRef + "-FEE", FEE_KRW, "KRW");
            } catch (RuntimeException ex) {
                log.warn("SENDMN revenue-ledger fee post failed for {}: {}",
                        txnRef, ex.getMessage());
            }
        }

        // Step 11: Persist local execution attempt
        persistAttempt(partnerTxnRef, merchant.merchantId(), amountKrw,
                PaymentStatus.APPROVED, schemeResp.schemeTxnRef());

        // Step 12: Build result
        String committedAt = KST_FMT.format(
                schemeResp.approvedAt() != null ? schemeResp.approvedAt() : Instant.now());

        return GmeremitPaymentService.WalletResult.approvedFx(
                schemeResp.schemeTxnRef(),
                merchant.merchantName(),
                amountKrw,
                FEE_KRW,
                chargedKrw,
                committedAt,
                offerRate.setScale(6, RoundingMode.HALF_UP),
                payAmountMnt
        );
    }

    /**
     * Live USD/KRW rate from sim-rate-provider for the prefunding (USD) deduction. Falls back to the
     * conservative {@link #KRW_PER_USD} constant when the rate provider is unreachable or returns a
     * non-positive rate, so the SENDMN path degrades gracefully instead of failing the payment.
     */
    private BigDecimal fetchKrwPerUsd() {
        try {
            RateClient.LiveRate r = rateClient.fetchLiveRate("USD", "KRW");
            if (r != null && r.rate() != null && r.rate().signum() > 0) {
                return r.rate();
            }
            log.warn("SENDMN live USD/KRW rate empty — falling back to {} KRW/USD", KRW_PER_USD);
        } catch (RuntimeException ex) {
            log.warn("SENDMN live USD/KRW rate unavailable ({}) — falling back to {} KRW/USD",
                    ex.getMessage(), KRW_PER_USD);
        }
        return KRW_PER_USD;
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
