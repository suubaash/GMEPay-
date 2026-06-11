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
 * <p>Columns: {@code txnId, partnerId, status, amount, currency, createdAt}.
 */
@Component
public class StubStatementClient implements StatementClient {

    /** ISO instant formatter for the {@code createdAt} column. */
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    /** The deterministic 5-row sample. Filtered by {@code createdAt.toLocalDate()}. */
    private static final List<Row> SAMPLE = List.of(
            new Row("TXN-1001", "COMMITTED", "125.50", "USD", LocalDate.of(2026, 6, 1)),
            new Row("TXN-1002", "COMMITTED", "75.00",  "USD", LocalDate.of(2026, 6, 3)),
            new Row("TXN-1003", "COMMITTED", "50000",  "KRW", LocalDate.of(2026, 6, 5)),
            new Row("TXN-1004", "FAILED",    "8000",   "JPY", LocalDate.of(2026, 6, 7)),
            new Row("TXN-1005", "COMMITTED", "210.25", "EUR", LocalDate.of(2026, 6, 9))
    );

    @Override
    public byte[] exportCsv(String partnerId, LocalDate from, LocalDate to) {
        String safePartner = partnerId == null ? "" : partnerId;
        LocalDate fromD = from == null ? LocalDate.MIN : from;
        LocalDate toD   = to   == null ? LocalDate.MAX : to;

        List<Row> filtered = new ArrayList<>();
        for (Row r : SAMPLE) {
            if (!r.createdAt().isBefore(fromD) && !r.createdAt().isAfter(toD)) {
                filtered.add(r);
            }
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append("txnId,partnerId,status,amount,currency,createdAt\n");
        for (Row r : filtered) {
            sb.append(r.txnId()).append(',')
              .append(safePartner).append(',')
              .append(r.status()).append(',')
              .append(r.amount()).append(',')
              .append(r.currency()).append(',')
              // Render the date as midnight UTC ISO-8601 so the column is
              // unambiguously parseable downstream.
              .append(ISO.format(r.createdAt().atStartOfDay().toInstant(ZoneOffset.UTC)))
              .append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Internal row shape (not exposed on the wire). */
    private record Row(
            String txnId,
            String status,
            String amount,
            String currency,
            LocalDate createdAt
    ) {}
}
