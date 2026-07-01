package com.gme.pay.registry.kyb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * Local mirror of kyb-adapter's {@code POST /v1/kyb/verify} response — only the
 * fields config-registry persists onto the {@code partner_kyb} row. The
 * adapter's full result carries more (sanctions hits, business-registration
 * verdict, missing-document list); {@code @JsonIgnoreProperties(ignoreUnknown)}
 * tolerates them so this binding stays robust as kyb-adapter's payload grows.
 *
 * @param providerRef      deterministic vendor reference of the run — stored as
 *                         the KYB row's {@code screening_provider_ref} and the
 *                         {@code GET /v1/kyb/result/{ref}} key.
 * @param decision         the collapsed verdict (e.g. {@code APPROVED},
 *                         {@code MANUAL_REVIEW}, {@code REJECTED}) — a String so
 *                         this service is not coupled to the adapter's enum.
 * @param decisionReason   one-line human-readable explanation.
 * @param screeningStatus  sanctions/PEP disposition (CLEAR / HIT / NEEDS_REVIEW)
 *                         — stored as {@code screening_status}.
 * @param screenedAt       run completion instant; {@code null} → caller stamps now.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KybVerificationResult(
        String providerRef,
        String decision,
        String decisionReason,
        String screeningStatus,
        Instant screenedAt) {
}
