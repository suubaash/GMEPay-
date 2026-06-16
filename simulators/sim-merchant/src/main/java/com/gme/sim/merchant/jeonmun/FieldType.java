package com.gme.sim.merchant.jeonmun;

/**
 * Justification / padding rule for a fixed-width 전문 (jeonmun) field.
 *
 * <p>Scheme-agnostic: these rules are shared by any KFTC-style fixed-field wire
 * format. The ZeroPay layouts in {@code jeonmun.zeropay} are the first consumer;
 * a future Alipay+/KHQR fixed-field layout would reuse the same enum + codec.
 */
public enum FieldType {
    /** Numeric, right-justified, zero-padded (leading zeros significant). */
    N,
    /** Alphabetic, left-justified, space-padded. */
    A,
    /** Alphanumeric, left-justified, space-padded. */
    AN,
    /** Any byte content, left-justified, space-padded. */
    ANY,
    /** Numeric-or-space: numeric right-justified zero-padded, or all-spaces when blank. */
    NSP
}
