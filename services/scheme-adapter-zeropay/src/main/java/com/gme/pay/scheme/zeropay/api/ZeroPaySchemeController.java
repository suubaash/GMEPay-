package com.gme.pay.scheme.zeropay.api;

import com.gme.pay.scheme.zeropay.adapter.SchemeAdapter;
import com.gme.pay.scheme.zeropay.adapter.model.AdapterHealth;
import com.gme.pay.scheme.zeropay.adapter.model.MpmSubmitRequest;
import com.gme.pay.scheme.zeropay.adapter.model.MpmSubmitResponse;
import com.gme.pay.scheme.zeropay.dto.AdapterHealthResponse;
import com.gme.pay.scheme.zeropay.dto.SubmitPaymentRequest;
import com.gme.pay.scheme.zeropay.dto.SubmitPaymentResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal REST API exposed by the ZeroPay scheme adapter service.
 *
 * <p>Endpoints are internal-only (not exposed through the public API gateway).</p>
 */
@RestController
@RequestMapping("/internal/scheme/zeropay")
public class ZeroPaySchemeController {

    private final SchemeAdapter schemeAdapter;

    public ZeroPaySchemeController(SchemeAdapter schemeAdapter) {
        this.schemeAdapter = schemeAdapter;
    }

    /**
     * Submits a payment to ZeroPay (MPM mode).
     *
     * <p>POST /internal/scheme/zeropay/submit</p>
     */
    @PostMapping("/submit")
    public ResponseEntity<SubmitPaymentResponse> submit(
            @RequestBody SubmitPaymentRequest request) {

        MpmSubmitRequest domainRequest = new MpmSubmitRequest(
                request.merchantId(),
                request.amountKrw(),
                request.currency(),
                request.partnerTxnRef(),
                request.idempotencyKey()
        );

        MpmSubmitResponse domainResponse = schemeAdapter.submitMpm(domainRequest);

        SubmitPaymentResponse response = new SubmitPaymentResponse(
                domainResponse.zeroPayTxnRef(),
                domainResponse.resultCode(),
                domainResponse.resultMessage(),
                "00".equals(domainResponse.resultCode())
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Returns the current health of the ZeroPay adapter.
     *
     * <p>GET /internal/scheme/zeropay/health</p>
     */
    @GetMapping("/health")
    public ResponseEntity<AdapterHealthResponse> health() {
        AdapterHealth health = schemeAdapter.healthCheck();
        AdapterHealthResponse response = new AdapterHealthResponse(
                health.status().name(),
                health.lastCheckedAt(),
                health.sftpReachable(),
                health.realtimeApiReachable(),
                health.lastError()
        );
        return ResponseEntity.ok(response);
    }
}
