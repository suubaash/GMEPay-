package com.gme.pay.bff.client.rest;

import com.gme.pay.bff.web.dto.FilingChannelState;
import com.gme.pay.bff.web.dto.ReportRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Filing-honesty contract for {@link RestReportingClient} (GAP T5-2).
 *
 * <p>The adapter used to stamp the literal {@code "GENERATED"} onto every run, discarding the
 * honest {@code filing_status} / {@code filing_channel_unavailable_reason} reporting-compliance
 * now returns. These tests pin the three properties that must hold:
 * honest statuses pass through <em>unchanged</em>; a missing upstream status never becomes a
 * success; the retired {@code SUBMITTED} vocabulary is reclassified rather than echoed.
 */
class RestReportingClientTest {

    private static final LocalDate FROM = LocalDate.parse("2025-06-01");
    private static final LocalDate TO = LocalDate.parse("2025-06-30");

    private static final String BOK_REASON =
            "gmepay.bok.channel.endpoint is blank — no BOK SFTP channel is configured (OI-03)";

    /** Two FX1014 records + one FX1015, so the per-type run grouping is exercised too. */
    private static final String RECORDS = """
            {"txn_id":"1","report_type":"FX1014","submission_status":"GENERATED"},
            {"txn_id":"2","report_type":"FX1014","submission_status":"GENERATED"},
            {"txn_id":"3","report_type":"FX1015","submission_status":"GENERATED"}
            """;

    private record Fixture(RestReportingClient client, MockRestServiceServer server) {}

