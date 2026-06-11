package com.gme.pay.contracts;

import com.gme.pay.domain.PartnerType;
import org.junit.jupiter.api.Test;

import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Sanity tests for the Slice 1 canonical contract DTOs in {@link PartnerView} /
 * {@link PartnerCommand}. These exercise the seams the 5-DTO collapse adds (factory,
 * wrapper convenience constructors, command -> view adapter) so a regression
 * here surfaces in this module rather than only downstream.
 */
class PartnerContractsTest {

    @Test
    void ofCoreFillsLaterSliceFieldsWithNullAndDefaultsStatusToOnboarding() {
        PartnerView view = PartnerView.ofCore(
                42L, "GMEREMIT", PartnerType.LOCAL, "KRW", RoundingMode.HALF_UP);

        assertEquals(42L, view.id());
        assertEquals("GMEREMIT", view.partnerCode());
        assertEquals(PartnerType.LOCAL, view.type());
        assertEquals("KRW", view.settlementCurrency());
        assertEquals(RoundingMode.HALF_UP, view.settlementRoundingMode());

        // Status defaults to ONBOARDING per ADR-011.
        assertEquals(PartnerStatus.ONBOARDING, view.status());

        // Later-slice fields stay null until those slices populate them.
        assertNull(view.legalNameLocal());
        assertNull(view.legalNameRomanized());
        assertNull(view.taxId());
        assertNull(view.taxIdType());
        assertNull(view.countryOfIncorporation());
        assertNull(view.legalForm());
        assertNull(view.registeredAddress());
        assertNull(view.operatingAddress());
        assertNull(view.lei());
        assertNull(view.validFrom());
        assertNull(view.validTo());
        assertNull(view.recordedAt());
    }

    @Test
    void partnerCommandCreateConvenienceConstructorOnlyHoldsCreateDraft() {
        PartnerCommand.CreateDraft draft = new PartnerCommand.CreateDraft(
                "ACME", PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP,
                "(주)에이씨엠이", "ACME Co., Ltd.",
                "123-45-67890", "KR-BRN",
                "KR", "CORP",
                null, null, null);

        PartnerCommand cmd = PartnerCommand.create(draft);
        assertNotNull(cmd.createDraft());
        assertEquals(draft, cmd.createDraft());
        assertNull(cmd.updateStep1());
    }

    @Test
    void addressCommandAdaptsToViewWithIdenticalFields() {
        AddressCommand cmd = new AddressCommand(
                "1 Yeouido-ro", "12F", "Seoul", "Yeongdeungpo-gu", "07321", "KR");

        AddressView view = cmd.toView();
        assertEquals(cmd.street1(), view.street1());
        assertEquals(cmd.street2(), view.street2());
        assertEquals(cmd.city(), view.city());
        assertEquals(cmd.state(), view.state());
        assertEquals(cmd.postcode(), view.postcode());
        assertEquals(cmd.country(), view.country());
    }
}
