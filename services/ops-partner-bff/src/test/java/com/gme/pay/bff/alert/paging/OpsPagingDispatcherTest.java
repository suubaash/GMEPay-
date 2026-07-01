package com.gme.pay.bff.alert.paging;

import com.gme.pay.bff.alert.OpsAlertEventHandler;
import com.gme.pay.bff.alert.OpsAlertStore;
import com.gme.pay.bff.alert.OpsAlertView;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Severity-threshold + dedupe/cooldown policy of {@link OpsPagingDispatcher}, driven through
 * {@link OpsAlertEventHandler} (the real consume path) with a recording {@link PagingPort}.
 */
class OpsPagingDispatcherTest {

    private static String alert(String type, String sev, String ref) {
        return "{\"eventType\":\"ops.alert\",\"alertType\":\"" + type + "\",\"severity\":\"" + sev
                + "\",\"subjectRef\":\"" + ref + "\",\"detail\":\"d\",\"occurredAt\":\"2026-07-02T00:00:00Z\"}";
    }

    @Test
    void criticalPagesAndRecordsDelivered() {
        OpsAlertStore store = new OpsAlertStore(200);
        TestPaging.RecordingPort port = new TestPaging.RecordingPort();
        OpsAlertEventHandler handler = new OpsAlertEventHandler(store, TestPaging.dispatcher(port, store));

        handler.handle("TXN-9", alert("STUCK_TXN", "CRITICAL", "TXN-9"));

        assertThat(port.pages).hasSize(1);
        assertThat(port.pages.get(0).subjectRef()).isEqualTo("TXN-9");
        OpsAlertView stored = store.recent(null, null, 0).get(0);
        assertThat(stored.paging()).isNotNull();
        assertThat(stored.paging().status()).isEqualTo("DELIVERED");
        assertThat(stored.paging().attempts()).isEqualTo(1);
    }

    @Test
    void infoIsStoredButNotPaged() {
        OpsAlertStore store = new OpsAlertStore(200);
        TestPaging.RecordingPort port = new TestPaging.RecordingPort();
        OpsAlertEventHandler handler = new OpsAlertEventHandler(store, TestPaging.dispatcher(port, store));

        handler.handle("X", alert("HEARTBEAT", "INFO", "X"));

        assertThat(port.pages).isEmpty();
        assertThat(store.recent(null, null, 0)).hasSize(1);
        assertThat(store.recent(null, null, 0).get(0).paging()).isNull();
    }

    @Test
    void warnPagesWhenThresholdLowered() {
        OpsAlertStore store = new OpsAlertStore(200);
        TestPaging.RecordingPort port = new TestPaging.RecordingPort();
        OpsPagingDispatcher d = TestPaging.dispatcher(port, store, "WARN",
                Duration.ofMinutes(15), Clock.systemUTC());
        OpsAlertEventHandler handler = new OpsAlertEventHandler(store, d);

        handler.handle("P_B", alert("FLOAT_LOW", "WARN", "P_B"));

        assertThat(port.pages).hasSize(1);
    }

    @Test
    void dedupeSuppressesRepeatWithinWindow() {
        OpsAlertStore store = new OpsAlertStore(200);
        TestPaging.RecordingPort port = new TestPaging.RecordingPort();
        OpsPagingDispatcher d = TestPaging.dispatcher(port, store, "CRITICAL",
                Duration.ofMinutes(15), Clock.systemUTC());
        OpsAlertEventHandler handler = new OpsAlertEventHandler(store, d);

        handler.handle("TXN-9", alert("STUCK_TXN", "CRITICAL", "TXN-9"));
        handler.handle("TXN-9", alert("STUCK_TXN", "CRITICAL", "TXN-9")); // re-fire

        assertThat(port.pages).hasSize(1); // second suppressed
        List<OpsAlertView> stored = store.recent(null, null, 0);
        assertThat(stored.get(0).paging().status()).isEqualTo("SUPPRESSED");
    }

    @Test
    void dedupeExpiresAfterWindow() {
        OpsAlertStore store = new OpsAlertStore(200);
        TestPaging.RecordingPort port = new TestPaging.RecordingPort();
        MutableClock clock = new MutableClock(Instant.parse("2026-07-02T00:00:00Z"));
        OpsPagingDispatcher d = TestPaging.dispatcher(port, store, "CRITICAL",
                Duration.ofMinutes(15), clock);
        OpsAlertEventHandler handler = new OpsAlertEventHandler(store, d);

        handler.handle("TXN-9", alert("STUCK_TXN", "CRITICAL", "TXN-9"));
        clock.advance(Duration.ofMinutes(16));
        handler.handle("TXN-9", alert("STUCK_TXN", "CRITICAL", "TXN-9"));

        assertThat(port.pages).hasSize(2); // window elapsed → paged again
    }

    /** Minimal advanceable clock for cooldown-window tests. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
    }
}
