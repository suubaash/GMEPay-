package com.gme.pay.registry.partner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gme.pay.domain.Partner;
import com.gme.pay.errors.ApiException;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class PartnerStoreTest {

    private final PartnerStore store = new PartnerStore();

    @Test
    void seededPartnersHaveExpectedRoundingModes() {
        assertEquals(RoundingMode.HALF_UP, store.get("GMEREMIT").settlementRoundingMode());
        assertEquals(RoundingMode.DOWN, store.get("SENDMN").settlementRoundingMode());
    }

    @Test
    void updateRoundingModePersists() {
        Partner p = store.updateRoundingMode("GMEREMIT", RoundingMode.FLOOR);
        assertEquals(RoundingMode.FLOOR, p.settlementRoundingMode());
        assertEquals(RoundingMode.FLOOR, store.get("GMEREMIT").settlementRoundingMode());
    }

    @Test
    void unknownPartnerThrows() {
        assertThrows(ApiException.class, () -> store.get("NOPE"));
    }
}
