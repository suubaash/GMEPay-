package com.gme.pay.kyb;

import java.math.BigDecimal;
import java.util.List;

/**
 * The legal entity being screened — the vendor-agnostic input to every
 * {@link KybProvider} call (ADR-009).
 *
 * <p>Deliberately a flat value record: the KYB port must not depend on
 * config-registry's persistence types (the dependency arrow points the other
 * way — services bind to this port). Callers project whatever aggregate they
 * hold (partner row + KYB sub-resource) into this shape at the call site.
 *
 * <ul>
 *   <li>{@code partnerCode} — the human-facing business code (e.g.
 *       {@code "GMEREMIT"}). Used as the correlation / aggregate key on the
 *       resulting {@code gmepay.kyb.screening} event.</li>
 *   <li>{@code legalNameLocal} / {@code legalNameRomanized} — both name forms
 *       are screened; sanctions lists carry romanized names, KoFIU lists carry
 *       local-script names.</li>
 *   <li>{@code countryOfIncorporation} — ISO-3166 alpha-2.</li>
 *   <li>{@code taxId} — jurisdiction-specific registration / tax number.</li>
 *   <li>{@code uboList} — ultimate beneficial owners per FATF R.24; each UBO is
 *       screened individually (PEP + sanctions).</li>
 * </ul>
 */
public record KybSubject(
        String partnerCode,
        String legalNameLocal,
        String legalNameRomanized,
        String countryOfIncorporation,
        String taxId,
        List<Ubo> uboList) {

    /**
     * One ultimate beneficial owner.
     *
     * <ul>
     *   <li>{@code name} — full romanized name as captured in wizard step 3.</li>
     *   <li>{@code ownershipPct} — percentage stake (0–100), plain decimal.</li>
     *   <li>{@code isPep} — operator-declared politically-exposed-person flag;
     *       providers re-derive this themselves, the declared flag rides along
     *       so declared-vs-detected mismatches can be surfaced.</li>
     *   <li>{@code country} — ISO-3166 alpha-2 of residence/nationality.</li>
     * </ul>
     */
    public record Ubo(
            String name,
            BigDecimal ownershipPct,
            boolean isPep,
            String country) {
    }

    /** Null-safe accessor: an absent UBO list reads as empty, never {@code null}. */
    public List<Ubo> ubos() {
        return uboList == null ? List.of() : uboList;
    }
}
