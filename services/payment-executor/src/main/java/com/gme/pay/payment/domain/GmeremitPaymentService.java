package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.client.QrClient;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Orchestrates the GMERemit domestic (KRW→KRW) happy-path payment.
 *
 * <p>Flow:
 * <ol>
 *   <li>Parse merchant QR via the scheme adapter's QR decode (SchemeClient passes raw payload).
 *       For the wallet entry point the QR client is called with the raw payload.
 *   <li>Validate merchant ACTIVE (audit B3). If merchant-validation is configured as
 *       {@code lenient} and the QR service is unreachable the merchant is treated as present.
 *   <li>Submit MPM payment to ZeroPay (authorize + commit) via SchemeClient.
 *   <li>Compute fee: ₩500 fixed. chargedKrw = amountKrw + feeKrw.
 *   <li>Persist execution attempt locally (H2 / Postgres depending on profile).
 *   <li>Return WalletResult.
 * </ol>
 *
 * <p>Remote services (transaction-mgmt, revenue-ledger) are NOT required for this path;
 * failures are logged but do not hard-fail the sandbox.
 */
@Service
public class GmeremitPaymentService {

    private static final Logger log = LoggerFactory.getLogger(GmeremitPaymentService.class);

    /** Fixed domestic service fee in KRW (₩500). */
    public static final BigDecimal FEE_KRW = new BigDecimal("500");

    private static final String SCHEME_ID = "zeropay";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter KST_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx").withZone(KST);

    private final QrClient qrClient;
    private final SchemeClient schemeClient;
    private final ExecutionAttemptRepository attemptRepository;
    /**
     * When true, a merchant-lookup miss/unreachable synthesizes a placeholder UNKNOWN merchant so the
     * payment can proceed. This is a DEV-ONLY escape hatch — gated on the explicit
     * {@code gmepay.payment.dev-synth-merchant} flag (default false). In the default STRICT behavior the
     * lookup failure HARD-FAILS with {@link MerchantNotFoundException} instead of synthesizing.
     */
    private final boolean devSynthMerchant;
    @Nullable private final TransactionClient transactionClient;
    @Nullable private final RevenueLedgerClient revenueLedgerClient;

    /**
     * Production constructor.
     * Spring 6 rule: when there are 2+ constructors the @Autowired annotation must be on
     * the production (@Value) constructor.
     *
     * <p>Strict is the only non-dev behavior: synthesizing an UNKNOWN merchant on a lookup miss is
     * gated on the explicit {@code gmepay.payment.dev-synth-merchant} flag (default false). The legacy
     * {@code gmepay.payment.merchant-validation=lenient} setting alone NO LONGER enables synth — the
     * explicit dev flag is required, so a stray lenient config in prod cannot weaken merchant validation.
     */
    @Autowired
    public GmeremitPaymentService(
            QrClient qrClient,
            SchemeClient schemeClient,
            ExecutionAttemptRepository attemptRepository,
            @Value("${gmepay.payment.dev-synth-merchant:false}") boolean devSynthMerchant,
            @Nullable TransactionClient transactionClient,
            @Nullable RevenueLedgerClient revenueLedgerClient) {
        this.qrClient = qrClient;
        this.schemeClient = schemeClient;
        this.attemptRepository = attemptRepository;
        this.devSynthMerchant = devSynthMerchant;
        this.transactionClient = transactionClient;
        this.revenueLedgerClient = revenueLedgerClient;
    }

    /**
     * Test-only constructor (skips @Value; no transaction/revenue clients). Delegates to the full
     * constructor with null collaborators so there is a single field-assignment site.
     */
    GmeremitPaymentService(QrClient qrClient,
                           SchemeClient schemeClient,
                           ExecutionAttemptRepository attemptRepository,
                           boolean devSynthMerchant) {
        this(qrClient, schemeClient, attemptRepository, devSynthMerchant, null, null);
    }

