package com.gme.sim.sendmn;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * sim-sendmn — standalone SendMN (Mongolia, QPay-fronted) QR switch simulator.
 *
 * <p>Plays SendMN's server side of {@code SMN_QRPayment_1.0.2}: POST
 * /api/Authentication (header credentials → 90-min token) and the three business
 * endpoints /api/Partner/VerifyQr / Confirm / PaymentStatus (raw-token
 * {@code Authorization} + {@code Username}/{@code AgentCode} headers, bodies in the
 * plain-JSON {@code {"encryptedData": base64(json)}} envelope — matching
 * scheme-adapter-sendmn's {@code PlainJsonEnvelopeCodec} default). Phase 4 of
 * {@code Documentation/schemes/QR_SCHEME_ACCOMMODATION_PLAN.md}; port 9106.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SimSendmnApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimSendmnApplication.class, args);
    }
}
