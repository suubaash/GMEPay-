package com.gme.pay.merchant.domain;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * Domain service for merchant lookup by QR code identifier.
 *
 * <p>Throws {@link ApiException} with {@link ErrorCode#MERCHANT_NOT_FOUND} when the
 * requested QR code has no associated merchant record.
 */
@Service
public class MerchantLookupService {

    private final MerchantRepository merchantRepository;

    public MerchantLookupService(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    /**
     * Looks up the merchant associated with the given QR code identifier.
     *
     * @param qrCodeId the QR code identifier (ZeroPay CHAR(20))
     * @return the {@link Merchant} record
     * @throws ApiException with {@link ErrorCode#MERCHANT_NOT_FOUND} if not found
     */
    public Merchant getByQrCodeId(String qrCodeId) {
        return merchantRepository.findByQrCodeId(qrCodeId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.MERCHANT_NOT_FOUND,
                        "No merchant found for QR code: " + qrCodeId));
    }
}
