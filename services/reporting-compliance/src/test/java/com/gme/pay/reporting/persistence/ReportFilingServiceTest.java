package com.gme.pay.reporting.persistence;

import com.gme.pay.reporting.channel.FilingChannelRegistry;
import com.gme.pay.reporting.channel.FilingTransmissionResult;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence tests for {@link ReportFilingService} — idempotent filing creation, the
 * double-submit guard, and the GAP T5-2 filing-honesty guarantees: a lane with no
 * configured channel can never reach {@code TRANSMITTED} / {@code ACKNOWLEDGED}, while
 * generation and local validation keep working and stay visible.
 * Runs against H2 (PostgreSQL mode) with Flyway, so the V003 CHECK constraint is live.
 */
@DataJpaTest
@Import({ReportFilingService.class, FilingChannelRegistry.class})
class ReportFilingServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 15);

    @Autowired
    ReportFilingService service;

    @Autowired
    ReportFilingRepository repository;

    @Test
    @DisplayName("openFiling is idempotent: same natural key returns the same row")
    void openFiling_idempotent() {
        ReportFiling first = service.openFiling(ReportFiling.Lane.BOK, "FX1015", DATE);
        ReportFiling second = service.openFiling(ReportFiling.Lane.BOK, "FX1015", DATE);

        assertNotNull(first.getId());
        assertEquals(first.getId(), second.getId(),
                "Re-opening the same (lane, type, date) must reuse the existing filing");
        assertEquals(1, repository.count(), "No duplicate row may be created");
        assertEquals(ReportFiling.Status.PENDING.name(), first.getSubmissionStatus());
    }

    @Test
    @DisplayName("recordGenerated transitions PENDING -> GENERATED with counts")
    void recordGenerated_setsCountAndStatus() {
        ReportFiling filing = service.openFiling(ReportFiling.Lane.KOFIU, "CTR", DATE);
        ReportFiling generated = service.recordGenerated(filing.getId(), 7, "/out/kofiu.dat");

        assertEquals(ReportFiling.Status.GENERATED.name(), generated.getSubmissionStatus());
        assertEquals(7, generated.getRecordCount());
        assertEquals("/out/kofiu.dat", generated.getFilePath());
        assertNotNull(generated.getGeneratedAt());
    }

    // =========================================================================
    // Filing-status honesty (GAP T5-2)
    // =========================================================================

    @Test
    @DisplayName("no channel configured: recordTransmission is REFUSED for every lane")
    void recordTransmission_refusedWhenNoChannel() {
        for (ReportFiling.Lane lane : ReportFiling.Lane.values()) {
            ReportFiling filing = service.openFiling(lane, "T1", DATE);
            service.recordGenerated(filing.getId(), 3, "/out/report.csv");

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.recordTransmission(filing.getId(), "RX-001"),
                    "lane " + lane + " has no channel and must refuse a transmission record");
            assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
            assertTrue(ex.getMessage().contains("no live filing channel"),
                    "the refusal must name the missing channel: " + ex.getMessage());

            // The filing is untouched: still GENERATED, no receipt, no submitted_at.
            ReportFiling after = repository.findById(filing.getId()).orElseThrow();
            assertEquals(ReportFiling.Status.GENERATED.name(), after.getSubmissionStatus());
            assertNull(after.getExternalReceiptId());
            assertNull(after.getSubmittedAt());
        }
    }

    @Test
    @DisplayName("no channel configured: recordAcknowledgement is REFUSED (no fabricated ACCEPTED)")
    void recordAcknowledgement_refusedWhenNoChannel() {
        ReportFiling filing = service.openFiling(ReportFiling.Lane.HOMETAX, "ETAX", DATE);
        service.recordGenerated(filing.getId(), 1, null);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.recordAcknowledgement(filing.getId(), "NTS-FAKE-0001"));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
        assertNotEquals(ReportFiling.Status.ACKNOWLEDGED.name(),
                repository.findById(filing.getId()).orElseThrow().getSubmissionStatus());
    }

    @Test
    @DisplayName("settleAgainstChannel ends the run at NOT_FILED_CHANNEL_UNAVAILABLE with a reason")
    void settleAgainstChannel_recordsHonestTerminalState() {
        ReportFiling filing = service.openFiling(ReportFiling.Lane.KOFIU, "CTR", DATE);
        service.recordGenerated(filing.getId(), 5, "/out/kofiu.dat");
        service.recordValidated(filing.getId());

        ReportFiling settled = service.settleAgainstChannel(filing.getId());

        assertEquals(ReportFiling.Status.NOT_FILED_CHANNEL_UNAVAILABLE.name(),
                settled.getSubmissionStatus());
        assertNotNull(settled.getChannelUnavailableReason(),
                "the register must state why nothing was filed");
        assertTrue(settled.getChannelUnavailableReason().contains("kofiu.channel.endpoint"),
                "the reason must name the missing config: " + settled.getChannelUnavailableReason());
        assertNull(settled.getExternalReceiptId(), "nothing was sent, so no receipt id");
        assertNull(settled.getSubmittedAt(), "nothing was sent, so no submitted_at");
        // Real capability is preserved and still visible.
        assertEquals(5, settled.getRecordCount());
        assertEquals("/out/kofiu.dat", settled.getFilePath());
        assertNotNull(settled.getGeneratedAt());
    }

    @Test
    @DisplayName("generation + local validation still succeed and are reported as such")
    void generationAndValidation_stillRealAndReported() {
        ReportFiling filing = service.openFiling(ReportFiling.Lane.KOFIU, "STR", DATE);

        ReportFiling generated = service.recordGenerated(filing.getId(), 12, "/out/str.dat");
        assertEquals(ReportFiling.Status.GENERATED.name(), generated.getSubmissionStatus());
        assertEquals(12, generated.getRecordCount());

        ReportFiling validated = service.recordValidated(filing.getId());
        assertEquals(ReportFiling.Status.VALIDATED.name(), validated.getSubmissionStatus());
        assertEquals(12, validated.getRecordCount(), "validation must not lose the record count");
        assertEquals("/out/str.dat", validated.getFilePath());
    }

    @Test
    @DisplayName("recordTransmissionResult(notTransmitted) records the reason, never a receipt")
    void recordTransmissionResult_notTransmitted() {
        ReportFiling filing = service.openFiling(ReportFiling.Lane.KOFIU, "CTR", DATE);
        service.recordGenerated(filing.getId(), 2, "/out/kofiu.dat");

        ReportFiling after = service.recordTransmissionResult(filing.getId(),
                FilingTransmissionResult.notTransmitted("no KoFIU endpoint configured"));

        assertEquals(ReportFiling.Status.NOT_FILED_CHANNEL_UNAVAILABLE.name(),
                after.getSubmissionStatus());
        assertEquals("no KoFIU endpoint configured", after.getChannelUnavailableReason());
        assertNull(after.getExternalReceiptId());
    }

    @Test
    @DisplayName("with a live channel: transmit → acknowledge works, and double-transmit conflicts")
    void liveChannel_transmitThenAcknowledge_andDoubleSubmitRejected() {
        // A BOK channel is configured ONLY here, to prove the lifecycle still works once a
        // real channel exists. Nothing in the shipped configuration sets this.
        ReportFilingService live = new ReportFilingService(repository,
                new FilingChannelRegistry("sftp://bok.example/inbound", "", "", ""));

        ReportFiling filing = live.openFiling(ReportFiling.Lane.BOK, "FX1014", DATE);
        live.recordGenerated(filing.getId(), 3, "/out/fx1014.csv");

        ReportFiling transmitted = live.recordTransmission(filing.getId(), "RX-001");
        assertEquals(ReportFiling.Status.TRANSMITTED.name(), transmitted.getSubmissionStatus());
        assertEquals("RX-001", transmitted.getExternalReceiptId());
        assertNotNull(transmitted.getSubmittedAt());

        ApiException ex = assertThrows(ApiException.class,
                () -> live.recordTransmission(filing.getId(), "RX-002"));
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, ex.errorCode());

        ReportFiling acked = live.recordAcknowledgement(filing.getId(), "RX-001");
        assertEquals(ReportFiling.Status.ACKNOWLEDGED.name(), acked.getSubmissionStatus());
    }

    @Test
    @DisplayName("a live channel still cannot record a transmission without an authority receipt")
    void liveChannel_blankReceiptRejected() {
        ReportFilingService live = new ReportFilingService(repository,
                new FilingChannelRegistry("sftp://bok.example/inbound", "", "", ""));
        ReportFiling filing = live.openFiling(ReportFiling.Lane.BOK, "FX1015", DATE);
        live.recordGenerated(filing.getId(), 1, "/out/fx1015.csv");

        ApiException ex = assertThrows(ApiException.class,
                () -> live.recordTransmission(filing.getId(), "   "));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
    }

    @Test
    @DisplayName("acknowledgement cannot precede transmission even on a live channel")
    void liveChannel_ackBeforeTransmitRejected() {
        ReportFilingService live = new ReportFilingService(repository,
                new FilingChannelRegistry("sftp://bok.example/inbound", "", "", ""));
        ReportFiling filing = live.openFiling(ReportFiling.Lane.BOK, "FX1014", DATE);
        live.recordGenerated(filing.getId(), 1, "/out/fx1014.csv");

        ApiException ex = assertThrows(ApiException.class,
                () -> live.recordAcknowledgement(filing.getId(), "RX-9"));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
    }

    @Test
    @DisplayName("different report types on the same date are independent filings")
    void differentTypes_independentFilings() {
        ReportFiling ctr = service.openFiling(ReportFiling.Lane.KOFIU, "CTR", DATE);
        ReportFiling str = service.openFiling(ReportFiling.Lane.KOFIU, "STR", DATE);

        assertTrue(ctr.getId() != null && str.getId() != null);
        assertTrue(!ctr.getId().equals(str.getId()),
                "CTR and STR for the same date must be distinct filing rows");
        assertEquals(2, repository.count());
    }
}
