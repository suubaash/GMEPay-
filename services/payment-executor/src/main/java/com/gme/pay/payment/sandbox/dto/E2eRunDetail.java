package com.gme.pay.payment.sandbox.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Response body for {@code POST /v1/sandbox/e2e/run} and {@code GET /v1/sandbox/e2e/runs/{id}} —
 * a run summary plus its ordered steps.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record E2eRunDetail(
        long id,
        String createdAt,
        String country,
        String partner,
        String amount,
        String currency,
        String mpmType,
        String status,
        String failedStep,
        int stepCount,
        List<Step> steps
) {
    /** One ordered step in a run. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Step(
            int seq,
            String name,
            String status,
            String detail,
            Long latencyMs,
            Integer httpStatus
    ) {
    }
}
