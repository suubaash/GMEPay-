package com.gme.pay.contracts;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Sanity tests for the Slice 8 Lane C regulatory contract surface — the five
 * roster enums, {@link PartnerRegulatoryConfigView} /
 * {@link PartnerRegulatoryConfigCommand},
 * {@link PartnerCommand.UpdateStep8Regulatory}, and the {@code strEnabled}
 * extension of the Slice 7 corridor DTOs. These exercise the seams in this
 * module so a regression surfaces here rather than only downstream.
 */
class RegulatoryContractsTest {

    @Test
    void rosterEnums_matchTheV029CheckConstraints() {
        // Spelled out value-by-value: these names ARE the V029 CHECK rosters —
        // renaming one silently breaks the DB write path.
        assertEquals(List.of("INDIVIDUAL_AGGREGATE", "INSTITUTIONAL"),
                names(BokFxReportingCategory.values()));
        assertEquals(List.of("INDIVIDUAL", "CORPORATION", "GOVERNMENT",
                        "FINANCIAL_INSTITUTION"),
                names(BokRemitterType.values()));
        assertEquals(List.of("ZERO_RATED_EXPORT", "STANDARD", "EXEMPT"),
                names(VatTreatment.values()));
        assertEquals(List.of("CONSENT", "CONTRACT", "LEGAL_OBLIGATION",
                        "VITAL_INTEREST", "PUBLIC_TASK", "LEGITIMATE_INTEREST"),
                names(LegalBasisCode.values()));
        assertEquals(List.of("TRP", "SYGNA", "IVMS101", "NONE"),
                names(TravelRuleProtocol.values()));
    }

    @Test
    void updateStep8RegulatoryCarriesTheFullPanel() {
        PartnerRegulatoryConfigCommand cmd = new PartnerRegulatoryConfigCommand(
                "601", "INSTITUTIONAL", "CORPORATION",
                "vault-doc-cert-0001", "ZERO_RATED_EXPORT",
                "KOFIU-GME-001", new BigDecimal("20000000"),
                List.of("MN", "VN"), "CONTRACT",
                "TRP", "https://trp.example.com/v1", new BigDecimal("1000000"));

        PartnerCommand.UpdateStep8Regulatory wrapper =
                new PartnerCommand.UpdateStep8Regulatory(cmd);

        assertEquals(cmd, wrapper.regulatory());
        assertEquals("601", wrapper.regulatory().bokTxnCode());
        assertEquals("KOFIU-GME-001", wrapper.regulatory().kofiuEntityId());
        assertEquals(List.of("MN", "VN"), wrapper.regulatory().pipaJurisdictionAllowlist());
        assertEquals("https://trp.example.com/v1",
                wrapper.regulatory().travelRuleEndpointUrl());
        // Money stays BigDecimal end to end (decimal STRING on the wire via
        // @JsonFormat — never a float).
        assertEquals(new BigDecimal("20000000"), wrapper.regulatory().ctrThresholdKrw());
    }

    @Test
    void regulatoryViewCarriesTypedRostersAndKrwThresholds() {
        PartnerRegulatoryConfigView view = new PartnerRegulatoryConfigView(
                42L, "601",
                BokFxReportingCategory.INDIVIDUAL_AGGREGATE,
                BokRemitterType.INDIVIDUAL,
                "vault-doc-cert-7", VatTreatment.STANDARD,
                "KOFIU-GME-007", new BigDecimal("10000000.00"),
                List.of("MN"), LegalBasisCode.CONSENT,
                TravelRuleProtocol.NONE, null, new BigDecimal("1000000.00"));

        assertEquals(42L, view.partnerId());
        assertEquals(BokFxReportingCategory.INDIVIDUAL_AGGREGATE,
                view.bokFxReportingCategory());
        assertEquals(VatTreatment.STANDARD, view.vatTreatment());
        assertEquals(LegalBasisCode.CONSENT, view.legalBasisCode());
        assertEquals(TravelRuleProtocol.NONE, view.travelRuleProtocol());
        // NONE legitimately carries no endpoint.
        assertNull(view.travelRuleEndpointUrl());
        assertEquals(new BigDecimal("10000000.00"), view.ctrThresholdKrw());
        assertEquals(List.of("MN"), view.pipaJurisdictionAllowlist());
    }

    @Test
    void corridorDtos_surfaceStrEnabled_withSlice7CompatibleArity() {
        // New canonical arity carries the V029.1 STR flag.
        PartnerCorridorCommand cmd = new PartnerCorridorCommand(
                "KR", "KRW", "MN", "MNT", null, true, true);
        assertEquals(Boolean.TRUE, cmd.strEnabled());

        PartnerCorridorView view = new PartnerCorridorView(
                7L, "KR", "KRW", "MN", "MNT", null, true, true);
        assertEquals(Boolean.TRUE, view.strEnabled());

        // Pre-Slice-8 arity stays source-compatible: the command leaves the
        // flag null (server defaults FALSE, the column default); the view
        // overload mirrors the default directly.
        PartnerCorridorCommand legacyCmd = new PartnerCorridorCommand(
                "KR", "KRW", "VN", "VND", null, null);
        assertNull(legacyCmd.strEnabled());

        PartnerCorridorView legacyView = new PartnerCorridorView(
                7L, "KR", "KRW", "VN", "VND", null, true);
        assertFalse(legacyView.strEnabled());
    }

    private static List<String> names(Enum<?>[] values) {
        return java.util.Arrays.stream(values).map(Enum::name).toList();
    }
}
