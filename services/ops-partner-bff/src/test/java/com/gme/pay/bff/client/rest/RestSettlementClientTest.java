package com.gme.pay.bff.client.rest;

import com.gme.pay.bff.client.SettlementClient.SettlementBatchSummary;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * GAP T4-5: {@link RestSettlementClient} now reads the PERSISTED settlement record, and cannot
 * fabricate an id, a status or a transmission.
 *
 * <p>The three behaviours this class used to assert were the gap, not the contract: a synthesised
 * {@code batchId} of {@code merchantId-date-type}, a hardcoded {@code status=COMPLETED}, and
 * {@code detail} returning {@code null} for every id because no upstream per-batch endpoint existed.
 * They are asserted here in their fixed form.
 */
class RestSettlementClientTest {

    private MockRestServiceServer server;

    private RestSettlementClient newClient() {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        return new RestSettlementClient(builder.build());
    }

    /** One persisted batch row, as {@code GET /v1/settlements/batches} serves it. */
    private static final String BATCHES_BODY = """
            [{"batchId":"ZP0061-20260609-MORNING","counterpartyId":"ZEROPAY",
              "businessDate":"2026-06-09","status":"RECONCILED","fileType":"ZP0061",
              "settlementWindow":"MORNING","settlementType":"N","settleCurrency":"KRW",
              "netSettlementAmount":"173500","merchantFeeTotal":"1500","recordCount":2,
              "transmissionState":"NOT_TRANSMITTED_CHANNEL_UNAVAILABLE",
              "transmissionDetail":"No settlement transmission channel: endpoint is not set.",
              "transmissionChannel":null,"transmittedAt":null},
             {"batchId":"ZP0061-20260609-AFTERNOON","counterpartyId":"ZEROPAY",
              "businessDate":"2026-06-09","status":"GENERATED","settleCurrency":"KRW",
              "netSettlementAmount":"90000",
              "transmissionState":"NOT_TRANSMITTED_CHANNEL_UNAVAILABLE",
              "transmissionDetail":"No settlement transmission channel: endpoint is not set."}]
            """;

    @Test
    void recent_readsPersistedBatches_withRealIdsAndRealStatuses() {
        RestSettlementClient client = newClient();
        server.expect(requestTo(containsString("/v1/settlements/batches")))
                .andExpect(method(GET))
                .andRespond(withSuccess(BATCHES_BODY, MediaType.APPLICATION_JSON));

        List<SettlementBatchSummary> batches = client.recent(null, 10);
        server.verify();

        assertThat(batches).hasSize(2);
        SettlementBatchSummary first = batches.get(0);
        // The REAL persisted primary key — not "M-100-2026-06-09-N", which matched no row.
        assertThat(first.batchId()).isEqualTo("ZP0061-20260609-MORNING");
        assertThat(first.partnerId()).isEqualTo("ZEROPAY");
        assertThat(first.currency()).isEqualTo("KRW");
        assertThat(first.amount()).isEqualByComparingTo("173500");
        // The REAL lifecycle status — never the invented "COMPLETED".
        assertThat(first.status()).isEqualTo("RECONCILED");
        assertThat(batches.get(1).status()).isEqualTo("GENERATED");
    }

    @Test
    void recent_neverPresentsANeverTransmittedBatchAsTransmitted() {
        RestSettlementClient client = newClient();
        server.expect(requestTo(containsString("/v1/settlements/batches")))
                .andExpect(method(GET))
                .andRespond(withSuccess(BATCHES_BODY, MediaType.APPLICATION_JSON));

        List<SettlementBatchSummary> batches = client.recent(null, 10);
        server.verify();

        assertThat(batches).allSatisfy(b -> {
            assertThat(b.transmitted()).isFalse();
            assertThat(b.transmissionState()).isEqualTo("NOT_TRANSMITTED_CHANNEL_UNAVAILABLE");
            assertThat(b.transmittedAt()).isNull();
            assertThat(b.transmissionReason()).contains("No settlement transmission channel");
        });
    }

