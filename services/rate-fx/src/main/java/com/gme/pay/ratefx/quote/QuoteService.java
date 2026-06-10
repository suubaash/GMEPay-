package com.gme.pay.ratefx.quote;

import com.gme.pay.ratefx.RateEngine;
import com.gme.pay.ratefx.RateInput;
import com.gme.pay.ratefx.RateResult;
import com.gme.pay.ratefx.persistence.RateQuoteEntity;
import com.gme.pay.ratefx.persistence.RateQuoteRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Issues and retrieves rate quotes. On issuance the engine result is locked:
 * mirrored to the {@code rate_quotes} audit table (durable, never expires) and
 * placed in the {@link QuoteTtlStore} rate lock (key {@code rq:{quoteId}},
 * EXPIRE = quote TTL). Retrieval goes through the TTL store only — an unknown
 * or expired quote is rejected with {@code RATE_QUOTE_EXPIRED}.
 */
@Service
public class QuoteService {

    private final RateEngine engine = new RateEngine();
    private final RateQuoteRepository repository;
    private final QuoteTtlStore ttlStore;
    private final Clock clock;
    private final Duration quoteTtl;

    public QuoteService(RateQuoteRepository repository,
                        QuoteTtlStore ttlStore,
                        Clock clock,
                        @Value("${rate.quote.ttl-seconds:900}") long quoteTtlSeconds) {
        this.repository = repository;
        this.ttlStore = ttlStore;
        this.clock = clock;
        this.quoteTtl = Duration.ofSeconds(quoteTtlSeconds);
    }

    /** Computes an offer, persists the audit row and places the TTL lock. */
    public StoredQuote issueQuote(RateInput input) {
        RateResult result = engine.quote(input);
        Instant now = clock.instant();
        String quoteId = "RQ-" + UUID.randomUUID();
        StoredQuote quote = StoredQuote.of(quoteId, input, result, now, now.plus(quoteTtl));
        repository.save(RateQuoteEntity.fromStored(quote));
        ttlStore.put(quote, quoteTtl);
        return quote;
    }

    /**
     * The still-locked quote, or the deterministic domain error
     * {@code RATE_QUOTE_EXPIRED} (409) when unknown/expired.
     */
    public StoredQuote getQuote(String quoteId) {
        return ttlStore.require(quoteId);
    }
}
