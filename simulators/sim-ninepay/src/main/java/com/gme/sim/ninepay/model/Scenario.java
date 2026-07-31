package com.gme.sim.ninepay.model;

/**
 * Mutable scenario toggles — inspect/flip via {@code GET/POST /sim/scenario}. Applied to
 * transfers SUBMITTED while the scenario is active (each record captures its planned
 * outcome at submission time).
 */
public class Scenario {

    /** Synchronous behavior of POST /service/transfer. */
    public enum TransferMode {
        /** Accept and run the async lifecycle. */
        NORMAL,
        /** Reject synchronously with {@link #syncErrorCode} (1024 / 1065 / 1066 ...). */
        FAIL_SYNC,
        /** Stall for sim.ninepay.timeout-hold-ms, then answer 1063 (no-response drill). */
        TIMEOUT
    }

    /** Asynchronous terminal outcome pushed via IPN. */
    public enum IpnOutcome {
        /** status SUCCESS, IPN code 000. */
        SUCCESS,
        /** status FAIL, IPN code {@link #ipnFailCode}. */
        FAIL,
        /** IPN code 008 — held by 9Pay pending merchant confirmation; wire status stays PROCESSING. */
        HELD,
        /** SUCCESS IPN (000) first, then a DELAYED second IPN code 009 = bank reversal. */
        REVERSAL
    }

    private TransferMode transferMode = TransferMode.NORMAL;
    private String syncErrorCode = "1024";
    private IpnOutcome ipnOutcome = IpnOutcome.SUCCESS;
    private String ipnFailCode = "001";

    public TransferMode getTransferMode() { return transferMode; }
    public void setTransferMode(TransferMode v) { this.transferMode = v; }

    public String getSyncErrorCode() { return syncErrorCode; }
    public void setSyncErrorCode(String v) { this.syncErrorCode = v; }

    public IpnOutcome getIpnOutcome() { return ipnOutcome; }
    public void setIpnOutcome(IpnOutcome v) { this.ipnOutcome = v; }

    public String getIpnFailCode() { return ipnFailCode; }
    public void setIpnFailCode(String v) { this.ipnFailCode = v; }

    public void reset() {
        transferMode = TransferMode.NORMAL;
        syncErrorCode = "1024";
        ipnOutcome = IpnOutcome.SUCCESS;
        ipnFailCode = "001";
    }
}
