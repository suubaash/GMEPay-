package com.gme.pay.txn.api.dto;

/**
 * Request body for the Ops force-resolve endpoint
 * {@code POST /v1/transactions/{txnRef}/resolve}.
 *
 * <p>Lets an operator manually resolve a transaction that is stuck in
 * {@link com.gme.pay.txn.domain.model.TransactionStatus#UNCERTAIN} to a terminal state:
 * <ul>
 *   <li>{@code resolution} — {@code COMPLETED} (→ APPROVED) | {@code REVERSED} (→ REVERSED).</li>
 *   <li>{@code reason}     — free-text justification, recorded in the transaction audit. Required.</li>
 *   <li>{@code operator}   — the operator id / login performing the action. Required.</li>
 * </ul>
 */
public record ResolveTransactionRequest(
        String resolution,
        String reason,
        String operator) {
}
