package com.gme.pay.ratefx.quote;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.gme.pay.ratefx.RateInput;
import com.gme.pay.ratefx.RateResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Immutable snapshot of an issued rate quote — the value held under the
 * {@code rq:{quoteId}} key for the quote's TTL window (rate lock) and mirrored
 * to the {@code rate_quotes} audit table.
 *
 * <p>Money/rate fields are {@link BigDecimal}, JSON-serialized as decimal
 * strings per docs/MONEY_CONVENTION.md, normalized to 8 decimal places
 * (RATE-04 §10.2) so the wire and DB representations match exactly.
 */
public record StoredQuote(
        String quoteId,
        String collectionCurrency,
        String settleACurrency,
        String settleBCurrency,
        String payoutCurrency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal targetPayout,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal payoutUsdCost,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal collectionUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal collectionMarginUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal payoutMarginUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal sendAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal collectionAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal offerRateColl,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal crossRate,
        boolean shortCircuit,
        Instant createdAt,
        Instant expiresAt) {

    /** Scale used for all persisted/locked money & rate values (RATE-04 §10.2). */
    public static final int LOCK_SCALE = 8;

    /**
     * Builds the locked quote from the engine's input/result, normalizing every
     * BigDecimal to {@value #LOCK_SCALE} dp HALF_UP (engine intermediates carry
     * MathContext(20) precision; the lock is the rounded, immutable view).
     */
    public static StoredQuote of(String quoteId, RateInput in, RateResult result,
                                 Instant createdAt, Instant expiresAt) {
        return new StoredQuote(
                quoteId,
                in.collectionCurrency(), in.settleACurrency(), in.settleBCurrency(), in.payoutCurrency(),
                scale8(in.targetPayout()),
                scale8(result.payoutUsdCost()),
                scale8(result.collectionUsd()),
                scale8(result.collectionMarginUsd()),
                scale8(result.payoutMarginUsd()),
                scale8(result.sendAmount()),
                scale8(result.collectionAmount()),
                scale8(result.offerRateColl()),
                scale8(result.crossRate()),
                result.shortCircuit(),
                createdAt, expiresAt);
    }

    private static BigDecimal scale8(BigDecimal value) {
        return value == null ? null : value.setScale(LOCK_SCALE, RoundingMode.HALF_UP);
    }
}
