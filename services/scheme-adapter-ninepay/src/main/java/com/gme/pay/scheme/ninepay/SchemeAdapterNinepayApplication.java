package com.gme.pay.scheme.ninepay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the 9Pay (Vietnam) payout scheme adapter service.
 *
 * <p>This service is the Anti-Corruption Layer (ACL) for the 9Pay disbursement
 * ("Pay-Out to Banks") API: VND transfers from a partner-prefunded 9Pay balance to
 * Vietnamese bank accounts, ATM cards and e-wallets. It exposes a canonical
 * {@code /scheme/...} REST surface toward the hub (payout submit / status / balance /
 * decode-qr) and an inbound {@code POST /scheme/ipn} endpoint for 9Pay's push
 * notifications (message codes 000–009, incl. the post-SUCCESS bank-reversal code 009).</p>
 *
 * <p>Naming: 9Pay's Java identifier is {@code ninepay} (packages cannot start with a
 * digit — decision D1 in {@code QR_SCHEME_ACCOMMODATION_PLAN.md}); SCHEME_CODE is
 * "NINEPAY", display name "9Pay".</p>
 *
 * <p>Unlike the QR-pay adapters (nepal/zeropay MPM), 9Pay is a <b>disbursement</b> edge:
 * submissions cannot be cancelled, idempotency is carried by a globally-unique
 * {@code request_id} (9Pay error 1062 = duplicate), and an ambiguous timeout must be
 * resolved by polling {@code /service/transfer/info} — never by resubmitting.</p>
 */
@SpringBootApplication
public class SchemeAdapterNinepayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchemeAdapterNinepayApplication.class, args);
    }
}
