package com.gme.pay.reporting.kofiu;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Default (stub) implementation of {@link RegulatoryConfigPort}.
 *
 * <p>Returns the statutory KoFIU CTR default (KRW 10,000,000) for every
 * partner, and an empty kofiuEntityId. Active whenever no other bean of type
 * {@link RegulatoryConfigPort} is present.
 *
 * <p>Replace with a real REST client calling
 * {@code GET /v1/partners/{partnerCode}/regulatory} on config-registry once the
 * production integration is ready.
 */
@Component
@ConditionalOnMissingBean(value = RegulatoryConfigPort.class,
        ignored = StubRegulatoryConfigPort.class)
public class StubRegulatoryConfigPort implements RegulatoryConfigPort {

    /** Statutory KoFIU CTR threshold (KRW 10,000,000 per FTRA Article 4). */
    static final BigDecimal STATUTORY_CTR_DEFAULT = new BigDecimal("10000000");

    @Override
    public Optional<BigDecimal> findCtrThreshold(long partnerId) {
        return Optional.of(STATUTORY_CTR_DEFAULT);
    }

    @Override
    public Optional<String> findKofiuEntityId(long partnerId) {
        // No real config-registry client wired yet.
        return Optional.empty();
    }
}
