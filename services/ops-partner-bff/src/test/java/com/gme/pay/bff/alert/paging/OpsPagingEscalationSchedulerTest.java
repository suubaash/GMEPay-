package com.gme.pay.bff.alert.paging;

import com.gme.pay.bff.alert.OpsAlertEventHandler;
import com.gme.pay.bff.alert.OpsAlertStore;
import com.gme.pay.bff.alert.OpsAlertView;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OpsPagingEscalationScheduler}: an un-acked CRITICAL alert older than the window is
 * re-paged; an acknowledged alert is NOT (ack stops escalation).
 */
class OpsPagingEscalationSchedulerTest {

    // occurredAt far in the past so it is always older than the escalation window.
    private static final String OLD_CRIT =
            "{\"eventType\":\"ops.alert\",\"alertType\":\"STUCK_TXN\",\"severity\":\"CRITICAL\","
                    + "\"subjectRef\":\"TXN-9\",\"detail\":\"stuck\",\"occurredAt\":\"2020-01-01T00:00:00Z\"}";

    private OpsPagingDispatcher dispatcher(TestPaging.RecordingPort port, OpsAlertStore store) {
        // Zero cooldown so the escalation re-page is not suppressed by the initial consume page.
        return TestPaging.dispatcher(port, store, "CRITICAL", Duration.ZERO, Clock.systemUTC());
    }

    @Test
    void rePagesUnackedCritical() {
        OpsAlertStore store = new OpsAlertStore(200);
        TestPaging.RecordingPort port = new TestPaging.RecordingPort();
        OpsPagingDispatcher d = dispatcher(port, store);
        new OpsAlertEventHandler(store, d).handle("TXN-9", OLD_CRIT); // page #1 on consume
        assertThat(port.pages).hasSize(1);

        OpsPagingEscalationScheduler sched =
                new OpsPagingEscalationScheduler(store, d, Duration.ofMinutes(10));
        sched.sweep();

        assertThat(port.pages).hasSize(2); // escalated
        assertThat(store.recent(null, null, 0).get(0).paging().attempts()).isEqualTo(2);
    }

    @Test
    void ackStopsEscalation() {
        OpsAlertStore store = new OpsAlertStore(200);
        TestPaging.RecordingPort port = new TestPaging.RecordingPort();
        OpsPagingDispatcher d = dispatcher(port, store);
        new OpsAlertEventHandler(store, d).handle("TXN-9", OLD_CRIT);
        long seq = store.recent(null, null, 0).get(0).seq();
        store.update(seq, a -> a.withAck(new OpsAlertView.Ack("op", "handling", "2026-07-02T00:00:00Z")));
        int before = port.pages.size();

        new OpsPagingEscalationScheduler(store, d, Duration.ofMinutes(10)).sweep();

        assertThat(port.pages).hasSize(before); // acked → not re-paged
    }
}
