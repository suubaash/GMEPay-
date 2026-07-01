package com.gme.sim.nepalqr.model;

/**
 * A transaction created by /qrscan-thirdparty/pay/, keyed by its globally-unique
 * reference. State may later move (e.g. to REVERSED) but the sim starts it at the
 * configured pay outcome.
 */
public class TxnRecord {

    public String idx;
    public String reference;
    public long amountPaisa;
    public String mobile;
    public String qs;
    public String purpose;
    public String remarks;
    public String state;        // APPROVED | PENDING | REJECTED | REVERSED
    public String detail;
    public String createdAt;    // ISO-8601 KST
}
