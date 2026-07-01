package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.GmeremitPaymentService.WalletResult;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.TransactionClient;
import com.gme.pay.payment.persistence.ExecutionAttemptEntity;
import com.gme.pay.payment.persistence.ExecutionAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Orchestrates the Nepal (Fonepay / NepalPay) wallet payment path.
 *
 * <p>Mirrors {@link GmeremitPaymentService} in shape and {@link WalletResult} contract, but with
 * two deliberate differences that fix the mis-routing bug (a Fonepay QR was falling through the
 * ZeroPay domestic path and failing with MERCHANT_NOT_FOUND / HUB_ERROR):
 * <ol>
 *   <li><b>No ZeroPay merchant validation.</b> The Nepal QR is NOT registered in the Korean
 *       {@code merchant-qr-data} store; the Nepal adapter / sim resolves the merchant itself from
 *       the QR payload. So this service does NOT call {@code QrClient.resolve(...)} — doing so
 *       would 404 and decline a perfectly valid Nepal QR.</li>
 *   <li><b>Explicit scheme routing.</b> It builds an {@link SchemeClient.MpmSubmitRequest} with
 *       {@code schemeId="NEPAL"} so the {@code @Primary SchemeClientRouter} forwards the submit to
 *       {@code NepalRestSchemeClient} (single-phase submit) instead of the default ZeroPay client.</li>
 * </ol>
 *
 * <h2>Amount handling</h2>
 * The wallet UI is KRW-labeled, but for the sandbox happy path the wallet amount is treated as
 * <b>NPR</b> and passed straight through to the Nepal adapter. The adapter converts to paisa
 * (×100, HALF_UP). No FX is applied here.
 *
 * <p>TODO(fx): real KRW&rarr;NPR conversion via the rate-fx service is a follow-up. Once wired,
 * fetch the KRW/NPR offer rate, convert the KRW wallet amount to NPR, and pass the NPR figure to
 * the adapter (the wallet-labeled KRW must not be sent as NPR in production).
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

    private final SchemeClient schemeClient;
    private final ExecutionAttemptRepository attemptRepository;
    @Nullable private final TransactionClient transactionClient;

    /** Production constructor. */
    @Autowired
    public NepalPaymentService(SchemeClient schemeClient,
                               ExecutionAttemptRepository attemptRepository,
                               @Nullable TransactionClient transactionClient) {
        this.schemeClient = schemeClient;
        this.attemptRepository = attemptRepository;
        this.transactionClient = transactionClient;
    }

    /** Test constructor — no transaction client. */
    NepalPaymentService(SchemeClient schemeClient, ExecutionAttemptRepository attemptRepository) {
        this(schemeClient, attemptRepository, null);
    }

    /**
     * Executes a Nepal (Fonepay / NepalPay) wallet payment.
     *
     * @param qrPayload raw Fonepay/NepalPay EMVCo QR scanned by the wallet
     * @param amount    wallet amount — treated as NPR for the sandbox happy path (see class TODO(fx))
     * @param userRef   wallet user reference (for logging)
     * @return result — check {@link WalletResult#approved()} before reading scheme fields
     */
    public WalletResult pay(String qrPayload, BigDecimal amount, String userRef) {

        String partnerTxnRef = "NEPAL-" + UUID.randomUUID();

        // Submit to the Nepal adapter via the router (schemeId=NEPAL). No ZeroPay merchant lookup:
        // the Nepal adapter/sim resolves the merchant from the QR itself.
        SchemeClient.MpmSubmitResponse schemeResp;
        try {
            schemeResp = schemeClient.submitMpm(
                    new SchemeClient.MpmSubmitRequest(
                            partnerTxnRef,
                            null,          // merchantId unknown here; Nepal adapter resolves from the QR
                            amount,        // NPR; NepalRestSchemeClient converts to paisa (×100, HALF_UP)
                            "NPR",
                            SCHEME_ID,
                            qrPayload
                    )
            );
        } catch (SchemeDeclinedException ex) {
            log.warn("Nepal scheme declined userRef={} ref={}: {}", userRef, partnerTxnRef, ex.getMessage());
            persistAttempt(partnerTxnRef, PaymentStatus.FAILED, null);
            // Carry the adapter's own reason, not a generic HUB_ERROR.
            return WalletResult.declined(null, ex.schemeErrorCode());
        } catch (PaymentException ex) {
            // Timeout / transport / non-2xx from the Nepal adapter — surface the adapter reason.
            log.warn("Nepal adapter failure userRef={} ref={}: {}", userRef, partnerTxnRef, ex.getMessage());
            persistAttempt(partnerTxnRef, PaymentStatus.FAILED, null);
            return WalletResult.declined(null, ex.getMessage());
        }

        // The Nepal adapter maps its response {schemeTxnRef,status,amountPaisa} onto MpmSubmitResponse:
        //   schemeApprovalCode <- status, schemeTxnRef <- schemeTxnRef.
        boolean approved = "APPROVED".equalsIgnoreCase(schemeResp.schemeApprovalCode());
        if (!approved) {
            log.warn("Nepal submit not APPROVED (status={}) ref={}",
                    schemeResp.schemeApprovalCode(), partnerTxnRef);
            persistAttempt(partnerTxnRef, PaymentStatus.FAILED, schemeResp.schemeTxnRef());
            return WalletResult.declined(null,
                    schemeResp.schemeApprovalCode() != null ? schemeResp.schemeApprovalCode() : "NEPAL_DECLINED");
        }

        // Record the transaction in transaction-mgmt (resilient — like the other services).
        String txnRef = partnerTxnRef;
        if (transactionClient != null) {
            try {
                TransactionClient.CreateResult created = transactionClient.createPending(
                        new TransactionClient.CreateRequest(
                                0L, partnerTxnRef, SCHEME_TAG, "OVERSEAS", "MPM",
                                amount, "NPR", amount, "NPR",
                                null, null, null));
                txnRef = created.txnRef();
                transactionClient.commitStatus(txnRef,
                        new TransactionClient.StatusPatch(
                                PaymentStatus.APPROVED,
                                schemeResp.schemeTxnRef(),
                                schemeResp.schemeApprovalCode(),
                                null,
                                schemeResp.approvedAt() != null ? schemeResp.approvedAt() : Instant.now()));
            } catch (RuntimeException ex) {
                log.warn("Nepal transaction-mgmt unavailable for {} — continuing (sandbox): {}",
                        partnerTxnRef, ex.getMessage());
            }
        }

        persistAttempt(partnerTxnRef, PaymentStatus.APPROVED, schemeResp.schemeTxnRef());

        String committedAt = KST_FMT.format(
                schemeResp.approvedAt() != null ? schemeResp.approvedAt() : Instant.now());

        // NPR pass-through: payAmount == amount, no fee applied on this sandbox path.
        return WalletResult.approved(
                txnRef,
                schemeResp.schemeTxnRef(),
                null,               // merchantName resolved by the adapter; not surfaced here yet
                amount,
                BigDecimal.ZERO,
                amount,
                committedAt
        );
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
