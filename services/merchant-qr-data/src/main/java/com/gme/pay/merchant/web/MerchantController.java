package com.gme.pay.merchant.web;

import com.gme.pay.errors.ApiError;
import com.gme.pay.errors.ApiException;
import com.gme.pay.merchant.domain.Merchant;
import com.gme.pay.merchant.domain.MerchantLookupService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller exposing the merchant lookup endpoint.
 *
 * <p>Exposes: {@code GET /v1/merchants/{qr}}
 *
 * <p>The {@code {qr}} path variable is the ZeroPay QR code identifier (CHAR(20)).
 * Returns 200 with a {@link MerchantResponse} on success,
 * or 404 with a canonical {@link ApiError} body when the QR code is unknown.
 */
@RestController
@RequestMapping("/v1/merchants")
public class MerchantController {

    private final MerchantLookupService merchantLookupService;

    public MerchantController(MerchantLookupService merchantLookupService) {
        this.merchantLookupService = merchantLookupService;
    }

    /**
     * Looks up the merchant associated with the given QR code identifier.
     *
     * @param qr the QR code identifier from the URL path
     * @return 200 with merchant details, or 404 if not found
     */
    @GetMapping("/{qr}")
    public ResponseEntity<MerchantResponse> getByQr(@PathVariable("qr") String qr) {
        Merchant merchant = merchantLookupService.getByQrCodeId(qr);
        MerchantResponse response = new MerchantResponse(
                merchant.merchantId(),
                merchant.qrCodeId(),
                merchant.name(),          // serialised as "merchantName" — matches RestQrClient
                merchant.merchantType(),
                merchant.feeType(),
                merchant.status(),
                merchant.active(),
                merchant.payoutCurrency(),
                merchant.schemeId(),
                merchant.city(),
                merchant.mcc());
        return ResponseEntity.ok(response);
    }

    /** Translates {@link ApiException} into the canonical error envelope. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex) {
        ApiError body = ApiError.of(ex.errorCode(), ex.getMessage(), UUID.randomUUID().toString());
        return ResponseEntity.status(ex.errorCode().httpStatus()).body(body);
    }
}
