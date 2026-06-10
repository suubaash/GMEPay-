package com.gme.pay.ratefx.testsupport;

import com.gme.pay.ratefx.RateInput;
import com.gme.pay.ratefx.persistence.RateQuoteEntity;
import com.gme.pay.ratefx.persistence.RateSnapshotEntity;
import com.gme.pay.ratefx.quote.StoredQuote;

import java.math.BigDecimal;
import java.time.Instant;

/** Shared test data for rate-fx persistence/TTL tests. All money is BigDecimal. */
public final class Fixtures {

    /** Fixed instants (micro-precision-safe for TIMESTAMP round-trips). */
    public static final Instant CREATED_AT = Instant.parse("2026-06-10T10:00:00Z");
    public static final Instant EXPIRES_AT = Instant.parse("2026-06-10T10:15:00Z");

    private Fixtures() {
    }

    /** Cross-border RECEIVE-mode input: USD collection -> KRW payout, usd_krw=1380. */
    public static RateInput crossBorderInput() {
        return new RateInput(
                new BigDecimal("50000"),  // target payout (KRW)
                "USD", "USD", "KRW", "KRW",
                null,                       // cost_rate_coll: identity (settle A = USD)
                new BigDecimal("1380"),   // cost_rate_pay: usd_krw
                new BigDecimal("0.01"),   // m_a
                new BigDecimal("0.01"),   // m_b
                new BigDecimal("3.00"));  // service charge
    }

    /** A fully-populated stored quote with 8-dp money values. */
    public static StoredQuote storedQuote(String quoteId) {
        return new StoredQuote(
                quoteId,
                "USD", "USD", "KRW", "KRW",
                new BigDecimal("50000.00000000"),
                new BigDecimal("36.23188406"),
                new BigDecimal("36.97131027"),
                new BigDecimal("0.36971310"),
                new BigDecimal("0.36971310"),
                new BigDecimal("36.97000000"),
                new BigDecimal("39.97000000"),
                new BigDecimal("1.01010101"),
                new BigDecimal("1352.44793000"),
                false,
                CREATED_AT, EXPIRES_AT);
    }

    /** The matching audit-table entity for {@link #storedQuote(String)}. */
    public static RateQuoteEntity quoteEntity(String quoteId) {
        return RateQuoteEntity.fromStored(storedQuote(quoteId));
    }

    /** A LIVE treasury snapshot row: usd_krw = 1380.00000000. */
    public static RateSnapshotEntity krwSnapshot(String snapshotId, Instant effectiveAt) {
        return new RateSnapshotEntity(
                snapshotId, "KRW",
                new BigDecimal("1380.00000000"),
                "LIVE",
                effectiveAt,
                CREATED_AT);
    }
}
