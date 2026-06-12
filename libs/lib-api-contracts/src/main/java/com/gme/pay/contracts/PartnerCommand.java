package com.gme.pay.contracts;

import com.gme.pay.domain.PartnerType;

import java.math.RoundingMode;
import java.util.List;

/**
 * Canonical write surface for a partner. Sealed-style wrapper carrying the
 * specific sub-command the caller wants to apply — keeps every partner-write
 * payload deserializing into a single inbound type for the controller layer.
 *
 * <p>Slice 1 introduced two sub-commands; Slice 2 adds the third:
 * <ul>
 *   <li>{@link CreateDraft} — first wizard submission, materialises a row in
 *       {@code partner} with {@code status=ONBOARDING}.</li>
 *   <li>{@link UpdateStep1} — bitemporal mutation of the Identity step on an
 *       existing draft.</li>
 *   <li>{@link UpdateStep2} — bulk replace of the Contacts step on an existing
 *       draft (Slice 2 — see {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 2 —
 *       Contacts").</li>
 * </ul>
 *
 * <p>Later slices add more (e.g. {@code AttachKyb}); each lands as another
 * nested record without churning the wrapper or any existing consumer.
 *
 * <p>The wrapper holds the sub-commands as {@code Object} typed fields so the
 * existing Spring Web JSON-binding path keeps working without introducing a
 * Jackson polymorphism layer at this stage. Callers that take the
 * {@link PartnerCommand} wrapper inspect the nested non-null field to dispatch.
 */
