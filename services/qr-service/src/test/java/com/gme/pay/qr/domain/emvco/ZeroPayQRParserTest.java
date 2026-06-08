package com.gme.pay.qr.domain.emvco;

import com.gme.pay.qr.domain.emvco.helper.TlvBuilder;
import com.gme.pay.qr.exception.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 unit tests for {@link ZeroPayQRParser} (WBS 5.4-T15).
 * Direct instantiation — no Spring context required.
 */
class ZeroPayQRParserTest {

    private ZeroPayQRParser parser;

    @BeforeEach
    void setUp() {
        parser = new ZeroPayQRParser(26); // default MAI tag
    }

    // -----------------------------------------------------------------------
    // Helper: build a standard happy-path payload
    // -----------------------------------------------------------------------

    private static String buildValidPayload(String merchantId, String qrCodeId) {
        return new TlvBuilder()
                .addTag(0, "01")          // format indicator
                .addTag(52, "5411")       // MCC
                .addTag(53, "410")        // currency = KRW
                .addTag(58, "KR")         // country
                .addTag(59, "TestMerchant")
                .addTag(60, "Seoul")
                .addMai(26, merchantId, qrCodeId)
                .build();
    }

    // -----------------------------------------------------------------------
    // Test 1 — happy path
    // -----------------------------------------------------------------------

    @Test
    void happyPathParsesAllMandatoryFields() {
        String payload = buildValidPayload("M123456789", "QR00000000000000000001");
        ParsedQRPayload result = parser.parse(payload);

        assertTrue(result.crcVerified());
        assertEquals(1,                          result.formatIndicator());
        assertEquals("5411",                     result.mcc());
        assertEquals("410",                      result.currencyCode());
        assertEquals("KR",                       result.countryCode());
        assertEquals("TestMerchant",             result.merchantName());
        assertEquals("Seoul",                    result.merchantCity());
        assertEquals(26,                         result.maiTag());
        assertEquals("M123456789",               result.merchantId());
        assertEquals("QR00000000000000000001",   result.qrCodeId());
        assertNull(result.encodedAmount());
    }

    // -----------------------------------------------------------------------
    // Test 2 — wrong currency
    // -----------------------------------------------------------------------

    @Test
    void throwsCurrencyMismatchForNonKrwCurrency() {
        String payload = new TlvBuilder()
                .addTag(0, "01")
                .addTag(52, "5411")
                .addTag(53, "840")   // USD, not KRW
                .addTag(58, "US")
                .addTag(59, "Merchant")
                .addTag(60, "City")
                .addMai(26, "M001", "QR001")
                .build();

        QRCurrencyMismatchException ex = assertThrows(QRCurrencyMismatchException.class,
                () -> parser.parse(payload));
        assertEquals(QRErrorCode.QR_CURRENCY_MISMATCH, ex.getErrorCode());
    }

    // -----------------------------------------------------------------------
    // Test 3 — wrong format indicator
    // -----------------------------------------------------------------------

    @Test
    void throwsMalformedForWrongFormatIndicator() {
        String payload = new TlvBuilder()
                .addTag(0, "02")   // must be "01"
                .addTag(52, "5411")
                .addTag(53, "410")
                .addTag(58, "KR")
                .addTag(59, "Merchant")
                .addTag(60, "City")
                .addMai(26, "M001", "QR001")
                .build();

        QRMalformedException ex = assertThrows(QRMalformedException.class,
                () -> parser.parse(payload));
        assertEquals(QRErrorCode.QR_MALFORMED, ex.getErrorCode());
    }

    // -----------------------------------------------------------------------
    // Test 4 — missing mandatory tag 52 (MCC)
    // -----------------------------------------------------------------------

    @Test
    void throwsMalformedWhenMccTagMissing() {
        // Omit tag 52
        String payload = new TlvBuilder()
                .addTag(0, "01")
                // tag 52 intentionally omitted
                .addTag(53, "410")
                .addTag(58, "KR")
                .addTag(59, "Merchant")
                .addTag(60, "City")
                .addMai(26, "M001", "QR001")
                .build();

        QRMalformedException ex = assertThrows(QRMalformedException.class,
                () -> parser.parse(payload));
        assertEquals(QRErrorCode.QR_MALFORMED, ex.getErrorCode());
    }

