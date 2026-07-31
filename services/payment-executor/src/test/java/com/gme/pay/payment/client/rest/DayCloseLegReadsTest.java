package com.gme.pay.payment.client.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gme.pay.payment.domain.PaymentException;
import com.gme.pay.payment.domain.client.PrefundingClient;
import com.gme.pay.payment.domain.client.RevenueLedgerReportClient;
import com.gme.pay.payment.domain.client.TransactionClient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * <b>T2-5 leg-read proof.</b> The day-close is only as trustworthy as the three reads it is built from, and the
 * failure mode that matters is not "the read threw" — it is "the read came back INCOMPLETE and the report
 * treated it as complete". Half a day of transactions or float movements produces variances indistinguishable
 * from real ones while the run looks successful.
 *
 * <p>So each read here is pinned on completeness rather than just on happy-path parsing:
 * {@link #transactionsArePagedToExhaustion()}, {@link #aFailurePartWayThroughPagingDiscardsTheWholeDay()},
 * {@link #movementsAreNettedSignedAcrossPages()} and
 * {@link #aRowCountThatDisagreesWithTotalElementsDiscardsTheWindow()}.
 *
 * <p>{@link #theLedgerLegIsQUOTEDFromRevenueLedgersOwnReports()} pins the other half of the design: the ledger
 * leg comes off revenue-ledger's existing endpoints, including its {@code balanced}/{@code clean} verdicts and
 * the T2-10 {@code unmappedComponents} — nothing here recomputes the ledger's arithmetic.
 */
class DayCloseLegReadsTest {

    private static final LocalDate D = LocalDate.of(2026, 7, 28);

    private static String txnPage(long totalElements, String... refs) {
        StringBuilder sb = new StringBuilder("{\"totalElements\":").append(totalElements)
                .append(",\"content\":[");
        for (int i = 0; i < refs.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"txnRef\":\"").append(refs[i]).append("\",\"qrSchemeId\":\"sendmn\",")
                    .append("\"sendCcy\":\"KRW\",\"sendAmount\":\"1350000\",")
                    .append("\"targetCcy\":\"MNT\",\"targetPayout\":\"3000000\",")
                    .append("\"prefundingDeductedUsd\":\"1000\",\"approvedAt\":\"2026-07-28T05:00:00Z\"}");
        }
        return sb.append("]}").toString();
    }

    @Test
    @DisplayName("transactions are paged to exhaustion, driven by totalElements")
    void transactionsArePagedToExhaustion() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://transaction-mgmt:8082");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // 501 rows over two pages of 500: a client that read one page would silently drop the 501st.
        String[] first = new String[500];
        for (int i = 0; i < 500; i++) {
            first[i] = "T" + i;
        }
        server.expect(requestTo("http://transaction-mgmt:8082/v1/transactions?from=2026-07-28"
                        + "&to=2026-07-28&status=APPROVED&page=0&size=500"))
                .andRespond(withSuccess(txnPage(501, first), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://transaction-mgmt:8082/v1/transactions?from=2026-07-28"
                        + "&to=2026-07-28&status=APPROVED&page=1&size=500"))
                .andRespond(withSuccess(txnPage(501, "T500"), MediaType.APPLICATION_JSON));

        List<TransactionClient.DailyTransaction> rows =
                new RestTransactionClient(builder.build()).findApprovedForDate(D);

        assertThat(rows).hasSize(501);
        assertThat(rows.get(500).txnRef()).isEqualTo("T500");
        assertThat(rows.get(0).corridor()).isEqualTo("sendmn KRW->MNT");
        assertThat(rows.get(0).prefundDeductedUsd()).isEqualByComparingTo("1000");
        server.verify();
    }

    @Test
    @DisplayName("a failure PART-WAY through paging discards the whole day rather than reporting it partial")
    void aFailurePartWayThroughPagingDiscardsTheWholeDay() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://transaction-mgmt:8082");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String[] first = new String[500];
        for (int i = 0; i < 500; i++) {
            first[i] = "T" + i;
        }
        server.expect(requestTo("http://transaction-mgmt:8082/v1/transactions?from=2026-07-28"
                        + "&to=2026-07-28&status=APPROVED&page=0&size=500"))
                .andRespond(withSuccess(txnPage(1000, first), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://transaction-mgmt:8082/v1/transactions?from=2026-07-28"
                        + "&to=2026-07-28&status=APPROVED&page=1&size=500"))
                .andRespond(withServerError());

        RestTransactionClient client = new RestTransactionClient(builder.build());

        assertThatThrownBy(() -> client.findApprovedForDate(D))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("DISCARDED rather than reported partial");
    }

    @Test
    @DisplayName("float movements are netted SIGNED across pages, so a reversal cancels its deduction")
    void movementsAreNettedSignedAcrossPages() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://prefunding:8080");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Instant from = Instant.parse("2026-07-27T15:00:00Z");
        Instant to = Instant.parse("2026-07-28T15:00:00Z");
        String base = "http://prefunding:8080/v1/prefunding/SENDMN/movements?from=" + from
                + "&to=" + to + "&types=DEBIT,CREDIT,CAPTURE&page=";
        server.expect(requestTo(base + "0&size=500"))
                .andRespond(withSuccess("""
                        {"partnerCode":"SENDMN","totalElements":3,"hasNext":true,"movements":[
                          {"txnRef":"T1","entryType":"DEBIT","balanceDeltaUsd":"-1000",
                           "at":"2026-07-28T05:00:00Z"},
                          {"txnRef":"T1","entryType":"CREDIT","balanceDeltaUsd":"1000",
                           "at":"2026-07-28T06:00:00Z"}]}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo(base + "1&size=500"))
                .andRespond(withSuccess("""
                        {"partnerCode":"SENDMN","totalElements":3,"hasNext":false,"movements":[
                          {"txnRef":"T2","entryType":"DEBIT","balanceDeltaUsd":"-500",
                           "at":"2026-07-28T07:00:00Z"}]}""", MediaType.APPLICATION_JSON));

        List<PrefundingClient.FloatMovement> movements =
                new RestPrefundingClient(builder.build()).movements("SENDMN", from, to);

        assertThat(movements).hasSize(3);
        assertThat(movements.stream()
                .map(PrefundingClient.FloatMovement::delta)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))
                .as("deduct + reverse + deduct = -500: a reversed payment does not look like consumed float")
                .isEqualByComparingTo("-500");
        server.verify();
    }

    @Test
    @DisplayName("a row count that disagrees with totalElements DISCARDS the window")
    void aRowCountThatDisagreesWithTotalElementsDiscardsTheWindow() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://prefunding:8080");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Instant from = Instant.parse("2026-07-27T15:00:00Z");
        Instant to = Instant.parse("2026-07-28T15:00:00Z");
        // hasNext=false but totalElements says there were 9 rows: completeness is ASSERTED, not assumed.
        server.expect(requestTo("http://prefunding:8080/v1/prefunding/SENDMN/movements?from=" + from
                        + "&to=" + to + "&types=DEBIT,CREDIT,CAPTURE&page=0&size=500"))
                .andRespond(withSuccess("""
                        {"partnerCode":"SENDMN","totalElements":9,"hasNext":false,"movements":[
                          {"txnRef":"T1","entryType":"DEBIT","balanceDeltaUsd":"-1000",
                           "at":"2026-07-28T05:00:00Z"}]}""", MediaType.APPLICATION_JSON));

        RestPrefundingClient client = new RestPrefundingClient(builder.build());

        assertThatThrownBy(() -> client.movements("SENDMN", from, to))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("totalElements=9")
                .hasMessageContaining("DISCARDED");
    }

    @Test
    @DisplayName("the ledger leg is QUOTED from revenue-ledger's own reports, verdicts and unmapped components")
    void theLedgerLegIsQUOTEDFromRevenueLedgersOwnReports() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://revenue-ledger:8080");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // strict is deliberately NOT sent: an imbalance is a variance to NAME, not a reason to have no leg.
        server.expect(requestTo("http://revenue-ledger:8080/v1/journals/trial-balance"
                        + "?startDate=2026-07-28&endDate=2026-07-28"))
                .andRespond(withSuccess("""
                        {"startDate":"2026-07-28","endDate":"2026-07-28","balanced":false,
                         "currencies":[{"currency":"KRW","debitTotal":"1000","creditTotal":"999",
                                        "difference":"1","balanced":false,"lineCount":5}],
                         "imbalances":[{"currency":"KRW","debitTotal":"1000","creditTotal":"999",
                                        "difference":"1","balanced":false,"lineCount":5}],
                         "rows":[{"account":"REVENUE_REVERSAL","currency":"KRW","debitTotal":"400",
                                  "creditTotal":"0","balance":"400","lineCount":1}]}""",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://revenue-ledger:8080/v1/revenue/journal-reconciliation"
                        + "?startDate=2026-07-28&endDate=2026-07-28"))
                .andRespond(withSuccess("""
                        {"startDate":"2026-07-28","endDate":"2026-07-28","clean":false,
                         "revenueRecords":{"source":"revenue_records","total":3,"journalled":2,
                            "notJournalled":1,"zeroAmount":0,"notJournalledTxnRefs":["T9"],
                            "truncated":false},
                         "commissionSplits":{"source":"commission_splits","total":1,"journalled":1,
                            "notJournalled":0,"zeroAmount":0,"notJournalledTxnRefs":[],"truncated":false},
                         "tieOuts":[{"stream":"FX_MARGIN","account":"REVENUE_FX_MARGIN","currency":"USD",
                            "recordedAmount":"5.00","journalledAmount":"5.00","variance":"0.00",
                            "tied":true}],
                         "unmappedComponents":[{"component":"PARTNER_COMMISSION_SHARE",
                            "source":"commission_splits.partner_share_krw","currency":"KRW",
                            "amount":"378.0000","recordCount":1,"reason":"no account code exists",
                            "decisionRequired":"which account, and expense vs contra-revenue"}]}""",
                        MediaType.APPLICATION_JSON));

        RestRevenueLedgerReportClient client = new RestRevenueLedgerReportClient(builder.build());
        RevenueLedgerReportClient.TrialBalance tb = client.fetchTrialBalance(D, D);
        RevenueLedgerReportClient.JournalReconciliation jr = client.fetchJournalReconciliation(D, D);

        assertThat(tb.balanced()).isFalse();
        assertThat(tb.imbalances()).hasSize(1);
        assertThat(tb.imbalances().get(0).difference()).isEqualByComparingTo("1");
        assertThat(tb.account("REVENUE_REVERSAL", "KRW").debitTotal())
                .as("per-account rows are needed: T2-11's double relief leaves every currency balanced")
                .isEqualByComparingTo("400");
        assertThat(tb.account("RECEIVABLE_PARTNER", "KRW")).isNull();

        assertThat(jr.clean()).isFalse();
        assertThat(jr.revenueRecords().notJournalled()).isEqualTo(1);
        assertThat(jr.revenueRecords().notJournalledTxnRefs()).containsExactly("T9");
        assertThat(jr.tieOuts()).singleElement()
                .satisfies(t -> assertThat(t.tied()).isTrue());
        assertThat(jr.unmappedComponents()).singleElement().satisfies(u -> {
            assertThat(u.component()).isEqualTo("PARTNER_COMMISSION_SHARE");
            assertThat(u.amount()).isEqualByComparingTo("378.0000");
            assertThat(u.decisionRequired()).contains("contra-revenue");
        });
        server.verify();
    }

    @Test
    @DisplayName("an unreadable ledger THROWS — a report that printed zeros would look like a clean day")
    void anUnreadableLedgerThrows() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://revenue-ledger:8080");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://revenue-ledger:8080/v1/journals/trial-balance"
                        + "?startDate=2026-07-28&endDate=2026-07-28"))
                .andRespond(withServerError());

        RestRevenueLedgerReportClient client = new RestRevenueLedgerReportClient(builder.build());

        assertThatThrownBy(() -> client.fetchTrialBalance(D, D))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("/v1/journals/trial-balance");
    }
}
