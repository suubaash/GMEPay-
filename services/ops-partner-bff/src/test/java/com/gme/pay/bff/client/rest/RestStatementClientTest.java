package com.gme.pay.bff.client.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.bff.client.TransactionMgmtClient;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gap T1-3: the partner CSV statement must be built from REAL transaction rows. It used to be five
 * hardcoded samples ({@code TXN-1001..1005}, every one {@code zeropay_kr}, every FX rate
 * {@code 1325.00000000}) served to every partner — a finance document made of fiction.
 *
 * <p>These tests drive {@link RestStatementClient} over a recording fake
 * {@link TransactionMgmtClient} so they can assert both the emitted CSV and the exact filter the
 * client asked transaction-mgmt for.
 */
class RestStatementClientTest {

    /** Records every filter it is asked for and replays a fixed row set. */
    private static final class RecordingTxnClient implements TransactionMgmtClient {
        private final List<TransactionSummary> rows;
        final List<Filter> filters = new ArrayList<>();

        RecordingTxnClient(List<TransactionSummary> rows) {
            this.rows = rows;
        }

        @Override
        public TransactionSummary getTransaction(String txnId) {
            return null;
        }

        @Override
        public List<TransactionSummary> recent(String partnerId, int limit) {
            return List.of();
        }

        @Override
        public Page<TransactionSummary> list(Filter filter) {
            filters.add(filter);
            // One page only; the client stops when a page is short of PAGE_SIZE.
            return filter.page() == 0
                    ? new Page<>(rows, 0, RestStatementClient.PAGE_SIZE, rows.size())
                    : new Page<>(List.of(), filter.page(), RestStatementClient.PAGE_SIZE, rows.size());
        }
    }

    private static TransactionSummaryBuilder txn(String id) {
        return new TransactionSummaryBuilder(id);
    }

    /** Small builder so each test states only the fields it cares about. */
    private static final class TransactionSummaryBuilder {
        private final String id;
        private String state = "COMMITTED";
        private Instant committedAt = Instant.parse("2026-07-01T00:00:00Z");
        private String qrSchemeId;
        private BigDecimal krwAmount;
        private String payerCurrency;
        private BigDecimal payerCurrencyAmount;
        private BigDecimal appliedFxRate;
        private BigDecimal prefundingDeductedUsd;

        TransactionSummaryBuilder(String id) {
            this.id = id;
        }

        TransactionSummaryBuilder at(String iso) {
            this.committedAt = Instant.parse(iso);
            return this;
        }

        TransactionSummaryBuilder state(String s) {
            this.state = s;
            return this;
        }

        TransactionSummaryBuilder scheme(String s) {
            this.qrSchemeId = s;
            return this;
        }

        TransactionSummaryBuilder money(String krw, String payerCcy, String payerAmt,
                                        String rate, String usd) {
            this.krwAmount = krw == null ? null : new BigDecimal(krw);
            this.payerCurrency = payerCcy;
            this.payerCurrencyAmount = payerAmt == null ? null : new BigDecimal(payerAmt);
            this.appliedFxRate = rate == null ? null : new BigDecimal(rate);
            this.prefundingDeductedUsd = usd == null ? null : new BigDecimal(usd);
            return this;
        }

        TransactionMgmtClient.TransactionSummary build() {
            return new TransactionMgmtClient.TransactionSummary(
                    id, "GMEREMIT", state, new BigDecimal("1"), "KRW", committedAt,
                    qrSchemeId, krwAmount, payerCurrency, payerCurrencyAmount, appliedFxRate,
                    null, prefundingDeductedUsd,
                    null, null, null, null, null, null, null, null, null);
        }
    }

