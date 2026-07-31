package com.gme.pay.bff.client.rest;

import com.gme.pay.bff.client.StatementClient;
import com.gme.pay.bff.client.TransactionMgmtClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Production {@link StatementClient} — the partner CSV statement built from REAL transactions
 * (gap register T1-3).
 *
 * <p>Before this existed there was no rest implementation, so every partner's "statement" download
 * was {@code StubStatementClient}'s five hardcoded rows: {@code TXN-1001..1005}, all
 * {@code zeropay_kr}, all at FX rate {@code 1325.00000000}, dated June 2026. A partner could have
 * taken that file to their finance team as a record of money that never moved.
 *
 * <h2>Source of truth</h2>
 *
 * <p>transaction-mgmt, read through the already-wired {@link TransactionMgmtClient} — so the
 * statement and the Transactions page cannot disagree, and the partner-scoping / fail-closed rules
 * in {@link RestTransactionMgmtClient} apply to the statement for free. Active on the same selector,
 * {@code gmepay.transaction-mgmt.client=rest}, because that is the upstream this reads.
 *
 * <h2>Contract</h2>
 *
 * <p>The CSV shape is UNCHANGED — same {@link #UC10_HEADER} column list and order the Portal's
 * statement page already downloads, and money still rides as decimal strings per
 * MONEY_CONVENTION.md (never floats). Only the source changed: every cell now comes from a
 * persisted transaction row. Rows are ordered oldest-first by timestamp.
 *
 * <p>Revenue stripping is preserved: {@code fxMarginPct} / {@code gmeRevenue} are absent from the
 * header and are never read here. Revenue is Admin-only.
 *
 * <h2>Empty vs unavailable</h2>
 *
 * <p>A partner with no transactions in the window gets the header row alone — an honest empty
 * statement. If transaction-mgmt is unreachable the underlying client already degrades to an empty
 * page and logs; the statement is then also header-only rather than fabricated. It is never a
 * partially-real file: a truncated read (see {@link #MAX_ROWS}) is logged as a WARN.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.transaction-mgmt.client", havingValue = "rest", matchIfMissing = true)
public class RestStatementClient implements StatementClient {

    private static final Logger log = LoggerFactory.getLogger(RestStatementClient.class);

    /**
     * UC-10-02 CSV header — byte-identical to the contract the Portal already consumes.
     * No revenue fields.
     */
    public static final String UC10_HEADER =
            "timestamp,qrSchemeId,krwAmount,payerCcyAmount,payerCurrency,appliedFxRate,prefundingDeductedUsd,status";

    /** Upstream page size per request (transaction-mgmt caps at 500). */
    static final int PAGE_SIZE = 500;

    /**
     * Hard cap on statement rows so one partner-year cannot stream unbounded memory through the
     * BFF. Reaching it is logged as a WARN because the file would then be incomplete.
     */
    static final int MAX_ROWS = 50_000;

    /** ISO instant formatter for the {@code timestamp} column. */
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final TransactionMgmtClient transactions;

    public RestStatementClient(TransactionMgmtClient transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public byte[] exportCsv(String partnerId, LocalDate from, LocalDate to) {
        List<TransactionMgmtClient.TransactionSummary> rows = fetchAll(partnerId, from, to);

        StringBuilder sb = new StringBuilder(256 + rows.size() * 96);
        sb.append(UC10_HEADER).append('\n');
        for (TransactionMgmtClient.TransactionSummary t : rows) {
            sb.append(csv(instant(t.committedAt()))).append(',')
              .append(csv(t.qrSchemeId())).append(',')
              .append(csv(decimal(t.krwAmount()))).append(',')
              .append(csv(decimal(t.payerCurrencyAmount()))).append(',')
              .append(csv(t.payerCurrency())).append(',')
              .append(csv(decimal(t.appliedFxRate()))).append(',')
              .append(csv(decimal(t.prefundingDeductedUsd()))).append(',')
              .append(csv(t.state()))
              .append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Pages through transaction-mgmt for the partner + inclusive date window, oldest-first.
     *
     * <p>The date filter is applied UPSTREAM (transaction-mgmt's {@code from}/{@code to} params) so
     * the window is enforced by the service that owns the rows, not re-derived here.
     */
    private List<TransactionMgmtClient.TransactionSummary> fetchAll(
            String partnerId, LocalDate from, LocalDate to) {
        List<TransactionMgmtClient.TransactionSummary> out = new ArrayList<>();
        int page = 0;
        while (out.size() < MAX_ROWS) {
            TransactionMgmtClient.Page<TransactionMgmtClient.TransactionSummary> result =
                    transactions.list(new TransactionMgmtClient.Filter(
                            partnerId, null, null, from, to, page, PAGE_SIZE));
            if (result == null || result.content() == null || result.content().isEmpty()) {
                break;
            }
            out.addAll(result.content());
            if (result.content().size() < PAGE_SIZE) {
                break; // last page
            }
            page++;
        }
        if (out.size() >= MAX_ROWS) {
            log.warn("statement for partner {} [{} .. {}] hit the {}-row cap — the CSV is TRUNCATED; "
                    + "narrow the date range", partnerId, from, to, MAX_ROWS);
            out = out.subList(0, MAX_ROWS);
        }
        // Oldest-first, which is what a statement reads like. Null timestamps sort last.
        out.sort(java.util.Comparator.comparing(
                TransactionMgmtClient.TransactionSummary::committedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        return out;
    }

    /** ISO-8601 rendering, or empty when the row carries no timestamp (never a made-up one). */
    private static String instant(Instant at) {
        return at == null ? "" : ISO.format(at);
    }

    /**
     * Money/rate as a plain decimal string per MONEY_CONVENTION.md; empty when the field is null
     * (e.g. {@code appliedFxRate} on a same-currency txn, {@code prefundingDeductedUsd} before
     * APPROVED). An absent value stays absent — it is never zero-filled.
     */
    private static String decimal(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    /**
     * Minimal RFC-4180 escaping. Upstream values are refs/codes/decimals today, but a merchant- or
     * scheme-supplied string must not be able to inject a column break into a partner's statement.
     */
    private static String csv(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean needsQuoting = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!needsQuoting) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
