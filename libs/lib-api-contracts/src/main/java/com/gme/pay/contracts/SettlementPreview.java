package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * Result of projecting one transaction instant through a partner's settlement
 * configuration and the business-day calendars (Slice 4 — see
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 4 — Banking &amp; Settlement").
 * Returned by {@code GET /v1/partners/{code}/settlement-preview?txnInstant=ISO}
 * and the BFF pass-through; powers the wizard's "with these settings, your
 * Mon 11:30 KST txn pays out Wed" panel.
 *
 * <ul>
 *   <li>{@code payoutDate} — the projected payout date: value date (cutoff
 *       applied in the config's timezone) plus {@code cycleTPlusN} BUSINESS
 *       days, skipping weekends and the holidays of both KR and the partner's
 *       bank country.</li>
 *   <li>{@code explanation} — the human-readable derivation trail, one line
 *       per step: cutoff verdict, every skipped day with its reason
 *       (weekend / which country's holiday), and the final T+N landing. The
 *       UI renders it verbatim; the operator pastes it into partner comms.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SettlementPreview(
        LocalDate payoutDate,
        List<String> explanation) {
}
