package com.gme.pay.registry;

import com.gme.pay.changerequest.ChangeRequestStateMachineConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Config & Registry service: schemes, partners, rules, treasury rates
 * (config-driven onboarding).
 *
 * <p>{@link ChangeRequestStateMachineConfig} is imported explicitly because it
 * lives outside the {@code com.gme.pay.registry} base package (in
 * lib-change-request's {@code com.gme.pay.changerequest}) and would otherwise
 * be invisible to component scanning. This wires the Spring State Machine
 * factory used by {@code ChangeRequestService} per ADR-008.
 */
@SpringBootApplication
@Import(ChangeRequestStateMachineConfig.class)
public class RegistryApplication {

    public static void main(String[] args) {
        SpringApplication.run(RegistryApplication.class, args);
    }
}