    // -----------------------------------------------------------------------
    // Test 5 — no MAI slot
    // -----------------------------------------------------------------------

    @Test
    void throwsUnknownSchemeWhenMaiAbsent() {
        // No addMai() call — no tags in 26-51 range
        String payload = new TlvBuilder()
                .addTag(0, "01")
                .addTag(52, "5411")
                .addTag(53, "410")
                .addTag(58, "KR")
                .addTag(59, "Merchant")
                .addTag(60, "City")
                .build();

        QRUnknownSchemeException ex = assertThrows(QRUnknownSchemeException.class,
                () -> parser.parse(payload));
        assertEquals(QRErrorCode.QR_UNKNOWN_SCHEME, ex.getErrorCode());
    }

    // -----------------------------------------------------------------------
    // Test 6 — MAI present but missing sub-tag 02 (qr_code_id)
    // -----------------------------------------------------------------------

    @Test
    void throwsMalformedWhenMaiMissingQrCodeIdSubTag() {
        // Build MAI template with only sub-tag 01
        String maiTemplate = "01" + "03" + "M01";   // sub-tag 01, len=3, value="M01"
        String payload = new TlvBuilder()
                .addTag(0, "01")
                .addTag(52, "5411")
                .addTag(53, "410")
                .addTag(58, "KR")
                .addTag(59, "Merchant")
                .addTag(60, "City")
                .addTag(26, maiTemplate)   // MAI without sub-tag 02
                .build();

        QRMalformedException ex = assertThrows(QRMalformedException.class,
                () -> parser.parse(payload));
        assertEquals(QRErrorCode.QR_MALFORMED, ex.getErrorCode());
    }

    // -----------------------------------------------------------------------
    // Test 7 — bad CRC: exception raised before tag validation
    // -----------------------------------------------------------------------

    @Test
    void throwsInvalidChecksumBeforeTagValidation() {
        String validPayload = buildValidPayload("M123", "QR001");
        // Corrupt the last 4 chars (the CRC value)
        String tampered = validPayload.substring(0, validPayload.length() - 4) + "0000";

        QRInvalidChecksumException ex = assertThrows(QRInvalidChecksumException.class,
                () -> parser.parse(tampered));
        assertEquals(QRErrorCode.QR_INVALID_CHECKSUM, ex.getErrorCode());
    }

    // -----------------------------------------------------------------------
    // Test 8 — optional encoded amount (tag 54)
    // -----------------------------------------------------------------------

    @Test
    void parsesOptionalEncodedAmountWhenPresent() {
        String payload = new TlvBuilder()
                .addTag(0, "01")
                .addTag(52, "5411")
                .addTag(53, "410")
                .addTag(54, "50000")   // optional amount
                .addTag(58, "KR")
                .addTag(59, "Merchant")
                .addTag(60, "City")
                .addMai(26, "M999", "QR999")
                .build();

        ParsedQRPayload result = parser.parse(payload);
        assertNotNull(result.encodedAmount());
        assertEquals(new BigDecimal("50000"), result.encodedAmount());
    }

    // -----------------------------------------------------------------------
    // Test 9 — non-default MAI tag
    // -----------------------------------------------------------------------

    @Test
    void usesConfiguredMaiTagInsteadOfDefault() {
        ZeroPayQRParser parserTag29 = new ZeroPayQRParser(29);

        String payload = new TlvBuilder()
                .addTag(0, "01")
                .addTag(52, "5411")
                .addTag(53, "410")
                .addTag(58, "KR")
                .addTag(59, "Merchant")
                .addTag(60, "City")
                .addMai(29, "M_CONFIGURED", "QR_CONFIGURED")  // tag 29 instead of 26
                .build();

        ParsedQRPayload result = parserTag29.parse(payload);
        assertEquals("M_CONFIGURED",  result.merchantId());
        assertEquals("QR_CONFIGURED", result.qrCodeId());
    }
}
