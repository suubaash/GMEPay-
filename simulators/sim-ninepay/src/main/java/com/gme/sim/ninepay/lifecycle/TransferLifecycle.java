package com.gme.sim.ninepay.lifecycle;

import com.gme.sim.ninepay.config.NinepaySimConfig;
import com.gme.sim.ninepay.ipn.IpnSender;
import com.gme.sim.ninepay.model.NinepayStore;
import com.gme.sim.ninepay.model.TransferRecord;
import com.gme.sim.ninepay.sign.SimSigner;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drives each payout through PENDING → PROCESSING → SUCCESS/FAIL.
 *
 * <p>Two advance triggers: a per-record timer (one step per
 * {@code sim.ninepay.lifecycle-step-ms}, default ~2s) and — when
 * {@code advance-on-poll} is on — each {@code /service/transfer/info} lookup. Transitions
 * are idempotent and synchronized on the record, so racing triggers just get there
 * faster.</p>
 *
 * <p>On reaching the terminal state the result is pushed as an IPN exactly once:</p>
 * <ul>
 *   <li>SUCCESS  → status SUCCESS, code 000 (balance stays debited).</li>
 *   <li>FAIL     → status FAIL, code from the record's plan (001–007); balance restored.</li>
 *   <li>HELD     → code 008, wire status stays PROCESSING; the record parks (no further
 *                  timer steps) — 9Pay would wait for merchant confirmation.</li>
 *   <li>REVERSAL → SUCCESS IPN (000) first, then after {@code reversal-delay-ms} a second
 *                  IPN code 009 (bank reversal); balance restored at the 009. Wire status
 *                  stays SUCCESS (009 is a message code, not a wire status).</li>
 * </ul>
 */
@Component
public class TransferLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TransferLifecycle.class);

    private final NinepaySimConfig config;
    private final NinepayStore store;
    private final IpnSender ipnSender;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "sim-ninepay-lifecycle");
        t.setDaemon(true);
        return t;
    });

    public TransferLifecycle(NinepaySimConfig config, NinepayStore store, IpnSender ipnSender) {
        this.config = config;
        this.store = store;
        this.ipnSender = ipnSender;
    }

    /** Starts the timer chain for a freshly accepted transfer. */
    public void schedule(TransferRecord record) {
        scheduler.schedule(() -> timerTick(record), config.getLifecycleStepMs(), TimeUnit.MILLISECONDS);
    }

    /** One poll-triggered step (transfer/info with advance-on-poll enabled). */
    public void advanceOnPoll(TransferRecord record) {
        if (config.isAdvanceOnPoll()) {
            advance(record);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    // ------------------------------------------------------------------ engine

    private void timerTick(TransferRecord record) {
        try {
            advance(record);
        } catch (Exception e) {
            log.error("lifecycle advance failed for {}: {}", record.getRequestId(), e.getMessage(), e);
        }
        synchronized (record) {
            if (!record.isTerminal() && !record.isHeld() && !orphaned(record)) {
                scheduler.schedule(() -> timerTick(record), config.getLifecycleStepMs(), TimeUnit.MILLISECONDS);
            }
        }
    }

    /** True when the record was orphaned by a store reset — its timers must go silent. */
    private boolean orphaned(TransferRecord record) {
        return store.byRequestId(record.getRequestId()) != record;
    }

    /** Advances one step; terminal + held + orphaned records are no-ops. */
    private void advance(TransferRecord record) {
        if (orphaned(record)) {
            return;
        }
        String ipnStatus = null;
        String ipnCode = null;
        String ipnMessage = null;
        boolean scheduleReversal = false;

        synchronized (record) {
            if (record.isTerminal() || record.isHeld()) {
                return;
            }
            switch (record.getStatus()) {
                case "PENDING" -> record.setStatus("PROCESSING");
                case "PROCESSING" -> {
                    switch (record.getPlannedOutcome()) {
                        case SUCCESS -> {
                            record.setStatus("SUCCESS");
                            ipnStatus = "SUCCESS";
                            ipnCode = "000";
                            ipnMessage = "Successful transaction";
                        }
                        case FAIL -> {
                            record.setStatus("FAIL");
                            ipnStatus = "FAIL";
                            ipnCode = record.getPlannedFailCode();
                            ipnMessage = "Transaction failed (code " + ipnCode + ")";
                            store.credit(record.getTransferAmountVnd());
                        }
                        case HELD -> {
                            record.setHeld(true); // wire status stays PROCESSING
                            ipnStatus = "PROCESSING";
                            ipnCode = "008";
                            ipnMessage = "Held by 9Pay pending merchant confirmation";
                        }
                        case REVERSAL -> {
                            record.setStatus("SUCCESS");
                            ipnStatus = "SUCCESS";
                            ipnCode = "000";
                            ipnMessage = "Successful transaction";
                            scheduleReversal = true;
                        }
                    }
                    if (record.isTerminal() || record.isHeld()) {
                        record.setTerminalIpnSent(true);
                    }
                }
                default -> { /* SUCCESS/FAIL handled by isTerminal() above */ }
            }
        }

        if (ipnCode != null) {
            ipnSender.send(record, config.getPartnerId(), ipnStatus, ipnCode, ipnMessage);
        }
        if (scheduleReversal) {
            scheduler.schedule(() -> reverse(record), config.getReversalDelayMs(), TimeUnit.MILLISECONDS);
        }
    }

    /** The critical scenario: a DELAYED second IPN code 009 — bank reversed a SUCCESS. */
    private void reverse(TransferRecord record) {
        if (orphaned(record)) {
            return;
        }
        synchronized (record) {
            if (record.isReversed() || !"SUCCESS".equals(record.getStatus())) {
                return;
            }
            record.setReversed(true);
        }
        store.credit(record.getTransferAmountVnd());
        try {
            ipnSender.send(record, config.getPartnerId(), "SUCCESS", "009",
                    "Payment reversed by bank");
        } catch (Exception e) {
            log.error("009 reversal IPN failed for {}: {}", record.getRequestId(), e.getMessage(), e);
        }
    }
}
