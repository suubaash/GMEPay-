package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gme.pay.domain.PartnerType;

import java.math.RoundingMode;
import java.time.Instant;

/**
 * Canonical read DTO for a partner — the JSON shape every config-registry consumer
 * deserializes. Lives in {@code lib-api-contracts} so cross-service callers
 * (auth-identity, notification-webhook, ops-partner-bff, settlement-reconciliation)
 * bind to one stable shape instead of each mirroring its own copy of the partner
 * record. Resolves the 5-DTO drift documented in
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Cross-cutting bug fixes".
 *
 * <h2>Field roster (Slice 1)</h2>
 *
 * <p>Slice 1 collects the Identity step of the wizard; later slices add Contacts,
 * KYB, Banking, etc. The roster below carries every field a UI or BFF needs to
 * read on a partner row right after the Identity step lands. Fields that later
 * slices populate stay {@code null} on a Slice 1 draft — that's expected and
 * intentional.
 *
 * <ul>
 *   <li>{@code id} — V003 {@code BIGINT} surrogate, the universal join key every
 *       consuming service stores as its partner foreign key. {@code null} on
 *       freshly-built rows whose insert has not yet flushed.</li>
 *   <li>{@code partnerCode} — V003 {@code VARCHAR(20) UNIQUE} business code
 *       (e.g. {@code "GMEREMIT"}). Operators type this; URL paths route by it.</li>
 *   <li>{@code type} — LOCAL vs OVERSEAS partner classification.</li>
 *   <li>{@code settlementCurrency}, {@code settlementRoundingMode} — settlement
 *       policy (the four-field demo aggregate carried forward).</li>
 *   <li>Slice 6 currency split (V016, ADR-013 Expand phase):
 *       {@code collectionCcy} — the currency the partner collects from its
 *       senders in; {@code settleACcy} — the currency GME settles with the
 *       partner in. Backfilled from {@code settlementCurrency} for pre-Slice-6
 *       rows; the legacy {@code settlementCurrency} stays populated for one
 *       more release (the Contract migration drops it later).</li>
 *   <li>Identity (Slice 1, fields the wizard collects):
 *     <ul>
 *       <li>{@code legalNameLocal} — name in the partner's local script
 *           (e.g. {@code "주식회사 지엠이"}).</li>
 *       <li>{@code legalNameRomanized} — romanized / English-equivalent name
 *           (e.g. {@code "GME Co., Ltd."}).</li>
 *       <li>{@code taxId} — registration number. Format depends on
 *           {@code taxIdType}.</li>
 *       <li>{@code taxIdType} — discriminator: {@code KR-BRN}, {@code KH-VAT},
 *           {@code VN-MST}, {@code SG-UEN}, {@code GENERIC}.</li>
 *       <li>{@code countryOfIncorporation} — ISO-3166 alpha-2.</li>
 *       <li>{@code legalForm} — enum surface: {@code CORP}, {@code LLC},
 *           {@code MTO}, {@code EMI}, {@code BANK}, {@code OTHER}. Carried as
 *           String here so the contracts module doesn't have to grow an enum
 *           per discrimination field; the service layer validates.</li>
 *       <li>{@code registeredAddress}, {@code operatingAddress} — structured
 *           postal addresses; see {@link AddressView}.</li>
 *       <li>{@code lei} — ISO-17442 Legal Entity Identifier (20-char). Tracked
 *           but optional.</li>
 *     </ul>
 *   </li>
 *   <li>Lifecycle (Slice 1 starts every partner at {@link PartnerStatus#ONBOARDING},
 *       see ADR-011): {@code status}.</li>
 *   <li>Bitemporal stamps (ADR-010, SCD Type 6): {@code validFrom}, {@code validTo}
 *       (half-open window {@code [validFrom, validTo)}, NULL upper bound =
 *       open-ended), {@code recordedAt} (system insert time of this version).</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} so {@code null} fields stay on the wire — the
 * UI relies on the field being present to know "this slice hasn't populated it
 * yet" vs "the server forgot to serialise it".
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PartnerView(
        Long id,
        String partnerCode,
        PartnerType type,
        String settlementCurrency,
        RoundingMode settlementRoundingMode,
        String collectionCcy,
        String settleACcy,
        String legalNameLocal,
        String legalNameRomanized,
        String taxId,
        String taxIdType,
        String countryOfIncorporation,
        String legalForm,
        AddressView registeredAddress,
        AddressView operatingAddress,
        String lei,
        PartnerStatus status,
        Instant validFrom,
        Instant validTo,
        Instant recordedAt) {

    /**
     * Compact factory for the Slice 1 four-field aggregate (partnerCode, type,
     * settlementCurrency, settlementRoundingMode). Every later-slice field is
     * filled with {@code null} and {@code status} defaults to
     * {@link PartnerStatus#ONBOARDING}. Used by config-registry's controller to
     * adapt the legacy domain {@code Partner} record into the canonical view
     * during the Expand phase, before the wider Identity columns ship.
     *
     * <p>The Slice 6 split mirrors the legacy single currency on both sides —
     * the same defensive mirroring {@code PartnerEntity} applies on persist
     * (V016 Expand phase): before the commercial-terms step writes a real
     * split, collection and settlement are the same fact.
     */
    public static PartnerView ofCore(Long id, String partnerCode, PartnerType type,
                                     String settlementCurrency,
                                     RoundingMode settlementRoundingMode) {
        return new PartnerView(
                id,
                partnerCode,
                type,
                settlementCurrency,
                settlementRoundingMode,
                settlementCurrency,
                settlementCurrency,
                null, null, null, null, null, null, null, null, null,
                PartnerStatus.ONBOARDING,
                null, null, null);
    }
}
