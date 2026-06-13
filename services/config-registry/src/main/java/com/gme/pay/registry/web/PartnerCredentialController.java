package com.gme.pay.registry.web;

import com.gme.pay.contracts.IssuedCredentialBundle;
import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerCredentialView;
import com.gme.pay.registry.credential.PartnerCredentialService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 8 Lane B — credential ledger endpoints on the partner resource.
 * Mounted under {@code /v1/admin} per the Slice 7/8 endpoint contract (the
 * BFF adds Keycloak OIDC role-gating).
 *
 * <p>Issuance itself has NO direct endpoint: credentials are minted by the
 * lifecycle transition to SANDBOX / LIVE
 * ({@link PartnerCredentialService#issueForTransition}, wired by Lane A's
 * lifecycle controller) so a partner can never hold keys for a tier its FSM
 * state does not grant.
 *
 * <p>{@code {partnerCode}} is always the human-facing business code, never
 * the BIGINT surrogate — same URL contract as every other partner endpoint.
 */
@RestController
@RequestMapping("/v1/admin")
public class PartnerCredentialController {

    private final PartnerCredentialService credentialService;

    public PartnerCredentialController(PartnerCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    /**
     * Manual rotation ({@link PartnerCredentialService#rotateCredentials}):
     * marks the environment's ACTIVE ledger rows ROTATED, revokes the old
     * auth-identity keys and issues a fresh set, returning the ONE-TIME
     * plaintext bundle — it is unrecoverable afterwards (SEC-09 §4; the
     * response must not be logged by any intermediary).
     *
     * <p>Returns 200 with the bundle; 404 unknown partner; 400 bad
     * environment; 409 when nothing is ACTIVE to rotate.
     */
    @PostMapping("/partners/{partnerCode}/credentials/rotate")
    public IssuedCredentialBundle rotate(
            @PathVariable("partnerCode") String partnerCode,
            @RequestBody PartnerCommand.RotateCredentials body,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "request body is required");
        }
        return credentialService.rotateCredentials(partnerCode, body.environment(), actor);
    }

    /** The partner's full credential ledger — display residue only, never plaintext. */
    @GetMapping("/partners/{partnerCode}/credentials")
    public List<PartnerCredentialView> list(@PathVariable("partnerCode") String partnerCode) {
        return credentialService.listCredentials(partnerCode);
    }
}
