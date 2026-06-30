package com.gme.pay.qr.domain.cpm;

import com.gme.pay.qr.domain.cpm.CpmSessionStorePort.ExpiredSession;
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
 * {@code expires_at < now} transition. For OVERSEAS sessions that carry a prefunding hold, the
 * sweep also RELEASES the reservation (Phase 2, IR-qr-3) so soft-held USD returns to available.
 * Release is keyed on the CPM token id (the reserve idempotency key) and is idempotent on the
 * prefunding side, so a release that already ran is a no-op.
 */
@Component
public class CpmTokenExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(CpmTokenExpiryScheduler.class);
    private static final String REASON_EXPIRED = "CPM_EXPIRED";

    private final CpmSessionStorePort sessionStore;
    private final PrefundingReservationPort prefundingReservation;
    private final Clock clock;

    public CpmTokenExpiryScheduler(CpmSessionStorePort sessionStore,
                                   PrefundingReservationPort prefundingReservation,
                                   Clock clock) {
        this.sessionStore = sessionStore;
        this.prefundingReservation = prefundingReservation;
        this.clock = clock;
    }

    /** Scheduled entry point. */
    @Scheduled(fixedDelayString = "${qr.cpm.expiry-sweep-ms:30000}")
    public void sweep() {
        List<ExpiredSession> expired = sessionStore.expireOverdue(clock.instant());
        if (expired.isEmpty()) {
            return;
        }
        for (ExpiredSession e : expired) {
            if (e.partnerId() != null) {
                prefundingReservation.release(
                        e.partnerId(), e.reservationId(), e.cpmTokenId(), REASON_EXPIRED);
            }
        }
        log.info("CPM expiry sweep marked {} token(s) EXPIRED: {}",
                expired.size(), expired.stream().map(ExpiredSession::cpmTokenId).toList());
    }
}
