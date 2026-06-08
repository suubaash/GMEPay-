package com.gme.pay.settlement.model;

/**
 * Settlement type distinguishes domestic NET from international GROSS settlement.
 * <p>
 * NET ('N')  — LOCAL partners (e.g. GME Remit / KRW): GME retains its merchant-fee share
 *              and remits only the net amount to ZeroPay.
 * GROSS ('G') — OVERSEAS partners (e.g. SendMN, T-Bank / USD prefunding): GME remits the
 *               full target_payout to ZeroPay and invoices the merchant separately each month.
 */
public enum SettlementType {

    NET('N'),
    GROSS('G');

    private final char code;

    SettlementType(char code) {
        this.code = code;
    }

    /** Single-character DB/file code ('N' or 'G'). */
    public char code() {
        return code;
    }

    public static SettlementType fromCode(char code) {
        for (SettlementType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown settlement type code: " + code);
    }
}
