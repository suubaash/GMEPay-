package com.gme.pay.settlement.exception;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /v1/settlement/exceptions/{id}/resolve.
 *
 * <p>The {@code operatorId} is typically the authenticated user's email address
 * (populated by the API gateway / BFF from the bearer token claim).
 *
 * <p>Field names are the canonical contract for the admin-ui front-end.
 * Do NOT rename without a coordinated UI change.
 */
public record ResolveExceptionRequest(

        /**
         * Operator identity — who is resolving this exception.
         * Must be non-blank; typically the ops user's email.
         */
        @NotBlank(message = "operatorId must not be blank")
        @Size(max = 128, message = "operatorId must not exceed 128 characters")
        String operatorId,

        /**
         * Free-text note explaining why this exception is being resolved.
         * Required — ops must document the reason.
         */
        @NotBlank(message = "note must not be blank")
        String note,

        /**
         * Structured resolution action.
         * Suggested values: MANUAL_OVERRIDE, RESUBMIT, WAIVED.
         * Required so the audit trail has machine-readable classification.
         */
        @NotNull(message = "resolutionAction must not be null")
        @Size(max = 32, message = "resolutionAction must not exceed 32 characters")
        String resolutionAction
) {}
