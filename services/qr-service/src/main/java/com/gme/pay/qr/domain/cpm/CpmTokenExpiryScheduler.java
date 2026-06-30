package com.gme.pay.qr.domain.cpm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Sweeps ISSUED CPM sessions past their expiry and marks them EXPIRED (WBS 5.3-T10).
 *
 * <p>Runs every 30 seconds. The sweep is idempotent: only rows still in ISSUED with
 * {@code expires_at < now} transition. Prefunding-reservation release on expiry is owned by the
 * prefunding service (INTEGRATION REQUEST #3); this scheduler only owns qr-service's session state.
 */
@Component
public class CpmTokenExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(CpmTokenExpiryScheduler.class);

    private final CpmSessionStorePort sessionStore;
    private final Clock clock;

    public CpmTokenExpiryScheduler(CpmSessionStorePort sessionStore, Clock clock) {
        this.sessionStore = sessionStore;
        this.clock = clock;
    }

    /** Scheduled entry point. */
    @Scheduled(fixedDelayString = "${qr.cpm.expiry-sweep-ms:30000}")
    public void sweep() {
        List<String> expired = sessionStore.expireOverdue(clock.instant());
        if (!expired.isEmpty()) {
            log.info("CPM expiry sweep marked {} token(s) EXPIRED: {}", expired.size(), expired);
        }
    }
}
