package com.gme.pay.bff.client.rest;

import com.gme.pay.bff.client.WebhookOpsClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link RestWebhookOpsClient} maps notification-webhook's backlog + replay; backlog
 * degrades to UNKNOWN on upstream error.
 */
class RestWebhookOpsClientTest {

    private MockRestServiceServer server;

    private RestWebhookOpsClient newClient() {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        return new RestWebhookOpsClient(builder.build());
    }

    @Test
    void backlog_mapsCounts() {
        RestWebhookOpsClient client = newClient();
        server.expect(requestTo("/v1/webhooks/deliveries/backlog"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"pending\":7,\"dlq\":2}", MediaType.APPLICATION_JSON));

        WebhookOpsClient.WebhookBacklog b = client.backlog();
        server.verify();
        assertThat(b.pending()).isEqualTo(7);
        assertThat(b.dlq()).isEqualTo(2);
        assertThat(b.total()).isEqualTo(9);
    }

    @Test
    void backlog_degradesToUnknownOnError() {
        RestWebhookOpsClient client = newClient();
        server.expect(requestTo("/v1/webhooks/deliveries/backlog"))
                .andExpect(method(GET))
                .andRespond(withServerError());

        WebhookOpsClient.WebhookBacklog b = client.backlog();
        server.verify();
        assertThat(b.unknown()).isTrue();
        assertThat(b.total()).isNull();
    }

    @Test
    void replay_postsToDeliveryEndpoint() {
        RestWebhookOpsClient client = newClient();
        server.expect(requestTo("/v1/webhooks/deliveries/DLV-42/replay"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"status\":\"REQUEUED\",\"detail\":\"ok\"}", MediaType.APPLICATION_JSON));

        WebhookOpsClient.ReplayResult r = client.replay("DLV-42", "ops.admin@gmepay.com");
        server.verify();
        assertThat(r.deliveryId()).isEqualTo("DLV-42");
        assertThat(r.status()).isEqualTo("REQUEUED");
    }
}
