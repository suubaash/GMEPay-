package com.gme.pay.kyb;

import java.time.Instant;

/**
 * Outcome of a FULL KYB run (ADR-009): sanctions screening plus the
 * registry-level checks a vendor performs against the entity — license
 * validity, UBO register cross-check, corporate-registry existence.
 *
 * <p>Slice 3 exercises this through {@link StubKybAdapter} only; the Octa
 * Solution adapter (ADR-014) fills the registry checks with real vendor data
 * once sandbox credentials land.
 *
 * <ul>
 *   <li>{@code screening} — the embedded sanctions/PEP screening result of the
 *       same run.</li>
 *   <li>{@code licenseVerified} — vendor confirmed the operating license
 *       against the issuing authority.</li>
 *   <li>{@code uboVerified} — declared UBO set matches the vendor's
 *       beneficial-ownership register view.</li>
 *   <li>{@code registryVerified} — entity exists and is active in the
 *       corporate registry of its incorporation country.</li>
 *   <li>{@code providerRef} — vendor reference for the full run (distinct from
 *       the screening's own ref).</li>
 *   <li>{@code completedAt} — run completion instant (UTC, microsecond
 *       truncated by well-behaved providers).</li>
 * </ul>
 */
public record KybRunResult(
        ScreeningResult screening,
        boolean licenseVerified,
        boolean uboVerified,
        boolean registryVerified,
        String providerRef,
        Instant completedAt) {
}
