package com.gme.pay.payment.dayclose;

import com.gme.pay.payment.opsrun.LedgerOpsRunTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Measures the rolling FX position on a schedule (gap <b>T2-5</b> sub-gap 4).
 *
 * <h2>Why a separate scheduler when the day-close already embeds one day's position</h2>
 * <p>The day-close carries the position for ITS date, which is what the persisted artifact needs. Exposure,
 * though, is cumulative: a single day's MNT payout says little, and the same payout every day for a week is the
 * position that matters. This run measures {@code gmepay.fx-exposure.window-days} (default 7) ending yesterday,
 * so the trend is recorded in {@code ledger_ops_runs} even on days nobody reads a report.
 *
 * <p><b>Cost is proportional to the window</b> — one paged transaction read per day in it — so widening the
 * window widens the daily read. Seven days is the default for that reason, not because seven days is the
 * economically correct horizon; there is no such thing here, because this report deliberately sets no target
 * position (see {@link FxExposureReport}).
 *
 * <h2>Default ON</h2>
 * <p>{@code matchIfMissing = true}, same reasoning as {@link DayCloseScheduler}: strictly read-only, and a
 * measurement that has to be switched on is a measurement nobody has. Set
 * {@code gmepay.fx-exposure.enabled=false} to disable; the measurement stays available on demand at
 * {@code GET /internal/ops/fx-exposure}.
 */
@Component
@ConditionalOnProperty(name = "gmepay.fx-exposure.enabled", havingValue = "true", matchIfMissing = true)
public class FxExposureScheduler {

    private static final Logger log = LoggerFactory.getLogger(FxExposureScheduler.class);

    private final FxExposureService fxExposureService;
    private final DayCloseReportService dayCloseService;

    public FxExposureScheduler(FxExposureService fxExposureService, DayCloseReportService dayCloseService) {
        this.fxExposureService = fxExposureService;
        this.dayCloseService = dayCloseService;
    }

    /**
     * Default 03:00 in the close timezone — after {@link DayCloseScheduler}, so the two never compete for the
     * same transaction reads.
     */
    @Scheduled(cron = "${gmepay.fx-exposure.cron:0 0 3 * * *}",
            zone = "${gmepay.day-close.zone:Asia/Seoul}")
    public void measureRollingWindow() {
        LocalDate end = dayCloseService.defaultCloseDate();
        LocalDate start = end.minusDays(fxExposureService.windowDays() - 1L);
        try {
            fxExposureService.run(start, end, LedgerOpsRunTrigger.SCHEDULER, null);
        } catch (RuntimeException e) {
            log.error("FX exposure scheduler failed outside the run ledger for [{}..{}]: {}",
                    start, end, e.toString(), e);
        }
    }
}
