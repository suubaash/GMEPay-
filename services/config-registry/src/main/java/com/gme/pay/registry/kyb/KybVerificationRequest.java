package com.gme.pay.registry.kyb;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gme.pay.kyb.KybSubject;
import java.util.List;

/**
 * Local mirror of kyb-adapter's {@code POST /v1/kyb/verify} request body — the
 * full-verification counterpart to the {@link KybScreeningClient} screen call
 * (ADR-009). config-registry assembles it from the stored partner aggregate at
 * the wizard's KYB step and posts it to kyb-adapter via {@link RestKybVerifyClient}.
 *
 * <p>Defined here (not imported) because kyb-adapter's own
 * {@code KybVerificationRequest} lives in its service module, not a shared lib —
 * the field names match so the wire body binds either way. {@code KybSubject}
 * IS shared (lib-kyb), reused unchanged.
 *
 * @param subject           the entity + UBOs to verify; required.
 * @param suppliedDocuments the onboarding document types the operator attached
 *                          (matched against kyb-adapter's required-document set;
 *                          a missing required document downgrades a clean run to
 *                          MANUAL_REVIEW rather than failing it).
 * @param force             re-run even if a prior run for the same subject is
 *                          persisted; {@code false} = idempotent replay.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KybVerificationRequest(
        KybSubject subject,
        List<String> suppliedDocuments,
        boolean force) {
}
