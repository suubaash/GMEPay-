package com.gme.pay.ratefx.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.ratefx.persistence.RateQuoteEntity;
import com.gme.pay.ratefx.persistence.RateQuoteRepository;
import com.gme.pay.ratefx.testsupport.Fixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the durable, restart-safe {@link JpaQuoteTtlStore}: a quote is retrievable until its
 * {@code expires_at}, gone afterwards, and survives a "restart" (a fresh store over the same backing
 * rows still finds the quote). The repository is a Mockito mock backed by a map.
 */
class JpaQuoteTtlStoreTest {

    private static final Instant START = Instant.parse("2026-06-30T10:00:00Z");

    /** A mock repository whose save/findById are backed by {@code rows}. */
    private static RateQuoteRepository repoOver(Map<String, RateQuoteEntity> rows) {
        RateQuoteRepository repo = mock(RateQuoteRepository.class);
        when(repo.save(any(RateQuoteEntity.class))).thenAnswer(inv -> {
            RateQuoteEntity e = inv.getArgument(0);
            rows.put(e.getQuoteId(), e);
            return e;
        });
        when(repo.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<String>getArgument(0))));
        return repo;
    }

    @Test
    void put_thenFind_returnsQuoteWhileLockHolds() {
        JpaQuoteTtlStore store = new JpaQuoteTtlStore(repoOver(new HashMap<>()), new MutableClock(START));
        StoredQuote quote = quoteExpiringIn(Duration.ofMinutes(15));

        store.put(quote, Duration.ofMinutes(15));

        assertThat(store.find(quote.quoteId())).contains(quote);
        assertThat(store.require(quote.quoteId())).isEqualTo(quote);
    }

    @Test
    void find_afterExpiry_isEmpty_andRequireThrowsExpired() {
        MutableClock clock = new MutableClock(START);
        JpaQuoteTtlStore store = new JpaQuoteTtlStore(repoOver(new HashMap<>()), clock);
        StoredQuote quote = quoteExpiringIn(Duration.ofMinutes(15));
        store.put(quote, Duration.ofMinutes(15));

        clock.advance(Duration.ofMinutes(15)); // now == expiresAt → expired

        assertThat(store.find(quote.quoteId())).isEmpty();
        assertThatThrownBy(() -> store.require(quote.quoteId()))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RATE_QUOTE_EXPIRED);
    }

    @Test
    void survivesRestart_freshStoreOverSameRowsStillFinds() {
        Map<String, RateQuoteEntity> rows = new HashMap<>();
        StoredQuote quote = quoteExpiringIn(Duration.ofMinutes(15));
        new JpaQuoteTtlStore(repoOver(rows), new MutableClock(START)).put(quote, Duration.ofMinutes(15));

        // "restart": a brand-new store instance + repo over the same persisted rows.
        JpaQuoteTtlStore afterRestart =
                new JpaQuoteTtlStore(repoOver(rows), new MutableClock(START.plusSeconds(60)));

        assertThat(afterRestart.find(quote.quoteId())).contains(quote);
    }

    private static StoredQuote quoteExpiringIn(Duration ttl) {
        StoredQuote base = Fixtures.storedQuote("RQ-jpa-1");
        return new StoredQuote(
                base.quoteId(), base.collectionCurrency(), base.settleACurrency(),
                base.settleBCurrency(), base.payoutCurrency(),
                base.targetPayout(), base.payoutUsdCost(), base.collectionUsd(),
                base.collectionMarginUsd(), base.payoutMarginUsd(), base.sendAmount(),
                base.collectionAmount(), base.offerRateColl(), base.crossRate(),
                base.shortCircuit(), START, START.plus(ttl));
    }
}
