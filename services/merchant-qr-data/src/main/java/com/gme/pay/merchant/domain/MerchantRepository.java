package com.gme.pay.merchant.domain;

import java.util.Optional;

/**
 * Domain repository abstraction for merchant lookups by QR code identifier.
 *
 * <p>Implementations may back this with MongoDB, PostgreSQL, or an in-memory store.
 * The REST layer depends only on this interface, keeping the domain pure.
 */
public interface MerchantRepository {

    /**
     * Finds a {@link Merchant} by its QR code identifier (ZeroPay CHAR(20)).
     *
     * @param qrCodeId the QR code identifier to look up
     * @return an {@link Optional} containing the merchant if found, empty otherwise
     */
    Optional<Merchant> findByQrCodeId(String qrCodeId);

    /**
     * Inserts or replaces a merchant projection keyed by its {@code qrCodeId}.
     *
     * <p>Idempotent: re-applying the same merchant leaves a single record. Used by the
     * write-through path that mirrors sandbox terminal registrations into the lookup store
     * (so a scanned QR resolves at payment time) and by the file-ingest sync.
     *
     * @param merchant the domain merchant to persist
     * @return the persisted merchant
     */
    Merchant upsert(Merchant merchant);
}
