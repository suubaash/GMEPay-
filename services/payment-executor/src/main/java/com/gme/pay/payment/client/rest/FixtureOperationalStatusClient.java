package com.gme.pay.payment.client.rest;

import com.gme.pay.contracts.OperationalStatusView;
import com.gme.pay.payment.domain.client.OperationalStatusClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <h2>T3-6 task 5: the silent no-op is now loud</h2>
 * The CPO audit's P12 named this bean specifically: when the base-url is unset, "the only runtime
 * availability control silently vanishes" — the master pause, maintenance mode and every
 * partner/scheme/route suspension become no-ops, and nothing anywhere says so. Nothing about the
 * ALLOW behaviour changes here (making it fail-closed would stop every local sandbox and every unit
 * slice from being able to take a payment, and the real client already fails closed when it is wired
 * but unreachable). What changes is that activation now logs an ERROR-level startup banner naming the
 * missing property and each capability that is consequently absent.
 */
@Component
@Primary
@ConditionalOnMissingBean(RestOperationalStatusClient.class)
public class FixtureOperationalStatusClient implements OperationalStatusClient {

    private static final Logger log = LoggerFactory.getLogger(FixtureOperationalStatusClient.class);

    /**
     * The startup banner text. Package-private so a test can pin that the warning names the missing
     * property AND the lost capability, without pinning a logging framework.
     */
    static final String BANNER =
            "OPERATIONAL GATE DISABLED: 'gmepay.config-registry.base-url' is unset, so the operational"
                    + " status read model cannot be reached and every check returns ALL-CLEAR. The"
                    + " master pause, maintenance mode and all partner/scheme/route suspensions are"
                    + " NO-OPS — new payments cannot be stopped by an operator in this configuration."
                    + " Set gmepay.config-registry.base-url for any environment that must be pausable"
                    + " (gap T3-6).";

    @PostConstruct
    void warnLoudly() {
        log.error("!!! {}", BANNER);
    }

    @Override
    public OperationalStatusView currentStatus() {
        return OperationalStatusView.allClear();
    }
}
