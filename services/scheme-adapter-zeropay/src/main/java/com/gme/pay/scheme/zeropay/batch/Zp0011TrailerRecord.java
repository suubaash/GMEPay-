package com.gme.pay.scheme.zeropay.batch;

import java.math.BigDecimal;

/**
 * Trailer record of a ZP0011 file (SCH-06 §5.2).
 *
 * <p>Fixed-width layout:</p>
 * <pre>
 *  Offset  Width  Field
 *  0       1      recordType           "T" for trailer
 *  1       6      totalRecordCount     NUM(6)
 *  7       15     controlSum           NUM(15) sum of all detail payout_amount_krw
 * </pre>
 * Total trailer length: 22 characters.
 */
public record Zp0011TrailerRecord(
        int totalRecordCount,
        BigDecimal controlSum
) {

    static final int RECORD_COUNT_OFFSET = 1;
    static final int RECORD_COUNT_LEN    = 6;
    static final int CONTROL_SUM_OFFSET  = 7;
    static final int CONTROL_SUM_LEN     = 15;

    /** Total length of the trailer line. */
    static final int RECORD_LENGTH       = 22;
}
