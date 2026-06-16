package com.gme.sim.merchant.scheme;

/**
 * Extensibility seam for QR-scheme wire formats in the merchant simulator.
 *
 * <p>The terminal speaks to schemes only through this interface, so adding Alipay+ or KHQR
 * later means dropping in a new implementation (with its own QR builder + message layouts)
 * and registering it — no change to the controller or UI contracts. ZeroPay is the first
 * implementation; it produces EMVCo MPM QRs and KFTC 전문 (420000/500000) frames.
 *
 * <p>Implementations are stateless and thread-safe.
 */
public interface SchemeWireCodec {

    /** Stable scheme id, e.g. "ZEROPAY". */
    String schemeId();

    /** Human-readable scheme name, e.g. "ZeroPay". */
    String displayName();

    /** ISO-4217 alphabetic payout currency, e.g. "KRW". */
    String payoutCurrency();

    /** Build the merchant's static store QR (no amount). */
    QrView buildStaticQr(MerchantView merchant);

    /**
     * Build a dynamic charge: the amount-bearing QR plus the payment wire message
     * (ZeroPay 420000) that this charge produces on the scheme network.
     *
     * @param amountMinor amount in the currency's minor-unit-free integer (KRW has none)
     * @param txnUniqueNo the operator's transaction reference
     * @param traceNo     8-digit message trace number
     */
    DynamicCharge buildDynamicCharge(MerchantView merchant, long amountMinor,
                                     String txnUniqueNo, String traceNo);

    /**
     * Build the static-QR result-registration wire (ZeroPay 500000) for a completed
     * static-QR payment.
     *
     * @param amountMinor amount the consumer paid
     * @param approvalNo  approval number to register the result against
     */
    WireMessage buildStaticResult(MerchantView merchant, long amountMinor,
                                  String approvalNo, String txnUniqueNo, String traceNo);

    /** Bundle of the dynamic QR and the payment wire it maps to. */
    record DynamicCharge(QrView qr, WireMessage wire) {}
}
