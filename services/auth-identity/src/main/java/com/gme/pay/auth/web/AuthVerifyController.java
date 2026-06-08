package com.gme.pay.auth.web;

import com.gme.pay.auth.dto.VerifyRequest;
import com.gme.pay.auth.dto.VerifyResponse;
import com.gme.pay.auth.service.AuthVerificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal API endpoint: POST /internal/auth/verify
 *
 * Intended for use by the api-gateway only (internal network, not publicly routed).
 * The gateway extracts HMAC headers from the partner request, computes the body SHA-256,
 * then POSTs here to delegate signature verification.
 *
 * Response:
 *   200 OK  with valid=true  → request is authentic; X-Partner-ID is populated.
 *   200 OK  with valid=false → request is rejected; error code indicates reason.
 *
 * Using 200 for both outcomes keeps the contract simple for the gateway consumer;
 * the gateway translates errorCode to the appropriate HTTP 4xx toward the partner.
 */
@RestController
@RequestMapping("/internal/auth")
public class AuthVerifyController {

    private final AuthVerificationService verificationService;

    public AuthVerifyController(AuthVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @PostMapping("/verify")
    public ResponseEntity<VerifyResponse> verify(@RequestBody VerifyRequest request) {
        VerifyResponse response = verificationService.verify(request);
        return ResponseEntity.ok(response);
    }
}
