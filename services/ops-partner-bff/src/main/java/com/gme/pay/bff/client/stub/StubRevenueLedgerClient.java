package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.RevenueLedgerClient;
import com.gme.pay.bff.web.dto.JournalPage;
import com.gme.pay.bff.web.dto.JournalView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase-1 in-memory stub of {@link RevenueLedgerClient}. Returns a fixed daily
 * total so the Admin dashboard renders a non-zero value; the range and
 * breakdown variants scale the daily figure by the (inclusive) day count.
 *
 * <p>Default bean: wired unless {@code gmepay.revenue-ledger.client=rest} selects
 * the live {@link com.gme.pay.bff.client.rest.RestRevenueLedgerClient}.
 */
@Component
@ConditionalOnProperty(
        name = "gmepay.revenue-ledger.client",
        havingValue = "stub",
        matchIfMissing = true)
public class StubRevenueLedgerClient implements RevenueLedgerClient {

    /** The fixed daily revenue triple used by both summary variants. */
    private static final BigDecimal DAILY_TOTAL  = new BigDecimal("1234.56");
    private static final BigDecimal DAILY_FEE    = new BigDecimal("420.10");
    private static final BigDecimal DAILY_MARGIN = new BigDecimal("814.46");

    @Override
    public RevenueSummary getSummary(LocalDate date) {
        return new RevenueSummary(
                date == null ? LocalDate.now() : date,
                DAILY_TOTAL, DAILY_FEE, DAILY_MARGIN);
    }

    @Override
    public RevenueSummary summaryRange(LocalDate from, LocalDate to) {
        LocalDate fromD = from == null ? LocalDate.now() : from;
        LocalDate toD   = to   == null ? fromD : to;
        long days = Math.max(1, ChronoUnit.DAYS.between(fromD, toD) + 1);
        BigDecimal scale = BigDecimal.valueOf(days);
        return new RevenueSummary(
                toD,
                DAILY_TOTAL.multiply(scale),
                DAILY_FEE.multiply(scale),
                DAILY_MARGIN.multiply(scale));
    }

    @Override
    public RevenueBreakdown breakdown(LocalDate from, LocalDate to) {
        // Deliberately small, deterministic shape so Admin UI charts render.
        Map<String, BigDecimal> byPartner = new LinkedHashMap<>();
        byPartner.put("partner_test_001", new BigDecimal("540.20"));
        byPartner.put("partner_test_002", new BigDecimal("412.00"));
        byPartner.put("partner_test_003", new BigDecimal("282.36"));

        Map<String, BigDecimal> byScheme = new LinkedHashMap<>();
        byScheme.put("zeropay_kr", new BigDecimal("680.00"));
        byScheme.put("paynow_sg",  new BigDecimal("310.40"));
        byScheme.put("upi_in",     new BigDecimal("244.16"));

        Map<String, BigDecimal> byCurrency = new LinkedHashMap<>();
        byCurrency.put("USD", new BigDecimal("810.10"));
        byCurrency.put("KRW", new BigDecimal("282.00"));
        byCurrency.put("JPY", new BigDecimal("142.46"));

        return new RevenueBreakdown(byPartner, byScheme, byCurrency);
    }

    @Override
    public JournalPage listJournals(Instant from, Instant to, String reference, Integer page, Integer size) {
        int p = page == null ? 0 : page;
        int s = size == null ? 50 : size;
        // Two deterministic balanced journals so the Admin UI journal view renders standalone.
        Instant now = Instant.now();
        JournalView j1 = new JournalView("jrnl-stub-0001", "TXN-00001", now, List.of(
                new JournalView.Line("RECEIVABLE_PARTNER", "DEBIT", new BigDecimal("12.34000000"), "USD"),
                new JournalView.Line("REVENUE_FX_MARGIN", "CREDIT", new BigDecimal("12.34000000"), "USD")));
        JournalView j2 = new JournalView("jrnl-stub-0002", "TXN-00002", now.minusSeconds(3600), List.of(
                new JournalView.Line("RECEIVABLE_PARTNER", "DEBIT", new BigDecimal("500.00000000"), "KRW"),
                new JournalView.Line("REVENUE_SERVICE_CHARGE", "CREDIT", new BigDecimal("500.00000000"), "KRW")));
        List<JournalView> items = (reference == null || reference.isBlank())
                ? List.of(j1, j2)
                : List.of(j1, j2).stream().filter(j -> reference.equals(j.reference())).toList();
        return new JournalPage(items, p, s, items.size());
    }
}
