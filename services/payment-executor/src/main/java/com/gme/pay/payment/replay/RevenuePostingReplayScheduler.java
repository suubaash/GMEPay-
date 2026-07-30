package com.gme.pay.payment.replay;

import com.gme.pay.payment.opsrun.LedgerOpsRunTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the revenue-posting replay sweep on a schedule (gap <b>T2-5</b> sub-gap 4).
 *
 * <h2>Default ON — and why</h2>
 * <p>{@code matchIfMissing = true}, matching payment-executor's own ops convention established by T3-3:
 * {@code DeclineSpikeMonitor} and {@code OpsAlertRetentionSweeper} are both default-on because a safety net
 * that has to be switched on is a safety net that is off in production. This job is squarely in that class —
 * with it off, {@code revenue_posting_failures} accumulates and booked revenue stays missing from the ledger,
 * which is the gap itself. (The service's one default-OFF scheduler, {@code AuthorizationExpirySweeper}, MOVES
 * MONEY — it releases float holds — which is a different risk profile from re-sending a posting to an
 * idempotent endpoint.)
 *
 * <p>Set {@code gmepay.revenue-posting-replay.enabled=false} to turn it off; that must be an explicit operator
 * decision, never an omission. The run is still available on demand at
 * {@code POST /internal/ops/revenue-posting-failures/replay}.
 *
 * <p><b>Single-replica safe by construction, without ShedLock.</b> Two replicas sweeping concurrently would
 * each re-POST to endpoints that are idempotent on the reference, so the worst case is a duplicate HTTP call
 * that revenue-ledger answers {@code 200}; both replicas then converge on {@code REPLAYED}. What they cannot do
 * is double-book.
 *
 * <p>Never throws: {@link RevenuePostingReplayService#run} is already wrapped in the durable run ledger, so a
 * failure is persisted and alerted rather than killing the scheduler thread. The extra catch here covers the
 * one thing the executor cannot — an error raised before the wrapper is entered.
 */
@Component
@ConditionalOnProperty(name = "gmepay.revenue-posting-replay.enabled", havingValue = "true",
        matchIfMissing = true)
public class RevenuePostingReplayScheduler {

    private static final Logger log = LoggerFactory.getLogger(RevenuePostingReplayScheduler.class);

    private final RevenuePostingReplayService service;

    public RevenuePostingReplayScheduler(RevenuePostingReplayService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${gmepay.revenue-posting-replay.interval-ms:300000}",
            initialDelayString = "${gmepay.revenue-posting-replay.initial-delay-ms:60000}")
    public void replayDuePostings() {
        try {
            service.run(LedgerOpsRunTrigger.SCHEDULER, null);
        } catch (RuntimeException e) {
            log.error("revenue posting replay scheduler failed outside the run ledger: {}", e.toString(), e);
        }
    }
}
