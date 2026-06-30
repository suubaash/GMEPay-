package com.gme.pay.router.resolve;

/**
 * QR presentment mode the customer's wallet uses, the second axis (after
 * country/location) of data-driven scheme resolution.
 *
 * <ul>
 *   <li>{@code CPM} — Customer-Presented Mode: the wallet shows a dynamic QR /
 *       token that the merchant terminal scans. Backed by a scheme row's
 *       {@code approvalMethodCpm} wiring.</li>
 *   <li>{@code MPM} — Merchant-Presented Mode: the merchant shows a static or
 *       dynamic QR that the customer wallet scans. Backed by a scheme row's
 *       {@code approvalMethodMpm} wiring.</li>
 * </ul>
 *
 * <p>A scheme "supports" a mode when the matching {@code approvalMethod*}
 * column on its enabled {@code partner_scheme} row is populated; an unpopulated
 * column means that scheme is not wired for that presentment mode and the
 * resolver raises {@link ResolutionError#PAYMENT_MODE_NOT_SUPPORTED}.
 */
public enum PaymentMode {
    CPM,
    MPM
}
