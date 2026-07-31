package com.gme.pay.settlement.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gme.pay.internalauth.InternalAuthHeaders;
import com.gme.pay.settlement.corridor.PrefundingMovement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link MockRestServiceServer} tests for {@link RestPrefundingMovementClient} — corridor tie-out
 * leg (b) against prefunding's date-ranged movement query (GAP T2-8).
 *
 * <p>What these pin, in order of why they exist:
 * <ul>
 *   <li><b>the window goes on the wire server-side</b> — the KST business day becomes
 *       {@code from}/{@code to} instants in the URL, so nothing is filtered client-side any more;</li>
 *   <li><b>reversals arrive and are signed</b> — a CREDIT row becomes a NEGATIVE float-consumed
 *       amount, which is what lets the reconciler net a deduct against its reversal;</li>
 *   <li><b>paging is complete</b> — the client walks {@code hasNext} to the end and returns every
 *       row, past the 500 the old {@code /deductions} endpoint clamped at;</li>
 *   <li><b>a partial read is never passed off as a day</b> — a mid-paging failure, or a
 *       {@code totalElements} that disagrees with the rows actually received, discards the result
 *       rather than handing the reconciler half a day (which would manufacture MISSING_PREFUNDING
 *       breaks that look exactly like real ones);</li>
 *   <li><b>the internal-auth token rides along</b> — prefunding's whole surface is gated (T0-5), so
 *       without it every read 401s and leg (b) is silently empty.</li>
 * </ul>
 */
class RestPrefundingMovementClientTest {

    /** Test fixture, not a credential — never read outside the test source set. */
    private static final String INTERNAL_TOKEN = "fixture-token-not-a-deployment-secret";

    private static final String BASE = "http://prefunding:8080";
    private static final String PARTNER = "SENDMN";
    private static final LocalDate DATE = LocalDate.of(2026, 7, 28);

    /** 2026-07-28 in Asia/Seoul is [2026-07-27T15:00Z, 2026-07-28T15:00Z). */
    private static final String FROM = "2026-07-27T15:00:00Z";
    private static final String TO = "2026-07-28T15:00:00Z";

    private static String url(int page) {
        return BASE + "/v1/prefunding/" + PARTNER + "/movements"
                + "?from=" + FROM
                + "&to=" + TO
                + "&types=DEBIT,CREDIT,CAPTURE"
                + "&page=" + page
                + "&size=" + RestPrefundingMovementClient.PAGE_SIZE;
    }

    private static String row(String ref, String type, String amount, String delta, String direction) {
        return "{\"ledgerEntryId\":1,\"txnRef\":\"" + ref + "\",\"entryType\":\"" + type + "\","
                + "\"amountUsd\":\"" + amount + "\",\"balanceDeltaUsd\":\"" + delta + "\","
                + "\"direction\":\"" + direction + "\",\"currency\":\"USD\","
                + "\"at\":\"2026-07-28T01:00:00Z\"}";
    }

    private static String page(long total, int pages, boolean hasNext, String... rows) {
        return "{\"partnerCode\":\"" + PARTNER + "\",\"from\":\"" + FROM + "\",\"to\":\"" + TO + "\","
                + "\"page\":0,\"size\":500,\"totalElements\":" + total + ",\"totalPages\":" + pages
                + ",\"hasNext\":" + hasNext + ",\"movements\":[" + String.join(",", rows) + "]}";
    }

    private record Fixture(MockRestServiceServer server, RestPrefundingMovementClient client) {}

    private static Fixture fixture(String token) {
        RestClient.Builder b = RestPrefundingMovementClient.builderFor(
                RestClient.builder(), BASE, token);
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        return new Fixture(server, new RestPrefundingMovementClient(b.build(), "Asia/Seoul"));
    }

    // -----------------------------------------------------------------------------------

