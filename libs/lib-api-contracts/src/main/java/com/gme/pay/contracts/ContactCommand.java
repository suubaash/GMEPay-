package com.gme.pay.contracts;

/**
 * Write shape for one partner contact — the JSON object the UI / BFF sends as an
 * element of the step-2 bulk-replace payload ({@link PartnerCommand.UpdateStep2}).
 * Fields mirror {@link ContactView}; see that record for the field-level contract.
 *
 * <p>{@code role} is carried as a String (not an enum) following the
 * {@code legalForm} precedent on {@link PartnerView} — the contracts module does
 * not grow an enum per discrimination field; config-registry validates the value
 * against its {@code ContactRole} roster (OPS_24X7, FINANCE, COMPLIANCE_MLRO,
 * TECH, LEGAL, INCIDENT) and rejects unknown values with 400.
 *
 * <p>Server-side validation (config-registry {@code PartnerContactService}):
 * <ul>
 *   <li>{@code role} — required, one of the roster above.</li>
 *   <li>{@code name} — required, non-blank, &le; 120 chars.</li>
 *   <li>{@code email} — required, RFC-shaped ({@code local@domain.tld}),
 *       &le; 254 chars.</li>
 *   <li>{@code phoneE164} — optional; when present must match E.164
 *       ({@code ^\+[1-9]\d{1,14}$}).</li>
 *   <li>{@code authorizedSignatory} — optional; {@code null} is treated as
 *       {@code false}.</li>
 *   <li>{@code notes} — optional, &le; 500 chars.</li>
 * </ul>
 */
public record ContactCommand(
        String role,
        String name,
        String email,
        String phoneE164,
        Boolean authorizedSignatory,
        String notes) {
}
