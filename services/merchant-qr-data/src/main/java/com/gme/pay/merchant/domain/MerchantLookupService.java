package com.gme.pay.merchant.domain;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Domain service for merchant lookup by QR code identifier.
 *
 * <p>Throws {@link ApiException} with {@link ErrorCode#MERCHANT_NOT_FOUND} when the
 * requested QR code has no associated merchant record.
 *
 * <p><strong>Strict mode (lenient-bypass removal):</strong> historically this service
 * returned <em>any</em> matched merchant — including SUSPENDED / DEACTIVATED records —
 * with HTTP 200, leaving the payment path to decide whether the merchant was usable
 * (the "lenient fake-merchant bypass"). When
 * {@code gmepay.merchant.strict-mode=true} (see {@code application.yml}) a non-operational
 * merchant (status != ACTIVE or {@code active=false}) is rejected at lookup time with
 * {@link ErrorCode#MERCHANT_NOT_FOUND} and a reason describing why, so the QR-pay flow
 * cannot proceed against an inactive merchant. Default ({@code false}) preserves the
 * legacy lenient behaviour for the existing golden path; flip the flag on to enforce.
 *
 * <p>NOTE: lib-errors (frozen) has no dedicated {@code MERCHANT_SUSPENDED} /
 * {@code MERCHANT_DEACTIVATED} codes, so strict rejections reuse
 * {@link ErrorCode#MERCHANT_NOT_FOUND} (404) with the specific reason in the message.
 * See INTEGRATION REQUEST #1 in the build report.
 */
@Service
public class MerchantLookupService {

    private static final Logger log = LoggerFactory.getLogger(MerchantLookupService.class);

    private final MerchantRepository merchantRepository;
    private final boolean strictMode;

    /**
     * Primary constructor used by Spring. {@code @Autowired} is required because this
     * class has multiple constructors (Spring 6 / Boot 3.x does not infer a single
     * candidate when 2+ ctors are present).
     *
     * @param merchantRepository the backing repository (Mongo / in-memory)
     * @param strictMode         when {@code true}, reject non-operational merchants
     */
    @Autowired
    public MerchantLookupService(MerchantRepository merchantRepository,
                                 @Value("${gmepay.merchant.strict-mode:false}") boolean strictMode) {
        this.merchantRepository = merchantRepository;
        this.strictMode = strictMode;
    }

    /**
     * Backwards-compatible constructor (lenient mode) for unit tests and callers
     * that do not configure strict mode.
     */
    public MerchantLookupService(MerchantRepository merchantRepository) {
        this(merchantRepository, false);
    }

    /**
     * Looks up the merchant associated with the given QR code identifier.
     *
     * @param qrCodeId the QR code identifier (ZeroPay CHAR(20))
     * @return the {@link Merchant} record
     * @throws ApiException with {@link ErrorCode#MERCHANT_NOT_FOUND} if not found, or
     *         (in strict mode) if the matched merchant is not operational
     */
    public Merchant getByQrCodeId(String qrCodeId) {
        Merchant merchant = merchantRepository.findByQrCodeId(qrCodeId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.MERCHANT_NOT_FOUND,
                        "No merchant found for QR code: " + qrCodeId));

        if (strictMode && !merchant.isOperational()) {
            log.warn("Strict-mode lookup rejected non-operational merchant: qr={}, merchantId={}, status={}, active={}",
                    qrCodeId, merchant.merchantId(), merchant.status(), merchant.active());
            throw new ApiException(
                    ErrorCode.MERCHANT_NOT_FOUND,
                    "Merchant for QR code " + qrCodeId + " is not operational (status="
                            + merchant.status() + ", active=" + merchant.active() + ")");
        }

        return merchant;
    }

    /** Returns whether strict (inactive-rejecting) mode is enabled. */
    public boolean isStrictMode() {
        return strictMode;
    }
}
