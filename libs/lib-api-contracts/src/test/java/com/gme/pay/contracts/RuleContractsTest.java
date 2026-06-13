package com.gme.pay.contracts;

import com.gme.pay.domain.PartnerType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Sanity tests for the Slice 6 contract DTOs — {@link RuleCommand} /
 * {@link RuleView} / {@link PartnerCommand.UpdateStep6Rules} — and the V016
 * currency-split fields on {@link PartnerView}. These exercise the seams in
 * this module so a regression surfaces here rather than only downstream.
 */
class RuleContractsTest {

    @Test
    void updateStep6RulesCarriesTheFullRuleSet() {
        RuleCommand outbound = new RuleCommand(
                "ZEROPAY", "OUTBOUND",
                new BigDecimal("0.0150"), new BigDecimal("0.0100"),
                new BigDecimal("1.5000"));
        RuleCommand inbound = new RuleCommand(
                "ZEROPAY", "INBOUND",
                new BigDecimal("0.0200"), new BigDecimal("0.0050"),
                null);

        PartnerCommand.UpdateStep6Rules cmd =
                new PartnerCommand.UpdateStep6Rules(List.of(outbound, inbound));

        assertEquals(2, cmd.rules().size());
        assertEquals(outbound, cmd.rules().get(0));
        assertEquals("ZEROPAY", cmd.rules().get(0).schemeId());
        assertEquals("OUTBOUND", cmd.rules().get(0).direction());
        assertEquals(new BigDecimal("0.0150"), cmd.rules().get(0).mA());
        // serviceChargeUsd is optional on the wire (server defaults it to 0).
        assertNull(cmd.rules().get(1).serviceChargeUsd());
    }

    @Test
    void ruleViewCarriesTheRuleKeyAndScale4Decimals() {
        java.time.Instant now = java.time.Instant.now();
        RuleView view = new RuleView(
                7L, "ZEROPAY", "BOTH",
                new BigDecimal("0.0120"), new BigDecimal("0.0080"),
                new BigDecimal("2.0000"),
                now, null, now);

        assertEquals(7L, view.id());
        assertEquals("ZEROPAY", view.schemeId());
        assertEquals("BOTH", view.direction());
        assertEquals(new BigDecimal("0.0120"), view.mA());
        assertEquals(new BigDecimal("0.0080"), view.mB());
        assertEquals(new BigDecimal("2.0000"), view.serviceChargeUsd());
        assertEquals(now, view.validFrom());
        assertNull(view.validTo());
    }

    @Test
    void ofCoreMirrorsTheLegacyCurrencyOntoTheV016Split() {
        PartnerView view = PartnerView.ofCore(
                42L, "GMEREMIT", PartnerType.LOCAL, "KRW", RoundingMode.HALF_UP);

        // Expand-phase contract (ADR-013): before the commercial-terms step
        // writes a real split, collection and settlement are the same fact —
        // and the legacy column stays populated.
        assertEquals("KRW", view.settlementCurrency());
        assertEquals("KRW", view.collectionCcy());
        assertEquals("KRW", view.settleACcy());
    }

    @Test
    void partnerViewCarriesAGenuineSplitDistinctFromTheLegacyColumn() {
        PartnerView view = new PartnerView(
                1L, "ACME", PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP,
                "KRW", "USD",
                null, null, null, null, null, null, null, null, null,
                PartnerStatus.ONBOARDING, null, null, null);

        assertEquals("USD", view.settlementCurrency());
        assertEquals("KRW", view.collectionCcy());
        assertEquals("USD", view.settleACcy());
    }
}
