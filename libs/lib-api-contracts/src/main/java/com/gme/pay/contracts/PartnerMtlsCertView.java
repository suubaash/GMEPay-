package com.gme.pay.contracts;

import java.time.Instant;

/**
 * Read shape of one CURRENT {@code partner_mtls_cert} row (V027) — Slice 8
 * Lane B. Returned by {@code GET /v1/admin/partners/{code}/mtls-cert} and by
 * the step-8 upload PATCH.
 *
 * <p>Deliberately EXCLUDES the PEM body: consumers match on
 * {@code fingerprintSha256} (SHA-256 over the DER encoding, lowercase hex);
 * the PEM itself stays inside config-registry. The write shape is
 * {@link PartnerCommand.UploadMtlsCert}.
 *
 * <ul>
 *   <li>{@code notBefore} / {@code notAfter} — the X.509 artifact's own
 *       validity window (parsed at upload), NOT the SCD-6 business time of
 *       the binding row.</li>
 *   <li>{@code status} — {@code ACTIVE} | {@code EXPIRED} | {@code REVOKED}
 *       (the V027 CHECK roster).</li>
 * </ul>
 */
public record PartnerMtlsCertView(
        Long id,
        String environment,
        String fingerprintSha256,
        String subjectDn,
        String issuerDn,
        Instant notBefore,
        Instant notAfter,
        String status,
        Instant recordedAt) {
}
