package com.gme.pay.scheme.sendmn.adapter;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.scheme.sendmn.client.SendmnSchemeApiClient;
import com.gme.pay.scheme.sendmn.client.SendmnSchemeApiClient.ConfirmApiResponse;
import com.gme.pay.scheme.sendmn.client.SendmnSchemeApiClient.ConfirmCommand;
import com.gme.pay.scheme.sendmn.client.SendmnSchemeApiClient.PaymentStatusApiResponse;
import com.gme.pay.scheme.sendmn.client.SendmnSchemeApiClient.VerifyQrApiResponse;
import com.gme.pay.scheme.sendmn.dto.StatusResponse;
import com.gme.pay.scheme.sendmn.dto.SubmitMpmRequest;
import com.gme.pay.scheme.sendmn.dto.SubmitMpmResponse;
import com.gme.pay.scheme.sendmn.dto.VerifyQrRequest;
import com.gme.pay.scheme.sendmn.dto.VerifyQrResponse;
import com.gme.pay.scheme.sendmn.fx.FxRateService;
import com.gme.pay.scheme.sendmn.persistence.SmnFxRateEntity;
import com.gme.pay.scheme.sendmn.persistence.SmnPaymentEntity;
import com.gme.pay.scheme.sendmn.persistence.SmnPaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * Translates payment-executor's canonical {@code /internal/scheme/sendmn/...} calls
 * into the SendMN partner API and back, owning the scheme's idempotency and ambiguity
 * policy:
 *
 * <ul>
 *   <li>{@code TX_TOKEN_NO} is generated here (once per payment) and persisted in
 *       {@code smn_payments} at verify time; Confirm and PaymentStatus reuse it.</li>
 *   <li>Error 304 (duplicate token) means the first Confirm DID reach SendMN — resolve
 *       by polling PaymentStatus, never by resubmitting.</li>
 *   <li>Ambiguous Confirm outcomes (transport timeout / 5xx / SendMN 991/998/999) also
 *       resolve via PaymentStatus; when even the poll fails the payment stays
 *       {@code UNKNOWN} — never auto-failed (ADR-016).</li>
 *   <li>{@code SETTLEMENT_AMOUNT} is computed from the latest SendMN-registered buy
 *       rate ({@link FxRateService}) — SendMN re-verifies it server-side (error 307).</li>
 * </ul>
 */
@Service
public class SendmnSchemeAdapter {

    private static final Logger log = LoggerFactory.getLogger(SendmnSchemeAdapter.class);

    /** SendMN result codes after which the outcome is unknowable without a status poll. */
    private static final Set<String> AMBIGUOUS_RES_CODES = Set.of("991", "998", "999");
    private static final String RES_DUPLICATE_TOKEN = "304";
    private static final String RES_SETTLEMENT_MISMATCH = "307";

    private static final String LOCAL_CUR = "MNT";
    private static final String SETTLEMENT_CUR = "USD";
    private static final DateTimeFormatter PAYMENT_DATETIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(java.time.ZoneOffset.UTC);
    private static final char[] TOKEN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final SendmnSchemeApiClient client;
    private final SmnPaymentRepository payments;
    private final FxRateService fxRates;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** Primary constructor — wired by Spring. {@code @Autowired} required (2+ ctors). */
    @org.springframework.beans.factory.annotation.Autowired
    public SendmnSchemeAdapter(SendmnSchemeApiClient client, SmnPaymentRepository payments,
                               FxRateService fxRates) {
        this(client, payments, fxRates, Clock.systemUTC());
    }

