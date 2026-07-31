package com.gme.sim.ninepay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 9Pay (Vietnam) disbursement MOCK — plays 9PAY'S SERVER for local development of
 * {@code services/scheme-adapter-ninepay}.
 *
 * <p>Implements the payout-to-banks API (integration spec ver 3.13, see
 * {@code Documentation/schemes/digest_9pay-payout-api_2026-07-27.md}):</p>
 * <ul>
 *   <li>POST /service/account/verify — beneficiary name resolve against a seeded registry</li>
 *   <li>POST /service/transfer — create payout (signature check, VND &ge; 2000, dup = 1062)</li>
 *   <li>POST /service/transfer/info — status lookup (optionally advances the lifecycle)</li>
 *   <li>POST /service/account/balance — seeded prefunded VND balance</li>
 *   <li>GET  /transfer-bank/bank-list — seeded VN bank/wallet roster</li>
 *   <li>POST /service/exchange-rate-v2 — seeded reference rates</li>
 *   <li>POST /service/v2/decode-qr — VIETQR/VNPAY decode (sim pipe format + EMV fallback)</li>
 * </ul>
 *
 * <p>Payouts advance PENDING → PROCESSING → SUCCESS/FAIL on a timer (and on lookup
 * polls); the terminal result is pushed as an <b>IPN</b> to {@code sim.ninepay.ipn-url}
 * (default: the adapter's {@code POST /scheme/ipn} on :8096), RSA-signed with the sim's
 * own key. Scenario toggles (/sim/scenario) force sync errors (1024/1065/1066), timeout,
 * IPN code 008 HELD, and the delayed code-009 post-SUCCESS bank reversal.</p>
 */
@SpringBootApplication
public class SimNinepayApplication {
    public static void main(String[] args) {
        SpringApplication.run(SimNinepayApplication.class, args);
    }
}
