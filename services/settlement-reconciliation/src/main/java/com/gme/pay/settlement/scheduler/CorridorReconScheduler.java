package com.gme.pay.settlement.scheduler;

import com.gme.pay.settlement.corridor.CorridorReconResult;
import com.gme.pay.settlement.corridor.CorridorThreeWayReconciler;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Daily driver for the cross-border three-way tie-out (GAP T2-2) — one run per configured corridor
 * for the PREVIOUS business day, after that day is closed.
 *
 * <p>Gated on {@code gmepay.settlement.corridor-recon.enabled} (default {@code false}), matching
 * {@link ReconScheduler}: production turns it on, dev/test never reconciles by accident. Each
 * corridor is run independently and a failure is logged, not propagated, so one corridor cannot
 * suppress another.
 *
 * <p>The run is idempotent per date, so a retry (or an operator re-run through
 * {@code POST /v1/settlement/corridor/{scheme}/recon}) is always safe.
 */
@Component
public class CorridorReconScheduler {

    private static final Logger log = LoggerFactory.getLogger(CorridorReconScheduler.class);

    private final boolean enabled;
    private final ZoneId zone;
    private final List<CorridorThreeWayReconciler> reconcilers;

    public CorridorReconScheduler(
            @Value("${gmepay.settlement.corridor-recon.enabled:false}") boolean enabled,
            @Value("${gmepay.settlement.corridor.settlement-zone:Asia/Seoul}") String zone,
            List<CorridorThreeWayReconciler> reconcilers) {
        this.enabled = enabled;
        this.zone = ZoneId.of(zone);
        this.reconcilers = reconcilers;
    }

    /**
     * Reconcile yesterday for every configured corridor. Default cron 02:30 UTC = 11:30 KST — after
     * the KST business day has closed and after the ZeroPay morning recon window, so a shared
     * transaction-mgmt read is not competing with it.
     */
    // T3-11: one corridor recon per night across the cluster. Two replicas would each write a
    // corridor_recon_summary row for the same date, so the three-way tie-out an operator reads would
    // show two conflicting answers for one day with no way to tell which run produced which.
    @Scheduled(cron = "${gmepay.settlement.corridor-recon.cron:0 30 2 * * *}")
    @SchedulerLock(name = "CorridorReconScheduler_reconcileYesterday",
            lockAtMostFor = "PT30M", lockAtLeastFor = "PT0S")
    public void reconcileYesterday() {
        if (!enabled) {
            log.debug("CorridorReconScheduler: disabled (gmepay.settlement.corridor-recon.enabled=false)");
            return;
        }
        LocalDate date = LocalDate.now(zone).minusDays(1);
        for (CorridorThreeWayReconciler reconciler : reconcilers) {
            try {
                CorridorReconResult result = reconciler.reconcile(date);
                log.info("CorridorReconScheduler: {} {} → {} line(s), {} break(s), variance ${}, cumulative ${}",
                        reconciler.scheme(), date, result.lines().size(), result.breaks().size(),
                        result.summary().getRateBasisVarianceUsd().toPlainString(),
                        result.summary().getCumulativeVarianceUsd().toPlainString());
            } catch (RuntimeException e) {
                log.error("CorridorReconScheduler: {} recon failed for {}: {}",
                        reconciler.scheme(), date, e.getMessage(), e);
            }
        }
    }
}
