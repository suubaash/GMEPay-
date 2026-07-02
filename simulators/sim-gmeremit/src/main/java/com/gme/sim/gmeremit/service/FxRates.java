package com.gme.sim.gmeremit.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Mock cross-border FX for the wallet sim.
 *
 * <p>PRODUCTION NOTE: real FX is sourced from the {@code rate-fx} service. This is a sim-only
 * fixed rate + margin (mirroring how {@code sim-wallet} handles its SENDMN corridor) so the
 * wallet can show the customer a KRW-debit estimate for a Nepal (NPR) payment.
 *
 * <p>KRW debit for an NPR amount = {@code nprAmount * krwPerNpr * (1 + margin)}, rounded to
 * whole KRW.
 */
@Component
public class FxRates {

    private final BigDecimal krwPerNpr;
    private final BigDecimal margin;

    public FxRates(
            @Value("${gmepay.sim.fx.krw-per-npr:1.05}") String krwPerNpr,
            @Value("${gmepay.sim.fx.npr-margin:0.02}") String margin) {
        this.krwPerNpr = new BigDecimal(krwPerNpr);
        this.margin    = new BigDecimal(margin);
    }

    /** Effective KRW-per-NPR rate including the sim margin. */
    public BigDecimal effectiveKrwPerNpr() {
        return krwPerNpr.multiply(BigDecimal.ONE.add(margin));
    }

    /** KRW cost of the given NPR amount at the effective rate, rounded to whole KRW. */
    public BigDecimal nprToKrw(BigDecimal nprAmount) {
        return nprAmount.multiply(effectiveKrwPerNpr()).setScale(0, RoundingMode.HALF_UP);
    }
}
