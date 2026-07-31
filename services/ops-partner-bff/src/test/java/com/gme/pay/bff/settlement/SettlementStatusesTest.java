package com.gme.pay.bff.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GAP T4-5: the BFF must not be able to state a settlement status upstream did not give it, and must
 * not be able to state a transmission at all unless upstream said so in its own vocabulary.
 */
class SettlementStatusesTest {

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "GENERATED", "TRANSMITTED", "RECEIVED", "RECONCILED", "ERROR"})
    @DisplayName("the real lifecycle vocabulary passes through unchanged")
    void honestLifecyclePassesThrough(String status) {
        assertThat(SettlementStatuses.lifecycleFromUpstream(status)).isEqualTo(status);
        assertThat(SettlementStatuses.lifecycleFromUpstream(status.toLowerCase())).isEqualTo(status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "SETTLED", "PAID", "SUCCESS", "DONE", "FINAL"})
    @DisplayName("the invented vocabulary is reclassified to UNKNOWN, never echoed")
    void inventedStatusesBecomeUnknown(String status) {
        assertThat(SettlementStatuses.lifecycleFromUpstream(status))
                .isEqualTo(SettlementStatuses.UNKNOWN);
        assertThat(SettlementStatuses.reasonFor(status, "NOT_TRANSMITTED", null))
                .contains("invented downstream");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "SOMETHING_ELSE"})
    @DisplayName("absent or unrecognised is UNKNOWN — never a success")
    void unknownIsNeverASuccess(String status) {
        assertThat(SettlementStatuses.lifecycleFromUpstream(status))
                .isEqualTo(SettlementStatuses.UNKNOWN);
        assertThat(SettlementStatuses.transmissionFromUpstream(status))
                .isEqualTo(SettlementStatuses.UNKNOWN);
        assertThat(SettlementStatuses.isTransmitted(status)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_TRANSMITTED", "NOT_TRANSMITTED_CHANNEL_UNAVAILABLE",
            "TRANSMISSION_FAILED", "TRANSMITTED"})
    @DisplayName("the real transmission vocabulary passes through unchanged")
    void honestTransmissionPassesThrough(String state) {
        assertThat(SettlementStatuses.transmissionFromUpstream(state)).isEqualTo(state);
    }

    @Test
    @DisplayName("ONLY the literal TRANSMITTED counts as sent")
    void onlyTransmittedIsSent() {
        assertThat(SettlementStatuses.isTransmitted("TRANSMITTED")).isTrue();
        assertThat(SettlementStatuses.isTransmitted("transmitted")).isTrue();
        for (String not : new String[]{"NOT_TRANSMITTED", "NOT_TRANSMITTED_CHANNEL_UNAVAILABLE",
                "TRANSMISSION_FAILED", "SENT", "SENT_PROBABLY", "COMPLETED", null, ""}) {
            assertThat(SettlementStatuses.isTransmitted(not))
                    .as("'%s' must not read as transmitted", not)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("the upstream reason always wins over our own explanation")
    void upstreamReasonWins() {
        assertThat(SettlementStatuses.reasonFor("COMPLETED", "SENT_PROBABLY", "no channel configured"))
                .isEqualTo("no channel configured");
    }

    @Test
    @DisplayName("silence about transmission is explained, not left blank")
    void silenceIsExplained() {
        assertThat(SettlementStatuses.reasonFor("RECONCILED", null, null))
                .contains("did not report a transmissionState")
                .contains("nothing may be assumed transmitted");
    }

    @Test
    @DisplayName("an unrecognised transmission state is explained as never meaning transmitted")
    void unrecognisedTransmissionIsExplained() {
        assertThat(SettlementStatuses.reasonFor("RECONCILED", "SENT_PROBABLY", null))
                .contains("SENT_PROBABLY")
                .contains("never means transmitted");
    }

    @Test
    @DisplayName("an honest pair needs no excuse")
    void honestPairHasNoReason() {
        assertThat(SettlementStatuses.reasonFor("RECONCILED", "TRANSMITTED", null)).isNull();
    }
}
