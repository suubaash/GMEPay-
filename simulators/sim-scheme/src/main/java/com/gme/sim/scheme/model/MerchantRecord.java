package com.gme.sim.scheme.model;

/**
 * In-memory merchant record.
 */
public record MerchantRecord(
        String merchantId,
        String name,
        String city,
        String mcc
) {}