    @Test
    void statusVocabulary_isNormalised_andNeverUpgraded() {
        RestSettlementClient client = newClient();
        // An old service that still says COMPLETED, plus a bogus transmission state and a stray
        // transmitted_at. None of it may become a success downstream.
        String body = """
                [{"batchId":"B-1","counterpartyId":"ZEROPAY","businessDate":"2026-06-09",
                  "status":"COMPLETED","settleCurrency":"KRW","netSettlementAmount":"1",
                  "transmissionState":"SENT_PROBABLY","transmittedAt":"2026-06-09T10:00:00Z"},
                 {"batchId":"B-2","counterpartyId":"ZEROPAY","businessDate":"2026-06-09",
                  "settleCurrency":"KRW","netSettlementAmount":"2"}]
                """;
        server.expect(requestTo(containsString("/v1/settlements/batches")))
                .andExpect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<SettlementBatchSummary> batches = client.recent(null, 10);
        server.verify();

        assertThat(batches.get(0).status()).isEqualTo("UNKNOWN");
        assertThat(batches.get(0).transmissionState()).isEqualTo("UNKNOWN");
        assertThat(batches.get(0).transmitted()).isFalse();
        assertThat(batches.get(0).transmittedAt())
                .as("a stray timestamp on a not-transmitted row must not travel downstream")
                .isNull();
        assertThat(batches.get(0).transmissionReason()).contains("invented downstream");

        // An entirely silent row: unknown on both axes, with a reason explaining the silence.
        assertThat(batches.get(1).status()).isEqualTo("UNKNOWN");
        assertThat(batches.get(1).transmissionState()).isEqualTo("UNKNOWN");
        assertThat(batches.get(1).transmissionReason()).contains("did not report a transmissionState");
    }

    @Test
    void range_sendsTheDateWindowUpstream() {
        RestSettlementClient client = newClient();
        server.expect(requestTo(containsString("from=2026-06-01")))
                .andExpect(requestTo(containsString("to=2026-06-30")))
                .andExpect(requestTo(containsString("counterpartyId=ZEROPAY")))
                .andExpect(method(GET))
                .andRespond(withSuccess(BATCHES_BODY, MediaType.APPLICATION_JSON));

        client.range("ZEROPAY", java.time.LocalDate.of(2026, 6, 1),
                java.time.LocalDate.of(2026, 6, 30), 0);
        server.verify();
    }

    @Test
    void detail_readsThePerBatchEndpoint_andMapsLines() {
        RestSettlementClient client = newClient();
        String body = """
                {"batch":{"batchId":"ZP0061-20260609-MORNING","counterpartyId":"ZEROPAY",
                          "businessDate":"2026-06-09","status":"RECEIVED","settleCurrency":"KRW",
                          "netSettlementAmount":"173500",
                          "transmissionState":"NOT_TRANSMITTED_CHANNEL_UNAVAILABLE",
                          "transmissionDetail":"no channel"},
                 "lines":[{"txnRef":"TXN-1","amount":"100000","currency":"KRW","matched":true},
                          {"txnRef":"TXN-2","amount":"73500","currency":"KRW","matched":false}],
                 "matchedCount":1,"openCount":1}
                """;
        server.expect(requestTo(containsString("/v1/settlements/batches/ZP0061-20260609-MORNING")))
                .andExpect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        var detail = client.detail("ZP0061-20260609-MORNING");
        server.verify();

        assertThat(detail).isNotNull();
        assertThat(detail.batch().batchId()).isEqualTo("ZP0061-20260609-MORNING");
        assertThat(detail.batch().status()).isEqualTo("RECEIVED");
        assertThat(detail.lines()).extracting(l -> l.txnRef()).containsExactly("TXN-1", "TXN-2");
        assertThat(detail.matchedCount()).isEqualTo(1);
        assertThat(detail.openCount()).isEqualTo(1);
    }

    @Test
    void detail_returnsNullOnlyWhenTheBatchIsGenuinelyUnknown() {
        RestSettlementClient client = newClient();
        server.expect(requestTo(containsString("/v1/settlements/batches/nope")))
                .andExpect(method(GET))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withResourceNotFound());

