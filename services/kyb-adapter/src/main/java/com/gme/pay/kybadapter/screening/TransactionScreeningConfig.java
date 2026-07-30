package com.gme.pay.kybadapter.screening;

import com.gme.pay.kyb.NoProviderPaymentScreeningPort;
import com.gme.pay.kyb.PaymentScreeningPort;
import com.gme.pay.kyb.ScreeningRequirement;
import com.gme.pay.kyb.TransactionScreeningPolicy;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the payment-path sanctions/PEP screening seam in kyb-adapter — gap <b>T5-3</b>.
 *
 * <h2>Two beans, and both defaults are the honest ones</h2>
 *
 * <ol>
 *   <li>{@link PaymentScreeningPort} defaults to {@link NoProviderPaymentScreeningPort}: the truthful
 *       implementation of a platform with no screening vendor. It is
 *       {@code @ConditionalOnMissingBean}, so <b>integrating a real provider is exactly one bean</b> —
 *       define a port that calls the vendor and stamps
 *       {@link com.gme.pay.kyb.ScreeningProvenance#vendor(String)}, and this default steps aside. No
 *       policy, table, migration, audit verb or query surface changes.</li>
 *   <li>{@link TransactionScreeningPolicy} defaults to {@link TransactionScreeningPolicy#off()}: no
 *       party is required, so nothing is ever refused and the payment path is unchanged.</li>
 * </ol>
 *
 * <h2>Why the port default is a real bean and not an absent one</h2>
 *
 * <p>The tempting shape is {@code @ConditionalOnProperty} on a vendor being configured, leaving no port
 * at all when none is. That is precisely the failure mode this gap is about: an absent control is
 * invisible, and invisible is how "screening" ends up on a feature list. A port that always answers
 * {@code NOT_SCREENED_NO_PROVIDER} means every screening call still runs, still records, still audits
 * and still banners — the absence reports itself, in a table, with dates on it.
 *
 * <h2>Turning the requirement on is a compliance decision, not a hardening step</h2>
 *
 * <p>Setting {@code gmepay.screening.transaction.required-parties} while no authoritative provider is
 * wired refuses every payment involving those parties, because
 * {@link TransactionScreeningPolicy} fails closed on a required-but-unavailable check. That is correct
 * behaviour and it is also a decision to stop taking money, which belongs to an owner and to compliance
 * — never to a default. The startup banner says so in as many words.
 */
@Configuration
public class TransactionScreeningConfig {

    private static final Logger log = LoggerFactory.getLogger(TransactionScreeningConfig.class);

    /**
     * The platform clock, so {@code screenedAt} and {@code recorded_at} are reproducible in tests. A
     * test may replace it with a fixed clock.
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock screeningClock() {
        return Clock.systemUTC();
    }

    /**
     * The no-provider default. Replaced by any other {@link PaymentScreeningPort} bean on the context.
     *
     * @param clock the platform clock, so {@code screenedAt} is testable
     */
    @Bean
    @ConditionalOnMissingBean(PaymentScreeningPort.class)
    public PaymentScreeningPort noProviderPaymentScreeningPort(Clock clock) {
        return new NoProviderPaymentScreeningPort(clock);
    }

    /**
     * The posture. Bound from configuration so the fail-open/fail-closed decision is an owner's, made
     * once, visibly — rather than a branch someone chose while writing the gate.
     *
     * @param requiredParties which parties must carry a completed screening. <b>Empty by default.</b>
     *                        An unrecognised name fails startup rather than silently requiring nobody.
     * @param allowUnavailableOverride tolerate a required-but-unavailable screening. Non-production
     *                        only — {@link TransactionScreeningPolicy}'s constructor throws otherwise,
     *                        so the service does not start rather than starting and quietly permitting.
     * @param environment     the deployment environment; blank or unrecognised counts as production
     */
    @Bean
    @ConditionalOnMissingBean(TransactionScreeningPolicy.class)
    public TransactionScreeningPolicy transactionScreeningPolicy(
            @Value("${gmepay.screening.transaction.required-parties:}") List<String> requiredParties,
            @Value("${gmepay.screening.transaction.allow-unavailable-override:false}")
            boolean allowUnavailableOverride,
            @Value("${gmepay.environment:${spring.profiles.active:}}") String environment) {

        ScreeningRequirement requirement = ScreeningRequirement.parse(requiredParties);
        TransactionScreeningPolicy policy =
                new TransactionScreeningPolicy(requirement, allowUnavailableOverride, environment);
        banner(policy);
        return policy;
    }

    /**
     * Say out loud, at every startup, what this service will and will not do about screening. An
     * operator must not have to read code or query a table to learn that the platform screens nobody.
     */
    private static void banner(TransactionScreeningPolicy policy) {
        if (policy.inert()) {
            log.warn("T5-3 payment screening: NO PARTY IS REQUIRED TO BE SCREENED"
                    + " (gmepay.screening.transaction.required-parties is empty). Screening outcomes are"
                    + " recorded as evidence and audited, but NO payment will ever be refused for want of"
                    + " a sanctions/PEP check. Populating that property is a COMPLIANCE DECISION with a"
                    + " revenue consequence — see TransactionScreeningConfig.");
            return;
        }
        if (policy.overrideArmed()) {
            log.error("T5-3 payment screening: parties {} are REQUIRED but the fail-open override is"
                            + " ARMED in environment '{}'. Payments whose required screening cannot be"
                            + " performed WILL PROCEED, each producing a"
                            + " TRANSACTION_SCREENING_OVERRIDDEN audit row. This is a non-production"
                            + " posture and must never reach production.",
                    policy.requirement().describe(), policy.environment());
            return;
        }
        log.warn("T5-3 payment screening: parties {} are REQUIRED and the posture is FAIL-CLOSED"
                        + " (environment '{}'). Any payment whose screening cannot be performed will be"
                        + " REFUSED. Confirm an authoritative provider is wired — with the default"
                        + " no-provider port this refuses EVERY payment involving those parties.",
                policy.requirement().describe(), policy.environment());
    }
}
