package com.gme.pay.prefunding;

import com.gme.pay.prefunding.aml.AmlMonitoringRules;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Prefunding/Balance service: prepaid USD balances, atomic deduction, low-balance alerts.
 *
 * <p>{@link AmlMonitoringRules} is bound here (gap T5-3). It carries the AML monitoring rule list,
 * which is <b>empty by default</b> — populating it is a compliance decision, and while it is empty
 * prefunding raises no AML monitoring alerts. Binding it explicitly rather than annotating it
 * {@code @Component} also means a malformed rule fails this service at startup.
 */
@SpringBootApplication
@EnableConfigurationProperties(AmlMonitoringRules.class)
public class PrefundingApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrefundingApplication.class, args);
    }
}
