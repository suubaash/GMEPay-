package com.gme.pay.scheme.sendmn.dto;

/**
 * Acknowledgement for POST /partner-hosted/fx-rate (our contract design — doc §9 leaves
 * the response shape to the partner; "0" mirrors SendMN's own success convention).
 *
 * @param code       "0" = registered (or already registered)
 * @param message    human-readable note
 * @param fxTickerNo echoed registration id
 * @param duplicate  true when this FX_TICKER_NO was already registered (idempotent replay)
 */
public record FxRateRegistrationResponse(
        String code,
        String message,
        String fxTickerNo,
        boolean duplicate
) {}
