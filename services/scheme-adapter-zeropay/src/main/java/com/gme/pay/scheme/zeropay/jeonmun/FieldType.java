package com.gme.pay.scheme.zeropay.jeonmun;

/**
 * Field encoding type for a ZeroPay 전문 (jeonmun) fixed-field message
 * (KFTC ZeroPay API §3.5). Determines justification + padding:
 *
 * <ul>
 *   <li>{@link #N}   numeric  — right-justified, zero-padded on the left.</li>
 *   <li>{@link #A}   alpha    — left-justified, space-padded on the right.</li>
 *   <li>{@link #AN}  alphanumeric — left-justified, space-padded on the right.</li>
 *   <li>{@link #ANY} any/free — left-justified, space-padded on the right.</li>
 *   <li>{@link #NSP} numeric-or-space — blank value ⇒ all spaces; otherwise
 *       right-justified, zero-padded (e.g. 자원순환 보증금, 직선불 거래금액).</li>
 * </ul>
 */
public enum FieldType {
    N,
    A,
    AN,
    ANY,
    NSP
}
