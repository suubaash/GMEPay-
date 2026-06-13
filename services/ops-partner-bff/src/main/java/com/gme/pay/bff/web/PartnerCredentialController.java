package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.web.dto.CredentialRotationRequest;
import com.gme.pay.bff.web.dto.DraftPartnerStep8IpAllowlistRequest;
import com.gme.pay.bff.web.dto.DraftPartnerStep8MtlsCertRequest;
import com.gme.pay.contracts.IssuedCredentialBundle;
import com.gme.pay.contracts.PartnerCredentialView;
import com.gme.pay.contracts.PartnerIpAllowlistView;
import com.gme.pay.contracts.PartnerMtlsCertView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 8 Lane B — credential, IP-allowlist and mTLS certificate pass-throughs
 * for the Admin UI wizard's step-8 panel and the partner detail page.
 *
 * <p>Pure pass-throughs: each call delegates to {@link ConfigRegistryClient},
 * which adapts to config-registry's credential, ip-allowlist and mtls-cert
 * endpoints. Upstream 4xx pass through with their messages preserved.
 *
 * <p>⚠ SECURITY (SEC-09 §4): the {@code /credentials/rotate} response carries
 * an {@link IssuedCredentialBundle} — the ONE-TIME plaintext credential set.
 * The {@code IssuedCredentialBundleLogMaskingFilter} registered in this
 * service ensures that the response body for this path is NEVER written to
 * any access-log or body-logging output, even at TRACE level.
 */
@RestController
@RequestMapping("/v1/admin")
public class PartnerCredentialController {

    private final ConfigRegistryClient configRegistry;

    public PartnerCredentialController(ConfigRegistryClient configRegistry) {
        this.configRegistry = configRegistry;
    }

    // -----------------------------------------------------------------------
    // IP allowlist
    // -----------------------------------------------------------------------

    /**
     * Save the step-8 IP allowlist onto an existing draft — bulk replace.
     * Returns 200 with the fresh set; 404 unknown draft; 409 environment limit;
     * 400 on shape/duplicate validation failure.
     */
    @PatchMapping("/partners/draft/{partnerCode}/step-8/ip-allowlist")
    public List<PartnerIpAllowlistView> replaceIpAllowlist(
            @PathVariable String partnerCode,
            @RequestBody DraftPartnerStep8IpAllowlistRequest body,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body required");
        }
        return configRegistry.patchDraftStep8IpAllowlist(
                partnerCode, body.toUpdateStep8Credentials(), actor);
    }

    /** The partner's allowlist across both environments (read shape). */
    @GetMapping("/partners/{partnerCode}/ip-allowlist")
    public List<PartnerIpAllowlistView> getIpAllowlist(
            @PathVariable String partnerCode) {
        return configRegistry.getIpAllowlist(partnerCode);
    }

    // -----------------------------------------------------------------------
    // mTLS certificate
    // -----------------------------------------------------------------------

    /**
     * Upload the step-8 mTLS client certificate — PEM parsed, fingerprinted,
     * stored bitemporally (V027). Returns 200 with the fresh ACTIVE binding;
     * 404 unknown partner; 400 on parse/validity-window failure; 409 duplicate.
     */
    @PatchMapping("/partners/draft/{partnerCode}/step-8/mtls-cert")
    public PartnerMtlsCertView uploadMtlsCert(
            @PathVariable String partnerCode,
            @RequestBody DraftPartnerStep8MtlsCertRequest body,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body required");
        }
        return configRegistry.patchDraftStep8MtlsCert(
                partnerCode, body.toUploadMtlsCert(), actor);
    }

    /** The partner's CURRENT cert bindings across both environments (no PEM bodies). */
    @GetMapping("/partners/{partnerCode}/mtls-cert")
    public List<PartnerMtlsCertView> getMtlsCerts(@PathVariable String partnerCode) {
        return configRegistry.getMtlsCerts(partnerCode);
    }

    // -----------------------------------------------------------------------
    // Credential ledger + rotation
    // -----------------------------------------------------------------------

    /**
     * Manual credential rotation. Returns 200 with the ONE-TIME
     * {@link IssuedCredentialBundle} — unrecoverable after this response.
     * 404 unknown partner; 400 bad environment; 409 nothing ACTIVE to rotate.
     *
     * <p>⚠ The response body MUST NOT be logged. The
     * {@code IssuedCredentialBundleLogMaskingFilter} enforces this.
     */
    @PostMapping("/partners/{partnerCode}/credentials/rotate")
    public IssuedCredentialBundle rotateCredentials(
            @PathVariable String partnerCode,
            @RequestBody CredentialRotationRequest body,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body required");
        }
        return configRegistry.rotateCredentials(
                partnerCode, body.toRotateCredentials(), actor);
    }

    /** The partner's full credential ledger — display residue only, never plaintext. */
    @GetMapping("/partners/{partnerCode}/credentials")
    public List<PartnerCredentialView> listCredentials(@PathVariable String partnerCode) {
        return configRegistry.listCredentials(partnerCode);
    }
}
