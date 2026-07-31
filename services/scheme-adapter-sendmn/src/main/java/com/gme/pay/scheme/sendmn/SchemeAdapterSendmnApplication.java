package com.gme.pay.scheme.sendmn;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the SendMN (Mongolia) QR scheme adapter service.
 *
 * <p>This service is the Anti-Corruption Layer (ACL) for the SendMN QR payment scheme
 * (SendMN fronts QPay, the Mongolian domestic QR switch). It translates
 * payment-executor's canonical {@code /internal/scheme/sendmn/...} calls into the
 * SendMN partner REST API (Authentication &rarr; VerifyQr &rarr; Confirm &rarr;
 * PaymentStatus, per {@code SMN_QRPayment_1.0.2}).</p>
 *
 * <p>Scheme peculiarities handled here:</p>
 * <ul>
 *   <li><b>encryptedData envelope</b> — every business body is
 *       {@code {"encryptedData": "..."}}; the exact RSA-4096 hybrid wire format is
 *       pending SendMN clarification, so it is isolated behind
 *       {@link com.gme.pay.scheme.sendmn.crypto.SendmnEnvelopeCodec}
 *       ({@code sendmn.envelope.mode=plain|rsa}).</li>
 *   <li><b>TX_TOKEN_NO idempotency</b> — partner-generated per payment, persisted in
 *       {@code smn_payments}; error 304 (duplicate) and ambiguous Confirm outcomes
 *       resolve via PaymentStatus polling, never blind resubmission (ADR-016).</li>
 *   <li><b>FX rate registration hosting</b> — SendMN calls
 *       {@code POST /partner-hosted/fx-rate} on us; registered buy rates persist in
 *       {@code smn_fx_rates} and drive/verify {@code SETTLEMENT_AMOUNT} on Confirm
 *       (SendMN re-checks it server-side, error 307).</li>
 * </ul>
 */
@SpringBootApplication
public class SchemeAdapterSendmnApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchemeAdapterSendmnApplication.class, args);
    }
}
