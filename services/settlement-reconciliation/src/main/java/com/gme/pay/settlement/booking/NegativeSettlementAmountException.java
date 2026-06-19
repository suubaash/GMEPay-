package com.gme.pay.settlement.booking;

/**
 * Thrown when a GROSS (international) settlement nets below zero — i.e. refunds exceed payments for
 * the window (WBS 7.6-T04). GROSS settlement remits the full payout to ZeroPay, so a negative net is
 * not a valid outbound instruction and must surface for manual handling rather than be sent.
 */
public class NegativeSettlementAmountException extends RuntimeException {
    public NegativeSettlementAmountException(String message) {
        super(message);
    }
}
