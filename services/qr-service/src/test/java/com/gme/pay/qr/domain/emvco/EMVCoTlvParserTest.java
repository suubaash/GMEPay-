package com.gme.pay.qr.domain.emvco;

import com.gme.pay.qr.exception.QRMalformedException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 unit tests for {@link EMVCoTlvParser} (WBS 5.4-T13).
 * No Spring context, no external dependencies.
 */
class EMVCoTlvParserTest {

    // -----------------------------------------------------------------------
    // parseTopLevel
    // -----------------------------------------------------------------------

    @Test
    void parsesNormalTwoTagString() {
        // "000201" = tag 00, len 2, value "01"
        // "520441" = tag 52, len 4, value "4125" -- wait, len=4 so value is 4 chars
        // Build manually: tag=52, len=02, value=AB -> "52" + "02" + "AB" = "520" -- no
        // Format: TAG(2) + LENGTH(2) + VALUE(LENGTH)
        // "0002" + "01" = tag 00, len 2, value "01"  -> "000201"
        // "5202" + "AB" = tag 52, len 2, value "AB"  -> "520" -- "52" "02" "AB" = "520" -- "5202AB"
        String tlv = "000201" + "5202AB";
        Map<Integer, String> result = EMVCoTlvParser.parseTopLevel(tlv);
        assertEquals(2, result.size());
        assertEquals("01", result.get(0));
        assertEquals("AB", result.get(52));
    }

    @Test
    void throwsOnDeclaredLengthExceedingRemainingData() {
        // tag 52, declared length 99, but only "XY" (2 chars) remain
        String tlv = "529" + "9XY";  // "52" "99" "XY" -> length 99 but only 2 chars available
        // Actually must be exactly: "52" + "99" + "XY"
        String payload = "52" + "99" + "XY";
        QRMalformedException ex = assertThrows(QRMalformedException.class,
                () -> EMVCoTlvParser.parseTopLevel(payload));
        assertEquals(com.gme.pay.qr.exception.QRErrorCode.QR_MALFORMED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("exceeds remaining"));
    }

    @Test
    void throwsOnNullInput() {
        assertThrows(QRMalformedException.class, () -> EMVCoTlvParser.parseTopLevel(null));
    }

    @Test
    void throwsOnEmptyString() {
        assertThrows(QRMalformedException.class, () -> EMVCoTlvParser.parseTopLevel(""));
    }

    @Test
    void parsesNestedTemplateSubTags() {
        // MAI template: sub-tag 01, len 10, value "M123456789"; sub-tag 02, len 05, value "QR001"
        String subTag01 = "01" + "10" + "M123456789";   // 14 chars
        String subTag02 = "02" + "05" + "QR001";        // 9 chars
        String template = subTag01 + subTag02;

        Map<Integer, String> result = EMVCoTlvParser.parseTemplate(template);
        assertEquals("M123456789", result.get(1));
        assertEquals("QR001",      result.get(2));
    }

    @Test
    void throwsOnDuplicateTopLevelTag() {
        // Two entries for tag 52
        String tlv = "5202AB" + "5202CD";
        assertThrows(QRMalformedException.class, () -> EMVCoTlvParser.parseTopLevel(tlv));
    }

    @Test
    void allowsZeroLengthValue() {
        // tag 52 with length 0 is unusual but valid (empty value)
        // then tag 59 with value "Merchant"
        String tlv = "52" + "00" + "59" + "08" + "Merchant";
        Map<Integer, String> result = EMVCoTlvParser.parseTopLevel(tlv);
        assertEquals("",         result.get(52));
        assertEquals("Merchant", result.get(59));
    }

    // -----------------------------------------------------------------------
    // parseTemplate (additional)
    // -----------------------------------------------------------------------

    @Test
    void throwsOnBlankTemplate() {
        assertThrows(QRMalformedException.class, () -> EMVCoTlvParser.parseTemplate("   "));
    }
}
