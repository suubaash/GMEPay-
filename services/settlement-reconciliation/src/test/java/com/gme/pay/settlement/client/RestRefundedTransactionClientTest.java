package com.gme.pay.settlement.client;

import com.gme.pay.settlement.port.RefundedTransactionPort.RefundLeg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Maps transaction-mgmt's {@code GET /v1/transactions/refunded?refundedOn=} projection to
 * {@link RefundLeg}, including the ORIGINAL payment txnRef (the cross-date netting key), and
 * fails soft to an empty list on a transport error.
 */
class RestRefundedTransactionClientTest {

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
    private final RestRefundedTransactionClient client =
            new RestRefundedTransactionClient(restTemplate, "http://transaction-mgmt:8082");

    @Test
    @DisplayName("maps refund legs incl. original payment txnRef, keyed by refund date")
    void mapsRefundedProjection() {
        server.expect(requestTo("http://transaction-mgmt:8082/v1/transactions/refunded?refundedOn=2026-06-29"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "[{\"refundTxnRef\":\"RFND-001\",\"originalTxnRef\":\"PAY-900\","
                                + "\"merchantId\":\"MRC001\",\"refundAmount\":\"10000\",\"refundCcy\":\"KRW\","
                                + "\"refundedOn\":\"2026-06-29\",\"refundedAt\":\"2026-06-29T03:15:00Z\"}]",
                        MediaType.APPLICATION_JSON));

        List<RefundLeg> legs = client.findRefundedOn(LocalDate.of(2026, 6, 29));

        assertThat(legs).hasSize(1);
        RefundLeg leg = legs.get(0);
        assertThat(leg.refundTxnRef()).isEqualTo("RFND-001");
        assertThat(leg.originalTxnRef()).isEqualTo("PAY-900");   // netting key carried through
        assertThat(leg.merchantId()).isEqualTo("MRC001");
        assertThat(leg.refundAmountKrw()).isEqualByComparingTo("10000");
        assertThat(leg.refundedOn()).isEqualTo(LocalDate.of(2026, 6, 29));
        assertThat(leg.refundedAt()).isNotNull();
        server.verify();
    }

    @Test
    @DisplayName("negative wire amount is carried as a positive claw-back magnitude")
    void normalisesNegativeAmountToMagnitude() {
        server.expect(requestTo("http://transaction-mgmt:8082/v1/transactions/refunded?refundedOn=2026-06-29"))
                .andRespond(withSuccess(
                        "[{\"refundTxnRef\":\"RFND-002\",\"originalTxnRef\":\"PAY-901\","
                                + "\"merchantId\":\"MRC002\",\"refundAmount\":\"-5000\",\"refundCcy\":\"KRW\","
                                + "\"refundedOn\":\"2026-06-29\"}]",
                        MediaType.APPLICATION_JSON));

        List<RefundLeg> legs = client.findRefundedOn(LocalDate.of(2026, 6, 29));

        assertThat(legs.get(0).refundAmountKrw()).isEqualByComparingTo("5000");
        server.verify();
    }

    @Test
    @DisplayName("transaction-mgmt error → empty list, never throws (settlement run continues)")
    void failsSoftOnError() {
        server.expect(requestTo("http://transaction-mgmt:8082/v1/transactions/refunded?refundedOn=2026-06-29"))
                .andRespond(withServerError());

        List<RefundLeg> legs = client.findRefundedOn(LocalDate.of(2026, 6, 29));

        assertThat(legs).isEmpty();
    }
}
