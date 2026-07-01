package com.gme.pay.bff.alert;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.contracts.events.OpsAlertPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Handles one {@code ops.alert} event consumed from {@code gmepay.ops.alert}: deserializes the
 * canonical {@link OpsAlertPayload} and appends it to the {@link OpsAlertStore} so it surfaces in
 * the control tower and {@code GET /v1/admin/ops/alerts}. Sibling of the Kafka listener (which owns
 * only the transport); this is the pure, unit-testable handler.
 *
 * <p><b>Poison handling.</b> Unparseable JSON or a wrong/missing {@code eventType} raises
 * {@link IllegalArgumentException}; the Kafka error handler retries then dead-letters, so the
 * partition never wedges on a bad record.
 */
@Service
public class OpsAlertEventHandler {

    /** Event type this handler accepts; anything else on the topic is poison. */
    public static final String EVENT_TYPE = OpsAlertPayload.EVENT_TYPE;

    private static final Logger log = LoggerFactory.getLogger(OpsAlertEventHandler.class);

    private final OpsAlertStore store;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public OpsAlertEventHandler(OpsAlertStore store) {
        this.store = Objects.requireNonNull(store, "store required");
    }

    /**
     * Deserialize + store one consumed record.
     *
     * @param recordKey the Kafka record key (publisher sets it to the subjectRef); logging only
     * @param payload   the raw JSON record value (a serialised {@link OpsAlertPayload})
     * @return the stored view
     * @throws IllegalArgumentException if the record is poison (invalid JSON / eventType)
     */
    public OpsAlertView handle(String recordKey, String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("ops.alert record has an empty payload");
        }
        OpsAlertPayload event;
        try {
            event = objectMapper.readValue(payload, OpsAlertPayload.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("ops.alert payload is not valid JSON", e);
        }
        if (event == null) {
            throw new IllegalArgumentException("ops.alert payload deserialized to null");
        }
        if (!EVENT_TYPE.equals(event.eventType())) {
            throw new IllegalArgumentException(
                    "unexpected eventType on ops.alert topic: " + event.eventType());
        }
        OpsAlertView stored = store.add(event);
        log.info("ops.alert consumed: type={} severity={} subjectRef={} (key={})",
                event.alertType(), event.severity(), event.subjectRef(), recordKey);
        return stored;
    }
}
