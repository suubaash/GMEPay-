package com.gme.pay.settlement.scheduler;

/**
 * Thrown when the inbound ZeroPay result file a recon window needs is not in the inbox (gap <b>T3-4</b>).
 *
 * <p>This used to be a {@code log.warn} plus an early return, which made "ZeroPay never sent the file" look
 * exactly like "everything reconciled" from outside the log. ZeroPay owes a result file for every settlement
 * request it accepted, so on a business day its absence is a settlement discrepancy in the making — the
 * failure mode the COO audit described as "discovered by the counterparty".
 *
 * <p>It is a distinct type so the run ledger records a recognisable {@code failure_class} and an operator
 * can tell "no file arrived" apart from "the file arrived and the parse blew up" without reading a stack
 * trace. Non-business dates are filtered out by the calendar gate before the file is ever looked for, so
 * this does not fire on Korean bank holidays once the calendar is populated.
 */
public class ReconInputMissingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ReconInputMissingException(String filename) {
        super("inbound recon file not found in inbox: " + filename
                + " — ZeroPay owes a result file for every accepted settlement request, so on a business "
                + "day this is an unreconciled settlement, not a quiet day");
    }
}
