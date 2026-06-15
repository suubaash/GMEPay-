package com.gme.pay.reporting.hometax;

import com.gme.pay.contracts.VatTreatment;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * Immutable value object representing the aggregated monthly invoice for one
 * overseas merchant: the total KRW collection volume, the invoiceable merchant
 * fee, and the resulting VAT line.
 *
 * <p>Fee calculation rules (UC-04-04):
 * <ul>
 *   <li>Invoiceable fee = monthly KRW total * {@code feeRate}.</li>
 *   <li>GME's own FX spread (2 %) and fixed KRW 500 per-transaction levy are
 *       GME revenue — they are NOT invoiced to the merchant and must be
 *       excluded before this object is created.</li>
 *   <li>VAT is applied per the merchant's {@link VatTreatment}:
 *     <ul>
 *       <li>{@code ZERO_RATED_EXPORT} — VAT = 0; invoice = fee * 0.00.</li>
 *       <li>{@code STANDARD} — VAT = fee * 10 %; invoice = fee * 1.10.</li>
 *       <li>{@code EXEMPT} — VAT = 0; invoice = fee (exempt, no VAT line).</li>
 *     </ul>
 *   </li>
 * </ul>
 */
public final class MerchantInvoiceSummary {

    private final long merchantPartnerId;
    private final YearMonth period;
    /** Total overseas-customer KRW collection volume for the period. */
    private final BigDecimal totalKrw;
    /** Net invoiceable merchant fee (before VAT). */
    private final BigDecimal feeAmount;
    /** VAT line (0 for ZERO_RATED_EXPORT and EXEMPT). */
    private final BigDecimal vatAmount;
    /** Total invoice amount = feeAmount + vatAmount. */
    private final BigDecimal invoiceAmount;
    private final VatTreatment vatTreatment;

    public MerchantInvoiceSummary(
            long merchantPartnerId,
            YearMonth period,
            BigDecimal totalKrw,
            BigDecimal feeAmount,
            BigDecimal vatAmount,
            BigDecimal invoiceAmount,
            VatTreatment vatTreatment) {
        this.merchantPartnerId = merchantPartnerId;
        this.period = period;
        this.totalKrw = totalKrw;
        this.feeAmount = feeAmount;
        this.vatAmount = vatAmount;
        this.invoiceAmount = invoiceAmount;
        this.vatTreatment = vatTreatment;
    }

    public long getMerchantPartnerId() { return merchantPartnerId; }
    public YearMonth getPeriod() { return period; }
    public BigDecimal getTotalKrw() { return totalKrw; }
    public BigDecimal getFeeAmount() { return feeAmount; }
    public BigDecimal getVatAmount() { return vatAmount; }
    public BigDecimal getInvoiceAmount() { return invoiceAmount; }
    public VatTreatment getVatTreatment() { return vatTreatment; }
}
