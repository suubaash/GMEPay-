package com.gme.pay.txn.persistence;

import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;

import java.math.RoundingMode;

/**
 * Pure-function mapper between the domain aggregate
 * {@link Transaction} and the JPA entity {@link TransactionEntity}.
 *
 * <p>Lives in {@code persistence} so the domain model stays free of JPA imports.
 * Stateless – methods are static.  Rounding-mode is persisted as the enum's
 * {@code name()} (e.g. {@code "HALF_UP"}) for cross-DB portability.
 *
 * <p>V003: maps Phase-4 enrichment columns (payment-executor 11-field create contract
 * and status-patch lock fields).
 */
public final class TransactionEntityMapper {

    private TransactionEntityMapper() {}

    /** Domain → JPA entity. */
    public static TransactionEntity toEntity(Transaction txn) {
        TransactionEntity e = new TransactionEntity();
        e.setTxnRef(txn.txnRef());
        e.setPartnerRef(txn.partnerRef());
        e.setSendAmount(txn.sendAmount());
        e.setSendCcy(txn.sendCcy());
        e.setTargetPayout(txn.targetPayout());
        e.setTargetCcy(txn.targetCcy());
        e.setStatus(txn.status().name());
        e.setBookedSettlementAmount(txn.bookedSettlementAmount());
        RoundingMode mode = txn.settlementRoundingMode();
        e.setSettlementRoundingMode(mode == null ? null : mode.name());
        e.setRoundingResidual(txn.roundingResidual());
        e.setCreatedAt(txn.createdAt());
        e.setUpdatedAt(txn.updatedAt());
        // V003
        e.setPartnerId(txn.partnerId());
        e.setPartnerTxnRef(txn.partnerTxnRef());
        e.setSchemeId(txn.schemeId());
        e.setDirection(txn.direction());
        e.setPaymentMode(txn.paymentMode());
        e.setPayoutCurrency(txn.payoutCurrency());
        e.setCollectionAmount(txn.collectionAmount());
        e.setCollectionCurrency(txn.collectionCurrency());
        e.setMerchantId(txn.merchantId());
        e.setQuoteId(txn.quoteId());
        e.setPaymentId(txn.paymentId());
        e.setSchemeTxnRef(txn.schemeTxnRef());
        e.setSchemeApprovalCode(txn.schemeApprovalCode());
        e.setPrefundDeductedUsd(txn.prefundDeductedUsd());
        e.setApprovedAt(txn.approvedAt());
        e.setFailureReason(txn.failureReason());
        return e;
    }

    /** JPA entity → domain. */
    public static Transaction toDomain(TransactionEntity e) {
        RoundingMode mode = e.getSettlementRoundingMode() == null
                ? null
                : RoundingMode.valueOf(e.getSettlementRoundingMode());
        return new Transaction(
                e.getTxnRef(),
                e.getPartnerRef(),
                e.getSendAmount(),
                e.getSendCcy(),
                e.getTargetPayout(),
                e.getTargetCcy(),
                TransactionStatus.valueOf(e.getStatus()),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getBookedSettlementAmount(),
                mode,
                e.getRoundingResidual(),
                // V003 enrichment
                e.getPartnerId(),
                e.getPartnerTxnRef(),
                e.getSchemeId(),
                e.getDirection(),
                e.getPaymentMode(),
                e.getPayoutCurrency(),
                e.getCollectionAmount(),
                e.getCollectionCurrency(),
                e.getMerchantId(),
                e.getQuoteId(),
                e.getPaymentId(),
                e.getSchemeTxnRef(),
                e.getSchemeApprovalCode(),
                e.getPrefundDeductedUsd(),
                e.getApprovedAt(),
                e.getFailureReason());
    }
}
