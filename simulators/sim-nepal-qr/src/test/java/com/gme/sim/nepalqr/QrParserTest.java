package com.gme.sim.nepalqr;

import com.gme.sim.nepalqr.qr.QrParseResult;
import com.gme.sim.nepalqr.qr.QrParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QrParserTest {

    // Sample Fonepay QR from issuance-extension.txt
    private static final String FONEPAY_QR =
            "00020101021126350011fonepay.com071640897200000017835204541253035245802NP"
          + "5914SudanMerchant6015AathraiTriveni62060702316304d60f";

    @Test
    void parsesFonepayMerchantFields() {
        QrParseResult p = QrParser.parse(FONEPAY_QR);
        assertEquals("fonepay", p.network);
        assertEquals("SudanMerchant", p.merchantName);
        assertEquals("AathraiTriveni", p.merchantCity);
        assertEquals("NP", p.merchantCountry);
        assertEquals("5412", p.merchantCategoryCode);
        assertEquals("NPR", p.trxCurrency);
        // No tag 54 in this static-style sample -> null amount
        assertNull(p.amountPaisa);
    }

    @Test
    void looksValidRejectsShortAndUnknown() {
        assertFalse(QrParser.looksValid(null));
        assertFalse(QrParser.looksValid("short"));
        assertTrue(QrParser.looksValid(FONEPAY_QR));
        assertTrue(QrParser.looksValid("{\"network\":\"khalti\"}"));
    }

    @Test
    void amountRupeesConvertsPaisa() {
        QrParseResult p = new QrParseResult();
        p.amountPaisa = 130000L;
        assertEquals("1300", p.amountRupees());
        p.amountPaisa = 130050L;
        assertEquals("1300.50", p.amountRupees());
        p.amountPaisa = null;
        assertNull(p.amountRupees());
    }
}
