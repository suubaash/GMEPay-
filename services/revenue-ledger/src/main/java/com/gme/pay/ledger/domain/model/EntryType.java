package com.gme.pay.ledger.domain.model;

/** Side of a double-entry ledger posting. Every journal must have balanced DEBIT and CREDIT lines. */
public enum EntryType {
    DEBIT,
    CREDIT
}
