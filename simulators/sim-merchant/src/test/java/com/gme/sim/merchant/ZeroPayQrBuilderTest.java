package com.gme.sim.merchant;

import static org.junit.jupiter.api.Assertions.*;

import com.gme.sim.merchant.emvco.Crc16;
import com.gme.sim.merchant.emvco.ZeroPayQrBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Spec-faithful ZeroPay EMVCo QR builder tests. */
class ZeroPayQrBuilderTest {

    @Test
    @DisplayName("static QR is QR구분 1, has no amount, uses KRW numeric 410, and a valid CRC")
    void staticQr() {
        ZeroPayQrBuilder.QrResult q = ZeroPayQrBuilder.buildStatic(
                "01", "ZP-M0001", "5812", "Seoul Noodle House", "Seoul");
        assertEquals("1", q.qrDivision());
        assertNull(q.amountKrw());
        assertTrue(Crc16.verify(q.qrPayload()), "CRC must verify");
        assertTrue(q.qrPayload().startsWith("000201"), "payload format + static initiation");
        assertTrue(q.qrPayload().contains("010211"), "tag 01 = 11 (static)");
        assertTrue(q.qrPayload().contains("5303410"), "tag 53 = currency 410 (KRW)");
    }

    @Test
    @DisplayName("dynamic QR is QR구분 2, embeds amount + serial + check char, valid CRC")
    void dynamicQr() {
        ZeroPayQrBuilder.QrResult q = ZeroPayQrBuilder.buildDynamic(
                "01", "ZP-M0001", "ZPQR00000042", "5812", "Seoul Noodle House", "Seoul", 12500L);
        assertEquals("2", q.qrDivision());
        assertEquals(12500L, q.amountKrw());
        assertEquals("ZPQR00000042", q.qrSerial());
        assertEquals(4, q.checkChar().length(), "체크문자 is 4 chars");
        assertTrue(Crc16.verify(q.qrPayload()));
        assertTrue(q.qrPayload().contains("010212"), "tag 01 = 12 (dynamic)");
        assertTrue(q.qrPayload().contains("5405" + "12500"), "tag 54 amount");
    }

    @Test
    @DisplayName("extract() round-trips the discrete ZeroPay fields out of a built QR")
    void extractRoundTrip() {
        ZeroPayQrBuilder.QrResult built = ZeroPayQrBuilder.buildDynamic(
                "07", "ZP-CAFE-01", "ZPQR00000777", "5814", "Gangnam Coffee", "Seoul", 4800L);
        ZeroPayQrBuilder.QrResult parsed = ZeroPayQrBuilder.extract(built.qrPayload());
        assertEquals("2", parsed.qrDivision());
        assertEquals("07", parsed.registrarId());
        assertEquals("ZP-CAFE-01", parsed.merchantId());
        assertEquals("ZPQR00000777", parsed.qrSerial());
        assertEquals(built.checkChar(), parsed.checkChar());
        assertEquals(4800L, parsed.amountKrw());
    }

    @Test
    @DisplayName("check char is deterministic for the same inputs")
    void checkCharDeterministic() {
        String a = ZeroPayQrBuilder.checkChar("01", "ZP-M0001", "ZPQR1", 100L);
        String b = ZeroPayQrBuilder.checkChar("01", "ZP-M0001", "ZPQR1", 100L);
        assertEquals(a, b);
        String diff = ZeroPayQrBuilder.checkChar("01", "ZP-M0001", "ZPQR2", 100L);
        assertNotEquals(a, diff, "different serial should usually change the check char");
    }

    @Test
    @DisplayName("a tampered payload fails CRC verification")
    void tamperedCrcFails() {
        ZeroPayQrBuilder.QrResult q = ZeroPayQrBuilder.buildStatic(
                "01", "ZP-M0001", "5812", "Seoul Noodle House", "Seoul");
        String p = q.qrPayload();
        char last = p.charAt(p.length() - 1);
        String tampered = p.substring(0, p.length() - 1) + (last == '0' ? '1' : '0');
        assertFalse(Crc16.verify(tampered));
    }
}
