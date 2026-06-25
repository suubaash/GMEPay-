package com.gme.pay.ratefx.web;

import com.gme.pay.ratefx.RateInput;
import com.gme.pay.ratefx.issue.PartnerQuoteRequest;
import com.gme.pay.ratefx.issue.QuoteIssueService;
import com.gme.pay.ratefx.quote.QuoteService;
import com.gme.pay.ratefx.quote.StoredQuote;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quote lifecycle endpoints (API-05 §4). POST issues a TTL-locked quote;
 * GET retrieves it while the lock holds — afterwards the deterministic
 * {@code RATE_QUOTE_EXPIRED} (409) error envelope is returned.
 *
 * <p>The stateless POST /v1/rates calculator endpoint is unchanged
 * (see {@link RateController}).
 */
@RestController
@RequestMapping("/v1/quotes")
public class QuoteController {

    private final QuoteService quoteService;
    private final QuoteIssueService quoteIssueService;

    public QuoteController(QuoteService quoteService, QuoteIssueService quoteIssueService) {
        this.quoteService = quoteService;
        this.quoteIssueService = quoteIssueService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StoredQuote create(@RequestBody RateInput request) {
        return quoteService.issueQuote(request);
    }

    /**
     * Partner-priced quote issuance (the quote-issuer epic). The caller supplies only the
     * transaction facts ({@code partnerCode, schemeId, direction, targetPayout, payoutCurrency});
     * rate-fx resolves the partner's currencies + margins + service fee from config-registry and the
     * treasury cost rates from its snapshot store, builds the {@link RateInput}, and issues the
     * TTL-locked quote. This is the production front-door that the stateless POST /v1/quotes (which
     * trusts a fully-formed RateInput) never provided.
     */
    @PostMapping("/partner")
    @ResponseStatus(HttpStatus.CREATED)
    public StoredQuote issueForPartner(@RequestBody PartnerQuoteRequest request) {
        return quoteIssueService.issueForPartner(request);
    }

    @GetMapping("/{quoteId}")
    public StoredQuote get(@PathVariable String quoteId) {
        return quoteService.getQuote(quoteId);
    }
}
