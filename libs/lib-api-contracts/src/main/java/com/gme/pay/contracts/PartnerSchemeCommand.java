package com.gme.pay.contracts;

/**
 * Canonical write payload for ONE scheme enablement on the wizard's step-7
 * scheme editor (Slice 7 — Scheme Enablement, see
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 7"). Scheme rows ride
 * {@link PartnerCommand.UpdateStep7Schemes} as a list — bulk-replace
 * semantics, the same contract as {@link RuleCommand} /
 * {@link BankAccountCommand}.
 *
 * <p>A scheme enablement is keyed by (partner × {@code schemeId}); the partner
 * is identified by the URL, so this payload carries only the scheme half of
 * the key plus the wiring fields. The read shape is {@link PartnerSchemeView}.
 *
 * <ul>
 *   <li>{@code schemeId} — required; one of {@code ZEROPAY} | {@code BAKONG} |
 *       {@code NAPAS_247} | {@code PROMPT_PAY} | {@code FAST_SG} |
 *       {@code QRIS} | {@code KHQR} (the V022 CHECK roster). String per the
 *       {@code settlementMethod} / {@code direction} precedent —
 *       config-registry validates the roster.</li>
 *   <li>{@code direction} — required; {@code INBOUND} | {@code OUTBOUND} |
 *       {@code BOTH}.</li>
 *   <li>{@code role} — required; {@code ACQUIRER} | {@code ISSUER} |
 *       {@code BOTH}.</li>
 *   <li>{@code zeropayMerchantId} / {@code zeropaySubMerchantId} — ZeroPay
 *       routing identifiers, &le; 40 chars; nullable while the draft is
 *       incomplete.</li>
 *   <li>{@code kftcInstitutionCode} — KFTC institution code, &le; 20 chars;
 *       nullable while the draft is incomplete.</li>
 *   <li>{@code partnerTypeChar} — {@code "D"} (direct) | {@code "I"}
 *       (indirect); nullable.</li>
 *   <li>{@code vaultSecretId} — opaque vault locator for the scheme API
 *       credential (ADR-006), &le; 64 chars; nullable.</li>
 *   <li>{@code approvalMethodCpm} / {@code approvalMethodMpm} —
 *       {@code CONFIRMATION} | {@code SILENT}; nullable.</li>
 *   <li>{@code enabled} — {@code null} defaults to {@code true}. An ENABLED
 *       {@code ZEROPAY} row must carry {@code zeropayMerchantId} +
 *       {@code kftcInstitutionCode} (service-enforced, 400 — drafts may stay
 *       incomplete only while the row is disabled).</li>
 * </ul>
 */
public record PartnerSchemeCommand(
        String schemeId,
        String direction,
        String role,
        String zeropayMerchantId,
        String zeropaySubMerchantId,
        String kftcInstitutionCode,
        String partnerTypeChar,
        String vaultSecretId,
        String approvalMethodCpm,
        String approvalMethodMpm,
        Boolean enabled) {
}
