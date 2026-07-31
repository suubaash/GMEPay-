package com.gme.pay.payment.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gme.pay.contracts.events.OpsAlertPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The one concrete notification sink (T3-3): a generic JSON POST to the single URL an operator
 * configures. No real HTTP server and no vendor account involved — {@code MockRestServiceServer}, the
 * same way ops-partner-bff's {@code WebhookPagingAdapterTest} covers the twin adapter it mirrors.
 */
class WebhookAlertSinkTest {

    private static final String URL = "https://oncall.example/hook";

    private static OpsAlertPayload sample() {
        return new OpsAlertPayload(OpsAlertPayload.EVENT_TYPE, "DECLINE_SPIKE", "CRITICAL",
                "PTN-ACME", "declineRate=1.00 (25/25) over 60s > threshold=0.50",
                "2026-07-28T18:30:00Z");
    }

    @Test
    @DisplayName("POSTs the stable OpsAlertPayload shape to the configured URL")
    void postsRightShapeToConfiguredUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WebhookAlertSink sink = new WebhookAlertSink(builder.build(), URL, 3);

        server.expect(requestTo(URL))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.eventType").value("ops.alert"))
                .andExpect(jsonPath("$.alertType").value("DECLINE_SPIKE"))
                .andExpect(jsonPath("$.severity").value("CRITICAL"))
                .andExpect(jsonPath("$.subjectRef").value("PTN-ACME"))
                .andExpect(jsonPath("$.occurredAt").value("2026-07-28T18:30:00Z"))
                .andRespond(withSuccess());

        AlertDelivery outcome = sink.deliver(sample());

        server.verify();
        assertThat(outcome.status()).isEqualTo(AlertDelivery.Status.DELIVERED);
        assertThat(outcome.channel()).isEqualTo(WebhookAlertSink.CHANNEL);
    }

    @Test
    @DisplayName("retries a 5xx and reports success once it lands")
    void retriesOn5xxThenSucceeds() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WebhookAlertSink sink = new WebhookAlertSink(builder.build(), URL, 3);

        server.expect(requestTo(URL)).andExpect(method(POST)).andRespond(withServerError());
        server.expect(requestTo(URL)).andExpect(method(POST)).andRespond(withSuccess());

        AlertDelivery outcome = sink.deliver(sample());

        server.verify();
        assertThat(outcome.status()).isEqualTo(AlertDelivery.Status.DELIVERED);
    }

    @Test
    @DisplayName("exhausted retries → FAILED, never an exception into the monitor")
    void failsAfterExhaustingRetriesWithoutThrowing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WebhookAlertSink sink = new WebhookAlertSink(builder.build(), URL, 2);

        server.expect(requestTo(URL)).andExpect(method(POST)).andRespond(withServerError());
        server.expect(requestTo(URL)).andExpect(method(POST)).andRespond(withServerError());

        AlertDelivery outcome = sink.deliver(sample());

        server.verify();
        assertThat(outcome.status()).isEqualTo(AlertDelivery.Status.FAILED);
        assertThat(outcome.error()).isNotBlank();
    }

    @Test
    @DisplayName("4xx is a permanent config error and is NOT retried")
    void doesNotRetry4xx() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WebhookAlertSink sink = new WebhookAlertSink(builder.build(), URL, 3);

        // Exactly ONE expectation: a second call would fail verification.
        server.expect(requestTo(URL)).andExpect(method(POST)).andRespond(withBadRequest());

        AlertDelivery outcome = sink.deliver(sample());

        server.verify();
        assertThat(outcome.status()).isEqualTo(AlertDelivery.Status.FAILED);
        assertThat(outcome.error()).isEqualTo("http 400");
    }

    @Test
    @DisplayName("log sink is the zero-config default and always reports delivered")
    void logSinkIsTheSafeDefault() {
        AlertDelivery outcome = new LogAlertSink().deliver(sample());

        assertThat(outcome.status()).isEqualTo(AlertDelivery.Status.DELIVERED);
        assertThat(outcome.channel()).isEqualTo(LogAlertSink.CHANNEL);
    }
}
