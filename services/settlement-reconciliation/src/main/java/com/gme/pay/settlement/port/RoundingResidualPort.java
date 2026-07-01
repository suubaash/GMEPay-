package com.gme.pay.settlement.port;

import java.math.BigDecimal;

/**
 * Cross-service port for posting an Addendum-001 per-batch rounding residual to revenue-ledger's
 * {@code POST /v1/journals/rounding-residual} ({@code postRoundingResidual(reference, residual, currency)}).
 *
 * <p>The {@code reference} is the settlement <strong>batch id</strong> ({@code ZP00NN-YYYYMMDD-WINDOW}),
 * so the REVENUE_ROUNDING gain/loss journal is traceable to the batch that produced it. Posting is
 * once-per-batch — the caller guards against re-post on a recon re-run.
 *
 * <p>MSA rule: revenue-ledger owns its DB; this is the only surface. The REST implementation is gated;
 * an in-process fixture is the fallback when disabled or revenue-ledger is unreachable.
 */
public interface RoundingResidualPort {

    /**
     * Post a rounding residual against a settlement batch.
     *
     * @param batchId  the settlement batch id, used as the journal {@code reference}
     * @param residual precise − booked (full precision); zero is a no-op (revenue-ledger returns 204)
     * @param currency the settle currency (e.g. {@code KRW})
     * @return {@code true} if the post was accepted by revenue-ledger (2xx) or skipped as a zero
     *         residual; {@code false} if the post failed and should be retried on a later recon run
     */
    boolean postResidual(String batchId, BigDecimal residual, String currency);
}
