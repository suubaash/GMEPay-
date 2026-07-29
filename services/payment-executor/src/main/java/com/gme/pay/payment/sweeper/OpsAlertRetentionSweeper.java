package com.gme.pay.payment.sweeper;

import com.gme.pay.payment.persistence.OpsAlertArchive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the durable {@code ops_alerts} table bounded (gap T3-3): deletes alerts older than
 * {@code gmepay.ops.alerts.retention-days} (default 90) once every
 * {@code gmepay.ops.alerts.prune-interval-ms} (default 6h).
 *
 * <p>Why bounded-by-time rather than bounded-by-count: the thing being replaced was a 200-entry deque,
 * i.e. bounded by count, which is why a busy hour could silently evict the morning's evidence. Alert
 * volume is inherently low (each monitor has a per-subject cooldown), so a time window keeps the whole
 * incident history for as long as an audit could ask for it and still never grows without limit.
 *
 * <p>Enabled by default; set {@code gmepay.ops.alerts.prune-enabled=false} to keep alerts forever.
 * Single-replica safe by construction — the delete is idempotent (a second replica pruning the same
 * cutoff simply deletes nothing), so this needs no ShedLock.
 */
@Component
@ConditionalOnProperty(name = "gmepay.ops.alerts.prune-enabled", havingValue = "true",
        matchIfMissing = true)
public class OpsAlertRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(OpsAlertRetentionSweeper.class);

    private final OpsAlertArchive archive;

    public OpsAlertRetentionSweeper(OpsAlertArchive archive) {
        this.archive = archive;
    }

    @Scheduled(fixedDelayString = "${gmepay.ops.alerts.prune-interval-ms:21600000}",
            initialDelayString = "${gmepay.ops.alerts.prune-initial-delay-ms:300000}")
    public void prune() {
        try {
            archive.prune();
        } catch (RuntimeException e) {
            // A retention failure must never take the scheduler down; the table just grows until fixed.
            log.error("ops_alerts retention prune failed: {}", e.toString());
        }
    }
}
