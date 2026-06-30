package com.gme.pay.ratefx.issue;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.ratefx.RateInput;
import com.gme.pay.ratefx.client.PartnerConfigPort;
import com.gme.pay.ratefx.client.PartnerConfigPort.PartnerCurrencies;
import com.gme.pay.ratefx.client.PartnerConfigPort.PartnerRule;
import com.gme.pay.ratefx.partnerb.PartnerBQuote;
import com.gme.pay.ratefx.partnerb.PartnerBQuotePort;
import com.gme.pay.ratefx.quote.QuoteService;
import com.gme.pay.ratefx.quote.StoredQuote;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The production QUOTE ISSUER (quote-issuer epic). Turns a {@link PartnerQuoteRequest} into a
 * priced, TTL-locked quote by resolving the partner's config (config-registry) + the treasury cost
 * rates (snapshot store) into a {@link RateInput}, then delegating to {@link QuoteService}. This is
 * the path that was missing — nothing in production issued quotes; only tests/sims hand-built
 * {@code RateInput}s.
 *
 * <p>It closes the deferred Step 5 + Step 6 tails by making the partner's stored config authoritative
 * at issue time:
 * <ul>
 *   <li><b>Step 5</b> — {@code settleACurrency}/{@code collectionCurrency} come from the partner's
 *       {@code settle_a_ccy}/{@code collection_ccy}, not the caller.</li>
 *   <li><b>Step 6</b> — the service fee comes from the partner's pricing rule
 *       ({@code serviceChargeUsd}), not the caller; folded into the quote's {@code collectionAmount}
 *       by the engine (which the float debit then reconciles against).</li>
 * </ul>
 *
 * <p>Margins {@code mA}/{@code mB} also come from the rule. The caller supplies only the transaction
 * facts (targetPayout, payoutCurrency, scheme, direction) — never the priced inputs.
 */
@Service
public class QuoteIssueService {

    private static final String USD = "USD";

    private final PartnerConfigPort configPort;
    private final CostRateResolver costRateResolver;
    private final PartnerBQuotePort partnerBQuotePort;
    private final QuoteService quoteService;

    public QuoteIssueService(PartnerConfigPort configPort,
                             CostRateResolver costRateResolver,
                             PartnerBQuotePort partnerBQuotePort,
                             QuoteService quoteService) {
        this.configPort = configPort;
        this.costRateResolver = costRateResolver;
        this.partnerBQuotePort = partnerBQuotePort;
        this.quoteService = quoteService;
    }

    /** Resolve config + cost rates, build the RateInput, and issue the TTL-locked quote. */
    public StoredQuote issueForPartner(PartnerQuoteRequest req) {
        return quoteService.issueQuote(buildRateInput(req));
    }