    SendmnSchemeAdapter(SendmnSchemeApiClient client, SmnPaymentRepository payments,
                        FxRateService fxRates, Clock clock) {
        this.client = client;
        this.payments = payments;
        this.fxRates = fxRates;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------
    // verify-qr
    // -------------------------------------------------------------------------

    /**
     * Decodes a scanned merchant QR via SendMN VerifyQr, generating and persisting the
     * payment's {@code TX_TOKEN_NO}.
     */
    @Transactional
    public VerifyQrResponse verifyQr(VerifyQrRequest req) {
        if (req == null || req.qrPayload() == null || req.qrPayload().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "verify-qr: qrPayload is required");
        }
        String txTokenNo = newTxTokenNo();
        VerifyQrApiResponse resp = client.verifyQr(req.qrPayload(), txTokenNo);
        if (!"0".equals(resp.resCode())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "sendmn VerifyQr rejected (RES_CODE=" + resp.resCode() + "): " + resp.resMsg());
        }
        SmnPaymentEntity payment = new SmnPaymentEntity(
                txTokenNo, req.qrPayload(), resp.merchantId(), resp.merchantName(),
                parseAmount(resp.localPaymentAmount()));
        // Persist the hub's reference NOW (before any Confirm can be sent) so the
        // ADR-016 by-reference status probe survives a hub restart.
        if (req.reference() != null && !req.reference().isBlank()) {
            payment.setHubReference(req.reference());
        }
        payments.save(payment);
        return new VerifyQrResponse(
                txTokenNo,
                resp.merchantId(),
                resp.merchantName(),
                resp.merchantAddress(),
                resp.terminalId(),
                resp.qrType(),
                resp.localPaymentAmount(),
                LOCAL_CUR);
    }

    // -------------------------------------------------------------------------
    // submit-mpm (SendMN Confirm)
    // -------------------------------------------------------------------------

    /**
     * Executes the payment (SendMN Confirm) with the settlement amount computed from
     * the latest registered buy rate. Idempotent: an already-APPROVED payment replays
     * its stored result; duplicate/ambiguous scheme answers resolve via status polling.
     */
    @Transactional
    public SubmitMpmResponse submitMpm(SubmitMpmRequest req) {
        if (req == null || req.txTokenNo() == null || req.txTokenNo().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "submit-mpm: txTokenNo is required");
        }
        SmnPaymentEntity payment = payments.findByTxTokenNo(req.txTokenNo())
                .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_NOT_FOUND,
                        "submit-mpm: unknown txTokenNo " + req.txTokenNo() + " — call verify-qr first"));

        // Local idempotency: the hub retrying an already-approved submit is a no-op.
        if (payment.getStatus() == SmnPaymentEntity.Status.APPROVED) {
            return toSubmitResponse(payment);
        }

        BigDecimal localAmount = requireAmount(req.localAmountMnt(), payment);
        payment.setLocalAmount(localAmount);
        // Backfill only: the reference is normally persisted at verify-qr (before Confirm).
        if (payment.getHubReference() == null
                && req.reference() != null && !req.reference().isBlank()) {
            payment.setHubReference(req.reference());
        }
        if (req.merchantId() != null && !req.merchantId().isBlank()) {
            payment.setMerchantId(req.merchantId());
        }
        if (payment.getMerchantId() == null || payment.getMerchantId().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "submit-mpm: merchantId unresolved");
        }

        SmnFxRateEntity rate = fxRates.latestRate(LOCAL_CUR, SETTLEMENT_CUR)
                .orElseThrow(() -> new ApiException(ErrorCode.SCHEME_UNAVAILABLE,
                        "submit-mpm: no SendMN-registered " + LOCAL_CUR + "/" + SETTLEMENT_CUR
                                + " buy rate — cannot compute SETTLEMENT_AMOUNT"));
        BigDecimal settlementAmount = fxRates.settlementAmount(localAmount, rate.getRate());
        payment.recordFxUsed(rate.getFxTickerNo(), rate.getRate(), SETTLEMENT_CUR, settlementAmount);

        ConfirmCommand cmd = new ConfirmCommand(
                payment.getTxTokenNo(),
                payment.getMerchantId(),
                LOCAL_CUR,
                localAmount,
                SETTLEMENT_CUR,
                rate.getRate(),
                rate.getFxTickerNo(),
                SETTLEMENT_CUR,
                settlementAmount,
                PAYMENT_DATETIME.format(clock.instant()));

        try {
            ConfirmApiResponse resp = client.confirm(cmd);
            applyConfirmResult(payment, resp);
        } catch (ApiException ex) {
            if (ex.errorCode() != ErrorCode.SCHEME_UNAVAILABLE) {
                throw ex;
            }
            // Ambiguous transport outcome (timeout / 5xx): the Confirm may have landed.
            // Poll before anything else; UNKNOWN if the poll can't tell (never auto-fail).
            log.warn("sendmn Confirm ambiguous for {} — polling PaymentStatus: {}",
                    payment.getTxTokenNo(), ex.getMessage());
            applyPolledStatus(payment);
        }
        payments.save(payment);
        return toSubmitResponse(payment);
    }

    // -------------------------------------------------------------------------
    // status
    // -------------------------------------------------------------------------

    /** Polls SendMN PaymentStatus and refreshes the stored payment. Never throws on poll failure. */
    @Transactional
    public StatusResponse status(String txTokenNo) {
        if (txTokenNo == null || txTokenNo.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "status: txTokenNo is required");
        }
        SmnPaymentEntity payment = payments.findByTxTokenNo(txTokenNo)
                .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_NOT_FOUND,
                        "status: unknown txTokenNo " + txTokenNo));
        applyPolledStatus(payment);
        payments.save(payment);
        return new StatusResponse(payment.getTxTokenNo(), payment.getStatus().name(),
                payment.getPaymentNo(), payment.getPaymentReceiptNo());
    }

    /**
     * ADR-016 restart-proof probe: resolves the hub's stable partner reference (persisted
     * at verify-qr, i.e. before any Confirm) to its payment and refreshes it via
     * PaymentStatus, exactly like {@link #status(String)}. An unknown reference throws
     * {@code PAYMENT_NOT_FOUND} (404): no verify-qr ever committed here, so no Confirm can
     * have been sent — the hub may safely treat it as absent. If a retried verify-qr left
     * several attempts for one reference, the APPROVED one (money moved) wins; otherwise
     * the newest attempt is polled.
     */
    @Transactional
    public StatusResponse statusByReference(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "status: reference is required");
        }
        List<SmnPaymentEntity> attempts = payments.findByHubReferenceOrderByIdDesc(reference);
        if (attempts.isEmpty()) {
            throw new ApiException(ErrorCode.PAYMENT_NOT_FOUND,
                    "status: unknown reference " + reference);
        }
        SmnPaymentEntity payment = attempts.stream()
                .filter(p -> p.getStatus() == SmnPaymentEntity.Status.APPROVED)
                .findFirst()
                .orElse(attempts.get(0));
        applyPolledStatus(payment);
        payments.save(payment);
        return new StatusResponse(payment.getTxTokenNo(), payment.getStatus().name(),
                payment.getPaymentNo(), payment.getPaymentReceiptNo());
    }

    // -------------------------------------------------------------------------
    // Confirm result / poll policy
    // -------------------------------------------------------------------------

    private void applyConfirmResult(SmnPaymentEntity payment, ConfirmApiResponse resp) {
        String code = resp.resCode();
        if ("0".equals(code)) {
            payment.setStatus(SmnPaymentEntity.Status.APPROVED);
            payment.setPaymentNo(resp.paymentNo());
            payment.setPaymentReceiptNo(resp.paymentReceiptNo());
            return;
        }
        if (RES_DUPLICATE_TOKEN.equals(code)) {
            // 304 = TX_TOKEN_NO duplicated → an earlier Confirm reached SendMN. The truth
            // lives in PaymentStatus; never resubmit.
            log.info("sendmn Confirm 304 (duplicate TX_TOKEN_NO) for {} — polling PaymentStatus",
                    payment.getTxTokenNo());
            applyPolledStatus(payment);
            return;
        }
        if (AMBIGUOUS_RES_CODES.contains(code)) {
            log.warn("sendmn Confirm RES_CODE={} for {} — outcome ambiguous, polling PaymentStatus",
                    code, payment.getTxTokenNo());
            applyPolledStatus(payment);
            return;
        }
        if (RES_SETTLEMENT_MISMATCH.equals(code)) {
            // Our settlement math disagrees with SendMN's — a rate/rounding defect
            // (open issue O3), not a payment failure. Surface loudly; payment not made.
            payment.setStatus(SmnPaymentEntity.Status.REJECTED);
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "sendmn Confirm 307 SETTLEMENT_AMOUNT mismatch for " + payment.getTxTokenNo()
                            + " (rate=" + payment.getFxUsdBuyRate()
                            + ", settlement=" + payment.getSettlementAmount() + "): " + resp.resMsg());
        }
        // Definitive synchronous validation reject (2xx missing-field, 303/305 invalid,
        // 311/316 format): the request never became a payment — safe to reject.
        payment.setStatus(SmnPaymentEntity.Status.REJECTED);
        throw new ApiException(ErrorCode.VALIDATION_ERROR,
                "sendmn Confirm rejected (RES_CODE=" + code + "): " + resp.resMsg());
    }

    /**
     * Refreshes the payment from a PaymentStatus poll. Poll failures and unrecognised
     * statuses leave the payment {@code UNKNOWN} (or its previous definitive state) —
     * never a failure state.
     */
    private void applyPolledStatus(SmnPaymentEntity payment) {
        String canonical;
        PaymentStatusApiResponse resp = null;
        try {
            resp = client.paymentStatus(payment.getTxTokenNo());
            canonical = "0".equals(resp.resCode())
                    ? SendmnStatusMapper.toCanonical(resp.paymentStatus())
                    : SendmnStatusMapper.UNKNOWN;
        } catch (ApiException ex) {
            log.warn("sendmn PaymentStatus poll failed for {} — leaving UNKNOWN: {}",
                    payment.getTxTokenNo(), ex.getMessage());
            canonical = SendmnStatusMapper.UNKNOWN;
        }
        switch (canonical) {
            case SendmnStatusMapper.APPROVED -> {
                payment.setStatus(SmnPaymentEntity.Status.APPROVED);
                if (resp != null) {
                    if (resp.paymentNo() != null) payment.setPaymentNo(resp.paymentNo());
                    if (resp.paymentReceiptNo() != null) payment.setPaymentReceiptNo(resp.paymentReceiptNo());
                }
            }
            case SendmnStatusMapper.PENDING -> payment.setStatus(SmnPaymentEntity.Status.PENDING);
            default -> {
                // Never downgrade a definitive APPROVED on a flaky poll.
                if (payment.getStatus() != SmnPaymentEntity.Status.APPROVED) {
                    payment.setStatus(SmnPaymentEntity.Status.UNKNOWN);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private SubmitMpmResponse toSubmitResponse(SmnPaymentEntity p) {
        return new SubmitMpmResponse(
                p.getTxTokenNo(),
                p.getStatus().name(),
                p.getPaymentNo(),
                p.getPaymentReceiptNo(),
                p.getFxUsdBuyRate() == null ? null : p.getFxUsdBuyRate().toPlainString(),
                p.getSettlementAmount() == null ? null : p.getSettlementAmount().toPlainString());
    }

    /** Partner-generated TX_TOKEN_NO, e.g. {@code SMN20261027041530XK7Q2M} (doc example style + entropy). */
    private String newTxTokenNo() {
        StringBuilder sb = new StringBuilder("SMN")
                .append(PAYMENT_DATETIME.format(clock.instant()));
        for (int i = 0; i < 6; i++) {
            sb.append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)]);
        }
        return sb.toString();
    }

    private BigDecimal requireAmount(String requested, SmnPaymentEntity payment) {
        String raw = requested != null && !requested.isBlank()
                ? requested
                : (payment.getLocalAmount() == null ? null : payment.getLocalAmount().toPlainString());
        BigDecimal amount = parseAmount(raw);
        if (amount == null || amount.signum() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "submit-mpm: a positive localAmountMnt is required (QR was static/amount-less)");
        }
        return amount.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private BigDecimal parseAmount(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "invalid amount: " + s);
        }
    }
}
