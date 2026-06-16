package com.gme.sim.merchant;

import static com.gme.sim.merchant.jeonmun.zeropay.ZeroPayMessages.*;
import static org.junit.jupiter.api.Assertions.*;

import com.gme.sim.merchant.jeonmun.JeonmunCodec;
import com.gme.sim.merchant.jeonmun.zeropay.ZeroPayMessages;
import com.gme.sim.merchant.jeonmun.zeropay.ZeroPayMpm420000;
import com.gme.sim.merchant.jeonmun.zeropay.ZeroPayStatic500000;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Codec + ZeroPay layout tests (independent oracle, mirrors the production adapter's tests). */
class ZeroPayJeonmunTest {

    private static ZeroPayMpm420000.PaymentRequest payment(boolean overseas) {
        return new ZeroPayMpm420000.PaymentRequest(
                "PTXN1234567", "700", 11000L, 1000L,
                "01", "ZPQR00000001", "AB12", "ZP-M0001", "TERM01",
                overseas, "00000042",
                LocalDate.of(2026, 6, 16), LocalDateTime.of(2026, 6, 16, 8, 30, 15));
    }

    private static ZeroPayStatic500000.StaticResultRequest staticResult() {
        return new ZeroPayStatic500000.StaticResultRequest(
                "PTXN9999999", "700", 5000L, 454L,
                "01", "STATIC-ZP-M0001", "CD34", "ZP-M0001", "TERM01",
                "A00000000001", "00000099",
                LocalDate.of(2026, 6, 16), LocalDateTime.of(2026, 6, 16, 9, 0, 0));
    }

    // ----------------------------------------------------------------- 420000

    @Test
    @DisplayName("420000 + 500000 layouts are each exactly the 1,000-byte online body")
    void layoutsAre1000Bytes() {
        assertEquals(1000, JeonmunCodec.length(MPM_DYNAMIC_420000));
        assertEquals(1000, JeonmunCodec.length(MPM_STATIC_RESULT_500000));
        assertEquals(1000, ONLINE_MESSAGE_LENGTH);
    }

    @Test
    @DisplayName("420000 encodes a 1,000-byte frame with fields at the right byte offsets")
    void encodesFramedFieldsAtCorrectOffsets() {
        byte[] msg = ZeroPayMpm420000.encodePayment(payment(true));
        assertEquals(1000, msg.length);
        assertEquals("ZPY", new String(msg, 0, 3, CHARSET));            // field 1 system code
        assertEquals("0200", new String(msg, 3, 4, CHARSET));           // field 2 message type
        assertEquals("420000", new String(msg, 7, 6, CHARSET));         // field 3 txn division
        assertEquals("000000011000", new String(msg, 300, 12, CHARSET)); // field 31 amount (N12)
    }

    @Test
    @DisplayName("420000 encode → decode round-trips key fields (codes keep leading zeros)")
    void roundTrips420000() {
        Map<Integer, String> m = JeonmunCodec.decode(
                MPM_DYNAMIC_420000, ZeroPayMpm420000.encodePayment(payment(false)), CHARSET, 1000);
        assertEquals("ZPY", m.get(F_SYSTEM_CODE));
        assertEquals("0200", m.get(F_MESSAGE_TYPE));
        assertEquals("420000", m.get(F_TXN_DIVISION));
        assertEquals("PTXN1234567", m.get(F_TXN_UNIQUE_NO));
        assertEquals("ZP-M0001", m.get(F_MERCHANT_ID));     // trailing spaces stripped
        assertEquals("ZPQR00000001", m.get(F_QR_SERIAL));
        assertEquals("2", m.get(F_QR_DIVISION));
        assertEquals(11000L, Long.parseLong(m.get(F_AMOUNT)));
        assertEquals(1000L, Long.parseLong(m.get(F_VAT)));
    }

    @Test
    @DisplayName("overseas (해외페이) sets prepaid-combo O + self-prepaid = amount")
    void overseasSetsPrepaidComboFields() {
        Map<Integer, String> m = JeonmunCodec.decode(
                MPM_DYNAMIC_420000, ZeroPayMpm420000.encodePayment(payment(true)), CHARSET, 1000);
        assertEquals("O", m.get(F_PREPAID_COMBO_CODE));
        assertEquals(11000L, Long.parseLong(m.get(F_OPERATOR_PREPAID_AMOUNT)));
        assertEquals(0L, Long.parseLong(m.get(F_DIRECT_PREPAID_AMOUNT)));
    }

    @Test
    @DisplayName("annotatePayment exposes only populated fields with byte offsets")
    void annotateExposesPopulatedFields() {
        var fields = ZeroPayMpm420000.annotatePayment(payment(true));
        var amount = fields.stream().filter(f -> f.no() == F_AMOUNT).findFirst().orElseThrow();
        assertEquals(300, amount.offset());
        assertEquals(12, amount.length());
        assertEquals("11000", amount.value());
        // 봉사료 (service charge) is never set → must be absent from the annotated view
        assertTrue(fields.stream().noneMatch(f -> f.no() == F_SERVICE_CHARGE));
    }

    @Test
    @DisplayName("numeric value overflowing its N field is rejected")
    void rejectsNumericOverflow() {
        ZeroPayMpm420000.PaymentRequest big = new ZeroPayMpm420000.PaymentRequest(
                "PTXN1", "700", 1_000_000_000_000L /* 13 digits > N12 */, 0L,
                "01", "S", "", "M", "", false, "1",
                LocalDate.of(2026, 6, 16), LocalDateTime.of(2026, 6, 16, 8, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ZeroPayMpm420000.encodePayment(big));
    }

    @Test
    @DisplayName("alphanumeric value overflowing its AN field is rejected")
    void rejectsAlphanumericOverflow() {
        ZeroPayMpm420000.PaymentRequest bad = new ZeroPayMpm420000.PaymentRequest(
                "PTXN1", "700", 1000L, 0L,
                "01", "S", "", "M01234567890123456789" /* 21 chars > AN20 */, "", false, "1",
                LocalDate.of(2026, 6, 16), LocalDateTime.of(2026, 6, 16, 8, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ZeroPayMpm420000.encodePayment(bad));
    }

    // ----------------------------------------------------------------- 500000

    @Test
    @DisplayName("500000 encodes a 1,000-byte frame for the static-result registration")
    void encodes500000() {
        byte[] msg = ZeroPayStatic500000.encodeRegistration(staticResult());
        assertEquals(1000, msg.length);
        assertEquals("ZPY", new String(msg, 0, 3, CHARSET));
        assertEquals("500000", new String(msg, 7, 6, CHARSET));   // field 3 txn division
    }

    @Test
    @DisplayName("500000 round-trips static-QR fields (QR구분=1, approval no)")
    void roundTrips500000() {
        Map<Integer, String> m = JeonmunCodec.decode(
                MPM_STATIC_RESULT_500000, ZeroPayStatic500000.encodeRegistration(staticResult()), CHARSET, 1000);
        assertEquals("500000", m.get(F_TXN_DIVISION));
        assertEquals("1", m.get(F_QR_DIVISION));
        assertEquals("STATIC-ZP-M0001", m.get(F_QR_SERIAL));
        assertEquals("A00000000001", m.get(F_APPROVAL_NO));
        assertEquals(5000L, Long.parseLong(m.get(F_AMOUNT)));
    }

    @Test
    @DisplayName("a blank-response 0210 decodes as registered")
    void decodes500000Response() {
        // Build a synthetic 0210 with blank response/result codes → registered heuristic.
        Map<Integer, String> v = ZeroPayStatic500000.requestValues(staticResult());
        v.put(F_MESSAGE_TYPE, ZeroPayMessages.MSG_PAYMENT_RESP);
        byte[] resp = JeonmunCodec.encode(MPM_STATIC_RESULT_500000, v, CHARSET, 1000);
        ZeroPayStatic500000.Response r = ZeroPayStatic500000.decodeResponse(resp);
        assertEquals("0210", r.messageType());
        assertEquals("PTXN9999999", r.txnUniqueNo());
        assertTrue(r.registered());
    }
}