    /**
     * Build the {@link RateInput} for a partner request — the money-critical resolution. Package
     * visible so the field-by-field sourcing is unit-testable without the persistence/TTL layer.
     */
    RateInput buildRateInput(PartnerQuoteRequest req) {
        if (req == null || req.partnerCode() == null || req.partnerCode().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "partnerCode is required");
        }
        if (req.targetPayout() == null || req.targetPayout().signum() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "targetPayout must be > 0");
        }
        if (req.payoutCurrency() == null || req.payoutCurrency().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "payoutCurrency is required");
        }

        PartnerCurrencies currencies = configPort.getPartnerCurrencies(req.partnerCode());
        String collectionCurrency = currencies.collectionOrDefault();
        String settleACurrency = currencies.settleAOrDefault();
        // RECEIVE-mode payout leg settles in the payout currency (e.g. KRW for ZeroPay).
        String settleBCurrency = req.payoutCurrency();

        PartnerRule rule = pickRule(configPort.getRules(req.partnerCode()),
                req.schemeId(), req.direction());

        RateSource collSource = RateSource.fromNullable(rule.rateCollSource());
        RateSource paySource = RateSource.fromNullable(rule.ratePaySource());

        BigDecimal costRateColl = resolveLeg(collSource, settleACurrency, req.schemeId());
        BigDecimal costRatePay = resolveLeg(paySource, settleBCurrency, req.schemeId());

        // The rule fee is USD-denominated; the engine adds serviceCharge to sendAmount (in settle-A
        // currency), so convert the USD fee into settle-A unless settle-A is already USD.
        BigDecimal serviceChargeUsd = nz(rule.serviceChargeUsd());
        BigDecimal serviceCharge = (settleACurrency == null || USD.equalsIgnoreCase(settleACurrency)
                || serviceChargeUsd.signum() == 0 || costRateColl == null)
                ? serviceChargeUsd
                : serviceChargeUsd.multiply(costRateColl).setScale(8, RoundingMode.HALF_UP);

        return new RateInput(
                req.targetPayout(),
                collectionCurrency,
                settleACurrency,
                settleBCurrency,
                req.payoutCurrency(),
                costRateColl,
                costRatePay,
                nz(rule.mA()),
                nz(rule.mB()),
                serviceCharge);
    }

    /**
     * Resolve a single leg's cost rate by its configured {@link RateSource}. {@code IDENTITY} and a
     * USD currency both yield {@code null} (the engine forces 1.0). {@code PARTNER} is quoted by
     * Partner B (WBS 4.6); {@code LIVE}/{@code MANUAL} are served from the treasury snapshot store.
     */
    private BigDecimal resolveLeg(RateSource source, String currency, String schemeId) {
        if (currency == null || USD.equalsIgnoreCase(currency)) {
            return null; // IDENTITY leg — engine forces 1.0 regardless of configured source
        }
        if (source.isPartner()) {
            return partnerBQuotePort.fetchQuote(schemeId, currency).rate();
        }
        // LIVE and MANUAL both read the snapshot store; the configured cost-rate-source selects which
        // snapshot rows the CostRateResolver considers.
        return costRateResolver.resolve(currency);
    }

    /**
     * Commit-time PARTNER deviation guard (RATE-04 §7.3, WBS 4.6). A PARTNER-source leg is re-quoted
     * at {@code POST /payments} time; if the new rate has drifted from the rate locked into the quote
     * by more than {@code tolerance} (fraction; default 1%), the commit is rejected with
     * {@link ErrorCode#PARTNER_B_QUOTE_DEVIATION}. Within tolerance, the (fresh) commit rate is
     * returned for the caller to settle against.
     *
     * @param schemeId      scheme/corridor the leg is for
     * @param settlementCcy the PARTNER leg's settlement currency
     * @param quotedRate    the rate locked into the issued quote (units of settlement ccy per USD)
     * @return the fresh commit-time Partner B rate (within tolerance)
     * @throws ApiException PARTNER_B_QUOTE_DEVIATION (commit drift exceeds tolerance) or
     *                      PARTNER_B_QUOTE_UNAVAILABLE (no commit-time quote)
     */
    public BigDecimal resolvePartnerCommitRate(String schemeId, String settlementCcy,
                                               BigDecimal quotedRate, BigDecimal tolerance) {
        if (quotedRate == null || quotedRate.signum() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "quotedRate must be positive for a PARTNER commit deviation check");
        }
        PartnerBQuote commit = partnerBQuotePort.fetchQuote(schemeId, settlementCcy);
        BigDecimal commitRate = commit.rate();
        BigDecimal deviation = commitRate.subtract(quotedRate).abs()
                .divide(quotedRate, java.math.MathContext.DECIMAL64);
        if (deviation.compareTo(tolerance) > 0) {
            throw new ApiException(ErrorCode.PARTNER_B_QUOTE_DEVIATION,
                    "PARTNER quote drifted " + deviation + " (> tolerance " + tolerance + ") for "
                            + settlementCcy + ": quoted=" + quotedRate + " commit=" + commitRate);
        }
        return commitRate;
    }

    /**
     * Pick the most specific rule for {@code (schemeId, direction)}: an exact scheme beats the
     * {@code null} wildcard; an exact direction beats {@code BOTH}/{@code null}. Throws when no rule
     * applies — the partner is not priced for this corridor.
     */
    private static PartnerRule pickRule(List<PartnerRule> rules, String schemeId, String direction) {
        PartnerRule best = null;
        int bestScore = -1;
        for (PartnerRule r : rules) {
            int s = schemeScore(r.schemeId(), schemeId);
            int d = directionScore(r.direction(), direction);
            if (s == 0 || d == 0) {
                continue;
            }
            int total = s + d;
            if (total > bestScore) {
                best = r;
                bestScore = total;
            }
        }
        if (best == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "no pricing rule for scheme=" + schemeId + " direction=" + direction);
        }
        return best;
    }

    private static int schemeScore(String ruleScheme, String requested) {
        if (ruleScheme == null) {
            return 1;
        }
        return requested != null && ruleScheme.equalsIgnoreCase(requested) ? 2 : 0;
    }

    private static int directionScore(String ruleDirection, String requested) {
        if (ruleDirection == null || "BOTH".equalsIgnoreCase(ruleDirection)) {
            return 1;
        }
        return requested != null && ruleDirection.equalsIgnoreCase(requested) ? 2 : 0;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
