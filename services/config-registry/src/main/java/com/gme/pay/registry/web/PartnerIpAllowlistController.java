package com.gme.pay.registry.web;

import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerIpAllowlistView;
import com.gme.pay.registry.credential.PartnerIpAllowlistService;
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
 * Slice 8 Lane B — IP-allowlist endpoints on the partner resource (wizard
 * step 8's reachability editor). Mounted under {@code /v1/admin} per the
 * Slice 7/8 endpoint contract (the BFF adds Keycloak OIDC role-gating).
 *
 * <p>{@code {partnerCode}} is always the human-facing business code, never
 * the BIGINT surrogate — same URL contract as every other partner endpoint.
 */
@RestController
@RequestMapping("/v1/admin")
public class PartnerIpAllowlistController {

    private final PartnerIpAllowlistService allowlistService;

    public PartnerIpAllowlistController(PartnerIpAllowlistService allowlistService) {
        this.allowlistService = allowlistService;
    }

    /**
     * Save the step-8 IP allowlist onto an existing draft — bulk replace
     * ({@link PartnerIpAllowlistService#replaceAllowlist}): every existing
     * {@code partner_ip_allowlist} row of the partner is replaced by the
     * payload set in one transaction with one audit row (ADR-007).
     *
     * <p>Returns 200 with the fresh set; 404 unknown draft; 409 when the
     * partner has left ONBOARDING or an environment exceeds the 10-CIDR
     * ceiling ({@code CIDR_LIMIT_EXCEEDED}); 400 on shape/duplicate
     * validation failure with the offending {@code ipAllowlist[i]} index.
     */
    @PatchMapping("/partners/draft/{partnerCode}/step-8/ip-allowlist")
    public List<PartnerIpAllowlistView> replaceAllowlist(
            @PathVariable("partnerCode") String partnerCode,
            @RequestBody PartnerCommand.UpdateStep8Credentials body,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "request body is required");
        }
        return allowlistService.replaceAllowlist(partnerCode, body.ipAllowlist(), actor);
    }

    /** The partner's allowlist across both environments (read shape). */
    @GetMapping("/partners/{partnerCode}/ip-allowlist")
    public List<PartnerIpAllowlistView> currentAllowlist(
            @PathVariable("partnerCode") String partnerCode) {
        return allowlistService.currentAllowlist(partnerCode);
    }
}
