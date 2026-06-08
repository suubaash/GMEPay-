package com.gme.pay.scheme.zeropay.adapter.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A generated or received ZeroPay batch file, ready for SFTP transfer or parsing.
 */
public record BatchFile(
        BatchType fileType,
        LocalDate businessDate,
        int sequenceNo,
        byte[] contentBytes,
        int recordCount,
        BigDecimal controlSum
) {}
