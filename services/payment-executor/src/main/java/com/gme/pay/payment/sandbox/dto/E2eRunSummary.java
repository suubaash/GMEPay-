package com.gme.pay.payment.sandbox.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response element for {@code GET /v1/sandbox/e2e/runs} — a run without its steps.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record E2eRunSummary(
        long id,
        String createdAt,
        String country,
        String partner,
        String amount,
        String currency,
        String mpmType,
        String status,
        String failedStep,
        int stepCount
) {
}
