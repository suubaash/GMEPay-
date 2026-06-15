package com.gme.pay.reporting.hometax;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response returned by the Hometax e-tax-invoice submission endpoint.
 *
 * <p>Field names are fixed by the NTS API contract. Jackson binds by name.
 */
public class HometaxInvoiceResponse {

    /** NTS-assigned invoice id (세금계산서 승인번호). */
    @JsonProperty("invoice_id")
    private String invoiceId;

    /** NTS confirmation number (국세청 접수번호). */
    @JsonProperty("nts_confirmation")
    private String ntsConfirmation;

    /** Submission status, e.g. "ACCEPTED". */
    @JsonProperty("status")
    private String status;

    public HometaxInvoiceResponse() {}

    public HometaxInvoiceResponse(String invoiceId, String ntsConfirmation, String status) {
        this.invoiceId = invoiceId;
        this.ntsConfirmation = ntsConfirmation;
        this.status = status;
    }

    public String getInvoiceId() { return invoiceId; }
    public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }

    public String getNtsConfirmation() { return ntsConfirmation; }
    public void setNtsConfirmation(String ntsConfirmation) { this.ntsConfirmation = ntsConfirmation; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
