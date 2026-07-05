package com.gme.pay.contracts.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CTO go-live gate for the event bus, executable: no terminal money transition may emit an
 * event no consumer receives, every event is versioned, and the catalogue itself stays coherent.
 */
class EventCatalogTest {

    @Test
    @DisplayName("every MONEY-TERMINAL event has at least one consumer (no dark money events)")
    void moneyTerminalEventsHaveConsumers() {
        for (EventCatalog.EventEntry e : EventCatalog.EVENTS) {
            if (e.moneyTerminal()) {
                assertFalse(e.consumers().isEmpty(),
                        "money-terminal event '" + e.eventType() + "' has NO consumer — a terminal "
                                + "money transition would emit an event nothing receives. Wire a "
                                + "consumer (and list it here) before shipping the producer.");
            }
        }
    }

    @Test
    @DisplayName("every event is versioned (schemaVersion >= 1) with at least one producer")
    void everyEventVersionedAndProduced() {
        for (EventCatalog.EventEntry e : EventCatalog.EVENTS) {
            assertTrue(e.schemaVersion() >= 1, e.eventType() + " must declare schemaVersion >= 1");
            assertFalse(e.producers().isEmpty(), e.eventType() + " must declare its producer(s)");
            assertTrue(e.topic().equals("gmepay." + e.eventType()), "topic naming convention");
        }
    }

    @Test
    @DisplayName("catalogue has no duplicate event types")
    void noDuplicateEventTypes() {
        Set<String> seen = new HashSet<>();
        for (EventCatalog.EventEntry e : EventCatalog.EVENTS) {
            assertTrue(seen.add(e.eventType()), "duplicate catalogue entry: " + e.eventType());
        }
        assertEquals(seen, EventCatalog.knownEventTypes());
    }

    @Test
    @DisplayName("canonical payload contracts are catalogued")
    void canonicalPayloadsCatalogued() {
        Set<String> types = EventCatalog.knownEventTypes();
        assertTrue(types.contains(PaymentApprovedPayload.EVENT_TYPE));
        assertTrue(types.contains(PaymentReversedPayload.EVENT_TYPE));
        assertTrue(types.contains(OpsAlertPayload.EVENT_TYPE));
    }
}
