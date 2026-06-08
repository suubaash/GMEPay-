package com.gme.pay.settlement.recon;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Matches GME-expected settlement amounts against ZeroPay-confirmed amounts.
 *
 * <p>Rules:
 * <ul>
 *   <li>Both sides present, amounts equal   → MATCHED</li>
 *   <li>Both sides present, amounts differ  → DISCREPANCY</li>
 *   <li>GME record present, no scheme entry → MISSING_SCHEME</li>
 *   <li>Scheme entry present, no GME record → MISSING_INTERNAL</li>
 * </ul>
 *
 * <p>KRW amounts are integers, so equality is exact (no tolerance needed for KRW).
 */
@Component
public class LineMatcher {

    /**
     * Match internal GME settlement lines against scheme-confirmed lines.
     *
     * @param gmeLines    map of merchantId -> GME expected net_settlement_amount
     * @param schemeLines map of merchantId -> amount confirmed by ZeroPay
     * @return list of {@link ReconLine}, one per distinct merchant across both inputs
     */
    public List<ReconLine> match(
            Map<String, BigDecimal> gmeLines,
            Map<String, BigDecimal> schemeLines) {

        List<ReconLine> results = new ArrayList<>();

        // Collect all merchant ids from both sides
        Map<String, BigDecimal> allGme = gmeLines != null ? gmeLines : Map.of();
        Map<String, BigDecimal> allScheme = schemeLines != null ? schemeLines : Map.of();

        // Use a merged key set
        Map<String, Boolean> seen = new HashMap<>();
        allGme.keySet().forEach(k -> seen.put(k, Boolean.TRUE));
        allScheme.keySet().forEach(k -> seen.put(k, Boolean.TRUE));

        for (String merchantId : seen.keySet()) {
            BigDecimal gme = allGme.get(merchantId);
            BigDecimal scheme = allScheme.get(merchantId);

            if (gme != null && scheme != null) {
                if (gme.compareTo(scheme) == 0) {
                    results.add(new ReconLine(merchantId, gme, scheme, BigDecimal.ZERO, MatchStatus.MATCHED));
                } else {
                    BigDecimal discrepancy = gme.subtract(scheme).abs();
                    results.add(new ReconLine(merchantId, gme, scheme, discrepancy, MatchStatus.DISCREPANCY));
                }
            } else if (gme != null) {
                results.add(new ReconLine(merchantId, gme, null, gme, MatchStatus.MISSING_SCHEME));
            } else {
                // scheme present, GME missing
                results.add(new ReconLine(merchantId, BigDecimal.ZERO, scheme, scheme, MatchStatus.MISSING_INTERNAL));
            }
        }

        return results;
    }

    /**
     * Returns only lines that require attention (DISCREPANCY, MISSING_SCHEME, MISSING_INTERNAL).
     */
    public List<ReconLine> unmatchedLines(
            Map<String, BigDecimal> gmeLines,
            Map<String, BigDecimal> schemeLines) {
        return match(gmeLines, schemeLines).stream()
                .filter(ReconLine::requiresAttention)
                .toList();
    }
}
