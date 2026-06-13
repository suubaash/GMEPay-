package com.gme.pay.ratefx.xe;

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
 */
@Component
@ConditionalOnProperty(name = "gmepay.rate-fx.xe.enabled", havingValue = "true")
public class XeRateFetchScheduler {

    private static final Logger log = LoggerFactory.getLogger(XeRateFetchScheduler.class);
    private static final String SOURCE = "LIVE";

    private final XeRateClient client;
    private final RateSnapshotRepository repository;

    public XeRateFetchScheduler(XeRateClient client, RateSnapshotRepository repository) {
        this.client = client;
        this.repository = repository;
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
                RateSnapshotEntity entity = new RateSnapshotEntity(
                        snapshotId, ccy, rate, SOURCE, now, now);
                repository.save(entity);
            }
            log.info("XeRateFetchScheduler: upserted {} LIVE snapshots at {}",
                    resp.quotes().size(), now);
        } catch (Exception ex) {
            // Non-fatal: log and continue — the service must not crash if the sim is down.
            log.error("XeRateFetchScheduler fetch failed: {}", ex.getMessage(), ex);
        }
    }
}
