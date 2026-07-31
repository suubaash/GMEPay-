package com.gme.pay.payment.dayclose;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.payment.opsrun.LedgerOpsRunTrigger;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Persists and reads the day-close artifact (table {@code day_close_reports}, Flyway V009 — gap <b>T2-5</b>).
 *
 * <p>One row per business date, overwritten on a re-run: the report is a DERIVED view of that day, so a re-run
 * is a correction of the same close rather than a second one. The version that was reviewed is the version that
 * is kept, which is the whole reason CFO#10 asked for a persisted artifact rather than an endpoint.
 *
 * <h2>This one DOES throw</h2>
 * <p>Unlike {@code RevenuePostingFailureStore} and {@code OpsAlertArchive} — which sit on the money path and must
 * never turn a successful payment into an error — this store runs inside a report job. A close that could not be
 * persisted has not happened, and pretending otherwise would leave finance signing a report nobody can produce
 * again. The failure propagates to {@code LedgerOpsRunExecutor}, which records it and alerts.
 */
@Service
public class DayCloseReportStore {

    /** Hard ceiling on the history query. */
    public static final int MAX_HISTORY = 180;

    private final DayCloseReportRepository repository;
    private final ObjectMapper objectMapper;

    public DayCloseReportStore(DayCloseReportRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Store (or replace) the close for its business date.
     *
     * @return the persisted row
     */
    @Transactional
    public DayCloseReportEntity upsert(DayCloseReport report, LedgerOpsRunTrigger trigger) {
        String json;
        try {
            json = objectMapper.writeValueAsString(report);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("day-close report for " + report.businessDate()
                    + " could not be serialised, so it cannot be persisted: " + e.getMessage(), e);
        }
        DayCloseReportEntity row = repository.findByBusinessDate(report.businessDate())
                .orElseGet(DayCloseReportEntity::new);
        row.setBusinessDate(report.businessDate());
        row.setGeneratedAt(report.generatedAt());
        row.setTriggerSource(trigger.name());
        row.setClean(report.clean());
        row.setVarianceCount(report.variances().size());
        row.setUnresolvedDecisionCount(report.unresolvedDecisions().size());
        row.setUnavailableLegCount((int) report.legs().stream().filter(l -> !l.available()).count());
        row.setReportJson(json);
        return repository.save(row);
    }

    /** The stored close for a date, deserialised, or empty when that date has never been closed. */
    @Transactional(readOnly = true)
    public Optional<DayCloseReport> find(LocalDate businessDate) {
        return repository.findByBusinessDate(businessDate).map(this::deserialise);
    }

    /** Recent closes, newest first, bounded. */
    @Transactional(readOnly = true)
    public List<DayCloseReportEntity> history(int limit) {
        int capped = limit <= 0 ? 30 : Math.min(limit, MAX_HISTORY);
        return repository.findAllByOrderByBusinessDateDesc(PageRequest.of(0, capped));
    }

    private DayCloseReport deserialise(DayCloseReportEntity row) {
        try {
            return objectMapper.readValue(row.getReportJson(), DayCloseReport.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // A stored report that cannot be read back is a real problem, not something to paper over with an
            // empty report: the artifact finance signed would silently become a different document.
            throw new IllegalStateException("stored day-close report for " + row.getBusinessDate()
                    + " could not be deserialised: " + e.getMessage(), e);
        }
    }
}
