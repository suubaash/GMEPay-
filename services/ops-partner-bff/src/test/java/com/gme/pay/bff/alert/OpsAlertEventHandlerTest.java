package com.gme.pay.bff.alert;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link OpsAlertEventHandler} + {@link OpsAlertStore}: a canonical
 * {@code OpsAlertPayload} JSON is consumed, stored, and queryable newest-first with
 * severity/type filtering; poison records are rejected (so the container dead-letters).
 */
class OpsAlertEventHandlerTest {

    private static final String FLOAT_LOW = """
            {"eventType":"ops.alert","alertType":"FLOAT_LOW","severity":"WARN",
             "subjectRef":"P_B","detail":"float below threshold","occurredAt":"2026-07-01T01:00:00Z"}""";
    private static final String STUCK = """
            {"eventType":"ops.alert","alertType":"STUCK_TXN","severity":"CRITICAL",
             "subjectRef":"TXN-9","detail":"stuck 30m","occurredAt":"2026-07-01T02:00:00Z"}""";

    @Test
    void consumesStoresAndReturnsNewestFirst() {
        OpsAlertStore store = new OpsAlertStore(200);
        OpsAlertEventHandler handler = new OpsAlertEventHandler(store);

        handler.handle("P_B", FLOAT_LOW);
        handler.handle("TXN-9", STUCK);

        List<OpsAlertView> all = store.recent(null, null, 0);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).alertType()).isEqualTo("STUCK_TXN"); // newest first
        assertThat(all.get(1).alertType()).isEqualTo("FLOAT_LOW");
    }

    @Test
    void filtersBySeverityAndType() {
        OpsAlertStore store = new OpsAlertStore(200);
        OpsAlertEventHandler handler = new OpsAlertEventHandler(store);
        handler.handle("P_B", FLOAT_LOW);
        handler.handle("TXN-9", STUCK);

        assertThat(store.recent("critical", null, 0))
                .singleElement().extracting(OpsAlertView::alertType).isEqualTo("STUCK_TXN");
        assertThat(store.recent(null, "FLOAT_LOW", 0))
                .singleElement().extracting(OpsAlertView::subjectRef).isEqualTo("P_B");
        assertThat(store.recent("INFO", null, 0)).isEmpty();
    }

    @Test
    void limitCaps() {
        OpsAlertStore store = new OpsAlertStore(200);
        OpsAlertEventHandler handler = new OpsAlertEventHandler(store);
        handler.handle("P_B", FLOAT_LOW);
        handler.handle("TXN-9", STUCK);
        assertThat(store.recent(null, null, 1)).hasSize(1);
    }

    @Test
    void capacityEvictsOldest() {
        OpsAlertStore store = new OpsAlertStore(1);
        OpsAlertEventHandler handler = new OpsAlertEventHandler(store);
        handler.handle("P_B", FLOAT_LOW);
        handler.handle("TXN-9", STUCK);
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.recent(null, null, 0)).singleElement()
                .extracting(OpsAlertView::alertType).isEqualTo("STUCK_TXN");
    }

    @Test
    void rejectsPoison() {
        OpsAlertStore store = new OpsAlertStore(200);
        OpsAlertEventHandler handler = new OpsAlertEventHandler(store);
        assertThatThrownBy(() -> handler.handle("k", "not-json"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.handle("k", "{\"eventType\":\"wrong.type\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.handle("k", "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.size()).isZero();
    }
}
