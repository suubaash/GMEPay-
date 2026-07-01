package com.gme.pay.bff.alert.paging;

import com.gme.pay.bff.alert.OpsAlertStore;
import com.gme.pay.bff.alert.OpsAlertView;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Test helpers for the paging path: a recording {@link PagingPort} spy and factory methods
 * for a dispatcher with a chosen severity threshold / clock, so unit tests can build the
 * handler graph without Spring.
 */
public final class TestPaging {

    private TestPaging() {
    }

    /** A {@link PagingPort} that records every {@link PageRequest} it is asked to deliver. */
    public static final class RecordingPort implements PagingPort {
        public final List<PageRequest> pages = new ArrayList<>();
        private final boolean deliver;

        public RecordingPort() {
            this(true);
        }

        public RecordingPort(boolean deliver) {
            this.deliver = deliver;
        }

        @Override
        public PageOutcome page(PageRequest request) {
            pages.add(request);
            return deliver ? PageOutcome.delivered("test") : PageOutcome.failed("test", "boom");
        }
    }

    /** Dispatcher with the default CRITICAL threshold and a 15m dedupe window. */
    public static OpsPagingDispatcher dispatcher(PagingPort port, OpsAlertStore store) {
        return dispatcher(port, store, "CRITICAL", Duration.ofMinutes(15), Clock.systemUTC());
    }

    public static OpsPagingDispatcher dispatcher(PagingPort port, OpsAlertStore store,
                                                 String minSeverity, Duration window, Clock clock) {
        return new OpsPagingDispatcher(port, store, minSeverity, window, "", clock);
    }

    /** Escalation test helper: mark a stored alert's occurredAt is not needed — expose ack. */
    public static OpsAlertView.Ack ack(String operator) {
        return new OpsAlertView.Ack(operator, null, "2026-07-02T00:00:00Z");
    }
}
