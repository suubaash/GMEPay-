package com.gme.pay.reporting.channel;

import com.gme.pay.reporting.persistence.ReportFiling;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FilingChannelRegistry} — the gate that decides whether a lane may
 * report a filing at all (GAP T5-2).
 *
 * <p>The shipped configuration must leave every lane without a channel, and the placeholder
 * NTS cert id {@code stub-cert-id} must NOT count as a configured credential (the audit
 * found {@code hometaxSet} reading true purely because that placeholder was non-null).
 */
class FilingChannelRegistryTest {

    @Test
    @DisplayName("shipped defaults: no lane has a live channel and each states why")
    void shippedDefaults_noLaneIsLive() {
        // Mirrors application.yml: blank BOK/KoFIU endpoints, placeholder NTS cert id.
        FilingChannelRegistry registry =
                new FilingChannelRegistry("", "", "https://api.hometax.go.kr", "stub-cert-id");

        for (ReportFiling.Lane lane : ReportFiling.Lane.values()) {
            assertFalse(registry.isLive(lane), lane + " must not be live on shipped config");
            assertNotNull(registry.unavailableReason(lane), lane + " must carry a reason");
            assertEquals(ReportFiling.Status.NOT_FILED_CHANNEL_UNAVAILABLE,
                    registry.statusOf(lane).reachableStatus(),
                    lane + " can reach no further than NOT_FILED_CHANNEL_UNAVAILABLE");
        }
    }

    @Test
    @DisplayName("noChannelsConfigured(): all three lanes unavailable, board has all lanes")
    void noChannelsConfigured_allUnavailable() {
        List<FilingChannelStatus> board = FilingChannelRegistry.noChannelsConfigured().statuses();

        assertEquals(3, board.size(), "the board must cover BOK, KOFIU and HOMETAX");
        assertTrue(board.stream().noneMatch(FilingChannelStatus::live));
        assertTrue(board.stream().allMatch(s -> s.reason() != null));
    }

    @Test
    @DisplayName("placeholder NTS cert id 'stub-cert-id' does not make Hometax live")
    void placeholderCertId_isNotACredential() {
        FilingChannelRegistry registry =
                new FilingChannelRegistry("", "", "https://api.hometax.go.kr", "stub-cert-id");

        assertFalse(registry.isLive(ReportFiling.Lane.HOMETAX));
        assertTrue(registry.unavailableReason(ReportFiling.Lane.HOMETAX).contains("placeholder"),
                "the reason must call out the placeholder cert id");
    }

    @Test
    @DisplayName("a Hometax base URL without a cert id is still not a channel")
    void hometaxBaseUrlAlone_isNotAChannel() {
        FilingChannelRegistry registry =
                new FilingChannelRegistry("", "", "https://api.hometax.go.kr", "");

        assertFalse(registry.isLive(ReportFiling.Lane.HOMETAX));
        assertTrue(registry.unavailableReason(ReportFiling.Lane.HOMETAX).contains("cert-id"));
    }

    @Test
    @DisplayName("lanes are independent: configuring BOK does not make KoFIU or Hometax live")
    void lanesAreIndependent() {
        FilingChannelRegistry registry = new FilingChannelRegistry(
                "sftp://bok.example/inbound", "", "", "");

        assertTrue(registry.isLive(ReportFiling.Lane.BOK));
        assertNull(registry.unavailableReason(ReportFiling.Lane.BOK));
        assertEquals(ReportFiling.Status.ACKNOWLEDGED,
                registry.statusOf(ReportFiling.Lane.BOK).reachableStatus());

        assertFalse(registry.isLive(ReportFiling.Lane.KOFIU));
        assertFalse(registry.isLive(ReportFiling.Lane.HOMETAX));
    }

    @Test
    @DisplayName("a real cert id plus base URL makes Hometax live")
    void realCertId_makesHometaxLive() {
        FilingChannelRegistry registry = new FilingChannelRegistry(
                "", "", "https://api.hometax.go.kr", "vault-doc-9f21-nts-issuer");

        assertTrue(registry.isLive(ReportFiling.Lane.HOMETAX));
        assertNull(registry.unavailableReason(ReportFiling.Lane.HOMETAX));
    }
}
