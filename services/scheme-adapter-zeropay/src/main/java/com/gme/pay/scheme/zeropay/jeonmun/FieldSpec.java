package com.gme.pay.scheme.zeropay.jeonmun;

/**
 * One positional field in a ZeroPay 전문 layout.
 *
 * @param no     the field's logical No. in the KFTC spec (used as the map key on encode/decode)
 * @param key    a stable ASCII key (English) for the field — its Korean name lives in
 *               {@code Documentation/ZeroPay-API-Integration-Parameters.md}
 * @param type   encoding/justification rule
 * @param length fixed byte length (EUC-KR) of the field
 */
public record FieldSpec(int no, String key, FieldType type, int length) {}
