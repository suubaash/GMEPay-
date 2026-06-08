package com.gme.pay.scheme.zeropay.adapter.model;

/** Summary of a merchant synchronisation run. */
public record SyncResult(
        int insertCount,
        int updateCount,
        int deactivateCount,
        boolean reconciliationMismatch,
        String mismatchDetail
) {}
