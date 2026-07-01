package com.gme.pay.payment.client.rest;

import com.gme.pay.contracts.OperationalStatusView;
import com.gme.pay.payment.domain.client.OperationalStatusClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * In-process fallback {@link OperationalStatusClient} for tests and a no-config-registry sandbox.
 *
 * <p>Active only when the real {@link RestOperationalStatusClient} is NOT wired — i.e. when
 * {@code gmepay.config-registry.base-url} is unset (see {@code @ConditionalOnMissingBean}). It always
 * returns {@link OperationalStatusView#allClear()}, so the operational gate is a no-op and every new
 * payment proceeds. This keeps the wallet / orchestrated authorize paths runnable without a live
 * config-registry (unit slices, local sandbox).
 */
@Component
@Primary
@ConditionalOnMissingBean(RestOperationalStatusClient.class)
public class FixtureOperationalStatusClient implements OperationalStatusClient {

    @Override
    public OperationalStatusView currentStatus() {
        return OperationalStatusView.allClear();
    }
}
