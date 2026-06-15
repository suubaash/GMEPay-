package com.gme.pay.reporting.kofiu;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Port (secondary / outbound): reads partner regulatory config from
 * config-registry V029 ({@code partner_regulatory_config}).
 *
 * <p>KoFIU service needs:
 * <ul>
 *   <li>{@code kofiuEntityId} — the reporting entity identifier on KoFIU feeds.</li>
 *   <li>{@code ctrThresholdKrw} — the daily aggregate CTR trigger (default
 *       KRW 10,000,000 per statute).</li>
 * </ul>
 *
 * <p>Per-corridor STR flag ({@code strEnabled}, V029_1) lives on the corridor
 * config and is read via {@link CorridorConfigPort}.
 */
public interface RegulatoryConfigPort {

    /**
     * Returns the CTR threshold (major KRW) for the given partner.
     * Returns {@code Optional.empty()} when the partner has no regulatory
     * config row yet, in which case the caller should fall back to the
     * statutory default (10,000,000 KRW).
     *
     * @param partnerId the partner's BIGINT surrogate
     * @return CTR threshold as BigDecimal, or empty
     */
    Optional<BigDecimal> findCtrThreshold(long partnerId);

    /**
     * Returns the KoFIU entity id for the given partner.
     * Returns {@code Optional.empty()} when not configured.
     *
     * @param partnerId the partner's BIGINT surrogate
     * @return kofiuEntityId string, or empty
     */
    Optional<String> findKofiuEntityId(long partnerId);
}
