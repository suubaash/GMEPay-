package com.gme.pay.reporting.hometax;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result of an attempt to file an e-tax-invoice with Hometax (NTS).
 *
 * <p>Field names of the NTS-issued values are fixed by the NTS API contract; Jackson
 * binds by name.
 *
 * <h2>Honesty contract (GAP T5-2)</h2>
 * {@code invoiceId} and {@code ntsConfirmation} may only ever hold values <b>issued by
 * NTS</b>. When no NTS channel is available (the normal case today — the mTLS onboarding
 * is externally gated, OI-02) the correct response is {@link #notFiled(String)}, which
 * carries {@code status = NOT_FILED_CHANNEL_UNAVAILABLE}, null ids and the reason. The
 * previous stub returned {@code status = "ACCEPTED"} with a spec-shaped fake confirmation
 * number, which read as an accepted NTS filing that never left the JVM.
 */
public class HometaxInvoiceResponse {

    /** NTS-assigned invoice id (세금계산서 승인번호). Null unless NTS issued one. */
    @JsonProperty("invoice_id")
    private String invoiceId;

    /** NTS confirmation number (국세청 접수번호). Null unless NTS issued one. */
    @JsonProperty("nts_confirmation")
    private String ntsConfirmation;

    /**
     * Filing status. Either the status returned by NTS, or the local
     * {@link com.gme.pay.reporting.persistence.ReportFiling.Status} name describing why no
     * filing happened (e.g. {@code NOT_FILED_CHANNEL_UNAVAILABLE}).
     */
    @JsonProperty("status")
    private String status;

    /** Non-null when nothing was filed: names the missing NTS channel configuration. */
    @JsonProperty("channel_unavailable_reason")
    private String channelUnavailableReason;

    public HometaxInvoiceResponse() {}

    public HometaxInvoiceResponse(String invoiceId, String ntsConfirmation, String status) {
        this.invoiceId = invoiceId;
        this.ntsConfirmation = ntsConfirmation;
        this.status = status;
    }

    /**
     * The invoice was aggregated but <b>not filed</b> — no NTS channel exists. Carries no
     * invoice id and no confirmation number, because NTS issued neither.
     *
     * @param reason why no filing was possible (surfaced to operators/auditors)
     */
    public static HometaxInvoiceResponse notFiled(String reason) {
        HometaxInvoiceResponse r = new HometaxInvoiceResponse(
                null, null,
                com.gme.pay.reporting.persistence.ReportFiling.Status
                        .NOT_FILED_CHANNEL_UNAVAILABLE.name());
        r.channelUnavailableReason = reason;
        return r;
    }

    /** {@code true} when no filing took place (no NTS-issued confirmation present). */
    public boolean isFiled() {
        return ntsConfirmation != null && !ntsConfirmation.isBlank();
    }

    public String getInvoiceId() { return invoiceId; }
    public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }

    public String getNtsConfirmation() { return ntsConfirmation; }
    public void setNtsConfirmation(String ntsConfirmation) { this.ntsConfirmation = ntsConfirmation; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getChannelUnavailableReason() { return channelUnavailableReason; }
    public void setChannelUnavailableReason(String reason) { this.channelUnavailableReason = reason; }
}
