package com.gme.pay.qr.controller;

import com.gme.pay.qr.domain.cpm.CpmToken;
import com.gme.pay.qr.domain.cpm.CpmTokenGenerator;
import com.gme.pay.qr.dto.CpmGenerateRequest;
import com.gme.pay.qr.dto.CpmTokenResponse;
import com.gme.pay.qr.exception.QRParseException;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Exposes POST /v1/qr/cpm/generate (WBS 5.3-T08).
 *
 * <p>In this wave the endpoint generates a deterministic CPM token locally (no external
 * ZeroPay call). Prefunding reservation, idempotency, HMAC auth, and scheme-resolution
 * logic are orchestrated from the payment-executor service; qr-service is responsible only
 * for the token structure and the REST contract.
 */
@RestController
@RequestMapping("/v1/qr/cpm")
public class CpmController {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final CpmTokenGenerator tokenGenerator;

    public CpmController(CpmTokenGenerator tokenGenerator) {
        this.tokenGenerator = tokenGenerator;
    }

    /**
     * Generate a CPM QR token for the given partner request.
     *
     * @param request validated {@link CpmGenerateRequest}
     * @return HTTP 201 with {@link CpmTokenResponse}
     */
    @PostMapping("/generate")
    public ResponseEntity<CpmTokenResponse> generate(@Valid @RequestBody CpmGenerateRequest request) {
        CpmToken token = tokenGenerator.generate(
                request.schemeId(),
                request.partnerTxnRef(),
                request.customerRef(),
                request.countryCode()
        );
        CpmTokenResponse response = toResponse(token);
        return ResponseEntity.status(201).body(response);
    }

    // -----------------------------------------------------------------------
    // Exception handling
    // -----------------------------------------------------------------------

    @ExceptionHandler(QRParseException.class)
    public ResponseEntity<Map<String, Object>> handleQRParseException(QRParseException ex) {
        return ResponseEntity.unprocessableEntity().body(Map.of(
                "errorCode", ex.getErrorCode().name(),
                "message",   ex.getMessage()
        ));
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
                null,   // prefundReservedUsd omitted for LOCAL partners (set by orchestrator for OVERSEAS)
                t.paymentId(),
                t.schemeId(),
                t.partnerTxnRef(),
                ISO_UTC.format(t.issuedAt())
        );
    }
}