    private static Fixture fixtureReturning(String body) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://reporting-compliance.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("/v1/reports")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        return new Fixture(new RestReportingClient(builder.build()), server);
    }

    private static String envelope(String filingStatusJson) {
        return """
                {"generated_at":"2026-07-28T02:00:00",
                 "total_count":3,
                 %s
                 "records":[%s]}
                """.formatted(filingStatusJson, RECORDS);
    }

    @Test
    @DisplayName("the honest NOT_FILED_CHANNEL_UNAVAILABLE status + reason + channel board pass through unchanged")
    void honestNotFiledStatusPassesThrough() {
        Fixture f = fixtureReturning(envelope("""
                "filing_status":"NOT_FILED_CHANNEL_UNAVAILABLE",
                "filing_channel_unavailable_reason":"%s",
                "filing_channels":[
                  {"lane":"BOK","channel_live":false,"reachable_status":"NOT_FILED_CHANNEL_UNAVAILABLE","reason":"%s"},
                  {"lane":"KOFIU","channel_live":false,"reachable_status":"NOT_FILED_CHANNEL_UNAVAILABLE","reason":"kofiu endpoint blank"},
                  {"lane":"HOMETAX","channel_live":false,"reachable_status":"NOT_FILED_CHANNEL_UNAVAILABLE","reason":"cert id is the placeholder stub-cert-id"}],
                """.formatted(BOK_REASON, BOK_REASON)));

        List<ReportRun> runs = f.client().listReports(null, FROM, TO);
        f.server().verify();

        assertThat(runs).hasSize(2);
        assertThat(runs).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo("NOT_FILED_CHANNEL_UNAVAILABLE");
            assertThat(r.filingChannelUnavailableReason()).isEqualTo(BOK_REASON);
            assertThat(r.filingChannels()).hasSize(3);
        });
        // record counts still grouped per type, unaffected by the status work
        assertThat(runs).extracting(ReportRun::type, ReportRun::recordCount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("BOK_FX1014", "2"),
                        org.assertj.core.groups.Tuple.tuple("BOK_FX1015", "1"));

        FilingChannelState hometax = runs.get(0).filingChannels().stream()
                .filter(c -> "HOMETAX".equals(c.lane())).findFirst().orElseThrow();
        assertThat(hometax.channelLive()).isFalse();
        assertThat(hometax.reachableStatus()).isEqualTo("NOT_FILED_CHANNEL_UNAVAILABLE");
        assertThat(hometax.reason()).contains("stub-cert-id");
    }

    @Test
    @DisplayName("an honest GENERATED comes from upstream, not from the adapter")
    void honestGeneratedStatusPassesThrough() {
        Fixture f = fixtureReturning(envelope("\"filing_status\":\"GENERATED\","));

        List<ReportRun> runs = f.client().listReports("BOK_FX1014", FROM, TO);
        f.server().verify();

        assertThat(runs).isNotEmpty();
        assertThat(runs).allSatisfy(r -> assertThat(r.status()).isEqualTo("GENERATED"));
    }

    @Test
    @DisplayName("a missing upstream filing_status becomes UNKNOWN — never GENERATED or a success")
    void absentUpstreamStatusIsNotASuccess() {
        Fixture f = fixtureReturning(envelope("")); // older service version: no filing_* fields

        List<ReportRun> runs = f.client().listReports(null, FROM, TO);
        f.server().verify();

        assertThat(runs).isNotEmpty();
        assertThat(runs).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo("UNKNOWN");
            assertThat(r.status()).isNotIn("GENERATED", "SUBMITTED", "CONFIRMED", "ACCEPTED", "FILED");
            assertThat(r.filingChannelUnavailableReason())
                    .contains("did not report a filing_status")
                    .contains("nothing may be assumed filed");
            assertThat(r.filingChannels()).isNull(); // no board reported → not an empty board
        });
    }

    @Test
    @DisplayName("the retired SUBMITTED vocabulary is reclassified, not echoed")
    void retiredSuccessVocabularyIsReclassified() {
        Fixture f = fixtureReturning(envelope("\"filing_status\":\"SUBMITTED\","));

        List<ReportRun> runs = f.client().listReports(null, FROM, TO);
        f.server().verify();

        assertThat(runs).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo("NOT_FILED_CHANNEL_UNAVAILABLE");
            assertThat(r.filingChannelUnavailableReason())
                    .contains("retired status 'SUBMITTED'")
                    .contains("reclassified");
        });
    }

    @Test
    @DisplayName("an unrecognised upstream status is UNKNOWN with the raw value named")
    void unrecognisedStatusIsUnknown() {
        Fixture f = fixtureReturning(envelope("\"filing_status\":\"ALL_GOOD\","));

        List<ReportRun> runs = f.client().listReports(null, FROM, TO);
        f.server().verify();

        assertThat(runs).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo("UNKNOWN");
            assertThat(r.filingChannelUnavailableReason()).contains("unrecognised filing_status 'ALL_GOOD'");
        });
    }

    @Test
    @DisplayName("downloadCsv normalises the submission_status column so the artifact carries no fake filing")
    void csvSubmissionStatusIsNormalised() {
        Fixture f = fixtureReturning("""
                {"generated_at":"2026-07-28T02:00:00","total_count":1,
                 "filing_status":"NOT_FILED_CHANNEL_UNAVAILABLE",
                 "records":[{"txn_id":"1","txn_ref":"PTXN1","report_type":"FX1014",
                             "usd_amount":"750.00","submission_status":"SUBMITTED"}]}
                """);

        String csv = new String(f.client().downloadCsv("BOK_FX1014", FROM, TO),
                java.nio.charset.StandardCharsets.UTF_8);
        f.server().verify();

        assertThat(csv).doesNotContain("SUBMITTED");
        assertThat(csv).contains("NOT_FILED_CHANNEL_UNAVAILABLE");
        assertThat(csv).contains("750.00"); // real data untouched
    }

    @Test
    @DisplayName("an erroring reporting-compliance degrades to an empty list, not to a fabricated run")
    void upstreamErrorYieldsNoRuns() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://reporting-compliance.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("/v1/reports"))).andRespond(withServerError());

        List<ReportRun> runs = new RestReportingClient(builder.build()).listReports(null, FROM, TO);
        server.verify();

        assertThat(runs).isEmpty();
    }
}
