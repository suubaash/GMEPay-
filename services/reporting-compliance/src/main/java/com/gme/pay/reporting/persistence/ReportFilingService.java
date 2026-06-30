package com.gme.pay.reporting.persistence;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Idempotent lifecycle manager for {@link ReportFiling} records.
 *
 * <p>Every regulatory report run (BOK / KoFIU / Hometax) opens — or re-opens — a
 * single {@code report_filing} row keyed by {@code (lane, reportType, reportDate)}.
 * A second run for the same key does NOT create a duplicate: {@link #openFiling}
 * returns the existing row when one is present, so a scheduler re-fire is safe.
 *
 * <p>The terminal channel submission ({@link #recordSubmission}) is guarded against
 * double-submit: attempting to submit a filing that is already SUBMITTED/CONFIRMED
 * throws {@link ApiException} with {@link ErrorCode#IDEMPOTENCY_CONFLICT}.
 */
@Service
public class ReportFilingService {

    private static final Logger log = LoggerFactory.getLogger(ReportFilingService.class);

    private final ReportFilingRepository repository;

    public ReportFilingService(ReportFilingRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * Returns the existing filing for the natural key, or creates a fresh PENDING one.
     * Idempotent: never produces a duplicate for the same key.
     */
    @Transactional
    public ReportFiling openFiling(ReportFiling.Lane lane, String reportType, LocalDate reportDate) {
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(reportType, "reportType");
        Objects.requireNonNull(reportDate, "reportDate");

        Optional<ReportFiling> existing = repository
                .findByLaneAndReportTypeAndReportDate(lane.name(), reportType, reportDate);
        if (existing.isPresent()) {
            log.debug("Reusing existing filing lane={} type={} date={} id={}",
                    lane, reportType, reportDate, existing.get().getId());
            return existing.get();
        }
        ReportFiling filing = new ReportFiling(lane, reportType, reportDate);
        return repository.save(filing);
    }

    /**
     * Marks a filing GENERATED with its record count and artifact path.
     * Safe to call repeatedly (overwrites count/path, status stays GENERATED).
     */
    @Transactional
    public ReportFiling recordGenerated(Long filingId, int recordCount, String filePath) {
        ReportFiling filing = require(filingId);
        filing.markGenerated(recordCount, filePath);
        return repository.save(filing);
    }

    /**
     * Marks a filing SUBMITTED with the channel acknowledgement id.
     * Double-submit guard: a filing already SUBMITTED/CONFIRMED is rejected with
     * {@link ErrorCode#IDEMPOTENCY_CONFLICT}.
     */
    @Transactional
    public ReportFiling recordSubmission(Long filingId, String externalReceiptId) {
        ReportFiling filing = require(filingId);
        String status = filing.getSubmissionStatus();
        if (ReportFiling.Status.SUBMITTED.name().equals(status)
                || ReportFiling.Status.CONFIRMED.name().equals(status)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "report_filing id=" + filingId + " already " + status
                            + " — refusing duplicate submission");
        }
        filing.markSubmitted(externalReceiptId);
        return repository.save(filing);
    }

    @Transactional
    public ReportFiling recordFailure(Long filingId) {
        ReportFiling filing = require(filingId);
        filing.markFailed();
        return repository.save(filing);
    }

    private ReportFiling require(Long filingId) {
        return repository.findById(filingId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR,
                        "report_filing id=" + filingId + " not found"));
    }
}
