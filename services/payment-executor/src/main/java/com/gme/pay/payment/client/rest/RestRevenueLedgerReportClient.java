package com.gme.pay.payment.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.payment.domain.PaymentException;
import com.gme.pay.payment.domain.client.RevenueLedgerReportClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * HTTP implementation of {@link RevenueLedgerReportClient} (gap <b>T2-5</b>): reads revenue-ledger's two
 * existing finance reports so the day-close can quote them rather than recompute them.
 *
 * <pre>
 *   GET /v1/journals/trial-balance?startDate=&amp;endDate=
 *   GET /v1/revenue/journal-reconciliation?startDate=&amp;endDate=
 * </pre>
 *
 * <p><b>{@code strict} is deliberately NOT set.</b> Both endpoints answer 409 with the report in the body when
 * {@code strict=true} and the period does not balance. The day-close wants that report either way — an imbalance
 * is a variance to NAME, not a reason to have no ledger leg at all. The imbalance is carried through on
 * {@code balanced}/{@code imbalances} and the day-close raises it as a named variance.
 *
 * <p><b>Throws on failure.</b> Unlike the write-side {@code RestRevenueLedgerClient}, silence is not an option
 * here: a report that printed zeros because it could not reach the ledger would look like a clean day.
 */
@Component
public class RestRevenueLedgerReportClient implements RevenueLedgerReportClient {

    private final RestClient restClient;

    /** Production wiring; {@code @Autowired} is required because this component has a second constructor. */
    @Autowired
    public RestRevenueLedgerReportClient(
            RestClient.Builder builder,
            @Value("${gmepay.revenue-ledger.base-url:http://revenue-ledger:8080}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /** Test constructor taking a pre-built client (e.g. bound to a {@code MockRestServiceServer}). */
    public RestRevenueLedgerReportClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public TrialBalance fetchTrialBalance(LocalDate start, LocalDate end) {
        TrialBalanceResponse body = get("/v1/journals/trial-balance", start, end,
                TrialBalanceResponse.class);
        return new TrialBalance(body.balanced(),
                map(body.currencies()), map(body.imbalances()),
                body.rows() == null ? List.of() : body.rows().stream()
                        .map(r -> new AccountRow(r.account(), r.currency(), r.debitTotal(),
                                r.creditTotal(), r.balance(), r.lineCount()))
                        .toList());
    }

    @Override
    public JournalReconciliation fetchJournalReconciliation(LocalDate start, LocalDate end) {
        JournalReconciliationResponse body = get("/v1/revenue/journal-reconciliation", start, end,
                JournalReconciliationResponse.class);
        return new JournalReconciliation(
                body.clean(),
                coverage(body.revenueRecords()),
                coverage(body.commissionSplits()),
                body.tieOuts() == null ? List.of() : body.tieOuts().stream()
                        .map(t -> new TieOut(t.stream(), t.account(), t.currency(), t.recordedAmount(),
                                t.journalledAmount(), t.variance(), t.tied()))
                        .toList(),
                body.unmappedComponents() == null ? List.of() : body.unmappedComponents().stream()
                        .map(u -> new UnmappedComponent(u.component(), u.source(), u.currency(), u.amount(),
                                u.recordCount(), u.reason(), u.decisionRequired()))
                        .toList());
    }

    private <T> T get(String path, LocalDate start, LocalDate end, Class<T> type) {
        try {
            T body = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path(path)
                            .queryParam("startDate", start.toString())
                            .queryParam("endDate", end.toString())
                            .build())
                    .retrieve()
                    .body(type);
            if (body == null) {
                throw new PaymentException("revenue-ledger returned an empty body for GET " + path
                        + " [" + start + ".." + end + "]");
            }
            return body;
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException("revenue-ledger GET " + path + " [" + start + ".." + end
                    + "] failed: " + ex, ex);
        }
    }

    private static List<CurrencyTotals> map(List<CurrencyTotalsResponse> rows) {
        return rows == null ? List.of() : rows.stream()
                .map(c -> new CurrencyTotals(c.currency(), c.debitTotal(), c.creditTotal(), c.difference(),
                        c.balanced(), c.lineCount()))
                .toList();
    }

    private static Coverage coverage(CoverageResponse c) {
        if (c == null) {
            return new Coverage(null, 0, 0, 0, 0, List.of(), false);
        }
        return new Coverage(c.source(), c.total(), c.journalled(), c.notJournalled(), c.zeroAmount(),
                c.notJournalledTxnRefs() == null ? List.of() : c.notJournalledTxnRefs(), c.truncated());
    }

    // ---- wire projections of revenue-ledger's response records ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TrialBalanceResponse(boolean balanced, List<CurrencyTotalsResponse> currencies,
                                List<CurrencyTotalsResponse> imbalances,
                                List<AccountRowResponse> rows) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountRowResponse(String account, String currency, BigDecimal debitTotal,
                              BigDecimal creditTotal, BigDecimal balance, long lineCount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CurrencyTotalsResponse(String currency, BigDecimal debitTotal, BigDecimal creditTotal,
                                  BigDecimal difference, boolean balanced, long lineCount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record JournalReconciliationResponse(boolean clean, CoverageResponse revenueRecords,
                                         CoverageResponse commissionSplits, List<TieOutResponse> tieOuts,
                                         List<UnmappedComponentResponse> unmappedComponents) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CoverageResponse(String source, long total, long journalled, long notJournalled,
                            long zeroAmount, List<String> notJournalledTxnRefs, boolean truncated) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TieOutResponse(String stream, String account, String currency, BigDecimal recordedAmount,
                          BigDecimal journalledAmount, BigDecimal variance, boolean tied) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UnmappedComponentResponse(String component, String source, String currency, BigDecimal amount,
                                     long recordCount, String reason, String decisionRequired) {}
}
