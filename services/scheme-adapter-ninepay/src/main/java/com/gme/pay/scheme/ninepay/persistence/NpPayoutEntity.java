package com.gme.pay.scheme.ninepay.persistence;

import com.gme.pay.scheme.ninepay.status.PayoutStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One attempted 9Pay disbursement — maps {@code np_payouts} (V001).
 *
 * <p>{@code request_id} is UNIQUE: it is the 9Pay idempotency key (error 1062 on reuse)
 * and transfers cannot be cancelled, so the row must exist BEFORE the wire call and is
 * never deleted. VND amounts are {@link BigDecimal} scale 0 (NUMERIC(20,0)) per
 * {@code docs/MONEY_CONVENTION.md}.</p>
 */
@Entity
@Table(name = "np_payouts")
public class NpPayoutEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, length = 50, unique = true)
    private String requestId;

    @Column(name = "transaction_id", length = 20)
    private String transactionId;

    @Column(name = "bank_no", nullable = false, length = 20)
    private String bankNo;

    @Column(name = "account_no", nullable = false, length = 22)
    private String accountNo;

    @Column(name = "account_type", nullable = false)
    private int accountType;

    @Column(name = "account_name", nullable = false, length = 164)
    private String accountName;

    @Column(name = "amount_vnd", nullable = false, precision = 20, scale = 0)
    private BigDecimal amountVnd;

    @Column(name = "fee_vnd", precision = 20, scale = 0)
    private BigDecimal feeVnd;

    @Column(name = "transfer_amount_vnd", precision = 20, scale = 0)
    private BigDecimal transferAmountVnd;

    @Column(name = "content", nullable = false, length = 150)
    private String content;

    @Column(name = "sender_uid", length = 100)
    private String senderUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private PayoutStatus status;

    @Column(name = "last_ipn_code", length = 3)
    private String lastIpnCode;

    @Column(name = "scheme_message", length = 200)
    private String schemeMessage;

    @Column(name = "scheme_created_at", length = 20)
    private String schemeCreatedAt;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NpPayoutEntity() {
        // JPA
    }

    /** New payout row, persisted BEFORE the wire submit (status {@link PayoutStatus#SUBMITTED}). */
    public NpPayoutEntity(String requestId, String bankNo, String accountNo, int accountType,
                          String accountName, long amountVnd, String content, String senderUid) {
        this.requestId = requestId;
        this.bankNo = bankNo;
        this.accountNo = accountNo;
        this.accountType = accountType;
        this.accountName = accountName;
        this.amountVnd = BigDecimal.valueOf(amountVnd);
        this.content = content;
        this.senderUid = senderUid;
        this.status = PayoutStatus.SUBMITTED;
    }

    @PrePersist
    void onPersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // ------------------------------------------------------------------ mutators

    public void recordSchemeAccept(String transactionId, PayoutStatus status, Long feeVnd,
                                   Long transferAmountVnd, String schemeCreatedAt, String schemeMessage) {
        this.transactionId = transactionId;
        this.status = status;
        if (feeVnd != null) {
            this.feeVnd = BigDecimal.valueOf(feeVnd);
        }
        if (transferAmountVnd != null) {
            this.transferAmountVnd = BigDecimal.valueOf(transferAmountVnd);
        }
        this.schemeCreatedAt = schemeCreatedAt;
        this.schemeMessage = schemeMessage;
    }

    public void recordStatus(PayoutStatus status) {
        this.status = status;
    }

    public void recordIpn(String code, PayoutStatus status, String transId, Long feeVnd,
                          Long transferAmountVnd, String schemeMessage) {
        this.lastIpnCode = code;
        this.status = status;
        if (transId != null && !transId.isBlank()) {
            this.transactionId = transId;
        }
        if (feeVnd != null) {
            this.feeVnd = BigDecimal.valueOf(feeVnd);
        }
        if (transferAmountVnd != null) {
            this.transferAmountVnd = BigDecimal.valueOf(transferAmountVnd);
        }
        if (schemeMessage != null && !schemeMessage.isBlank()) {
            this.schemeMessage = schemeMessage;
        }
        if (status == PayoutStatus.REVERSED && reversedAt == null) {
            reversedAt = Instant.now();
        }
    }

    // ------------------------------------------------------------------ accessors

    public Long getId() {
        return id;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getBankNo() {
        return bankNo;
    }

    public String getAccountNo() {
        return accountNo;
    }

    public int getAccountType() {
        return accountType;
    }

    public String getAccountName() {
        return accountName;
    }

    public BigDecimal getAmountVnd() {
        return amountVnd;
    }

    public BigDecimal getFeeVnd() {
        return feeVnd;
    }

    public BigDecimal getTransferAmountVnd() {
        return transferAmountVnd;
    }

    public String getContent() {
        return content;
    }

    public String getSenderUid() {
        return senderUid;
    }

    public PayoutStatus getStatus() {
        return status;
    }

    public String getLastIpnCode() {
        return lastIpnCode;
    }

    public String getSchemeMessage() {
        return schemeMessage;
    }

    public String getSchemeCreatedAt() {
        return schemeCreatedAt;
    }

    public Instant getReversedAt() {
        return reversedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
