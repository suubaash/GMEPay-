package com.gme.pay.payment.client.rest;

import com.gme.pay.payment.domain.SchemeDeclinedException;
import com.gme.pay.payment.domain.SchemeOperationNotSupportedException;

import java.util.function.Predicate;

/**
 * Decides which exceptions count as a <em>failure</em> for the scheme circuit breaker
 * (feat/resilience-scheme-breaker).
 *
 * <p>A synchronous {@link SchemeDeclinedException} is an authoritative <b>business decision</b>, not a
 * fault of the scheme's availability — a healthy scheme correctly declining bad QRs must NOT trip its
 * breaker (that would take the whole scheme offline on legitimate declines and is a money-semantics
 * change we must avoid). Everything else — timeouts (5xx / read-timeout → {@code SchemeTimeoutException}),
 * connect failures, empty-body {@code PaymentException} — is a technical fault and counts toward the
 * breaker's failure rate.
 *
 * <p>{@link SchemeOperationNotSupportedException} is likewise NOT a fault (T2-7): the scheme simply has
 * no cancel round-trip, so no HTTP call was even made. Recording it would let a handful of refund
 * attempts on a single-shot corridor trip that scheme's breaker and take its PAY path offline.
 *
 * <p>Referenced by {@code resilience4j.circuitbreaker.configs.default.record-failure-predicate};
 * resilience4j instantiates it via its public no-arg constructor.
 */
public class SchemeFailureRecordPredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        // true  = record as a breaker failure (technical fault)
        // false = ignore for the failure rate (authoritative business decline / unsupported operation)
        return !(throwable instanceof SchemeDeclinedException)
                && !(throwable instanceof SchemeOperationNotSupportedException);
    }
}
