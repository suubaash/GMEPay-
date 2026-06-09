package com.gme.pay.merchant.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * Spring Data MongoDB repository for {@link MerchantDocument}.
 *
 * <p>Phase-1 persistence for the merchant-qr-data service (WBS 9.3). Provides
 * lookup by both the QR code value and the business merchant identifier.
 */
public interface MerchantMongoRepository extends MongoRepository<MerchantDocument, String> {

    /**
     * Finds a merchant document by QR code value.
     *
     * @param qr the ZeroPay QR code identifier (CHAR(20))
     * @return optional document, empty when not found
     */
    Optional<MerchantDocument> findByQrCode(String qr);

    /**
     * Finds a merchant document by business merchant identifier.
     *
     * @param mid the ZeroPay merchant identifier (CHAR(10))
     * @return optional document, empty when not found
     */
    Optional<MerchantDocument> findByMerchantId(String mid);
}
