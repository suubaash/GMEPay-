package com.gme.pay.payment.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.gme.pay.contracts.events.OpsAlertPayload;
import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.payment.persistence.OpsAlertArchive;
import com.gme.pay.payment.persistence.OpsAlertEntity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * T3-3 — the alerting fan-out must be <b>unbreakable</b>. Each of the three legs (persist, publish,
 * notify) is called from inside the pay path, so a failure in any one of them must not stop the others
 * and must never propagate to the caller: the whole point of the gap fix is that a decline spike
 * reaches somebody, and a monitor that throws would be worse than the default-off state it replaced.
 */
class OpsAlertPipelineTest {

    private static final OpsAlertPayload ALERT = new OpsAlertPayload(
            OpsAlertPayload.EVENT_TYPE, "DECLINE_SPIKE", "CRITICAL", "PTN-ACME",
            "declineRate=1.00 (25/25) over 60s > threshold=0.50", "2026-07-28T18:30:00Z");

    /** Captures published events. */
    private static final class RecordingPublisher implements EventPublisher {
        private final List<DomainEvent> published = new ArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            published.add(event);
        }
    }

    /** Captures deliveries and can be told to fail or blow up. */
    private static final class RecordingSink implements AlertSink {
        private final List<OpsAlertPayload> delivered = new ArrayList<>();
        private AlertDelivery outcome = AlertDelivery.delivered("test");
        private RuntimeException boom;

        @Override
        public AlertDelivery deliver(OpsAlertPayload alert) {
            delivered.add(alert);
            if (boom != null) {
                throw boom;
            }
            return outcome;
        }
    }

    @Test
    @DisplayName("happy path: persisted, published, and pushed to the sink — in that order")
    void allThreeLegsRun() {
        OpsAlertArchive archive = Mockito.mock(OpsAlertArchive.class);
        Mockito.when(archive.record(ALERT)).thenReturn(7L);
        RecordingPublisher publisher = new RecordingPublisher();
        RecordingSink sink = new RecordingSink();

        new OpsAlertPipeline(publisher, archive, sink).emit(ALERT);

        // Persisted FIRST — the durable record must exist before anything that can fail over a network.
        Mockito.verify(archive).record(ALERT);
        assertThat(publisher.published).hasSize(1);
        assertThat(publisher.published.get(0).eventType()).isEqualTo(OpsAlertPayload.EVENT_TYPE);
        assertThat(sink.delivered).containsExactly(ALERT);
        // The delivery outcome lands on the same row as the alert.
        Mockito.verify(archive)
                .recordNotification(7L, OpsAlertEntity.NOTIFY_DELIVERED, "test", null);
    }

    @Test
    @DisplayName("a sink that reports failure is recorded, not propagated")
    void failingSinkIsRecorded() {
        OpsAlertArchive archive = Mockito.mock(OpsAlertArchive.class);
        Mockito.when(archive.record(ALERT)).thenReturn(9L);
        RecordingSink sink = new RecordingSink();
        sink.outcome = AlertDelivery.failed("webhook", "http 503");

        OpsAlertPipeline pipeline = new OpsAlertPipeline(new RecordingPublisher(), archive, sink);

        assertThatCode(() -> pipeline.emit(ALERT)).doesNotThrowAnyException();
        Mockito.verify(archive)
                .recordNotification(9L, OpsAlertEntity.NOTIFY_FAILED, "webhook", "http 503");
    }

    @Test
    @DisplayName("a sink that THROWS does not break the monitor and is still recorded as FAILED")
    void throwingSinkDoesNotBreakTheMonitor() {
        OpsAlertArchive archive = Mockito.mock(OpsAlertArchive.class);
        Mockito.when(archive.record(ALERT)).thenReturn(11L);
        RecordingPublisher publisher = new RecordingPublisher();
        RecordingSink sink = new RecordingSink();
        sink.boom = new IllegalStateException("connection reset");

        OpsAlertPipeline pipeline = new OpsAlertPipeline(publisher, archive, sink);

        assertThatCode(() -> pipeline.emit(ALERT)).doesNotThrowAnyException();
        // The other two legs already completed before the sink was reached.
        Mockito.verify(archive).record(ALERT);
        assertThat(publisher.published).hasSize(1);
        Mockito.verify(archive).recordNotification(
                Mockito.eq(11L), Mockito.eq(OpsAlertEntity.NOTIFY_FAILED),
                Mockito.eq("unknown"), Mockito.contains("connection reset"));
    }

    @Test
    @DisplayName("a sink returning null is treated as a failure, not an NPE")
    void nullOutcomeIsHandled() {
        OpsAlertArchive archive = Mockito.mock(OpsAlertArchive.class);
        Mockito.when(archive.record(ALERT)).thenReturn(13L);
        AlertSink nullSink = alert -> null;

        OpsAlertPipeline pipeline =
                new OpsAlertPipeline(new RecordingPublisher(), archive, nullSink);

        assertThatCode(() -> pipeline.emit(ALERT)).doesNotThrowAnyException();
        Mockito.verify(archive).recordNotification(
                13L, OpsAlertEntity.NOTIFY_FAILED, "unknown", "sink returned null");
    }

    @Test
    @DisplayName("a publisher that throws still leaves the alert persisted and notified")
    void publisherFailureDoesNotStopPersistOrNotify() {
        OpsAlertArchive archive = Mockito.mock(OpsAlertArchive.class);
        Mockito.when(archive.record(ALERT)).thenReturn(17L);
        RecordingSink sink = new RecordingSink();
        EventPublisher exploding = event -> {
            throw new IllegalStateException("no broker");
        };

        OpsAlertPipeline pipeline = new OpsAlertPipeline(exploding, archive, sink);

        assertThatCode(() -> pipeline.emit(ALERT)).doesNotThrowAnyException();
        Mockito.verify(archive).record(ALERT);
        assertThat(sink.delivered).containsExactly(ALERT);
    }

    @Test
    @DisplayName("a failed durable write (null id) still publishes and notifies")
    void archiveFailureStillAlerts() {
        OpsAlertArchive archive = Mockito.mock(OpsAlertArchive.class);
        Mockito.when(archive.record(ALERT)).thenReturn(null); // DB down: record() swallows and returns null
        RecordingPublisher publisher = new RecordingPublisher();
        RecordingSink sink = new RecordingSink();

        assertThatCode(() -> new OpsAlertPipeline(publisher, archive, sink).emit(ALERT))
                .doesNotThrowAnyException();

        assertThat(publisher.published).hasSize(1);
        assertThat(sink.delivered).containsExactly(ALERT);
    }

    @Test
    @DisplayName("publish-only constructor (no archive) works and uses the log sink")
    void publishOnlyConstructor() {
        RecordingPublisher publisher = new RecordingPublisher();

        assertThatCode(() -> new OpsAlertPipeline(publisher).emit(ALERT)).doesNotThrowAnyException();

        assertThat(publisher.published).hasSize(1);
    }
}
