package com.gme.pay.scheme.zeropay.batch;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Header record of a ZP0011 file (SCH-06 §5.2).
 *
 * <p>Fixed-width layout:</p>
 * <pre>
 *  Offset  Width  Field
 *  0       6      fileType             "ZP0011"
 *  6       8      businessDate         YYYYMMDD
 *  14      10     gmeInstitutionCode   CHAR(10)
 *  24      6      totalRecordCount     NUM(6)
 *  30      15     totalPayoutAmountKrw NUM(15)
 * </pre>
 * Total header length: 45 characters.
 */
public record Zp0011HeaderRecord(
        String fileType,
        LocalDate businessDate,
        String gmeInstitutionCode,
        int totalRecordCount,
        BigDecimal totalPayoutAmountKrw
) {

    static final String FILE_TYPE_VALUE      = "ZP0011";
    static final int FILE_TYPE_OFFSET        = 0;
    static final int FILE_TYPE_LEN           = 6;
    static final int BUSINESS_DATE_OFFSET    = 6;
    static final int BUSINESS_DATE_LEN       = 8;
    static final int INSTITUTION_CODE_OFFSET = 14;
    static final int INSTITUTION_CODE_LEN    = 10;
    static final int RECORD_COUNT_OFFSET     = 24;
    static final int RECORD_COUNT_LEN        = 6;
    static final int TOTAL_AMOUNT_OFFSET     = 30;
    static final int TOTAL_AMOUNT_LEN        = 15;

    /** Total length of the header line. */
    static final int RECORD_LENGTH           = 45;
}
