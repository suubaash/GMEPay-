package com.gme.pay.qr.exception;

/**
 * Thrown when a CPM generate request reuses a {@code partner_txn_ref} already seen (WBS 5.3-T09).
 * Maps to HTTP 409 {@code DUPLICATE_PARTNER_TXN_REF}.
 */
public class DuplicatePartnerTxnRefException extends QRParseException {

    public DuplicatePartnerTxnRefException(String partnerTxnRef) {
        super(QRErrorCode.DUPLICATE_PARTNER_TXN_REF,
                "partner_txn_ref already used: " + partnerTxnRef);
    }
}
