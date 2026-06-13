package com.gme.pay.registry.web;

import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerMtlsCertView;
import com.gme.pay.registry.credential.PartnerMtlsCertService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 8 Lane B — mTLS client-certificate endpoints on the partner resource
 * (wizard step 8). Mounted under {@code /v1/admin} per the Slice 7/8 endpoint
 * contract (the BFF adds Keycloak OIDC role-gating).
 *
 * <p>{@code {partnerCode}} is always the human-facing business code, never
 * the BIGINT surrogate — same URL contract as every other partner endpoint.
 */
@RestController
@RequestMapping("/v1/admin")
public class PartnerMtlsCertController {

    private final PartnerMtlsCertService certService;

    public PartnerMtlsCertController(PartnerMtlsCertService certService) {
        this.certService = certService;
    }

    /**
     * Upload the step-8 mTLS client certificate for one environment
     * ({@link PartnerMtlsCertService#uploadCert}): the PEM is parsed
     * (X509Certificate), the validity window checked
     * ({@code notBefore <= now < notAfter}), the SHA-256 fingerprint computed
     * over the DER encoding, and the binding stored bitemporally (V027) with
     * one audit row (ADR-007).
     *
     * <p>Returns 200 with the fresh ACTIVE binding; 404 unknown partner; 400
     * on roster / parse / validity-window failure; 409 when the identical
     * cert is already the current ACTIVE binding.
     */
    @PatchMapping("/partners/draft/{partnerCode}/step-8/mtls-cert")
    public PartnerMtlsCertView uploadCert(
            @PathVariable("partnerCode") String partnerCode,
            @RequestBody PartnerCommand.UploadMtlsCert body,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "request body is required");
        }
        return certService.uploadCert(partnerCode, body.environment(), body.certPem(), actor);
    }

    /** The partner's CURRENT cert bindings across both environments (no PEM bodies). */
    @GetMapping("/partners/{partnerCode}/mtls-cert")
    public List<PartnerMtlsCertView> currentCerts(
            @PathVariable("partnerCode") String partnerCode) {
        return certService.currentCerts(partnerCode);
    }
}
