package com.gme.sim.merchant.jeonmun;

/**
 * One positional field in a fixed-width 전문 layout.
 *
 * @param no     the field's logical No. in the KFTC spec (the map key on encode/decode)
 * @param key    a stable ASCII key (English); the Korean name lives in
 *               {@code Documentation/ZeroPay-API-Integration-Parameters.md}
 * @param korean the field's Korean name (shown verbatim in the terminal wire view)
 * @param type   encoding/justification rule
 * @param length fixed byte length (in the target charset, EUC-KR for ZeroPay)
 */
public record FieldSpec(int no, String key, String korean, FieldType type, int length) {}
