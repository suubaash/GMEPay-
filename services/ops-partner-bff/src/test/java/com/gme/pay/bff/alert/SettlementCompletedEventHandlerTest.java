package com.gme.pay.bff.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.gme.pay.contracts.events.OpsAlertPayload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** settlement.completed → INFO ops alert (subjectRef = batchId); poison rules mirror ops.alert. */
class SettlementCompletedEventHandlerTest {

    private final OpsAlertStore store = mock(OpsAlertStore.class);
    private final SettlementCompletedEventHandler handler = new SettlementCompletedEventHandler(store);

    @Test
    @DisplayName("stores the batch as an INFO SETTLEMENT_COMPLETED alert with a money summary")
    void storesInfoAlert() {
        String payload = """
                {"eventType":"settlement.completed","aggregateId":"41","batchId":"41",
                 "fileType":"ZP0061","settlementWindow":"MORNING","businessDate":"2026-07-05",
                 "netSettlementAmount":"84296","totalCurrency":"KRW","transactionCount":2,
                 "fileChecksum":"abc123","occurredAt":"2026-07-05T05:00:00Z","schemaVersion":1}
                """;

        handler.handle("41", payload);

        ArgumentCaptor<OpsAlertPayload> alert = ArgumentCaptor.forClass(OpsAlertPayload.class);
        verify(store).add(alert.capture());
        assertEquals("SETTLEMENT_COMPLETED", alert.getValue().alertType());
        assertEquals("INFO", alert.getValue().severity());
        assertEquals("41", alert.getValue().subjectRef());
        assertTrue(alert.getValue().detail().contains("ZP0061"));
        assertTrue(alert.getValue().detail().contains("84296"));
        assertTrue(alert.getValue().detail().contains("abc123"));
    }

    @Test
    @DisplayName("wrong eventType on the topic is poison")
    void wrongEventTypeIsPoison() {
        assertThrows(IllegalArgumentException.class,
                () -> handler.handle("k", "{\"eventType\":\"payment.approved\"}"));
    }

    @Test
    @DisplayName("empty payload is poison")
    void emptyPayloadIsPoison() {
        assertThrows(IllegalArgumentException.class, () -> handler.handle("k", " "));
    }
}
