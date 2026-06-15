package com.gme.pay.reporting.kofiu;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default (stub) implementation of {@link CorridorConfigPort}.
 *
 * <p>Always returns {@code false} (STR disabled) — the safe default when no
 * real config-registry client is wired. Active whenever no other bean of type
 * {@link CorridorConfigPort} is present.
 *
 * <p>Replace with a real REST client calling
 * {@code GET /v1/partners/{partnerCode}/corridors} on config-registry once the
 * production integration is ready.
 */
@Component
@ConditionalOnMissingBean(value = CorridorConfigPort.class,
        ignored = StubCorridorConfigPort.class)
public class StubCorridorConfigPort implements CorridorConfigPort {

    @Override
    public boolean isStrEnabled(long partnerId, String srcCcy, String dstCcy) {
        // No real config-registry client wired yet — STR disabled by default.
        return false;
    }
}
