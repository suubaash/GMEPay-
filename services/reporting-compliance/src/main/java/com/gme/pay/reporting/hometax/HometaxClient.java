package com.gme.pay.reporting.hometax;

/**
 * Port for the NTS (National Tax Service) Hometax e-tax-invoice submission API.
 *
 * <p>In production this will be implemented as an mTLS HTTP client calling the
 * NTS Hometax API with the GME issuer certificate. In Phase-1 and tests the
 * default implementation is {@link StubHometaxClient}, which returns a fake
 * {@code invoiceId} and {@code ntsConfirmation} without making any real network
 * call — no NTS credentials are available locally.
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
