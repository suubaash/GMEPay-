package com.gme.pay.scheme.ninepay.dto;

/**
 * Response for POST /scheme/decode-qr.
 *
 * @param type          VNPAY | VIETQR. VNPAY → skip account verify and pass the raw QR as
 *                      {@code qrStr} on the payout (bankNo=VNPAY).
 * @param bankNo        9Pay bank code ("VNPAY" for VNPay QRs)
 * @param accountNumber beneficiary account encoded in the QR
 * @param amountVnd     embedded amount when the QR is dynamic; null when static
 * @param accountName   beneficiary name when encoded
 * @param city          city when encoded
 * @param description   free-text description when encoded
 * @param service       QR service type (e.g. QRIBFTTA)
 */
public record DecodeQrResponse(
        String type,
        String bankNo,
        String accountNumber,
        Long amountVnd,
        String accountName,
        String city,
        String description,
        String service
) {}
