package com.gme.sim.nepalqr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Nepal QR partner MOCK (Khalti / Fonepay).
 *
 * Mocks two Khalti contracts so GMEPay+'s wallet/scheme flow can create a
 * transaction against the Nepal QR partner:
 *   - QR Validate API           (POST /api/qr/validate/)
 *   - Issuance Extension API     (parse / pay / status under /qrscan-thirdparty/)
 *
 * Every inbound request and its response is persisted in an in-memory record store,
 * inspectable via GET /sim/nepal-qr/records[...].
 */
@SpringBootApplication
public class SimNepalQrApplication {
    public static void main(String[] args) {
        SpringApplication.run(SimNepalQrApplication.class, args);
    }
}
