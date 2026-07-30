package com.gme.pay.payment.dayclose;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The net open FX position per currency, DERIVED from transactions already recorded (gap <b>T2-5</b> /
 * CFO#9: "zero FX exposure tracking — GME bears FX risk on MNT/VND/KRW with no measurement").
 *
 * <h2>What this is</h2>
 * <p>A MEASUREMENT. For each currency it reports what came in, what went out, what the same-currency net of
 * those is, how much USD was actually settled against the transactions that touched it, and what USD rate
 * basis that implies. Currencies GME collects in show a positive position; currencies it pays out in
 * (MNT, VND, NPR) show a negative one. Nothing here is a model of anything: every figure is a sum or a
 * quotient of values transaction-mgmt already stores.
 *
 * <h2>What this deliberately is NOT</h2>
 * <ul>
 *   <li><b>Not a hedging recommendation.</b> No hedge is proposed, sized, priced or implemented. Whether GME
 *       hedges MNT/VND/NPR, and how, is a treasury decision that this report exists to INFORM, not to make.</li>
 *   <li><b>Not a target position.</b> There is no "acceptable" or "expected" position on any currency, so
 *       there is no limit, threshold or breach flag — inventing one would be inventing policy. A reader who
 *       wants a limit has to supply one.</li>
 *   <li><b>Not a P&amp;L.</b> Positions are NOT revalued at any rate. Revaluing would require choosing a
 *       revaluation rate and a revaluation policy; both are accounting decisions, and there is no
 *       mark-to-market number here that could be mistaken for a realised result.</li>
 *   <li><b>Not cross-currency netting.</b> Each currency is netted only against itself. Converting an MNT
 *       payout into KRW to net it against a KRW collection would require applying a rate this report has no
 *       business choosing.</li>
 * </ul>
 *
 * @param startDate         inclusive first business date measured
 * @param endDate           inclusive last business date measured
 * @param generatedAt       when this measurement was taken
 * @param available         false when the transaction leg could not be read; positions are then EMPTY, never
 *                          zeroed — "no data" and "no exposure" are different answers
 * @param unavailableReason why, when {@code available} is false
 * @param transactionCount  APPROVED transactions the measurement is derived from
 * @param positions         one entry per currency observed on either leg
 */
public record FxExposureReport(
        LocalDate startDate,
        LocalDate endDate,
        Instant generatedAt,
        boolean available,
        String unavailableReason,
        int transactionCount,
        List<CurrencyPosition> positions
) {

    /** A report that could not be produced. Positions are empty, not zero. */
    public static FxExposureReport unavailable(LocalDate start, LocalDate end, Instant at, String reason) {
        return new FxExposureReport(start, end, at, false, reason, 0, List.of());
    }

    /**
     * One currency's measured position.
     *
     * <p>Sign convention: {@code netOpenPosition} is positive when GME holds more of the currency than it has
     * paid away (it collected in it) and negative when it has paid away more than it holds (it pays out in it).
     * A payout currency such as MNT is therefore normally negative, and that is the exposure — GME owes the
     * corridor local currency it does not hold.
     *
     * @param currency                 ISO-4217 code
     * @param collected                total collected IN this currency across the period
     * @param collectedTxnCount        transactions contributing to {@code collected}
     * @param refunded                 total refunded back OUT of collections in this currency (cumulative
     *                                 amounts as recorded on the transaction)
     * @param paidOut                  total paid OUT in this currency
     * @param paidOutTxnCount          transactions contributing to {@code paidOut}
     * @param netOpenPosition          {@code collected − refunded − paidOut}, same-currency only
     * @param settledUsd               USD actually taken from partner float on the transactions that touched
     *                                 this currency — the SETTLED leg, i.e. how much of the position has
     *                                 already been converted through USD
     * @param impliedUsdBasis          {@code collected / settledUsd} — the units-per-USD rate basis the
     *                                 settlement implies for a collection currency. Null when this currency was
     *                                 never a collection currency, or when no USD was settled against it (there
     *                                 is then no basis to derive, and guessing one would be inventing a rate)
     * @param basisTxnCount            collection transactions with a usable USD figure, i.e. the sample
     *                                 {@code impliedUsdBasis} is derived from
     * @param missingBasisCount        collection transactions with NO recorded USD deduction, so their rate
     *                                 basis is unknown. A non-zero value means {@code impliedUsdBasis} does not
     *                                 describe the whole period, which the reader must know
     * @param fallbackBasisSuspected   collection transactions whose back-derived basis matches the hub's
     *                                 hardcoded USD fallback constant within tolerance, i.e. were probably
     *                                 priced off an unverified constant rather than a live rate (CFO#9's second
     *                                 half). Zero for every currency except the one the constant is configured
     *                                 for
     */
    public record CurrencyPosition(
            String currency,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal collected,
            int collectedTxnCount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal refunded,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal paidOut,
            int paidOutTxnCount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal netOpenPosition,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal settledUsd,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal impliedUsdBasis,
            int basisTxnCount,
            int missingBasisCount,
            int fallbackBasisSuspected
    ) {}
}
