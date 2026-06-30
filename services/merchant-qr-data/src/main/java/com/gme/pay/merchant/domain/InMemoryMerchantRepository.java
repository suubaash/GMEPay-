package com.gme.pay.merchant.domain;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link MerchantRepository} implementation.
 *
 * <p>Used as a stand-in until the MongoDB/PostgreSQL persistence layer (9.3-T08 onward) is wired.
 * Pre-loaded with a small set of seed merchants for local development and unit tests.
 * Thread-safe via {@link ConcurrentHashMap}.
 *
 * <p>Demo seeds match the sim-scheme ZeroPay sandbox merchant identifiers so the running
 * sandbox can validate end-to-end without real SFTP credentials (UC-07-03).
 */
@Repository
public class InMemoryMerchantRepository implements MerchantRepository, ReconcilableMerchantRepository {

    private final Map<String, Merchant> store = new ConcurrentHashMap<>();

    /** Constructs the repository pre-loaded with demo merchants for sandbox testing. */
    public InMemoryMerchantRepository() {
        // Seoul Mart — active RETAIL merchant (matches sim-scheme demo merchant #1)
        seed(new Merchant("M0000000001", "QR00000000000000001A", "Seoul Mart",
                "RETAIL", "DOMESTIC", "ACTIVE", true,
                "KRW", "ZEROPAY", "Seoul", "5411"));

        // Busan Cafe — active FOOD_BEVERAGE merchant (matches sim-scheme demo merchant #2)
        seed(new Merchant("M0000000002", "QR00000000000000002B", "Busan Cafe",
                "FOOD_BEVERAGE", "DOMESTIC", "ACTIVE", true,
                "KRW", "ZEROPAY", "Busan", "5812"));

        // Incheon FX Shop — SUSPENDED (not operational, active=false)
        seed(new Merchant("M0000000003", "QR00000000000000003C", "Incheon FX Shop",
                "FOREX", "CROSSBORDER", "SUSPENDED", false,
                "USD", "ZEROPAY", "Incheon", "6211"));

        // Deactivated convenience store — QR deactivated scenario for validation tests
        seed(new Merchant("M0000000004", "QR00000000000000004D", "Closed Corner Store",
                "RETAIL", "DOMESTIC", "DEACTIVATED", false,
                "KRW", "ZEROPAY", "Seoul", "5411"));

        // Sim-scheme-aligned fixture (ZP-M001): keyed by an actual ZeroPay EMVCo static QR payload so
        // the full MPM path validates end-to-end — the same scanned QR resolves here AND decodes at
        // sim-scheme's /qr/decode. The trailing CRC (tag 63) keeps this payload permanently valid.
        seed(new Merchant("M0000000001",
                "00020101021129260011com.zeropay0107ZP-M0015204581253034105802KR5918Seoul Noodle House6005Seoul63040B08",
                "Seoul Noodle House", "RETAIL", "DOMESTIC", "ACTIVE", true,
                "KRW", "ZEROPAY", "Seoul", "5812"));

        // End-to-end demo fixture (SMOKE_MERCH_01): keyed by the SAME EMVCo QR the qr-pay demo scans and
        // sim-scheme registers/extracts (merchant id in sub-tag 01), so ONE scanned QR resolves here AND
        // is approved by sim-scheme — the wallet → txn → scheme-confirmed → view flow runs without
        // synthesising an UNKNOWN merchant. Mirrors .smoke/05_qr_payload.txt + 04_merchant_register.json.
        seed(new Merchant("SMOKE_MERCH_01",
                "00020101021229330011com.zeropay0114SMOKE_MERCH_015204599953034105405100005802KR5914Smoke Merchant6005Seoul6304E765",
                "Smoke Merchant", "RETAIL", "DOMESTIC", "ACTIVE", true,
                "KRW", "ZEROPAY", "Seoul", "5999"));
    }

    private void seed(Merchant merchant) {
        store.put(merchant.qrCodeId(), merchant);
    }

    @Override
    public Optional<Merchant> findByQrCodeId(String qrCodeId) {
        if (qrCodeId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(qrCodeId));
    }

    @Override
    public List<Merchant> findAll() {
        return List.copyOf(store.values());
    }

    /** Adds or replaces a merchant entry — used by tests and seed loaders. */
    public void put(Merchant merchant) {
        store.put(merchant.qrCodeId(), merchant);
    }

    /** Removes a merchant entry — used by tests. */
    public void remove(String qrCodeId) {
        store.remove(qrCodeId);
    }
}
