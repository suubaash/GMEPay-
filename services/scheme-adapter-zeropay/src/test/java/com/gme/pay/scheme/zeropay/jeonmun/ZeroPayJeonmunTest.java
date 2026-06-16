package com.gme.pay.scheme.zeropay.jeonmun;

import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.CHARSET;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_AMOUNT;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_DIRECT_PREPAID_AMOUNT;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_MERCHANT_FEE;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_MERCHANT_ID;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_MESSAGE_TYPE;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_OPERATOR_PREPAID_AMOUNT;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_PREPAID_COMBO_CODE;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_QR_DIVISION;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_QR_SERIAL;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_RESPONSE_CODE;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_SYSTEM_CODE;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_TXN_DIVISION;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_TXN_UNIQUE_NO;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_VAT;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.MPM_DYNAMIC_420000;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.MSG_PAYMENT_RESP;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.ONLINE_MESSAGE_LENGTH;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.SYSTEM_CODE;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.TXN_DIV_MPM_DYNAMIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ZeroPayJeonmunTest {

    private static ZeroPayMpm420000.PaymentRequest sample(boolean overseas) {
        return new ZeroPayMpm420000.PaymentRequest(
                "PTXN1234567", "001", 1000L, 91L,
                "ZP", "QR-SERIAL-0001", "AB12", "M0000000001", "TERM01",
                overseas, "00000042",
                LocalDate.of(2026, 6, 16), LocalDateTime.of(2026, 6, 16, 8, 30, 15));
    }

    @Test
    @DisplayName("420000 layout is exactly the 1,000-byte online body")
    void layoutIsExactly1000Bytes() {
        assertEquals(1000, JeonmunCodec.length(MPM_DYNAMIC_420000));
        assertEquals(1000, ONLINE_MESSAGE_LENGTH);
    }

    @Test
    @DisplayName("payment encodes to a 1,000-byte frame with fields at the right byte offsets")
    void encodesFramedFieldsAtCorrectOffsets() {
        byte[] msg = ZeroPayMpm420000.encodePayment(sample(true));
        assertEquals(1000, msg.length);
        assertEquals("ZPY", new String(msg, 0, 3, CHARSET));          // field 1 system code
        assertEquals("0200", new String(msg, 3, 4, CHARSET));         // field 2 message type
        assertEquals("420000", new String(msg, 7, 6, CHARSET));       // field 3 txn division
        assertEquals("000000001000", new String(msg, 300, 12, CHARSET)); // field 31 amount (N12, zero-padded)
    }

    @Test
    @DisplayName("encode → decode round-trips all key fields (codes keep leading zeros)")
    void roundTripsThroughDecode() {
        Map<Integer, String> m = JeonmunCodec.decode(
                MPM_DYNAMIC_420000, ZeroPayMpm420000.encodePayment(sample(false)), CHARSET, 1000);
        assertEquals("ZPY", m.get(F_SYSTEM_CODE));
        assertEquals("0200", m.get(F_MESSAGE_TYPE));     // leading zero preserved
        assertEquals("420000", m.get(F_TXN_DIVISION));
        assertEquals("PTXN1234567", m.get(F_TXN_UNIQUE_NO));
        assertEquals("M0000000001", m.get(F_MERCHANT_ID));   // trailing spaces stripped
        assertEquals("QR-SERIAL-0001", m.get(F_QR_SERIAL));
        assertEquals("2", m.get(F_QR_DIVISION));
        assertEquals(1000L, Long.parseLong(m.get(F_AMOUNT)));
        assertEquals(91L, Long.parseLong(m.get(F_VAT)));
    }

    @Test
    @DisplayName("overseas (해외페이) sets prepaid-combo O + self-prepaid = amount")
    void overseasSetsPrepaidComboFields() {
        Map<Integer, String> m = JeonmunCodec.decode(
                MPM_DYNAMIC_420000, ZeroPayMpm420000.encodePayment(sample(true)), CHARSET, 1000);
        assertEquals("O", m.get(F_PREPAID_COMBO_CODE));
        assertEquals(1000L, Long.parseLong(m.get(F_OPERATOR_PREPAID_AMOUNT)));
        assertEquals(0L, Long.parseLong(m.get(F_DIRECT_PREPAID_AMOUNT)));
    }

    @Test
    @DisplayName("numeric value overflowing its N field is rejected")
    void rejectsNumericOverflow() {
        ZeroPayMpm420000.PaymentRequest big = new ZeroPayMpm420000.PaymentRequest(
                "PTXN1", "001", 1_000_000_000_000L /* 13 digits > N12 */, 0L,
                "ZP", "S", "", "M", "", false, "1",
                LocalDate.of(2026, 6, 16), LocalDateTime.of(2026, 6, 16, 8, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ZeroPayMpm420000.encodePayment(big));
    }

    @Test
    @DisplayName("alphanumeric value overflowing its AN field is rejected")
    void rejectsAlphanumericOverflow() {
        ZeroPayMpm420000.PaymentRequest bad = new ZeroPayMpm420000.PaymentRequest(
                "PTXN1", "001", 1000L, 0L,
                "ZP", "S", "", "M01234567890123456789" /* 21 chars > AN20 */, "", false, "1",
                LocalDate.of(2026, 6, 16), LocalDateTime.of(2026, 6, 16, 8, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ZeroPayMpm420000.encodePayment(bad));
    }

    @Test
    @DisplayName("decodeResponse parses a 0210 response (approved heuristic + merchant fee)")
    void decodesResponse() {
        Map<Integer, String> v = new HashMap<>();
        v.put(F_SYSTEM_CODE, SYSTEM_CODE);
        v.put(F_MESSAGE_TYPE, MSG_PAYMENT_RESP);     // 0210
        v.put(F_TXN_DIVISION, TXN_DIV_MPM_DYNAMIC);
        v.put(F_TXN_UNIQUE_NO, "PTXN1234567");
        v.put(F_MERCHANT_FEE, "30");
        v.put(F_RESPONSE_CODE, "");                  // blank → approved heuristic
        byte[] resp = JeonmunCodec.encode(MPM_DYNAMIC_420000, v, CHARSET, ONLINE_MESSAGE_LENGTH);

        ZeroPayMpm420000.Response r = ZeroPayMpm420000.decodeResponse(resp);
        assertEquals("0210", r.messageType());
        assertEquals("PTXN1234567", r.txnUniqueNo());
        assertEquals(30L, r.merchantFeeKrw());
        assertTrue(r.approved());
    }
}
