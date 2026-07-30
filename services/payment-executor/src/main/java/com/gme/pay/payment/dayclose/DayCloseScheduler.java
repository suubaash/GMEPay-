package com.gme.pay.payment.dayclose;

import com.gme.pay.payment.opsrun.LedgerOpsRunTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Produces the day-close artifact on a schedule (gap <b>T2-5</b> sub-gap 4).
 *
 * <h2>Default ON — and why</h2>
 * <p>{@code matchIfMissing = true}, matching payment-executor's own ops convention (T3-3's
 * {@code DeclineSpikeMonitor} and {@code OpsAlertRetentionSweeper} are both default-on). Two reasons this
 * belongs in that class rather than with settlement-reconciliation's default-OFF corridor recon: the run is
 * strictly READ-ONLY over three services plus one upsert into payment-executor's own table, so a wrong day
 * costs a row and not money; and a day-close that has to be switched on is a day-close that nobody produced
 * for the first month, which is the gap.
 *
 * <p>(Settlement's corridor recon defaults OFF for a different and also correct reason: it writes into a
 * shared ops exception queue and alerts, so an unattended default-on run would page people about a corridor
 * nobody had onboarded yet.)
 *
 * <p>Set {@code gmepay.day-close.enabled=false} to turn it off. The close is always available on demand at
 * {@code POST /internal/ops/day-close}.
 *
 * <h2>Which date, and why not today's</h2>
 * <p>Yesterday, in {@code gmepay.day-close.zone} (KST by default). Closing the CURRENT date would produce an
 * artifact that is wrong by construction — the day is still transacting — and re-running it later would silently
 * replace the numbers under a report someone had already read.
 *
 * <p><b>Not calendar-gated, deliberately.</b> Money moves on Korean banking holidays, so a holiday still gets a
 * close. See {@code LedgerOpsRunExecutor} for why no business-day calendar is consulted here.
 *
 * <p><b>Single-replica safe by construction, without ShedLock:</b> the write is an upsert keyed on the business
 * date, so two replicas closing the same day converge on one row rather than duplicating it.
 */
@Component
@ConditionalOnProperty(name = "gmepay.day-close.enabled", havingValue = "true", matchIfMissing = true)
public class DayCloseScheduler {

    private static final Logger log = LoggerFactory.getLogger(DayCloseScheduler.class);

    private final DayCloseReportService service;

    public DayCloseScheduler(DayCloseReportService service) {
        this.service = service;
    }

    /**
     * Default 02:30 in the close timezone — after the day has ended and before the morning settlement windows,
     * so a finance reader has the close before the day's downstream batches start moving.
     */
    @Scheduled(cron = "${gmepay.day-close.cron:0 30 2 * * *}", zone = "${gmepay.day-close.zone:Asia/Seoul}")
    public void closeYesterday() {
        LocalDate date = service.defaultCloseDate();
        try {
            service.run(date, LedgerOpsRunTrigger.SCHEDULER, null);
        } catch (RuntimeException e) {
            log.error("day-close scheduler failed outside the run ledger for {}: {}", date, e.toString(), e);
        }
    }
}
