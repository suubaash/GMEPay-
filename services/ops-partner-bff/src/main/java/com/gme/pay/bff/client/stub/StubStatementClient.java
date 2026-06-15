package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.StatementClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase-1 in-memory stub of {@link StatementClient}. Generates a deterministic
 * 5-row sample CSV (header + up to 5 transaction rows) restricted to the
 * inclusive {@code [from, to]} date range so the Partner Portal Statement
 * page can offer downloads without booting reporting-compliance.
 *
 * <p>UC-10-02 columns: {@code timestamp,qrSchemeId,krwAmount,payerCcyAmount,
 * payerCurrency,appliedFxRate,prefundingDeductedUsd,status}.
 * Money fields are decimal strings per MONEY_CONVENTION.md.
 *
 * <p>IMPORTANT: internal revenue fields (fxMarginPct, gmeRevenue) are NOT
 * included — revenue is Admin-only.
 */
@Component
public class StubStatementClient implements StatementClient {

    /** UC-10-02 CSV header — no revenue fields. */
    public static final String UC10_HEADER =
            "timestamp,qrSchemeId,krwAmount,payerCcyAmount,payerCurrency,appliedFxRate,prefundingDeductedUsd,status";

    /** ISO instant formatter for the {@code timestamp} column. */
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    /**
     * Deterministic 5-row sample. Filtered by {@code createdAt.toLocalDate()}.
     *
     * <p>Fields: txnId, qrSchemeId, krwAmount (KRW string), payerCcyAmount,
     * payerCurrency, appliedFxRate, prefundingDeductedUsd (USD string), status, createdAt.
     *
     * <p>Revenue fields (fxMarginPct, gmeRevenue) are deliberately absent.
     */
    private static final List<Row> SAMPLE = List.of(
            new Row("TXN-1001", "zeropay_kr", "166330", "125.50", "USD", "1325.00000000", "125.50", "COMMITTED", LocalDate.of(2026, 6, 1)),
            new Row("TXN-1002", "zeropay_kr",  "99375",  "75.00", "USD", "1325.00000000",  "75.00", "COMMITTED", LocalDate.of(2026, 6, 3)),
            new Row("TXN-1003", "zeropay_kr",  "50000",  "50000", "KRW",               "",      "", "COMMITTED", LocalDate.of(2026, 6, 5)),
            new Row("TXN-1004", "zeropay_kr",       "",   "8000", "JPY",               "",      "", "FAILED",    LocalDate.of(2026, 6, 7)),
            new Row("TXN-1005", "zeropay_kr", "278581", "210.25", "EUR", "1325.00000000", "210.25", "COMMITTED", LocalDate.of(2026, 6, 9))
    );

    @Override
    public byte[] exportCsv(String partnerId, LocalDate from, LocalDate to) {
        LocalDate fromD = from == null ? LocalDate.MIN : from;
        LocalDate toD   = to   == null ? LocalDate.MAX : to;

        List<Row> filtered = new ArrayList<>();
        for (Row r : SAMPLE) {
            if (!r.createdAt().isBefore(fromD) && !r.createdAt().isAfter(toD)) {
                filtered.add(r);
            }
        }

        StringBuilder sb = new StringBuilder(512);
        sb.append(UC10_HEADER).append('\n');
        for (Row r : filtered) {
            // timestamp: midnight UTC ISO-8601 (KST display is handled by the UI)
            sb.append(ISO.format(r.createdAt().atStartOfDay().toInstant(ZoneOffset.UTC))).append(',')
              .append(r.qrSchemeId()).append(',')
              .append(r.krwAmount()).append(',')
              .append(r.payerCcyAmount()).append(',')
              .append(r.payerCurrency()).append(',')
              .append(r.appliedFxRate()).append(',')
              .append(r.prefundingDeductedUsd()).append(',')
              .append(r.status())
              .append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Internal row shape — UC-10-02 fields only, no revenue fields.
     * Revenue fields (fxMarginPct, gmeRevenue) MUST NOT be added here.
     */
    private record Row(
            String txnId,
            String qrSchemeId,
            String krwAmount,
            String payerCcyAmount,
            String payerCurrency,
            String appliedFxRate,
            String prefundingDeductedUsd,
            String status,
            LocalDate createdAt
    ) {}
}
