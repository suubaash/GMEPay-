package com.gme.pay.ratefx.quote;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;

import java.time.Duration;
import java.util.Optional;

/**
 * Port for the quote TTL / rate-lock store (17.3-G01). A quote put with TTL
 * {@code t} is retrievable until {@code t} elapses and gone afterwards.
 *
 * <p>Implementations: {@link RedisQuoteTtlStore} (key {@code rq:{quoteId}},
 * value = quote JSON, EXPIRE = quote TTL — locks survive service restarts) and
 * {@link InMemoryQuoteTtlStore} (default when no Redis host is configured).
 */
public interface QuoteTtlStore {

    /** Stores the quote under its id for exactly {@code ttl}; replaces any previous value. */
    void put(StoredQuote quote, Duration ttl);

    /** The quote, or empty when unknown or its TTL has elapsed. */
    Optional<StoredQuote> find(String quoteId);

    /** Drops the lock eagerly (e.g. once a quote is committed). Unknown ids are a no-op. */
    void remove(String quoteId);

    /**
     * The quote, or the deterministic domain error when unknown/expired:
     * {@link ErrorCode#RATE_QUOTE_EXPIRED} (HTTP 409, non-retryable).
     */
    default StoredQuote require(String quoteId) {
        return find(quoteId).orElseThrow(() -> new ApiException(
                ErrorCode.RATE_QUOTE_EXPIRED,
                "quote " + quoteId + " is unknown or its TTL has elapsed"));
    }

    /** Guard shared by implementations: a quote TTL must be strictly positive. */
    static Duration requirePositive(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("VALIDATION_ERROR: quote TTL must be positive, got " + ttl);
        }
        return ttl;
    }
}
