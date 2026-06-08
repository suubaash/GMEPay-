package com.gme.pay.merchant.domain;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link MerchantRepository} implementation.
 *
 * <p>Used as a stand-in until the MongoDB/PostgreSQL persistence layer (9.3-T08 onward) is wired.
 * Pre-loaded with a small set of seed merchants for local development and unit tests.
 * Thread-safe via {@link ConcurrentHashMap}.
 */
@Repository
public class InMemoryMerchantRepository implements MerchantRepository {

    private final Map<String, Merchant> store = new ConcurrentHashMap<>();

    /** Constructs the repository pre-loaded with sample merchants. */
    public InMemoryMerchantRepository() {
        seed(new Merchant("M0000000001", "QR00000000000000001A", "Seoul Mart",
                "RETAIL", "DOMESTIC", "ACTIVE", true));
        seed(new Merchant("M0000000002", "QR00000000000000002B", "Busan Cafe",
                "FOOD_BEVERAGE", "DOMESTIC", "ACTIVE", true));
        seed(new Merchant("M0000000003", "QR00000000000000003C", "Incheon FX Shop",
                "FOREX", "CROSSBORDER", "SUSPENDED", false));
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

    /** Adds or replaces a merchant entry — used by tests and seed loaders. */
    public void put(Merchant merchant) {
        store.put(merchant.qrCodeId(), merchant);
    }

    /** Removes a merchant entry — used by tests. */
    public void remove(String qrCodeId) {
        store.remove(qrCodeId);
    }
}
