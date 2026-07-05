package com.gme.pay.settlement.calculator;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multilateral netting across corridors (flywheel loop C —
 * {@code docs/SETTLEMENT_NETTING_DESIGN.md}, tracker item #6).
 *
 * <p>The hub is the central counterparty: every settlement obligation is bilateral
 * between the hub and ONE counterparty (a sending partner or an acquiring scheme),
 * expressed in USD (the platform pivot, rate-fx RATE-04). Opposing corridor flows
 * against the same counterparty offset — e.g. what the hub owes NepalPay for
 * Korea→Nepal spend offsets what NepalPay owes the hub for Nepal→Korea spend —
 * so each new two-sided corridor REDUCES the funding a counterparty must trap in
 * prefund, which is the loop-C capital-efficiency turn.
 *
 * <p>Sign convention: {@code amountUsd > 0} = the hub owes the counterparty
 * (payable); {@code amountUsd < 0} = the counterparty owes the hub (receivable).
 * Never nets across two different counterparties — that requires a scheme-level
 * netting agreement the platform does not have (see design note §4).
 *
 * <p>USD scale 2, HALF_UP, per {@code docs/MONEY_CONVENTION.md}.
 */
@Component
public class MultilateralNettingCalculator {

    private static final int USD_SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * One bilateral settlement obligation between the hub and {@code counterparty}
     * for {@code corridor} (e.g. "KR-NP"). Positive = hub pays; negative = hub receives.
     */
    public record Obligation(String counterparty, String corridor, BigDecimal amountUsd) {
        public Obligation {
            if (counterparty == null || counterparty.isBlank()) {
                throw new IllegalArgumentException("counterparty is required");
            }
            if (amountUsd == null) {
                throw new IllegalArgumentException("amountUsd is required");
            }
        }
    }

    /**
     * The netted position for one counterparty: {@code netUsd > 0} = the hub pays
     * the counterparty once; {@code netUsd < 0} = the counterparty pays the hub once.
     * {@code grossUsd} is the sum of absolute obligations that were collapsed.
     */
    public record Position(String counterparty, BigDecimal grossUsd, BigDecimal netUsd) {}

    /**
     * The full netting run: per-counterparty positions plus the loop-C headline
     * numbers — {@code grossFundingUsd} (what settling every obligation gross would
     * move), {@code netFundingUsd} (what actually moves after netting) and
     * {@code nettingEfficiencyPct} (capital freed, 0 when nothing offsets;
     * {@code null} when there are no obligations at all).
     */
    public record Summary(
            LocalDate valueDate,
            List<Position> positions,
            BigDecimal grossFundingUsd,
            BigDecimal netFundingUsd,
            BigDecimal nettingEfficiencyPct) {}

    /**
     * Collapse the window's obligations into one net position per counterparty.
     * Ordering of positions follows first appearance of each counterparty, so a
     * settlement file built from the summary is deterministic.
     */
    public Summary calculate(LocalDate valueDate, List<Obligation> obligations) {
        Map<String, BigDecimal> gross = new LinkedHashMap<>();
        Map<String, BigDecimal> net = new LinkedHashMap<>();

        if (obligations != null) {
            for (Obligation o : obligations) {
                gross.merge(o.counterparty(), o.amountUsd().abs(), BigDecimal::add);
                net.merge(o.counterparty(), o.amountUsd(), BigDecimal::add);
            }
        }

        List<Position> positions = gross.keySet().stream()
                .map(cp -> new Position(
                        cp,
                        scaled(gross.get(cp)),
                        scaled(net.get(cp))))
                .toList();

        BigDecimal grossFunding = scaled(gross.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal netFunding = scaled(net.values().stream()
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add));

        BigDecimal efficiencyPct = null;
        if (grossFunding.signum() > 0) {
            efficiencyPct = grossFunding.subtract(netFunding)
                    .multiply(HUNDRED)
                    .divide(grossFunding, USD_SCALE, RoundingMode.HALF_UP);
        }

        return new Summary(valueDate, positions, grossFunding, netFunding, efficiencyPct);
    }

    private static BigDecimal scaled(BigDecimal v) {
        return v.setScale(USD_SCALE, RoundingMode.HALF_UP);
    }
}
