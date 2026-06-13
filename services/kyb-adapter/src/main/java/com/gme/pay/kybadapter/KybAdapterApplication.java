package com.gme.pay.kybadapter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * kyb-adapter service — owns the KYB / sanctions-screening vendor integration
 * (ADR-009 port, ADR-014 vendor).
 *
 * <p>Internal-only service: config-registry calls {@code POST /v1/kyb/screen}
 * when an operator hits "Run screening" in wizard step 3 (and, later, when the
 * daily rescreen scheduler fires). Every screening result is also fanned out on
 * Kafka topic {@code gmepay.kyb.screening} (ADR-001) so reporting-compliance
 * and notification-webhook can react without polling.
 *
 * <p>The active {@link com.gme.pay.kyb.KybProvider} is selected by
 * {@code gmepay.kyb.provider} (see {@link KybProviderConfig}): the
 * deterministic {@code StubKybAdapter} by default, the Octa Solution adapter
 * once ADR-014 sandbox credentials land.
 */
@SpringBootApplication
public class KybAdapterApplication {

    public static void main(String[] args) {
        SpringApplication.run(KybAdapterApplication.class, args);
    }
}
