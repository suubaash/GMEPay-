package com.gme.pay.merchant.persistence;

import com.gme.pay.merchant.domain.Merchant;
import com.gme.pay.merchant.domain.MerchantRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * MongoDB-backed adapter implementing the domain {@link MerchantRepository} port
 * (17.7-G01, ADR-003: MongoDB for the merchant/QR mirror).
 *
 * <p>Delegates lookup to {@link MerchantMongoRepository} and bridges the
 * storage-layer {@link MerchantDocument} into the domain {@link Merchant}
 * record consumed by the REST layer.
 *
 * <p>Activation: gated on {@code spring.data.mongodb.uri} (same condition as
 * {@link MongoPersistenceConfig}, which wires the underlying Mongo stack) and
 * marked {@link Primary} so it is selected ahead of the in-memory
 * implementation. When the property is absent this bean does not exist and the
 * in-memory store remains the default — unit tests and local dev need no Mongo.
 */
@Component
@Primary
@ConditionalOnProperty(name = "spring.data.mongodb.uri")
public class MongoBackedMerchantRepository implements MerchantRepository {

    private final MerchantMongoRepository mongoRepository;

    public MongoBackedMerchantRepository(MerchantMongoRepository mongoRepository) {
        this.mongoRepository = mongoRepository;
    }

    @Override
    public Optional<Merchant> findByQrCodeId(String qrCodeId) {
        if (qrCodeId == null) {
            return Optional.empty();
        }
        return mongoRepository.findByQrCode(qrCodeId).map(MongoBackedMerchantRepository::toDomain);
    }

    /**
     * Convenience lookup by business merchant identifier. Not part of the
     * {@link MerchantRepository} port (yet) but exposed for callers that already
     * hold a merchant id rather than a QR code.
     *
     * @param merchantId the ZeroPay merchant id (CHAR(10))
     * @return optional domain merchant
     */
    public Optional<Merchant> findByMerchantId(String merchantId) {
        if (merchantId == null) {
            return Optional.empty();
        }
        return mongoRepository.findByMerchantId(merchantId).map(MongoBackedMerchantRepository::toDomain);
    }

    /**
     * Inserts or replaces the merchant projection keyed by its QR code.
     *
     * <p>The QR code is the document's natural {@code _id}, so a Spring Data
     * {@code save} is an idempotent upsert — re-applying the same merchant
     * leaves a single document. Used by the merchant sync path (17.7-G02) and
     * integration tests; not part of the read-only lookup port.
     *
     * @param merchant the domain merchant to persist
     * @return the persisted merchant mapped back to the domain model
     */
    @Override
    public Merchant upsert(Merchant merchant) {
        MerchantDocument saved = mongoRepository.save(toDocument(merchant));
        return toDomain(saved);
    }

    /** Maps a persistence {@link MerchantDocument} to a domain {@link Merchant}. */
    static Merchant toDomain(MerchantDocument doc) {
        return new Merchant(
                doc.getMerchantId(),
                doc.getQrCode(),
                doc.getName(),
                doc.getMerchantType(),
                doc.getFeeType(),
                doc.getStatus(),
                doc.isActive(),
                doc.getPayoutCurrency(),
                doc.getSchemeId(),
                doc.getCity(),
                doc.getMcc());
    }

    /** Maps a domain {@link Merchant} to its persistence document (id = QR code). */
    static MerchantDocument toDocument(Merchant merchant) {
        return new MerchantDocument(
                merchant.qrCodeId(),
                merchant.merchantId(),
                merchant.qrCodeId(),
                merchant.name(),
                merchant.merchantType(),
                merchant.feeType(),
                merchant.status(),
                merchant.active(),
                merchant.payoutCurrency(),
                merchant.schemeId(),
                merchant.city(),
                merchant.mcc());
    }
}
