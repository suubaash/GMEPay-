package com.gme.pay.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Sanity tests for the Slice 6 commercial-terms contract DTOs —
 * {@link FeeScheduleView} / {@link FeeScheduleCommand} / {@link FeeTier} /
 * {@link FxConfigView} / {@link LimitsView} / {@link ContractView} /
 * {@link CommercialTermsView} and the composite
 * {@link PartnerCommand.UpdateStep6Commercial}. These exercise the seams the
 * composite payload adds (section nullability, shared {@link FeeTier} between
 * read and write shapes) so a regression here surfaces in this module rather
 * than only downstream.
 */
class CommercialContractsTest {

    @Test
    void updateStep6CommercialCarriesEachSectionIndependently() {
        FxConfigCommand fx = new FxConfigCommand(
                new BigDecimal("85"), "SEOUL_FX_BROKER", 300);

        PartnerCommand.UpdateStep6Commercial fxOnly =
                new PartnerCommand.UpdateStep6Commercial(null, fx, null, null);

        // Null sections stay null — the composite's "untouched" semantics
        // depend on the record NOT defaulting them.
        assertNull(fxOnly.feeSchedules());
        assertEquals(fx, fxOnly.fxConfig());
        assertNull(fxOnly.limits());
        assertNull(fxOnly.contract());
    }

    @Test
    void feeTierIsSharedBetweenCommandAndViewShapes() {
        FeeTier tier = new FeeTier(new BigDecimal("10000"), new BigDecimal("20"));

        FeeScheduleCommand cmd = new FeeScheduleCommand(
                "zeropay_kr", "OUTBOUND",
                new BigDecimal("1.50"), new BigDecimal("25"), List.of(tier));
        FeeScheduleView view = new FeeScheduleView(
                1L, cmd.schemeId(), cmd.direction(), cmd.fixedFeeUsd(), cmd.bpsFee(),
                cmd.tiers(), null, null, null);

        // The SAME FeeTier instance round-trips command -> view: the wire
        // shape of a tier is identical on both sides by construction.
        assertEquals(cmd.tiers(), view.tiers());
        assertEquals(new BigDecimal("10000"), view.tiers().get(0).fromVolumeUsd());
        assertEquals(new BigDecimal("20"), view.tiers().get(0).bpsOverride());
    }

    @Test
    void commercialTermsViewHoldsNullForUntouchedSections() {
        ContractView contract = new ContractView(
                7L, LocalDate.of(2026, 7, 1), null, false, null, null, null,
                null, null, null);

        CommercialTermsView view = new CommercialTermsView(null, null, null, contract);

        assertNull(view.feeSchedules());
        assertNull(view.fxConfig());
        assertNull(view.limits());
        assertEquals(contract, view.contract());
        assertNull(view.contract().effectiveTo());
        assertEquals(LocalDate.of(2026, 7, 1), view.contract().effectiveFrom());
    }

    @Test
    void limitsCommandCarriesAllCapsAndLicense() {
        LimitsCommand cmd = new LimitsCommand(
                new BigDecimal("1"), new BigDecimal("5000"),
                new BigDecimal("10000"), new BigDecimal("30000"),
                new BigDecimal("50000"), "SOAEK_HAEOEMONG");

        assertEquals(new BigDecimal("5000"), cmd.perTxnMaxUsd());
        assertEquals(new BigDecimal("50000"), cmd.annualCapUsd());
        assertEquals("SOAEK_HAEOEMONG", cmd.licenseType());
    }
}
