package com.gme.pay.scheme.zeropay.adapter.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Generic parsed record from a ZeroPay batch file.
 * The {@code fields} map contains all fixed-width field values keyed by field name.
 */
public record BatchRecord(
        BatchType fileType,
        String recordType,
        LocalDate businessDate,
        Map<String, String> fields,
        BigDecimal amount
) {}
