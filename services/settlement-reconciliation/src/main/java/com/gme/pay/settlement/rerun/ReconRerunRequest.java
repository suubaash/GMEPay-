package com.gme.pay.settlement.rerun;

import java.time.LocalDate;

/**
 * Operator recon re-run request. Exactly one of {@code batchId} / {@code settlementDate} identifies the
 * scope; {@code operatorId} and {@code reason} record who/why for the audit trail.
 *
 * <ul>
 *   <li>{@code batchId} — re-run one persisted settlement batch.</li>
 *   <li>{@code settlementDate} — re-run every batch generated for that business date (both windows).</li>
 * </ul>
 */
public record ReconRerunRequest(
        String batchId,
        LocalDate settlementDate,
        String operatorId,
        String reason) {
}