    private static String csv(RestStatementClient client, String partner, String from, String to) {
        return new String(
                client.exportCsv(partner, LocalDate.parse(from), LocalDate.parse(to)),
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("builds every cell from the real transaction row, oldest first")
    void buildsCsvFromRealTransactions() {
        RecordingTxnClient txns = new RecordingTxnClient(List.of(
                txn("T-2").at("2026-07-05T09:30:00Z").scheme("sendmn_mn")
                        .money("1350000", "MNT", "3500000", "1325.50000000", "1000.00").build(),
                txn("T-1").at("2026-07-02T01:00:00Z").scheme("zeropay_kr")
                        .money("166330", "USD", "125.50", "1325.00000000", "125.50").build()));
        RestStatementClient client = new RestStatementClient(txns);

        String[] lines = csv(client, "GMEREMIT", "2026-07-01", "2026-07-31").split("\n");

        assertThat(lines[0]).isEqualTo(RestStatementClient.UC10_HEADER);
        // Oldest first, regardless of the order the upstream page arrived in.
        assertThat(lines[1]).isEqualTo(
                "2026-07-02T01:00:00Z,zeropay_kr,166330,125.50,USD,1325.00000000,125.50,COMMITTED");
        assertThat(lines[2]).isEqualTo(
                "2026-07-05T09:30:00Z,sendmn_mn,1350000,3500000,MNT,1325.50000000,1000.00,COMMITTED");
        assertThat(lines).hasSize(3);
    }

    @Test
    @DisplayName("asks transaction-mgmt for the partner + date window (the window is enforced upstream)")
    void forwardsPartnerAndDateWindow() {
        RecordingTxnClient txns = new RecordingTxnClient(List.of());
        RestStatementClient client = new RestStatementClient(txns);

        csv(client, "GMEREMIT", "2026-07-01", "2026-07-31");

        assertThat(txns.filters).hasSize(1);
        TransactionMgmtClient.Filter f = txns.filters.get(0);
        assertThat(f.partnerId()).isEqualTo("GMEREMIT");
        assertThat(f.fromDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(f.toDate()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    @DisplayName("no transactions in range -> header only (an honest empty statement)")
    void emptyRangeYieldsHeaderOnly() {
        RestStatementClient client = new RestStatementClient(new RecordingTxnClient(List.of()));

        String body = csv(client, "GMEREMIT", "2026-07-01", "2026-07-31");

        assertThat(body).isEqualTo(RestStatementClient.UC10_HEADER + "\n");
    }

    @Test
    @DisplayName("absent money fields stay EMPTY — never zero-filled or invented")
    void absentMoneyFieldsStayEmpty() {
        // A same-currency txn has no appliedFxRate, and a non-APPROVED txn has no
        // prefundingDeductedUsd. Writing 0 there would misstate a finance document.
        RecordingTxnClient txns = new RecordingTxnClient(List.of(
                txn("T-FAIL").at("2026-07-03T00:00:00Z").state("FAILED")
                        .money(null, "KRW", "50000", null, null).build()));
        RestStatementClient client = new RestStatementClient(txns);

        String[] lines = csv(client, "GMEREMIT", "2026-07-01", "2026-07-31").split("\n");

        // timestamp, (no scheme), (no krw), 50000, KRW, (no rate), (no usd), FAILED
        assertThat(lines[1]).isEqualTo("2026-07-03T00:00:00Z,,,50000,KRW,,,FAILED");
        assertThat(lines[1]).doesNotContain(",0,").doesNotContain(",0.00,");
    }

    @Test
    @DisplayName("the CSV carries no revenue columns (revenue is Admin-only)")
    void noRevenueColumns() {
        RecordingTxnClient txns = new RecordingTxnClient(List.of(
                txn("T-1").scheme("zeropay_kr").money("1000", "USD", "1", "1", "1").build()));
        RestStatementClient client = new RestStatementClient(txns);

        String body = csv(client, "GMEREMIT", "2026-07-01", "2026-07-31");

        for (String revenueField : new String[] {
                "fxMarginPct", "gmeRevenue", "marginRevenue", "feeRevenue"}) {
            assertThat(body).doesNotContain(revenueField);
        }
    }

    @Test
    @DisplayName("a value containing a comma is quoted so it cannot break a column")
    void injectedSeparatorIsEscaped() {
        RecordingTxnClient txns = new RecordingTxnClient(List.of(
                txn("T-1").at("2026-07-04T00:00:00Z")
                        .scheme("evil,scheme\"quoted").money(null, "KRW", "1", null, null).build()));
        RestStatementClient client = new RestStatementClient(txns);

        String[] lines = csv(client, "GMEREMIT", "2026-07-01", "2026-07-31").split("\n");

        assertThat(lines[1]).contains("\"evil,scheme\"\"quoted\"");
        // Still exactly 8 columns once the quoted field is accounted for.
        assertThat(lines[1].split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")).hasSize(8);
    }

    @Test
    @DisplayName("an unreachable transaction-mgmt yields an empty statement, not a fabricated one")
    void upstreamOutageDegradesHonestly() {
        // RestTransactionMgmtClient already degrades an outage to an empty page; the statement then
        // contains only the header rather than sample rows.
        TransactionMgmtClient down = new TransactionMgmtClient() {
            @Override
            public TransactionSummary getTransaction(String txnId) {
                return null;
            }

            @Override
            public List<TransactionSummary> recent(String partnerId, int limit) {
                return List.of();
            }

            @Override
            public Page<TransactionSummary> list(Filter filter) {
                return new Page<>(List.of(), 0, 500, 0L);
            }
        };

        assertThat(csv(new RestStatementClient(down), "GMEREMIT", "2026-07-01", "2026-07-31"))
                .isEqualTo(RestStatementClient.UC10_HEADER + "\n");
    }

    @Test
    @DisplayName("pages until the upstream returns a short page")
    void pagesThroughUpstream() {
        // A full first page must trigger a second request; a short page ends the loop.
        List<TransactionMgmtClient.TransactionSummary> fullPage = new ArrayList<>();
        for (int i = 0; i < RestStatementClient.PAGE_SIZE; i++) {
            fullPage.add(txn("T-" + i).at("2026-07-02T00:00:00Z")
                    .money(null, "KRW", "1", null, null).build());
        }
        RecordingTxnClient txns = new RecordingTxnClient(fullPage);
        RestStatementClient client = new RestStatementClient(txns);

        String[] lines = csv(client, "GMEREMIT", "2026-07-01", "2026-07-31").split("\n");

        assertThat(txns.filters).hasSize(2);
        assertThat(txns.filters.get(0).page()).isZero();
        assertThat(txns.filters.get(1).page()).isEqualTo(1);
        assertThat(lines).hasSize(RestStatementClient.PAGE_SIZE + 1);
    }
}
