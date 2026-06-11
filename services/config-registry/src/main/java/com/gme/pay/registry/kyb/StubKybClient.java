package com.gme.pay.registry.kyb;

import com.gme.pay.kyb.KybSubject;
import com.gme.pay.kyb.ScreeningResult;
import com.gme.pay.kyb.StubKybAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link KybScreeningClient}: runs lib-kyb's deterministic
 * {@link StubKybAdapter} in-process. Active unless
 * {@code gmepay.kyb-adapter.client=rest} promotes {@link RestKybClient} —
 * the same stub-by-default discipline as the BFF's
 * {@code StubConfigRegistryClient}, so {@code @DataJpaTest} slices and local
 * dev never need the kyb-adapter service running.
 *
 * <p>Same decision rules as the adapter service's default wiring (both run
 * the one {@code StubKybAdapter}), so switching a local environment to
 * {@code rest} changes the transport, never the verdicts.
 */
@Component
@ConditionalOnProperty(name = "gmepay.kyb-adapter.client", havingValue = "stub", matchIfMissing = true)
public class StubKybClient implements KybScreeningClient {

    private final StubKybAdapter adapter = new StubKybAdapter();

    @Override
    public ScreeningResult screen(KybSubject subject) {
        return adapter.screen(subject);
    }
}
