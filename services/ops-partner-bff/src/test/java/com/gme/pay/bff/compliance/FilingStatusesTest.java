package com.gme.pay.bff.compliance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GAP T5-2: the BFF may restate a filing status but never invent one. Unit-level counterpart to
 * {@code RestReportingClientTest}.
 */
class FilingStatusesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "PENDING", "GENERATED", "VALIDATED", "NOT_FILED_CHANNEL_UNAVAILABLE",
            "TRANSMITTED", "ACKNOWLEDGED", "FAILED"})
    @DisplayName("the honest vocabulary passes through unchanged")
    void honestVocabularyPassesThrough(String status) {
        assertThat(FilingStatuses.fromUpstream(status)).isEqualTo(status);
        assertThat(FilingStatuses.reasonFor(status, null)).isNull();
    }

    @Test
    @DisplayName("an upstream reason always wins")
    void upstreamReasonWins() {
        assertThat(FilingStatuses.reasonFor("NOT_FILED_CHANNEL_UNAVAILABLE", "bok endpoint blank"))
                .isEqualTo("bok endpoint blank");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUBMITTED", "CONFIRMED", "ACCEPTED", "FILED", "submitted"})
    @DisplayName("the retired success vocabulary is reclassified to NOT_FILED_CHANNEL_UNAVAILABLE")
    void retiredSuccessIsReclassified(String status) {
        assertThat(FilingStatuses.fromUpstream(status))
                .isEqualTo(FilingStatuses.NOT_FILED_CHANNEL_UNAVAILABLE);
        assertThat(FilingStatuses.reasonFor(status, null))
                .contains("retired status").contains("reclassified");
    }

    @Test
    @DisplayName("an absent status is UNKNOWN with an explanation — never a success, never GENERATED")
    void absentStatusIsUnknown() {
        for (String raw : new String[]{null, "", "   "}) {
            assertThat(FilingStatuses.fromUpstream(raw)).isEqualTo(FilingStatuses.UNKNOWN);
            assertThat(FilingStatuses.reasonFor(raw, null)).contains("did not report a filing_status");
        }
    }

    @Test
    @DisplayName("an unrecognised status is UNKNOWN and names the raw value")
    void unrecognisedStatusIsUnknown() {
        assertThat(FilingStatuses.fromUpstream("ALL_GOOD")).isEqualTo(FilingStatuses.UNKNOWN);
        assertThat(FilingStatuses.reasonFor("ALL_GOOD", null)).contains("'ALL_GOOD'");
    }

    @Test
    @DisplayName("whitespace and case are normalised, not treated as unknown")
    void caseAndWhitespaceNormalised() {
        assertThat(FilingStatuses.fromUpstream("  generated ")).isEqualTo("GENERATED");
    }
}
