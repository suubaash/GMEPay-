package com.gme.sim.merchant.scheme;

/**
 * The slice of a registered merchant a {@link SchemeWireCodec} needs to build QR + wire.
 * Scheme-agnostic so Alipay+/KHQR codecs can consume the same view later.
 *
 * @param merchantId   가맹점ID
 * @param name         merchant display name
 * @param city         merchant city
 * @param mcc          merchant category code
 * @param registrarId  등록기관ID (2-char QR registrar code), resolved by the terminal
 * @param terminalNo   단말기관리번호
 * @param requestingOrg the operator's KFTC 결제사업자 code (전문 field 23)
 */
public record MerchantView(
        String merchantId, String name, String city, String mcc,
        String registrarId, String terminalNo, String requestingOrg) {}