public record PartnerCommand(
        CreateDraft createDraft,
        UpdateStep1 updateStep1,
        UpdateStep2 updateStep2) {

    /** Convenience constructor for a create-draft command. */
    public static PartnerCommand create(CreateDraft draft) {
        return new PartnerCommand(draft, null, null);
    }

    /** Convenience constructor for an update-step-1 command. */
    public static PartnerCommand update(UpdateStep1 update) {
        return new PartnerCommand(null, update, null);
    }

    /** Convenience constructor for an update-step-2 (contacts) command. */
    public static PartnerCommand updateContacts(UpdateStep2 update) {
        return new PartnerCommand(null, null, update);
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

    /**
     * Body for "Save step-2 changes" (Contacts) on an already-created draft —
     * Slice 2. The wizard's contract is <b>bulk replace</b>: {@code contacts}
     * carries the FULL desired contact set, and the service supersedes every
     * current {@code partner_contact} row and inserts the new set in one
     * transaction (SCD-6 paired writes per ADR-010). An empty list therefore
     * clears all contacts; {@code null} is rejected with 400.
     *
     * <p>The &ge;4-distinct-roles requirement is enforced by the activation
     * gate (Slice 8), not by this payload — the wizard saves partial progress.
     */
    public record UpdateStep2(List<ContactCommand> contacts) {
    }

    /**
     * Body for "Save step-4 changes" (Banking &amp; Settlement) on an
     * already-created draft — Slice 4. Same <b>bulk replace</b> contract as
     * {@link UpdateStep2}: {@code bankAccounts} carries the FULL desired
     * bank-account set, and config-registry supersedes every current
     * {@code partner_bank_account} row and inserts the new set in one
     * transaction (SCD-6 paired writes per ADR-010). An empty list clears all
     * bank accounts; {@code null} is rejected with 400.
     *
     * <p>Verification status is provider-stamped, not operator-typed: a bulk
     * replace carries an existing verification verdict forward when the
     * (currency, ibanOrAccountNumber) pair is unchanged, and resets to
     * UNVERIFIED otherwise. During ONBOARDING these writes go direct
     * (audited); the 2-authorized-signatory approval flow for post-activation
     * bank changes lands with the Slice 8 FSM.
     *
     * <p>Per the wrapper's contract this lands as another nested record
     * without churning the wrapper's component list or any existing consumer;
     * the step-4 controller binds this record directly.
     */
    public record UpdateStep4(List<BankAccountCommand> bankAccounts) {
    }

    /**
     * Body for "Save step-4 settlement settings" on an already-created draft —
     * Slice 4 (Banking &amp; Settlement), the sibling of {@link UpdateStep4}:
     * step 4's bank-account rows ride {@link UpdateStep4}, the per-partner
     * settlement parameters ride this record onto
     * {@code PATCH /v1/partners/draft/{code}/step-4-settlement}. Full-state
     * replace of the {@code partner_settlement_config} row (SCD-6 paired write
     * per ADR-010), the same discipline as {@link KybCommand.UpdateStep3}.
     *
     * <ul>
     *   <li>{@code cycleTPlusN} — settlement cycle in BUSINESS days after the
     *       value date, {@code 0..5}; {@code null} defaults to {@code 1}
     *       (T+1).</li>
     *   <li>{@code cutoffTime} — local-time cutoff in {@code cutoffTimezone};
     *       transactions after it book to the next value date. {@code null}
     *       defaults to {@code 16:30}.</li>
     *   <li>{@code cutoffTimezone} — IANA zone id (&le; 40 chars, e.g.
     *       {@code Asia/Seoul}); {@code null} defaults to
     *       {@code Asia/Seoul}.</li>
     *   <li>{@code settlementMethod} — required; one of {@code SWIFT_MT103},
     *       {@code KR_FIRM_BANKING}, {@code BAKONG}, {@code NAPAS_247},
     *       {@code PROMPT_PAY}, {@code FAST_SG}, {@code OTHER} (the V013 CHECK
     *       roster). String per the {@code legalForm} / {@code riskRating}
     *       precedent — config-registry validates the roster.</li>
     * </ul>
     *
     * <p>Per the wrapper's contract this lands as another nested record
     * without churning the wrapper's component list or any existing consumer;
     * the step-4 settlement controller binds this record directly.
     */
    public record UpdateStep4Settlement(
            Integer cycleTPlusN,
            java.time.LocalTime cutoffTime,
            String cutoffTimezone,
            String settlementMethod) {
    }

    /**
     * Body for "Save step-6 pricing rules" on an already-created draft —
     * Slice 6 (Commercial Terms, see {@code docs/PARTNER_SETUP_PLAN.md}
     * §"Slice 6"). Same <b>bulk replace</b> contract as {@link UpdateStep2} /
     * {@link UpdateStep4}: {@code rules} carries the FULL desired rule set,
     * and config-registry supersedes every current {@code partner_rule} row
     * (V017) and inserts the new set in one transaction (SCD-6 paired writes
     * per ADR-010). An empty list clears all rules; {@code null} is rejected
     * with 400. The read shape is {@link RuleView}.
     *
     * <p>At most one rule per ({@code schemeId}, {@code direction}) pair —
     * duplicates in the payload are a 400 (the V017 partial-unique index is
     * the storage-level backstop). Each element is validated against the
     * lib-domain {@code Rule.validate} margin invariant (cross-border
     * {@code mA + mB >= 2%}, same-currency zero margin) using the partner's
     * V016 {@code collection_ccy} / {@code settle_a_ccy} split.
     *
     * <p>Per the wrapper's contract this lands as another nested record
     * without churning the wrapper's component list or any existing consumer;
     * the step-6 rules controller binds this record directly.
     */
    public record UpdateStep6Rules(List<RuleCommand> rules) {
    }

    /**
     * Body for "Save step-6 commercial terms" (fees + FX + limits + contract)
     * on an already-created draft — Slice 6 (see
     * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 6 — Commercial Terms"), the
     * sibling of {@link UpdateStep6Rules}: the rule rows ride that record,
     * the four commercial sub-resources ride this composite onto
     * {@code PATCH /v1/partners/draft/{code}/step-6-commercial}. The read
     * shape is {@link CommercialTermsView}.
     *
     * <p>Section semantics — applied ATOMICALLY (one transaction; a
     * validation failure in any section rolls back all of them):
     * <ul>
     *   <li>{@code feeSchedules} — <b>bulk replace</b> of the partner's
     *       {@code partner_fee_schedule} set (V018), same multi-row contract
     *       as {@link UpdateStep4}: an empty list clears all fee rows;
     *       {@code null} leaves the fee set UNTOUCHED.</li>
     *   <li>{@code fxConfig} — full-state replace of the
     *       {@code partner_fx_config} row (V019); {@code null} leaves it
     *       untouched.</li>
     *   <li>{@code limits} — full-state replace of the
     *       {@code partner_limits} row (V020); {@code null} leaves it
     *       untouched. The 소액해외송금업 ({@code SOAEK_HAEOEMONG}) statutory
     *       caps are enforced server-side.</li>
     *   <li>{@code contract} — full-state replace of the
     *       {@code partner_contract} row (V021); {@code null} leaves it
     *       untouched.</li>
     * </ul>
     *
     * <p>A payload with ALL sections {@code null} is a 400 — there is nothing
     * to save. Each non-null section produces its own SCD-6 paired write
     * (ADR-010) and its own audit row (ADR-007).
     *
     * <p>Per the wrapper's contract this lands as another nested record
     * without churning the wrapper's component list or any existing consumer;
     * the step-6 commercial controller binds this record directly.
     */
    public record UpdateStep6Commercial(
            List<FeeScheduleCommand> feeSchedules,
            FxConfigCommand fxConfig,
            LimitsCommand limits,
            ContractCommand contract) {
    }

    /**
     * Body for "Save step-7 scheme enablements" on an already-created draft —
     * Slice 7 (Scheme Enablement, see {@code docs/PARTNER_SETUP_PLAN.md}
     * §"Slice 7"). Same <b>bulk replace</b> contract as {@link UpdateStep2} /
     * {@link UpdateStep4} / {@link UpdateStep6Rules}: {@code schemes} carries
     * the FULL desired scheme set, and config-registry supersedes every
     * current {@code partner_scheme} row (V022) and inserts the new set in
     * one transaction (SCD-6 paired writes per ADR-010). An empty list clears
     * all schemes; {@code null} is rejected with 400. The read shape is
     * {@link PartnerSchemeView}.
     *
     * <p>At most one row per {@code schemeId} — duplicates in the payload are
     * a 400 (the V022 partial-unique index is the storage-level backstop). An
     * ENABLED {@code ZEROPAY} element must carry {@code zeropayMerchantId} +
     * {@code kftcInstitutionCode} (service-enforced, NOT a DB CHECK — drafts
     * may stay incomplete while the row is disabled).
     *
     * <p>Per the wrapper's contract this lands as another nested record
     * without churning the wrapper's component list or any existing consumer;
     * the step-7 schemes controller binds this record directly.
     */
    public record UpdateStep7Schemes(List<PartnerSchemeCommand> schemes) {
    }

    /**
     * Body for "Save step-7 corridors" on an already-created draft — Slice 7
     * (Schemes &amp; Corridors, see {@code docs/PARTNER_SETUP_PLAN.md}
     * §"Slice 7"), the sibling of {@link UpdateStep7Schemes}: the scheme
     * enablements ride that record, the corridor matrix rides this one. Same
     * <b>bulk replace</b> contract as {@link UpdateStep6Rules}:
     * {@code corridors} carries the FULL desired corridor set, and
     * config-registry supersedes every current {@code partner_corridor} row
     * (V023) and inserts the new set in one transaction (SCD-6 paired writes
     * per ADR-010). An empty list clears all corridors; {@code null} is
     * rejected with 400. The read shape is {@link PartnerCorridorView}.
     *
     * <p>At most one corridor per ({@code srcCountry}, {@code srcCcy},
     * {@code dstCountry}, {@code dstCcy}) lane — duplicates in the payload are
     * a 400 (the V023 partial-unique index is the storage-level backstop).
     *
     * <p>Per the wrapper's contract this lands as another nested record
     * without churning the wrapper's component list or any existing consumer;
     * the step-7 corridors controller binds this record directly.
     */
    public record UpdateStep7Corridors(List<PartnerCorridorCommand> corridors) {
    }

    /**
     * Body for "Save step-5 changes" (Prefunding) on an already-created draft
     * — Slice 5 (see {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 5 —
     * Prefunding"). Full-state replace of the
     * {@code partner_prefunding_config} row (SCD-6 paired write per ADR-010),
     * the same discipline as {@link UpdateStep4Settlement}. The read shape is
     * {@link PrefundingConfigView}.
     *
     * <ul>
     *   <li>{@code fundingModel} — required; {@code PREFUNDED} |
     *       {@code POSTPAID} | {@code HYBRID} (the V015 CHECK roster). String
     *       per the {@code settlementMethod} precedent — config-registry
     *       validates the roster.</li>
     *   <li>Money fields ({@code openingBalanceUsd},
     *       {@code lowBalanceThresholdUsd}, {@code creditLimitUsd},
     *       {@code collateralAmountUsd}) — {@link java.math.BigDecimal} in
     *       major USD units, decimal STRINGS on the wire per
     *       {@code docs/MONEY_CONVENTION.md}; at most 4 decimal places
     *       (NUMERIC(19,4)). {@code lowBalanceThresholdUsd} must be &gt; 0;
     *       {@code null} defaults to {@code 10000}. The others must be &ge; 0
     *       when present; {@code null} = not configured.</li>
     *   <li>{@code alertTier70} / {@code alertTier85} / {@code alertTier95} —
     *       which alert tiers are armed; {@code null} defaults to
     *       {@code true}.</li>
     *   <li>{@code autoSuspendOnBreach} — {@code null} defaults to
     *       {@code true}.</li>
     *   <li>{@code floatTopUpBankAccountId} — must reference a CURRENT
     *       {@code partner_bank_account} row of THIS partner with
     *       {@code purpose=FLOAT_TOPUP} (validated server-side), or
     *       {@code null}.</li>
     *   <li>{@code topUpReferencePattern} — &le; 60 chars and must contain
     *       the {@code {partner_code}} placeholder; {@code null}/blank
     *       defaults to {@code GMP-{partner_code}-{yyyyMMdd}}.</li>
     * </ul>
     *
     * <p>Per the wrapper's contract this lands as another nested record
     * without churning the wrapper's component list or any existing consumer;
     * the step-5 controller binds this record directly.
     */
    public record UpdateStep5(
            String fundingModel,
            @com.fasterxml.jackson.annotation.JsonFormat(
                    shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
            java.math.BigDecimal openingBalanceUsd,
            @com.fasterxml.jackson.annotation.JsonFormat(
                    shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
            java.math.BigDecimal lowBalanceThresholdUsd,
            Boolean alertTier70,
            Boolean alertTier85,
            Boolean alertTier95,
            @com.fasterxml.jackson.annotation.JsonFormat(
                    shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
            java.math.BigDecimal creditLimitUsd,
            Boolean autoSuspendOnBreach,
            Long floatTopUpBankAccountId,
            String topUpReferencePattern,
            @com.fasterxml.jackson.annotation.JsonFormat(
                    shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
            java.math.BigDecimal collateralAmountUsd) {
    }
}
