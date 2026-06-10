package com.gme.pay.payment.domain.settlement;

import com.gme.pay.payment.domain.client.PartnerConfigClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Plain JUnit 5 unit tests for {@link SettlementBookingService}.
 * Uses a 1-line hand-rolled {@link PartnerConfigClient} fake; no Spring context.
 */
class SettlementBookingServiceTest {

    /** Hand-rolled partner-config client whose loadPartner returns the given mode/currency. */
    private static PartnerConfigClient fakeClient(String currency, RoundingMode mode) {
        return id -> new PartnerConfigClient.PartnerConfigView(id, "OVERSEAS", currency, mode);
    }

    // ======================================================================
    // (a) DOWN partner: books FLOOR-2dp and yields positive residual
    // ======================================================================
    @Test
    @DisplayName("DOWN partner books FLOOR-2dp and yields positive residual")
    void downPartner_booksFloor_positiveResidual() {
        SettlementBookingService svc = new SettlementBookingService(fakeClient("USD", RoundingMode.DOWN));

        SettlementBooking booking = svc.book(7L, new BigDecimal("10500.567"), "USD");

        assertNotNull(booking);
        assertEquals(0, booking.booked().compareTo(new BigDecimal("10500.56")),
                "DOWN should floor to 2dp -> 10500.56");
        // residual = precise - booked = 10500.567 - 10500.56 = 0.007 (> 0)
        assertEquals(1, booking.residual().signum(), "positive residual expected");
        assertEquals(0, booking.residual().compareTo(new BigDecimal("0.007")));
        assertEquals(RoundingMode.DOWN, booking.mode());
        assertEquals("USD", booking.currency());
    }

    // ======================================================================
    // (b) HALF_UP partner: rounds half-up
    // ======================================================================
    @Test
    @DisplayName("HALF_UP partner rounds half-up")
    void halfUpPartner_roundsHalfUp() {
        SettlementBookingService svc = new SettlementBookingService(fakeClient("USD", RoundingMode.HALF_UP));

        SettlementBooking booking = svc.book(7L, new BigDecimal("10500.565"), "USD");

        // HALF_UP at 2dp: 10500.565 -> 10500.57
        assertEquals(0, booking.booked().compareTo(new BigDecimal("10500.57")));
        // residual = 10500.565 - 10500.57 = -0.005 (loss)
        assertEquals(-1, booking.residual().signum(), "negative residual expected (rounding loss)");
        assertEquals(0, booking.residual().compareTo(new BigDecimal("-0.005")));
        assertEquals(RoundingMode.HALF_UP, booking.mode());
    }

    // ======================================================================
    // (c) booked + residual reconstructs precise
    // ======================================================================
    @Test
    @DisplayName("booked + residual exactly reconstructs the precise amount")
    void bookedPlusResidual_reconstructsPrecise() {
        SettlementBookingService svc = new SettlementBookingService(fakeClient("USD", RoundingMode.DOWN));
        BigDecimal precise = new BigDecimal("10500.567");

        SettlementBooking booking = svc.book(99L, precise, "USD");

        BigDecimal reconstructed = booking.booked().add(booking.residual());
        assertEquals(0, reconstructed.compareTo(precise),
                "booked + residual must equal precise, got " + reconstructed);
    }
}
