package com.gme.sim.ninepay.model;

/**
 * One payout held by the sim. Wire {@code status} walks PENDING → PROCESSING →
 * SUCCESS/FAIL (only those four ever appear on the wire — a code-009 reversal keeps wire
 * status SUCCESS and flips {@link #reversed}; code-008 keeps wire status PROCESSING and
 * flips {@link #held}).
 *
 * <p>Mutable by design; the lifecycle engine synchronizes on the record instance for all
 * state transitions (timer thread + poll thread may race).</p>
 */
public class TransferRecord {

    public enum Outcome { SUCCESS, FAIL, HELD, REVERSAL }

    private final String requestId;
    private final String transactionId;
    private final String bankNo;
    private final String accountNo;
    private final int accountType;
    private final String accountName;
    private final long amountVnd;
    private final long feeVnd;
    private final String content;
    private final String createdAt;

    /** Terminal behavior decided at submission (scenario + seeded-account registry). */
    private final Outcome plannedOutcome;
    /** IPN message code used when {@link #plannedOutcome} is FAIL (001–007). */
    private final String plannedFailCode;

    private String status = "PENDING"; // wire status: PENDING | PROCESSING | SUCCESS | FAIL
    private boolean held;
    private boolean reversed;
    private boolean terminalIpnSent;

    public TransferRecord(String requestId, String transactionId, String bankNo, String accountNo,
                          int accountType, String accountName, long amountVnd, long feeVnd,
                          String content, String createdAt, Outcome plannedOutcome, String plannedFailCode) {
        this.requestId = requestId;
        this.transactionId = transactionId;
        this.bankNo = bankNo;
        this.accountNo = accountNo;
        this.accountType = accountType;
        this.accountName = accountName;
        this.amountVnd = amountVnd;
        this.feeVnd = feeVnd;
        this.content = content;
        this.createdAt = createdAt;
        this.plannedOutcome = plannedOutcome;
        this.plannedFailCode = plannedFailCode;
    }

    public String getRequestId() { return requestId; }
    public String getTransactionId() { return transactionId; }
    public String getBankNo() { return bankNo; }
    public String getAccountNo() { return accountNo; }
    public int getAccountType() { return accountType; }
    public String getAccountName() { return accountName; }
    public long getAmountVnd() { return amountVnd; }
    public long getFeeVnd() { return feeVnd; }
    /** What the sim debits from the prefunded balance: amount + fee. */
    public long getTransferAmountVnd() { return amountVnd + feeVnd; }
    public String getContent() { return content; }
    public String getCreatedAt() { return createdAt; }
    public Outcome getPlannedOutcome() { return plannedOutcome; }
    public String getPlannedFailCode() { return plannedFailCode; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    public boolean isHeld() { return held; }
    public void setHeld(boolean v) { this.held = v; }

    public boolean isReversed() { return reversed; }
    public void setReversed(boolean v) { this.reversed = v; }

    public boolean isTerminalIpnSent() { return terminalIpnSent; }
    public void setTerminalIpnSent(boolean v) { this.terminalIpnSent = v; }

    public boolean isTerminal() {
        return "SUCCESS".equals(status) || "FAIL".equals(status);
    }
}
