package com.gme.pay.merchant.domain;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * Domain service for the merchant write-through path: registers (upserts) a merchant keyed by
 * its QR code identifier so that a scanned QR resolves at payment time.
 *
 * <p>Complements {@link MerchantLookupService} (the read side). Used by the sandbox flow where
 * a terminal registration is mirrored into the lookup store.
 */
@Service
public class MerchantRegistrationService {

    private final MerchantRepository merchantRepository;

    public MerchantRegistrationService(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    /**
     * Inserts or replaces the given merchant.
     *
     * @param merchant the merchant to persist (its {@code qrCodeId} is the key)
     * @return the persisted merchant
     * @throws ApiException {@link ErrorCode#VALIDATION_ERROR} if {@code qrCodeId} is missing
     */
    public Merchant register(Merchant merchant) {
        if (merchant.qrCodeId() == null || merchant.qrCodeId().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "qrCodeId is required");
        }
        return merchantRepository.upsert(merchant);
    }
}
