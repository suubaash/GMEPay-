package com.gme.pay.settlement.transmission;

import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.persistence.SettlementBatchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * GAP T4-5: <b>a batch that was never transmitted must not be able to present as transmitted.</b>
 *
 * <p>Two independent layers are pinned here, because either alone would be bypassable:
 * <ol>
 *   <li>the {@link SettlementTransmissionChannelRegistry} classification — what counts as a real
 *       channel, and specifically that a local path (the {@code LocalDirSftpTransport} temp-directory
 *       case) and a placeholder credential do NOT;</li>
 *   <li>the {@link SettlementTransmissionRecorder} guard — the only writer of
 *       {@link SettlementTransmissionState#TRANSMITTED} refuses without a live channel, so the state
 *       cannot be reached by a future change without that change failing loudly.</li>
 * </ol>
 */
class SettlementTransmissionHonestyTest {

    private static SettlementBatchEntity generatedBatch() {
        SettlementBatchEntity b = new SettlementBatchEntity(
                "ZP0061-20260615-MORNING", "ZEROPAY", LocalDate.of(2026, 6, 15),
                "GENERATED", new BigDecimal("84296"), "KRW", Instant.parse("2026-06-15T20:00:00Z"));
        b.setFileType("ZP0061");
        b.setSettlementWindow("MORNING");
        return b;
    }

    @Nested
    @DisplayName("channel registry: what does NOT count as a transmission channel")
    class Registry {

        @Test
        @DisplayName("no configuration at all -> unavailable, with a reason naming the missing property")
        void blankEndpoint() {
            SettlementTransmissionChannelRegistry r =
                    SettlementTransmissionChannelRegistry.noChannelConfigured();

            assertThat(r.isLive()).isFalse();
            assertThat(r.reachableState())
                    .isEqualTo(SettlementTransmissionState.NOT_TRANSMITTED_CHANNEL_UNAVAILABLE);
            assertThat(r.unavailableReason())
                    .contains("gmepay.settlement.transmission.channel.endpoint")
                    .contains("never transmitted");
            assertThat(r.status().channelId()).isNull();
        }

        @Test
        @DisplayName("a LOCAL path is not a channel — this is the LocalDirSftpTransport case")
        void localEndpointsAreNotChannels() {
            for (String endpoint : new String[]{
                    "file:///tmp/zeropay-out", "/tmp/zeropay", "local-dir", "./out", "classpath:out",
                    "TEMP/zeropay"}) {
                SettlementTransmissionChannelRegistry r =
                        new SettlementTransmissionChannelRegistry(endpoint, "vault:zeropay-sftp-key");
                assertThat(r.isLive())
                        .as("endpoint '%s' must not count as a real channel", endpoint)
                        .isFalse();
                assertThat(r.unavailableReason()).contains("local path");
            }
        }

        @Test
        @DisplayName("an endpoint with no credential, or a placeholder credential, is not a channel")
        void credentialsMustBeReal() {
            assertThat(new SettlementTransmissionChannelRegistry("sftp://zp.kftc.or.kr", "").isLive())
                    .isFalse();
            for (String credential : new String[]{"stub", "CHANGEME", "todo", "placeholder-key",
                    "REPLACE-ME", "dummy", "test-key-1"}) {
                SettlementTransmissionChannelRegistry r =
                        new SettlementTransmissionChannelRegistry("sftp://zp.kftc.or.kr", credential);
                assertThat(r.isLive())
                        .as("credential '%s' must not count as installed", credential)
                        .isFalse();
                assertThat(r.unavailableReason()).contains("credential");
            }
        }

        @Test
        @DisplayName("a real endpoint + a real credential is the ONLY way to be live")
        void liveOnlyWithRealConfig() {
            SettlementTransmissionChannelRegistry r = new SettlementTransmissionChannelRegistry(
                    "sftp://zp.kftc.or.kr:22/settlement", "vault:zeropay-sftp-key");

            assertThat(r.isLive()).isTrue();
            assertThat(r.reachableState()).isEqualTo(SettlementTransmissionState.TRANSMITTED);
            assertThat(r.unavailableReason()).isNull();
            assertThat(r.status().channelId()).isEqualTo("sftp://zp.kftc.or.kr:22/settlement");
        }
    }

    @Nested
    @DisplayName("recorder: the only writer of TRANSMITTED, and it refuses")
    class Recorder {

        private final SettlementBatchRepository repo = mock(SettlementBatchRepository.class);

        @Test
        @DisplayName("with no channel: a generated batch is STAMPED not-transmitted, with the reason")
        void stampsChannelUnavailable() {
            SettlementTransmissionChannelRegistry channels =
                    SettlementTransmissionChannelRegistry.noChannelConfigured();
            SettlementTransmissionRecorder recorder =
                    new SettlementTransmissionRecorder(channels, repo);
            SettlementBatchEntity batch = generatedBatch();

            recorder.stampReachableState(batch);

            assertThat(batch.getTransmissionState())
                    .isEqualTo(SettlementTransmissionState.NOT_TRANSMITTED_CHANNEL_UNAVAILABLE);
            assertThat(batch.getTransmissionDetail()).isEqualTo(channels.unavailableReason());
            assertThat(batch.getTransmittedAt()).as("never sent => no send timestamp").isNull();
            assertThat(batch.getTransmissionChannel()).isNull();
            // The stamp is part of the caller's generation transaction; the recorder must not save.
            verifyNoInteractions(repo);
        }

        @Test
        @DisplayName("with no channel: recording a transmission is REFUSED, not silently accepted")
        void refusesToRecordTransmissionWithoutChannel() {
            SettlementTransmissionRecorder recorder = new SettlementTransmissionRecorder(
                    SettlementTransmissionChannelRegistry.noChannelConfigured(), repo);
            SettlementBatchEntity batch = generatedBatch();

            assertThatThrownBy(() -> recorder.recordTransmitted(batch, Instant.now()))
                    .isInstanceOf(TransmissionChannelUnavailableException.class)
                    .hasMessageContaining("refusing to record")
                    .hasMessageContaining("ZP0061-20260615-MORNING");

            assertThat(batch.getTransmissionState().isSent()).isFalse();
            assertThat(batch.getTransmittedAt()).isNull();
            verifyNoInteractions(repo);
        }

        @Test
        @DisplayName("with no channel: a transmission FAILURE is also refused — we never tried")
        void refusesToRecordFailureWithoutChannel() {
            SettlementTransmissionRecorder recorder = new SettlementTransmissionRecorder(
                    SettlementTransmissionChannelRegistry.noChannelConfigured(), repo);

            assertThatThrownBy(() -> recorder.recordTransmissionFailure(generatedBatch(), "timeout"))
                    .isInstanceOf(TransmissionChannelUnavailableException.class)
                    .hasMessageContaining("failed to transmit");
        }

        @Test
        @DisplayName("with a live channel: a transmission is recorded with instant + named channel")
        void recordsTransmissionWithLiveChannel() {
            SettlementTransmissionChannelRegistry channels = new SettlementTransmissionChannelRegistry(
                    "sftp://zp.kftc.or.kr:22/settlement", "vault:zeropay-sftp-key");
            when(repo.save(any(SettlementBatchEntity.class))).thenAnswer(i -> i.getArgument(0));
            SettlementTransmissionRecorder recorder =
                    new SettlementTransmissionRecorder(channels, repo);
            Instant at = Instant.parse("2026-06-15T21:00:00Z");

            SettlementBatchEntity saved = recorder.recordTransmitted(generatedBatch(), at);

            assertThat(saved.getTransmissionState()).isEqualTo(SettlementTransmissionState.TRANSMITTED);
            assertThat(saved.getTransmittedAt()).isEqualTo(at);
            assertThat(saved.getTransmissionChannel()).isEqualTo("sftp://zp.kftc.or.kr:22/settlement");
            assertThat(saved.getTransmissionDetail()).isNull();
        }

        @Test
        @DisplayName("stamping never downgrades a real transmission")
        void stampDoesNotClobberARealTransmission() {
            SettlementTransmissionChannelRegistry live = new SettlementTransmissionChannelRegistry(
                    "sftp://zp.kftc.or.kr:22/settlement", "vault:zeropay-sftp-key");
            when(repo.save(any(SettlementBatchEntity.class))).thenAnswer(i -> i.getArgument(0));
            SettlementBatchEntity batch = new SettlementTransmissionRecorder(live, repo)
                    .recordTransmitted(generatedBatch(), Instant.parse("2026-06-15T21:00:00Z"));

            // Channel later removed from configuration; the historical transmission stands.
            new SettlementTransmissionRecorder(
                    SettlementTransmissionChannelRegistry.noChannelConfigured(), repo)
                    .stampReachableState(batch);

            assertThat(batch.getTransmissionState()).isEqualTo(SettlementTransmissionState.TRANSMITTED);
            assertThat(batch.getTransmittedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("entity: transmitted_at cannot exist without TRANSMITTED, and vice versa")
    class EntityInvariant {

        @Test
        @DisplayName("a fresh batch is NOT_TRANSMITTED, never null-and-ambiguous")
        void defaultsToNotTransmitted() {
            assertThat(generatedBatch().getTransmissionState())
                    .isEqualTo(SettlementTransmissionState.NOT_TRANSMITTED);
        }

        @Test
        @DisplayName("markTransmitted demands both an instant and a named channel")
        void markTransmittedDemandsEvidence() {
            assertThatThrownBy(() -> generatedBatch().markTransmitted(null, "sftp://zp"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not evidence");
            assertThatThrownBy(() -> generatedBatch().markTransmitted(Instant.now(), " "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must name the channel");
        }

        @Test
        @DisplayName("markNotTransmitted refuses TRANSMITTED — there is one door into that state")
        void markNotTransmittedRefusesSentState() {
            assertThatThrownBy(() -> generatedBatch()
                    .markNotTransmitted(SettlementTransmissionState.TRANSMITTED, "sent, honest"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("markTransmitted");
        }

        @Test
        @DisplayName("moving back to not-transmitted clears the send timestamp and channel")
        void notTransmittedClearsSendEvidence() {
            SettlementBatchEntity b = generatedBatch();
            b.markTransmitted(Instant.parse("2026-06-15T21:00:00Z"), "sftp://zp");

            b.markNotTransmitted(SettlementTransmissionState.TRANSMISSION_FAILED, "connection reset");

            assertThat(b.getTransmittedAt()).isNull();
            assertThat(b.getTransmissionChannel()).isNull();
            assertThat(b.getTransmissionState())
                    .isEqualTo(SettlementTransmissionState.TRANSMISSION_FAILED);
        }
    }
}
