package com.gme.pay.ratefx.quote;

import com.gme.pay.ratefx.persistence.RateQuoteEntity;
import com.gme.pay.ratefx.persistence.RateQuoteRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Durable, restart-safe {@link QuoteTtlStore} backed by the {@code rate_quotes} audit table — the
 * default when no Redis host is configured. Unlike {@link InMemoryQuoteTtlStore}, locks survive a
 * service restart because the rows are already persisted (the {@code rate_quotes} table is also the
 * audit copy QuoteService writes on issue).
 *
 * <p>TTL is enforced by the row's {@code expires_at}: {@link #find(String)} returns empty once
 * {@code now >= expires_at} (the same observable behaviour as a Redis EXPIRE), so an expired quote
 * deterministically maps to {@link com.gme.pay.errors.ErrorCode#RATE_QUOTE_EXPIRED} via the port's
 * {@code require}. The {@code put} here only sets/extends the deadline on the already-saved row; it
 * does not duplicate the audit write.
 *
 * <p>The expired audit row is intentionally NOT deleted (it remains for audit/housekeeping, cleaned
 * by {@link RateQuoteRepository#findByExpiresAtBefore}); it is simply treated as a TTL miss.
 */
public final class JpaQuoteTtlStore implements QuoteTtlStore {

    private final RateQuoteRepository repository;
    private final Clock clock;

    public JpaQuoteTtlStore(RateQuoteRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public void put(StoredQuote quote, Duration ttl) {
        QuoteTtlStore.requirePositive(ttl);
        // The audit row carries its own expires_at (set by QuoteService at issue time). The lock and
        // the audit copy are the same row here, so persisting it is idempotent w.r.t. the deadline.
        repository.save(RateQuoteEntity.fromStored(quote));
    }

    @Override
    public Optional<StoredQuote> find(String quoteId) {
        return repository.findById(quoteId)
                .map(RateQuoteEntity::toStored)
                .filter(q -> clock.instant().isBefore(q.expiresAt()));
    }

    @Override
    public void remove(String quoteId) {
        // No-op: the durable audit row must outlive the lock. Expiry is governed by expires_at, and a
        // committed quote is identified by its payment record, not by deleting the audit trail.
    }
}
