package com.gme.sim.sendmn.model;

import java.math.BigDecimal;

/**
 * Mutable state of one TX_TOKEN_NO as it moves through the doc's PAYMENT_STATUS values:
 * {@code Decrypted} (QR verified, not yet paid) → {@code Processing} (Confirm accepted)
 * → {@code Approved} (after the configured number of status polls).
 */
public class PaymentRecord {

    public enum State { DECRYPTED, PROCESSING, APPROVED }

    private final String txTokenNo;
    private State state;
    private Merchant merchant;
    private String paymentNo;
    private String paymentReceiptNo;
    private BigDecimal localAmount;
    private BigDecimal settlementAmount;
    private String settlementDate;
    private String reconcileDate;
    private int pollCount;

    public PaymentRecord(String txTokenNo, State state, Merchant merchant) {
        this.txTokenNo = txTokenNo;
        this.state = state;
        this.merchant = merchant;
    }

    /** Doc wording for PAYMENT_STATUS. */
    public String statusLabel() {
        return switch (state) {
            case DECRYPTED -> "Decrypted";
            case PROCESSING -> "Processing";
            case APPROVED -> "Approved";
        };
    }

    public String getTxTokenNo() { return txTokenNo; }

    public State getState() { return state; }
    public void setState(State state) { this.state = state; }

    public Merchant getMerchant() { return merchant; }
    public void setMerchant(Merchant merchant) { this.merchant = merchant; }

    public String getPaymentNo() { return paymentNo; }
    public void setPaymentNo(String paymentNo) { this.paymentNo = paymentNo; }

    public String getPaymentReceiptNo() { return paymentReceiptNo; }
    public void setPaymentReceiptNo(String paymentReceiptNo) { this.paymentReceiptNo = paymentReceiptNo; }

    public BigDecimal getLocalAmount() { return localAmount; }
    public void setLocalAmount(BigDecimal localAmount) { this.localAmount = localAmount; }

    public BigDecimal getSettlementAmount() { return settlementAmount; }
    public void setSettlementAmount(BigDecimal settlementAmount) { this.settlementAmount = settlementAmount; }

    public String getSettlementDate() { return settlementDate; }
    public void setSettlementDate(String settlementDate) { this.settlementDate = settlementDate; }

    public String getReconcileDate() { return reconcileDate; }
    public void setReconcileDate(String reconcileDate) { this.reconcileDate = reconcileDate; }

    public int incrementAndGetPolls() { return ++pollCount; }
    public int getPollCount() { return pollCount; }
}
