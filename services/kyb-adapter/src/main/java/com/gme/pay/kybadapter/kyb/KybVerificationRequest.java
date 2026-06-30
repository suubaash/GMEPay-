package com.gme.pay.kybadapter.kyb;

import com.gme.pay.kyb.KybSubject;
import java.util.List;

/**
 * Input to a full KYB verification run ({@code POST /v1/kyb/verify}) — the
 * subject to screen plus the set of onboarding documents the operator attached
 * in wizard step 3, and an idempotency override flag.
 *
 * <p>Kept separate from lib-kyb's {@code KybSubject} (which is the FROZEN,
 * vendor-agnostic screening input) so the document-completeness concern — a
 * GME-onboarding policy, not a vendor concern — lives in this service.
 *
 * @param subject       the entity + UBOs to screen and verify; required.
 * @param suppliedDocuments the document types the operator attached
 *                      (e.g. {@code BUSINESS_REGISTRATION}, {@code AOA},
 *                      {@code UBO_DECLARATION}); matched case-insensitively
 *                      against {@link #REQUIRED_DOCUMENTS}.
 * @param force         re-run even if a prior run for the same subject (same
 *                      deterministic providerRef) is already persisted; default
 *                      {@code false} → idempotent replay of the stored run.
 */
public record KybVerificationRequest(
        KybSubject subject,
        List<String> suppliedDocuments,
        boolean force) {

    /**
     * Onboarding documents GME requires for an entity KYB pack. Absence of any
     * one downgrades a clean run to {@link KybDecision#MANUAL_REVIEW} (an analyst
     * chases the missing document) rather than failing it outright.
     */
    public static final List<String> REQUIRED_DOCUMENTS =
            List.of("BUSINESS_REGISTRATION", "AOA", "UBO_DECLARATION");

    /** Null-safe accessor: an absent document list reads as empty, never {@code null}. */
    public List<String> documents() {
        return suppliedDocuments == null ? List.of() : suppliedDocuments;
    }
}
