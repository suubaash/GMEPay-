package com.gme.pay.reporting.persistence;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.reporting.channel.FilingChannelRegistry;
import com.gme.pay.reporting.channel.FilingTransmissionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <h2>Filing-status honesty (GAP T5-2)</h2>
 * This service is the only writer of {@code submission_status}, and it is where the
 * honesty guarantee is enforced:
 * <ul>
 *   <li>{@link #recordTransmission} and {@link #recordAcknowledgement} throw unless
 *       {@link FilingChannelRegistry#isLive(ReportFiling.Lane)} is true for the filing's
 *       lane. No lane is live today, so {@code TRANSMITTED} / {@code ACKNOWLEDGED} are
 *       unreachable — a caller cannot fabricate an acceptance even by mistake.</li>
 *   <li>{@link #settleAgainstChannel} is the normal end of a run: it stamps
 *       {@code NOT_FILED_CHANNEL_UNAVAILABLE} plus the reason from the registry when the
 *       lane has no channel, so the register states why nothing was filed.</li>
 *   <li>Generation and validation ({@link #recordGenerated}, {@link #recordValidated})
 *       are untouched real capability and are reported as such.</li>
 * </ul>
 *
 * <p>The terminal channel submission is additionally guarded against double-submit:
 * attempting to transmit a filing that is already TRANSMITTED/ACKNOWLEDGED throws
 * {@link ApiException} with {@link ErrorCode#IDEMPOTENCY_CONFLICT}.
 */
@Service
public class ReportFilingService {

    private static final Logger log = LoggerFactory.getLogger(ReportFilingService.class);

    private final ReportFilingRepository repository;
    private final FilingChannelRegistry channelRegistry;

    /**
     * Spring constructor. {@code @Autowired} declared explicitly because this
     * {@code @Service} has more than one constructor (Spring 6 requires it).
     */
    @Autowired
    public ReportFilingService(ReportFilingRepository repository,
                               FilingChannelRegistry channelRegistry) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.channelRegistry = Objects.requireNonNull(channelRegistry, "channelRegistry");
    }

    /**
     * Convenience constructor defaulting to "no lane has a transmission channel" — the
     * platform's actual state. Used where no channel configuration is available; it can
     * never make a lane live.
     */
    public ReportFilingService(ReportFilingRepository repository) {
        this(repository, FilingChannelRegistry.noChannelsConfigured());
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
     *
     * <p>GENERATED asserts only that this service aggregated the data and produced the
     * artifact — real, tested capability. It asserts nothing about transmission.
     */
    @Transactional
    public ReportFiling recordGenerated(Long filingId, int recordCount, String filePath) {
        ReportFiling filing = require(filingId);
        filing.markGenerated(recordCount, filePath);
        return repository.save(filing);
    }

    /**
     * Marks a generated filing VALIDATED — it passed this service's own format checks.
     * Local validation only; it is not an authority confirmation and never implies filing.
     */
    @Transactional
    public ReportFiling recordValidated(Long filingId) {
        ReportFiling filing = require(filingId);
        filing.markValidated();
        return repository.save(filing);
    }

    /**
     * Records the honest terminal state for a lane with no transmission channel:
     * {@code NOT_FILED_CHANNEL_UNAVAILABLE} plus the reason the channel is missing.
     */
    @Transactional
    public ReportFiling recordChannelUnavailable(Long filingId, String reason) {
        ReportFiling filing = require(filingId);
        filing.markChannelUnavailable(reason);
        return repository.save(filing);
    }

    /**
     * Normal end-of-run settlement. When the filing's lane has no live channel the filing
     * is stamped {@code NOT_FILED_CHANNEL_UNAVAILABLE} with the registry's reason; when a
     * channel is live the filing is left as-is for the transmitting caller to advance.
     *
     * @return the (possibly updated) filing
     */
    @Transactional
    public ReportFiling settleAgainstChannel(Long filingId) {
        ReportFiling filing = require(filingId);
        ReportFiling.Lane lane = laneOf(filing);
        if (channelRegistry.isLive(lane)) {
            return filing;
        }
        filing.markChannelUnavailable(channelRegistry.unavailableReason(lane));
        log.info("Filing id={} lane={} type={} date={} generated but NOT FILED: {}",
                filingId, filing.getLane(), filing.getReportType(), filing.getReportDate(),
                filing.getChannelUnavailableReason());
        return repository.save(filing);
    }

    /**
     * Applies a channel outcome to the filing: {@code TRANSMITTED} on a real transmission,
     * {@code NOT_FILED_CHANNEL_UNAVAILABLE} otherwise. Convenience wrapper so callers
     * cannot forget the negative branch.
     */
    @Transactional
    public ReportFiling recordTransmissionResult(Long filingId, FilingTransmissionResult result) {
        Objects.requireNonNull(result, "result");
        return result.transmitted()
                ? recordTransmission(filingId, result.receiptId())
                : recordChannelUnavailable(filingId, result.reason());
    }

    /**
     * Marks a filing TRANSMITTED with the receipt id returned by the authority's channel.
     *
     * <p><b>Refuses</b> with {@link ErrorCode#VALIDATION_ERROR} when the filing's lane has
     * no live transmission channel — this is the structural block on fabricated
     * acceptance. Double-transmit guard: a filing already TRANSMITTED/ACKNOWLEDGED is
     * rejected with {@link ErrorCode#IDEMPOTENCY_CONFLICT}.
     */
    @Transactional
    public ReportFiling recordTransmission(Long filingId, String externalReceiptId) {
        ReportFiling filing = require(filingId);
        requireLiveChannel(filing, "transmission");

        String status = filing.getSubmissionStatus();
        if (ReportFiling.Status.TRANSMITTED.name().equals(status)
                || ReportFiling.Status.ACKNOWLEDGED.name().equals(status)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "report_filing id=" + filingId + " already " + status
                            + " — refusing duplicate submission");
        }
        if (externalReceiptId == null || externalReceiptId.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "report_filing id=" + filingId + " cannot be marked TRANSMITTED without a "
                            + "receipt id issued by the authority's channel");
        }
        filing.markTransmitted(externalReceiptId);
        return repository.save(filing);
    }

    /**
     * Marks a transmitted filing ACKNOWLEDGED by the authority. Same live-channel
     * requirement as {@link #recordTransmission}, and the filing must already be
     * TRANSMITTED — an acknowledgement cannot precede a transmission.
     */
    @Transactional
    public ReportFiling recordAcknowledgement(Long filingId, String externalReceiptId) {
        ReportFiling filing = require(filingId);
        requireLiveChannel(filing, "acknowledgement");

        if (!ReportFiling.Status.TRANSMITTED.name().equals(filing.getSubmissionStatus())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "report_filing id=" + filingId + " is " + filing.getSubmissionStatus()
                            + " — only a TRANSMITTED filing can be ACKNOWLEDGED");
        }
        filing.markAcknowledged(externalReceiptId);
        return repository.save(filing);
    }

    @Transactional
    public ReportFiling recordFailure(Long filingId) {
        ReportFiling filing = require(filingId);
        filing.markFailed();
        return repository.save(filing);
    }

    /**
     * Hard gate: a filing may only advance past local work when its lane actually has a
     * transmission channel. Without this, any caller could write a status that implies a
     * regulator received the report.
     */
    private void requireLiveChannel(ReportFiling filing, String action) {
        ReportFiling.Lane lane = laneOf(filing);
        if (channelRegistry.isLive(lane)) {
            return;
        }
        throw new ApiException(ErrorCode.VALIDATION_ERROR,
                "report_filing id=" + filing.getId() + " lane=" + lane
                        + ": refusing to record " + action + " — no live filing channel. "
                        + channelRegistry.unavailableReason(lane));
    }

    private static ReportFiling.Lane laneOf(ReportFiling filing) {
        return ReportFiling.Lane.valueOf(filing.getLane());
    }

    private ReportFiling require(Long filingId) {
        return repository.findById(filingId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR,
                        "report_filing id=" + filingId + " not found"));
    }
}
