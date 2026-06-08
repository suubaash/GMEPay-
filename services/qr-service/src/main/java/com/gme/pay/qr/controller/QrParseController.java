package com.gme.pay.qr.controller;

import com.gme.pay.qr.domain.emvco.ParsedQRPayload;
import com.gme.pay.qr.domain.emvco.ZeroPayQRParser;
import com.gme.pay.qr.dto.ParseQrRequest;
import com.gme.pay.qr.dto.ParsedQrResponse;
import com.gme.pay.qr.exception.QRErrorCode;
import com.gme.pay.qr.exception.QRParseException;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Exposes POST /v1/qr/parse (WBS 5.4-T19 / scope: parse endpoint).
 *
 * <p>Currently supports schemeId=ZEROPAY. Unknown scheme IDs return 422 QR_UNKNOWN_SCHEME.
 */
@RestController
@RequestMapping("/v1/qr")
public class QrParseController {

    private final ZeroPayQRParser zeroPayQRParser;

    public QrParseController(ZeroPayQRParser zeroPayQRParser) {
        this.zeroPayQRParser = zeroPayQRParser;
    }

    /**
     * Parse a raw EMVCo QR payload and return the structured fields.
     *
     * @param request {@link ParseQrRequest} with rawPayload and schemeId
     * @return 200 with {@link ParsedQrResponse} on success; 422 on parse failure
     */
    @PostMapping("/parse")
    public ResponseEntity<ParsedQrResponse> parse(@Valid @RequestBody ParseQrRequest request) {
        ParsedQRPayload parsed = routeToParser(request.schemeId(), request.rawPayload());
        ParsedQrResponse response = toResponse(parsed);
        return ResponseEntity.ok(response);
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

    private ParsedQRPayload routeToParser(String schemeId, String rawPayload) {
        if (ZeroPayQRParser.SCHEME_ID.equalsIgnoreCase(schemeId)) {
            return zeroPayQRParser.parse(rawPayload);
        }
        throw new com.gme.pay.qr.exception.QRUnknownSchemeException(
                "No parser registered for schemeId: " + schemeId);
    }

    private static ParsedQrResponse toResponse(ParsedQRPayload p) {
        return new ParsedQrResponse(
                p.rawPayload(),
                p.formatIndicator(),
                p.currencyCode(),
                p.merchantName(),
                p.merchantCity(),
                p.mcc(),
                p.countryCode(),
                p.maiTag(),
                p.merchantId(),
                p.qrCodeId(),
                p.encodedAmount(),
                p.crcVerified()
        );
    }
}
