package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Canonical read DTO for one partner KYB document row (Slice 3 — see
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 3 — KYB"). This is METADATA only;
 * the bytes live in the ADR-006 vault and are served by the dedicated
 * {@code .../documents/{docId}/content} passthrough, never inlined in JSON.
 *
 * <ul>
 *   <li>{@code id} — BIGSERIAL surrogate of the document ROW; under SCD-6 a
 *       re-upload mints a fresh row, so the id identifies a stored version, not
 *       "the license" in the abstract. This is the {@code {docId}} the content
 *       endpoint takes — historical ids stay downloadable (version history).</li>
 *   <li>{@code docType} — {@code LICENSE}, {@code CERT_INCORPORATION},
 *       {@code AOA}, {@code BOARD_RESOLUTION}, {@code UBO_DECLARATION},
 *       {@code FINANCIALS}, {@code CBDDQ}, {@code OTHER}. Carried as String per
 *       the {@code ContactView.role} precedent; config-registry validates the
 *       roster.</li>
 *   <li>{@code filename} / {@code contentType} — as uploaded; echoed on
 *       download.</li>
 *   <li>{@code vaultUri} — opaque ADR-006 locator of the immutable object
 *       (admin-facing diagnostics; the UI downloads via the content endpoint,
 *       never directly from the vault).</li>
 *   <li>{@code version} — 1-based per {@code (partner, docType)}; matches the
 *       {@code v<n>} segment of {@code vaultUri}.</li>
 *   <li>{@code sha256} — lowercase hex digest of the stored bytes.</li>
 *   <li>{@code expiryDate} — expiry printed on the document itself;
 *       {@code null} = non-expiring.</li>
 *   <li>{@code verifiedBy} / {@code verifiedAt} — operator verification stamp;
 *       {@code null} until the verification flow (later Slice-3 ticket) sets
 *       them.</li>
 *   <li>Bitemporal stamps (ADR-010): {@code validFrom} / {@code validTo} /
 *       {@code recordedAt}, same contract as {@link ContactView}.</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} so {@code null} fields stay on the wire — the
 * UI relies on field presence, same contract as {@link PartnerView}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DocumentView(
        Long id,
        String docType,
        String filename,
        String contentType,
        String vaultUri,
        Integer version,
        String sha256,
        LocalDate expiryDate,
        String verifiedBy,
        Instant verifiedAt,
        Instant validFrom,
        Instant validTo,
        Instant recordedAt) {
}
