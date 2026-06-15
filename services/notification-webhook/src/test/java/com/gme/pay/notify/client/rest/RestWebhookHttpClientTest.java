package com.gme.pay.notify.client.rest;

import com.gme.pay.notify.domain.WebhookSender.WebhookDeliveryResult;
import com.gme.pay.notify.domain.WebhookSender.WebhookRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies {@link RestWebhookHttpClient} POSTs with the signed headers and never
 * throws: a 2xx becomes a success result, a 4xx/5xx becomes a non-success result
 * carrying the real status (so the retry/DLQ policy can act on it).
 */
class RestWebhookHttpClientTest {

    private MockRestServiceServer server;

    private RestWebhookHttpClient newClient() {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        return new RestWebhookHttpClient(builder.build());
    }

    private static WebhookRequest sampleRequest() {
        return new WebhookRequest(
                "https://partner.example.com/webhook",
                "{\"event\":\"payment.approved\"}".getBytes(StandardCharsets.UTF_8),
                "sha256=deadbeef",
                "2026-06-15T00:00:00Z",
                "evt_01HX");
    }

    @Test
    void post_2xx_isSuccessWithSignedHeaders() {
        RestWebhookHttpClient client = newClient();
        server.expect(requestTo("https://partner.example.com/webhook"))
                .andExpect(method(POST))
                .andExpect(header("X-GME-Webhook-Signature", "sha256=deadbeef"))
                .andExpect(header("X-GME-Webhook-Timestamp", "2026-06-15T00:00:00Z"))
                .andExpect(header("X-GME-Event-ID", "evt_01HX"))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        WebhookDeliveryResult result = client.post(sampleRequest());
        server.verify();

        assertThat(result.success()).isTrue();
        assertThat(result.httpStatus()).isEqualTo(200);
    }

    @Test
    void post_4xx_isNonSuccessWithStatusNotThrown() {
        RestWebhookHttpClient client = newClient();
        server.expect(requestTo("https://partner.example.com/webhook"))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("rejected"));

        WebhookDeliveryResult result = client.post(sampleRequest());
        server.verify();

        assertThat(result.success()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(400);
        assertThat(result.responseBody()).contains("rejected");
    }
}
