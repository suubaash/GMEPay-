package com.gme.pay.kybadapter.kyb;

import com.gme.pay.kyb.ScreeningResult;
import java.time.Instant;
import java.util.List;

/**
 * Outcome of a full KYB verification run — the synchronous response of
 * {@code POST /v1/kyb/verify} and {@code GET /v1/kyb/result/{ref}}, and the
 * payload of the {@code gmepay.kyb.verification} event.
 *
 * <p>Carries the collapsed {@link #decision} plus the evidence that produced it
 * (the raw sanctions screening, the business-registration verdict, and which
 * required documents were missing) so the wizard can render the full picture
 * and an analyst can disposition a {@link KybDecision#MANUAL_REVIEW} without a
 * second call.
 *
 * @param providerRef    deterministic vendor reference of the run — the
 *                       idempotency key and the {@code GET .../result/{ref}}
 *                       path variable.
 * @param partnerCode    the screened entity's business code.
 * @param decision       the collapsed verdict.
 * @param decisionReason one-line human-readable explanation.
 * @param screeningStatus sanctions/PEP disposition (lib-kyb).
 * @param hits           the individual sanctions/PEP list matches.
 * @param bizRegStatus   business-registration verdict.
 * @param missingDocuments required documents the operator did not supply.
 * @param idempotentReplay {@code true} when this result was served from the
 *                       persisted run rather than freshly computed.
 * @param screenedAt     run completion instant (UTC, MICROS).
 * @param screeningProviderId    WHICH provider produced the screening — {@code stub},
 *                       {@code unknown}, or a vendor id (V002 / gap T1-4).
 * @param screeningAuthoritative {@code true} only when a real screening provider
 *                       consulted sanctions / PEP / adverse-media sources. A
 *                       {@code false} here means <b>nothing was screened</b>,
 *                       whatever {@link #decision} says.
 * @param screeningCaveat why the run is not authoritative; {@code null} on an
 *                       authoritative run. Travels with the verdict so no reader
 *                       downstream can present the run as a completed check.
 */
public record KybVerificationResult(
        String providerRef,
        String partnerCode,
        KybDecision decision,
        String decisionReason,
        ScreeningResult.Status screeningStatus,
        List<ScreeningResult.Hit> hits,
        BusinessRegistrationVerifier.BizRegStatus bizRegStatus,
        List<String> missingDocuments,
        boolean idempotentReplay,
        Instant screenedAt,
        String screeningProviderId,
        boolean screeningAuthoritative,
        String screeningCaveat) {

    /** Null-safe accessor for the hit list. */
    public List<ScreeningResult.Hit> hitList() {
        return hits == null ? List.of() : hits;
    }

    /** Null-safe accessor for the missing-documents list. */
    public List<String> missingDocumentList() {
        return missingDocuments == null ? List.of() : missingDocuments;
    }

    /** Returns a copy with {@code idempotentReplay} set — used when serving a stored run. */
    public KybVerificationResult asReplay() {
        return new KybVerificationResult(providerRef, partnerCode, decision, decisionReason,
                screeningStatus, hits, bizRegStatus, missingDocuments, true, screenedAt,
                screeningProviderId, screeningAuthoritative, screeningCaveat);
    }

    /**
     * {@code true} when this verdict rests on a screening that actually happened.
     * A caller treating a KYB run as satisfied MUST test this — the
     * {@link #decision} alone does not distinguish "checked and clean" from
     * "nothing was checked".
     */
    public boolean screeningPerformed() {
        return screeningAuthoritative
                && screeningStatus != ScreeningResult.Status.NOT_SCREENED_NO_PROVIDER;
    }
}
