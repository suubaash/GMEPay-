package com.gme.pay.router.resolve;

import com.gme.pay.contracts.SchemeOperatingHoursView;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default {@link SchemeOperatingHoursSource} when no config-registry-backed one is wired — every
 * scheme's window is reported as having NO rows, i.e. {@code UNVERIFIED}. Gap <b>T3-6</b>.
 *
 * <p>Named for what it ANSWERS, not for what it lacks: it does not claim schemes are open, it declines
 * to answer, and {@link LocationSchemeResolver} treats that as "route, but on the record". Mirrors the
 * three-verdict discipline of the settlement {@code BusinessCalendar.empty()} (gap T3-4), whose empty
 * calendar likewise classifies every date as UNVERIFIED rather than as a business day.
 *
 * <p><b>Loud on activation (T3-6 task 5).</b> The failure mode this gap punishes is invisible loss of a
 * control, so this bean logs an ERROR-level startup banner naming the missing property and the
 * capability that is consequently absent. A local run stays runnable; it just stops being silent.
 */
@Component
@ConditionalOnMissingBean(RestSchemeOperatingHoursSource.class)
public class UnverifiedSchemeOperatingHoursSource implements SchemeOperatingHoursSource {

    private static final Logger log =
            LoggerFactory.getLogger(UnverifiedSchemeOperatingHoursSource.class);

    /** Startup banner; package-private so a test can pin its content rather than a log framework. */
    static final String BANNER =
            "SCHEME OPERATING HOURS NOT CONSULTED: 'gmepay.config-registry.enabled' is not true, so the"
                    + " V024 scheme_operating_hours schedule is unavailable to scheme resolution. EVERY"
                    + " scheme's window is UNVERIFIED and no resolution will ever be refused with"
                    + " SCHEME_CLOSED. Enable the config-registry client in any environment that must"
                    + " honour scheme operating windows (gap T3-6).";

    @PostConstruct
    void warnLoudly() {
        log.error("!!! {}", BANNER);
    }

    @Override
    public List<SchemeOperatingHoursView> weeklySchedule(String schemeId) {
        return List.of();
    }
}