        assertThat(client.detail("nope")).isNull();
        server.verify();
        // A blank id never leaves the JVM.
        assertThat(client.detail(" ")).isNull();
    }

    @Test
    void statement_readsThePersistedStatement_andCountsTransmittedItself() {
        RestSettlementClient client = newClient();
        String body = """
                {"merchantId":"MRC-A","from":"2026-06-01","to":"2026-06-30","currency":"KRW",
                 "entries":[{"batch":{"batchId":"ZP0061-20260609-MORNING","counterpartyId":"ZEROPAY",
                                      "businessDate":"2026-06-09","status":"RECONCILED",
                                      "settleCurrency":"KRW","netSettlementAmount":"173500",
                                      "transmissionState":"NOT_TRANSMITTED_CHANNEL_UNAVAILABLE",
                                      "transmissionDetail":"no channel"},
                             "netSettlementAmount":"80000","paymentAmount":"84720",
                             "clawbackAmount":"4720","lineCount":3,"openLineCount":1,
                             "lines":[{"txnRef":"TXN-1","amount":"84720","currency":"KRW","matched":true}]}],
                 "netSettlementAmount":"80000","paymentAmount":"84720","clawbackAmount":"4720",
                 "lineCount":3,"openLineCount":1,"transmittedEntryCount":0,
                 "transmissionChannel":{"channel_live":false,
                     "reachable_state":"NOT_TRANSMITTED_CHANNEL_UNAVAILABLE",
                     "reason":"No settlement transmission channel: endpoint is not set."}}
                """;
        server.expect(requestTo(containsString("/v1/settlements/statement")))
                .andExpect(requestTo(containsString("merchantId=MRC-A")))
                .andExpect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        var statement = client.statement("MRC-A", java.time.LocalDate.of(2026, 6, 1),
                java.time.LocalDate.of(2026, 6, 30), true);
        server.verify();

        assertThat(statement.partnerId()).isEqualTo("MRC-A");
        assertThat(statement.netSettlementAmount()).isEqualByComparingTo("80000");
        assertThat(statement.clawbackAmount()).isEqualByComparingTo("4720");
        assertThat(statement.entries()).hasSize(1);
        assertThat(statement.entries().get(0).lines()).hasSize(1);
        assertThat(statement.transmittedEntryCount()).isZero();
        assertThat(statement.transmissionChannel().live()).isFalse();
        assertThat(statement.transmissionChannel().reason())
                .contains("No settlement transmission channel");
    }

    @Test
    void statement_whenUpstreamIsUnreachable_isEmptyAndSaysWhy() {
        RestSettlementClient client = newClient();
        server.expect(requestTo(containsString("/v1/settlements/statement")))
                .andExpect(method(GET))
                .andRespond(request -> {
                    throw new java.io.IOException("connection refused");
                });

        var statement = client.statement("MRC-A", null, null, true);
        server.verify();

        assertThat(statement.entries()).isEmpty();
        assertThat(statement.netSettlementAmount()).isEqualByComparingTo("0");
        assertThat(statement.transmissionChannel().live()).isFalse();
        assertThat(statement.transmissionChannel().reason())
                .as("an empty statement must say whether it is empty because nothing settled or "
                        + "because nothing could be read")
                .contains("unreachable");
    }

    @Test
    void transmissionChannel_absentBoardIsUNKNOWN_neverAvailable() {
        RestSettlementClient client = newClient();
        server.expect(requestTo(containsString("/v1/settlements/transmission-channel")))
                .andExpect(method(GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        var channel = client.transmissionChannel();
        server.verify();

        assertThat(channel.live()).isFalse();
        assertThat(channel.reachableState()).isEqualTo("UNKNOWN");
        assertThat(channel.reason()).isNotBlank();
    }

    @Test
    void openReconExceptions_countsOpenRowsFromExceptionList() {
        RestSettlementClient client = newClient();
        // ReConExceptionController serves GET /v1/settlement/exceptions (singular) and
        // returns a LIST of exception rows — the client counts the OPEN-filtered rows.
        String body = """
                [{"id":1,"batchId":"B-1","exceptionStatus":"OPEN"},
                 {"id":2,"batchId":"B-2","exceptionStatus":"OPEN"}]
                """;
        server.expect(requestTo(containsString("/v1/settlement/exceptions")))
                .andExpect(requestTo(containsString("exceptionStatus=OPEN")))
                .andExpect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThat(client.openReconExceptions()).isEqualTo(2);
        server.verify();
    }

    @Test
    void rerunRecon_sendsCanonicalFieldNames_andMapsReconRerunResponse() {
        RestSettlementClient client = newClient();
        // ReconRerunResponse's wire shape: operatorId/batchesRerun/totalMatched/totalExceptions.
        String response = """
                {"operatorId":"ops@gmeremit.com","batchesRerun":3,
                 "totalMatched":41,"totalExceptions":2,"batches":[]}
                """;
        server.expect(requestTo(containsString("/v1/settlements/recon/rerun")))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .jsonPath("$.settlementDate").value("2026-07-04"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .jsonPath("$.operatorId").value("ops@gmeremit.com"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .jsonPath("$.reason").value("daily check"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        var result = client.rerunRecon("2026-07-04", "ops@gmeremit.com", "daily check");
        server.verify();

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.matched()).isEqualTo(41);
        assertThat(result.unmatched()).isEqualTo(2);
        assertThat(result.detail()).isEqualTo("batchesRerun=3");
    }
}
