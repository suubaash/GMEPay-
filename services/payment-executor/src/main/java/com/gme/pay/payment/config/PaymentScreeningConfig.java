package com.gme.pay.payment.config;

import com.gme.pay.kyb.NoProviderPaymentScreeningPort;
import com.gme.pay.kyb.PaymentScreeningPort;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the payment-path sanctions/PEP screening seam — gap <b>T5-3</b>.
 *
 * <p>The default is {@link NoProviderPaymentScreeningPort}: the truthful implementation of a platform
 * with no screening vendor. It is declared {@code @ConditionalOnMissingBean}, so <b>integrating a real
 * provider is exactly one bean</b> — define a {@link PaymentScreeningPort} that calls the vendor and
 * stamps {@link com.gme.pay.kyb.ScreeningProvenance#vendor(String)}, and this default steps aside. No
 * gate, counter, migration, alert type, error code or query surface changes.
 *
 * <h2>Why the default is a real bean and not a null / absent gate</h2>
 * <p>The tempting shape is {@code @ConditionalOnProperty} on a provider being configured, leaving no
 * gate at all when none is. That is precisely the failure mode this gap is about: an absent control is
 * invisible, and invisible is how "screening" ended up on a feature list. A port that always answers
 * {@code NOT_SCREENED_NO_PROVIDER} means the gate always runs, always counts, always alerts and always
 * banners — the absence reports itself.
 */
@Configuration
public class PaymentScreeningConfig {

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
}
