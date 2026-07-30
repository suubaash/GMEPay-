package com.gme.pay.settlement.netting;

import com.gme.pay.settlement.calculator.MultilateralNettingCalculator;
import com.gme.pay.settlement.persistence.CorridorReconSummaryEntity;
import com.gme.pay.settlement.persistence.CorridorReconSummaryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The production caller of {@link MultilateralNettingCalculator} (GAP T4-5).
 *
 * <h2>Why this class exists</h2>
 * The calculator was a complete loop-C design — per-counterparty positions, {@code netFundingUsd},
 * {@code nettingEfficiencyPct} — with <b>no production caller at all</b>: only a unit test. That is the
 * worst of the three possible states, because the capability looked available from the outside and was
 * not reachable by anyone. This service resolves that by wiring the <em>reporting</em> half to a real
 * surface finance can run, and by being explicit that <em>applying</em> netting to funding is a
 * decision rather than a missing line of code (see {@link NettingReportResponse#REPORTING_ONLY_NOTE}).
 *
 * <h2>Where the obligations come from</h2>
 * {@code corridor_recon_summary} (V011) — the persisted per-day, per-scheme cross-border tie-out. Its
 * {@code usdOwedScheme} is a real, reconciled USD obligation of the hub to that scheme for that
 * corridor and day, so an obligation per {@code (scheme, corridor, day)} needs no new computation and
 * no FX guesswork. The sign convention matches the calculator's: positive = the hub owes the
 * counterparty. Nothing invents a receivable — a corridor that only flows one way contributes only one
 * side, which is exactly why the efficiency figure is {@code 0.00} today and why it will move on its
 * own the first time a genuinely two-sided corridor is reconciled.
 *
 * <p>Netting is applied <b>within</b> a counterparty only, never across two of them — the calculator's
 * documented rule; cross-counterparty netting needs a scheme-level agreement the platform has no basis
 * for asserting.
 */
@Service
@Transactional(readOnly = true)
public class NettingReportService {

    /** Guard on the report window, matching the settlement statement's bound. */
    public static final int MAX_WINDOW_DAYS = 400;

    private final CorridorReconSummaryRepository summaryRepository;
    private final MultilateralNettingCalculator calculator;

    public NettingReportService(CorridorReconSummaryRepository summaryRepository,
                               MultilateralNettingCalculator calculator) {
        this.summaryRepository = summaryRepository;
        this.calculator = calculator;
    }

    /**
     * Build the netting report over a business-date window.
     *
     * @param from inclusive window start
     * @param to   inclusive window end
     * @throws IllegalArgumentException on an inverted or over-wide window
     */
    public NettingReportResponse report(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("both from and to are required (ISO yyyy-MM-dd)");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from (" + from + ") is after to (" + to + ")");
        }
        long days = to.toEpochDay() - from.toEpochDay() + 1;
        if (days > MAX_WINDOW_DAYS) {
            throw new IllegalArgumentException(
                    "window of " + days + " days exceeds the " + MAX_WINDOW_DAYS + "-day maximum");
        }

        List<CorridorReconSummaryEntity> rows =
                summaryRepository.findBySettlementDateBetweenOrderBySettlementDateAscSchemeAsc(from, to);

        List<MultilateralNettingCalculator.Obligation> obligations = new ArrayList<>();
        Map<String, List<String>> corridorsByCounterparty = new LinkedHashMap<>();
        LinkedHashSet<String> sourceCorridors = new LinkedHashSet<>();
        boolean schemeFeedBackedAll = !rows.isEmpty();

        for (CorridorReconSummaryEntity row : rows) {
            if (row.getUsdOwedScheme() == null) {
                continue;
            }
            obligations.add(new MultilateralNettingCalculator.Obligation(
                    row.getScheme(), row.getCorridor(), row.getUsdOwedScheme()));
            corridorsByCounterparty
                    .computeIfAbsent(row.getScheme(), k -> new ArrayList<>())
                    .add(row.getCorridor());
            sourceCorridors.add(row.getScheme() + ":" + row.getCorridor());
            if (!row.isSchemeFeedAvailable()) {
                schemeFeedBackedAll = false;
            }
        }
        // De-duplicate the per-counterparty corridor list while keeping first-appearance order.
        corridorsByCounterparty.replaceAll((cp, list) -> List.copyOf(new LinkedHashSet<>(list)));

        MultilateralNettingCalculator.Summary summary = calculator.calculate(to, obligations);
        return NettingReportResponse.of(from, to, summary, obligations.size(),
                corridorsByCounterparty, List.copyOf(sourceCorridors), schemeFeedBackedAll);
    }
}
