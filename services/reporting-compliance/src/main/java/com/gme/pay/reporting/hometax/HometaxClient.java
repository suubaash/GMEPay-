package com.gme.pay.reporting.hometax;

/**
 * Port for the NTS (National Tax Service) Hometax e-tax-invoice submission API.
 *
 * <p>In production this will be implemented as an mTLS HTTP client calling the
 * NTS Hometax API with the GME issuer certificate. No such implementation exists yet
 * (NTS mTLS onboarding externally gated, OI-02), so the wired implementation everywhere
 * today is {@link StubHometaxClient}, which makes no network call and returns
 * {@link HometaxInvoiceResponse#notFiled(String)} — status
 * {@code NOT_FILED_CHANNEL_UNAVAILABLE}, no invoice id, no confirmation number.
 *
 * <p><b>Contract for any future implementation:</b> {@code invoiceId} and
 * {@code ntsConfirmation} may only be populated with values actually issued by NTS.
 * A response must never carry a success status for a submission that did not occur.
 *
 * <p>Configuration keys consumed by the production implementation:
 * <ul>
 *   <li>{@code gmepay.hometax.base-url} — base URL of the NTS Hometax API</li>
 *   <li>{@code gmepay.hometax.cert-id} — lib-vault document id of the mTLS
 *       client certificate</li>
 * </ul>
 */
public interface HometaxClient {

    /**
     * Submits an e-tax-invoice to Hometax.
     *
     * @param request the invoice payload (supply amount, VAT, period, cert id)
     * @return the NTS response containing an invoice id and confirmation number
     */
    HometaxInvoiceResponse submitInvoice(HometaxInvoiceRequest request);
}
