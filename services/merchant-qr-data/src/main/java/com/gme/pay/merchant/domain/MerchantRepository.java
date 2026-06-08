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
}
