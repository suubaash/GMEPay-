package com.gme.pay.kybadapter;

import com.gme.pay.events.EventPublisher;
import com.gme.pay.events.LogEventPublisher;
import com.gme.pay.kyb.KybProvider;
import com.gme.pay.kyb.StubKybAdapter;
import com.gme.pay.kybadapter.octa.OctaKybAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the active {@link KybProvider} (ADR-009: vendor swap is a
 * configuration change, never a refactor).
 *
 * <ul>
 *   <li>{@code gmepay.kyb.provider} unset or {@code stub} → the deterministic
 *       {@link StubKybAdapter} from lib-kyb (ADR-014: active until Octa
 *       Solution sandbox credentials arrive).</li>
 *   <li>{@code gmepay.kyb.provider=octa} → {@link OctaKybAdapter}, configured
 *       from {@code gmepay.kyb.octa.base-url} / {@code gmepay.kyb.octa.api-key}.
 *       Currently a placeholder that fails fast on invocation — see ADR-014.</li>
 * </ul>
 *
 * <p>Also registers the fallback {@link EventPublisher}: when
 * {@code spring.kafka.bootstrap-servers} is set, lib-events-kafka's
 * auto-configuration contributes a {@code @Primary} {@code KafkaEventPublisher}
 * that wins over this log-only default (the same wiring contract as
 * revenue-ledger's {@code OutboxConfig}); without a broker configured the
 * service logs each screening event instead of dropping it silently.
 */
@Configuration
public class KybProviderConfig {

    @Bean
    @ConditionalOnProperty(name = "gmepay.kyb.provider", havingValue = "stub", matchIfMissing = true)
    public KybProvider stubKybProvider() {
        return new StubKybAdapter();
    }

    @Bean
    @ConditionalOnProperty(name = "gmepay.kyb.provider", havingValue = "octa")
    public KybProvider octaKybProvider(
            @Value("${gmepay.kyb.octa.base-url:}") String baseUrl,
            @Value("${gmepay.kyb.octa.api-key:}") String apiKey) {
        return new OctaKybAdapter(baseUrl, apiKey);
    }

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    public EventPublisher logEventPublisher() {
        return new LogEventPublisher();
    }
}
