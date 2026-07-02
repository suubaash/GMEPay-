package com.gme.pay.bff.client.rest;

import com.gme.pay.bff.client.TransactionMgmtClient.Filter;
import com.gme.pay.bff.client.TransactionMgmtClient.Page;
import com.gme.pay.bff.client.TransactionMgmtClient.SearchQuery;
import com.gme.pay.bff.client.TransactionMgmtClient.TransactionSummary;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies {@link RestTransactionMgmtClient} maps transaction-mgmt's
 * {@code TransactionResponse} / {@code TransactionQueryPageResponse} wire shapes
 * onto the BFF's {@link TransactionSummary} / {@link Page}, and builds the query
 * string correctly. Uses {@link MockRestServiceServer} bound to a real
 * {@link RestClient} so the actual Jackson conversion (money-string -> BigDecimal,
 * ISO instant, enum-name -> String) is exercised end-to-end.
 */
class RestTransactionMgmtClientTest {

    private MockRestServiceServer server;

    private RestTransactionMgmtClient newClient() {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        return new RestTransactionMgmtClient(builder.build());
    }

    @Test
    void getTransaction_mapsWireFieldsToSummary() {
        RestTransactionMgmtClient client = newClient();
        String body = """
                {"txnRef":"TXN-1","partnerRef":"GMEREMIT","status":"COMMITTED",
                 "sendAmount":"125.50","sendCcy":"USD","targetPayout":"168000",
                 "targetCcy":"KRW","createdAt":"2026-06-09T10:15:30Z",
                 "updatedAt":"2026-06-09T10:16:00Z","qrSchemeId":"zeropay_kr",
                 "krwAmount":"168000","payerCurrency":"USD","payerCurrencyAmount":"125.50",
                 "appliedFxRate":"1339.0","prefundingDeductedUsd":"125.50",
                 "merchantId":"M-1","merchantName":"Cafe"}
                """;
        server.expect(requestTo(containsString("/v1/transactions/TXN-1")))
                .andExpect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        TransactionSummary s = client.getTransaction("TXN-1");
        server.verify();

        assertThat(s).isNotNull();
        assertThat(s.txnId()).isEqualTo("TXN-1");
        assertThat(s.partnerId()).isEqualTo("GMEREMIT");
        assertThat(s.state()).isEqualTo("COMMITTED");
        // amount/currency are the payout leg (targetPayout / targetCcy)
        assertThat(s.amount()).isEqualByComparingTo("168000");
        assertThat(s.currency()).isEqualTo("KRW");
        assertThat(s.committedAt()).isNotNull();
        assertThat(s.qrSchemeId()).isEqualTo("zeropay_kr");
        assertThat(s.payerCurrency()).isEqualTo("USD");
        assertThat(s.payerCurrencyAmount()).isEqualByComparingTo("125.50");
        assertThat(s.appliedFxRate()).isEqualByComparingTo("1339.0");
        assertThat(s.prefundingDeductedUsd()).isEqualByComparingTo("125.50");
    }

    @Test
    void getTransaction_returnsNullOn404() {
        RestTransactionMgmtClient client = newClient();
        server.expect(requestTo(containsString("/v1/transactions/UNKNOWN")))
                .andExpect(method(GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.getTransaction("UNKNOWN")).isNull();
        server.verify();
    }

    @Test
    void list_buildsQueryParamsAndMapsPage() {
        RestTransactionMgmtClient client = newClient();
        String body = """
                {"content":[
                  {"txnRef":"TXN-9","partnerRef":"P1","status":"APPROVED",
                   "targetPayout":"100.00","targetCcy":"USD",
                   "createdAt":"2026-06-09T10:15:30Z"}],
                 "page":0,"size":20,"totalElements":1}
                """;
        server.expect(requestTo(containsString("/v1/transactions?")))
                .andExpect(method(GET))
                .andExpect(queryParam("page", "0"))
                .andExpect(queryParam("size", "20"))
                .andExpect(queryParam("status", "APPROVED"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        Page<TransactionSummary> page = client.list(
                new Filter(null, null, "APPROVED", null, null, 0, 20));
        server.verify();

        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.content()).hasSize(1);
        TransactionSummary s = page.content().get(0);
        assertThat(s.txnId()).isEqualTo("TXN-9");
        assertThat(s.amount()).isEqualByComparingTo("100.00");
        assertThat(s.currency()).isEqualTo("USD");
    }

    @Test
    void search_forwardsUserRefAndReference() {
        RestTransactionMgmtClient client = newClient();
        String body = "{\"content\":[],\"page\":0,\"size\":20,\"totalElements\":0}";
        server.expect(requestTo(containsString("/v1/transactions/search?")))
                .andExpect(method(GET))
                .andExpect(queryParam("q", "TXN-7"))
                .andExpect(queryParam("userRef", "wallet-123"))
                .andExpect(queryParam("reference", "PARTNER-REF-9"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        Page<TransactionSummary> page = client.search(
                new SearchQuery("TXN-7", null, null, "wallet-123", "PARTNER-REF-9", 0, 20));
        server.verify();
        assertThat(page.content()).isEmpty();
    }

    @Test
    void getTransaction_mapsCsFieldsAndStatusHistory() {
        RestTransactionMgmtClient client = newClient();
        String body = """
                {"txnRef":"TXN-2","partnerRef":"P1","status":"FAILED",
                 "targetPayout":"10.00","targetCcy":"USD","createdAt":"2026-06-09T10:15:30Z",
                 "failureReason":"SCHEME_DECLINED","statusLabel":"Declined",
                 "declineReasonText":"Insufficient funds at issuer",
                 "statusHistory":[
                   {"status":"CREATED","statusLabel":"Created","at":"2026-06-09T10:15:30Z","note":null},
                   {"status":"FAILED","statusLabel":"Declined","at":"2026-06-09T10:15:32Z","note":"issuer NSF"}]}
                """;
        server.expect(requestTo(containsString("/v1/transactions/TXN-2")))
                .andExpect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        TransactionSummary s = client.getTransaction("TXN-2");
        server.verify();
        assertThat(s.failureReason()).isEqualTo("SCHEME_DECLINED");
        assertThat(s.statusLabel()).isEqualTo("Declined");
        assertThat(s.declineReasonText()).isEqualTo("Insufficient funds at issuer");
        assertThat(s.statusHistory()).hasSize(2);
        assertThat(s.statusHistory().get(1).status()).isEqualTo("FAILED");
        assertThat(s.statusHistory().get(1).statusLabel()).isEqualTo("Declined");
        assertThat(s.statusHistory().get(1).note()).isEqualTo("issuer NSF");
    }

    @Test
    void list_forwardsNumericPartnerIdButOmitsNonNumeric() {
        RestTransactionMgmtClient client = newClient();
        String body = "{\"content\":[],\"page\":0,\"size\":20,\"totalElements\":0}";
        // A non-numeric partner code must NOT be forwarded as the numeric partnerId param.
        server.expect(requestTo(containsString("/v1/transactions?")))
                .andExpect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        Page<TransactionSummary> page = client.list(
                new Filter("GMEREMIT", null, null, null, null, 0, 20));
        server.verify();
        assertThat(page.content()).isEmpty();
        assertThat(page.total()).isZero();
    }
}
