package com.gme.sim.sendmn;

import com.gme.sim.sendmn.model.Merchant;
import com.gme.sim.sendmn.model.MerchantRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the seeded registry + EMVCo QR builder (counterpart of QrParserTest). */
class MerchantRegistryTest {

    private final MerchantRegistry registry = new MerchantRegistry();

    @Test
    void seedsMongolianMerchantsWithQpayGuidQr() {
        assertThat(registry.all()).hasSizeGreaterThanOrEqualTo(3);
        for (Merchant m : registry.all()) {
            assertThat(m.qrCode())
                    .startsWith("000201")                    // payload format indicator
                    .contains("010211")                      // id 01 len 02 value 11 = static QR
                    .contains("A000000843000101")            // QPay GUID
                    .contains("5303496")                     // currency MNT
                    .contains("5802MN")                      // country MN
                    .contains("ULAANBAATAR");
            // valid EMVCo CRC-16/CCITT-FALSE over everything incl the trailing "6304"
            String qr = m.qrCode();
            String data = qr.substring(0, qr.length() - 4);
            assertThat(qr).endsWith(MerchantRegistry.crc16CcittFalse(data));
        }
    }

    @Test
    void lookupByQrAndByMerchantIdAgree() {
        for (Merchant m : registry.all()) {
            assertThat(registry.byQr(m.qrCode())).contains(m);
            assertThat(registry.byMerchantId(m.merchantId())).contains(m);
        }
        assertThat(registry.byQr("garbage")).isEmpty();
        assertThat(registry.byMerchantId("nope")).isEmpty();
    }

    @Test
    void crc16MatchesKnownVector() {
        // CRC-16/CCITT-FALSE("123456789") = 0x29B1 (standard check value)
        assertThat(MerchantRegistry.crc16CcittFalse("123456789")).isEqualTo("29B1");
    }
}
