package com.gme.pay.contracts;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanity tests for the Slice 7 corridor contract DTOs —
 * {@link PartnerCorridorCommand} / {@link PartnerCorridorView} /
 * {@link PartnerCommand.UpdateStep7Corridors}. These exercise the seams in
 * this module so a regression surfaces here rather than only downstream.
 */
class PartnerCorridorContractsTest {

    @Test
    void updateStep7CorridorsCarriesTheFullCorridorSet() {
        PartnerCorridorCommand krMn = new PartnerCorridorCommand(
                "KR", "KRW", "MN", "MNT", LocalDate.of(2026, 7, 1), true);
        PartnerCorridorCommand krVn = new PartnerCorridorCommand(
                "KR", "KRW", "VN", "VND", null, null);

        PartnerCommand.UpdateStep7Corridors cmd =
                new PartnerCommand.UpdateStep7Corridors(List.of(krMn, krVn));

        assertEquals(2, cmd.corridors().size());
        assertEquals(krMn, cmd.corridors().get(0));
        assertEquals("KR", cmd.corridors().get(0).srcCountry());
        assertEquals("KRW", cmd.corridors().get(0).srcCcy());
        assertEquals("MN", cmd.corridors().get(0).dstCountry());
        assertEquals("MNT", cmd.corridors().get(0).dstCcy());
        assertEquals(LocalDate.of(2026, 7, 1), cmd.corridors().get(0).goLiveDate());
        // goLiveDate and isActive are optional on the wire (server defaults
        // isActive to true, the V023 column default; goLiveDate stays null).
        assertNull(cmd.corridors().get(1).goLiveDate());
        assertNull(cmd.corridors().get(1).isActive());
    }

    @Test
    void corridorViewCarriesTheLaneKeyAndLifecycleFields() {
        PartnerCorridorView view = new PartnerCorridorView(
                42L, "KR", "KRW", "KH", "KHR", LocalDate.of(2026, 9, 15), false);

        assertEquals(42L, view.partnerId());
        assertEquals("KR", view.srcCountry());
        assertEquals("KRW", view.srcCcy());
        assertEquals("KH", view.dstCountry());
        assertEquals("KHR", view.dstCcy());
        assertEquals(LocalDate.of(2026, 9, 15), view.goLiveDate());
        assertEquals(Boolean.FALSE, view.isActive());
    }

    @Test
    void corridorViewToleratesUnscheduledLanes() {
        PartnerCorridorView view = new PartnerCorridorView(
                7L, "KR", "KRW", "SG", "SGD", null, true);

        assertNull(view.goLiveDate());
        assertTrue(view.isActive());
    }
}
