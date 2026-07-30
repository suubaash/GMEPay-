package com.gme.pay.ratefx.xe;

import com.gme.pay.ratefx.audit.RateAuditor;
import com.gme.pay.ratefx.persistence.RateSnapshotEntity;
import com.gme.pay.ratefx.persistence.RateSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * Pulls USD-base rates from sim-rate-provider every 15 minutes (UC-05-01)
 * and upserts them into {@code rate_snapshots} with {@code source = 'LIVE'}.
 *
 * <p>Active only when {@code gmepay.rate-fx.xe.enabled=true}.
 * {@link org.springframework.scheduling.annotation.EnableScheduling} is declared
 * on {@link XeSchedulingConfig} so it is also conditional on that property.
 *
 * <h2>Audit (gap T5-1 / CISO §9)</h2>
 *
 * <p>Each upserted snapshot writes one {@code audit_log} row attributed to
 * {@link RateAuditor#SYSTEM_XE_FETCH_SCHEDULER} ({@code system:xe-rate-fetch-scheduler}) with
 * {@code event_type = RATE_SNAPSHOT_LIVE_FETCHED}. Auditing the automated poll is not busywork: it is
 * what makes the MANUAL rows meaningful. If only manual overrides were audited, a reader could not
 * tell an un-audited feed write from a gap in the trail, and the chain for a currency would have holes
 * wherever the scheduler ran. With both audited, a manual rate stands out against its own history by
 * actor and by verb, at a glance.
 */
@Component
@ConditionalOnProperty(name = "gmepay.rate-fx.xe.enabled", havingValue = "true")
public class XeRateFetchScheduler {

    private static final Logger log = LoggerFactory.getLogger(XeRateFetchScheduler.class);
    private static final String SOURCE = "LIVE";

    private final XeRateClient client;
    private final RateSnapshotRepository repository;
    private final RateAuditor audit;

    public XeRateFetchScheduler(XeRateClient client, RateSnapshotRepository repository,
                                RateAuditor audit) {
        this.client = client;
        this.repository = repository;
        this.audit = audit;
    }

    /**
     * Fetch + upsert cycle. fixedDelay = 900 000 ms (15 min) per UC-05-01.
     * Instants are truncated to MICROS for TIMESTAMP column compatibility.
     */
    @Scheduled(fixedDelayString = "${gmepay.rate-fx.xe.fetch-delay-ms:900000}")
    public void fetchAndUpsert() {
        try {
            XeMultiRateResponse resp = client.fetchUsdRates();
            if (resp == null || resp.quotes() == null) {
                log.warn("XeRateClient returned null/empty response — skipping upsert");
                return;
            }
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            for (Map.Entry<String, String> entry : resp.quotes().entrySet()) {
                String ccy = entry.getKey();
                BigDecimal rate = new BigDecimal(entry.getValue());
                String snapshotId = "xe-" + ccy + "-" + UUID.randomUUID();
                // The rate this poll displaces, read before the save, so the audit row shows the move.
                RateAuditor.RateState before = repository
                        .findFirstByCurrencyCodeAndEffectiveAtLessThanEqualOrderByEffectiveAtDescCapturedAtDesc(
                                ccy, now)
                        .map(e -> new RateAuditor.RateState(e.getUsdRate(), e.getSource(),
                                e.getSnapshotId(), e.getEffectiveAt()))
                        .orElseGet(RateAuditor.RateState::none);
                RateSnapshotEntity entity = new RateSnapshotEntity(
                        snapshotId, ccy, rate, SOURCE, now, now);
                RateSnapshotEntity saved = repository.save(entity);
                // A named system principal, never the bare "system" literal — which used to mean both
                // "the platform did this" and "nobody told me who did this".
                audit.rateWritten(ccy, before,
                        new RateAuditor.RateState(saved.getUsdRate(), saved.getSource(),
                                saved.getSnapshotId(), saved.getEffectiveAt()),
                        "scheduled provider poll (" + resp.source() + ")",
                        RateAuditor.SYSTEM_XE_FETCH_SCHEDULER);
            }
            log.info("XeRateFetchScheduler: upserted {} LIVE snapshots at {}",
                    resp.quotes().size(), now);
        } catch (Exception ex) {
            // Non-fatal: log and continue — the service must not crash if the sim is down.
            log.error("XeRateFetchScheduler fetch failed: {}", ex.getMessage(), ex);
        }
    }
}
