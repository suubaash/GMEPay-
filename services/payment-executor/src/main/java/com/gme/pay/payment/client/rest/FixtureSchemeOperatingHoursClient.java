package com.gme.pay.payment.client.rest;

import com.gme.pay.contracts.SchemeOperatingHoursView;
import com.gme.pay.payment.domain.client.SchemeOperatingHoursClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * In-process fallback {@link SchemeOperatingHoursClient} for tests and a no-config-registry sandbox.
 *
 * <p>Active only when {@link RestSchemeOperatingHoursClient} is NOT wired — i.e. when
 * {@code gmepay.config-registry.base-url} is unset. It returns an EMPTY schedule for every scheme,
 * which the gate evaluates as {@code UNVERIFIED}: payments proceed, and every one of them is reported
 * as an unverified window rather than silently assumed open.
 *
 * <p><b>This bean is LOUD (T3-6 task 5).</b> The gap this closes was not "the fallback allows
 * payments" — a local sandbox must stay runnable — it was that the fallback was <b>invisible</b>. So
 * activation logs a startup banner at ERROR level naming the missing property and the exact capability
 * that is therefore absent. Availability control may be disabled in a sandbox; it may not be disabled
 * in silence.
 *
 * @see FixtureOperationalStatusClient
 */
@Component
@Primary
@ConditionalOnMissingBean(RestSchemeOperatingHoursClient.class)
public class FixtureSchemeOperatingHoursClient implements SchemeOperatingHoursClient {

    private static final Logger log =
            LoggerFactory.getLogger(FixtureSchemeOperatingHoursClient.class);

    /**
     * The startup banner text. Exposed (package-private) so a test can pin that the warning names
     * both the missing property and the lost capability, rather than pinning a log framework.
     */
    static final String BANNER =
            "SCHEME OPERATING HOURS NOT ENFORCED: 'gmepay.config-registry.base-url' is unset, so the"
                    + " V024 scheme_operating_hours schedule cannot be read. EVERY scheme's window is"
                    + " reported UNVERIFIED and no payment will ever be rejected with SCHEME_CLOSED."
                    + " Set gmepay.config-registry.base-url for any environment that must honour"
                    + " scheme operating windows (gap T3-6).";

    @PostConstruct
    void warnLoudly() {
        log.error("!!! {}", BANNER);
    }

    @Override
    public List<SchemeOperatingHoursView> weeklySchedule(String schemeId) {
        return List.of();
    }
}
