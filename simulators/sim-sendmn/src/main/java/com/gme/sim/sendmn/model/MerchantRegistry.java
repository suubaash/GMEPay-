package com.gme.sim.sendmn.model;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory registry of seeded Mongolian merchants and their static EMVCo MPM QR
 * payloads (GUID {@code A000000843000101} = QPay, country MN, currency 496/MNT,
 * valid CRC-16/CCITT-FALSE checksum). VerifyQr resolves a scanned payload by exact
 * match, exactly as a switch keyed on the registered QR would.
 */
@Component
public class MerchantRegistry {

    private static final String QPAY_GUID = "A000000843000101";

    private final Map<String, Merchant> byQr = new LinkedHashMap<>();
    private final Map<String, Merchant> byMerchantId = new LinkedHashMap<>();

    public MerchantRegistry() {
        seed("0b2f4c66-8a1d-4e3b-9c57-d21f83a90e14", "1453767113", "NOMIN SUPERMARKET",
                "Peace Avenue 15, Ulaanbaatar", "T-0001", null);
        seed("6d9c1a02-53be-47f8-8e14-7ab2c05d33f9", "1453767114", "THE BULL HOT POT",
                "Seoul Street 21, Ulaanbaatar", null, null);
        seed("e3a8b7d4-1c65-4f20-b39a-58c4d06e72a1", "1453767115", "UB CASHMERE STORE",
                "Chinggis Avenue 9, Ulaanbaatar", "T-0009", new BigDecimal("25000.00"));
    }

    private void seed(String merchantId, String numericId, String name, String address,
                      String terminalId, BigDecimal fixedAmount) {
        String qr = buildStaticQr(numericId, name, fixedAmount);
        Merchant m = new Merchant(merchantId, numericId, name, address, terminalId, fixedAmount, qr);
        byQr.put(qr, m);
        byMerchantId.put(merchantId, m);
    }

    public List<Merchant> all() {
        return List.copyOf(byMerchantId.values());
    }

    public Optional<Merchant> byQr(String qrCode) {
        return Optional.ofNullable(byQr.get(qrCode));
    }

    public Optional<Merchant> byMerchantId(String merchantId) {
        return Optional.ofNullable(byMerchantId.get(merchantId));
    }

    // ------------------------------------------------------------------ QR builder

    /** EMVCo MPM payload: static (01=11), QPay GUID template 26, MN / ULAANBAATAR / 496. */
    static String buildStaticQr(String numericMerchantId, String name, BigDecimal amount) {
        StringBuilder sb = new StringBuilder();
        tlv(sb, "00", "01");                                     // payload format indicator
        tlv(sb, "01", "11");                                     // static QR (doc: QR_TYPE 11)
        StringBuilder mai = new StringBuilder();
        tlv(mai, "00", QPAY_GUID);                               // globally unique identifier
        tlv(mai, "01", numericMerchantId);                       // merchant id under the GUID
        tlv(sb, "26", mai.toString());                           // merchant account information
        tlv(sb, "52", "5411");                                   // MCC
        tlv(sb, "53", "496");                                    // MNT
        if (amount != null) {
            tlv(sb, "54", amount.toPlainString());
        }
        tlv(sb, "58", "MN");
        tlv(sb, "59", name.length() > 25 ? name.substring(0, 25) : name);
        tlv(sb, "60", "ULAANBAATAR");
        sb.append("6304");                                       // CRC id+len, CRC over all incl "6304"
        sb.append(crc16CcittFalse(sb.toString()));
        return sb.toString();
    }

    private static void tlv(StringBuilder sb, String id, String value) {
        sb.append(id).append(String.format("%02d", value.length())).append(value);
    }

    /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF) as 4 uppercase hex chars (EMVCo 63). */
    public static String crc16CcittFalse(String data) {
        int crc = 0xFFFF;
        for (byte b : data.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return String.format("%04X", crc);
    }
}
