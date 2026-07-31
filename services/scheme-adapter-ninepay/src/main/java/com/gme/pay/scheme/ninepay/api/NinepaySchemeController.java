package com.gme.pay.scheme.ninepay.api;

import com.gme.pay.scheme.ninepay.adapter.NinepaySchemeAdapter;
import com.gme.pay.scheme.ninepay.dto.BalanceResponse;
import com.gme.pay.scheme.ninepay.dto.DecodeQrRequest;
import com.gme.pay.scheme.ninepay.dto.DecodeQrResponse;
import com.gme.pay.scheme.ninepay.dto.IpnAck;
import com.gme.pay.scheme.ninepay.dto.PayoutRequest;
import com.gme.pay.scheme.ninepay.dto.PayoutResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API exposed by the 9Pay payout scheme adapter.
 *
 * <p>{@code /scheme/payout}, {@code /scheme/payout/{requestId}}, {@code /scheme/balance}
 * and {@code /scheme/decode-qr} are the hub-facing surface (internal-only, consumed by the
 * future payout orchestration — hub wiring is deliberately out of Phase-3 scope, decision
 * D4). {@code /scheme/ipn} is the INBOUND edge 9Pay pushes status notifications to; in
 * production it must be reachable from 9Pay's IPN egress addresses and from nothing else.
 * That is now enforced rather than merely asserted — {@link NinepayIpnSourceFilter} (gap
 * <b>T5-7</b>) rejects an unlisted source with 403 before the body is parsed, and the
 * ingress ({@code deploy/helm/gmepay/templates/ingress-ipn.yaml}) drops it earlier still.
 * <b>The addresses themselves are operator/partner-supplied</b> and default to empty; the
 * unconfigured state is logged as a WARN on every boot because it leaves T5-6 uncompensated.
 * ({@code Documentation/schemes/digest_9pay-payout-api_2026-07-27.md} records the addresses
 * 9Pay's API doc listed — to be confirmed with 9Pay, not assumed.)</p>
 *
 * <p><b>Payout is one-shot and uncancellable</b>: there is no cancel endpoint by design —
 * 9Pay offers none. A payout that answered ambiguously reads back via
 * {@code GET /scheme/payout/{requestId}} (which polls 9Pay for non-final rows).</p>
 */
@RestController
@RequestMapping("/scheme")
public class NinepaySchemeController {

    private final NinepaySchemeAdapter adapter;

    public NinepaySchemeController(NinepaySchemeAdapter adapter) {
        this.adapter = adapter;
    }

    /** POST /scheme/payout — submit (or idempotently replay) a VND disbursement. */
    @PostMapping("/payout")
    public ResponseEntity<PayoutResponse> payout(@RequestBody PayoutRequest req) {
        return ResponseEntity.ok(adapter.submitPayout(req));
    }

    /** GET /scheme/payout/{requestId} — payout state; non-final rows are re-polled at 9Pay. */
    @GetMapping("/payout/{requestId}")
    public ResponseEntity<PayoutResponse> payoutStatus(@PathVariable("requestId") String requestId) {
        return ResponseEntity.ok(adapter.getPayout(requestId));
    }

    /** GET /scheme/balance — prefunded 9Pay balance (funding monitor / pre-submit check). */
    @GetMapping("/balance")
    public ResponseEntity<BalanceResponse> balance() {
        return ResponseEntity.ok(adapter.balance());
    }

    /** POST /scheme/decode-qr — decode a VIETQR/VNPAY payload into payout fields. */
    @PostMapping("/decode-qr")
    public ResponseEntity<DecodeQrResponse> decodeQr(@RequestBody DecodeQrRequest req) {
        return ResponseEntity.ok(adapter.decodeQr(req.qr()));
    }

    /**
     * POST /scheme/ipn — inbound 9Pay push (codes 000–009). The raw body is consumed as a
     * String so the verbatim payload lands in {@code np_ipn_events}; signature failures are
     * audited then rejected with 400 so 9Pay redelivers.
     */
    @PostMapping("/ipn")
    public ResponseEntity<IpnAck> ipn(@RequestBody String rawBody) {
        return ResponseEntity.ok(adapter.handleIpn(rawBody));
    }
}
