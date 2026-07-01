package com.gme.pay.bff.alert.paging;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link WebhookPagingAdapter} POSTs the stable {@link PageRequest} shape to the configured
 * URL and retries on 5xx (MockRestServiceServer — no real broker/HTTP server).
 */
class WebhookPagingAdapterTest {

    private static final String URL = "https://oncall.example/hook";

    private static PageRequest sample() {
        return new PageRequest("STUCK_TXN", "CRITICAL", "TXN-9", "stuck 30m",
                "2026-07-02T00:00:00Z", null);
    }

    @Test
    void postsRightShapeToConfiguredUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WebhookPagingAdapter adapter = new WebhookPagingAdapter(builder.build(), URL, 3);

        server.expect(requestTo(URL))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.alertType").value("STUCK_TXN"))
                .andExpect(jsonPath("$.severity").value("CRITICAL"))
                .andExpect(jsonPath("$.subjectRef").value("TXN-9"))
                .andExpect(jsonPath("$.detail").value("stuck 30m"))
                .andExpect(jsonPath("$.occurredAt").value("2026-07-02T00:00:00Z"))
                .andRespond(withSuccess());

        PagingPort.PageOutcome outcome = adapter.page(sample());
        server.verify();
        assertThat(outcome.delivered()).isTrue();
        assertThat(outcome.channel()).isEqualTo("webhook");
    }

    @Test
    void retriesOn5xxThenSucceeds() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WebhookPagingAdapter adapter = new WebhookPagingAdapter(builder.build(), URL, 3);

        server.expect(requestTo(URL)).andExpect(method(POST)).andRespond(withServerError());
        server.expect(requestTo(URL)).andExpect(method(POST)).andRespond(withSuccess());

        PagingPort.PageOutcome outcome = adapter.page(sample());
        server.verify();
        assertThat(outcome.delivered()).isTrue();
    }

    @Test
    void failsAfterExhaustingRetries() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WebhookPagingAdapter adapter = new WebhookPagingAdapter(builder.build(), URL, 2);

        server.expect(requestTo(URL)).andExpect(method(POST)).andRespond(withServerError());
        server.expect(requestTo(URL)).andExpect(method(POST)).andRespond(withServerError());

        PagingPort.PageOutcome outcome = adapter.page(sample());
        server.verify();
        assertThat(outcome.delivered()).isFalse();
    }
}
