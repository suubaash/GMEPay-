package com.gme.pay.payment.alert;

import com.gme.pay.contracts.events.OpsAlertPayload;
import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the {@link DeclineSpikeMonitor} rolling-window DECLINE_SPIKE alert (defect #5). */
class DeclineSpikeMonitorTest {

    /** Captures the payloads pushed onto the EventPublisher seam. */
    private static final class CapturingPublisher implements EventPublisher {
        final List<OpsAlertPayload> published = new ArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            published.add(((OpsAlertEvent) event).getPayload());
        }
    }

    private static DeclineSpikeMonitor monitor(EventPublisher pub) {
        // window 60s, min 10 samples, threshold 0.5, no cooldown (0s) so a second spike could re-fire.
        return new DeclineSpikeMonitor(pub, Clock.systemUTC(), 60, 10, 0.5, 0);
    }

    @Test
    @DisplayName("a burst of declines over threshold emits a DECLINE_SPIKE alert for the partner")
    void declineBurst_emitsAlert() {
        CapturingPublisher pub = new CapturingPublisher();
        DeclineSpikeMonitor m = monitor(pub);

        // 9 declines then keep going — under min-samples nothing fires; 12 declines of 12 = 100%.
        for (int i = 0; i < 12; i++) {
            m.record("GMEREMIT", null, false);
        }

        // Alert(s) fired; assert the first is a well-formed DECLINE_SPIKE for the partner subject.
        assertTrue(!pub.published.isEmpty(), "a decline burst must emit at least one alert");
        OpsAlertPayload a = pub.published.get(0);
        assertEquals(OpsAlertPayload.EVENT_TYPE, a.eventType());
        assertEquals(DeclineSpikeMonitor.ALERT_TYPE, a.alertType());
        assertEquals("GMEREMIT", a.subjectRef());
        assertEquals(DeclineSpikeMonitor.SEV_CRITICAL, a.severity(), "100% decline rate → CRITICAL");
        assertNotNull(a.occurredAt());
    }

    @Test
    @DisplayName("below min-samples: an early decline does NOT trip a 100% rate")
    void belowMinSamples_noAlert() {
        CapturingPublisher pub = new CapturingPublisher();
        DeclineSpikeMonitor m = monitor(pub);

        for (int i = 0; i < 5; i++) {   // 5 < min-samples(10)
            m.record("GMEREMIT", null, false);
        }
        assertTrue(pub.published.isEmpty(), "under min-samples nothing should fire");
    }

    @Test
    @DisplayName("healthy approvals below threshold do not alert")
    void healthy_noAlert() {
        CapturingPublisher pub = new CapturingPublisher();
        DeclineSpikeMonitor m = monitor(pub);

        for (int i = 0; i < 30; i++) {
            m.record("GMEREMIT", "zeropay", true);   // all approved
        }
        assertTrue(pub.published.isEmpty(), "all-approved must not alert");
    }

    @Test
    @DisplayName("cooldown suppresses repeat alerts for the same subject")
    void cooldown_suppressesRepeat() {
        CapturingPublisher pub = new CapturingPublisher();
        // long cooldown so the second burst is suppressed
        DeclineSpikeMonitor m = new DeclineSpikeMonitor(
                pub, Clock.systemUTC(), 60, 10, 0.5, 3600);

        for (int i = 0; i < 20; i++) {
            m.record("SENDMN", null, false);
        }
        int afterFirst = pub.published.size();
        assertEquals(1, afterFirst, "exactly one alert while in cooldown");

        for (int i = 0; i < 20; i++) {
            m.record("SENDMN", null, false);
        }
        assertEquals(1, pub.published.size(), "cooldown must suppress the repeat");
    }
}
