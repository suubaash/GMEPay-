package com.gme.pay.bff.alert;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.contracts.events.OpsAlertPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Turns one consumed {@code settlement.completed} event into an INFO ops alert so the batch is
 * visible on the ops surface (subjectRef = batchId, detail = file/window/net/checksum summary).
 * Poison rules mirror {@link OpsAlertEventHandler}: empty/invalid JSON or a wrong eventType
 * throws so the container retries and finally dead-letters.
 */
@Service
public class SettlementCompletedEventHandler {

    /** Event type this handler accepts; anything else on the topic is poison. */
    public static final String EVENT_TYPE = "settlement.completed";

    /** alertType under which batches appear in the ops Alerts surface. */
    public static final String ALERT_TYPE = "SETTLEMENT_COMPLETED";

    private static final Logger log = LoggerFactory.getLogger(SettlementCompletedEventHandler.class);

    private final OpsAlertStore store;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public SettlementCompletedEventHandler(OpsAlertStore store) {
        this.store = Objects.requireNonNull(store, "store required");
    }

    public OpsAlertView handle(String recordKey, String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("settlement.completed record has an empty payload");
        }
        JsonNode event;
        try {
            event = objectMapper.readTree(payload);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("settlement.completed payload is not valid JSON", e);
        }
        if (!EVENT_TYPE.equals(event.path("eventType").asText())) {
            throw new IllegalArgumentException(
                    "unexpected eventType on settlement.completed topic: " + event.path("eventType").asText());
        }
        String batchId = event.path("batchId").asText(event.path("aggregateId").asText(""));
        if (batchId.isBlank()) {
            throw new IllegalArgumentException("settlement.completed payload carries no batchId/aggregateId");
        }
        String detail = "settlement batch generated: " + event.path("fileType").asText("?")
                + " " + event.path("settlementWindow").asText("?")
                + " " + event.path("businessDate").asText("?")
                + " net " + event.path("netSettlementAmount").asText("?")
                + " " + event.path("totalCurrency").asText("")
                + " (" + event.path("transactionCount").asText("0") + " txns, checksum "
                + event.path("fileChecksum").asText("-") + ")";

        OpsAlertView stored = store.add(new OpsAlertPayload(
                OpsAlertPayload.EVENT_TYPE,
                ALERT_TYPE,
                "INFO",
                batchId,
                detail,
                event.path("occurredAt").asText(null)));
        log.info("settlement.completed consumed: batchId={} (key={})", batchId, recordKey);
        return stored;
    }
}
