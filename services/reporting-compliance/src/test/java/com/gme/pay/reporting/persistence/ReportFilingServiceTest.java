package com.gme.pay.reporting.persistence;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence tests for {@link ReportFilingService} — idempotent filing creation
 * and the double-submit guard. Runs against H2 (PostgreSQL mode) with Flyway.
 */
@DataJpaTest
@Import(ReportFilingService.class)
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

    @Test
    @DisplayName("recordSubmission rejects a second submission (idempotency conflict)")
    void recordSubmission_doubleSubmitRejected() {
        ReportFiling filing = service.openFiling(ReportFiling.Lane.BOK, "FX1014", DATE);
        service.recordGenerated(filing.getId(), 3, "/out/fx1014.csv");

        ReportFiling submitted = service.recordSubmission(filing.getId(), "RX-001");
        assertEquals(ReportFiling.Status.SUBMITTED.name(), submitted.getSubmissionStatus());
        assertEquals("RX-001", submitted.getExternalReceiptId());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.recordSubmission(filing.getId(), "RX-002"));
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, ex.errorCode());
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
