package com.gme.sim.merchant.emvco;

/**
 * One EMVCo TLV field: 2-digit decimal tag + 2-digit decimal length + value string.
 */
public record TlvField(int tag, String value) {

    /** Encode this field to EMVCo wire format: TT LL VV…  (tag and length zero-padded to 2). */
    public String encode() {
        String v = value == null ? "" : value;
        return String.format("%02d%02d%s", tag, v.length(), v);
    }
}