    /**
     * Executes the GMERemit domestic payment.
     *
     * @param qrPayload   raw EMVCo QR string scanned by the wallet
     * @param amountKrw   amount in KRW the wallet intends to pay
     * @param userRef     wallet user reference (for logging/idempotency)
     * @return result — check {@link WalletResult#approved()} before reading scheme fields
     */
    public WalletResult pay(String qrPayload, BigDecimal amountKrw, String userRef) {

        // Step 1: Resolve merchant from QR. STRICT (default, non-dev) is the only safe behavior — a
        // lookup miss/unreachable HARD-FAILS with MERCHANT_NOT_FOUND (404). Synthesizing a placeholder
        // UNKNOWN merchant is a dev-only escape hatch behind the explicit gmepay.payment.dev-synth-merchant
        // flag, so a stray config can never let a payment proceed against an unverified merchant in prod.
        QrClient.MerchantView merchant;
        try {
            merchant = qrClient.resolve(qrPayload);
        } catch (MerchantNotFoundException ex) {
            // Merchant definitively unknown (merchant-qr-data 404) — a clean business decline,
            // NOT a server error and NOT something lenient mode should mask: lenient covers
            // "merchant-qr-data unreachable", never "this merchant does not exist".
            log.warn("Payment declined: no merchant registered for qr={}", qrPayload);
            return WalletResult.declined(null, "MERCHANT_NOT_FOUND");
        } catch (RuntimeException ex) {
            if (devSynthMerchant) {
                log.warn("merchant-qr-data unreachable (DEV synth-merchant flag ON) — proceeding with "
                        + "unknown merchant: {}", ex.getMessage());
                // Synthesise a placeholder merchant so the payment can proceed (DEV ONLY).
                merchant = new QrClient.MerchantView("UNKNOWN", "Unknown Merchant", "KRW", SCHEME_ID, null, true);
            } else {
                log.warn("merchant resolution failed (strict) for qr={}: {}", qrPayload, ex.getMessage());
                throw new MerchantNotFoundException(
                        "merchant could not be resolved for QR (strict mode): " + ex.getMessage(), ex);
            }
        }

        // Step 2: Validate merchant ACTIVE (audit B3)
        if (!merchant.active()) {
            log.warn("Payment declined: merchant {} is DEACTIVATED (qr={})", merchant.merchantId(), qrPayload);
            return WalletResult.declined(merchant.merchantName(), "MERCHANT_INACTIVE");
        }

        // Step 3: Submit to ZeroPay
        String partnerTxnRef = "GMEREMIT-" + UUID.randomUUID();
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
            log.warn("Scheme declined payment for merchant {}: {}", merchant.merchantId(), ex.getMessage());
            persistAttempt(partnerTxnRef, merchant.merchantId(), amountKrw, PaymentStatus.FAILED, null);
            return WalletResult.declined(merchant.merchantName(), ex.schemeErrorCode());
        }

        // Step 4: Compute fee
        BigDecimal chargedKrw = amountKrw.add(FEE_KRW);

        // Step 5: Record transaction in transaction-mgmt (resilient: log + continue on failure)
        String txnRef = partnerTxnRef; // use partnerTxnRef as our txnRef for domestic
        if (transactionClient != null) {
            try {
                TransactionClient.CreateResult created = transactionClient.createPending(
                        new TransactionClient.CreateRequest(
                                0L, partnerTxnRef, SCHEME_ID, "DOMESTIC", "MPM",
                                amountKrw, "KRW", amountKrw, "KRW",
                                merchant.merchantId(), null,
                                null));  // domestic wallet uses a flat FEE_KRW, not the rate-based merchant fee
                txnRef = created.txnRef();
                transactionClient.commitStatus(txnRef,
                        new TransactionClient.StatusPatch(
                                PaymentStatus.APPROVED,
                                schemeResp.schemeTxnRef(),
                                schemeResp.schemeApprovalCode(),
                                null,
                                schemeResp.approvedAt() != null ? schemeResp.approvedAt() : Instant.now()));
            } catch (RuntimeException ex) {
                log.warn("transaction-mgmt unavailable for {} — continuing (sandbox): {}",
                        partnerTxnRef, ex.getMessage());
            }
        }

