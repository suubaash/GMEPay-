package com.gme.pay.scheme.zeropay.batch;

import com.gme.pay.scheme.zeropay.persistence.ZpCommittedTxnEntity;
import com.gme.pay.scheme.zeropay.persistence.ZpCommittedTxnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Captures committed real-time transactions into {@code zp_committed_txns} so the daily
 * ZeroPay batch files (built by {@link ZpPersistenceBatchDataPort}) carry real records.
 *
 * <p>Capture is best-effort and MUST NOT fail the payment/refund path: every write is wrapped
 * so a duplicate (idempotent re-submit) or a transient persistence error is logged and
 * swallowed. The real-time response to the partner is unaffected.</p>
 */
@Component
public class ZpCommittedTxnRecorder {

    private static final Logger log = LoggerFactory.getLogger(ZpCommittedTxnRecorder.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ZpCommittedTxnRepository repository;

    public ZpCommittedTxnRecorder(ZpCommittedTxnRepository repository) {
        this.repository = repository;
    }

    /**
     * Records a committed MPM/CPM payment. The KST business date and time are derived from
     * {@code committedAt} (UTC instant string) when parseable, otherwise from "now" in KST.
     */
    public void recordPayment(String gmeTxnId, String zeropayTxnRef, String merchantId,
                              String qrCodeId, BigDecimal amountKrw, BigDecimal merchantFeeKrw,
                              BigDecimal vanFeeKrw, String partnerType, String approvalCode) {
        if (zeropayTxnRef == null || zeropayTxnRef.isBlank()) {
            log.debug("Skipping payment capture: missing zeropayTxnRef (approvalCode={})", approvalCode);
            return;
        }
        LocalDateTime nowKst = LocalDateTime.now(KST);
        try {
            repository.save(ZpCommittedTxnEntity.payment(
                    trim20(gmeTxnId), trim20(zeropayTxnRef), trim10(merchantId), trim20(qrCodeId),
                    nowKst.toLocalDate(), nowKst.toLocalTime(),
                    nz(amountKrw), nz(merchantFeeKrw), nz(vanFeeKrw),
                    partnerType, trim12(approvalCode), null));
            log.debug("Captured committed payment txnRef={} merchant={} amount={}",
                    zeropayTxnRef, merchantId, amountKrw);
        } catch (DataIntegrityViolationException dup) {
            log.debug("Payment capture skipped (already recorded) txnRef={}", zeropayTxnRef);
        } catch (RuntimeException e) {
            log.warn("Payment capture failed (non-fatal) txnRef={}: {}", zeropayTxnRef, e.getMessage());
        }
    }

    /**
     * Records a completed refund of a previously committed payment.
     */
    public void recordRefund(String gmeTxnId, String zeropayTxnRef, String merchantId,
                             String qrCodeId, BigDecimal amountKrw, BigDecimal merchantFeeKrw,
                             BigDecimal vanFeeKrw, String partnerType, String refundId,
                             String originalApprovalCode) {
        if (zeropayTxnRef == null || zeropayTxnRef.isBlank()) {
            log.debug("Skipping refund capture: missing zeropayTxnRef (refundId={})", refundId);
            return;
        }
        LocalDateTime nowKst = LocalDateTime.now(KST);
        try {
            repository.save(ZpCommittedTxnEntity.refund(
                    trim20(gmeTxnId), trim20(zeropayTxnRef), trim10(merchantId), trim20(qrCodeId),
                    nowKst.toLocalDate(), nowKst.toLocalTime(),
                    nz(amountKrw), nz(merchantFeeKrw), nz(vanFeeKrw),
                    partnerType, trim12(refundId), trim12(originalApprovalCode), null));
            log.debug("Captured refund txnRef={} merchant={} amount={}",
                    zeropayTxnRef, merchantId, amountKrw);
        } catch (DataIntegrityViolationException dup) {
            log.debug("Refund capture skipped (already recorded) txnRef={}", zeropayTxnRef);
        } catch (RuntimeException e) {
            log.warn("Refund capture failed (non-fatal) txnRef={}: {}", zeropayTxnRef, e.getMessage());
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String trim(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static String trim10(String v) {
        return trim(v, 10);
    }

    private static String trim12(String v) {
        return trim(v, 12);
    }

    private static String trim20(String v) {
        return trim(v, 20);
    }

    /** Time-of-day, exposed for testability. */
    LocalTime nowKstTime() {
        return LocalTime.now(KST);
    }

    /** Business date, exposed for testability. */
    LocalDate nowKstDate() {
        return LocalDate.now(KST);
    }
}
