package com.gme.pay.bff.web;

import com.gme.pay.bff.client.TransactionMgmtClient;
import com.gme.pay.bff.client.TransactionMgmtClient.TransactionSummary;
import com.gme.pay.bff.web.dto.SchemeStatement;
import com.gme.pay.rbac.RbacHeaders;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * QR-scheme reconciliation statement (owner Goal #6). Lets GME produce, for a QR-scheme partner
 * (e.g. ZEROPAY, NEPAL), the transactions GME recorded for that scheme — with per-currency totals —
 * so the scheme can reconcile against its own records.
 *
 * <ul>
 *   <li>{@code GET /v1/admin/schemes/{schemeId}/statement?from=&to=&page=&size=} — one page of the
 *       scheme's transactions (newest-first) plus per-currency totals over the whole window.</li>
 * </ul>
 *
 * <p><b>Admin-facing.</b> GME produces/exports the statement to hand to the scheme; no new auth
 * surface. Read-only, so gated on {@code txn.view} (fail-closed) like the other read endpoints.
 *
 * <p><b>Sourcing of {@code totals}.</b> transaction-mgmt's {@code GET /v1/transactions/stats}
 * groups {@code byCorridor} (= scheme_id) but returns only per-corridor <em>counts</em> — it carries
 * neither currency nor summed amount. Rather than change that shared hot aggregate to add
 * per-currency gross, we take the lighter path for a <em>single-scheme</em> statement: page the
 * scheme's window via transaction-mgmt's {@code GET /v1/transactions?schemeId=&from=&to=} (the
 * additive scheme filter) in bounded chunks and fold per currency. The fetch is bounded — it is one
 * scheme's window, capped page count — so it never loads unbounded rows.
 */
@RestController
@RequestMapping("/v1/admin/schemes")
public class SchemeStatementController {

    /** Default window when from/to are omitted: the last 30 days. */
    private static final int DEFAULT_WINDOW_DAYS = 30;

    /** Item-page size cap. */
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 50;

    /** Chunk size for the full-window totals fetch. */
    private static final int TOTALS_CHUNK = MAX_PAGE_SIZE;

    /**
     * Defensive cap on the number of chunks pulled while summing totals, so a pathologically large
     * window can never fan out unbounded. 500 chunks * 200 = up to 100k txns folded per statement.
     */
    private static final int MAX_TOTALS_CHUNKS = 500;

    private final TransactionMgmtClient transactions;
    private final OpsRbacGuard rbac;

    public SchemeStatementController(TransactionMgmtClient transactions, OpsRbacGuard rbac) {
        this.transactions = transactions;
        this.rbac = rbac;
    }

    @GetMapping("/{schemeId}/statement")
    public SchemeStatement statement(
            @PathVariable String schemeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestHeader(value = RbacHeaders.PERMISSIONS, required = false) String permissions) {
        rbac.requireTxnView(permissions);

        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(Duration.ofDays(DEFAULT_WINDOW_DAYS));
        // transaction-mgmt's list endpoint filters createdAt by inclusive LocalDate bounds; convert
        // the instant window to UTC dates (to is inclusive-date, so its calendar day is included).
        LocalDate fromDate = start.atOffset(ZoneOffset.UTC).toLocalDate();
        LocalDate toDate = end.atOffset(ZoneOffset.UTC).toLocalDate();

        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size <= 0 ? DEFAULT_PAGE_SIZE : size), MAX_PAGE_SIZE);

        // --- items: this page of the scheme's txns, newest-first (upstream orders by createdAt DESC).
        TransactionMgmtClient.Page<TransactionSummary> pageResult = transactions.list(
                new TransactionMgmtClient.Filter(
                        null, schemeId, null, fromDate, toDate, safePage, safeSize));
        List<SchemeStatement.Item> items = pageResult.content().stream()
                .map(SchemeStatementController::toItem)
                .toList();

        // --- totals: per-currency count + summed amount over the FULL window (not just this page).
        List<SchemeStatement.CurrencyTotal> totals =
                computeTotals(schemeId, fromDate, toDate);

        return new SchemeStatement(
                schemeId,
                new SchemeStatement.Window(start, end),
                totals,
                items,
                pageResult.page(),
                pageResult.size(),
                pageResult.total());
    }

    /**
     * Folds the scheme's full-window transactions into per-currency count + gross. Pages through
     * transaction-mgmt in bounded chunks scoped to the single scheme; stops when a short (final)
     * page is seen or the defensive chunk cap is hit. Insertion-ordered by first-seen currency.
     */
    private List<SchemeStatement.CurrencyTotal> computeTotals(
            String schemeId, LocalDate fromDate, LocalDate toDate) {
        Map<String, long[]> countByCcy = new LinkedHashMap<>();
        Map<String, BigDecimal> grossByCcy = new LinkedHashMap<>();

        for (int chunk = 0; chunk < MAX_TOTALS_CHUNKS; chunk++) {
            TransactionMgmtClient.Page<TransactionSummary> p = transactions.list(
                    new TransactionMgmtClient.Filter(
                            null, schemeId, null, fromDate, toDate, chunk, TOTALS_CHUNK));
            List<TransactionSummary> rows = p.content();
            if (rows.isEmpty()) {
                break;
            }
            for (TransactionSummary t : rows) {
                String ccy = t.currency();
                countByCcy.computeIfAbsent(ccy, k -> new long[1])[0]++;
                BigDecimal amt = t.amount() != null ? t.amount() : BigDecimal.ZERO;
                grossByCcy.merge(ccy, amt, BigDecimal::add);
            }
            // Last page reached — a short page means no more rows.
            if (rows.size() < TOTALS_CHUNK) {
                break;
            }
        }

        List<SchemeStatement.CurrencyTotal> out = new ArrayList<>(countByCcy.size());
        for (Map.Entry<String, long[]> e : countByCcy.entrySet()) {
            out.add(new SchemeStatement.CurrencyTotal(
                    e.getKey(), e.getValue()[0], grossByCcy.get(e.getKey())));
        }
        return out;
    }

    /** Maps the upstream transaction projection to a statement row (reusing the txn fields). */
    private static SchemeStatement.Item toItem(TransactionSummary t) {
        return new SchemeStatement.Item(
                t.txnId(),        // txnRef
                t.committedAt(),  // occurredAt (= createdAt upstream)
                t.merchantId(),
                t.partnerId(),
                t.amount(),       // = targetPayout upstream
                t.currency(),     // = targetCcy upstream
                t.state());       // status
    }
}
