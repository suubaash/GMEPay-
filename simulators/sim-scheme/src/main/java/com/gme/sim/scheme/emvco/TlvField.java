package com.gme.sim.scheme.emvco;

/**
 * One EMVCo TLV field: 2-digit decimal tag + 2-digit decimal length + value string.
 */
public record TlvField(int tag, String value) {

    /**
     * Encode this field to EMVCo wire format: TT LL VV…
     * Tag and length are zero-padded to exactly 2 decimal digits each.
     */
    public String encode() {
        String v = value == null ? "" : value;
        return String.format("%02d%02d%s", tag, v.length(), v);
    }
}
