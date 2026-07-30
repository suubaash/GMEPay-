package com.gme.pay.payment.client.rest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3-6 task 5 — the silent no-op is no longer silent.
 *
 * <p>The CPO audit (P12) named the exact failure: when {@code gmepay.config-registry.base-url} is unset,
 * {@code FixtureOperationalStatusClient.allClear()} makes the platform's ONLY runtime availability
 * control vanish, and nothing anywhere says so. The ALLOW behaviour is kept (a sandbox and every unit
 * slice must still be able to take a payment, and the real client already fails CLOSED when it is wired
 * but unreachable); what is asserted here is that activation is announced at ERROR level and that the
 * message names both the missing property and the capability that is consequently absent.
 */
class FixtureClientLoudWarningTest {

    private static List<ILoggingEvent> capture(Class<?> type, Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }

    @Test
    @DisplayName("the operational-status fixture logs an ERROR banner naming the property + the loss")
    void operationalStatusFixture_isLoud() {
        FixtureOperationalStatusClient client = new FixtureOperationalStatusClient();

        List<ILoggingEvent> events = capture(FixtureOperationalStatusClient.class, client::warnLoudly);

        assertEquals(1, events.size());
        assertEquals(Level.ERROR, events.get(0).getLevel(),
                "a vanished kill switch is not an INFO-level fact");
        String rendered = events.get(0).getFormattedMessage();
        assertTrue(rendered.contains("gmepay.config-registry.base-url"), rendered);
        assertTrue(rendered.contains("OPERATIONAL GATE DISABLED"), rendered);
        assertTrue(rendered.contains("NO-OPS"), rendered);
        assertTrue(rendered.contains("T3-6"), rendered);

        // ...and the behaviour itself is unchanged: still all-clear, still permissive.
        assertTrue(client.currentStatus().suspendedPartners().isEmpty());
    }

    @Test
    @DisplayName("the operating-hours fixture logs an ERROR banner and answers UNVERIFIED, not OPEN")
    void schemeOperatingHoursFixture_isLoud_andUnverified() {
        FixtureSchemeOperatingHoursClient client = new FixtureSchemeOperatingHoursClient();

        List<ILoggingEvent> events =
                capture(FixtureSchemeOperatingHoursClient.class, client::warnLoudly);

        assertEquals(1, events.size());
        assertEquals(Level.ERROR, events.get(0).getLevel());
        String rendered = events.get(0).getFormattedMessage();
        assertTrue(rendered.contains("gmepay.config-registry.base-url"), rendered);
        assertTrue(rendered.contains("SCHEME_CLOSED"), rendered);
        assertTrue(rendered.contains("UNVERIFIED"), rendered);

        // Empty rows ⇒ UNVERIFIED at the evaluator, which is the whole point: no schedule read must
        // never be encoded as "open".
        assertTrue(client.weeklySchedule("ZEROPAY").isEmpty());
        assertTrue(com.gme.pay.contracts.SchemeAvailability
                .evaluate("ZEROPAY", client.weeklySchedule("ZEROPAY"), java.time.Instant.now())
                .unverified());
    }

    @Test
    @DisplayName("both banners are wired to a lifecycle callback, so they fire on startup unbidden")
    void bannersFireAtStartup() throws Exception {
        // The banner is worthless if nothing calls it; pin the @PostConstruct wiring rather than trusting
        // that a future edit keeps it.
        for (Class<?> type : List.of(FixtureOperationalStatusClient.class,
                FixtureSchemeOperatingHoursClient.class)) {
            var method = type.getDeclaredMethod("warnLoudly");
            assertTrue(method.isAnnotationPresent(jakarta.annotation.PostConstruct.class),
                    type.getSimpleName() + ".warnLoudly must be a @PostConstruct callback");
        }
    }
}
