package com.gme.pay.bff.web.dto;

import com.gme.pay.contracts.PartnerCommand;

/**
 * BFF wire shape for
 * {@code POST /v1/admin/partners/{code}/credentials/rotate}
 * (Slice 8 Lane B — credential rotation). The response carries the ONE-TIME
 * {@link com.gme.pay.contracts.IssuedCredentialBundle}; the BFF log-masking
 * filter ensures it is never written to any access or body log.
 *
 * @param environment {@code SANDBOX} | {@code PRODUCTION}.
 */
public record CredentialRotationRequest(String environment) {

    /** Adapt to the canonical write surface {@code lib-api-contracts} exposes. */
    public PartnerCommand.RotateCredentials toRotateCredentials() {
        return new PartnerCommand.RotateCredentials(environment);
    }
}
