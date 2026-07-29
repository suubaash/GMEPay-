package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.StatementClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Standalone-boot fallback for {@link StatementClient}: emits the header row and NO data.
 *
 * <h2>Why this has no sample rows any more (gap register T1-3)</h2>
 *
 * <p>This class used to emit five hardcoded transactions — {@code TXN-1001..1005}, every one
 * {@code zeropay_kr}, every FX rate {@code 1325.00000000}, dated 1–9 June 2026 — filtered by the
 * requested date range so the file even looked responsive to the picker. Because no
 * {@code RestStatementClient} existed, that was the statement <b>every</b> partner downloaded. It is
 * a finance document: fabricated rows in it are indistinguishable from a record of real money
 * movement, and it named amounts, currencies and rates that never occurred.
 *
 * <p>A header-only CSV is the honest answer for stub mode: the BFF is not connected to
 * transaction-mgmt, so it knows of no transactions. Real statements require
 * {@code gmepay.transaction-mgmt.client=rest}, which activates
 * {@link com.gme.pay.bff.client.rest.RestStatementClient} and builds every cell from a persisted
 * transaction row; that selector is set on every deploy target.
 *
 * <p>The header itself is retained (and identical to the real client's) so the download contract,
 * content type and column list are unchanged — a partner opening the file sees the expected columns
 * with no rows, not a broken file.
 */
@Component
public class StubStatementClient implements StatementClient {

    private static final Logger log = LoggerFactory.getLogger(StubStatementClient.class);

    /**
     * UC-10-02 CSV header — no revenue fields. Kept byte-identical to
     * {@link com.gme.pay.bff.client.rest.RestStatementClient#UC10_HEADER} so stub and real mode
     * produce the same columns.
     */
    public static final String UC10_HEADER =
            "timestamp,qrSchemeId,krwAmount,payerCcyAmount,payerCurrency,appliedFxRate,prefundingDeductedUsd,status";

    @Override
    public byte[] exportCsv(String partnerId, LocalDate from, LocalDate to) {
        log.debug("statement requested for partner '{}' [{} .. {}] but the BFF is in stub mode — "
                + "emitting the header only. Set GMEPAY_TRANSACTION_MGMT_CLIENT=rest to build the "
                + "statement from real transactions.", partnerId, from, to);
        return (UC10_HEADER + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
