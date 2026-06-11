package com.gme.pay.ratefx.quote;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.ratefx.testsupport.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TTL semantics of the in-memory default store (no Docker, deterministic clock). */
class InMemoryQuoteTtlStoreTest {

    private MutableClock clock;
    private InMemoryQuoteTtlStore store;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-06-10T10:00:00Z"));
        store = new InMemoryQuoteTtlStore(clock);
    }

    @Test
    @DisplayName("put then find within TTL returns the quote unchanged")
    void findWithinTtl() {
        StoredQuote quote = Fixtures.storedQuote("RQ-1");
        store.put(quote, Duration.ofSeconds(30));

        clock.advance(Duration.ofSeconds(29));
        assertEquals(quote, store.find("RQ-1").orElseThrow());
        assertEquals(quote, store.require("RQ-1"));
    }

    @Test
    @DisplayName("after the TTL elapses the quote is gone and require() raises RATE_QUOTE_EXPIRED")
    void expiredQuoteRejected() {
        store.put(Fixtures.storedQuote("RQ-2"), Duration.ofSeconds(30));

        clock.advance(Duration.ofSeconds(30)); // deadline is exclusive: exactly TTL => expired
        assertTrue(store.find("RQ-2").isEmpty());

        ApiException ex = assertThrows(ApiException.class, () -> store.require("RQ-2"));
        assertEquals(ErrorCode.RATE_QUOTE_EXPIRED, ex.errorCode());
        assertEquals(409, ex.errorCode().httpStatus());
    }

    @Test
    @DisplayName("an unknown quote id raises the same deterministic RATE_QUOTE_EXPIRED error")
    void unknownQuoteRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> store.require("RQ-NOPE"));
        assertEquals(ErrorCode.RATE_QUOTE_EXPIRED, ex.errorCode());
    }

    @Test
    @DisplayName("remove() drops the lock eagerly")
    void removeDropsLock() {
        store.put(Fixtures.storedQuote("RQ-3"), Duration.ofMinutes(15));
        store.remove("RQ-3");
        assertTrue(store.find("RQ-3").isEmpty());
    }

    @Test
    @DisplayName("non-positive TTLs are refused")
    void nonPositiveTtlRefused() {
        StoredQuote quote = Fixtures.storedQuote("RQ-4");
        assertThrows(IllegalArgumentException.class, () -> store.put(quote, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> store.put(quote, Duration.ofSeconds(-1)));
    }
}
