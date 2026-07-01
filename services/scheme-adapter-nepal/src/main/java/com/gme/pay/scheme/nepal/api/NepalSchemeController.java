package com.gme.pay.scheme.nepal.api;

import com.gme.pay.scheme.nepal.adapter.NepalSchemeAdapter;
import com.gme.pay.scheme.nepal.dto.DecodeRequest;
import com.gme.pay.scheme.nepal.dto.DecodeResponse;
import com.gme.pay.scheme.nepal.dto.StatusResponse;
import com.gme.pay.scheme.nepal.dto.SubmitRequest;
import com.gme.pay.scheme.nepal.dto.SubmitResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal REST API exposed by the Nepal QR scheme adapter service.
 *
 * <p>Endpoints are internal-only (consumed by payment-executor, not the public gateway),
 * mirroring {@code /internal/scheme/zeropay/...}.</p>
 *
 * <p><b>One-shot vs two-phase:</b> ZeroPay exposes authorize then commit; Nepal's partner
 * {@code pay} is synchronous and single-shot, so {@code /submit} here is authorize+commit
 * combined — there is deliberately no separate commit endpoint.</p>
 */
@RestController
@RequestMapping("/internal/scheme/nepal")
public class NepalSchemeController {

    private final NepalSchemeAdapter adapter;

    public NepalSchemeController(NepalSchemeAdapter adapter) {
        this.adapter = adapter;
    }

    /** POST /internal/scheme/nepal/decode — resolve a scanned QR to merchant/receiver fields. */
    @PostMapping("/decode")
    public ResponseEntity<DecodeResponse> decode(@RequestBody DecodeRequest req) {
        return ResponseEntity.ok(adapter.decode(req.qs()));
    }

    /** POST /internal/scheme/nepal/submit — authorize+commit a payment via the partner /pay/. */
    @PostMapping("/submit")
    public ResponseEntity<SubmitResponse> submit(@RequestBody SubmitRequest req) {
        return ResponseEntity.ok(adapter.submit(req));
    }

    /** GET /internal/scheme/nepal/status?reference= — look up the state via the partner /status/. */
    @GetMapping("/status")
    public ResponseEntity<StatusResponse> status(@RequestParam("reference") String reference) {
        return ResponseEntity.ok(adapter.status(reference));
    }
}
