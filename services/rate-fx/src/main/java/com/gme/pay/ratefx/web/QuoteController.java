package com.gme.pay.ratefx.web;

import com.gme.pay.ratefx.RateInput;
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

    public QuoteController(QuoteService quoteService) {
        this.quoteService = quoteService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StoredQuote create(@RequestBody RateInput request) {
        return quoteService.issueQuote(request);
    }

    @GetMapping("/{quoteId}")
    public StoredQuote get(@PathVariable String quoteId) {
        return quoteService.getQuote(quoteId);
    }
}
