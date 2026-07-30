package com.gme.pay.settlement.netting;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.gme.pay.settlement.calculator.MultilateralNettingCalculator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The multilateral netting <b>report</b> finance can run (GAP T4-5).
 *
 * <h2>Reporting only — applying it is a decision, and this says so</h2>
 * {@link #applied} is always {@code false} and {@link #appliedNote} explains why in the payload
 * itself. Netting changes how much cash a counterparty must trap in prefund; deciding to fund on a
 * netted basis means agreeing with each counterparty which obligations offset, over which window, and
 * who carries the intraday gap — a commercial and treasury decision, not a calculation. So the numbers
 * are produced and shown, and nothing downstream consumes them: no prefunding call, no funding
 * instruction, no settlement file. That is deliberate, and it is stated rather than left ambiguous.
 *
 * @param from                 window start (inclusive)
 * @param to                   window end (inclusive)
 * @param positions            one netted position per counterparty; {@code netUsd > 0} = the hub pays
 * @param grossFundingUsd      what settling every obligation gross would move
 * @param netFundingUsd        what would move after offsetting within each counterparty
 * @param nettingEfficiencyPct capital freed as a percentage; {@code 0} when nothing offsets,
 *                             {@code null} when the window contains no obligations at all
 * @param obligationCount      how many obligations were collapsed — {@code 0} means the report is
 *                             empty because nothing has been reconciled in the window, not because
 *                             netting achieved nothing
 * @param sourceCorridors      the corridors the obligations came from, so a reader can see the
 *                             coverage rather than assuming it is complete
 * @param schemeFeedBackedAll  {@code true} only when EVERY source row was backed by a real partner
 *                             settlement file. False today: no scheme publishes one (external gate),
 *                             so the obligations derive from GME-owned records on both sides
 * @param applied              always {@code false} — see the class note
 * @param appliedNote          plain statement of what applying netting would require
 */
public record NettingReportResponse(
        LocalDate from,
        LocalDate to,
        List<Position> positions,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal grossFundingUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal netFundingUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal nettingEfficiencyPct,
        int obligationCount,
        List<String> sourceCorridors,
        boolean schemeFeedBackedAll,
        boolean applied,
        String appliedNote) {

    /** The standing note carried on every report, so the reporting/applying line is never blurred. */
    public static final String REPORTING_ONLY_NOTE =
            "REPORTING ONLY. These figures are not applied to funding anywhere: no prefunding balance, "
                    + "settlement file or funding instruction is derived from them. Funding on a netted "
                    + "basis requires a per-counterparty agreement on which obligations offset, over "
                    + "which window, and who carries the intraday gap — an open commercial/treasury "
                    + "decision, not a calculation.";

    /**
     * One counterparty's netted position.
     *
     * @param counterparty the scheme/partner the hub faces
     * @param grossUsd     Σ absolute obligations against it
     * @param netUsd       SIGNED net: {@code > 0} hub pays, {@code < 0} hub receives
     * @param corridors    the corridors that contributed, in first-appearance order
     */
    public record Position(
            String counterparty,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal grossUsd,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal netUsd,
            List<String> corridors) {}

    /**
     * Build the wire shape from the calculator's summary plus the provenance the report needs.
     *
     * @param corridorsByCounterparty which corridors fed each counterparty's position, in
     *                                first-appearance order (so the report is deterministic)
     */
    static NettingReportResponse of(LocalDate from,
                                    LocalDate to,
                                    MultilateralNettingCalculator.Summary summary,
                                    int obligationCount,
                                    Map<String, List<String>> corridorsByCounterparty,
                                    List<String> sourceCorridors,
                                    boolean schemeFeedBackedAll) {
        List<Position> positions = summary.positions().stream()
                .map(p -> new Position(p.counterparty(), p.grossUsd(), p.netUsd(),
                        List.copyOf(corridorsByCounterparty
                                .getOrDefault(p.counterparty(), List.of()))))
                .toList();
        return new NettingReportResponse(
                from, to, positions,
                summary.grossFundingUsd(), summary.netFundingUsd(), summary.nettingEfficiencyPct(),
                obligationCount, List.copyOf(sourceCorridors), schemeFeedBackedAll,
                false, REPORTING_ONLY_NOTE);
    }
}
