package com.gme.pay.reporting.service;

import com.gme.pay.reporting.domain.BokFxMapper;
import com.gme.pay.reporting.domain.BokFxRecord;
import com.gme.pay.reporting.domain.CommittedTransaction;
import com.gme.pay.reporting.domain.TransactionDirection;
import com.gme.pay.reporting.persistence.BokReportRecordEntity;
import com.gme.pay.reporting.persistence.BokReportRecordRepository;
import com.gme.pay.reporting.persistence.ReportFiling;
import com.gme.pay.reporting.persistence.ReportFilingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Persists BOK FX records for a report date and links them to {@link ReportFiling}
 * runs (one filing per report_type per date).
 *
 * <p>Idempotency works at two levels:
 * <ul>
 *   <li>{@link ReportFilingService#openFiling} reuses the per-date filing row;</li>
 *   <li>{@link BokReportRecordRepository#existsByTxnId} (plus the DB UNIQUE on txn_id)
 *       skips transactions already persisted, so a scheduler re-run for the same day
 *       does not create duplicate {@code bok_report_record} rows.</li>
 * </ul>
 *
 * <p>This is the persistence counterpart of {@link BokReportService} (which only
 * projects records into an API response). It is invoked by the gated scheduler.
 */
@Service
public class BokRecordPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(BokRecordPersistenceService.class);

    private final ReportFilingService filingService;
    private final BokReportRecordRepository recordRepository;
    private final BokFxMapper mapper = new BokFxMapper();

    public BokRecordPersistenceService(
            ReportFilingService filingService,
            BokReportRecordRepository recordRepository) {
        this.filingService = Objects.requireNonNull(filingService, "filingService");
        this.recordRepository = Objects.requireNonNull(recordRepository, "recordRepository");
    }

    /**
     * Persists all cross-border transactions for {@code reportDate} as
     * {@code bok_report_record} rows, grouped under per-type {@link ReportFiling}s.
     *
     * @param transactions committed (cross-border) transactions, FX fields populated
     * @param reportDate   KST report date
     * @return number of new {@code bok_report_record} rows inserted (existing rows skipped)
     */
    @Transactional
    public int persistForDate(List<CommittedTransaction> transactions, LocalDate reportDate) {
        Objects.requireNonNull(transactions, "transactions");
        Objects.requireNonNull(reportDate, "reportDate");

        // Open (or reuse) one filing per report type for this date.
        ReportFiling fx1014 = filingService.openFiling(
                ReportFiling.Lane.BOK, "FX1014", reportDate);
        ReportFiling fx1015 = filingService.openFiling(
                ReportFiling.Lane.BOK, "FX1015", reportDate);

        int inserted = 0;
        int fx1014Count = 0;
        int fx1015Count = 0;

        for (CommittedTransaction txn : transactions) {
            // Domestic/same-currency transactions are BOK-exempt.
            if (txn.isSameCcyShortcircuit()
                    || txn.getDirection() == TransactionDirection.DOMESTIC) {
                continue;
            }
            BokFxRecord record = mapper.toRecord(txn);
            boolean isFx1015 = "FX1015".equals(record.getReportType().name());
            Long filingId = (isFx1015 ? fx1015 : fx1014).getId();

            if (isFx1015) {
                fx1015Count++;
            } else {
                fx1014Count++;
            }

            // Per-transaction idempotency: skip if already persisted.
            if (recordRepository.existsByTxnId(record.getTxnId())) {
                continue;
            }

            recordRepository.save(new BokReportRecordEntity(
                    filingId,
                    record.getTxnId(),
                    record.getTxnRef(),
                    record.getReportType().name(),
                    record.getReportDate(),
                    record.getPartnerId(),
                    record.getCollectionAmount(),
                    record.getCollectionCcy(),
                    record.getPayoutAmount(),
                    record.getPayoutCcy(),
                    record.getOfferRateColl(),   // BOK FX1015 field #14 — carried verbatim
                    record.getCrossRate(),
                    record.getUsdAmount(),
                    BokFxMapper.STATUS_PENDING));
            inserted++;
        }

        // Reflect counts on the filing rows (status -> GENERATED).
        filingService.recordGenerated(fx1014.getId(), fx1014Count, null);
        filingService.recordGenerated(fx1015.getId(), fx1015Count, null);

        log.info("Persisted BOK records for {}: inserted={} (FX1014={}, FX1015={})",
                reportDate, inserted, fx1014Count, fx1015Count);
        return inserted;
    }
}