    @Test
    @DisplayName("asks the server for the KST business day as a half-open [from, to) window, with the token")
    void windowsServerSideAndCarriesTheToken() {
        Fixture f = fixture(INTERNAL_TOKEN);
        f.server().expect(requestTo(url(0)))
                .andExpect(method(GET))
                .andExpect(header(InternalAuthHeaders.INTERNAL_TOKEN, INTERNAL_TOKEN))
                .andRespond(withSuccess(page(1, 1, false,
                                row("SENDMN-a", "DEBIT", "75.00000000", "-75.00000000", "DEBIT")),
                        MediaType.APPLICATION_JSON));

        List<PrefundingMovement> movements = f.client().deductionsOn(PARTNER, DATE);
        f.server().verify();

        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).reference()).isEqualTo("SENDMN-a");
        // float CONSUMED is positive — it compares directly against prefundingDeductedUsd
        assertThat(movements.get(0).amountUsd()).isEqualByComparingTo(new BigDecimal("75.00000000"));
        assertThat(movements.get(0).entryType()).isEqualTo("DEBIT");
    }

    @Test
    @DisplayName("a reversal comes back as a NEGATIVE float-consumed amount, so it nets its deduct out")
    void reversalIsSignedNegative() {
        Fixture f = fixture(INTERNAL_TOKEN);
        f.server().expect(requestTo(url(0)))
                .andRespond(withSuccess(page(2, 1, false,
                                row("SENDMN-r", "DEBIT", "75.00000000", "-75.00000000", "DEBIT"),
                                row("SENDMN-r", "CREDIT", "75.00000000", "75.00000000", "CREDIT")),
                        MediaType.APPLICATION_JSON));

        List<PrefundingMovement> movements = f.client().deductionsOn(PARTNER, DATE);
        f.server().verify();

        assertThat(movements).hasSize(2);
        assertThat(movements.get(0).amountUsd()).isEqualByComparingTo(new BigDecimal("75"));
        assertThat(movements.get(1).amountUsd()).isEqualByComparingTo(new BigDecimal("-75"));
        assertThat(movements.get(1).entryType()).isEqualTo("CREDIT");
        BigDecimal net = movements.stream().map(PrefundingMovement::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(net).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("pages until hasNext=false — 1,100 movements, past the old 500-row cap, with no loss")
    void pagesPastTheOldCap() {
        Fixture f = fixture(INTERNAL_TOKEN);
        int total = 1100;
        int size = RestPrefundingMovementClient.PAGE_SIZE;
        int index = 0;
        for (int p = 0; p * size < total; p++) {
            int rowsOnPage = Math.min(size, total - p * size);
            String[] rows = new String[rowsOnPage];
            for (int i = 0; i < rowsOnPage; i++) {
                rows[i] = row("SENDMN-" + index++, "DEBIT", "1.00000000", "-1.00000000", "DEBIT");
            }
            boolean hasNext = (p + 1) * size < total;
            f.server().expect(ExpectedCount.once(), requestTo(url(p)))
                    .andRespond(withSuccess(page(total, 3, hasNext, rows),
                            MediaType.APPLICATION_JSON));
        }

        List<PrefundingMovement> movements = f.client().deductionsOn(PARTNER, DATE);
        f.server().verify();

        assertThat(movements).hasSize(total);
        assertThat(movements.stream().map(PrefundingMovement::reference).distinct().count())
                .isEqualTo(total);
        assertThat(movements.get(0).reference()).isEqualTo("SENDMN-0");
        assertThat(movements.get(total - 1).reference()).isEqualTo("SENDMN-1099");
    }

    @Test
    @DisplayName("a failure PART-WAY through paging discards everything — never half a day")
    void midPagingFailureDiscardsPartialResult() {
        Fixture f = fixture(INTERNAL_TOKEN);
        f.server().expect(requestTo(url(0)))
                .andRespond(withSuccess(page(600, 2, true,
                                row("SENDMN-a", "DEBIT", "10.00000000", "-10.00000000", "DEBIT")),
                        MediaType.APPLICATION_JSON));
        f.server().expect(requestTo(url(1))).andRespond(withServerError());

        assertThat(f.client().deductionsOn(PARTNER, DATE)).isEmpty();
        f.server().verify();
    }

    @Test
    @DisplayName("rows received disagreeing with totalElements discards the day rather than inventing breaks")
    void totalMismatchDiscardsTheDay() {
        Fixture f = fixture(INTERNAL_TOKEN);
        // Server claims 5 movements but hands back 1 and says the day is done.
        f.server().expect(requestTo(url(0)))
                .andRespond(withSuccess(page(5, 1, false,
                                row("SENDMN-a", "DEBIT", "10.00000000", "-10.00000000", "DEBIT")),
                        MediaType.APPLICATION_JSON));

        assertThat(f.client().deductionsOn(PARTNER, DATE)).isEmpty();
        f.server().verify();
    }

    @Test
    @DisplayName("prefunding unreachable / 401 → empty leg (b), logged, never thrown")
    void outageDegradesToEmpty() {
        Fixture f = fixture(INTERNAL_TOKEN);
        f.server().expect(requestTo(url(0))).andRespond(withServerError());
        assertThat(f.client().deductionsOn(PARTNER, DATE)).isEmpty();
        f.server().verify();

        Fixture g = fixture(INTERNAL_TOKEN);
        g.server().expect(requestTo(url(0))).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThat(g.client().deductionsOn(PARTNER, DATE)).isEmpty();
        g.server().verify();
    }

    @Test
    @DisplayName("a blank secret sends NO token — fail-visible, never a fabricated credential")
    void blankSecretSendsNoToken() {
        Fixture f = fixture("  ");
        f.server().expect(requestTo(url(0)))
                .andExpect(headerDoesNotExist(InternalAuthHeaders.INTERNAL_TOKEN))
                .andRespond(withSuccess(page(0, 0, false), MediaType.APPLICATION_JSON));

        assertThat(f.client().deductionsOn(PARTNER, DATE)).isEmpty();
        f.server().verify();
    }

    @Test
    @DisplayName("un-joinable and zero-delta rows are skipped: operator top-ups and holds are not payments")
    void skipsRowsThatCannotBeJoinedOrMovedNoFloat() {
        Fixture f = fixture(INTERNAL_TOKEN);
        f.server().expect(requestTo(url(0)))
                .andRespond(withSuccess(page(4, 1, false,
                                // an operator top-up: real money, but keyed to no transaction
                                "{\"ledgerEntryId\":9,\"txnRef\":null,\"entryType\":\"CREDIT\","
                                        + "\"amountUsd\":\"5000.00000000\","
                                        + "\"balanceDeltaUsd\":\"5000.00000000\","
                                        + "\"direction\":\"CREDIT\",\"currency\":\"USD\","
                                        + "\"at\":\"2026-07-28T01:00:00Z\"}",
                                // a hold: no float moved
                                row("SENDMN-h", "RESERVE", "10.00000000", "0", "NONE"),
                                // unparseable timestamp
                                "{\"ledgerEntryId\":11,\"txnRef\":\"SENDMN-bad\","
                                        + "\"entryType\":\"DEBIT\",\"amountUsd\":\"1.00000000\","
                                        + "\"balanceDeltaUsd\":\"-1.00000000\",\"direction\":\"DEBIT\","
                                        + "\"currency\":\"USD\",\"at\":\"not-a-timestamp\"}",
                                row("SENDMN-ok", "DEBIT", "20.00000000", "-20.00000000", "DEBIT")),
                        MediaType.APPLICATION_JSON));

        List<PrefundingMovement> movements = f.client().deductionsOn(PARTNER, DATE);
        f.server().verify();

        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).reference()).isEqualTo("SENDMN-ok");
        assertThat(movements.get(0).amountUsd()).isEqualByComparingTo(new BigDecimal("20"));
    }

    @Test
    @DisplayName("an unusable balanceDeltaUsd falls back to amount + direction rather than dropping the row")
    void degradesToAmountAndDirection() {
        Fixture f = fixture(INTERNAL_TOKEN);
        f.server().expect(requestTo(url(0)))
                .andRespond(withSuccess(page(2, 1, false,
                                row("SENDMN-a", "DEBIT", "30.00000000", "not-a-number", "DEBIT"),
                                row("SENDMN-a", "CREDIT", "12.00000000", "", "CREDIT")),
                        MediaType.APPLICATION_JSON));

        List<PrefundingMovement> movements = f.client().deductionsOn(PARTNER, DATE);
        f.server().verify();

        assertThat(movements).hasSize(2);
        assertThat(movements.get(0).amountUsd()).isEqualByComparingTo(new BigDecimal("30"));
        assertThat(movements.get(1).amountUsd()).isEqualByComparingTo(new BigDecimal("-12"));
    }

    @Test
    @DisplayName("an empty day is an empty list, not a failure")
    void emptyDay() {
        Fixture f = fixture(INTERNAL_TOKEN);
        f.server().expect(requestTo(url(0)))
                .andRespond(withSuccess(page(0, 0, false), MediaType.APPLICATION_JSON));
        assertThat(f.client().deductionsOn(PARTNER, DATE)).isEmpty();
        f.server().verify();
    }
}
