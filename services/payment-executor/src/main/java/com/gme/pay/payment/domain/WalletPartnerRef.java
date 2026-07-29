package com.gme.pay.payment.domain;

import org.springframework.lang.Nullable;

/**
 * The partner whose regulatory limits apply to a wallet payment: its config-registry partner
 * {@code code} (the key {@code GET /v1/partners/{code}/limits} uses) plus the numeric {@code id}
 * prefunding's cumulative-usage ledger is keyed by.
 *
 * <p><b>Which partner?</b> The SENDING partner — the wallet issuer charging the customer — not the
 * receiving/routing partner a QR resolves to. It is that partner's licence (소액해외송금업 among
 * them) whose per-transaction and rolling caps the FSS asks about after a breach, so the wallet entry
 * point ({@code POST /v1/pay}) supplies its own alias, which IS the config-registry partner code.
 *
 * <p>{@link #none()} means "no limit subject known" — the gate then cannot resolve limits and logs
 * that it is running unconstrained. Only legacy/test call paths produce it; the production wallet
 * controller always supplies a real ref.
 */
public record WalletPartnerRef(@Nullable String code, long id) {

    /**
     * GMEREMIT sandbox identity. Matches {@code WalletPayController.GMEREMIT_PARTNER_ID} (and the
     * {@code X-Partner-Id} default of 1 used by the orchestrated endpoints) so the cumulative-usage
     * ledger rows of a wallet payment and an authorize land on ONE partner row.
     */
    public static final WalletPartnerRef GMEREMIT = new WalletPartnerRef("GMEREMIT", 1L);

    /** SENDMN sandbox identity. Matches {@code WalletPayController.SENDMN_PARTNER_ID}. */
    public static final WalletPartnerRef SENDMN = new WalletPartnerRef("SENDMN", 2L);

    public static WalletPartnerRef of(@Nullable String code, long id) {
        return new WalletPartnerRef(code, id);
    }

    /** No known limit subject — the gate cannot resolve limits (logged, never silently assumed). */
    public static WalletPartnerRef none() {
        return new WalletPartnerRef(null, 0L);
    }

    /** True when a partner code is present, i.e. limits CAN be resolved for this payment. */
    public boolean isKnown() {
        return code != null && !code.isBlank();
    }
}
