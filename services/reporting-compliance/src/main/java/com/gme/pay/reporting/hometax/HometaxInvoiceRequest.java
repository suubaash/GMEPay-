package com.gme.pay.reporting.hometax;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * Request payload sent to the Hometax e-tax-invoice endpoint.
 *
 * <p>Field names are fixed by the NTS (National Tax Service) API contract.
 * Jackson binds by {@code @JsonProperty} name; any mismatch silently produces
 * a malformed payload — keep names in sync with the NTS spec.
 *
 * <p>Money fields are serialized as decimal strings per the GME money convention
 * ({@code docs/MONEY_CONVENTION.md}), never as JSON floating-point numbers.
 */
public class HometaxInvoiceRequest {

    /** GME's Hometax issuer certificate id (lib-vault document id). */
    @JsonProperty("issuer_cert_id")
    private String issuerCertId;

    /** The partner's surrogate id from config-registry. */
    @JsonProperty("merchant_partner_id")
    private long merchantPartnerId;

    /** Billing period in YYYY-MM format. */
    @JsonProperty("billing_period")
    private String billingPeriod;

    /** Net invoiceable fee before VAT, as a decimal string in KRW. */
    @JsonProperty("supply_amount")
    private String supplyAmount;

    /** VAT amount (0 for ZERO_RATED_EXPORT / EXEMPT), decimal string in KRW. */
    @JsonProperty("vat_amount")
    private String vatAmount;

    /** Total invoice amount (supply + VAT), decimal string in KRW. */
    @JsonProperty("total_amount")
    private String totalAmount;

    /** VAT treatment code: ZERO_RATED_EXPORT / STANDARD / EXEMPT. */
    @JsonProperty("vat_treatment")
    private String vatTreatment;

    public HometaxInvoiceRequest() {}

    public HometaxInvoiceRequest(
            String issuerCertId,
            long merchantPartnerId,
            YearMonth billingPeriod,
            BigDecimal supplyAmount,
            BigDecimal vatAmount,
            BigDecimal totalAmount,
            String vatTreatment) {
        this.issuerCertId = issuerCertId;
        this.merchantPartnerId = merchantPartnerId;
        this.billingPeriod = billingPeriod.toString();
        this.supplyAmount = supplyAmount.toPlainString();
        this.vatAmount = vatAmount.toPlainString();
        this.totalAmount = totalAmount.toPlainString();
        this.vatTreatment = vatTreatment;
    }

    public String getIssuerCertId() { return issuerCertId; }
    public long getMerchantPartnerId() { return merchantPartnerId; }
    public String getBillingPeriod() { return billingPeriod; }
    public String getSupplyAmount() { return supplyAmount; }
    public String getVatAmount() { return vatAmount; }
    public String getTotalAmount() { return totalAmount; }
    public String getVatTreatment() { return vatTreatment; }
}
