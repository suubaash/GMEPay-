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

    /**
     * Full field-by-field assertion against the exact real Fonepay wallet QR,
     * covering EVERY decoded field: network, initMethod, merchantId (MAI sub-tag
     * 07), MCC, currency, country, name+city (off-by-one declared lengths) and a
     * static (null) amount. Ground-truth hand-parsed from the EMVCo TLV.
     */
    @Test
    void decodesExactRealFonepayQr() {
        QrParseResult p = QrParser.parse(FONEPAY_QR);
        assertEquals("fonepay", p.network, "network");
        assertEquals("static", p.initMethod, "initMethod (POI tag 01 = 11)");
        assertEquals("4089720000001783", p.merchantId, "merchantId (template 26 sub-tag 07)");
        assertEquals("fonepay.com", p.merchantInfoExtra, "GUID (template 26 sub-tag 00)");
        assertEquals("5412", p.merchantCategoryCode, "MCC (tag 52)");
        assertEquals("NPR", p.trxCurrency, "currency (tag 53 = 524)");
        assertEquals("NP", p.merchantCountry, "country (tag 58)");
        assertEquals("SudanMerchant", p.merchantName, "name (tag 59, off-by-one long)");
        assertEquals("AathraiTriveni", p.merchantCity, "city (tag 60, off-by-one long)");
        assertNull(p.amountPaisa, "no tag 54 -> static, null amount");
        assertNull(p.amountRupees(), "static amount surfaces as null rupees");
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
