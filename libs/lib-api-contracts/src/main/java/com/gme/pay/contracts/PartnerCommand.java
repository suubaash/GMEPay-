package com.gme.pay.contracts;

import com.gme.pay.domain.PartnerType;

import java.math.RoundingMode;

/**
 * Canonical write surface for a partner. Sealed-style wrapper carrying the
 * specific sub-command the caller wants to apply — keeps every partner-write
 * payload deserializing into a single inbound type for the controller layer.
 *
 * <p>Slice 1 introduces two sub-commands:
 * <ul>
 *   <li>{@link CreateDraft} — first wizard submission, materialises a row in
 *       {@code partner} with {@code status=ONBOARDING}.</li>
 *   <li>{@link UpdateStep1} — bitemporal mutation of the Identity step on an
 *       existing draft.</li>
 * </ul>
 *
 * <p>Later slices add more (e.g. {@code AddContact}, {@code AttachKyb}); each
 * lands as another nested record without churning the wrapper or any existing
 * consumer.
 *
 * <p>The wrapper holds the sub-commands as {@code Object} typed fields so the
 * existing Spring Web JSON-binding path keeps working without introducing a
 * Jackson polymorphism layer at this stage. Callers that take the
 * {@link PartnerCommand} wrapper inspect the nested non-null field to dispatch.
 */
public record PartnerCommand(
        CreateDraft createDraft,
        UpdateStep1 updateStep1) {

    /** Convenience constructor for a create-draft command. */
    public static PartnerCommand create(CreateDraft draft) {
        return new PartnerCommand(draft, null);
    }

    /** Convenience constructor for an update-step-1 command. */
    public static PartnerCommand update(UpdateStep1 update) {
        return new PartnerCommand(null, update);
    }

    /**
     * Body for the Slice 1 "Create draft" wizard submission. The fields here are
     * the minimum the controller needs to instantiate a row — Identity-step UI
     * may collect them progressively, but step-1 completion sends them all at
     * once.
     *
     * <ul>
     *   <li>{@code partnerCode} — required; must be unique across all partners.</li>
     *   <li>{@code type} — LOCAL or OVERSEAS.</li>
     *   <li>{@code settlementCurrency} — ISO-4217 (e.g. {@code "USD"},
     *       {@code "KRW"}). Stays as the single field in Slice 1; Slice 6 splits
     *       it into {@code collection_ccy} + {@code settle_a_ccy}.</li>
     *   <li>{@code settlementRoundingMode} — JVM {@link RoundingMode}; defaults
     *       to {@code HALF_UP} when {@code null}.</li>
     *   <li>Identity (Slice 1 step-1): {@code legalNameLocal},
     *       {@code legalNameRomanized}, {@code taxId}, {@code taxIdType},
     *       {@code countryOfIncorporation}, {@code legalForm},
     *       {@code registeredAddress}, {@code operatingAddress}, {@code lei}.
     *       The controller is responsible for which subset is mandatory at
     *       create time; the contract carries the lot so the UI can submit
     *       whatever the wizard has captured.</li>
     * </ul>
     */
    public record CreateDraft(
            String partnerCode,
            PartnerType type,
            String settlementCurrency,
            RoundingMode settlementRoundingMode,
            String legalNameLocal,
            String legalNameRomanized,
            String taxId,
            String taxIdType,
            String countryOfIncorporation,
            String legalForm,
            AddressCommand registeredAddress,
            AddressCommand operatingAddress,
            String lei) {
    }

    /**
     * Body for "Save step-1 changes" on an already-created draft. Same shape as
     * {@link CreateDraft} minus {@code partnerCode} (the URL identifies which
     * partner is being mutated, not the body). Bitemporal mutation rules apply:
     * the service layer INSERTs a new partner row with a fresh {@code recorded_at}
     * and supersedes the prior version in the same transaction (ADR-010).
     */
    public record UpdateStep1(
            PartnerType type,
            String settlementCurrency,
            RoundingMode settlementRoundingMode,
            String legalNameLocal,
            String legalNameRomanized,
            String taxId,
            String taxIdType,
            String countryOfIncorporation,
            String legalForm,
            AddressCommand registeredAddress,
            AddressCommand operatingAddress,
            String lei) {
    }
}