        // Step 6: Book the ₩500 service fee as revenue (non-blocking)
        if (revenueLedgerClient != null) {
            try {
                revenueLedgerClient.postRoundingResidual(txnRef, FEE_KRW, "KRW");
            } catch (RuntimeException ex) {
                log.warn("revenue-ledger unavailable for {} — continuing (sandbox): {}",
                        txnRef, ex.getMessage());
            }
        }

        // Step 7: Persist execution attempt (H2 in sandbox, Postgres in production)
        persistAttempt(partnerTxnRef, merchant.merchantId(), amountKrw, PaymentStatus.APPROVED, schemeResp.schemeTxnRef());

        // Step 8: Build result
        String committedAt = KST_FMT.format(
                schemeResp.approvedAt() != null ? schemeResp.approvedAt() : Instant.now());

        return WalletResult.approved(
                txnRef,
                schemeResp.schemeTxnRef(),
                merchant.merchantName(),
                amountKrw,
                FEE_KRW,
                chargedKrw,
                committedAt
        );
    }

    private void persistAttempt(String partnerTxnRef, String merchantId, BigDecimal amountKrw,
                                 PaymentStatus outcome, String schemeTxnRef) {
        try {
            ExecutionAttemptEntity entity = new ExecutionAttemptEntity(
                    partnerTxnRef,
                    0L, // partnerId — GMEREMIT is partner 0 in sandbox
                    partnerTxnRef,
                    SCHEME_ID,
                    PaymentMode.MPM,
                    outcome,
                    Instant.now()
            );
            entity.setSchemeTxnRef(schemeTxnRef);
            entity.setCompletedAt(Instant.now());
            attemptRepository.save(entity);
        } catch (RuntimeException ex) {
            // Non-blocking: log and continue (sandbox may not have all tables)
            log.warn("Failed to persist execution attempt for {}: {}", partnerTxnRef, ex.getMessage());
        }
    }

    // ---- result type ----

    /**
     * Outcome of a {@link #pay} call.
     *
     * <p>Use the static factories {@link #approved}, {@link #approvedFx} and
     * {@link #declined} to construct instances. FX fields are null for domestic payments.
     */
    public record WalletResult(
            boolean approved,
            /** transaction-mgmt reference — the handle to GET /v1/transactions/{txnRef} for the full value breakdown. */
            String txnRef,
            String schemeTxnRef,
            String merchantName,
            BigDecimal payAmountKrw,
            BigDecimal feeKrw,
            BigDecimal chargedKrw,
            String committedAt,
            String declineReason,
            // FX-specific fields (null for domestic KRW→KRW payments)
            Boolean fxApplied,
            BigDecimal fxRate,
            BigDecimal payAmountMnt
    ) {
        /** Factory for domestic KRW→KRW approved results. */
        public static WalletResult approved(String txnRef,
                                            String schemeTxnRef,
                                            String merchantName,
                                            BigDecimal payAmountKrw,
                                            BigDecimal feeKrw,
                                            BigDecimal chargedKrw,
                                            String committedAt) {
            return new WalletResult(true, txnRef, schemeTxnRef, merchantName,
                    payAmountKrw, feeKrw, chargedKrw, committedAt, null,
                    null, null, null);
        }

        /** Factory for FX (overseas) approved results. */
        public static WalletResult approvedFx(String schemeTxnRef,
                                              String merchantName,
                                              BigDecimal payAmountKrw,
                                              BigDecimal feeKrw,
                                              BigDecimal chargedKrw,
                                              String committedAt,
                                              BigDecimal fxRate,
                                              BigDecimal payAmountMnt) {
            return new WalletResult(true, null, schemeTxnRef, merchantName,
                    payAmountKrw, feeKrw, chargedKrw, committedAt, null,
                    true, fxRate, payAmountMnt);
        }

        public static WalletResult declined(String merchantName, String reason) {
            return new WalletResult(false, null, null, merchantName,
                    null, null, null, null, reason,
                    null, null, null);
        }
    }
}
