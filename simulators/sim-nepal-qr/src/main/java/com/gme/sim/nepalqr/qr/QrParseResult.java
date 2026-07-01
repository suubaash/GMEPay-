package com.gme.sim.nepalqr.qr;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Best-effort parse of an EMVCo / Fonepay QR string. Fields that cannot be read
 * from the QR fall back to sensible mock defaults so the sim always returns a
 * usable merchant shape.
 */
public class QrParseResult {

    public String format = "EMVCo";
    public String initMethod = "static";       // "static" (tag 01=11) | "dynamic" (tag 01=12)
    public String network = "fonepay";         // fonepay | nepalpay | unionpay | smartqr | khalti | mobank
    public String merchantId = "MOCKMERCHANT";
    public String merchantName = "Mock Merchant";
    public String merchantCity = "Kathmandu";
    public String merchantCountry = "NP";
    public String merchantCategoryCode = "5411";
    public String trxCurrency = "NPR";
    /** Amount in paisa when the QR is dynamic; null for a static QR. */
    public Long amountPaisa = null;
    public String purpose = "Remittance";
    public String merchantInfoExtra = "";
    /** Raw top-level TLV tag -> value, for merchantData / debugging. */
    public Map<String, String> tags = new LinkedHashMap<>();

    /** Amount in rupees as a plain string (for the parse API), or null. */
    public String amountRupees() {
        if (amountPaisa == null) return null;
        long whole = amountPaisa / 100;
        long frac = amountPaisa % 100;
        return frac == 0 ? Long.toString(whole)
                : whole + "." + String.format("%02d", frac);
    }
}
