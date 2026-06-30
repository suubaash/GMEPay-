package com.gme.pay.qr.controller;

import com.gme.pay.qr.domain.cpm.CpmGenerateService;
import com.gme.pay.qr.domain.cpm.CpmToken;
import com.gme.pay.qr.dto.CpmGenerateRequest;
import com.gme.pay.qr.dto.CpmTokenResponse;
import com.gme.pay.qr.exception.QRErrorCode;
import com.gme.pay.qr.exception.QRParseException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Exposes POST /v1/qr/cpm/generate (WBS 5.3-T08).
 *
 * <p>qr-service owns the token structure, scheme-for-country resolution, prepare-token issuance
 * (via a port — local fallback now, scheme adapter in prod) and session persistence. Prefunding
 * reservation and authoritative smart-routing are orchestrated from other services (FROZEN — see
 * INTEGRATION REQUESTS).
 */
@RestController
@RequestMapping("/v1/qr/cpm")
public class CpmController {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final CpmGenerateService generateService;

    public CpmController(CpmGenerateService generateService) {
        this.generateService = generateService;
    }

    /**
     * Generate a CPM QR token for the given partner request.
     *
     * @param request validated {@link CpmGenerateRequest}
     * @return HTTP 201 with {@link CpmTokenResponse}
     */
    @PostMapping("/generate")
    public ResponseEntity<CpmTokenResponse> generate(@Valid @RequestBody CpmGenerateRequest request) {
        CpmToken token = generateService.createSession(
                request.schemeId(),
                request.direction(),
                request.customerRef(),
                request.partnerTxnRef(),
                request.countryCode(),
                request.prefundReserveUsd(),
                request.partnerId()
        );
        return ResponseEntity.status(201).body(toResponse(token));
    }

    // -----------------------------------------------------------------------
    // Exception handling
    // -----------------------------------------------------------------------

    @ExceptionHandler(QRParseException.class)
    public ResponseEntity<Map<String, Object>> handleQRParseException(QRParseException ex) {
        return ResponseEntity.status(statusFor(ex.getErrorCode())).body(Map.of(
                "errorCode", ex.getErrorCode().name(),
                "message",   ex.getMessage()
        ));
    }

    /** Map a CPM error code to its HTTP status (WBS 5.3-T08/T09 error table). */
    private static HttpStatus statusFor(QRErrorCode code) {
        return switch (code) {
            case DUPLICATE_PARTNER_TXN_REF -> HttpStatus.CONFLICT;            // 409
            case INSUFFICIENT_PREFUNDING   -> HttpStatus.PAYMENT_REQUIRED;    // 402
            case MISSING_IDEMPOTENCY_KEY   -> HttpStatus.BAD_REQUEST;         // 400
            case INVALID_SIGNATURE         -> HttpStatus.UNAUTHORIZED;        // 401
            default                        -> HttpStatus.UNPROCESSABLE_ENTITY; // 422
        };
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static CpmTokenResponse toResponse(CpmToken t) {
        return new CpmTokenResponse(
                t.cpmTokenId(),
                t.prepareToken(),
                t.qrContent(),
                ISO_UTC.format(t.expiresAt()),
                null,   // prefundReservedUsd set by orchestrator for OVERSEAS (other service)
                t.paymentId(),
                t.schemeId(),
                t.partnerTxnRef(),
                ISO_UTC.format(t.issuedAt())
        );
    }
}
