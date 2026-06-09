package com.gme.pay.merchant.persistence;

import com.gme.pay.merchant.domain.Merchant;
import com.gme.pay.merchant.domain.MerchantRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * MongoDB-backed adapter implementing the domain {@link MerchantRepository} port.
 *
 * <p>Phase-1 persistence (WBS 9.3-T08). Delegates lookup to
 * {@link MerchantMongoRepository} and bridges the storage-layer
 * {@link MerchantDocument} into the domain {@link Merchant} record consumed by
 * the REST layer.
 *
 * <p>Marked {@link Primary} so it is selected ahead of the in-memory
 * implementation when a Mongo connection is available; the in-memory
 * implementation remains as a fall-back for tests that exclude the Mongo
 * auto-configuration.
 *
 * <p>Field mapping (document &rarr; domain):
 * <ul>
 *   <li>{@code qrCode} &rarr; {@code qrCodeId}</li>
 *   <li>{@code country} &rarr; {@code merchantType} (placeholder until richer
 *       projection lands; the domain record's {@code merchantType}/{@code feeType}
 *       slots are populated from available document fields)</li>
 *   <li>{@code settleCurrency} &rarr; {@code feeType}</li>
 *   <li>{@code active} &rarr; {@code active} and derives {@code status}
 *       ({@code ACTIVE} when true, {@code DEACTIVATED} otherwise)</li>
 * </ul>
 */
@Component
@Primary
@ConditionalOnBean(MerchantMongoRepository.class)
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

    /** Maps a persistence {@link MerchantDocument} to a domain {@link Merchant}. */
    static Merchant toDomain(MerchantDocument doc) {
        return new Merchant(
                doc.getMerchantId(),
                doc.getQrCode(),
                doc.getName(),
                doc.getCountry(),
                doc.getSettleCurrency(),
                doc.isActive() ? "ACTIVE" : "DEACTIVATED",
                doc.isActive());
    }
}
