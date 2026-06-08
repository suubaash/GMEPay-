package com.gme.pay.qr.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /v1/qr/parse.
 *
 * @param rawPayload the raw EMVCo QR string scanned from the QR image
 * @param schemeId   the QR scheme identifier, e.g. "ZEROPAY" (required to select the correct parser)
 */
public record ParseQrRequest(
        @NotBlank(message = "rawPayload is required") String rawPayload,
        @NotBlank(message = "schemeId is required") String schemeId
) {}
